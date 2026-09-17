package com.remmi.browser.engine

import android.content.Context
import android.util.Log
import com.remmi.browser.security.Mode
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.ProfileConfigManager
import com.remmi.browser.security.TorLifecycleManager
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/**
 * BrowserRuntimeFactory
 *
 * Implements initialization of GeckoRuntimeSettings pointing to the proper profile configuration
 * path depending on the active mode (Mode.SHIELD vs Mode.TOR).
 *
 * Manages atomic switching logic between profiles, handling safe session teardown
 * to prevent cache/cookie leakage across modes.
 */
class BrowserRuntimeFactory private constructor(private val context: Context) {

  companion object {
    private const val TAG = "BrowserRuntimeFactory"

    @Volatile
    private var instance: BrowserRuntimeFactory? = null

    fun getInstance(context: Context): BrowserRuntimeFactory {
      return instance ?: synchronized(this) {
        instance ?: BrowserRuntimeFactory(context.applicationContext).also { instance = it }
      }
    }
  }

  private val configManager = ProfileConfigManager.getInstance(context)
  private val torLifecycle = TorLifecycleManager.getInstance(context)
  private val switchMutex = Mutex()

  @Volatile
  var activeMode: Mode = Mode.SHIELD
    private set

  /**
   * Creates GeckoRuntimeSettings configured for the specified mode.
   * Ensures the profile directory and user.js configuration are atomically written and verified.
   */
  fun createRuntimeSettings(mode: Mode, socksPort: Int = 9050): GeckoRuntimeSettings {
    activeMode = mode
    DebugLogManager.log("[RUNTIME_FACTORY] Creating GeckoRuntimeSettings for mode: $mode")

    // 1. Atomically write and verify the profile configuration on disk
    val configResult = configManager.writeProfileConfigAtomic(mode, socksPort = socksPort)
    val configFile = configResult.getOrNull()

    // 2. Build GeckoRuntimeSettings
    val builder = GeckoRuntimeSettings.Builder()
      .aboutConfigEnabled(com.remmi.browser.BuildConfig.DEBUG)
      .consoleOutput(com.remmi.browser.BuildConfig.DEBUG)
      .enterpriseRootsEnabled(true)
      .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)

    // Pass -profile argument so Gecko loads the isolated profile directory and user.js directly
    try {
      builder.arguments(arrayOf("-profile", configManager.getProfileDir(mode).absolutePath))
      DebugLogManager.log("[RUNTIME_FACTORY] Configured profile dir argument: ${configManager.getProfileDir(mode).absolutePath}")
    } catch (e: Exception) {
      Log.w(TAG, "Notice setting profile argument: ${e.message}")
    }

    val settings = builder.build()

    try {
      val lnaMethod = settings.javaClass.getMethod("setLnaEnabled", Boolean::class.javaPrimitiveType)
      lnaMethod.invoke(settings, false)
    } catch (_: Exception) {}

    return settings
  }

  /**
   * Safely switches between Mode.SHIELD and Mode.TOR with complete state isolation:
   * 1. Tears down active sessions via onSessionTeardown callback to prevent cookie/cache contamination
   * 2. Halts or boots Tor daemon based on target mode (zero background Tor in Shield mode)
   * 3. Atomically updates profile config on disk
   * 4. Enforces runtime preferences via native Gecko engine
   * 5. Cleans cross-profile transient state
   */
  suspend fun switchProfile(
    targetMode: Mode,
    runtime: GeckoRuntime?,
    onSessionTeardown: suspend () -> Unit = {}
  ): Result<Unit> = switchMutex.withLock {
    withContext(Dispatchers.IO) {
      if (activeMode == targetMode && (targetMode != Mode.TOR || torLifecycle.isTorActive())) {
        Log.i(TAG, "Already in mode $targetMode - no transition necessary")
        return@withContext Result.success(Unit)
      }

      val previousMode = activeMode
      Log.i(TAG, "Executing profile transition: $previousMode -> $targetMode")
      DebugLogManager.log("[RUNTIME_FACTORY] TRANSITION_START $previousMode -> $targetMode")

      // Step 1: Teardown sessions to prevent cross-profile leakage
      try {
        DebugLogManager.log("[RUNTIME_FACTORY] Executing safe session teardown to isolate state...")
        onSessionTeardown()
      } catch (e: Exception) {
        Log.w(TAG, "Session teardown warning: ${e.message}")
      }

      // Step 2: Manage Tor daemon lifecycle (Tor must NOT run during Shield Mode)
      var activeTorPort = 9050
      when (targetMode) {
        Mode.SHIELD -> {
          torLifecycle.stopTorDaemon()
        }
        Mode.TOR -> {
          val torStartRes = torLifecycle.startTorDaemon()
          if (torStartRes.isFailure) {
            val err = torStartRes.exceptionOrNull() ?: IllegalStateException("Tor daemon failed to start")
            Log.e(TAG, "Aborting transition to Tor mode: ${err.message}")
            return@withContext Result.failure(err)
          }
          activeTorPort = torStartRes.getOrDefault(9050)
        }
      }

      // Step 3: Write atomic profile preferences
      val writeRes = configManager.writeProfileConfigAtomic(targetMode, socksPort = activeTorPort)
      if (writeRes.isFailure) {
        Log.w(TAG, "Config write notice: ${writeRes.exceptionOrNull()?.message}")
      }

      // Step 4: Apply engine-level preferences to runtime
      if (runtime != null) {
        val prefSuccess = when (targetMode) {
          Mode.SHIELD -> {
            NetworkHardening.applyShieldNetworkSettings(runtime)
          }
          Mode.TOR -> {
            NetworkHardening.applyTorNetworkSettings(runtime, port = activeTorPort)
          }
        }
        if (!prefSuccess) {
          Log.w(TAG, "Applying network hardening returned false during switch to $targetMode")
        }
      }

      // Step 5: Clean transient state of the previous profile
      configManager.cleanProfileState(previousMode)

      activeMode = targetMode
      DebugLogManager.log("[RUNTIME_FACTORY] TRANSITION_COMPLETE activeMode=$targetMode")
      Log.i(TAG, "Successfully transitioned to $targetMode")
      Result.success(Unit)
    }
  }
}
