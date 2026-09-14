package com.remmi.browser.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import com.remmi.browser.security.crypto.MasterPasswordStrength
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import com.remmi.browser.storage.MasterKeyMetadataEntity
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.PasswordEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import org.mozilla.geckoview.Autocomplete

data class AutofillCandidate(
  val id: Long,
  val url: String,
  val username: String,
)

sealed class VaultLockState {
  object Uninitialized : VaultLockState()
  object Locked : VaultLockState()
  data class Unlocked(val dek: ByteArray) : VaultLockState()
  data class TemporarilyLocked(val remainingSeconds: Int, val totalSeconds: Int) : VaultLockState()
  object CompromisedDevice : VaultLockState()
}

data class VaultSecurityScore(
  val score: Int, // 0..100
  val isMasterPasswordStrong: Boolean,
  val isBiometricEnabled: Boolean,
  val totalAccounts: Int,
  val weakPasswordsCount: Int,
  val reusedPasswordsCount: Int,
  val securityGrade: String,
)

/**
 * High-Security Password Manager Repository.
 * Manages zero-knowledge cryptography, hardware Keystore integration, rate limiting,
 * brute-force auto-wipe, and memory-safe credentials management.
 */
class PasswordManagerRepository private constructor(
  private val context: Context,
  
) {
  private suspend fun getDb(): RemmiDatabase = RemmiDatabase.getDatabaseAsync(context)

  private val scope = CoroutineScope(Dispatchers.IO + Job())
  private val prefs: SharedPreferences = context.getSharedPreferences("remmi_pm_secure_state", Context.MODE_PRIVATE)

  private val _lockState = MutableStateFlow<VaultLockState>(VaultLockState.Locked)
  val lockState: StateFlow<VaultLockState> = _lockState.asStateFlow()

  private val _failedAttempts = MutableStateFlow(0)
  val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

  private val _lockoutSecondsRemaining = MutableStateFlow(0)
  val lockoutSecondsRemaining: StateFlow<Int> = _lockoutSecondsRemaining.asStateFlow()

  private val saveMutex = Mutex()
  private val recentSaveTimes = ConcurrentHashMap<String, Long>()

  private var countdownJob: Job? = null

  companion object {
    private const val TAG = "PasswordManagerRepo"
    const val FORT_KNOX_PACKAGE = "com.aistudio.fortknox.secx"

    private const val KEY_FAILED_ATTEMPTS = "pm_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL_TIMESTAMP = "pm_lockout_until"

    @Volatile
    private var INSTANCE: PasswordManagerRepository? = null

    fun getInstance(context: Context): PasswordManagerRepository {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: PasswordManagerRepository(context.applicationContext).also {
          INSTANCE = it
        }
      }
    }

    fun resetInstance() {
      synchronized(this) {
        INSTANCE = null
      }
    }
  }

  init {
    checkInitialState()
  }

  fun checkInitialState() {
    scope.launch {
      val integrity = TamperDetection.checkIntegrity(context)
      if (integrity.isRootDetected) {
        _lockState.value = VaultLockState.CompromisedDevice
        return@launch
      }

      val savedFailed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
      val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL_TIMESTAMP, 0L)
      val now = System.currentTimeMillis()

      _failedAttempts.value = savedFailed

      if (lockoutUntil > now) {
        val remainingSec = ((lockoutUntil - now) / 1000).toInt()
        startLockoutCountdown(remainingSec, remainingSec)
      } else {
        _lockState.value = VaultLockState.Locked
      }

      // Defer database work until database state becomes READY
      RemmiDatabase.databaseState.collect { dbState ->
        if (dbState is RemmiDatabase.DatabaseState.Ready) {
          try {
            val metadata = dbState.database.masterKeyMetadataDao().getMetadata()
            if (metadata == null) {
              // Auto-initialize device keystore vault so passwords can be saved and autofilled seamlessly
              try {
                val dek = PasswordCryptoEngine.generateSecureRandomBytes(PasswordCryptoEngine.KEY_LENGTH_BYTES)
                val wrapped = PasswordCryptoEngine.wrapDekWithDeviceKey(dek)
                val newMeta = MasterKeyMetadataEntity(
                  id = 1,
                  encryptedDek = wrapped.ciphertext,
                  dekIv = wrapped.iv,
                  dekAuthTag = wrapped.authTag,
                  kdfSalt = ByteArray(0),
                  kdfParams = "DEVICE_KEYSTORE",
                  verifier = ByteArray(0),
                  verifierSalt = ByteArray(0),
                )
                dbState.database.masterKeyMetadataDao().saveMetadata(newMeta)
                _lockState.value = VaultLockState.Unlocked(dek)
                Log.i(TAG, "Device Keystore vault automatically initialized and unlocked.")
              } catch (e: Exception) {
                Log.w(TAG, "Failed auto-initializing device keystore vault: ${e.message}")
                _lockState.value = VaultLockState.Uninitialized
              }
            } else if (metadata.kdfParams == "DEVICE_KEYSTORE") {
              try {
                val dek = PasswordCryptoEngine.unwrapDekWithDeviceKey(metadata.encryptedDek, metadata.dekIv, metadata.dekAuthTag)
                _lockState.value = VaultLockState.Unlocked(dek)
                Log.i(TAG, "Device Keystore vault unlocked successfully.")
              } catch (e: Exception) {
                Log.w(TAG, "Failed unwrapping device DEK: ${e.message}, regenerating fresh device DEK...")
                try {
                  val dek = PasswordCryptoEngine.generateSecureRandomBytes(PasswordCryptoEngine.KEY_LENGTH_BYTES)
                  val wrapped = PasswordCryptoEngine.wrapDekWithDeviceKey(dek)
                  val newMeta = metadata.copy(
                    encryptedDek = wrapped.ciphertext,
                    dekIv = wrapped.iv,
                    dekAuthTag = wrapped.authTag
                  )
                  dbState.database.masterKeyMetadataDao().saveMetadata(newMeta)
                  _lockState.value = VaultLockState.Unlocked(dek)
                } catch (_: Exception) {
                  _lockState.value = VaultLockState.Uninitialized
                }
              }
            } else {
              // User has configured a custom Master Password / PIN
              _lockState.value = VaultLockState.Locked
            }
          } catch (_: Throwable) {}
        }
      }
    }
  }

  // --- Master Password & PIN Setup ---
  suspend fun setupMasterPassword(
    password: CharArray,
    pin: CharArray? = null,
    enableBiometrics: Boolean = false,
    autoWipe: Boolean = true,
  ): Boolean = withContext(Dispatchers.IO) {
    if (!PasswordCryptoEngine.isPasswordValid(password)) {
      return@withContext false
    }
    if (pin != null && pin.isNotEmpty() && !PasswordCryptoEngine.isPinValid(pin)) {
      return@withContext false
    }

    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(password)
    val dek = PasswordCryptoEngine.generateSecureRandomBytes(PasswordCryptoEngine.KEY_LENGTH_BYTES)

    var pinKdfResult: com.remmi.browser.security.crypto.MasterKeyDerivationResult? = null
    var pinEncryptedDek: com.remmi.browser.security.crypto.AesGcmCiphertext? = null
    var pinVerifierPair: Pair<ByteArray, ByteArray>? = null

    try {
      val encryptedDek = PasswordCryptoEngine.encryptAesGcm(kdfResult.kek, dek)
      val (verifier, verifierSalt) = PasswordCryptoEngine.computeVerifier(kdfResult.kek)

      if (pin != null && pin.isNotEmpty()) {
        pinKdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(pin)
        pinEncryptedDek = PasswordCryptoEngine.encryptAesGcm(pinKdfResult.kek, dek)
        pinVerifierPair = PasswordCryptoEngine.computeVerifier(pinKdfResult.kek)
      }

      var biometricWrappedDek: ByteArray? = null
      var biometricIv: ByteArray? = null
      var biometricAuthTag: ByteArray? = null

      if (enableBiometrics) {
        try {
          val key = PasswordCryptoEngine.getOrCreateBiometricKeystoreKey()
          val cipher = Cipher.getInstance("AES/GCM/NoPadding")
          cipher.init(Cipher.ENCRYPT_MODE, key)
          val wrapped = PasswordCryptoEngine.wrapDekWithBiometric(dek, cipher)
          biometricWrappedDek = wrapped.ciphertext
          biometricIv = wrapped.iv
          biometricAuthTag = wrapped.authTag
        } catch (e: Exception) {
          Log.w(TAG, "Biometric Keystore init deferred: ${e.message}")
        }
      }

      val metadata = MasterKeyMetadataEntity(
        id = 1,
        encryptedDek = encryptedDek.ciphertext,
        dekIv = encryptedDek.iv,
        dekAuthTag = encryptedDek.authTag,
        kdfSalt = kdfResult.salt,
        kdfParams = kdfResult.paramsDescription,
        verifier = verifier,
        verifierSalt = verifierSalt,
        biometricWrappedDek = biometricWrappedDek,
        biometricIv = biometricIv,
        biometricAuthTag = biometricAuthTag,
        biometricEnabled = enableBiometrics && biometricWrappedDek != null,
        pinEnabled = pinEncryptedDek != null,
        pinEncryptedDek = pinEncryptedDek?.ciphertext,
        pinDekIv = pinEncryptedDek?.iv,
        pinDekAuthTag = pinEncryptedDek?.authTag,
        pinKdfSalt = pinKdfResult?.salt,
        pinKdfParams = pinKdfResult?.paramsDescription,
        pinVerifier = pinVerifierPair?.first,
        pinVerifierSalt = pinVerifierPair?.second,
        autoWipeEnabled = autoWipe,
      )

      getDb().masterKeyMetadataDao().saveMetadata(metadata)
      resetFailedAttempts()

      _lockState.value = VaultLockState.Unlocked(dek.copyOf())
      return@withContext true
    } finally {
      PasswordCryptoEngine.zeroize(kdfResult.kek)
      if (pinKdfResult != null) {
        PasswordCryptoEngine.zeroize(pinKdfResult.kek)
      }
    }
  }

  // --- Master PIN Management ---
  suspend fun setupMasterPin(pin: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) {
      return@withContext Result.failure(IllegalStateException("Vault must be unlocked to configure PIN."))
    }
    if (!PasswordCryptoEngine.isPinValid(pin)) {
      return@withContext Result.failure(IllegalArgumentException("Invalid PIN format."))
    }

    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault uninitialized."))

    val pinKdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(pin)
    try {
      val pinEnc = PasswordCryptoEngine.encryptAesGcm(pinKdfResult.kek, state.dek)
      val (verifier, verifierSalt) = PasswordCryptoEngine.computeVerifier(pinKdfResult.kek)

      val updated = metadata.copy(
        pinEnabled = true,
        pinEncryptedDek = pinEnc.ciphertext,
        pinDekIv = pinEnc.iv,
        pinDekAuthTag = pinEnc.authTag,
        pinKdfSalt = pinKdfResult.salt,
        pinKdfParams = pinKdfResult.paramsDescription,
        pinVerifier = verifier,
        pinVerifierSalt = verifierSalt,
      )
      getDb().masterKeyMetadataDao().saveMetadata(updated)
      return@withContext Result.success(Unit)
    } catch (e: Exception) {
      return@withContext Result.failure(e)
    } finally {
      PasswordCryptoEngine.zeroize(pinKdfResult.kek)
    }
  }

  suspend fun removeMasterPin(): Boolean = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata() ?: return@withContext false
    val updated = metadata.copy(
      pinEnabled = false,
      pinEncryptedDek = null,
      pinDekIv = null,
      pinDekAuthTag = null,
      pinKdfSalt = null,
      pinKdfParams = null,
      pinVerifier = null,
      pinVerifierSalt = null,
    )
    getDb().masterKeyMetadataDao().saveMetadata(updated)
    return@withContext true
  }

  // --- Master Password Unlock ---
  suspend fun unlockWithMasterPassword(password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
    val currentLock = _lockState.value
    if (currentLock is VaultLockState.TemporarilyLocked) {
      return@withContext Result.failure(IllegalStateException("Vault locked for ${currentLock.remainingSeconds}s due to failed attempts."))
    }
    if (currentLock is VaultLockState.CompromisedDevice) {
      return@withContext Result.failure(SecurityException("Device integrity compromised. Password vault blocked."))
    }

    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault uninitialized."))

    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(password, metadata.kdfSalt)

    try {
      val isVerified = PasswordCryptoEngine.verifyKek(kdfResult.kek, metadata.verifier, metadata.verifierSalt)
      if (!isVerified) {
        handleFailedAttempt(metadata.autoWipeEnabled)
        return@withContext Result.failure(SecurityException("Incorrect Master Password."))
      }

      // Decrypt DEK
      val dek = PasswordCryptoEngine.decryptAesGcm(
        key = kdfResult.kek,
        ciphertext = metadata.encryptedDek,
        iv = metadata.dekIv,
        authTag = metadata.dekAuthTag,
      )

      resetFailedAttempts()
      _lockState.value = VaultLockState.Unlocked(dek)
      return@withContext Result.success(Unit)
    } catch (e: Exception) {
      handleFailedAttempt(metadata.autoWipeEnabled)
      return@withContext Result.failure(SecurityException("Decryption error: ${e.message}"))
    } finally {
      PasswordCryptoEngine.zeroize(kdfResult.kek)
    }
  }

  // --- Master PIN Unlock ---
  suspend fun unlockWithMasterPin(pin: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
    val currentLock = _lockState.value
    if (currentLock is VaultLockState.TemporarilyLocked) {
      return@withContext Result.failure(IllegalStateException("Vault locked for ${currentLock.remainingSeconds}s due to failed attempts."))
    }
    if (currentLock is VaultLockState.CompromisedDevice) {
      return@withContext Result.failure(SecurityException("Device integrity compromised. Password vault blocked."))
    }

    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault uninitialized."))

    if (!metadata.pinEnabled || metadata.pinEncryptedDek == null || metadata.pinKdfSalt == null ||
      metadata.pinVerifier == null || metadata.pinVerifierSalt == null || metadata.pinDekIv == null || metadata.pinDekAuthTag == null
    ) {
      return@withContext Result.failure(IllegalStateException("Master PIN is not enabled."))
    }

    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(pin, metadata.pinKdfSalt)
    try {
      val isVerified = PasswordCryptoEngine.verifyKek(kdfResult.kek, metadata.pinVerifier, metadata.pinVerifierSalt)
      if (!isVerified) {
        handleFailedAttempt(metadata.autoWipeEnabled)
        return@withContext Result.failure(SecurityException("Incorrect Master PIN."))
      }

      val dek = PasswordCryptoEngine.decryptAesGcm(
        key = kdfResult.kek,
        ciphertext = metadata.pinEncryptedDek,
        iv = metadata.pinDekIv,
        authTag = metadata.pinDekAuthTag,
      )

      resetFailedAttempts()
      _lockState.value = VaultLockState.Unlocked(dek)
      return@withContext Result.success(Unit)
    } catch (e: Exception) {
      handleFailedAttempt(metadata.autoWipeEnabled)
      return@withContext Result.failure(SecurityException("PIN decryption error: ${e.message}"))
    } finally {
      PasswordCryptoEngine.zeroize(kdfResult.kek)
    }
  }

  // --- Destructive Action Authentication Verifier ---
  suspend fun verifyMasterCredentialForDestructiveAction(
    password: CharArray? = null,
    pin: CharArray? = null,
  ): Boolean = withContext(Dispatchers.IO) {
    val currentLock = _lockState.value
    if (currentLock is VaultLockState.TemporarilyLocked) {
      return@withContext false
    }

    val metadata = getDb().masterKeyMetadataDao().getMetadata() ?: return@withContext false

    if (password != null && password.isNotEmpty()) {
      val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(password, metadata.kdfSalt)
      try {
        val isVerified = PasswordCryptoEngine.verifyKek(kdfResult.kek, metadata.verifier, metadata.verifierSalt)
        if (isVerified) {
          resetFailedAttempts()
          return@withContext true
        } else {
          handleFailedAttempt(metadata.autoWipeEnabled)
          return@withContext false
        }
      } finally {
        PasswordCryptoEngine.zeroize(kdfResult.kek)
      }
    } else if (pin != null && pin.isNotEmpty() && metadata.pinEnabled && metadata.pinKdfSalt != null && metadata.pinVerifier != null && metadata.pinVerifierSalt != null) {
      val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(pin, metadata.pinKdfSalt)
      try {
        val isVerified = PasswordCryptoEngine.verifyKek(kdfResult.kek, metadata.pinVerifier, metadata.pinVerifierSalt)
        if (isVerified) {
          resetFailedAttempts()
          return@withContext true
        } else {
          handleFailedAttempt(metadata.autoWipeEnabled)
          return@withContext false
        }
      } finally {
        PasswordCryptoEngine.zeroize(kdfResult.kek)
      }
    }

    return@withContext false
  }

  suspend fun getMasterKeyMetadata(): MasterKeyMetadataEntity? = withContext(Dispatchers.IO) {
    getDb().masterKeyMetadataDao().getMetadata()
  }

  fun isBiometricAvailable(): Boolean {
    return try {
      val biometricManager = androidx.biometric.BiometricManager.from(context)
      val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
      biometricManager.canAuthenticate(authenticators) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
      false
    }
  }

  fun prepareBiometricEncryptCipher(): Result<Cipher> {
    return try {
      val key = PasswordCryptoEngine.getOrCreateBiometricKeystoreKey()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key)
      Result.success(cipher)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun prepareBiometricDecryptCipher(): Result<Cipher> = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault uninitialized."))

    val iv = metadata.biometricIv
      ?: return@withContext Result.failure(IllegalStateException("Biometric unlock not initialized."))

    try {
      val key = PasswordCryptoEngine.getOrCreateBiometricKeystoreKey()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
      cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
      Result.success(cipher)
    } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
      Log.w(TAG, "Biometric key invalidated by biometric enrollment change: ${e.message}")
      // Mark biometric as disabled so the user must use Master Password or PIN and re-enroll
      val updated = metadata.copy(biometricEnabled = false, biometricWrappedDek = null, biometricIv = null, biometricAuthTag = null)
      getDb().masterKeyMetadataDao().saveMetadata(updated)
      Result.failure(SecurityException("Biometrics changed. Please unlock with Master Password or PIN to re-enroll."))
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  // --- Biometric Unlock ---
  suspend fun unlockWithBiometric(biometricCipher: Cipher): Result<Unit> = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault uninitialized."))

    val wrapped = metadata.biometricWrappedDek
    val authTag = metadata.biometricAuthTag
    if (wrapped == null || authTag == null) {
      return@withContext Result.failure(IllegalStateException("Biometric unlock not configured."))
    }

    try {
      val dek = PasswordCryptoEngine.unwrapDekWithBiometric(biometricCipher, wrapped, authTag)
      resetFailedAttempts()
      _lockState.value = VaultLockState.Unlocked(dek)
      return@withContext Result.success(Unit)
    } catch (e: Exception) {
      return@withContext Result.failure(SecurityException("Biometric decryption error: ${e.message}"))
    }
  }

  suspend fun enableBiometricUnlock(biometricCipher: Cipher): Boolean = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext false
    val metadata = getDb().masterKeyMetadataDao().getMetadata() ?: return@withContext false

    try {
      val wrapped = PasswordCryptoEngine.wrapDekWithBiometric(state.dek, biometricCipher)
      val updated = metadata.copy(
        biometricWrappedDek = wrapped.ciphertext,
        biometricIv = wrapped.iv,
        biometricAuthTag = wrapped.authTag,
        biometricEnabled = true,
      )
      getDb().masterKeyMetadataDao().saveMetadata(updated)
      return@withContext true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to enable biometric: ${e.message}")
      return@withContext false
    }
  }

  suspend fun disableBiometricUnlock(): Boolean = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata() ?: return@withContext false
    val updated = metadata.copy(
      biometricEnabled = false,
      biometricWrappedDek = null,
      biometricIv = null,
      biometricAuthTag = null,
    )
    getDb().masterKeyMetadataDao().saveMetadata(updated)
    return@withContext true
  }

  suspend fun enableBiometricsWithCipher(biometricCipher: Cipher): Boolean = enableBiometricUnlock(biometricCipher)

  suspend fun disableBiometrics(): Boolean = disableBiometricUnlock()

  suspend fun setAutoWipe(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata() ?: return@withContext false
    val updated = metadata.copy(autoWipeEnabled = enabled)
    getDb().masterKeyMetadataDao().saveMetadata(updated)
    return@withContext true
  }

  // --- Rate Limiting & Brute Force Lockout ---
  private suspend fun handleFailedAttempt(autoWipeEnabled: Boolean) {
    val newCount = _failedAttempts.value + 1
    _failedAttempts.value = newCount
    prefs.edit().putInt(KEY_FAILED_ATTEMPTS, newCount).apply()

    when {
      newCount >= 10 && autoWipeEnabled -> {
        Log.w(TAG, "10 failed attempts reached. Executing auto-wipe protocol.")
        wipeAllVaultData()
      }
      newCount >= 5 -> {
        // 5 minutes lockout
        val duration = 300
        val lockoutUntil = System.currentTimeMillis() + (duration * 1000L)
        prefs.edit().putLong(KEY_LOCKOUT_UNTIL_TIMESTAMP, lockoutUntil).apply()
        startLockoutCountdown(duration, duration)
      }
      newCount >= 3 -> {
        // 30 seconds lockout
        val duration = 30
        val lockoutUntil = System.currentTimeMillis() + (duration * 1000L)
        prefs.edit().putLong(KEY_LOCKOUT_UNTIL_TIMESTAMP, lockoutUntil).apply()
        startLockoutCountdown(duration, duration)
      }
    }
  }

  private fun startLockoutCountdown(remainingSec: Int, totalSec: Int) {
    countdownJob?.cancel()
    countdownJob = scope.launch {
      var current = remainingSec
      while (current > 0) {
        _lockoutSecondsRemaining.value = current
        _lockState.value = VaultLockState.TemporarilyLocked(current, totalSec)
        delay(1000L)
        current--
      }
      _lockoutSecondsRemaining.value = 0
      prefs.edit().remove(KEY_LOCKOUT_UNTIL_TIMESTAMP).apply()
      _lockState.value = VaultLockState.Locked
    }
  }

  private fun resetFailedAttempts() {
    _failedAttempts.value = 0
    _lockoutSecondsRemaining.value = 0
    prefs.edit()
      .putInt(KEY_FAILED_ATTEMPTS, 0)
      .remove(KEY_LOCKOUT_UNTIL_TIMESTAMP)
      .apply()
  }

  suspend fun hasCustomMasterPassword(): Boolean = withContext(Dispatchers.IO) {
    try {
      val meta = getDb().masterKeyMetadataDao().getMetadata() ?: return@withContext false
      return@withContext meta.kdfParams != "DEVICE_KEYSTORE" && meta.verifier.isNotEmpty()
    } catch (_: Exception) {
      false
    }
  }

  // --- Vault Lock / Zeroize ---
  fun lockVault() {
    scope.launch(Dispatchers.IO) {
      val state = _lockState.value
      if (state is VaultLockState.Unlocked) {
        PasswordCryptoEngine.zeroize(state.dek)
      }
      _lockState.value = VaultLockState.Locked
    }
  }

  suspend fun unlockWithDeviceBiometrics(): Result<Unit> = withContext(Dispatchers.IO) {
    val currentLock = _lockState.value
    if (currentLock is VaultLockState.TemporarilyLocked) {
      return@withContext Result.failure(IllegalStateException("Vault locked for ${currentLock.remainingSeconds}s due to failed attempts."))
    }
    if (currentLock is VaultLockState.CompromisedDevice) {
      return@withContext Result.failure(SecurityException("Device integrity compromised. Password vault blocked."))
    }

    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault uninitialized."))

    try {
      val dek = if (metadata.kdfParams == "DEVICE_KEYSTORE") {
        PasswordCryptoEngine.unwrapDekWithDeviceKey(metadata.encryptedDek, metadata.dekIv, metadata.dekAuthTag)
      } else {
        return@withContext Result.failure(IllegalStateException("Master password or PIN required."))
      }
      resetFailedAttempts()
      _lockState.value = VaultLockState.Unlocked(dek)
      return@withContext Result.success(Unit)
    } catch (e: Exception) {
      Log.w(TAG, "Device biometric unlock error: ${e.message}")
      return@withContext Result.failure(e)
    }
  }

  // --- Entry CRUD with In-Memory Decryption ---
  suspend fun getDecryptedEntries(): List<DecryptedPasswordEntry> = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext emptyList()
    val entities = getDb().passwordEntryDao().getAllEntriesList()

    val result = mutableListOf<DecryptedPasswordEntry>()
    for (entity in entities) {
      try {
        val url = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entity.siteUrlEncrypted, entity.iv, entity.authTag), StandardCharsets.UTF_8)
        val user = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entity.usernameEncrypted, entity.iv, entity.authTag), StandardCharsets.UTF_8)
        val pass = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entity.passwordEncrypted, entity.iv, entity.authTag), StandardCharsets.UTF_8)
        val notes = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entity.notesEncrypted, entity.iv, entity.authTag), StandardCharsets.UTF_8)

        result.add(
          DecryptedPasswordEntry(
            id = entity.id,
            url = url,
            username = user,
            password = pass,
            notes = notes,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
          )
        )
      } catch (e: Exception) {
        Log.w(TAG, "Entry decryption skipped: ${e.message}")
      }
    }
    return@withContext result
  }

  suspend fun saveOrUpdateEntry(
    url: String,
    username: String,
    password: String,
    notes: String = "",
    existingId: Long = 0,
  ): Long = withContext(Dispatchers.IO) {
    saveMutex.withLock {
      val state = _lockState.value
      if (state !is VaultLockState.Unlocked) throw IllegalStateException("Vault is locked.")

      val canonicalUrl = PasswordCryptoEngine.canonicalizeOrigin(url) ?: url.trim()
      val siteHash = PasswordCryptoEngine.hashSiteUrl(canonicalUrl)
      val cleanUser = username.trim()
      val cleanPass = password.trim()

      // Debounce window (3000ms): if identical entry was recently saved, prevent duplicate entry
      val saveKey = "$canonicalUrl|$cleanUser|$cleanPass"
      val now = System.currentTimeMillis()
      val lastSaved = recentSaveTimes[saveKey]
      if (lastSaved != null && (now - lastSaved) < 3000L && existingId <= 0) {
        val existingCandidates = getDb().passwordEntryDao().getEntriesByUrlHash(siteHash)
        for (cand in existingCandidates) {
          try {
            val uBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, cand.usernameEncrypted, cand.iv, cand.authTag)
            val u = String(uBytes, StandardCharsets.UTF_8).trim()
            PasswordCryptoEngine.zeroize(uBytes)
            if (u.equals(cleanUser, ignoreCase = true)) {
              Log.i(TAG, "Debounced duplicate save for $canonicalUrl ($cleanUser), returning existing ID ${cand.id}")
              return@withLock cand.id
            }
          } catch (_: Exception) {}
        }
      }
      recentSaveTimes[saveKey] = now

      var finalId = existingId
      val existingCandidates = getDb().passwordEntryDao().getEntriesByUrlHash(siteHash)
      if (finalId <= 0) {
        for (cand in existingCandidates) {
          try {
            val uBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, cand.usernameEncrypted, cand.iv, cand.authTag)
            val u = String(uBytes, StandardCharsets.UTF_8).trim()
            PasswordCryptoEngine.zeroize(uBytes)
            if (u.equals(cleanUser, ignoreCase = true)) {
              finalId = cand.id
              break
            }
          } catch (_: Exception) {}
        }
      }

      val urlEnc = PasswordCryptoEngine.encryptAesGcmPacked(state.dek, canonicalUrl.toByteArray(StandardCharsets.UTF_8))
      val userEnc = PasswordCryptoEngine.encryptAesGcmPacked(state.dek, cleanUser.toByteArray(StandardCharsets.UTF_8))
      val passEnc = PasswordCryptoEngine.encryptAesGcmPacked(state.dek, cleanPass.toByteArray(StandardCharsets.UTF_8))
      val notesEnc = PasswordCryptoEngine.encryptAesGcmPacked(state.dek, notes.toByteArray(StandardCharsets.UTF_8))

      val fallbackIv = urlEnc.copyOfRange(0, PasswordCryptoEngine.IV_LENGTH_BYTES)
      val fallbackAuthTag = urlEnc.copyOfRange(PasswordCryptoEngine.IV_LENGTH_BYTES, PasswordCryptoEngine.IV_LENGTH_BYTES + PasswordCryptoEngine.AUTH_TAG_LENGTH_BYTES)

      val entity = PasswordEntryEntity(
        id = finalId,
        siteUrlHash = siteHash,
        siteUrlEncrypted = urlEnc,
        usernameEncrypted = userEnc,
        passwordEncrypted = passEnc,
        notesEncrypted = notesEnc,
        createdAt = if (finalId > 0) System.currentTimeMillis() else System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        iv = fallbackIv,
        authTag = fallbackAuthTag,
      )

      if (finalId > 0) {
        getDb().passwordEntryDao().update(entity)
        Log.i(TAG, "Updated existing password entry ID $finalId for $canonicalUrl ($cleanUser)")
        return@withLock finalId
      } else {
        val newId = getDb().passwordEntryDao().insert(entity)
        Log.i(TAG, "Inserted new password entry ID $newId for $canonicalUrl ($cleanUser)")
        return@withLock newId
      }
    }
  }

  suspend fun deduplicateDatabaseEntries(): Int = withContext(Dispatchers.IO) {
    saveMutex.withLock {
      val state = _lockState.value
      if (state !is VaultLockState.Unlocked) return@withLock 0
      val all = getDb().passwordEntryDao().getAllEntriesList()
      if (all.size <= 1) return@withLock 0

      val seen = mutableMapOf<String, PasswordEntryEntity>()
      val idsToDelete = mutableListOf<Long>()

      for (entry in all) {
        try {
          val url = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entry.siteUrlEncrypted, entry.iv, entry.authTag), StandardCharsets.UTF_8)
          val host = PasswordCryptoEngine.canonicalizeOrigin(url) ?: url.trim().lowercase()
          val user = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entry.usernameEncrypted, entry.iv, entry.authTag), StandardCharsets.UTF_8).trim().lowercase()
          val key = "$host|$user"

          val existing = seen[key]
          if (existing != null) {
            if (entry.updatedAt > existing.updatedAt) {
              idsToDelete.add(existing.id)
              seen[key] = entry
            } else {
              idsToDelete.add(entry.id)
            }
          } else {
            seen[key] = entry
          }
        } catch (_: Exception) {}
      }

      for (delId in idsToDelete) {
        getDb().passwordEntryDao().deleteById(delId)
        Log.i(TAG, "Cleaned up duplicate password entry ID $delId")
      }
      return@withLock idsToDelete.size
    }
  }

  suspend fun deleteEntry(id: Long) = withContext(Dispatchers.IO) {
    getDb().passwordEntryDao().deleteById(id)
  }

  fun isUnlocked(): Boolean = _lockState.value is VaultLockState.Unlocked

  // --- Autofill Match Candidate Lookup (Decodes only URL and Username, NOT Password) ---
  suspend fun findAutofillCandidatesForUrl(url: String): List<AutofillCandidate> = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext emptyList()

    val canonicalTargetOrigin = PasswordCryptoEngine.canonicalizeOrigin(url) ?: return@withContext emptyList()
    val targetHost = PasswordCryptoEngine.extractCanonicalHost(canonicalTargetOrigin)
    val siteHash = PasswordCryptoEngine.hashSiteUrl(canonicalTargetOrigin)
    var candidates = getDb().passwordEntryDao().getEntriesByUrlHash(siteHash)
    if (candidates.isEmpty()) {
      candidates = getDb().passwordEntryDao().getAllEntriesList()
    }

    val matches = mutableListOf<AutofillCandidate>()
    for (candidate in candidates) {
      try {
        val urlBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.siteUrlEncrypted, candidate.iv, candidate.authTag)
        val entryUrl = String(urlBytes, StandardCharsets.UTF_8)
        PasswordCryptoEngine.zeroize(urlBytes)

        val entryOrigin = PasswordCryptoEngine.canonicalizeOrigin(entryUrl)
        val entryHost = PasswordCryptoEngine.extractCanonicalHost(entryUrl)
        if (entryOrigin == canonicalTargetOrigin || (entryHost.isNotEmpty() && entryHost.equals(targetHost, ignoreCase = true))) {
          val userBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.usernameEncrypted, candidate.iv, candidate.authTag)
          val user = String(userBytes, StandardCharsets.UTF_8)
          PasswordCryptoEngine.zeroize(userBytes)

          matches.add(
            AutofillCandidate(
              id = candidate.id,
              url = entryUrl,
              username = user,
            )
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Autofill candidate metadata lookup skipped: ${e.message}")
      }
    }
    return@withContext matches
  }

  // --- Native GeckoView Autocomplete Login Fetching ---
  suspend fun getLoginEntriesForOrigin(originUrl: String): List<Autocomplete.LoginEntry> = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext emptyList()

    val canonicalOrigin = PasswordCryptoEngine.canonicalizeOrigin(originUrl) ?: return@withContext emptyList()
    val targetHost = PasswordCryptoEngine.extractCanonicalHost(canonicalOrigin)
    val siteHash = PasswordCryptoEngine.hashSiteUrl(canonicalOrigin)
    var candidates = getDb().passwordEntryDao().getEntriesByUrlHash(siteHash)
    if (candidates.isEmpty()) {
      candidates = getDb().passwordEntryDao().getAllEntriesList()
    }

    val entries = mutableListOf<Autocomplete.LoginEntry>()
    for (candidate in candidates) {
      try {
        val entryUrlBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.siteUrlEncrypted, candidate.iv, candidate.authTag)
        val entryUrl = String(entryUrlBytes, StandardCharsets.UTF_8)
        PasswordCryptoEngine.zeroize(entryUrlBytes)

        val entryOrigin = PasswordCryptoEngine.canonicalizeOrigin(entryUrl)
        val entryHost = PasswordCryptoEngine.extractCanonicalHost(entryUrl)
        if (entryOrigin == canonicalOrigin || (entryHost.isNotEmpty() && entryHost.equals(targetHost, ignoreCase = true))) {
          val userBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.usernameEncrypted, candidate.iv, candidate.authTag)
          val user = String(userBytes, StandardCharsets.UTF_8)
          PasswordCryptoEngine.zeroize(userBytes)

          val passBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.passwordEncrypted, candidate.iv, candidate.authTag)
          val pass = String(passBytes, StandardCharsets.UTF_8)
          PasswordCryptoEngine.zeroize(passBytes)

          val entry = Autocomplete.LoginEntry.Builder()
            .guid(candidate.id.toString())
            .origin(canonicalOrigin)
            .formActionOrigin(canonicalOrigin)
            .username(user)
            .password(pass)
            .build()
          entries.add(entry)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed decrypting login entry: ${e.message}")
      }
    }
    return@withContext entries
  }

  // --- Decrypt Single Selected Password on Demand ---
  suspend fun decryptPasswordForEntry(id: Long): String? = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext null

    val candidate = getDb().passwordEntryDao().getEntryById(id) ?: return@withContext null
    try {
      val passBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.passwordEncrypted, candidate.iv, candidate.authTag)
      val pass = String(passBytes, StandardCharsets.UTF_8)
      PasswordCryptoEngine.zeroize(passBytes)
      pass
    } catch (e: Exception) {
      Log.w(TAG, "Failed decrypting single entry password: ${e.message}")
      null
    }
  }

  // --- Safe Legacy URL Hash Migration ---
  suspend fun migrateLegacyUrlHashesIfNeeded(): Int = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext 0

    var updatedCount = 0
    val entries = getDb().passwordEntryDao().getAllEntriesList()
    for (entity in entries) {
      try {
        val entryUrlBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, entity.siteUrlEncrypted, entity.iv, entity.authTag)
        val entryUrl = String(entryUrlBytes, StandardCharsets.UTF_8)
        PasswordCryptoEngine.zeroize(entryUrlBytes)

        val newHash = PasswordCryptoEngine.hashSiteUrl(entryUrl)
        if (newHash != entity.siteUrlHash) {
          val updated = entity.copy(siteUrlHash = newHash)
          getDb().passwordEntryDao().update(updated)
          updatedCount++
        }
      } catch (e: Exception) {
        Log.w(TAG, "Error migrating legacy url hash: ${e.message}")
      }
    }
    updatedCount
  }

  // --- Autofill Match Lookup (HTTPS Only) ---
  suspend fun findAutofillCredentialsForUrl(url: String): List<DecryptedPasswordEntry> = withContext(Dispatchers.IO) {
    if (!url.startsWith("https://", ignoreCase = true)) {
      return@withContext emptyList() // Strict: NEVER autofill on HTTP / clearnet plaintext
    }
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) return@withContext emptyList()

    val canonicalTargetOrigin = PasswordCryptoEngine.canonicalizeOrigin(url) ?: return@withContext emptyList()
    val siteHash = PasswordCryptoEngine.hashSiteUrl(canonicalTargetOrigin)
    val candidates = getDb().passwordEntryDao().getEntriesByUrlHash(siteHash)

    val matches = mutableListOf<DecryptedPasswordEntry>()
    for (candidate in candidates) {
      try {
        val entryUrlBytes = PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.siteUrlEncrypted, candidate.iv, candidate.authTag)
        val entryUrl = String(entryUrlBytes, StandardCharsets.UTF_8)
        PasswordCryptoEngine.zeroize(entryUrlBytes)

        val entryOrigin = PasswordCryptoEngine.canonicalizeOrigin(entryUrl)
        if (entryOrigin == canonicalTargetOrigin) {
          val user = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.usernameEncrypted, candidate.iv, candidate.authTag), StandardCharsets.UTF_8)
          val pass = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.passwordEncrypted, candidate.iv, candidate.authTag), StandardCharsets.UTF_8)
          val notes = String(PasswordCryptoEngine.decryptAesGcmPacked(state.dek, candidate.notesEncrypted, candidate.iv, candidate.authTag), StandardCharsets.UTF_8)

          matches.add(
            DecryptedPasswordEntry(
              id = candidate.id,
              url = entryUrl,
              username = user,
              password = pass,
              notes = notes,
              createdAt = candidate.createdAt,
              updatedAt = candidate.updatedAt,
            )
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Autofill candidate skipped: ${e.message}")
      }
    }
    return@withContext matches
  }

  // --- Fort Knox Priority Detector ---
  fun isFortKnoxInstalled(): Boolean {
    return try {
      context.packageManager.getPackageInfo(FORT_KNOX_PACKAGE, 0)
      true
    } catch (e: PackageManager.NameNotFoundException) {
      false
    }
  }

  // --- Vault Security Score Calculation ---
  fun calculateSecurityScore(
    entries: List<DecryptedPasswordEntry>,
    masterStrength: MasterPasswordStrength?,
    isBiometricOn: Boolean,
  ): VaultSecurityScore {
    var score = 40
    if (masterStrength != null && masterStrength.score >= 80) score += 30 else score += 10
    if (isBiometricOn) score += 15

    var weakCount = 0
    val passwordSet = mutableSetOf<String>()
    var reusedCount = 0

    for (entry in entries) {
      if (entry.password.length < 14) weakCount++
      if (!passwordSet.add(entry.password)) reusedCount++
    }

    if (weakCount == 0 && entries.isNotEmpty()) score += 10 else score -= (weakCount * 3)
    if (reusedCount == 0 && entries.isNotEmpty()) score += 5 else score -= (reusedCount * 5)

    val finalScore = score.coerceIn(0, 100)
    val grade = when {
      finalScore >= 90 -> "A+ (DEFENSE GRADE)"
      finalScore >= 75 -> "A (FORTIFIED)"
      finalScore >= 60 -> "B (SECURE)"
      finalScore >= 40 -> "C (MODERATE)"
      else -> "D (VULNERABLE)"
    }

    return VaultSecurityScore(
      score = finalScore,
      isMasterPasswordStrong = (masterStrength?.score ?: 0) >= 80,
      isBiometricEnabled = isBiometricOn,
      totalAccounts = entries.size,
      weakPasswordsCount = weakCount,
      reusedPasswordsCount = reusedCount,
      securityGrade = grade,
    )
  }

  // --- Master Password & Key Rotation ---
  suspend fun changeMasterPassword(newPassword: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
    val state = _lockState.value
    if (state !is VaultLockState.Unlocked) {
      return@withContext Result.failure(IllegalStateException("Vault must be unlocked to change Master Password."))
    }
    if (!PasswordCryptoEngine.isPasswordValid(newPassword)) {
      return@withContext Result.failure(IllegalArgumentException("New Master Password does not meet security requirements (min 12 chars)."))
    }

    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault is uninitialized."))

    val newKdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(newPassword)
    try {
      val newEncryptedDek = PasswordCryptoEngine.encryptAesGcm(newKdfResult.kek, state.dek)
      val (newVerifier, newVerifierSalt) = PasswordCryptoEngine.computeVerifier(newKdfResult.kek)

      val updated = metadata.copy(
        encryptedDek = newEncryptedDek.ciphertext,
        dekIv = newEncryptedDek.iv,
        dekAuthTag = newEncryptedDek.authTag,
        kdfSalt = newKdfResult.salt,
        kdfParams = newKdfResult.paramsDescription,
        verifier = newVerifier,
        verifierSalt = newVerifierSalt,
      )
      getDb().masterKeyMetadataDao().saveMetadata(updated)
      return@withContext Result.success(Unit)
    } catch (e: Exception) {
      return@withContext Result.failure(e)
    } finally {
      PasswordCryptoEngine.zeroize(newKdfResult.kek)
    }
  }

  suspend fun changeMasterPasswordWithVerification(
    oldPassword: CharArray,
    newPassword: CharArray,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault is uninitialized."))

    if (!PasswordCryptoEngine.isPasswordValid(newPassword)) {
      return@withContext Result.failure(IllegalArgumentException("New Master Password does not meet security requirements (min 12 chars)."))
    }

    val oldKdf = PasswordCryptoEngine.deriveKeyEncryptionKey(oldPassword, metadata.kdfSalt)
    try {
      val isVerified = PasswordCryptoEngine.verifyKek(oldKdf.kek, metadata.verifier, metadata.verifierSalt)
      if (!isVerified) {
        handleFailedAttempt(metadata.autoWipeEnabled)
        return@withContext Result.failure(SecurityException("Incorrect old Master Password."))
      }

      val dek = PasswordCryptoEngine.decryptAesGcm(
        key = oldKdf.kek,
        ciphertext = metadata.encryptedDek,
        iv = metadata.dekIv,
        authTag = metadata.dekAuthTag,
      )

      val newKdf = PasswordCryptoEngine.deriveKeyEncryptionKey(newPassword)
      try {
        val newEnc = PasswordCryptoEngine.encryptAesGcm(newKdf.kek, dek)
        val (newVerifier, newVerifierSalt) = PasswordCryptoEngine.computeVerifier(newKdf.kek)

        val updated = metadata.copy(
          encryptedDek = newEnc.ciphertext,
          dekIv = newEnc.iv,
          dekAuthTag = newEnc.authTag,
          kdfSalt = newKdf.salt,
          kdfParams = newKdf.paramsDescription,
          verifier = newVerifier,
          verifierSalt = newVerifierSalt,
        )
        getDb().masterKeyMetadataDao().saveMetadata(updated)
        resetFailedAttempts()
        _lockState.value = VaultLockState.Unlocked(dek)
        return@withContext Result.success(Unit)
      } finally {
        PasswordCryptoEngine.zeroize(newKdf.kek)
      }
    } catch (e: Exception) {
      return@withContext Result.failure(e)
    } finally {
      PasswordCryptoEngine.zeroize(oldKdf.kek)
    }
  }

  suspend fun changeMasterPinWithVerification(
    oldPin: CharArray,
    newPin: CharArray,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    val metadata = getDb().masterKeyMetadataDao().getMetadata()
      ?: return@withContext Result.failure(IllegalStateException("Vault is uninitialized."))

    if (!metadata.pinEnabled || metadata.pinEncryptedDek == null || metadata.pinKdfSalt == null ||
      metadata.pinVerifier == null || metadata.pinVerifierSalt == null || metadata.pinDekIv == null || metadata.pinDekAuthTag == null
    ) {
      return@withContext Result.failure(IllegalStateException("PIN is not configured."))
    }

    if (!PasswordCryptoEngine.isPinValid(newPin)) {
      return@withContext Result.failure(IllegalArgumentException("Invalid new PIN format."))
    }

    val oldKdf = PasswordCryptoEngine.deriveKeyEncryptionKey(oldPin, metadata.pinKdfSalt)
    try {
      val isVerified = PasswordCryptoEngine.verifyKek(oldKdf.kek, metadata.pinVerifier, metadata.pinVerifierSalt)
      if (!isVerified) {
        handleFailedAttempt(metadata.autoWipeEnabled)
        return@withContext Result.failure(SecurityException("Incorrect old Master PIN."))
      }

      val dek = PasswordCryptoEngine.decryptAesGcm(
        key = oldKdf.kek,
        ciphertext = metadata.pinEncryptedDek,
        iv = metadata.pinDekIv,
        authTag = metadata.pinDekAuthTag,
      )

      val newKdf = PasswordCryptoEngine.deriveKeyEncryptionKey(newPin)
      try {
        val newEnc = PasswordCryptoEngine.encryptAesGcm(newKdf.kek, dek)
        val (newVerifier, newVerifierSalt) = PasswordCryptoEngine.computeVerifier(newKdf.kek)

        val updated = metadata.copy(
          pinEnabled = true,
          pinEncryptedDek = newEnc.ciphertext,
          pinDekIv = newEnc.iv,
          pinDekAuthTag = newEnc.authTag,
          pinKdfSalt = newKdf.salt,
          pinKdfParams = newKdf.paramsDescription,
          pinVerifier = newVerifier,
          pinVerifierSalt = newVerifierSalt,
        )
        getDb().masterKeyMetadataDao().saveMetadata(updated)
        resetFailedAttempts()
        _lockState.value = VaultLockState.Unlocked(dek)
        return@withContext Result.success(Unit)
      } finally {
        PasswordCryptoEngine.zeroize(newKdf.kek)
        PasswordCryptoEngine.zeroize(dek)
      }
    } catch (e: Exception) {
      return@withContext Result.failure(e)
    } finally {
      PasswordCryptoEngine.zeroize(oldKdf.kek)
    }
  }

  // --- Wipe Protocol ---
  suspend fun wipeAllVaultData(): Boolean = withContext(Dispatchers.IO) {
    // 1. Zeroize in-memory DEK immediately
    val state = _lockState.value
    if (state is VaultLockState.Unlocked) {
      PasswordCryptoEngine.zeroize(state.dek)
    }

    // 2. Clear Database records & metadata while database is still open
    val recordsCleared = try {
      val db = getDb()
      db.passwordEntryDao().clearAll()
      db.masterKeyMetadataDao().clearMetadata()
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clear vault database records: ${e.message}", e)
      false
    }

    // 3. Revoke Android Keystore Biometric Key
    val bioKeyRevoked = PasswordCryptoEngine.deleteBiometricKeystoreKey()

    // 4. Reset rate limiters & locks
    resetFailedAttempts()
    _lockState.value = VaultLockState.Uninitialized

    recordsCleared && bioKeyRevoked
  }
}
