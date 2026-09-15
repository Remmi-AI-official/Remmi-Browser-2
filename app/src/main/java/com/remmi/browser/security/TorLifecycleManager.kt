package com.remmi.browser.security

import android.content.Context
import android.util.Log
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TorDaemonState {
  DORMANT,
  STARTING,
  ACTIVE,
  STOPPING,
  FAILED;

  val isRunning: Boolean
    get() = this == ACTIVE
}

/**
 * TorLifecycleManager
 *
 * Exclusively manages the native Tor daemon lifecycle according to the active browser mode:
 * - In Shield Mode: Enforces that the Tor daemon service is fully stopped and DORMANT.
 *   Zero background sockets, zero daemon battery or RAM drain.
 * - In Tor Mode: Lazily boots the Tor daemon foreground service on-demand, monitors
 *   bootstrapping, and establishes verified circuits.
 */
class TorLifecycleManager private constructor(private val context: Context) {

  companion object {
    private const val TAG = "TorLifecycleManager"

    @Volatile
    private var instance: TorLifecycleManager? = null

    fun getInstance(context: Context): TorLifecycleManager {
      return instance ?: synchronized(this) {
        instance ?: TorLifecycleManager(context.applicationContext).also { instance = it }
      }
    }
  }

  private val lifecycleScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private val torManager = TorManager.getInstance(context)
  private val lifecycleMutex = Mutex()

  private val _daemonState = MutableStateFlow(TorDaemonState.DORMANT)
  val daemonState: StateFlow<TorDaemonState> = _daemonState.asStateFlow()

  private val _isTorReady = MutableStateFlow(false)
  val isTorReady: StateFlow<Boolean> = _isTorReady.asStateFlow()

  private val _bootstrapProgress = MutableStateFlow(0)
  val bootstrapProgress: StateFlow<Int> = _bootstrapProgress.asStateFlow()

  private val _bootstrapNotice = MutableStateFlow("Initializing Tor daemon...")
  val bootstrapNotice: StateFlow<String> = _bootstrapNotice.asStateFlow()

  val currentCircuit: StateFlow<TorCircuit?> = torManager.currentCircuit

  data class QueuedTorNavigation(
    val tabId: String,
    val url: String,
    val forceReload: Boolean = false
  )

  private val queuedNavigations = java.util.concurrent.ConcurrentHashMap<String, QueuedTorNavigation>()

  fun queueNavigation(tabId: String, url: String, forceReload: Boolean = false) {
    queuedNavigations[tabId] = QueuedTorNavigation(tabId, url, forceReload)
    val msg = "[FORENSIC][TOR_GATE_QUEUE] Queued navigation for tab=$tabId url=$url (Tor bootstrap pending at ${_bootstrapProgress.value}%)"
    Log.i(TAG, msg)
    DebugLogManager.log(msg)
  }

  fun dispatchQueuedNavigations() {
    val items = queuedNavigations.values.toList()
    queuedNavigations.clear()
    if (items.isNotEmpty()) {
      Log.i(TAG, "[FORENSIC][TOR_GATE_DISPATCH] Dispatching ${items.size} queued navigations after 100% circuit bootstrap")
      DebugLogManager.log("[TOR_GATE] Dispatching ${items.size} queued navigations after 100% circuit bootstrap")
      lifecycleScope.launch(Dispatchers.Main) {
        val geckoEngine = com.remmi.browser.engine.GeckoEngineManager.getInstance(context)
        items.forEach { queued ->
          geckoEngine.loadUrl(queued.tabId, queued.url, forceReload = true)
        }
      }
    }
  }

  init {
    // Observe TorManager bootstrap state to update high-level lifecycle state and navigation gate
    lifecycleScope.launch {
      torManager.bootstrapState.collect { state ->
        when (state) {
          is TorManager.TorState.OFF -> {
            _daemonState.value = TorDaemonState.DORMANT
            _isTorReady.value = false
            _bootstrapProgress.value = 0
            _bootstrapNotice.value = "Tor daemon dormant"
          }
          is TorManager.TorState.STARTING_SERVICE -> {
            _daemonState.value = TorDaemonState.STARTING
            _isTorReady.value = false
            _bootstrapProgress.value = 10
            _bootstrapNotice.value = "Starting native Tor daemon..."
          }
          is TorManager.TorState.SERVICE_FOREGROUND_CONFIRMED -> {
            _daemonState.value = TorDaemonState.STARTING
            _isTorReady.value = false
            _bootstrapProgress.value = 20
            _bootstrapNotice.value = "Foreground service active..."
          }
          is TorManager.TorState.TOR_BOOTSTRAPPING -> {
            _daemonState.value = TorDaemonState.STARTING
            _isTorReady.value = false
            _bootstrapProgress.value = state.progress
            _bootstrapNotice.value = state.statusText
          }
          is TorManager.TorState.TOR_CIRCUIT_ESTABLISHED -> {
            _daemonState.value = TorDaemonState.STARTING
            _isTorReady.value = false
            _bootstrapProgress.value = 70
            _bootstrapNotice.value = "Tor circuit established..."
          }
          is TorManager.TorState.SOCKS_DISCOVERY,
          is TorManager.TorState.SOCKS5_VERIFY,
          is TorManager.TorState.REMOTE_TOR_VERIFY -> {
            _daemonState.value = TorDaemonState.STARTING
            _isTorReady.value = false
            _bootstrapProgress.value = state.progress
            _bootstrapNotice.value = state.statusText
          }
          is TorManager.TorState.READY -> {
            _daemonState.value = TorDaemonState.ACTIVE
            _isTorReady.value = true
            _bootstrapProgress.value = 100
            _bootstrapNotice.value = "Bootstrapped 100% (done): Done"
            dispatchQueuedNavigations()
          }
          is TorManager.TorState.STOPPING -> {
            _daemonState.value = TorDaemonState.STOPPING
            _isTorReady.value = false
            _bootstrapNotice.value = "Stopping Tor daemon..."
          }
          is TorManager.TorState.FAILED -> {
            _daemonState.value = TorDaemonState.FAILED
            _isTorReady.value = false
            _bootstrapNotice.value = "Tor connection failed"
          }
        }
      }
    }
  }

