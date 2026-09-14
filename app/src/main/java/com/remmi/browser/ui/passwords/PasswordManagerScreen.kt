package com.remmi.browser.ui.passwords

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.VaultLockState
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Universal biometric & device credential authentication helper.
 */
fun promptBiometricAuth(
  activity: FragmentActivity?,
  title: String,
  subtitle: String,
  onSuccess: () -> Unit,
) {
  if (activity == null) {
    onSuccess()
    return
  }
  val executor = ContextCompat.getMainExecutor(activity)
  val prompt = BiometricPrompt(
    activity,
    executor,
    object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        onSuccess()
      }
      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        // User canceled or authentication error
      }
      override fun onAuthenticationFailed() {
        // Biometric retry
      }
    }
  )

  val promptInfo = BiometricPrompt.PromptInfo.Builder()
    .setTitle(title)
    .setSubtitle(subtitle)
    .setAllowedAuthenticators(
      BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    .build()

  try {
    prompt.authenticate(promptInfo)
  } catch (_: Exception) {
    val fallbackInfo = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setNegativeButtonText("Cancel")
      .build()
    try {
      prompt.authenticate(fallbackInfo)
    } catch (_: Exception) {
      onSuccess()
    }
  }
}

@Composable
fun PasswordManagerScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenUrl: ((String) -> Unit)? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { PasswordManagerRepository.getInstance(context) }
  val clipboard = remember { ClipboardManager(context) }

  // Screenshot security protection (FLAG_SECURE)
  DisposableEffect(Unit) {
    val window = (context as? Activity)?.window
    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose {
      window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  val lockState by repo.lockState.collectAsState()
  val failedAttempts by repo.failedAttempts.collectAsState()
  val lockoutSecondsRemaining by repo.lockoutSecondsRemaining.collectAsState()

  BackHandler {
    onBack()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
  ) {
    when (val state = lockState) {
      is VaultLockState.Uninitialized -> {
        SetupVaultScreen(
          repo = repo,
          onBack = onBack
        )
      }

      is VaultLockState.Locked -> {
        LockedVaultScreen(
          repo = repo,
          failedAttempts = failedAttempts,
          lockoutSeconds = lockoutSecondsRemaining,
          onBack = onBack
        )
      }

      is VaultLockState.TemporarilyLocked -> {
        LockedVaultScreen(
          repo = repo,
          failedAttempts = failedAttempts,
          lockoutSeconds = state.remainingSeconds,
          onBack = onBack
        )
      }

      is VaultLockState.CompromisedDevice -> {
        CompromisedDeviceScreen(onBack = onBack)
      }

      is VaultLockState.Unlocked -> {
        UnlockedVaultScreen(
          repo = repo,
          dek = state.dek,
          clipboard = clipboard,
          onBack = onBack,
          onOpenUrl = onOpenUrl
        )
      }
    }
  }
}

// -------------------------------------------------------------
// COMPROMISED DEVICE SCREEN (When device integrity fails)
// -------------------------------------------------------------
@Composable
private fun CompromisedDeviceScreen(onBack: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp)
      .testTag("screen_compromised_device")
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(80.dp)
        .clip(CircleShape)
        .background(Color(0xFFE53935).copy(alpha = 0.15f))
    ) {
      Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = Color(0xFFFF5252),
        modifier = Modifier.size(44.dp)
      )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
      text = "SECURITY INTEGRITY ALERT",
      color = Color(0xFFFF5252),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      fontFamily = ThemeCyber.fontFamily,
      letterSpacing = 1.sp
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "Root, debugger, or system tampering detected on this device. Cryptographic keys are quarantined to prevent memory extraction of your credentials.",
      color = ThemeCyber.colors.textSecondary,
      fontSize = 13.5.sp,
      lineHeight = 20.sp,
      textAlign = TextAlign.Center,
      fontFamily = ThemeCyber.fontFamily
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
      onClick = onBack,
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1015)),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
      modifier = Modifier.fillMaxWidth().height(46.dp)
    ) {
      Text("Exit Vault", color = Color(0xFFFF5252), fontWeight = FontWeight.SemiBold, fontFamily = ThemeCyber.fontFamily)
    }
  }
}

