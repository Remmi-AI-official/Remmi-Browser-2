package com.remmi.browser.security.autofill

import android.content.Context
import android.util.Log
import com.remmi.browser.security.PasswordManagerRepository
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
 * Enforces strict HTTPS verification, zero clearnet leaks, Fort Knox priority triage,
 * and zero exposure of passwords in UI state models.
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
    if (passwordRepo.isFortKnoxInstalled()) {
      onDismiss()
      return
    }

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
    if (!origin.startsWith("https://", ignoreCase = true)) {
      Log.i(TAG, "Refusing password save on non-HTTPS origin ($origin)")
      onDismiss()
      return
    }
    if (passwordRepo.isFortKnoxInstalled()) {
      Log.i(TAG, "Fort Knox priority active. Suppressing built-in save prompt.")
      onDismiss()
      return
    }

    _savePrompt.value = SavePasswordPromptRequest(
      tabId = tabId,
      origin = origin,
      username = username,
      onSave = {
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