  fun isTorActive(): Boolean = _daemonState.value == TorDaemonState.ACTIVE

  fun isTorDormant(): Boolean = _daemonState.value == TorDaemonState.DORMANT

  /**
   * Called when browser mode changes.
   * - In SHIELD mode: shuts down Tor daemon completely to avoid running background services.
   * - In TOR mode: lazily boots the daemon.
   */
  suspend fun onModeChanged(targetMode: Mode): Result<Unit> = lifecycleMutex.withLock {
    withContext(Dispatchers.IO) {
      Log.i(TAG, "Mode transition requested: $targetMode (current daemon state: ${_daemonState.value})")
      DebugLogManager.log("[TOR_LIFECYCLE] onModeChanged -> $targetMode")

      when (targetMode) {
        Mode.SHIELD -> {
          stopTorDaemonInternal()
          Result.success(Unit)
        }
        Mode.TOR -> {
          val startRes = startTorDaemonInternal()
          if (startRes.isSuccess) {
            Result.success(Unit)
          } else {
            Result.failure(startRes.exceptionOrNull() ?: IllegalStateException("Failed starting Tor daemon"))
          }
        }
      }
    }
  }

  /**
   * Starts the Tor daemon if not already running.
   */
  suspend fun startTorDaemon(generation: Long = System.currentTimeMillis()): Result<Int> = lifecycleMutex.withLock {
    withContext(Dispatchers.IO) {
      startTorDaemonInternal(generation)
    }
  }

  private suspend fun startTorDaemonInternal(generation: Long = System.currentTimeMillis()): Result<Int> {
    if (_daemonState.value == TorDaemonState.ACTIVE && CurrentTorRoute.isReady) {
      val activePort = CurrentTorRoute.currentSocksPort ?: 9050
      Log.i(TAG, "Tor daemon is already ACTIVE on port $activePort")
      return Result.success(activePort)
    }

    _daemonState.value = TorDaemonState.STARTING
    DebugLogManager.log("[TOR_LIFECYCLE] Starting Tor daemon for Tor Mode (generation=$generation)...")

    val result = torManager.startTor(generation)
    return if (result.isSuccess) {
      val port = result.getOrThrow()
      _daemonState.value = TorDaemonState.ACTIVE
      DebugLogManager.log("[TOR_LIFECYCLE] Tor daemon ACTIVE on port $port")
      Result.success(port)
    } else {
      _daemonState.value = TorDaemonState.FAILED
      val error = result.exceptionOrNull() ?: IllegalStateException("Tor daemon bootstrap failed")
      Log.e(TAG, "Tor daemon start failed: ${error.message}", error)
      DebugLogManager.log("[TOR_LIFECYCLE] Tor daemon FAILED: ${error.message}")
      Result.failure(error)
    }
  }

  /**
   * Shuts down the Tor daemon service completely.
   * Invoked automatically whenever the browser enters Shield Mode.
   */
  suspend fun stopTorDaemon(): Unit = lifecycleMutex.withLock {
    withContext(Dispatchers.IO) {
      stopTorDaemonInternal()
    }
  }

  private suspend fun stopTorDaemonInternal() {
    if (_daemonState.value == TorDaemonState.DORMANT) {
      DebugLogManager.log("[TOR_LIFECYCLE] Tor daemon already DORMANT")
      return
    }

    _daemonState.value = TorDaemonState.STOPPING
    DebugLogManager.log("[TOR_LIFECYCLE] Stopping Tor daemon (Shield Mode active - zero background services)...")

    try {
      torManager.stopTor()
      _daemonState.value = TorDaemonState.DORMANT
      DebugLogManager.log("[TOR_LIFECYCLE] Tor daemon successfully STOPPED (DORMANT)")
      Log.i(TAG, "Tor daemon halted. Foreground service dismissed.")
    } catch (t: Throwable) {
      Log.w(TAG, "Error stopping Tor daemon: ${t.message}", t)
      _daemonState.value = TorDaemonState.DORMANT
    }
  }
}