// -------------------------------------------------------------
// 1. SETUP SCREEN (When vault is uninitialized)
// -------------------------------------------------------------
@Composable
private fun SetupVaultScreen(
  repo: PasswordManagerRepository,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var masterPassword by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var optionalPin by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var isSettingUp by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp)
      .testTag("screen_setup_vault")
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(68.dp)
        .clip(CircleShape)
        .background(ThemeCyber.colors.primary.copy(alpha = 0.12f))
    ) {
      Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = null,
        tint = ThemeCyber.colors.primary,
        modifier = Modifier.size(36.dp)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "Setup Military-Grade Vault",
      color = ThemeCyber.colors.textPrimary,
      fontWeight = FontWeight.Bold,
      fontSize = 20.sp,
      fontFamily = ThemeCyber.fontFamily,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = "Argon2id + AES-256-GCM Zero-Knowledge Encryption.\nYour master passphrase encrypts all credentials locally.",
      color = ThemeCyber.colors.textSecondary,
      fontSize = 12.5.sp,
      fontFamily = ThemeCyber.fontFamily,
      textAlign = TextAlign.Center,
      lineHeight = 17.sp
    )

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedTextField(
      value = masterPassword,
      onValueChange = {
        masterPassword = it
        errorMessage = null
      },
      label = { Text("Master Passphrase (Min 8 chars)", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
      visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        IconButton(onClick = { showPassword = !showPassword }) {
          Icon(
            imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = ThemeCyber.colors.textSecondary
          )
        }
      },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ThemeCyber.colors.primary,
        unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
        focusedTextColor = ThemeCyber.colors.textPrimary,
        unfocusedTextColor = ThemeCyber.colors.textPrimary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("input_setup_password")
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = confirmPassword,
      onValueChange = {
        confirmPassword = it
        errorMessage = null
      },
      label = { Text("Confirm Master Passphrase", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
      visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ThemeCyber.colors.primary,
        unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
        focusedTextColor = ThemeCyber.colors.textPrimary,
        unfocusedTextColor = ThemeCyber.colors.textPrimary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("input_setup_confirm")
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = optionalPin,
      onValueChange = {
        if (it.length <= 12 && it.all { ch -> ch.isDigit() }) {
          optionalPin = it
        }
      },
      label = { Text("Quick Unlock PIN (Optional, 8-12 digits)", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      visualTransformation = PasswordVisualTransformation(),
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ThemeCyber.colors.primary,
        unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
        focusedTextColor = ThemeCyber.colors.textPrimary,
        unfocusedTextColor = ThemeCyber.colors.textPrimary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("input_setup_pin")
    )

    if (errorMessage != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = errorMessage!!,
        color = ThemeCyber.colors.dangerRed,
        fontSize = 12.sp,
        fontFamily = ThemeCyber.fontFamily
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
      onClick = {
        if (masterPassword.length < 8) {
          errorMessage = "Master passphrase must be at least 8 characters."
          return@Button
        }
        if (masterPassword != confirmPassword) {
          errorMessage = "Passphrases do not match."
          return@Button
        }
        if (optionalPin.isNotBlank() && optionalPin.length < 8) {
          errorMessage = "PIN must be 8-12 digits if provided."
          return@Button
        }

        isSettingUp = true
        scope.launch {
          val success = repo.setupMasterPassword(
            password = masterPassword.toCharArray(),
            pin = if (optionalPin.isNotBlank()) optionalPin.toCharArray() else null
          )
          isSettingUp = false
          if (!success) {
            errorMessage = "Initialization failed. Passphrase must be at least 8 characters."
          }
        }
      },
      enabled = !isSettingUp && masterPassword.isNotBlank(),
      colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("btn_setup_vault_submit")
    ) {
      if (isSettingUp) {
        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
      } else {
        Text("Initialize Secure Vault", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, fontFamily = ThemeCyber.fontFamily)
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    TextButton(onClick = onBack) {
      Text("Back", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily)
    }
  }
}

// -------------------------------------------------------------
// 2. LOCKED SCREEN (When vault is locked)
// -------------------------------------------------------------
@Composable
private fun LockedVaultScreen(
  repo: PasswordManagerRepository,
  failedAttempts: Int,
  lockoutSeconds: Int,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var inputKey by remember { mutableStateOf("") }
  var showInput by remember { mutableStateOf(false) }
  var isUnlocking by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  fun triggerBiometrics() {
    val activity = context as? FragmentActivity ?: return
    scope.launch {
      val meta = repo.getMasterKeyMetadata()
      if (meta?.kdfParams == "DEVICE_KEYSTORE") {
        promptBiometricAuth(
          activity = activity,
          title = "Unlock Password Vault",
          subtitle = "Authenticate using fingerprint, face, or device screen lock",
          onSuccess = {
            scope.launch {
              val res = repo.unlockWithDeviceBiometrics()
              if (res.isFailure) {
                errorMessage = "Unlock failed: ${res.exceptionOrNull()?.message}"
              }
            }
          }
        )
      } else {
        val cipherResult = repo.prepareBiometricDecryptCipher()
        if (cipherResult.isSuccess) {
          val cipher = cipherResult.getOrNull() ?: return@launch
          val executor = ContextCompat.getMainExecutor(context)
          val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
              override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authCipher = result.cryptoObject?.cipher
                if (authCipher != null) {
                  scope.launch {
                    repo.unlockWithBiometric(authCipher)
                  }
                }
              }
              override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Graceful fallback to password/PIN
              }
            }
          )
          val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Password Vault")
            .setSubtitle("Authenticate to access your credentials")
            .setNegativeButtonText("Use Passphrase / PIN")
            .build()
          prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } else {
          promptBiometricAuth(
            activity = activity,
            title = "Unlock Password Vault",
            subtitle = "Authenticate using fingerprint or device screen lock",
            onSuccess = {
              scope.launch {
                repo.unlockWithDeviceBiometrics()
              }
            }
          )
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    if (repo.isBiometricAvailable()) {
      triggerBiometrics()
    }
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp)
      .testTag("screen_locked_vault")
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(76.dp)
        .clip(CircleShape)
        .background(
          if (lockoutSeconds > 0) ThemeCyber.colors.dangerRed.copy(alpha = 0.15f)
          else ThemeCyber.colors.primary.copy(alpha = 0.15f)
        )
    ) {
      Icon(
        imageVector = if (lockoutSeconds > 0) Icons.Default.Warning else Icons.Default.Lock,
        contentDescription = null,
        tint = if (lockoutSeconds > 0) ThemeCyber.colors.dangerRed else ThemeCyber.colors.primary,
        modifier = Modifier.size(40.dp)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = if (lockoutSeconds > 0) "Security Lockout Active" else "Vault Encrypted",
      color = ThemeCyber.colors.textPrimary,
      fontWeight = FontWeight.Bold,
      fontSize = 20.sp,
      fontFamily = ThemeCyber.fontFamily
    )

    Spacer(modifier = Modifier.height(6.dp))

    if (lockoutSeconds > 0) {
      Text(
        text = "Too many failed attempts. Security cooldown:\n$lockoutSeconds seconds remaining",
        color = ThemeCyber.colors.dangerRed,
        fontSize = 13.sp,
        fontFamily = ThemeCyber.fontFamily,
        textAlign = TextAlign.Center
      )
    } else {
      Text(
        text = "Enter Master Passphrase or Quick PIN to decrypt",
        color = ThemeCyber.colors.textSecondary,
        fontSize = 12.5.sp,
        fontFamily = ThemeCyber.fontFamily
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedTextField(
      value = inputKey,
      onValueChange = {
        inputKey = it
        errorMessage = null
      },
      enabled = lockoutSeconds <= 0 && !isUnlocking,
      label = { Text("Passphrase or PIN", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
      visualTransformation = if (showInput) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        IconButton(onClick = { showInput = !showInput }) {
          Icon(
            imageVector = if (showInput) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = ThemeCyber.colors.textSecondary
          )
        }
      },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ThemeCyber.colors.primary,
        unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
        focusedTextColor = ThemeCyber.colors.textPrimary,
        unfocusedTextColor = ThemeCyber.colors.textPrimary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("input_vault_unlock_key")
    )

    if (errorMessage != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = errorMessage!!,
        color = ThemeCyber.colors.dangerRed,
        fontSize = 12.sp,
        fontFamily = ThemeCyber.fontFamily
      )
    }

    if (failedAttempts > 0 && lockoutSeconds <= 0) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "Failed attempts: $failedAttempts (Auto-lockout at 5)",
        color = ThemeCyber.colors.dangerRed.copy(alpha = 0.8f),
        fontSize = 11.5.sp,
        fontFamily = ThemeCyber.fontFamily
      )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Button(
      onClick = {
        if (inputKey.isBlank()) return@Button
        isUnlocking = true
        scope.launch {
          val chars = inputKey.toCharArray()
          // Check if purely numeric PIN or passphrase
          val result = if (chars.all { it.isDigit() } && chars.size in 8..12) {
            repo.unlockWithMasterPin(chars)
          } else {
            repo.unlockWithMasterPassword(chars)
          }
          isUnlocking = false
          if (result.isFailure) {
            errorMessage = result.exceptionOrNull()?.message ?: "Incorrect passphrase or PIN."
          }
        }
      },
      enabled = lockoutSeconds <= 0 && !isUnlocking && inputKey.isNotBlank(),
      colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("btn_vault_unlock_submit")
    ) {
      if (isUnlocking) {
        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
      } else {
        Text("Unlock Vault", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, fontFamily = ThemeCyber.fontFamily)
      }
    }

    if (repo.isBiometricAvailable() && lockoutSeconds <= 0) {
      Spacer(modifier = Modifier.height(14.dp))
      OutlinedButton(
        onClick = { triggerBiometrics() },
        border = BorderStroke(1.dp, ThemeCyber.colors.primary),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(46.dp)
          .testTag("btn_biometric_unlock")
      ) {
        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = ThemeCyber.colors.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Unlock with Biometrics", color = ThemeCyber.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    TextButton(onClick = onBack) {
      Text("Back to Browser", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily)
    }
  }
}

// -------------------------------------------------------------
// 3. UNLOCKED VAULT SCREEN (The Full Redesigned UI)
// -------------------------------------------------------------
@Composable
private fun UnlockedVaultScreen(
  repo: PasswordManagerRepository,
  dek: ByteArray,
  clipboard: ClipboardManager,
  onBack: () -> Unit,
  onOpenUrl: ((String) -> Unit)?,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var entries by remember { mutableStateOf<List<DecryptedPasswordEntry>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var searchQuery by remember { mutableStateOf("") }
  var selectedFilter by remember { mutableStateOf("ALL") } // ALL, WEAK, REUSED

  // Dialog States
  var showAddDialog by remember { mutableStateOf(false) }
  var addDialogDomain by remember { mutableStateOf<String?>(null) }
  var editingEntry by remember { mutableStateOf<DecryptedPasswordEntry?>(null) }
  var showGeneratorDialog by remember { mutableStateOf(false) }
  var showBackupDialog by remember { mutableStateOf(false) }
  var showAuditDialog by remember { mutableStateOf(false) }
  var showSettingsDialog by remember { mutableStateOf(false) }
  var entryToDelete by remember { mutableStateOf<DecryptedPasswordEntry?>(null) }

  // Expanded website folders map: domain -> isExpanded
  val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }

  fun loadEntries() {
    isLoading = true
    scope.launch {
      repo.deduplicateDatabaseEntries()
      val list = repo.getDecryptedEntries()
      entries = list
      isLoading = false
    }
  }

  LaunchedEffect(Unit) {
    loadEntries()
  }

  val folders = remember(entries) {
    PasswordManagerUtils.groupEntriesIntoFolders(entries)
  }

  val stats = remember(entries) {
    PasswordManagerUtils.computeVaultStats(entries)
  }

  // Filtered Folders based on search & filter tabs
  val displayedFolders = remember(folders, searchQuery, selectedFilter) {
    var filtered = folders
    if (searchQuery.isNotBlank()) {
      val q = searchQuery.trim().lowercase()
      filtered = filtered.mapNotNull { folder ->
        val matchFolder = folder.domain.contains(q) || folder.displayName.lowercase().contains(q)
        val matchingEntries = folder.entries.filter {
          it.username.lowercase().contains(q) || it.notes.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
        if (matchFolder) {
          folder
        } else if (matchingEntries.isNotEmpty()) {
          folder.copy(entries = matchingEntries)
        } else null
      }
    }

    when (selectedFilter) {
      "WEAK" -> filtered.filter { it.weakCount > 0 }
      "REUSED" -> filtered.filter { it.reusedCount > 0 }
      else -> filtered
    }
  }

  // Active Dialogs
  if (showAddDialog) {
    AddEditCredentialDialog(
      initialDomain = addDialogDomain,
      onDismiss = {
        showAddDialog = false
        addDialogDomain = null
      },
      onSave = { url, user, pass, notes, _ ->
        scope.launch {
          repo.saveOrUpdateEntry(url, user, pass, notes)
          loadEntries()
          Toast.makeText(context, "Saved to ${PasswordManagerUtils.extractDomain(url)} folder", Toast.LENGTH_SHORT).show()
        }
      }
    )
  }

  if (editingEntry != null) {
    AddEditCredentialDialog(
      initialEntry = editingEntry,
      onDismiss = { editingEntry = null },
      onSave = { url, user, pass, notes, id ->
        scope.launch {
          repo.saveOrUpdateEntry(url, user, pass, notes, existingId = id)
          loadEntries()
          Toast.makeText(context, "Account updated successfully", Toast.LENGTH_SHORT).show()
        }
      },
      onDelete = { id ->
        scope.launch {
          repo.deleteEntry(id)
          loadEntries()
          Toast.makeText(context, "Account deleted", Toast.LENGTH_SHORT).show()
        }
      }
    )
  }

  if (showGeneratorDialog) {
    MilitaryPasswordGeneratorDialog(
      onDismiss = { showGeneratorDialog = false }
    )
  }

  if (showBackupDialog) {
    BackupRestoreDialog(
      dek = dek,
      repo = repo,
      onDismiss = { showBackupDialog = false },
      onRefresh = { loadEntries() }
    )
  }

  if (showAuditDialog) {
    SecurityAuditDialog(
      entries = entries,
      onDismiss = { showAuditDialog = false },
      onEditEntry = { entry ->
        editingEntry = entry
      }
    )
  }

  if (showSettingsDialog) {
    VaultSettingsSheet(
      repo = repo,
      onDismiss = { showSettingsDialog = false }
    )
  }

  if (entryToDelete != null) {
    Dialog(onDismissRequest = { entryToDelete = null }) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
        border = BorderStroke(1.dp, ThemeCyber.colors.dangerRed.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Delete Account?", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = ThemeCyber.fontFamily)
          Spacer(modifier = Modifier.height(8.dp))
          Text("This credential will be permanently purged from your encrypted vault.", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp, fontFamily = ThemeCyber.fontFamily)
          Spacer(modifier = Modifier.height(18.dp))
          Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { entryToDelete = null }) {
              Text("Cancel", color = ThemeCyber.colors.textSecondary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                val toDel = entryToDelete
                entryToDelete = null
                if (toDel != null) {
                  scope.launch {
                    repo.deleteEntry(toDel.id)
                    loadEntries()
                    Toast.makeText(context, "Account deleted", Toast.LENGTH_SHORT).show()
                  }
                }
              },
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed)
            ) {
              Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }

  Scaffold(
    containerColor = Color.Transparent,
    floatingActionButton = {
      FloatingActionButton(
        onClick = {
          addDialogDomain = null
          showAddDialog = true
        },
        containerColor = ThemeCyber.colors.primary,
        contentColor = Color.Black,
        shape = CircleShape,
        modifier = Modifier.testTag("fab_add_password")
      ) {
        Icon(Icons.Default.Add, contentDescription = "Add Password", modifier = Modifier.size(28.dp))
      }
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      // -------------------------------------------------------------
      // TOP APP BAR
      // -------------------------------------------------------------
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(ThemeCyber.colors.surfaceLight)
              .testTag("btn_vault_back")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = ThemeCyber.colors.textPrimary,
              modifier = Modifier.size(20.dp)
            )
          }

          Spacer(modifier = Modifier.width(12.dp))

          Column {
            Text(
              text = "Password Vault",
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 18.sp,
              fontFamily = ThemeCyber.fontFamily
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(ThemeCyber.colors.successGreen)
              )
              Spacer(modifier = Modifier.width(5.dp))
              Text(
                text = "AES-256-GCM • Military Grade",
                color = ThemeCyber.colors.successGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = ThemeCyber.fontFamily
              )
            }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = { repo.lockVault() },
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(ThemeCyber.colors.surfaceLight)
              .testTag("btn_lock_vault")
          ) {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = "Lock Vault",
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(19.dp)
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          IconButton(
            onClick = { showSettingsDialog = true },
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(ThemeCyber.colors.surfaceLight)
              .testTag("btn_vault_settings")
          ) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Settings",
              tint = ThemeCyber.colors.textSecondary,
              modifier = Modifier.size(19.dp)
            )
          }
        }
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp)
      ) {
        // -------------------------------------------------------------
        // HERO SECURITY DASHBOARD
        // -------------------------------------------------------------
        item {
          Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
            border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 14.dp)
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
              ) {
                Column {
                  Text(
                    text = "Vault Security Status",
                    color = ThemeCyber.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = ThemeCyber.fontFamily
                  )
                  Text(
                    text = "${stats.totalFolders} Website Folders • ${stats.totalCredentials} Passwords",
                    color = ThemeCyber.colors.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = ThemeCyber.fontFamily
                  )
                }

                Box(
                  contentAlignment = Alignment.Center,
                  modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                      if (stats.overallHealth >= 80) ThemeCyber.colors.successGreen.copy(alpha = 0.15f)
                      else if (stats.overallHealth >= 50) Color(0xFFFFB300).copy(alpha = 0.15f)
                      else ThemeCyber.colors.dangerRed.copy(alpha = 0.15f)
                    )
                ) {
                  Text(
                    text = "${stats.overallHealth}%",
                    color = if (stats.overallHealth >= 80) ThemeCyber.colors.successGreen
                    else if (stats.overallHealth >= 50) Color(0xFFFFB300)
                    else ThemeCyber.colors.dangerRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    fontFamily = ThemeCyber.fontFamily
                  )
                }
              }

              Spacer(modifier = Modifier.height(14.dp))

              // Quick Action Chips Row
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                QuickActionBadge(
                  icon = Icons.Default.Security,
                  label = "Audit Vault",
                  badge = if (stats.weakPasswordsCount > 0) "${stats.weakPasswordsCount}" else null,
                  modifier = Modifier.weight(1f),
                  onClick = { showAuditDialog = true }
                )

                QuickActionBadge(
                  icon = Icons.Default.AutoAwesome,
                  label = "Generator",
                  badge = null,
                  modifier = Modifier.weight(1f),
                  onClick = { showGeneratorDialog = true }
                )

                QuickActionBadge(
                  icon = Icons.Default.Storage,
                  label = "File Backup",
                  badge = null,
                  modifier = Modifier.weight(1f),
                  onClick = { showBackupDialog = true }
                )
              }
            }
          }
        }

        // -------------------------------------------------------------
        // SEARCH & FILTER CHIPS
        // -------------------------------------------------------------
        item {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search folders, websites, usernames...", color = ThemeCyber.colors.textSecondary, fontSize = 13.sp) },
            leadingIcon = {
              Icon(Icons.Default.Search, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(19.dp))
            },
            trailingIcon = {
              if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { searchQuery = "" }) {
                  Icon(Icons.Default.Close, contentDescription = "Clear", tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp))
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ThemeCyber.colors.primary,
              unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
              focusedTextColor = ThemeCyber.colors.textPrimary,
              unfocusedTextColor = ThemeCyber.colors.textPrimary,
              focusedContainerColor = ThemeCyber.colors.surface,
              unfocusedContainerColor = ThemeCyber.colors.surface,
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_search_vault")
          )

          Spacer(modifier = Modifier.height(10.dp))

          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 12.dp)
          ) {
            item {
              FilterChipItem(
                label = "All Folders (${folders.size})",
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" }
              )
            }
            item {
              FilterChipItem(
                label = "Weak Passwords (${stats.weakPasswordsCount})",
                selected = selectedFilter == "WEAK",
                onClick = { selectedFilter = "WEAK" }
              )
            }
            item {
              FilterChipItem(
                label = "Reused Passwords (${stats.reusedPasswordsCount})",
                selected = selectedFilter == "REUSED",
                onClick = { selectedFilter = "REUSED" }
              )
            }
          }
        }

        // -------------------------------------------------------------
        // WEBSITE FOLDERS LIST
        // -------------------------------------------------------------
        if (isLoading) {
          item {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp)
            ) {
              CircularProgressIndicator(color = ThemeCyber.colors.primary)
            }
          }
        } else if (displayedFolders.isEmpty()) {
          item {
            EmptyVaultFolderView(
              isSearching = searchQuery.isNotBlank(),
              onAddClick = {
                addDialogDomain = null
                showAddDialog = true
              }
            )
          }
        } else {
          items(displayedFolders, key = { it.domain }) { folder ->
            val isExpanded = expandedFolders[folder.domain] == true || searchQuery.isNotBlank()

            WebsiteFolderCard(
              folder = folder,
              isExpanded = isExpanded,
              clipboard = clipboard,
              onToggleExpand = {
                expandedFolders[folder.domain] = !(expandedFolders[folder.domain] ?: false)
              },
              onAddAccount = {
                addDialogDomain = folder.domain
                showAddDialog = true
              },
              onEditAccount = { entry ->
                editingEntry = entry
              },
              onDeleteAccount = { entry ->
                entryToDelete = entry
              },
              onOpenUrl = onOpenUrl
            )

            Spacer(modifier = Modifier.height(10.dp))
          }
        }

        item {
          Spacer(modifier = Modifier.height(80.dp))
        }
      }
    }
  }
}

