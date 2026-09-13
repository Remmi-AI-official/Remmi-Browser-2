package com.remmi.browser.security.autofill

import android.content.Context
import android.util.Log
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

/**
 * Native GeckoView Autocomplete.StorageDelegate implementation for REMmi.
 * Bridges GeckoView's internal login management with REMmi's encrypted password vault.
 * Enforces strict HTTPS origin matching, zero-leakage, and Fort Knox priority triage.
 */
class GeckoPasswordStorageDelegate(
  private val context: Context,
  private val passwordRepo: PasswordManagerRepository,
) : Autocomplete.StorageDelegate {

  private val scope = CoroutineScope(Dispatchers.IO)

  override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? {
    if (domain.isBlank()) {
      return GeckoResult.fromValue(emptyArray())
    }

    // Vault Lock Check
    if (!passwordRepo.isUnlocked()) {
      Log.d(TAG, "Vault locked. Suppressing credential fetch.")
      return GeckoResult.fromValue(emptyArray())
    }

    val canonical = PasswordCryptoEngine.canonicalizeOrigin(domain)
    if (canonical == null) {
      Log.w(TAG, "Refusing login fetch for invalid domain ($domain)")
      return GeckoResult.fromValue(emptyArray())
    }

    val result = GeckoResult<Array<Autocomplete.LoginEntry>>()
    scope.launch {
      try {
        val entries = passwordRepo.getLoginEntriesForOrigin(canonical)
        result.complete(entries.toTypedArray())
      } catch (e: Exception) {
        Log.w(TAG, "Failed retrieving logins for origin: ${e.message}")
        result.complete(emptyArray())
      }
    }

    return result
  }

  override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>>? {
    // We do not expose broad unconditional vault logins
    return GeckoResult.fromValue(emptyArray())
  }

  suspend fun saveLoginDirect(login: Autocomplete.LoginEntry) {
    if (!passwordRepo.isUnlocked()) return
    val origin = login.origin ?: return
    val canonicalOrigin = PasswordCryptoEngine.canonicalizeOrigin(origin) ?: return
    val username = login.username ?: ""
    val password = login.password ?: ""
    if (password.isEmpty()) return
    passwordRepo.saveOrUpdateEntry(
      url = canonicalOrigin,
      username = username,
      password = password,
    )
  }

  override fun onLoginSave(login: Autocomplete.LoginEntry) {
    if (!passwordRepo.isUnlocked()) {
      Log.w(TAG, "Cannot save login while vault is locked.")
      return
    }

    val origin = login.origin ?: return
    val canonicalOrigin = PasswordCryptoEngine.canonicalizeOrigin(origin)
    if (canonicalOrigin == null) {
      Log.w(TAG, "Refusing to save credentials for invalid origin")
      return
    }

    val username = login.username ?: ""
    val password = login.password ?: ""
    if (password.isEmpty()) {
      Log.w(TAG, "Cannot save empty password credential")
      return
    }

    scope.launch {
      try {
        passwordRepo.saveOrUpdateEntry(
          url = canonicalOrigin,
          username = username,
          password = password,
        )
        Log.i(TAG, "Credential securely stored for exact origin: $canonicalOrigin")
      } catch (e: Exception) {
        Log.w(TAG, "Failed saving credential: ${e.message}")
      }
    }
  }

  override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
    // Non-sensitive telemetry/logging
    Log.d(TAG, "Native login autofilled for origin=${login.origin} usedFields=$usedFields")
  }

  companion object {
    private const val TAG = "GeckoPasswordStorageDelegate"
  }
}
