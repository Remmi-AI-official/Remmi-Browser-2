package com.remmi.browser.security.autofill

import android.content.Context
import android.util.Log
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.Autocomplete

data class SavePasswordPromptRequest(
  val tabId: String = "",
  val origin: String,
  val username: String,
  val password: String = "",
  val onSave: () -> Unit,
  val onDismiss: () -> Unit,
)

data class SelectPasswordPromptRequest(
  val tabId: String,
  val origin: String,
  val options: List<Autocomplete.LoginSelectOption>,
  val onSelect: (Autocomplete.LoginSelectOption) -> Unit,
  val onDismiss: () -> Unit,
)

/**
 * REMmi Cyber Autofill & GeckoView Credential Delegate Coordinator.
 * Coordinates native GeckoView login selection and save prompts with Compose UI.
 */
class PasswordAutofillCoordinator(
  private val context: Context,
  private val scope: CoroutineScope,
  private val passwordRepo: PasswordManagerRepository,
) {

  private val _savePrompt = MutableStateFlow<SavePasswordPromptRequest?>(null)
  val savePrompt: StateFlow<SavePasswordPromptRequest?> = _savePrompt.asStateFlow()

  private val _selectPrompt = MutableStateFlow<SelectPasswordPromptRequest?>(null)
  val selectPrompt: StateFlow<SelectPasswordPromptRequest?> = _selectPrompt.asStateFlow()

  private val _showFortKnoxNotice = MutableStateFlow(false)
  val showFortKnoxNotice: StateFlow<Boolean> = _showFortKnoxNotice.asStateFlow()

  companion object {
    private const val TAG = "PasswordAutofillCoord"
  }

  init {
    checkFortKnox()
    setupBlockExtensionBridge()
  }

  private fun setupBlockExtensionBridge() {
    val blockExtension = com.remmi.adblock.BlockExtension.getInstance()
    blockExtension.addAuthFormListener { tabId, origin, user, pass ->
      if (pass.isNotBlank()) {
        scope.launch(Dispatchers.Main) {
          requestLoginSave(
            tabId = tabId,
            origin = origin,
            username = user,
            password = pass,
            onSave = {
              // Direct save is already performed inside requestLoginSave; avoid duplicate secondary save
              Log.i(TAG, "Saved password confirmed via WebExtension bridge for origin: $origin")
            },
            onDismiss = {}
          )
        }
      }
    }

    blockExtension.addAuthFocusListener { tabId, origin, isPassword ->
      scope.launch(Dispatchers.IO) {
        if (passwordRepo.isUnlocked()) {
          val logins = passwordRepo.getLoginEntriesForOrigin(origin)
          if (logins.isNotEmpty()) {
            val options = logins.map { entry ->
              Autocomplete.LoginSelectOption(entry)
            }
            scope.launch(Dispatchers.Main) {
              if (_selectPrompt.value == null) {
                requestLoginSelect(
                  tabId = tabId,
                  origin = origin,
                  options = options,
                  onSelect = { option ->
                    val chosen = option.value
                    if (chosen != null) {
                      blockExtension.sendAutofillCredentials(tabId, chosen.username ?: "", chosen.password ?: "")
                    }
                  },
                  onDismiss = {}
                )
              }
            }
          }
        }
      }
    }
  }

  fun checkFortKnox() {
    if (passwordRepo.isFortKnoxInstalled()) {
      _showFortKnoxNotice.value = true
    }
  }

  fun dismissFortKnoxNotice() {
    _showFortKnoxNotice.value = false
  }

  fun dismissSavePrompt() {
    _savePrompt.value?.onDismiss?.invoke()
    _savePrompt.value = null
  }

  fun dismissSelectPrompt() {
    _selectPrompt.value?.onDismiss?.invoke()
    _selectPrompt.value = null
  }

  fun requestLoginSelect(
    tabId: String,
    origin: String,
    options: List<Autocomplete.LoginSelectOption>,
    onSelect: (Autocomplete.LoginSelectOption) -> Unit,
    onDismiss: () -> Unit,
  ) {
    _selectPrompt.value = SelectPasswordPromptRequest(
      tabId = tabId,
      origin = origin,
      options = options,
      onSelect = { option ->
        onSelect(option)
        _selectPrompt.value = null
      },
      onDismiss = {
        onDismiss()
        _selectPrompt.value = null
      }
    )
  }

  fun requestLoginSave(
    tabId: String,
    origin: String,
    username: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
  ) {
    requestLoginSave(tabId, origin, username, "", onSave, onDismiss)
  }

  fun requestLoginSave(
    tabId: String,
    origin: String,
    username: String,
    password: String = "",
    onSave: () -> Unit,
    onDismiss: () -> Unit,
  ) {
    val canonical = PasswordCryptoEngine.canonicalizeOrigin(origin) ?: origin
    if (canonical.isBlank()) {
      Log.i(TAG, "Refusing password save on empty origin ($origin)")
      onDismiss()
      return
    }

    if (passwordRepo.isFortKnoxInstalled()) {
      Log.i(TAG, "Suppressed password save because FortKnox is installed")
      onDismiss()
      return
    }

    if (!passwordRepo.isUnlocked()) {
      Log.i(TAG, "Suppressed password save because Vault is locked")
      onDismiss()
      return
    }

    if (!canonical.startsWith("https://") && !origin.startsWith("https://")) {
      Log.i(TAG, "Refusing password save on insecure origin ($origin)")
      onDismiss()
      return
    }

    _savePrompt.value = SavePasswordPromptRequest(
      tabId = tabId,
      origin = canonical,
      username = username,
      password = password,
      onSave = {
        scope.launch(Dispatchers.IO) {
          try {
            if (password.isNotEmpty() && passwordRepo.isUnlocked()) {
              passwordRepo.saveOrUpdateEntry(
                url = canonical,
                username = username,
                password = password,
              )
              Log.i(TAG, "Direct coordinator save successful for origin: $canonical user: $username")
            }
          } catch (e: Exception) {
            Log.w(TAG, "Failed saving password direct in coordinator: ${e.message}")
          }
        }
        onSave()
        _savePrompt.value = null
      },
      onDismiss = {
        onDismiss()
        _savePrompt.value = null
      }
    )
  }
}