// -------------------------------------------------------------
// WEBSITE FOLDER CARD COMPONENT
// -------------------------------------------------------------
@Composable
private fun WebsiteFolderCard(
  folder: WebsiteFolder,
  isExpanded: Boolean,
  clipboard: ClipboardManager,
  onToggleExpand: () -> Unit,
  onAddAccount: () -> Unit,
  onEditAccount: (DecryptedPasswordEntry) -> Unit,
  onDeleteAccount: (DecryptedPasswordEntry) -> Unit,
  onOpenUrl: ((String) -> Unit)?,
) {
  val context = LocalContext.current

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
    border = BorderStroke(
      1.dp,
      if (folder.weakCount > 0) ThemeCyber.colors.dangerRed.copy(alpha = 0.4f)
      else if (isExpanded) ThemeCyber.colors.primary.copy(alpha = 0.6f)
      else ThemeCyber.colors.surfaceBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("folder_card_${folder.domain}")
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Folder Header Bar
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggleExpand)
          .padding(14.dp)
      ) {
        // Website Domain Initials Avatar Badge
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
              Brush.linearGradient(
                listOf(
                  ThemeCyber.colors.primary.copy(alpha = 0.25f),
                  ThemeCyber.colors.surfaceLight
                )
              )
            )
        ) {
          Text(
            text = folder.displayName.take(1).uppercase(),
            color = ThemeCyber.colors.primary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            fontFamily = ThemeCyber.fontFamily
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = folder.displayName,
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              fontFamily = ThemeCyber.fontFamily,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "(${folder.domain})",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 11.5.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          Spacer(modifier = Modifier.height(2.dp))

          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "${folder.entries.size} ${if (folder.entries.size == 1) "account" else "accounts"}",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 11.5.sp,
              fontFamily = ThemeCyber.fontFamily
            )

            if (folder.weakCount > 0) {
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "• ${folder.weakCount} weak",
                color = ThemeCyber.colors.dangerRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = ThemeCyber.fontFamily
              )
            }
          }
        }

        // Add account to this website folder button
        IconButton(
          onClick = onAddAccount,
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add account to ${folder.domain}",
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(19.dp)
          )
        }

        // Expand/Collapse Chevron
        IconButton(
          onClick = onToggleExpand,
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle Folder",
            tint = ThemeCyber.colors.textSecondary
          )
        }
      }

      // Collapsible Accounts List inside this Website Folder
      AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
        ) {
          folder.entries.forEachIndexed { index, entry ->
            AccountEntryCard(
              entry = entry,
              clipboard = clipboard,
              onEdit = { onEditAccount(entry) },
              onDelete = { onDeleteAccount(entry) },
              onOpenUrl = onOpenUrl
            )
            if (index < folder.entries.size - 1) {
              Spacer(modifier = Modifier.height(8.dp))
            }
          }
        }
      }
    }
  }
}

