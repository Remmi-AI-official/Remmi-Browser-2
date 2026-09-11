package com.remmi.browser.security.permissions

import android.content.Context
import android.net.Uri
import android.util.Log
import com.remmi.browser.security.autofill.PasswordAutofillCoordinator
import com.remmi.browser.util.DebugLogManager
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

interface FilePickerRequester {
  fun launchFilePicker(
    mimeTypes: Array<String>?,
    allowMultiple: Boolean,
    onResult: (List<Uri>) -> Unit
  )
}

class RemmiPromptDelegate(
  private val context: Context,
  private val tabId: String,
  private val filePickerProvider: () -> FilePickerRequester?,
  private val autofillCoordinatorProvider: () -> PasswordAutofillCoordinator? = { null },
) : GeckoSession.PromptDelegate {

  override fun onFilePrompt(
    session: GeckoSession,
    prompt: GeckoSession.PromptDelegate.FilePrompt
  ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
    val allowMultiple = (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
    val mimeTypes = prompt.mimeTypes

    logSecurity("FILE_PICK_START", "tabId=$tabId allowMultiple=$allowMultiple mimeCount=${mimeTypes?.size ?: 0}")

    val requester = filePickerProvider()
    if (requester == null) {
      logSecurity("FILE_PICK_RESULT", "tabId=$tabId success=false reason=NO_PICKER_AVAILABLE")
      val response = prompt.dismiss()
      result.complete(response)
      return result
    }

    requester.launchFilePicker(mimeTypes, allowMultiple) { selectedUris ->
      if (selectedUris.isNotEmpty()) {
        logSecurity("FILE_PICK_RESULT", "tabId=$tabId count=${selectedUris.size} success=true")
        val response = if (allowMultiple) {
          prompt.confirm(context, selectedUris.toTypedArray())
        } else {
          prompt.confirm(context, selectedUris.first())
        }
        result.complete(response)
      } else {
        logSecurity("FILE_PICK_RESULT", "tabId=$tabId count=0 success=false reason=USER_CANCELLED")
        val response = prompt.dismiss()
        result.complete(response)
      }
    }

    return result
  }

  override fun onLoginSelect(
    session: GeckoSession,
    prompt: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>
  ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
    val options = prompt.options
    if (options == null || options.isEmpty()) {
      result.complete(prompt.dismiss())
      return result
    }

    val coordinator = autofillCoordinatorProvider()
    if (coordinator == null) {
      result.complete(prompt.dismiss())
      return result
    }

    val origin = prompt.title ?: ""
    coordinator.requestLoginSelect(
      tabId = tabId,
      origin = origin,
      options = options.toList(),
      onSelect = { selectedOption ->
        result.complete(prompt.confirm(selectedOption))
      },
      onDismiss = {
        result.complete(prompt.dismiss())
      }
    )

    return result
  }

  override fun onLoginSave(
    session: GeckoSession,
    prompt: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>
  ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
    val options = prompt.options
    val firstOption = options?.firstOrNull()
    if (firstOption == null) {
      result.complete(prompt.dismiss())
      return result
    }

    val coordinator = autofillCoordinatorProvider()
    if (coordinator == null) {
      result.complete(prompt.dismiss())
      return result
    }

    val loginEntry = firstOption.value
    val origin = loginEntry?.origin ?: prompt.title ?: ""
    val username = loginEntry?.username ?: ""

    coordinator.requestLoginSave(
      tabId = tabId,
      origin = origin,
      username = username,
      onSave = {
        result.complete(prompt.confirm(firstOption))
      },
      onDismiss = {
        result.complete(prompt.dismiss())
      }
    )

    return result
  }

  private fun logSecurity(event: String, details: String) {
    val msg = "[PERMISSION][$event] $details timestamp=${System.currentTimeMillis()}"
    Log.i(TAG, msg)
    DebugLogManager.log(msg)
  }

  companion object {
    private const val TAG = "RemmiPromptDelegate"
  }
}
