package com.remmi.browser.extensions

import android.content.Context
import android.net.Uri
import android.util.Log
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * UI representation of a WebExtension installed in GeckoView.
 */
data class ExtensionInfo(
  val id: String,
  val name: String,
  val description: String,
  val version: String,
  val isEnabled: Boolean,
  val isBuiltIn: Boolean,
  val allowedInPrivateBrowsing: Boolean,
  val iconUri: String? = null,
  val homepageUrl: String? = null,
  val webExtension: WebExtension? = null
)

/**
 * Manages GeckoView WebExtensions:
 * 1. Loading built-in extensions from assets.
 * 2. Installing .xpi files or web add-ons.
 * 3. Enabling / disabling extensions and managing private browsing permissions.
 * 4. Exposing live state via StateFlow to Compose UI.
 */
class ExtensionManager private constructor(
  private val context: Context,
  private val geckoEngineManager: GeckoEngineManager
) {

  companion object {
    private const val TAG = "ExtensionManager"

    @Volatile
    private var instance: ExtensionManager? = null

    fun getInstance(context: Context): ExtensionManager {
      return instance ?: synchronized(this) {
        instance ?: ExtensionManager(
          context.applicationContext,
          GeckoEngineManager.getInstance(context.applicationContext)
        ).also { instance = it }
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val _extensions = MutableStateFlow<List<ExtensionInfo>>(emptyList())
  val extensions: StateFlow<List<ExtensionInfo>> = _extensions.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  init {
    scope.launch {
      try {
        geckoEngineManager.ensureRuntime()
        refreshExtensions()
      } catch (e: Exception) {
        Log.w(TAG, "Runtime not ready on init: ${e.message}")
      }
    }
  }

  private suspend fun <T> GeckoResult<T>.awaitResult(): T? {
    return suspendCancellableCoroutine { continuation ->
      this.accept(
        { result ->
          continuation.resume(result)
        },
        { throwable ->
          continuation.resumeWithException(throwable ?: Exception("GeckoResult failed with null error"))
        }
      )
    }
  }

  /**
   * Refreshes the list of installed extensions from GeckoRuntime.
   */
  suspend fun refreshExtensions(): List<ExtensionInfo> = withContext(Dispatchers.Main) {
    _isLoading.value = true
    try {
      val runtime = geckoEngineManager.ensureRuntime()
      val webExtList = runtime.webExtensionController.list().awaitResult() ?: emptyList()

      val infos = webExtList.map { ext ->
        val meta = ext.metaData
        val isPrivateAllowed = (ext.flags and 1L) != 0L

        ExtensionInfo(
          id = ext.id,
          name = meta.name.takeIf { !it.isNullOrBlank() } ?: ext.id,
          description = meta.description ?: "",
          version = meta.version ?: "1.0",
          isEnabled = true,
          isBuiltIn = ext.isBuiltIn,
          allowedInPrivateBrowsing = isPrivateAllowed,
          iconUri = null,
          homepageUrl = meta.homepageUrl,
          webExtension = ext
        )
      }
      _extensions.value = infos
      _lastError.value = null
      DebugLogManager.log("[EXTENSION_MANAGER] Refreshed ${infos.size} extensions")
      infos
    } catch (e: Exception) {
      val err = "Failed to list extensions: ${e.message}"
      Log.e(TAG, err, e)
      _lastError.value = err
      emptyList()
    } finally {
      _isLoading.value = false
    }
  }

  /**
   * Loads built-in extensions packaged in assets/extensions/.
   */
  suspend fun loadBuiltInExtensionsFromAssets(): Result<List<ExtensionInfo>> = withContext(Dispatchers.IO) {
    try {
      val runtime = withContext(Dispatchers.Main) { geckoEngineManager.ensureRuntime() }
      val assetManager = context.assets
      val extensionDirs = try {
        assetManager.list("extensions") ?: emptyArray()
      } catch (e: Exception) {
        emptyArray()
      }

      for (dirName in extensionDirs) {
        val extensionUri = "resource://android/assets/extensions/$dirName/"
        try {
          withContext(Dispatchers.Main) {
            runtime.webExtensionController.installBuiltIn(extensionUri).awaitResult()
          }
          Log.i(TAG, "Successfully loaded built-in extension from $extensionUri")
        } catch (e: Exception) {
          Log.w(TAG, "Notice loading built-in extension $extensionUri: ${e.message}")
        }
      }

      val refreshed = refreshExtensions()
      Result.success(refreshed)
    } catch (e: Exception) {
      Log.e(TAG, "Error loading built-in extensions: ${e.message}", e)
      Result.failure(e)
    }
  }

  /**
   * Installs an extension from a given URI (e.g. file URI, content URI, or downloaded .xpi).
   */
  suspend fun installExtension(uriString: String): Result<ExtensionInfo> = withContext(Dispatchers.Main) {
    _isLoading.value = true
    try {
      val runtime = geckoEngineManager.ensureRuntime()
      Log.i(TAG, "Installing extension from: $uriString")
      DebugLogManager.log("[EXTENSION_MANAGER] Installing extension from: $uriString")

      val ext = runtime.webExtensionController.install(uriString).awaitResult()
      if (ext != null) {
        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
        val meta = ext.metaData
        val info = ExtensionInfo(
          id = ext.id,
          name = meta.name.takeIf { !it.isNullOrBlank() } ?: ext.id,
          description = meta.description ?: "",
          version = meta.version ?: "1.0",
          isEnabled = true,
          isBuiltIn = ext.isBuiltIn,
          allowedInPrivateBrowsing = true,
          iconUri = null,
          homepageUrl = meta.homepageUrl,
          webExtension = ext
        )
        refreshExtensions()
        Log.i(TAG, "Successfully installed extension: ${info.name} (${info.id})")
        DebugLogManager.log("[EXTENSION_MANAGER] Installed: ${info.name} (${info.id})")
        Result.success(info)
      } else {
        val err = "Extension controller returned null for install"
        _lastError.value = err
        Result.failure(IllegalStateException(err))
      }
    } catch (e: Exception) {
      val err = "Installation failed: ${e.message}"
      Log.e(TAG, err, e)
      _lastError.value = err
      DebugLogManager.log("[EXTENSION_MANAGER] Error: $err")
      Result.failure(e)
    } finally {
      _isLoading.value = false
    }
  }

  /**
   * Installs an extension from a local .xpi File.
   */
  suspend fun installFromFile(file: File): Result<ExtensionInfo> = withContext(Dispatchers.IO) {
    if (!file.exists()) {
      return@withContext Result.failure(IllegalArgumentException("File does not exist: ${file.absolutePath}"))
    }
    val fileUri = Uri.fromFile(file).toString()
    installExtension(fileUri)
  }

  /**
   * Installs an extension from an Android Content Uri (e.g. from File Picker).
   */
  suspend fun installFromContentUri(contentUri: Uri): Result<ExtensionInfo> = withContext(Dispatchers.IO) {
    try {
      val tempXpi = File(context.cacheDir, "extension_install_${System.currentTimeMillis()}.xpi")
      context.contentResolver.openInputStream(contentUri)?.use { input ->
        FileOutputStream(tempXpi).use { output ->
          input.copyTo(output)
        }
      } ?: return@withContext Result.failure(IllegalStateException("Unable to open stream for URI $contentUri"))

      val res = installFromFile(tempXpi)
      try {
        tempXpi.delete()
      } catch (_: Exception) {}
      res
    } catch (e: Exception) {
      Log.e(TAG, "Failed to copy and install XPI from content Uri: ${e.message}", e)
      Result.failure(e)
    }
  }

  /**
   * Enables or disables an installed extension.
   */
  suspend fun setExtensionEnabled(extensionId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.Main) {
    try {
      val runtime = geckoEngineManager.ensureRuntime()
      val ext = _extensions.value.firstOrNull { it.id == extensionId }?.webExtension
        ?: runtime.webExtensionController.list().awaitResult()?.firstOrNull { it.id == extensionId }
        ?: return@withContext Result.failure(IllegalArgumentException("Extension not found: $extensionId"))

      if (enabled) {
        runtime.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER).awaitResult()
      } else {
        runtime.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER).awaitResult()
      }
      refreshExtensions()
      Log.i(TAG, "Extension $extensionId enabled state set to $enabled")
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set enabled state for $extensionId: ${e.message}", e)
      Result.failure(e)
    }
  }

  /**
   * Updates permission to run in private / incognito / ghost tabs.
   */
  suspend fun setAllowedInPrivateBrowsing(extensionId: String, allowed: Boolean): Result<Unit> = withContext(Dispatchers.Main) {
    try {
      val runtime = geckoEngineManager.ensureRuntime()
      val ext = _extensions.value.firstOrNull { it.id == extensionId }?.webExtension
        ?: runtime.webExtensionController.list().awaitResult()?.firstOrNull { it.id == extensionId }
        ?: return@withContext Result.failure(IllegalArgumentException("Extension not found: $extensionId"))

      runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, allowed)
      refreshExtensions()
      Log.i(TAG, "Extension $extensionId allowed in private browsing set to $allowed")
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Failed setting private browsing permission for $extensionId: ${e.message}", e)
      Result.failure(e)
    }
  }

  /**
   * Uninstalls a user-installed extension.
   */
  suspend fun uninstallExtension(extensionId: String): Result<Unit> = withContext(Dispatchers.Main) {
    try {
      val runtime = geckoEngineManager.ensureRuntime()
      val ext = _extensions.value.firstOrNull { it.id == extensionId }?.webExtension
        ?: runtime.webExtensionController.list().awaitResult()?.firstOrNull { it.id == extensionId }
        ?: return@withContext Result.failure(IllegalArgumentException("Extension not found: $extensionId"))

      runtime.webExtensionController.uninstall(ext).awaitResult()
      refreshExtensions()
      Log.i(TAG, "Successfully uninstalled extension: $extensionId")
      DebugLogManager.log("[EXTENSION_MANAGER] Uninstalled: $extensionId")
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to uninstall $extensionId: ${e.message}", e)
      Result.failure(e)
    }
  }
}