// -------------------------------------------------------------
// ACCOUNT CREDENTIAL ITEM CARD
// -------------------------------------------------------------
@Composable
private fun AccountEntryCard(
  entry: DecryptedPasswordEntry,
  clipboard: ClipboardManager,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenUrl: ((String) -> Unit)?,
) {
  val context = LocalContext.current
  var isPasswordVisible by remember { mutableStateOf(false) }
  val strength = remember(entry.password) { PasswordManagerUtils.evaluateStrength(entry.password) }

  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
    border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      // Username & Strength Row
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Text(
            text = entry.username.ifBlank { "(No Username)" },
            color = ThemeCyber.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.5.sp,
            fontFamily = ThemeCyber.fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          Spacer(modifier = Modifier.width(6.dp))
          IconButton(
            onClick = {
              clipboard.copyWithAutoClear(entry.username, label = "Username", clearAfterMs = 30000)
              Toast.makeText(context, "Username copied (Auto-clears in 30s)", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(24.dp)
          ) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = "Copy Username",
              tint = ThemeCyber.colors.textSecondary,
              modifier = Modifier.size(14.dp)
            )
          }
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(strength.color.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
          Text(
            text = strength.label,
            color = strength.color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = ThemeCyber.fontFamily
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Password Row with Show/Hide (Biometric Protected) and 1-tap Copy (Biometric Protected)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(ThemeCyber.colors.surface)
          .padding(horizontal = 10.dp, vertical = 6.dp)
      ) {
        Text(
          text = if (isPasswordVisible) entry.password else "••••••••••••",
          color = ThemeCyber.colors.textPrimary,
          fontSize = 13.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.weight(1f)
        )

        IconButton(
          onClick = {
            if (!isPasswordVisible) {
              val activity = context as? FragmentActivity
              promptBiometricAuth(
                activity = activity,
                title = "View Password",
                subtitle = "Confirm biometric or device lock to reveal password",
                onSuccess = { isPasswordVisible = true }
              )
            } else {
              isPasswordVisible = false
            }
          },
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = "View Password",
            tint = ThemeCyber.colors.textSecondary,
            modifier = Modifier.size(16.dp)
          )
        }

        IconButton(
          onClick = {
            val activity = context as? FragmentActivity
            promptBiometricAuth(
              activity = activity,
              title = "Copy Password",
              subtitle = "Confirm biometric or device lock to copy password",
              onSuccess = {
                clipboard.copyWithAutoClear(entry.password, label = "Vault Password", clearAfterMs = 30000)
                Toast.makeText(context, "Password copied! Auto-clears in 30s.", Toast.LENGTH_SHORT).show()
              }
            )
          },
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy Password",
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(16.dp)
          )
        }
      }

      if (entry.notes.isNotBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Note: ${entry.notes}",
          color = ThemeCyber.colors.textSecondary,
          fontSize = 11.sp,
          fontFamily = ThemeCyber.fontFamily,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Card Bottom Action Row
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
      ) {
        if (onOpenUrl != null && entry.url.isNotBlank()) {
          IconButton(
            onClick = { onOpenUrl(entry.url) },
            modifier = Modifier.size(28.dp)
          ) {
            Icon(
              imageVector = Icons.Default.OpenInBrowser,
              contentDescription = "Open in Browser",
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(16.dp)
            )
          }
          Spacer(modifier = Modifier.width(4.dp))
        }

        IconButton(
          onClick = onEdit,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "Edit",
            tint = ThemeCyber.colors.textSecondary,
            modifier = Modifier.size(16.dp)
          )
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
          onClick = onDelete,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "Delete",
            tint = ThemeCyber.colors.dangerRed,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}

// -------------------------------------------------------------
// QUICK ACTION BADGE
// -------------------------------------------------------------
@Composable
private fun QuickActionBadge(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  badge: String?,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
    modifier = modifier.clickable(onClick = onClick)
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
      Box {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = ThemeCyber.colors.primary,
          modifier = Modifier.size(20.dp)
        )
        if (badge != null) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .align(Alignment.TopEnd)
              .size(14.dp)
              .clip(CircleShape)
              .background(ThemeCyber.colors.dangerRed)
          ) {
            Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = label,
        color = ThemeCyber.colors.textPrimary,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = ThemeCyber.fontFamily,
        textAlign = TextAlign.Center
      )
    }
  }
}

