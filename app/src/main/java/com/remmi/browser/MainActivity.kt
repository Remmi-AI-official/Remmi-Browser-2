package com.remmi.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.remmi.browser.engine.TabManager
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.PanicWipeManager
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.passwords.PasswordManagerScreen
import com.remmi.browser.ui.screens.BrowserScreen
import com.remmi.browser.ui.screens.DebugLogsScreen
import com.remmi.browser.ui.screens.SettingsScreen
import com.remmi.browser.ui.screens.WelcomeScreen
import com.remmi.browser.ui.theme.RemmiTheme

import com.remmi.browser.ui.components.CrashReportDialog
import com.remmi.browser.util.CrashExportResult
import com.remmi.browser.util.CrashHandlerHelper

enum class ScreenRoute {
  WELCOME,
  BROWSER,
  SETTINGS,
  PASSWORDS,
  DEBUG_LOGS,
  EMERGENCY_RECOVERY,
  VAULT_RECOVERY
}

class MainActivity : FragmentActivity() {

  private val pendingPermissionCallbacks = mutableListOf<Pair<Array<String>, (Map<String, Boolean>) -> Unit>>()
  private val requestPermissionLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
  ) { _ ->
    val currentCallbacks = synchronized(pendingPermissionCallbacks) {
      val copy = ArrayList(pendingPermissionCallbacks)
      pendingPermissionCallbacks.clear()
      copy
    }
    for ((perms, cb) in currentCallbacks) {
      val resultMap = perms.associateWith { perm ->
        androidx.core.content.ContextCompat.checkSelfPermission(
          this,
          perm
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
      }
      cb.invoke(resultMap)
    }
  }

  private var pendingFilePickerCallback: ((List<android.net.Uri>) -> Unit)? = null
  private val filePickerLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    val cb = pendingFilePickerCallback
    pendingFilePickerCallback = null
    cb?.invoke(uris ?: emptyList())
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    val startTime = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i("AppStartup", "STATE_LOG: APP_START (time=$startTime)")

    super.onCreate(savedInstanceState)
    com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this, com.remmi.browser.util.StartupPhase.MAIN_ACTIVITY_CREATE)

    // Bridge runtime permissions & secure file picker
    val permissionManager = com.remmi.browser.security.permissions.PermissionSessionManager.getInstance()
    permissionManager.androidPermissionRequester = object : com.remmi.browser.security.permissions.AndroidPermissionRequester {
      override fun requestAndroidPermissions(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit
      ) {
        val ungranted = permissions.filter {
          androidx.core.content.ContextCompat.checkSelfPermission(
            this@MainActivity,
            it
          ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isEmpty()) {
          val grantedMap = permissions.associateWith { true }
          onResult(grantedMap)
        } else {
          synchronized(pendingPermissionCallbacks) {
            pendingPermissionCallbacks.add(permissions to onResult)
          }
          requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
      }
    }

    val engineManager = com.remmi.browser.engine.GeckoEngineManager.getInstance(applicationContext)
    engineManager.filePickerRequester = object : com.remmi.browser.security.permissions.FilePickerRequester {
      override fun launchFilePicker(
        mimeTypes: Array<String>?,
        allowMultiple: Boolean,
        onResult: (List<android.net.Uri>) -> Unit
      ) {
        pendingFilePickerCallback = onResult
        val types = if (mimeTypes.isNullOrEmpty()) arrayOf("*/*") else mimeTypes
        try {
          filePickerLauncher.launch(types)
        } catch (e: Exception) {
          android.util.Log.e("MainActivity", "File picker launch failed: ${e.message}")
          onResult(emptyList())
        }
      }
    }

    enableEdgeToEdge()

    val isPendingWipe = PanicWipeManager.isWipePending(applicationContext)

    val prefs = getSharedPreferences("remmi_app_state", Context.MODE_PRIVATE)
    val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)
    val settingsRepo = SettingsRepository.getInstance(applicationContext)

    val incomingUrl = handleIncomingIntent(intent)
    val initialScreen = when {
      isPendingWipe -> ScreenRoute.EMERGENCY_RECOVERY
      incomingUrl != null || hasSeenOnboarding -> ScreenRoute.BROWSER
      else -> ScreenRoute.WELCOME
    }

    android.util.Log.i("AppStartup", "STATE_LOG: UI_CONTENT_SET (time=${android.os.SystemClock.elapsedRealtime()})")

    setContent {
      val settings by settingsRepo.settings.collectAsState()
      val tabManager = remember { TabManager.getInstance() }
      var crashResultToShow by remember { mutableStateOf<CrashExportResult?>(null) }

      androidx.compose.runtime.LaunchedEffect(Unit) {
        android.util.Log.i("AppStartup", "STATE_LOG: FIRST_FRAME (time=${android.os.SystemClock.elapsedRealtime()})")
        com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this@MainActivity, com.remmi.browser.util.StartupPhase.FIRST_FRAME)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
          val pendingCrash = com.remmi.browser.util.CrashHandlerHelper.checkAndExportPendingReport(this@MainActivity)
          if (pendingCrash != null) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
              crashResultToShow = pendingCrash
              val label = if (pendingCrash.reportType == com.remmi.browser.util.ReportType.ABNORMAL_TERMINATION) {
                "Abnormal termination report"
              } else {
                "Crash report"
              }
              android.widget.Toast.makeText(
                this@MainActivity,
                "$label stored privately on this device",
                android.widget.Toast.LENGTH_LONG
              ).show()
            }
          }
        }
        com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this@MainActivity, com.remmi.browser.util.StartupPhase.APP_READY)
      }

      RemmiTheme(
        appearanceMode = settings.appearanceMode,
        cyberTheme = settings.cyberTheme,
        cyberHudEnabled = settings.cyberHudEnabled,
        pureBlackOled = settings.pureBlackOled,
        browserFont = settings.browserFont,
      ) {
        var currentScreen by remember {
          mutableStateOf(initialScreen)
        }
        
        val dbState by com.remmi.browser.storage.RemmiDatabase.databaseState.collectAsState()
        androidx.compose.runtime.LaunchedEffect(dbState) {
          if (dbState is com.remmi.browser.storage.RemmiDatabase.DatabaseState.Error) {
             val err = (dbState as com.remmi.browser.storage.RemmiDatabase.DatabaseState.Error).throwable
             if (err is com.remmi.browser.storage.VaultRecoveryRequiredException) {
                 currentScreen = ScreenRoute.VAULT_RECOVERY
             }
          }
        }

        if (crashResultToShow != null) {
          CrashReportDialog(
            crashResult = crashResultToShow!!,
            onDismiss = { crashResultToShow = null }
          )
        }

        val isOverlayActive = currentScreen in listOf(ScreenRoute.SETTINGS, ScreenRoute.PASSWORDS, ScreenRoute.DEBUG_LOGS)
        val isRecoveryOrWelcome = currentScreen in listOf(ScreenRoute.EMERGENCY_RECOVERY, ScreenRoute.VAULT_RECOVERY, ScreenRoute.WELCOME)

        if (isRecoveryOrWelcome) {
          val screenMsg = "[FORENSIC][SCREEN_ROUTE] targetScreen=$currentScreen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          android.util.Log.i("MainActivity", screenMsg)
          com.remmi.browser.util.DebugLogManager.log(screenMsg)
          when (currentScreen) {
            ScreenRoute.EMERGENCY_RECOVERY -> {
              com.remmi.browser.ui.screens.EmergencyWipeRecoveryScreen(
                onRecoveryComplete = {
                  com.remmi.browser.engine.GeckoEngineManager.getInstance(applicationContext).initializeRuntimeAsync()
                  currentScreen = ScreenRoute.BROWSER
                }
              )
            }
            ScreenRoute.VAULT_RECOVERY -> {
              com.remmi.browser.ui.screens.VaultRecoveryScreen(
                onProceedToWipe = {
                  com.remmi.browser.security.PanicWipeManager.markWipeInProgress(this@MainActivity, wipeVault = true)
                  currentScreen = ScreenRoute.EMERGENCY_RECOVERY
                }
              )
            }
            ScreenRoute.WELCOME -> {
              WelcomeScreen(
                onEnterBrowser = {
                  prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                  currentScreen = ScreenRoute.BROWSER
                }
              )
            }
            else -> {}
          }
        } else {
          // Browser is always kept mounted in composition tree so GeckoView / sessions never unmount
          BrowserScreen(
            onOpenSettings = {
              currentScreen = ScreenRoute.SETTINGS
            },
            onOpenWelcome = {
              currentScreen = ScreenRoute.WELCOME
            },
            onOpenPasswords = {
              currentScreen = ScreenRoute.PASSWORDS
            }
          )

          if (isOverlayActive) {
            val screenMsg = "[FORENSIC][SCREEN_ROUTE] overlayScreen=$currentScreen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
            android.util.Log.i("MainActivity", screenMsg)
            com.remmi.browser.util.DebugLogManager.log(screenMsg)
            when (currentScreen) {
              ScreenRoute.SETTINGS -> {
                SettingsScreen(
                  onBack = {
                    currentScreen = ScreenRoute.BROWSER
                  },
                  onOpenPasswords = {
                    currentScreen = ScreenRoute.PASSWORDS
                  },
                  onOpenDebugLogs = {
                    currentScreen = ScreenRoute.DEBUG_LOGS
                  },
                  onOpenUrl = { url ->
                    currentScreen = ScreenRoute.BROWSER
                    tabManager.openTab(url = url, profile = PrivacyProfile.GHOST)
                  }
                )
              }
              ScreenRoute.PASSWORDS -> {
                PasswordManagerScreen(
                  onBack = {
                    currentScreen = ScreenRoute.SETTINGS
                  }
                )
              }
              ScreenRoute.DEBUG_LOGS -> {
                DebugLogsScreen(
                  onBack = {
                    currentScreen = ScreenRoute.SETTINGS
                  }
                )
              }
              else -> {}
            }
          }
        }
      }
    }
  }

  override fun onStop() {
    super.onStop()
    com.remmi.browser.security.permissions.PermissionSessionManager.getInstance().onAppBackgrounded()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    try {
      com.remmi.browser.engine.TabThumbnailManager.getInstance(this).onTrimMemory(level)
      com.remmi.browser.engine.GeckoEngineManager.getInstance(this).onTrimMemory(level)
    } catch (_: Throwable) {}
  }

  override fun onLowMemory() {
    super.onLowMemory()
    try {
      com.remmi.browser.engine.TabThumbnailManager.getInstance(this).onLowMemory()
      com.remmi.browser.engine.GeckoEngineManager.getInstance(this).onLowMemory()
    } catch (_: Throwable) {}
  }

  override fun onDestroy() {
    if (isFinishing) {
      com.remmi.browser.util.CrashHandlerHelper.markCleanShutdown(this)
    }
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  private fun handleIncomingIntent(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null

    val data = uri.toString()
    // External VIEW intents are untrusted input; TabManager applies the same
    // navigation authority as in-app navigation before creating/loading a tab.
    TabManager.getInstance().openOrNavigateTab(url = data)
    return data
  }
}