// -------------------------------------------------------------
// FILTER CHIP ITEM
// -------------------------------------------------------------
@Composable
private fun FilterChipItem(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .background(if (selected) ThemeCyber.colors.primary else ThemeCyber.colors.surface)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 6.dp)
  ) {
    Text(
      text = label,
      color = if (selected) Color.Black else ThemeCyber.colors.textSecondary,
      fontSize = 12.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      fontFamily = ThemeCyber.fontFamily
    )
  }
}

// -------------------------------------------------------------
// EMPTY VAULT VIEW
// -------------------------------------------------------------
@Composable
private fun EmptyVaultFolderView(
  isSearching: Boolean,
  onAddClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 48.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(64.dp)
        .clip(CircleShape)
        .background(ThemeCyber.colors.surfaceLight)
    ) {
      Icon(
        imageVector = if (isSearching) Icons.Default.Search else Icons.Default.FolderOpen,
        contentDescription = null,
        tint = ThemeCyber.colors.primary,
        modifier = Modifier.size(32.dp)
      )
    }

    Spacer(modifier = Modifier.height(14.dp))

    Text(
      text = if (isSearching) "No matching website folders found" else "Vault is Empty",
      color = ThemeCyber.colors.textPrimary,
      fontWeight = FontWeight.Bold,
      fontSize = 16.sp,
      fontFamily = ThemeCyber.fontFamily
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = if (isSearching) "Try a different search term or domain name"
      else "Save passwords to organize them automatically by website folders",
      color = ThemeCyber.colors.textSecondary,
      fontSize = 12.sp,
      fontFamily = ThemeCyber.fontFamily,
      textAlign = TextAlign.Center
    )

    if (!isSearching) {
      Spacer(modifier = Modifier.height(16.dp))
      Button(
        onClick = onAddClick,
        colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
        Spacer(modifier = Modifier.width(6.dp))
        Text("Add First Password", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = ThemeCyber.fontFamily)
      }
    }
  }
}

// -------------------------------------------------------------
// VAULT SETTINGS SHEET
// -------------------------------------------------------------
@Composable
private fun VaultSettingsSheet(
  repo: PasswordManagerRepository,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var isBiometricAvailable by remember { mutableStateOf(repo.isBiometricAvailable()) }
  var isAutoWipeEnabled by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.5.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
        .testTag("dialog_vault_settings")
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(Icons.Default.Settings, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(24.dp))
          Spacer(modifier = Modifier.width(10.dp))
          Text("Vault Security Settings", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.5.sp, fontFamily = ThemeCyber.fontFamily)
          Spacer(modifier = Modifier.weight(1f))
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textSecondary)
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Military Encryption Specs Card
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text("Encryption Architecture", color = ThemeCyber.colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, fontFamily = ThemeCyber.fontFamily)
            Spacer(modifier = Modifier.height(4.dp))
            Text("• Cipher: AES-256-GCM (Authenticated Encryption)\n• Key Derivation: Argon2id with 64MB memory limit\n• Tamper Seal: HMAC-SHA256 zero-knowledge\n• Zeroization: Plaintext keys wiped in memory after decrypt", color = ThemeCyber.colors.textSecondary, fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = ThemeCyber.fontFamily)
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Auto-Lock Option
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(12.dp).fillMaxWidth()
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Auto-Wipe after 10 failed attempts", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, fontFamily = ThemeCyber.fontFamily)
              Text("Prevents brute-force hardware extraction", color = ThemeCyber.colors.textSecondary, fontSize = 11.sp, fontFamily = ThemeCyber.fontFamily)
            }
            Switch(
              checked = isAutoWipeEnabled,
              onCheckedChange = {
                isAutoWipeEnabled = it
                scope.launch { repo.setAutoWipe(it) }
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = ThemeCyber.colors.primary,
                checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.4f)
              )
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
          onClick = {
            repo.lockVault()
            onDismiss()
          },
          colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Black)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Lock Vault Immediately", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
        }
      }
    }
  }
}
