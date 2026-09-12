package com.remmi.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.BlockExtension
import com.remmi.browser.engine.chain.ChainStatus
import com.remmi.browser.engine.chain.HopType
import com.remmi.browser.engine.chain.NavigationChainTracker
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.security.AntiFingerprint
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.PdfPrintHelper
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import kotlin.coroutines.resume

sealed class CloseResult {
  object Success : CloseResult()
  object NotFound : CloseResult()
  object AlreadyClosed : CloseResult()
  data class Failed(val error: Throwable) : CloseResult()
}

/**
 * Interface for tab event subscriptions.
 * Decouples Compose UI layers from raw GeckoView delegates.
 */
interface GeckoTabCallbacks {
  fun onUrlChange(url: String) {}
  fun onTitleChange(title: String) {}
  fun onProgressChange(progress: Int) {}
  fun onLoadingChange(isLoading: Boolean) {}
  fun onSecurityChange(isSecure: Boolean) {}
  fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
  fun onTrackerBlocked(url: String, type: String) {}
  fun onExternalResponse(response: WebResponse) {}
  fun onContextMenu(data: WebContextMenuData) {}
  fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {}
}

/**
 * GeckoEngineManager & GeckoSessionController
 * The EXCLUSIVE SINGLE OWNER of:
 * - GeckoRuntime lifecycle
 * - GeckoSession creation, registry, and destruction (NEVER exposed to UI callers)
 * - Delegate registration and thread enforcement
 * - View attachment/detachment for GeckoView
 * - Navigation commands (loadUrl, goBack, goForward, reload, stopLoading, findInPage, evaluateJs, print, exportPdf)
 *
 * Enforces strictly that all GeckoSession interactions execute on the Android Main thread.
 */
class GeckoEngineManager private constructor(private val context: Context) {

  var runtime: GeckoRuntime? = null
    private set

  private val engineSupervisorJob = kotlinx.coroutines.SupervisorJob()
  private val engineScope = CoroutineScope(Dispatchers.IO + engineSupervisorJob)

  val blockExtension = BlockExtension.getInstance()
  init {
    val buildVerifyMsg = "[BUILD_VERIFY] version=1.0.2 stateMachine=ACTIVE"
    Log.i("REM_BUILD", buildVerifyMsg)
    com.remmi.browser.util.DebugLogManager.log(buildVerifyMsg)
    blockExtension.siteSecurityProvider = { host ->
      val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
      policy.shieldsDown
    }
    blockExtension.cosmeticPolicyProvider = { host ->
      val globalSettings = com.remmi.browser.storage.SettingsRepository.getInstance(context).settings.value
      val globalCosmetic = globalSettings.cosmeticFilteringEnabled
      val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
      if (policy.shieldsDown) {
        false
      } else {
        when (policy.cosmeticPolicy) {
          "ENABLED" -> true
          "DISABLED" -> false
          else -> globalCosmetic
        }
      }
    }

    blockExtension.addThreatListener { threatUrl, threatType ->
      mainHandler.post {
        sessionCallbacks.values.forEach { cb ->
          cb.onTrackerBlocked(threatUrl, threatType)
        }
      }
    }
  }
  enum class GeckoInitState {
    NOT_STARTED,
    INITIALIZING,
    READY,
    FAILED
  }

  private val _initState = MutableStateFlow(GeckoInitState.NOT_STARTED)
  val initState: StateFlow<GeckoInitState> = _initState.asStateFlow()

  @Volatile
  private var initDeferred: CompletableDeferred<GeckoRuntime>? = null

  @Volatile
  var currentProfile: PrivacyProfile = PrivacyProfile.SHIELD

  var filePickerRequester: com.remmi.browser.security.permissions.FilePickerRequester? = null
  enum class SessionLifecycleState {
    CREATED_UNOPENED,
    OPENED,
    ATTACHED,
    DETACHED,
    CLOSED
  }

  private val sessionLifecycleStates = mutableMapOf<GeckoSession, SessionLifecycleState>()
  private val sessionOwners = mutableMapOf<GeckoSession, String>()
  private val pendingGeckoOpenSessions = mutableSetOf<GeckoSession>()

  private val activeSessions = mutableMapOf<String, GeckoSession>()
  
  fun getSession(tabId: String): GeckoSession? = activeSessions[tabId]
  fun getAttachedView(tabId: String): org.mozilla.geckoview.GeckoView? = attachedViews[tabId]
  fun getSessionForTest(tabId: String): GeckoSession? = activeSessions[tabId]
  fun getAttachedViewForTest(tabId: String): org.mozilla.geckoview.GeckoView? = attachedViews[tabId]
  fun getSessionLifecycleStateForTest(session: GeckoSession): SessionLifecycleState? = sessionLifecycleStates[session]
  fun getSessionOwnerForTest(session: GeckoSession): String? = sessionOwners[session]
  fun isPendingGeckoOpenForTest(session: GeckoSession): Boolean = session in pendingGeckoOpenSessions
  fun simulateGeckoOpenForTest(session: GeckoSession) {
    if (session in pendingGeckoOpenSessions) {
      pendingGeckoOpenSessions.remove(session)
    }
    val owner = sessionOwners[session] ?: "UNKNOWN"
    val isAtt = attachedViews[owner]?.session === session
    sessionLifecycleStates[session] = if (isAtt) SessionLifecycleState.ATTACHED else SessionLifecycleState.OPENED
    logSessionLifecycle(owner, session, "simulateGeckoOpenForTest")
  }

  fun captureTabThumbnail(tabId: String) {
    try {
      val view = attachedViews[tabId]
      if (view != null && view.session?.isOpen == true) {
        TabThumbnailManager.getInstance(context).captureGeckoView(tabId, view, debounceMs = 0L, force = true)
      }
    } catch (e: Exception) {
      Log.d(TAG, "captureTabThumbnail notice on tab $tabId: ${e.message}")
    }
  }
  private val sessionCallbacks = mutableMapOf<String, GeckoTabCallbacks>()
  private val sessionNavStates = mutableMapOf<String, Pair<Boolean, Boolean>>()
  private val mainHandler = Handler(Looper.getMainLooper())

  data class PendingNavigation(
    val url: String,
    val generation: Long,
    val navId: Long,
  )

  data class PendingContentRecovery(
    val session: GeckoSession,
    val url: String,
    val generation: Long,
    val navId: Long,
    val terminationType: String,
  )

  enum class RecoveryStage {
    DISPATCHED,
    NAV_IN_FLIGHT,
    SUCCESS,
    FAILED,
  }

  enum class RecoveryState {
    NONE,
    PENDING_DETACHED,
    STARTING,
    IN_FLIGHT,
    SUPERSEDED,
    SUCCESS,
    FAILED,
  }

  data class ActiveRecovery(
    val tabId: String,
    val session: GeckoSession,
    val targetUrl: String,
    val generation: Long,
    val navId: Long,
    val startTime: Long,
    var stage: RecoveryStage = RecoveryStage.DISPATCHED,
    var timeoutRunnable: Runnable? = null,
    val redirectedUrls: MutableList<String> = mutableListOf(),
  )

  data class SuccessfulNavRecord(
    val navId: Long,
    val url: String,
    val gen: Long,
    val timestampElapsed: Long,
  )

  private val geckoViewPool = mutableMapOf<String, GeckoView>()

  fun getOrCreateGeckoView(context: Context, tabId: String): GeckoView {
    assertMainThread("getOrCreateGeckoView id=$tabId")
    val existing = geckoViewPool[tabId]
    if (existing != null) {
      (existing.parent as? android.view.ViewGroup)?.removeView(existing)
      return existing
    }
    val newView = GeckoView(context).apply {
      layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT
      )
      isFocusable = true
      isFocusableInTouchMode = true
      isNestedScrollingEnabled = false
      tag = tabId
    }
    geckoViewPool[tabId] = newView
    return newView
  }

  private val attachedViews = mutableMapOf<String, GeckoView>()
  // Guards against duplicate attachView() calls racing from AndroidView/factory and lifecycle ON_RESUME.
  private val attachingTabs = mutableSetOf<String>()
  private val _viewAttachmentStates = mutableMapOf<String, MutableStateFlow<Boolean>>()
  private val navGenerations = mutableMapOf<String, Long>()
  private val currentNavIds = mutableMapOf<String, Long>()
  private val navIdCounter = java.util.concurrent.atomic.AtomicLong(1000L)
  private val lastSuccessfulNavigations = mutableMapOf<String, SuccessfulNavRecord>()
  private val currentScrollPositions = java.util.concurrent.ConcurrentHashMap<String, Int>()

  fun getScrollY(tabId: String): Int {
    return currentScrollPositions[tabId] ?: 0
  }
  private val lastOriginalFailures = mutableMapOf<String, String>()
  private val sessionGenerations = mutableMapOf<String, Long>()
  private val viewGenerations = mutableMapOf<String, Long>()
  private val lastRedirectUrls = mutableMapOf<String, String>()
  private val lastRecoveredGenerations = mutableMapOf<String, Long>()
  private val pendingNavigations = mutableMapOf<String, PendingNavigation>()
  private val pendingContentRecoveries = mutableMapOf<String, PendingContentRecovery>()
  private val activeRecoveries = mutableMapOf<String, ActiveRecovery>()
  private val recoveryStates = mutableMapOf<String, RecoveryState>()
  private val inFlightNavigations = mutableMapOf<String, Long>()
  private val lastDispatchedUrls = mutableMapOf<String, String>()
  private val lastObservedUrls = mutableMapOf<String, String>()
  private val latestProgressUrls = mutableMapOf<String, String>()
  private val latestLocationUrls = mutableMapOf<String, String>()
  private val dispatchedNavigationsHistory = mutableMapOf<String, MutableList<String>>()
  private val navLoadingStates = mutableMapOf<String, Boolean>()
  private val navProgressStates = mutableMapOf<String, Int>()

  // Optional test hook for intercepting loadUri calls in JVM unit tests
  internal var uriLoaderForTest: ((tabId: String, session: GeckoSession, url: String) -> Unit)? = null

  // Optional test hook for intercepting session.open calls in JVM unit tests
  internal var sessionOpenerForTest: ((session: GeckoSession, runtime: GeckoRuntime?) -> Unit)? = null

  // Provider for PasswordAutofillCoordinator to bridge GeckoView login prompts with UI
  var autofillCoordinatorProvider: (() -> com.remmi.browser.security.autofill.PasswordAutofillCoordinator?)? = null

  fun getRecoveryState(tabId: String): RecoveryState = recoveryStates[tabId] ?: RecoveryState.NONE

  fun transitionRecoveryState(
    tabId: String,
    newState: RecoveryState,
    navId: Long,
    generation: Long,
    reason: String
  ) {
    val oldState = recoveryStates[tabId] ?: RecoveryState.NONE
    recoveryStates[tabId] = newState
    val msg = "[FORENSIC][RECOVERY_STATE] tabId=$tabId oldState=$oldState newState=$newState navId=$navId generation=$generation reason=$reason"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logStaleCallbackRejected(
    tabId: String,
    session: GeckoSession?,
    view: GeckoView?,
    navId: Long,
    generation: Long,
    currentSession: GeckoSession?,
    currentView: GeckoView?,
    currentNavId: Long,
    currentGeneration: Long,
    callback: String,
    reason: String
  ) {
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currSessId = currentSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currViewId = currentView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val msg = "[FORENSIC][STALE_CALLBACK_REJECTED] tabId=$tabId session=$sessId view=$viewId navId=$navId generation=$generation currentSession=$currSessId currentView=$currViewId currentNavId=$currentNavId currentGeneration=$currentGeneration callback=$callback reason=$reason"
    Log.w(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavIntent(
    tabId: String,
    navId: Long,
    generation: Long,
    trigger: String,
    url: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_INTENT] tabId=$tabId navId=$navId generation=$generation trigger=$trigger url=$url elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavAllocation(
    tabId: String,
    navId: Long,
    generation: Long,
    trigger: String,
    url: String,
    previousNavId: Long,
    previousGeneration: Long
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_ALLOCATION] tabId=$tabId navId=$navId generation=$generation trigger=$trigger url=$url previousNavId=$previousNavId previousGeneration=$previousGeneration elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavCorrelation(
    tabId: String,
    navId: Long,
    generation: Long,
    url: String,
    trigger: String,
    reason: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_CORRELATION] tabId=$tabId navId=$navId generation=$generation url=$url trigger=$trigger reason=$reason elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavAllocationRejected(
    tabId: String,
    navId: Long,
    generation: Long,
    url: String,
    trigger: String,
    reason: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_ALLOCATION_REJECTED] tabId=$tabId navId=$navId generation=$generation url=$url trigger=$trigger reason=$reason elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavDuplicateClassification(
    tabId: String,
    navId: Long,
    generation: Long,
    previousNavId: Long,
    previousGeneration: Long,
    classification: String,
    trigger: String,
    reason: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_DUPLICATE_CLASSIFICATION] tabId=$tabId navId=$navId generation=$generation previousNavId=$previousNavId previousGeneration=$previousGeneration classification=$classification trigger=$trigger reason=$reason elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logSessionViewBinding(
    tabId: String,
    session: GeckoSession?,
    view: GeckoView?,
    attached: Boolean,
    reason: String
  ) {
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val msg = "[FORENSIC][SESSION_VIEW_BINDING] tabId=$tabId session=$sessId view=$viewId attached=$attached reason=$reason"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun isCallbackAuthoritative(
    tabId: String,
    session: GeckoSession,
    callbackName: String,
    isViewBound: Boolean = false,
    callbackGen: Long? = null
  ): Boolean {
    val currentSession = activeSessions[tabId]
    val currentView = attachedViews[tabId]
    val currentNavId = getActiveNavId(tabId)
    val currentGen = navGenerations[tabId] ?: 0L

    if (currentSession == null || currentSession !== session) {
      logStaleCallbackRejected(
        tabId = tabId,
        session = session,
        view = currentView,
        navId = currentNavId,
        generation = callbackGen ?: currentGen,
        currentSession = currentSession,
        currentView = currentView,
        currentNavId = currentNavId,
        currentGeneration = currentGen,
        callback = callbackName,
        reason = if (currentSession == null) "session_closed_or_absent" else "session_mismatch"
      )
      return false
    }

    if (isViewBound && currentView == null) {
      logStaleCallbackRejected(
        tabId = tabId,
        session = session,
        view = null,
        navId = currentNavId,
        generation = callbackGen ?: currentGen,
        currentSession = currentSession,
        currentView = null,
        currentNavId = currentNavId,
        currentGeneration = currentGen,
        callback = callbackName,
        reason = "view_detached"
      )
      return false
    }

    if (callbackGen != null && callbackGen < currentGen) {
      logStaleCallbackRejected(
        tabId = tabId,
        session = session,
        view = currentView,
        navId = currentNavId,
        generation = callbackGen,
        currentSession = currentSession,
        currentView = currentView,
        currentNavId = currentNavId,
        currentGeneration = currentGen,
        callback = callbackName,
        reason = "stale_generation"
      )
      return false
    }

    return true
  }

  fun allocateNavigationGeneration(
    tabId: String,
    trigger: String,
    url: String
  ): Pair<Long, Long> {
    val previousNavId = currentNavIds[tabId] ?: 0L
    val previousGeneration = navGenerations[tabId] ?: 0L
    val newNavId = navIdCounter.incrementAndGet()
    val newGen = previousGeneration + 1L
    currentNavIds[tabId] = newNavId
    navGenerations[tabId] = newGen
    inFlightNavigations[tabId] = newNavId
    lastRecoveredGenerations.remove(tabId)

    logNavIntent(tabId, newNavId, newGen, trigger, url)
    logNavAllocation(
      tabId = tabId,
      navId = newNavId,
      generation = newGen,
      trigger = trigger,
      url = url,
      previousNavId = previousNavId,
      previousGeneration = previousGeneration,
    )

    val initialHopType = when (trigger) {
      "USER_GESTURE" -> HopType.USER_GESTURE
      "NEW_SESSION" -> HopType.NEW_WINDOW_BLANK
      "LOCATION_CHANGED" -> HopType.LOCATION_CHANGE
      "reload" -> HopType.INITIAL_LOAD
      "goBack", "goForward" -> HopType.LOCATION_CHANGE
      else -> HopType.INITIAL_LOAD
    }
    NavigationChainTracker.startChain(
      tabId = tabId,
      navId = newNavId,
      generation = newGen,
      sourceUrl = url,
      hopType = initialHopType,
      hasUserGesture = (trigger == "USER_GESTURE")
    )

    return Pair(newNavId, newGen)
  }

  fun logProgressState(
    tabId: String,
    navId: Long,
    generation: Long,
    event: String,
    oldProgress: Int,
    newProgress: Int,
    isLoading: Boolean,
    accepted: Boolean,
    reason: String
  ) {
    val isMilestone = event != "NAV_PROGRESS" || newProgress == 0 || newProgress == 100 || !accepted
    if (com.remmi.browser.BuildConfig.DEBUG || isMilestone) {
      val msg = "[FORENSIC][PROGRESS_STATE] navId=$navId generation=$generation event=$event oldProgress=$oldProgress newProgress=$newProgress isLoading=$isLoading accepted=$accepted reason=$reason"
      if (com.remmi.browser.BuildConfig.DEBUG) {
        Log.d(TAG, msg)
      }
      com.remmi.browser.util.DebugLogManager.log(msg)
    }
  }

  fun logRecoveryUrlState(
    tabId: String,
    navId: Long,
    generation: Long,
    url: String?,
    activeRecovery: Boolean,
    targetUrl: String?,
    classification: String,
    action: String
  ) {
    val msg = "[FORENSIC][RECOVERY_URL_STATE] tabId=$tabId navId=$navId generation=$generation url=$url activeRecovery=$activeRecovery targetUrl=${targetUrl ?: "none"} classification=$classification action=$action"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun getActiveNavId(tabId: String): Long {
    return currentNavIds[tabId] ?: 0L
  }

  fun allocateNavId(tabId: String): Long {
    val id = navIdCounter.incrementAndGet()
    currentNavIds[tabId] = id
    return id
  }

  fun getMemoryForensicSnapshot(trigger: String): String {
    if (!com.remmi.browser.BuildConfig.DEBUG && trigger == "NAV_START") {
      return ""
    }
    val snap = try { com.remmi.browser.util.ProcessMemoryTelemetry.captureSnapshot() } catch (_: Throwable) { null }
    val rssMb = (snap?.rssBytes ?: 0L) / (1024 * 1024)
    val pssMb = (snap?.pssBytes ?: 0L) / (1024 * 1024)
    val javaUsedMb = (snap?.javaHeapUsedBytes ?: 0L) / (1024 * 1024)
    val javaMaxMb = (snap?.javaHeapMaxBytes ?: 0L) / (1024 * 1024)
    val nativeMb = (snap?.nativeHeapAllocatedBytes ?: 0L) / (1024 * 1024)
    val availBytes = com.remmi.browser.util.HangWatchdog.getAvailableMemBytes(context)
    val availStr = if (availBytes != null) " availMem=${availBytes / (1024 * 1024)}MB" else ""
    val memMsg = "[FORENSIC][MEMORY_SNAPSHOT] trigger=$trigger rss=${rssMb}MB pss=${pssMb}MB javaHeap=${javaUsedMb}/${javaMaxMb}MB nativeHeap=${nativeMb}MB$availStr"
    if (com.remmi.browser.BuildConfig.DEBUG) {
      Log.d(TAG, memMsg)
    }
    com.remmi.browser.util.DebugLogManager.log(memMsg)
    return memMsg
  }

  private fun getCallerTrace(depth: Int = 4): String {
    return "fast_trace"
  }

  fun logDestructiveOp(
    operation: String,
    tabId: String,
    session: GeckoSession? = null,
    view: GeckoView? = null,
    url: String? = null,
    reason: String = "",
  ) {
    val targetSession = session ?: activeSessions[tabId]
    val sessId = targetSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val targetView = view ?: attachedViews[tabId]
    val viewId = targetView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val navId = getActiveNavId(tabId)
    val gen = navGenerations[tabId] ?: 0L
    val targetUrl = url ?: lastDispatchedUrls[tabId] ?: "unknown"
    val caller = getCallerTrace(4)
    val threadName = Thread.currentThread().name
    val isOpen = targetSession?.isOpen ?: false
    val attachedOwner = attachedViews.entries.find { it.value === targetView && targetView != null }?.key ?: "none"

    val msg = "[FORENSIC][DESTRUCTIVE_OP] operation=$operation tabId=$tabId session=$sessId view=$viewId navId=$navId url=$targetUrl gen=$gen reason=$reason caller=$caller thread=$threadName isOpen=$isOpen attachedOwner=$attachedOwner"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logContentProcessEvent(
    event: String,
    tabId: String,
    session: GeckoSession? = null,
    url: String? = null,
    reason: String = "",
  ) {
    val targetSession = session ?: activeSessions[tabId]
    val sessId = targetSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val targetView = attachedViews[tabId]
    val viewId = targetView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val navId = getActiveNavId(tabId)
    val gen = navGenerations[tabId] ?: 0L
    val targetUrl = url ?: lastDispatchedUrls[tabId] ?: "unknown"
    val pid = android.os.Process.myPid()
    val isAttached = isViewAttached(tabId)
    val hasActiveRec = hasActiveRecovery(tabId)
    val hasPendingRec = hasPendingContentRecovery(tabId)
    val bridge = com.remmi.adblock.AdblockBridge.getInstance()
    val adblockGen = bridge.getEngineGeneration()
    val adblockRules = bridge.getLoadedRulesCount()
    val isNative = bridge.isNativeAvailable()
    val inflightNet = com.remmi.adblock.BlockExtension.getInflightDecisionCount()
    val inflightCosmetic = com.remmi.adblock.BlockExtension.getInflightCosmeticCount()
    val now = android.os.SystemClock.elapsedRealtime()

    val msg = "[FORENSIC][CONTENT_PROCESS_EVENT] event=$event pid=$pid tabId=$tabId session=$sessId view=$viewId navId=$navId url=$targetUrl gen=$gen reason=$reason attached=$isAttached activeRec=$hasActiveRec pendingRec=$hasPendingRec adblockGen=$adblockGen adblockRules=$adblockRules isNative=$isNative inflightNet=$inflightNet inflightCosmetic=$inflightCosmetic elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun checkPostNavFailure(tabId: String, failureType: String, currentUrl: String? = null) {
    val record = lastSuccessfulNavigations[tabId]
    val now = android.os.SystemClock.elapsedRealtime()
    val curr = currentUrl ?: lastObservedUrls[tabId] ?: lastDispatchedUrls[tabId] ?: "unknown"
    val navId = record?.navId ?: getActiveNavId(tabId)
    val gen = record?.gen ?: (navGenerations[tabId] ?: 0L)
    val elapsed = if (record != null) now - record.timestampElapsed else -1L

    val isNavActive = (navLoadingStates[tabId] == true) || inFlightNavigations.containsKey(tabId)
    val recState = recoveryStates[tabId]
    val isRecoveryInFlight = recState == RecoveryState.STARTING || recState == RecoveryState.IN_FLIGHT

    when (failureType) {
      "VIEW_ON_RELEASE", "DETACH_VIEW", "TAG_MISMATCH_DETACH" -> {
        if (isNavActive || isRecoveryInFlight) {
          val reason = if (isRecoveryInFlight) "view_disposed_during_recovery" else "view_disposed_during_active_nav"
          val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=VIEW_DISPOSED_DURING_ACTIVE_NAVIGATION failure=$failureType reason=$reason elapsedSinceNavStopMs=$elapsed"
          Log.w(TAG, lifecycleMsg)
          com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = failureType
        } else {
          val reason = "view_disposed_after_terminal_success"
          val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=VIEW_DISPOSED_AFTER_NAV_SUCCESS reason=$reason elapsedSinceNavStopMs=$elapsed"
          Log.i(TAG, lifecycleMsg)
          com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        }
      }
      "CONTENT_CRASH", "CONTENT_KILL" -> {
        val reason = "content_process_terminated"
        val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=CONTENT_PROCESS_FAILED failure=$failureType reason=$reason elapsedSinceNavStopMs=$elapsed"
        Log.e(TAG, lifecycleMsg)
        com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

        if (!isNavActive && record != null) {
          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=content_kill_after_terminal_success"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        } else if (elapsed in 0..15000L || record == null) {
          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = failureType
        }
      }
      "PAGE_STOP_FAILED", "NAV_ERROR" -> {
        val reason = "navigation_terminal_error"
        val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=NAVIGATION_FAILED failure=$failureType reason=$reason elapsedSinceNavStopMs=$elapsed"
        Log.e(TAG, lifecycleMsg)
        com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

        val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
        Log.e(TAG, failMsg)
        com.remmi.browser.util.DebugLogManager.log(failMsg)
        lastOriginalFailures[tabId] = failureType
      }
      "ABOUT_BLANK" -> {
        if (isRecoveryInFlight) {
          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=ABOUT_BLANK reason=transient_recovery_blank"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        } else if (record != null && elapsed in 0..15000L && record.url != "about:blank") {
          val reason = "unexpected_post_nav_blank"
          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=ABOUT_BLANK reason=$reason"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = "ABOUT_BLANK"
        }
      }
      else -> {
        if (!isNavActive && record != null) {
          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=content_kill_after_terminal_success"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        } else if (elapsed in 0..15000L || record == null) {
          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=other"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = failureType
        }
      }
    }
  }

  fun getViewAttachmentState(tabId: String): StateFlow<Boolean> {
    return _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.asStateFlow()
  }

  fun isViewAttached(tabId: String): Boolean {
    val view = attachedViews[tabId] ?: return false
    val session = activeSessions[tabId] ?: return false
    return view.session == session
  }

  fun getPendingNavigation(tabId: String): String? = pendingNavigations[tabId]?.url
  fun getLastDispatchedUrl(tabId: String): String? = lastDispatchedUrls[tabId]
  fun getLastObservedUrl(tabId: String): String? = lastObservedUrls[tabId]
  fun isInFlight(tabId: String): Boolean = inFlightNavigations.containsKey(tabId) || (navLoadingStates[tabId] == true)
  fun getNavGeneration(tabId: String): Long = navGenerations[tabId] ?: 0L
  fun getLastRecoveredGeneration(tabId: String): Long? = lastRecoveredGenerations[tabId]
  fun getDispatchedNavigations(tabId: String): List<String> = dispatchedNavigationsHistory[tabId]?.toList() ?: emptyList()
  fun hasPendingContentRecovery(tabId: String): Boolean = pendingContentRecoveries.containsKey(tabId)
  fun getPendingContentRecovery(tabId: String): PendingContentRecovery? = pendingContentRecoveries[tabId]
  fun hasActiveRecovery(tabId: String): Boolean = activeRecoveries.containsKey(tabId)
  fun getActiveRecovery(tabId: String): ActiveRecovery? = activeRecoveries[tabId]
  fun getLastOriginalFailure(tabId: String): String? = lastOriginalFailures[tabId]
  fun getLastSuccessfulNavigation(tabId: String): SuccessfulNavRecord? = lastSuccessfulNavigations[tabId]

  @VisibleForTesting
  fun triggerRecoveryTimeoutForTest(tabId: String) {
    val active = activeRecoveries.remove(tabId) ?: return
    active.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    active.stage = RecoveryStage.FAILED
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(active.session))
    val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId url=${active.targetUrl} gen=${active.generation} stage=DISPATCHED elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    Log.w(TAG, toMsg)
    com.remmi.browser.util.DebugLogManager.log(toMsg)
  }

  fun isInternalOrIgnoredUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    val trimmed = url.trim()
    if (trimmed.equals("about:blank", ignoreCase = true)) return true
    if (trimmed.startsWith("about:", ignoreCase = true)) return true
    if (trimmed.startsWith("remmi:", ignoreCase = true)) return true
    if (trimmed.startsWith("chrome:", ignoreCase = true)) return true
    if (trimmed.startsWith("resource:", ignoreCase = true)) return true
    if (trimmed.startsWith("moz-extension:", ignoreCase = true)) return true
    if (trimmed.equals("unknown", ignoreCase = true)) return true
    return false
  }

  private fun parseUri(rawUrl: String): android.net.Uri? {
    val clean = rawUrl.trim()
    val withScheme = if (!clean.contains("://") && !clean.startsWith("about:") && !clean.startsWith("remmi:")) {
      "https://$clean"
    } else {
      clean
    }
    return try {
      android.net.Uri.parse(withScheme)
    } catch (_: Exception) {
      null
    }
  }

  @VisibleForTesting
  internal fun areUrlsEquivalent(url1: String?, url2: String?): Boolean {
    if (url1.isNullOrBlank() || url2.isNullOrBlank()) return false
    if (isInternalOrIgnoredUrl(url1) || isInternalOrIgnoredUrl(url2)) return false
    if (url1 == url2) return true

    val uri1 = parseUri(url1) ?: return false
    val uri2 = parseUri(url2) ?: return false

    val scheme1 = uri1.scheme?.lowercase() ?: ""
    val scheme2 = uri2.scheme?.lowercase() ?: ""
    val schemesCompatible = (scheme1 == scheme2) || 
      ((scheme1 == "http" || scheme1 == "https") && (scheme2 == "http" || scheme2 == "https"))
    if (!schemesCompatible) return false

    val host1 = uri1.host?.lowercase() ?: ""
    val host2 = uri2.host?.lowercase() ?: ""
    if (host1.isEmpty() || host2.isEmpty()) return false
    val hostsCompatible = (host1 == host2) || 
      (host1 == host2.removePrefix("www.")) || 
      (host2 == host1.removePrefix("www."))
    if (!hostsCompatible) return false

    val defaultPort1 = if (scheme1 == "http") 80 else if (scheme1 == "https") 443 else -1
    val defaultPort2 = if (scheme2 == "http") 80 else if (scheme2 == "https") 443 else -1
    val isDefaultPort1 = (uri1.port == -1 || uri1.port == defaultPort1)
    val isDefaultPort2 = (uri2.port == -1 || uri2.port == defaultPort2)
    if (isDefaultPort1 && isDefaultPort2) {
      // Both use default ports for their respective schemes
    } else if (uri1.port != uri2.port) {
      return false
    }

    val path1 = (uri1.path ?: "").trimEnd('/')
    val path2 = (uri2.path ?: "").trimEnd('/')
    if (path1 != path2) return false

    val query1 = uri1.query
    val query2 = uri2.query
    if (query1 != query2) {
      if (query1 == null || query2 == null) return false
      val names1 = try { uri1.queryParameterNames } catch (_: Exception) { null }
      val names2 = try { uri2.queryParameterNames } catch (_: Exception) { null }
      if (names1 == null || names2 == null || names1 != names2) return false
      for (name in names1) {
        val vals1 = uri1.getQueryParameters(name)
        val vals2 = uri2.getQueryParameters(name)
        if (vals1 != vals2) return false
      }
    }

    return true
  }

  @VisibleForTesting
  internal fun isDifferentHost(url1: String?, url2: String?): Boolean {
    if (url1.isNullOrBlank() || url2.isNullOrBlank()) return true
    if (isInternalOrIgnoredUrl(url1) || isInternalOrIgnoredUrl(url2)) return true
    val uri1 = parseUri(url1) ?: return true
    val uri2 = parseUri(url2) ?: return true
    val host1 = uri1.host?.lowercase()?.removePrefix("www.") ?: return true
    val host2 = uri2.host?.lowercase()?.removePrefix("www.") ?: return true
    return host1 != host2
  }

  private fun isRecoveryTargetOrRedirect(url: String?, recovery: ActiveRecovery): Boolean {
    if (url.isNullOrBlank() || isInternalOrIgnoredUrl(url)) return false
    if (areUrlsEquivalent(url, recovery.targetUrl)) return true
    for (redirectUrl in recovery.redirectedUrls) {
      if (areUrlsEquivalent(url, redirectUrl)) return true
    }
    return false
  }

  @VisibleForTesting
  internal fun recordRecoveryRedirect(tabId: String, redirectedUrl: String) {
    val active = activeRecoveries[tabId] ?: return
    if (!isInternalOrIgnoredUrl(redirectedUrl) && active.redirectedUrls.size < 10) {
      if (!active.redirectedUrls.any { areUrlsEquivalent(it, redirectedUrl) }) {
        active.redirectedUrls.add(redirectedUrl)
        active.stage = RecoveryStage.NAV_IN_FLIGHT
        lastObservedUrls[tabId] = redirectedUrl
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(active.session))
        val redMsg = "[FORENSIC][CONTENT_RECOVERY_REDIRECT] tabId=$tabId session=$sessId url=$redirectedUrl targetUrl=${active.targetUrl} gen=${active.generation} redirectCount=${active.redirectedUrls.size} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, redMsg)
        com.remmi.browser.util.DebugLogManager.log(redMsg)
      }
    }
  }

  @VisibleForTesting
  internal fun setRuntimeForTesting(runtime: GeckoRuntime?) {
    this.runtime = runtime
    GeckoPreferenceController.resetCache(runtime)
  }

  @VisibleForTesting
  internal fun setInitStateForTesting(state: GeckoInitState) {
    _initState.value = state
  }

  @VisibleForTesting
  internal fun setSessionForTesting(tabId: String, session: GeckoSession) {
    activeSessions[tabId] = session
    sessionOwners[session] = tabId
    sessionLifecycleStates[session] = if (session.isOpen) SessionLifecycleState.OPENED else SessionLifecycleState.CREATED_UNOPENED
    logSessionLifecycle(tabId, session, "setSessionForTesting")
    wireDelegates(tabId, session)
  }

  private fun logSessionLifecycle(
    tabId: String,
    session: GeckoSession,
    action: String,
    illegalTransition: String? = null
  ) {
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    val state = sessionLifecycleStates[session] ?: if (session.isOpen) SessionLifecycleState.OPENED else SessionLifecycleState.CREATED_UNOPENED
    val opened = session.isOpen
    val attached = attachedViews[tabId]?.session === session
    val owner = sessionOwners[session] ?: tabId
    val logMsg = "[FORENSIC][SESSION_LIFECYCLE] tabId=$tabId session=$sessId state=$state action=$action opened=$opened attached=$attached owner=$owner" +
        (if (illegalTransition != null) " ILLEGAL_TRANSITION=$illegalTransition" else "")
    if (illegalTransition != null) {
      Log.e(TAG, logMsg)
    } else {
      Log.i(TAG, logMsg)
    }
    com.remmi.browser.util.DebugLogManager.log(logMsg)
  }

  private fun canTransitionSession(
    session: GeckoSession,
    targetState: SessionLifecycleState,
    action: String,
    tabId: String
  ): Boolean {
    val currentState = sessionLifecycleStates[session] ?: if (session.isOpen) SessionLifecycleState.OPENED else SessionLifecycleState.CREATED_UNOPENED
    val currentOwner = sessionOwners[session]

    if (currentOwner != null && currentOwner != tabId) {
      logSessionLifecycle(tabId, session, action, "OWNERSHIP_MISMATCH currentOwner=$currentOwner requestingTab=$tabId")
      return false
    }

    if (currentState == SessionLifecycleState.CLOSED) {
      logSessionLifecycle(tabId, session, action, "CLOSED_TO_${targetState}_FORBIDDEN")
      return false
    }

    if (targetState == SessionLifecycleState.OPENED) {
      if (session.isOpen) {
        logSessionLifecycle(tabId, session, action, "ALREADY_OPEN_FORBIDDEN isOpen=${session.isOpen}")
        return false
      }
      if (session in pendingGeckoOpenSessions) {
        logSessionLifecycle(tabId, session, action, "PENDING_GECKO_OPEN_FORBIDDEN")
        return false
      }
    }

    return true
  }

  private fun openSessionSafely(session: GeckoSession, tabId: String, action: String): Boolean {
    assertMainThread("OPEN_SESSION_SAFELY id=$tabId action=$action")
    val currentState = sessionLifecycleStates[session] ?: if (session.isOpen) SessionLifecycleState.OPENED else SessionLifecycleState.CREATED_UNOPENED

    if (session.isOpen) {
      logSessionLifecycle(tabId, session, action, "PREVENTED_DOUBLE_OPEN session.isOpen=true")
      if (currentState == SessionLifecycleState.CREATED_UNOPENED) {
        sessionLifecycleStates[session] = SessionLifecycleState.OPENED
      }
      return false
    }

    if (session in pendingGeckoOpenSessions) {
      logSessionLifecycle(tabId, session, action, "PREVENTED_OPEN_ON_PENDING_GECKO_SESSION")
      return false
    }

    if (!canTransitionSession(session, SessionLifecycleState.OPENED, action, tabId)) {
      return false
    }

    val testOpener = sessionOpenerForTest
    if (testOpener != null) {
      testOpener(session, runtime)
    } else {
      runtime?.let { rt ->
        try {
          session.open(rt)
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] Failed to open session on tabId=$tabId: ${e.message}")
          return false
        }
      }
    }
    if (sessionLifecycleStates[session] != SessionLifecycleState.ATTACHED) {
      sessionLifecycleStates[session] = SessionLifecycleState.OPENED
    }
    logSessionLifecycle(tabId, session, action)
    return true
  }

  private fun checkAndObserveGeckoOpen(tabId: String, session: GeckoSession) {
    if (session.isOpen && session in pendingGeckoOpenSessions) {
      pendingGeckoOpenSessions.remove(session)
      val isAtt = attachedViews[tabId]?.session === session
      sessionLifecycleStates[session] = if (isAtt) SessionLifecycleState.ATTACHED else SessionLifecycleState.OPENED
      logSessionLifecycle(tabId, session, "geckoOpenedSession")
    }
  }

  private fun assertMainThread(operation: String) {
    val isMain = Looper.myLooper() == Looper.getMainLooper()
    Log.d(TAG, "[GECKO] operation=$operation thread=${if (isMain) "main" else "ILLEGAL_${Thread.currentThread().name}"}")
    check(isMain) { "Gecko operation $operation MUST be called on the Main thread! (Current: ${Thread.currentThread().name})" }
  }

  suspend fun ensureRuntime(): GeckoRuntime = withContext(Dispatchers.Main) {
    runtime?.let { return@withContext it }

    if (_initState.value == GeckoInitState.NOT_STARTED || _initState.value == GeckoInitState.FAILED) {
      initializeRuntimeAsync()
    }

    if (runtime == null) {
      kotlinx.coroutines.withTimeoutOrNull(10_000L) {
        _initState.first { it == GeckoInitState.READY || it == GeckoInitState.FAILED }
      }
    }

    runtime ?: throw IllegalStateException("GeckoRuntime not ready (state=${_initState.value})")
  }

  /**
   * Pre-warms GeckoRuntime asynchronously during Application cold-start.
   * Keeps Tor daemon strictly dormant while preparing the engine for sub-second tab mounting.
   */
  fun prewarm() {
    if (_initState.value == GeckoInitState.NOT_STARTED || _initState.value == GeckoInitState.FAILED) {
      Log.i(TAG, "Pre-warming GeckoRuntime on cold start...")
      initializeRuntimeAsync()
    }
  }

  fun initializeRuntimeAsync() {
    if (_initState.value != GeckoInitState.NOT_STARTED && _initState.value != GeckoInitState.FAILED) {
      return
    }

    _initState.value = GeckoInitState.INITIALIZING
    Log.i(TAG, "STATE_LOG: GECKO_INIT_START (time=${android.os.SystemClock.elapsedRealtime()})")

    CoroutineScope(Dispatchers.Main.immediate).launch {
      try {
        initializeRuntimeInternal()
        _initState.value = GeckoInitState.READY
        val readyLog = "[GECKO_RUNTIME_READY] runtimeReady=true initState=READY runtimeNonNull=${runtime != null} (time=${android.os.SystemClock.elapsedRealtime()})"
        Log.i(TAG, readyLog)
        com.remmi.browser.util.DebugLogManager.log(readyLog)
      } catch (t: Throwable) {
        _initState.value = GeckoInitState.FAILED
        val stackTrace = Log.getStackTraceString(t)
        val failLog = "[GECKO_RUNTIME_FAILED] runtimeReady=false initState=FAILED (time=${android.os.SystemClock.elapsedRealtime()}) error=${t.javaClass.name}: ${t.message ?: "No message"}\n$stackTrace"
        Log.e(TAG, failLog, t)
        com.remmi.browser.util.DebugLogManager.log(failLog)
      }
    }
  }

  private suspend fun initializeRuntimeInternal() {
    assertMainThread("INITIALIZE_RUNTIME_INTERNAL")
    if (runtime != null) {
      _initState.value = GeckoInitState.READY
      return
    }

    val watchdog = com.remmi.browser.util.HangWatchdog.startGeckoInitWatchdog()
    val startTime = android.os.SystemClock.elapsedRealtime()
    Log.i(TAG, "Initializing GeckoRuntime with Process Isolation & WebRender...")

    // Clean up any stale config files on IO dispatcher
    withContext(Dispatchers.IO) {
      try {
        java.io.File(context.filesDir, "gv-config.yaml").takeIf { it.exists() }?.delete()
        java.io.File(context.filesDir, "geckoview-config.yaml").takeIf { it.exists() }?.delete()
        val profilesDir = java.io.File(context.filesDir, "profiles")
        if (profilesDir.exists() && profilesDir.isDirectory) {
          profilesDir.walkTopDown().forEach { file ->
            if (file.name.endsWith(".yaml") || file.name.endsWith(".yml")) {
              file.delete()
            }
          }
        }
      } catch (_: Throwable) {}
    }

    val activeMode = com.remmi.browser.security.Mode.fromProfile(currentProfile)
    val settings = withContext(Dispatchers.IO) {
      com.remmi.browser.security.ProfileConfigManager.getInstance(context).ensureProfilesReady()
      BrowserRuntimeFactory.getInstance(context).createRuntimeSettings(activeMode)
    }

    val rt = GeckoRuntime.create(context, settings)
    runtime = rt
    _initState.value = GeckoInitState.READY
    GeckoPreferenceController.resetCache(rt)

    val createDuration = android.os.SystemClock.elapsedRealtime() - startTime
    val createLog = "[GECKO_RUNTIME_CREATED] GeckoRuntime.create() completed successfully in ${createDuration}ms. runtimeReady=true"
    Log.i(TAG, createLog)
    com.remmi.browser.util.DebugLogManager.log(createLog)

    try {
      rt.setAutocompleteStorageDelegate(
        com.remmi.browser.security.autofill.GeckoPasswordStorageDelegate(
          context = context,
          passwordRepo = com.remmi.browser.security.PasswordManagerRepository.getInstance(context)
        )
      )
    } catch (t: Throwable) {
      Log.w(TAG, "Failed registering AutocompleteStorageDelegate: ${t.message}")
    }

    // Register WebExtension for native ad/tracker blocking and secondary proxy synchronization
    try {
      val extensionUri = "resource://android/assets/extensions/remmi_engine_extension/"
      
      val installPromptHandler = { ext: WebExtension? ->
        if (ext != null) {
          rt.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
          ext.setMessageDelegate(blockExtension, "remmi_engine_extension")
          blockExtension.setExtensionRegistered()
          Log.i(TAG, "Remmi WebExtension registered successfully: ${ext.id}")
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Registered (ID: ${ext.id})")
        } else {
          blockExtension.setExtensionFailed("WebExtension controller returned null")
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] WARNING: WebExtension controller returned null")
        }
      }

      val failureHandler = { throwable: Throwable? ->
        Log.w(TAG, "ensureBuiltIn notice, trying fallback install", throwable)
        try {
          rt.webExtensionController
            .installBuiltIn(extensionUri)
            .accept(
              { ext -> installPromptHandler(ext) },
              { fallbackErr ->
                val reason = fallbackErr?.message ?: "Unknown fallback error"
                blockExtension.setExtensionFailed(reason)
                com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Fallback install failed: $reason")
              }
            )
        } catch (fbEx: Throwable) {
          val reason = fbEx.message ?: "Exception"
          blockExtension.setExtensionFailed(reason)
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Fallback exception: $reason")
        }
      }

      suspendCancellableCoroutine<Unit> { cont ->
        rt.webExtensionController
          .ensureBuiltIn(extensionUri, "extension@remmi.browser")
          .accept(
            { ext: WebExtension? -> installPromptHandler(ext); cont.resume(Unit) },
            { throwable: Throwable? -> failureHandler(throwable); cont.resume(Unit) }
          )
      }
    } catch (t: Throwable) {
      Log.w(TAG, "WebExtension installation skipped: ${t.message}")
      blockExtension.setExtensionFailed(t.message ?: "Skipped")
      com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Installation exception: ${t.message}")
    }

    applyPrivacyProfile(currentProfile)
    val duration = android.os.SystemClock.elapsedRealtime() - startTime
    watchdog.stop()
    Log.i(TAG, "GeckoRuntime initialization completed in ${duration}ms (READY)")
  }

  suspend fun applyPrivacyProfile(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    socksPort: Int? = CurrentTorRoute.currentSocksPort,
    generation: Long = CurrentTorRoute.currentGeneration,
    settings: com.remmi.browser.storage.BrowserSettings? = null,
  ): Boolean {
    currentProfile = profile
    val rt = runtime ?: return false
    val browserSettings = settings ?: com.remmi.browser.storage.SettingsRepository.getInstance(context).settings.value

    val mode = com.remmi.browser.security.Mode.fromProfile(profile)
    BrowserRuntimeFactory.getInstance(context).switchProfile(mode, rt)

    return when (profile) {
      PrivacyProfile.GHOST -> {
        val port = socksPort ?: return false
        NetworkHardening.applyTorNetworkSettings(rt, port, generation, browserSettings)
      }
      PrivacyProfile.SHIELD -> {
        NetworkHardening.applyShieldNetworkSettings(rt, generation, browserSettings)
      }
      PrivacyProfile.INCOGNITO -> {
        NetworkHardening.applyShieldNetworkSettings(rt, generation, browserSettings)
      }
    }
  }

  fun updateGlobalPreferences(settings: com.remmi.browser.storage.BrowserSettings) {
    val rt = runtime ?: return
    engineScope.launch {
      if (CurrentTorRoute.isGhostActive) {
        val port = CurrentTorRoute.currentSocksPort ?: return@launch
        NetworkHardening.applyTorNetworkSettings(rt, port, CurrentTorRoute.currentGeneration, settings)
      } else {
        NetworkHardening.applyShieldNetworkSettings(rt, CurrentTorRoute.currentGeneration, settings)
      }
    }
    
    // Ensure site-specific overrides have precedence (P1-2)
    val currentTabs = TabManager.getInstance().tabs.value
    for (tab in currentTabs) {
      if (tab.url.isNotBlank() && tab.url != "about:blank") {
        try {
          val host = java.net.URI(if (tab.url.contains("://")) tab.url else "https://${tab.url}").host
          if (!host.isNullOrBlank()) {
            applySiteSecurityPolicy(tab.id, host)
          }
        } catch (_: Exception) {}
      }
    }
  }

  fun applySiteSecurityPolicy(tabId: String, host: String) {
    if (host.isBlank()) return
    val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
    val tab = TabManager.getInstance().getTab(tabId)
    val profile = tab?.profile ?: currentProfile
    val securityLevel = policy.customSecurityLevel ?: tab?.securityLevel ?: SecurityLevel.STANDARD

    onMainSession(tabId, "APPLY_SITE_SECURITY_POLICY") { session ->
      session.settings.apply {
        allowJavascript = policy.javascriptEnabled ?: securityLevel.javascriptEnabled
        useTrackingProtection = (policy.cookiePolicy != "ALLOW")
        suspendMediaWhenInactive = !policy.autoplayAllowed
      }
      AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
      Log.d(TAG, "Applied site security policy for host '$host' to tab $tabId (js=${session.settings.allowJavascript}, tp=${session.settings.useTrackingProtection}, autoplay=${policy.autoplayAllowed})")
    }
  }

  fun applySiteSecurityPolicyToMatchingTabs(host: String) {
    val cleanHost = host.lowercase().trim()
    val currentTabs = TabManager.getInstance().tabs.value
    for (tab in currentTabs) {
      val tabHost = try {
        java.net.URI(if (tab.url.contains("://")) tab.url else "https://${tab.url}").host?.lowercase()?.trim()
      } catch (_: Exception) { null }
      if (tabHost == cleanHost) {
        applySiteSecurityPolicy(tab.id, cleanHost)
      }
    }
  }

  suspend fun applyPrivacyProfile(
    profile: PrivacyProfile,
    socksPort: Int?,
    generation: Long,
  ): Boolean {
    return applyPrivacyProfile(profile, SecurityLevel.STANDARD, socksPort, generation)
  }

  fun setTabGhostMode(tabId: String, isGhost: Boolean) {
    // Native Gecko controls tab isolation via Private Browsing session settings
  }

  // --- Internal Session Factory & Wire-up (Strict Main Thread) ---

  private fun createSessionInternal(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    isDesktopMode: Boolean = false,
    openSession: Boolean = true
  ): GeckoSession {
    assertMainThread("CREATE_SESSION")
    android.util.Log.i(TAG, "STATE_LOG: SESSION_CREATE_START (time=${android.os.SystemClock.elapsedRealtime()})")
    
    val rt = runtime
    if (rt == null && uriLoaderForTest == null) {
      throw IllegalStateException("GeckoRuntime is not ready yet (state=${_initState.value}). Sessions must be created only after runtime readiness.")
    }
    
    val isPrivateContainer = containerType != ContainerType.NORMAL || profile == PrivacyProfile.INCOGNITO || profile == PrivacyProfile.GHOST
    val settings = GeckoSessionSettings.Builder()
      .usePrivateMode(isPrivateContainer)
      .useTrackingProtection(true)
      .suspendMediaWhenInactive(true)
      .build()
      
    val session = GeckoSession(settings)

    session.settings.apply {
      userAgentMode = if (isDesktopMode || profile == PrivacyProfile.GHOST) {
        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
      } else {
        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
      }
      viewportMode = if (isDesktopMode) {
        GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
      } else {
        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
      }
      allowJavascript = securityLevel.javascriptEnabled
    }

    AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
    sessionLifecycleStates[session] = SessionLifecycleState.CREATED_UNOPENED
    android.util.Log.i(TAG, "STATE_LOG: SESSION_OPEN (time=${android.os.SystemClock.elapsedRealtime()})")
    if (openSession) {
      val testOpener = sessionOpenerForTest
      if (testOpener != null) {
        testOpener(session, rt)
      } else {
        rt?.let { session.open(it) }
      }
      sessionLifecycleStates[session] = SessionLifecycleState.OPENED
    }
    return session
  }

  private fun getOrCreateSessionInternal(
    tabId: String,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    isDesktopMode: Boolean = false,
    openSession: Boolean = true,
  ): GeckoSession {
    assertMainThread("GET_OR_CREATE_INTERNAL id=$tabId")
    val existing = activeSessions[tabId]
    if (existing != null) {
      if (sessionLifecycleStates[existing] == SessionLifecycleState.CLOSED) {
        logSessionLifecycle(tabId, existing, "getOrCreateSessionInternal", "REUSE_CLOSED_SESSION_FORBIDDEN")
        activeSessions.remove(tabId)
        sessionOwners.remove(existing)
      } else {
        Log.i(TAG, "[FORENSIC] GECKO_SESSION REUSED id=$tabId (isOpen=${existing.isOpen})")
        if (openSession && !existing.isOpen && existing !in pendingGeckoOpenSessions) {
          openSessionSafely(existing, tabId, "getOrCreateSessionInternal")
        }
        return existing
      }
    } else {
      Log.i(TAG, "[FORENSIC] GECKO_SESSION CREATED id=$tabId (first time)")
    }

    val newSession = createSessionInternal(profile, securityLevel, containerType, isDesktopMode, openSession)
    activeSessions[tabId] = newSession
    sessionOwners[newSession] = tabId
    sessionNavStates[tabId] = Pair(false, false)
    wireDelegates(tabId, newSession)
    return newSession
  }

  private fun wireDelegates(tabId: String, session: GeckoSession) {
    // Wire Navigation delegate
    session.navigationDelegate = object : GeckoSession.NavigationDelegate {
      override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
        checkAndObserveGeckoOpen(tabId, session)
        if (!isCallbackAuthoritative(tabId, session, "onLoadRequest")) {
          return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        val url = request.uri ?: ""
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        val navStartMsg = "[FORENSIC] [NAV_START] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navStartMsg)
        com.remmi.browser.util.DebugLogManager.log(navStartMsg)

        val navLoadReqMsg = "[FORENSIC] [NAV_LOAD_REQUEST] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url isRedirect=${request.isRedirect} hasUserGesture=${request.hasUserGesture} gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navLoadReqMsg)
        com.remmi.browser.util.DebugLogManager.log(navLoadReqMsg)

        if (request.isRedirect && url.isNotBlank()) {
          lastRedirectUrls[tabId] = url
          NavigationChainTracker.recordHop(
            tabId = tabId,
            uri = url,
            hopType = HopType.HTTP_REDIRECT,
            isRedirect = true,
            hasUserGesture = request.hasUserGesture,
            isDirectNavigation = request.isDirectNavigation,
            triggerUri = request.triggerUri,
            target = request.target
          )
        }

        val tab = TabManager.getInstance().getTab(tabId)
        val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
        
        val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
        when (check.decision) {
            com.remmi.browser.security.NavigationDecision.BLOCK -> {
                val blockMsg = "[FORENSIC] [NAV_ERROR] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url error=security_blocked gen=$gen elapsedRealtime=$now"
                Log.e(TAG, blockMsg)
                com.remmi.browser.util.DebugLogManager.log(blockMsg)
                NavigationChainTracker.markSecurityBlocked(tabId, url, "security_authority_blocked")
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            com.remmi.browser.security.NavigationDecision.SANITIZE_AND_LOAD,
            com.remmi.browser.security.NavigationDecision.REDIRECT_SEARCH -> {
                if (check.sanitizedUrl != null && check.sanitizedUrl != url) {
                    session.loadUri(check.sanitizedUrl)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
            com.remmi.browser.security.NavigationDecision.ALLOW -> {
                // proceed
            }
        }

        // Intercept Ad Redirects (HTTP 3xx redirects or script-initiated navigations without user gesture)
        val sourceUrl = lastObservedUrls[tabId] ?: lastDispatchedUrls[tabId] ?: tab?.url ?: ""
        val sourceHost = try { if (sourceUrl.isNotBlank()) java.net.URI(sourceUrl).host?.lowercase() else null } catch (_: Exception) { null }
        val targetHost = try { if (url.isNotBlank()) java.net.URI(url).host?.lowercase() else null } catch (_: Exception) { null }
        val isCrossHost = sourceHost != null && targetHost != null && sourceHost != targetHost

        if (request.isRedirect || (!request.hasUserGesture && url.isNotBlank())) {
          if (isCrossHost || request.isRedirect) {
            val result = GeckoResult<AllowOrDeny>()
            val triggerUri = request.triggerUri ?: sourceUrl
            val isRedirect = request.isRedirect
            val hasUserGesture = request.hasUserGesture

            engineScope.launch(Dispatchers.Default) {
              val bridge = AdblockBridge.getInstance()
              val popupDec = bridge.evaluateDecision(
                url = url,
                sourceUrl = sourceUrl,
                initiator = triggerUri,
                method = "GET",
                resourceType = "popup",
                aggressive = isGhost,
                thirdParty = isCrossHost
              )
              val docDec = if (!popupDec.blocked) {
                bridge.evaluateDecision(
                  url = url,
                  sourceUrl = sourceUrl,
                  initiator = triggerUri,
                  method = "GET",
                  resourceType = "main_frame",
                  aggressive = isGhost,
                  thirdParty = isCrossHost
                )
              } else popupDec

              val isAdRedirect = com.remmi.browser.security.NavigationSecurityAuthority.isAdOrSpamDestination(url)

              if (popupDec.blocked || docDec.blocked || isAdRedirect) {
                val blockMsg = "[FORENSIC] [NAV_REDIRECT_BLOCKED] tabId=$tabId url=$url sourceUrl=$sourceUrl isRedirect=$isRedirect hasUserGesture=$hasUserGesture ruleId=${popupDec.ruleId ?: docDec.ruleId}"
                Log.w(TAG, blockMsg)
                com.remmi.browser.util.DebugLogManager.log(blockMsg)
                NavigationChainTracker.markSecurityBlocked(tabId, url, "adblock_redirect_denied")
                withContext(Dispatchers.Main) {
                  result.complete(AllowOrDeny.DENY)
                }
              } else {
                withContext(Dispatchers.Main) {
                  processAllowedLoadRequest(tabId, session, navId, gen, url, sessId, viewId, request, now)
                  result.complete(AllowOrDeny.ALLOW)
                }
              }
            }
            return result
          }
        }

        processAllowedLoadRequest(tabId, session, navId, gen, url, sessId, viewId, request, now)
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
      }

      private fun processAllowedLoadRequest(
        tabId: String,
        session: GeckoSession,
        navId: Long,
        gen: Long,
        url: String,
        sessId: String,
        viewId: String,
        request: GeckoSession.NavigationDelegate.LoadRequest,
        now: Long
      ) {
        val activeRecovery = activeRecoveries[tabId]
        if (activeRecovery != null && 
            activeRecovery.session === session && 
            activeRecovery.generation == gen) {
          if (request.isRedirect && url.isNotBlank() && !isInternalOrIgnoredUrl(url)) {
            recordRecoveryRedirect(tabId, url)
          }
          logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "recovery_inflight")
        } else if (url.isBlank() || isInternalOrIgnoredUrl(url)) {
          logNavAllocationRejected(tabId, navId, gen, url, "onLoadRequest", "internal_or_ignored")
        } else {
          val isInFlight = inFlightNavigations.containsKey(tabId)
          val prevDispatched = lastDispatchedUrls[tabId]
          val prevObserved = lastObservedUrls[tabId]
          val isEquivalentToDispatched = areUrlsEquivalent(prevDispatched, url)
          val isEquivalentToObserved = areUrlsEquivalent(prevObserved, url)
          val isSameUrl = isEquivalentToDispatched || isEquivalentToObserved

          if (request.isRedirect) {
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "redirect")
          } else if (isInFlight && (isSameUrl || isEquivalentToDispatched)) {
            // Belongs to the existing in-flight app navigation intent (e.g. loadUrl, reload, etc.)
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "correlated_to_inflight_intent")
          } else if (isSameUrl && !request.hasUserGesture) {
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "duplicate_or_same_url")
          } else if (request.hasUserGesture && !isSameUrl) {
            // Genuine user-gesture link click from inside the page!
            val prevUrl = prevObserved ?: prevDispatched
            lastDispatchedUrls[tabId] = url
            val (newNavId, newGen) = allocateNavigationGeneration(tabId, "USER_GESTURE", url)
            val inPageMsg = "[FORENSIC][IN_PAGE_NAV] tabId=$tabId session=$sessId view=$viewId navId=$newNavId url=$url prevUrl=$prevUrl newGen=$newGen trigger=onLoadRequest hasUserGesture=${request.hasUserGesture} elapsedRealtime=$now"
            Log.i(TAG, inPageMsg)
            com.remmi.browser.util.DebugLogManager.log(inPageMsg)

            val navReqMsg = "[FORENSIC] [NAV_REQUESTED] tabId=$tabId session=$sessId view=$viewId navId=$newNavId url=$url gen=$newGen trigger=USER_GESTURE elapsedRealtime=$now"
            Log.i(TAG, navReqMsg)
            com.remmi.browser.util.DebugLogManager.log(navReqMsg)

            // Mark loading state and initial progress immediately for instant UX feedback
            val oldProg = navProgressStates[tabId] ?: 0
            navLoadingStates[tabId] = true
            navProgressStates[tabId] = 15
            logProgressState(tabId, newNavId, newGen, "NAV_START", oldProg, 15, true, true, "user_gesture_link_click")

            sessionCallbacks[tabId]?.onLoadingChange(true)
            sessionCallbacks[tabId]?.onProgressChange(15)
            sessionCallbacks[tabId]?.onUrlChange(url)
            TabManager.getInstance().updateTab(tabId) {
              it.copy(url = url, isLoading = true, progress = 15, isReaderMode = false, readerArticle = null)
            }
            TabManager.getInstance().incrementInTabNavigation(tabId)
          } else if (!isSameUrl && !isInternalOrIgnoredUrl(url)) {
            // Non-user gesture navigation or script/redirect navigation
            val oldProg = navProgressStates[tabId] ?: 0
            navLoadingStates[tabId] = true
            navProgressStates[tabId] = 15
            sessionCallbacks[tabId]?.onLoadingChange(true)
            sessionCallbacks[tabId]?.onProgressChange(15)
            sessionCallbacks[tabId]?.onUrlChange(url)
            TabManager.getInstance().updateTab(tabId) {
              it.copy(url = url, isLoading = true, progress = 15)
            }
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", if (isInFlight) "inflight_active" else "unchanged")
          } else {
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", if (isInFlight) "inflight_active" else "unchanged")
          }
        }
      }

      override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        val forensicStart = "[FORENSIC][NEW_SESSION_START] url=$uri"
        Log.i(TAG, forensicStart)
        com.remmi.browser.util.DebugLogManager.log(forensicStart)
        val navNewSessionMsg = "[FORENSIC] [NAV_NEW_SESSION] sourceTabId=$tabId url=$uri elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, navNewSessionMsg)
        com.remmi.browser.util.DebugLogManager.log(navNewSessionMsg)
        
        val tabManager = com.remmi.browser.engine.TabManager.getInstance()
        val sourceTab = tabManager.getTab(tabId)
        val sourceUrl = sourceTab?.url ?: lastObservedUrls[tabId] ?: ""
        val sourceHost = try { if (sourceUrl.isNotBlank()) java.net.URI(sourceUrl).host?.lowercase() ?: "" else "" } catch (_: Exception) { "" }

        // Popup and Redirect Security Checks
        val isGhost = (sourceTab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
        val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(uri, isGhost)
        if (check.decision == com.remmi.browser.security.NavigationDecision.BLOCK) {
          val blockMsg = "[FORENSIC][POPUP_BLOCKED_SECURITY] sourceTabId=$tabId sourceUrl=$sourceUrl popupUrl=$uri reason=${check.reason}"
          Log.w(TAG, blockMsg)
          com.remmi.browser.util.DebugLogManager.log(blockMsg)
          return GeckoResult.fromValue(null)
        }

        val geckoResult = GeckoResult<GeckoSession>()
        engineScope.launch(Dispatchers.Default) {
          val sitePolicy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(sourceHost)
          
          // Evaluate Adblock rules for popup and main_frame on background thread
          val bridge = AdblockBridge.getInstance()
          val popupDecision = bridge.evaluateDecision(
            url = uri,
            sourceUrl = sourceUrl,
            initiator = sourceUrl,
            method = "GET",
            resourceType = "popup",
            aggressive = isGhost,
            thirdParty = true
          )
          val docDecision = if (!popupDecision.blocked) {
            bridge.evaluateDecision(
              url = uri,
              sourceUrl = sourceUrl,
              initiator = sourceUrl,
              method = "GET",
              resourceType = "main_frame",
              aggressive = isGhost,
              thirdParty = true
            )
          } else popupDecision

          val isAdPattern = com.remmi.browser.security.NavigationSecurityAuthority.isAdOrSpamDestination(uri)
          val isBlankPopupFromScript = sitePolicy.blockPopups && (uri.isBlank() || uri.equals("about:blank", ignoreCase = true))

          if (popupDecision.blocked || docDecision.blocked || isAdPattern || isBlankPopupFromScript) {
            val blockMsg = "[FORENSIC][POPUP_BLOCKED] sourceTabId=$tabId sourceUrl=$sourceUrl popupUrl=$uri ruleId=${popupDecision.ruleId ?: docDecision.ruleId} adPattern=$isAdPattern blank=$isBlankPopupFromScript"
            Log.w(TAG, blockMsg)
            com.remmi.browser.util.DebugLogManager.log(blockMsg)
            geckoResult.complete(null)
            return@launch
          }
          
          withContext(Dispatchers.Main) {
            val newTab = tabManager.createTab(
                url = uri,
                profile = sourceTab?.profile ?: PrivacyProfile.SHIELD,
                isDesktop = sourceTab?.isDesktopMode ?: false,
                containerType = sourceTab?.containerType ?: ContainerType.NORMAL,
                securityLevel = sourceTab?.securityLevel ?: SecurityLevel.STANDARD,
                groupId = sourceTab?.groupId,
                parentTabId = tabId,
                openedFromLink = true
            )
            
            // Rule 8: If a session is discovered to be already opened when onNewSession needs a session:
            // NEVER return/reuse it. Create a fresh unopened session instead.
            // Cleanly invalidate the abandoned session/tab binding without crashing.
            val preExisting = activeSessions[newTab.id]
            if (preExisting != null) {
              logSessionLifecycle(newTab.id, preExisting, "onNewSession:INVALIDATE_ABANDONED", "Discarding pre-existing session before fresh unopened session creation")
              activeSessions.remove(newTab.id)
              sessionOwners.remove(preExisting)
              sessionLifecycleStates[preExisting] = SessionLifecycleState.CLOSED
              pendingGeckoOpenSessions.remove(preExisting)
              try {
                preExisting.navigationDelegate = null
                preExisting.progressDelegate = null
                preExisting.contentDelegate = null
                if (preExisting.isOpen) {
                  preExisting.close()
                }
              } catch (_: Throwable) {}
            }

            // Strict Invariant: Create a completely fresh, unopened GeckoSession
            var freshSession = createSessionInternal(
                profile = newTab.profile,
                securityLevel = newTab.securityLevel,
                containerType = newTab.containerType,
                isDesktopMode = newTab.isDesktopMode,
                openSession = false
            )

            // Strict Rule: Never return parent tab session as child session
            if (freshSession === session) {
              logSessionLifecycle(newTab.id, freshSession, "onNewSession:CHECK_PARENT", "FRESH_SESSION_MATCHED_PARENT_SESSION")
              freshSession = createSessionInternal(newTab.profile, newTab.securityLevel, newTab.containerType, newTab.isDesktopMode, openSession = false)
            }

            // Defensive check: ensure freshSession is strictly unopened
            if (freshSession.isOpen) {
              logSessionLifecycle(newTab.id, freshSession, "onNewSession:CHECK_OPEN", "FRESH_SESSION_WAS_UNEXPECTEDLY_OPEN")
              freshSession = createSessionInternal(newTab.profile, newTab.securityLevel, newTab.containerType, newTab.isDesktopMode, openSession = false)
            }

            // Register required delegates before use
            wireDelegates(newTab.id, freshSession)

            // Store/bind it to the new tab without opening it prematurely
            activeSessions[newTab.id] = freshSession
            sessionOwners[freshSession] = newTab.id
            sessionLifecycleStates[freshSession] = SessionLifecycleState.CREATED_UNOPENED
            pendingGeckoOpenSessions.add(freshSession)
            sessionNavStates[newTab.id] = Pair(false, false)

            logSessionLifecycle(newTab.id, freshSession, "onNewSession:BOUND_UNOPENED")

            val (newNavId, newGen) = allocateNavigationGeneration(newTab.id, "NEW_SESSION", uri)
            lastDispatchedUrls[newTab.id] = uri

            NavigationChainTracker.recordHop(
              tabId = tabId,
              uri = uri,
              hopType = HopType.NEW_WINDOW_BLANK,
              triggerUri = sourceTab?.url,
              target = 2
            )
            NavigationChainTracker.linkParentChain(
              childTabId = newTab.id,
              parentTabId = tabId,
              parentNavId = currentNavIds[tabId]
            )

            val returnLog = "[FORENSIC][NEW_SESSION_RETURN] tabId=${newTab.id} session=0x${Integer.toHexString(System.identityHashCode(freshSession))} isOpen=${freshSession.isOpen}"
            Log.i(TAG, returnLog)
            com.remmi.browser.util.DebugLogManager.log(returnLog)
            geckoResult.complete(freshSession)
          }
        }
        return geckoResult
      }


      override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
      ) {
        checkAndObserveGeckoOpen(tabId, session)
        if (!isCallbackAuthoritative(tabId, session, "onLocationChange")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val genBefore = navGenerations[tabId] ?: 0L
        val activeNavId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        val navLocMsg = "[FORENSIC] [NAV_LOCATION] tabId=$tabId session=$sessId view=$viewId navId=$activeNavId url=$url gen=$genBefore hasUserGesture=$hasUserGesture elapsedRealtime=$now"
        Log.i(TAG, navLocMsg)
        com.remmi.browser.util.DebugLogManager.log(navLocMsg)

        val activeDispatched = lastDispatchedUrls[tabId]
        val isIntentionalBlank = activeDispatched == "about:blank" || 
                                 activeDispatched == null || 
                                 activeDispatched.isBlank() ||
                                 activeDispatched == "remmi://newtab" ||
                                 activeDispatched == "about:home"
        val isNavigatingRealUrl = inFlightNavigations.containsKey(tabId) || 
                                  (!activeDispatched.isNullOrBlank() && !isInternalOrIgnoredUrl(activeDispatched))

        val prevObserved = lastObservedUrls[tabId]
        val prevDispatched = lastDispatchedUrls[tabId]

        if (url != null) {
          latestLocationUrls[tabId] = url
          com.remmi.browser.security.permissions.PermissionSessionManager.getInstance().onTabNavigated(tabId, url)
        }

        // Recovery In-Flight Detection
        val activeRecovery = activeRecoveries[tabId]
        val isRecoveryActive = activeRecovery != null && 
                               activeRecovery.session === session && 
                               activeRecovery.generation == genBefore &&
                               activeRecovery.stage != RecoveryStage.SUCCESS &&
                               activeRecovery.stage != RecoveryStage.FAILED

        if (isRecoveryActive) {
          val isAboutBlank = (url == "about:blank" || isInternalOrIgnoredUrl(url))
          if (isAboutBlank) {
            checkPostNavFailure(tabId, "ABOUT_BLANK", "about:blank")
            logRecoveryUrlState(tabId, activeNavId, genBefore, url, true, activeRecovery?.targetUrl, "TRANSIENT_ABOUT_BLANK", "SUPPRESS_LOCATION_UPDATE")
            return
          }

          if (url != null) {
            val isTargetMatch = isRecoveryTargetOrRedirect(url, activeRecovery!!)
            if (isTargetMatch) {
              activeRecovery.stage = RecoveryStage.NAV_IN_FLIGHT
              val inFlightMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=$url targetUrl=${activeRecovery.targetUrl} gen=$genBefore elapsedRealtime=$now"
              Log.i(TAG, inFlightMsg)
              com.remmi.browser.util.DebugLogManager.log(inFlightMsg)
              logRecoveryUrlState(tabId, activeNavId, genBefore, url, true, activeRecovery.targetUrl, "RECOVERY_TARGET", "ADVANCE_STAGE_NAV_IN_FLIGHT")
              lastObservedUrls[tabId] = url
              lastDispatchedUrls[tabId] = url
              sessionCallbacks[tabId]?.onUrlChange(url)
              return
            }
          }
          logRecoveryUrlState(tabId, activeNavId, genBefore, url, true, activeRecovery?.targetUrl, "NORMAL", "PROCESS_NORMAL")
        } else {
          if (url == "about:blank") {
            if (!isIntentionalBlank && navLoadingStates[tabId] == true) {
              checkPostNavFailure(tabId, "ABOUT_BLANK", "about:blank")
            }
            if (!isIntentionalBlank && isNavigatingRealUrl) {
              val transMsg = "[FORENSIC][SUPPRESS_TRANSIENT_ABOUT_BLANK] tabId=$tabId targetUrl=$activeDispatched elapsedRealtime=$now"
              Log.i(TAG, transMsg)
              com.remmi.browser.util.DebugLogManager.log(transMsg)
              logRecoveryUrlState(tabId, activeNavId, genBefore, url, false, null, "TRANSIENT_ABOUT_BLANK", "SUPPRESS_LOCATION_UPDATE")
              return
            }
          }
          val blankClassification = if (url == "about:blank") "NORMAL_ABOUT_BLANK" else "NORMAL"
          logRecoveryUrlState(tabId, activeNavId, genBefore, url, false, null, blankClassification, "PROCESS_NORMAL")
        }

        if (url != null && !isInternalOrIgnoredUrl(url)) {
          lastObservedUrls[tabId] = url
        }

        if (activeRecovery == null && url != null && !isInternalOrIgnoredUrl(url)) {
          val isSameAsDispatched = areUrlsEquivalent(prevDispatched, url)
          val isSameAsObserved = areUrlsEquivalent(prevObserved, url)
          val hostChanged = isDifferentHost(prevObserved ?: prevDispatched, url)
          val isInFlight = inFlightNavigations.containsKey(tabId)

          val classification: String
          var genAfter: Long = genBefore

          if (isSameAsDispatched) {
            classification = "APP_REQUEST_MATCH"
            lastDispatchedUrls[tabId] = url
            inFlightNavigations.remove(tabId)
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "app_request_match")
          } else if (isSameAsObserved) {
            classification = "DUPLICATE_OBSERVATION"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "duplicate_observation")
          } else if (lastRedirectUrls[tabId] != null && areUrlsEquivalent(lastRedirectUrls[tabId], url)) {
            classification = "REDIRECT"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "redirect")
          } else if (isInFlight) {
            // Continuation / redirect / location resolution for existing in-flight navigation (prevent duplicate navId)
            classification = "IN_FLIGHT_LOCATION_MATCH"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "in_flight_location_match")
          } else if (!hostChanged && !hasUserGesture) {
            // Same-host SPA / script history change (e.g. DuckDuckGo replaceState / pushState)
            classification = "SAME_DOCUMENT_SPA"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "same_document_spa")
            val spaMsg = "[FORENSIC][NAV_SAME_DOC_SPA] tabId=$tabId session=$sessId view=$viewId navId=$activeNavId url=$url prevUrl=${prevObserved ?: prevDispatched} gen=$genBefore hasUserGesture=false elapsedRealtime=$now"
            Log.i(TAG, spaMsg)
            com.remmi.browser.util.DebugLogManager.log(spaMsg)
            NavigationChainTracker.recordHop(
              tabId = tabId,
              uri = url,
              hopType = HopType.SAME_DOCUMENT_SPA,
              hasUserGesture = hasUserGesture
            )
          } else {
            val prevUrl = prevObserved ?: prevDispatched
            lastDispatchedUrls[tabId] = url
            val (newNavId, newGen) = allocateNavigationGeneration(tabId, if (hasUserGesture) "USER_GESTURE" else "LOCATION_CHANGED", url)
            genAfter = newGen
            classification = if (hasUserGesture) "GENUINE_NEW_NAVIGATION" else "LOCATION_CHANGED"
            val inPageMsg = "[FORENSIC][IN_PAGE_NAV] tabId=$tabId session=$sessId view=$viewId navId=$newNavId url=$url prevUrl=$prevUrl newGen=$newGen trigger=onLocationChange hasUserGesture=$hasUserGesture elapsedRealtime=$now"
            Log.i(TAG, inPageMsg)
            com.remmi.browser.util.DebugLogManager.log(inPageMsg)
          }

          val evalMsg = "[FORENSIC][NAV_LOCATION_EVAL] tabId=$tabId prevObserved=$prevObserved prevDispatched=$prevDispatched canonicalCurrent=$url isSameAsObserved=$isSameAsObserved isSameAsDispatched=$isSameAsDispatched hostChanged=$hostChanged hasUserGesture=$hasUserGesture activeNavId=$activeNavId generationBefore=$genBefore generationAfter=$genAfter classification=$classification"
          Log.i(TAG, evalMsg)
          com.remmi.browser.util.DebugLogManager.log(evalMsg)
        }

        url?.let {
          if (it.isNotBlank()) {
            if (it != "about:blank") {
              try {
                val host = java.net.URI(if (it.contains("://")) it else "https://$it").host
                if (!host.isNullOrBlank()) {
                  applySiteSecurityPolicy(tabId, host)
                }
              } catch (_: Exception) {}
            }
            if (it == "about:blank") {
              val activeDispatchedUrl = lastDispatchedUrls[tabId]
              val isIntentionalBlank = activeDispatchedUrl == "about:blank" || 
                                       activeDispatchedUrl == null || 
                                       activeDispatchedUrl.isBlank() ||
                                       activeDispatchedUrl == "remmi://newtab" ||
                                       activeDispatchedUrl == "about:home"
              val isNavigatingRealUrl = inFlightNavigations.containsKey(tabId) || 
                                        (!activeDispatchedUrl.isNullOrBlank() && !isInternalOrIgnoredUrl(activeDispatchedUrl))

              if (!isIntentionalBlank && isNavigatingRealUrl) {
                val transMsg = "[FORENSIC][SUPPRESS_TRANSIENT_ABOUT_BLANK_CALLBACK] tabId=$tabId targetUrl=$activeDispatchedUrl"
                Log.i(TAG, transMsg)
                com.remmi.browser.util.DebugLogManager.log(transMsg)
              } else {
                lastObservedUrls[tabId] = "about:blank"
                lastDispatchedUrls[tabId] = "about:blank"
                sessionCallbacks[tabId]?.onUrlChange("about:blank")
                sessionCallbacks[tabId]?.onTitleChange("New Tab")
                sessionCallbacks[tabId]?.onSecurityChange(true)
              }
            } else if (!isInternalOrIgnoredUrl(it)) {
              sessionCallbacks[tabId]?.onUrlChange(it)
              NavigationChainTracker.markPageCompleted(tabId, it)
            }
          }
        }
      }

      override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        if (!isCallbackAuthoritative(tabId, session, "onCanGoBack")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val navBackMsg = "[FORENSIC] [NAV_CAN_GO_BACK] tabId=$tabId session=$sessId view=$viewId canGoBack=$canGoBack gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navBackMsg)
        com.remmi.browser.util.DebugLogManager.log(navBackMsg)

        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(first = canGoBack)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }

      override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        if (!isCallbackAuthoritative(tabId, session, "onCanGoForward")) {
          return
        }

        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(second = canGoForward)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }

      override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: org.mozilla.geckoview.WebRequestError
      ): GeckoResult<String>? {
        if (!isCallbackAuthoritative(tabId, session, "onLoadError")) {
          return null
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val errLog = "[FORENSIC][NAV_LOAD_ERROR] tabId=$tabId session=$sessId uri=$uri category=${error.category} error=${error.code}"
        Log.w(TAG, errLog)
        com.remmi.browser.util.DebugLogManager.log(errLog)

        val targetUrl = uri ?: lastDispatchedUrls[tabId] ?: ""
        val errorDataUri = OfflineErrorPageGenerator.toDataUri(
          targetUrl = targetUrl,
          errorCode = "ERR_INTERNET_DISCONNECTED",
          isDark = true
        )
        return GeckoResult.fromValue(errorDataUri)
      }
    }

    // Wire Progress delegate
    session.progressDelegate = object : GeckoSession.ProgressDelegate {
      override fun onPageStart(session: GeckoSession, url: String) {
        if (!isCallbackAuthoritative(tabId, session, "onPageStart")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        latestProgressUrls[tabId] = url
        val progMsg = "[FORENSIC] [NAV_PROGRESS] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url progress=10 gen=$gen state=start elapsedRealtime=$now"
        Log.i(TAG, progMsg)
        com.remmi.browser.util.DebugLogManager.log(progMsg)

        val pageStartMsg = "[FORENSIC] [NAV_PAGE_START] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url gen=$gen elapsedRealtime=$now"
        Log.i(TAG, pageStartMsg)
        com.remmi.browser.util.DebugLogManager.log(pageStartMsg)

        logContentProcessEvent(event = "READY", tabId = tabId, session = session, url = url, reason = "PAGE_START")
        getMemoryForensicSnapshot("NAV_START")

        val activeRecovery = activeRecoveries[tabId]
        if (activeRecovery != null && 
            activeRecovery.session === session && 
            activeRecovery.generation == gen && 
            activeRecovery.stage == RecoveryStage.DISPATCHED) {
          if (!isInternalOrIgnoredUrl(url)) {
            val isTargetMatch = isRecoveryTargetOrRedirect(url, activeRecovery)
            if (isTargetMatch) {
              activeRecovery.stage = RecoveryStage.NAV_IN_FLIGHT
              val inFlightMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=$url targetUrl=${activeRecovery.targetUrl} gen=$gen elapsedRealtime=$now"
              Log.i(TAG, inFlightMsg)
              com.remmi.browser.util.DebugLogManager.log(inFlightMsg)
            }
          }
        }

        val oldProg = navProgressStates[tabId] ?: 0
        navLoadingStates[tabId] = true
        navProgressStates[tabId] = 10
        logProgressState(tabId, navId, gen, "NAV_START", oldProg, 10, true, true, "page_start")

        sessionCallbacks[tabId]?.onLoadingChange(true)
        sessionCallbacks[tabId]?.onProgressChange(10)
      }

      override fun onPageStop(session: GeckoSession, success: Boolean) {
        if (!isCallbackAuthoritative(tabId, session, "onPageStop")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastObservedUrls[tabId] ?: lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        val stopMsg = "[FORENSIC] [NAV_STOP] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl success=$success gen=$gen elapsedRealtime=$now"
        Log.i(TAG, stopMsg)
        com.remmi.browser.util.DebugLogManager.log(stopMsg)

        inFlightNavigations.remove(tabId)
        getMemoryForensicSnapshot("NAV_STOP")

        if (success) {
          lastSuccessfulNavigations[tabId] = SuccessfulNavRecord(
            navId = navId,
            url = currUrl,
            gen = gen,
            timestampElapsed = now,
          )
        }

        val activeRecovery = activeRecoveries[tabId]
        val latestLoc = latestLocationUrls[tabId]
        val latestProg = latestProgressUrls[tabId]
        val isAboutBlank = (currUrl == "about:blank" || latestLoc == "about:blank" || latestProg == "about:blank" || isInternalOrIgnoredUrl(currUrl))
        if (latestLoc == "about:blank" || isInternalOrIgnoredUrl(latestLoc)) {
          latestLocationUrls.remove(tabId)
        }
        if (latestProg == "about:blank" || isInternalOrIgnoredUrl(latestProg)) {
          latestProgressUrls.remove(tabId)
        }

        val isRecoveryActive = activeRecovery != null && 
                               activeRecovery.session === session && 
                               activeRecovery.generation == gen &&
                               activeRecovery.stage != RecoveryStage.SUCCESS && 
                               activeRecovery.stage != RecoveryStage.FAILED

        if (isRecoveryActive) {
          val targetMatches = !isAboutBlank && isRecoveryTargetOrRedirect(currUrl, activeRecovery!!)

          if (isAboutBlank && !targetMatches) {
            // Transient about:blank stop during recovery - ignore and do not clear recovery or loading state
            val oldProg = navProgressStates[tabId] ?: 0
            logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, oldProg, true, false, "transient_about_blank_in_recovery")
            logRecoveryUrlState(tabId, navId, gen, currUrl, true, activeRecovery.targetUrl, "TRANSIENT_RECOVERY_BLANK", "SUPPRESS_PAGE_STOP")
            return
          }

          if (success && targetMatches) {
            activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            activeRecovery.stage = RecoveryStage.SUCCESS
            transitionRecoveryState(tabId, RecoveryState.SUCCESS, activeRecovery.navId, gen, "recovery_success")
            activeRecoveries.remove(tabId)
            lastRecoveredGenerations.remove(tabId)
            val origFail = lastOriginalFailures[tabId] ?: "NONE"
            val succMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_SUCCESS] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=${activeRecovery.targetUrl} gen=$gen originalFailure=$origFail elapsedRealtime=$now"
            Log.i(TAG, succMsg)
            com.remmi.browser.util.DebugLogManager.log(succMsg)
            logContentProcessEvent(event = "RECOVER", tabId = tabId, session = session, url = activeRecovery.targetUrl, reason = "RECOVERY_SUCCESS")
            getMemoryForensicSnapshot("RECOVERY_SUCCESS")

            val oldProg = navProgressStates[tabId] ?: 0
            navLoadingStates[tabId] = false
            navProgressStates[tabId] = 0
            logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "recovery_success")
            logRecoveryUrlState(tabId, navId, gen, currUrl, false, activeRecovery.targetUrl, "RECOVERY_TARGET", "FINALIZE_SUCCESS")

            sessionCallbacks[tabId]?.onLoadingChange(false)
            sessionCallbacks[tabId]?.onProgressChange(0)
            return
          } else if (!success && targetMatches) {
            activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            activeRecovery.stage = RecoveryStage.FAILED
            transitionRecoveryState(tabId, RecoveryState.FAILED, activeRecovery.navId, gen, "recovery_failed")
            activeRecoveries.remove(tabId)
            lastRecoveredGenerations[tabId] = gen
            val failMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_FAILED] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=$currUrl targetUrl=${activeRecovery.targetUrl} gen=$gen success=$success elapsedRealtime=$now"
            Log.w(TAG, failMsg)
            com.remmi.browser.util.DebugLogManager.log(failMsg)

            val oldProg = navProgressStates[tabId] ?: 0
            navLoadingStates[tabId] = false
            navProgressStates[tabId] = 0
            logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "recovery_failed")
            logRecoveryUrlState(tabId, navId, gen, currUrl, false, activeRecovery.targetUrl, "RECOVERY_TARGET", "FINALIZE_FAILED")

            sessionCallbacks[tabId]?.onLoadingChange(false)
            sessionCallbacks[tabId]?.onProgressChange(0)
            return
          }
        }

        if (!success) {
          checkPostNavFailure(tabId, "PAGE_STOP_FAILED", currUrl)
        } else {
          val currentSettings = com.remmi.browser.storage.SettingsRepository.getInstance(context).settings.value
          if (currentSettings.darkThemeForAllWebPages && !isInternalOrIgnoredUrl(currUrl)) {
            val forceDarkJs = "javascript:(function(){try{if(document.getElementById('__remmi_dark_mode_style'))return;var s=document.createElement('style');s.id='__remmi_dark_mode_style';s.textContent=':root,html{color-scheme:dark!important;}@media(prefers-color-scheme:light),(prefers-color-scheme:no-preference){html,body{background-color:#121212!important;color:#e0e0e0!important;}}';(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
            try {
              session.loadUri(forceDarkJs)
            } catch (_: Exception) {}
          }
        }

        val oldProg = navProgressStates[tabId] ?: 0
        navLoadingStates[tabId] = false
        navProgressStates[tabId] = 0
        logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "navigation_complete")

        sessionCallbacks[tabId]?.onLoadingChange(false)
        sessionCallbacks[tabId]?.onProgressChange(0)
      }

      override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (!isCallbackAuthoritative(tabId, session, "onProgressChange")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()

        val isLoading = navLoadingStates[tabId] ?: false
        val oldProg = navProgressStates[tabId] ?: 0

        if (!isLoading) {
          logProgressState(tabId, navId, gen, "NAV_PROGRESS", oldProg, progress, false, false, "stale_navigation_completed")
          return
        }

        val newProg = maxOf(oldProg, progress.coerceIn(0, 100))
        navProgressStates[tabId] = newProg
        logProgressState(tabId, navId, gen, "NAV_PROGRESS", oldProg, newProg, true, true, "progress_update")

        if (com.remmi.browser.BuildConfig.DEBUG) {
          val progMsg = "[FORENSIC] [NAV_PROGRESS] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl progress=$newProg gen=$gen state=update elapsedRealtime=$now"
          Log.d(TAG, progMsg)
        }

        sessionCallbacks[tabId]?.onProgressChange(newProg)
      }

      override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
      ) {
        if (!isCallbackAuthoritative(tabId, session, "onSecurityChange")) {
          return
        }
        sessionCallbacks[tabId]?.onSecurityChange(securityInfo.isSecure)
      }
    }

    // Wire Content delegate
    session.contentDelegate = object : GeckoSession.ContentDelegate {
      override fun onCrash(session: GeckoSession) {
        if (!isCallbackAuthoritative(tabId, session, "onCrash")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val view = attachedViews[tabId]
        val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val isOpen = session.isOpen
        val threadName = Thread.currentThread().name
        val now = android.os.SystemClock.elapsedRealtime()
        val crashMsg = "[FORENSIC][CONTENT_CRASH] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen isOpen=$isOpen thread=$threadName elapsedRealtime=$now"
        Log.e(TAG, crashMsg)
        com.remmi.browser.util.DebugLogManager.log(crashMsg)

        checkPostNavFailure(tabId, "CONTENT_CRASH", currUrl)
        logContentProcessEvent(event = "CRASH", tabId = tabId, session = session, url = currUrl, reason = "GECKO_CRASH")
        getMemoryForensicSnapshot("CONTENT_KILL")
        handleContentProcessTermination(tabId, session, "CRASH")
      }

      override fun onKill(session: GeckoSession) {
        if (!isCallbackAuthoritative(tabId, session, "onKill")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val view = attachedViews[tabId]
        val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val isOpen = session.isOpen
        val threadName = Thread.currentThread().name
        val now = android.os.SystemClock.elapsedRealtime()
        val killMsg = "[FORENSIC][CONTENT_KILL] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen isOpen=$isOpen thread=$threadName elapsedRealtime=$now"
        Log.e(TAG, killMsg)
        com.remmi.browser.util.DebugLogManager.log(killMsg)

        checkPostNavFailure(tabId, "CONTENT_KILL", currUrl)
        logContentProcessEvent(event = "KILL", tabId = tabId, session = session, url = currUrl, reason = "GECKO_KILL")
        getMemoryForensicSnapshot("CONTENT_KILL")
        handleContentProcessTermination(tabId, session, "KILL")
      }

      override fun onTitleChange(session: GeckoSession, title: String?) {
        if (!isCallbackAuthoritative(tabId, session, "onTitleChange")) {
          return
        }

        title?.let {
          if (it.isNotBlank() && it != "about:blank") {
            sessionCallbacks[tabId]?.onTitleChange(it)
          }
        }
      }

      override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        if (!isCallbackAuthoritative(tabId, session, "onExternalResponse")) {
          return
        }
        
        val revertedUrl = lastObservedUrls[tabId]
        if (revertedUrl != null) {
          lastDispatchedUrls[tabId] = revertedUrl
          sessionCallbacks[tabId]?.onUrlChange(revertedUrl)
        }
        
        val extMsg = com.remmi.browser.util.UrlSanitizer.formatExternalResponseLog(tabId, response.uri, revertedUrl)
        Log.i(TAG, extMsg)
        com.remmi.browser.util.DebugLogManager.log(extMsg)

        NavigationChainTracker.markDownloadCompleted(
          tabId = tabId,
          downloadUrl = response.uri,
          visiblePageUrl = revertedUrl
        )
        
        sessionCallbacks[tabId]?.onExternalResponse(response)
      }

      override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
      ) {
        if (!isCallbackAuthoritative(tabId, session, "onContextMenu", isViewBound = true)) {
          return
        }

        val hasLink = !element.linkUri.isNullOrBlank()
        val hasImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE || !element.srcUri.isNullOrBlank()
        if (hasLink || hasImage) {
          sessionCallbacks[tabId]?.onContextMenu(
            WebContextMenuData(
              linkUri = element.linkUri,
              srcUri = element.srcUri,
              altText = element.altText,
              title = element.title,
              type = element.type,
            )
          )
        }
      }
    }

    // Wire Scroll delegate
    session.scrollDelegate = object : GeckoSession.ScrollDelegate {
      private var lastScrollY = 0
      override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
        currentScrollPositions[tabId] = scrollY
        if (!isCallbackAuthoritative(tabId, session, "onScrollChanged", isViewBound = true)) {
          return
        }

        if (scrollY <= 15) {
          lastScrollY = scrollY
          sessionCallbacks[tabId]?.onScrollChanged(scrollX, scrollY, false)
          return
        }

        val deltaY = scrollY - lastScrollY
        // Threshold to avoid toggling on micro-jitters
        if (Math.abs(deltaY) < 14) {
          return
        }

        val isScrollingDown = deltaY > 0
        lastScrollY = scrollY
        sessionCallbacks[tabId]?.onScrollChanged(scrollX, scrollY, isScrollingDown)
      }
    }

    // Wire Permission delegate (Least-Privilege, Exact-Origin, Native GeckoView)
    val tab = com.remmi.browser.engine.TabManager.getInstance().getTab(tabId)
    val isGhost = (tab?.profile == com.remmi.browser.security.PrivacyProfile.GHOST) || (currentProfile == com.remmi.browser.security.PrivacyProfile.GHOST)
    session.permissionDelegate = com.remmi.browser.security.permissions.RemmiPermissionDelegate(
      tabId = tabId,
      isGhost = isGhost
    )

    // Wire Prompt delegate (File picker / upload / native login prompts)
    session.promptDelegate = com.remmi.browser.security.permissions.RemmiPromptDelegate(
      context = context,
      tabId = tabId,
      filePickerProvider = { filePickerRequester },
      autofillCoordinatorProvider = { autofillCoordinatorProvider?.invoke() }
    )
  }

  /**
   * Internal session execution runner confined strictly to the Android Main thread.
   * Completely encapsulates activeSessions and isolates GeckoSession manipulation.
   */
  private suspend fun <T> withSession(
    tabId: String,
    operation: String = "OPERATION",
    action: (GeckoSession) -> T,
  ): T? = withContext(Dispatchers.Main.immediate) {
    assertMainThread("WITH_SESSION op=$operation id=$tabId")
    val session = activeSessions[tabId] ?: return@withContext null
    try {
      action(session)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] withSession op=$operation failed on tabId=$tabId: ${e.message}")
      null
    }
  }

  /**
   * Synchronous gateway for fire-and-forget UI calls, automatically dispatches to Main thread.
   */
  private fun onMainSession(
    tabId: String,
    operation: String,
    action: (GeckoSession) -> Unit,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { onMainSession(tabId, operation, action) }
      return
    }
    assertMainThread("MAIN_SESSION op=$operation id=$tabId")
    val session = activeSessions[tabId] ?: return
    try {
      action(session)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] onMainSession op=$operation error on tabId=$tabId: ${e.message}")
    }
  }

  // --- View Attachment & Lifecycle Control ---

  private fun dispatchPendingNavigationIfReady(tabId: String) {
    assertMainThread("DISPATCH_PENDING id=$tabId")
    val pending = pendingNavigations.remove(tabId) ?: return
    val tab = TabManager.getInstance().getTab(tabId)
    val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
    if (isGhost && !com.remmi.browser.security.TorLifecycleManager.getInstance(context).isTorReady.value) {
      pendingNavigations[tabId] = pending
      val gateMsg = "[FORENSIC][TOR_GATE_BLOCK] Retaining pending navigation for tab=$tabId url=${pending.url}: Tor is not 100% ready"
      Log.i(TAG, gateMsg)
      com.remmi.browser.util.DebugLogManager.log(gateMsg)
      return
    }

    val session = activeSessions[tabId] ?: return
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val threadName = Thread.currentThread().name
    val navId = pending.navId
    currentNavIds[tabId] = navId
    
    val isRecoveryActive = activeRecoveries.containsKey(tabId) || pendingContentRecoveries.containsKey(tabId)
    val isActualDuplicate = (lastDispatchedUrls[tabId] == pending.url) && !isRecoveryActive

    if (isActualDuplicate) {
      val skipMsg = "[FORENSIC] [GECKO_NAV_SKIPPED_DUPLICATE] tabId=$tabId session=$sessId view=$viewId navId=$navId gen=${pending.generation} url=${pending.url} thread=$threadName"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      return
    }
    
    lastDispatchedUrls[tabId] = pending.url
    dispatchedNavigationsHistory.getOrPut(tabId) { mutableListOf() }.add(pending.url)
    val dispatchMsg = "[FORENSIC] [GECKO_NAV_DISPATCH] tabId=$tabId session=$sessId view=$viewId navId=$navId gen=${pending.generation} url=${pending.url} thread=$threadName"
    Log.i(TAG, dispatchMsg)
    com.remmi.browser.util.DebugLogManager.log(dispatchMsg)
    
    try {
      if (!session.isOpen && session !in pendingGeckoOpenSessions) {
        openSessionSafely(session, tabId, "dispatchPendingNavigation")
      }

      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, session, pending.url)
      } else {
        session.loadUri(pending.url)
      }
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] dispatchPendingNavigation error on tabId=$tabId: ${e.message}")
    }
  }

  private fun handleContentProcessTermination(
    tabId: String,
    session: GeckoSession,
    terminationType: String // "CRASH" or "KILL"
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { handleContentProcessTermination(tabId, session, terminationType) }
      return
    }

    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    val view = attachedViews[tabId]
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currUrl = lastObservedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: TabManager.getInstance().getTab(tabId)?.url?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: lastDispatchedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: ""
    val gen = navGenerations[tabId] ?: 0L
    val now = android.os.SystemClock.elapsedRealtime()

    if (currUrl.isNotBlank()) {
      lastDispatchedUrls[tabId] = currUrl
    }

    val inFlightRecovery = activeRecoveries.remove(tabId)
    if (inFlightRecovery != null) {
      inFlightRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
      val crashInFlightMsg = "[FORENSIC][CONTENT_RECOVERY_CRASH_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId navId=${inFlightRecovery.navId} url=${inFlightRecovery.targetUrl} gen=${inFlightRecovery.generation} stage=${inFlightRecovery.stage} termination=$terminationType elapsedRealtime=$now"
      Log.e(TAG, crashInFlightMsg)
      com.remmi.browser.util.DebugLogManager.log(crashInFlightMsg)
      lastRecoveredGenerations[tabId] = gen
    }

    // 1. Verify active session ownership (only recover if this session is the active session for the tab)
    val currentActive = activeSessions[tabId]
    if (currentActive == null || currentActive !== session) {
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen reason=stale_or_inactive_session elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // 2. Validate URL (do not reload about:blank, remmi://newtab, or empty URL)
    if (currUrl.isBlank() || currUrl == "about:blank" || currUrl.startsWith("remmi://")) {
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen reason=invalid_or_blank_url elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // 2. BACKGROUND / DETACHED TAB SAFETY
    // Current code can receive onKill/onCrash while attachedViews[tabId] == null.
    // Do NOT consume the single per-generation recovery attempt for a detached/background tab.
    if (!isViewAttached(tabId)) {
      val navId = getActiveNavId(tabId)
      pendingContentRecoveries[tabId] = PendingContentRecovery(
        session = session,
        url = currUrl,
        generation = gen,
        navId = navId,
        terminationType = terminationType,
      )
      transitionRecoveryState(tabId, RecoveryState.PENDING_DETACHED, navId, gen, "view_absent_deferred")
      val defMsg = "[FORENSIC][CONTENT_RECOVERY_DEFERRED] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen termination=$terminationType elapsedRealtime=$now"
      Log.i(TAG, defMsg)
      com.remmi.browser.util.DebugLogManager.log(defMsg)
      return
    }

    // 3. FOREGROUND ACTIVE TAB
    // Prevent recovery loops: maximum one automatic recovery attempt per navigation generation
    val lastRecoveredGen = lastRecoveredGenerations[tabId]
    if (lastRecoveredGen != null && lastRecoveredGen == gen) {
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen reason=max_attempts_exceeded elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // 4. Mark recovery start
    lastRecoveredGenerations[tabId] = gen
    val navId = getActiveNavId(tabId)
    transitionRecoveryState(tabId, RecoveryState.STARTING, navId, gen, "foreground_$terminationType")
    val startMsg = "[FORENSIC][CONTENT_RECOVERY_START] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen termination=$terminationType elapsedRealtime=$now"
    Log.i(TAG, startMsg)
    com.remmi.browser.util.DebugLogManager.log(startMsg)

    // 1. SESSION REOPEN HARDENING
    // Before recovery load, use the same lifecycle pattern already present in loadUrl():
    // if (!session.isOpen) session.open(runtime)
    if (!session.isOpen && session !in pendingGeckoOpenSessions) {
      openSessionSafely(session, tabId, "contentRecovery")
    }

    // CRITICAL: Ensure GeckoView reconnects to session display surface after content process crash
    val attachedView = attachedViews[tabId]
    if (attachedView != null) {
      try {
        if (attachedView.session === session) {
          try {
            attachedView.releaseSession()
          } catch (_: Exception) {}
        }
        attachedView.setSession(session)
        session.setActive(true)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] Failed to re-link session to GeckoView on recovery: ${e.message}")
      }
    }

    // 5. Issue recovery load
    val loadMsg = "[FORENSIC][CONTENT_RECOVERY_LOAD] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen elapsedRealtime=$now"
    Log.i(TAG, loadMsg)
    com.remmi.browser.util.DebugLogManager.log(loadMsg)

    try {
      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, session, currUrl)
      } else {
        session.loadUri(currUrl)
      }

      val recovery = ActiveRecovery(
        tabId = tabId,
        session = session,
        targetUrl = currUrl,
        generation = gen,
        navId = navId,
        startTime = now,
        stage = RecoveryStage.DISPATCHED,
      )
      val timeoutRunnable = Runnable {
        val current = activeRecoveries[tabId]
        if (current != null && current.generation == gen && current.stage != RecoveryStage.SUCCESS && current.stage != RecoveryStage.FAILED) {
          activeRecoveries.remove(tabId)
          lastRecoveredGenerations[tabId] = gen
          transitionRecoveryState(tabId, RecoveryState.FAILED, navId, gen, "recovery_timeout")
          val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen stage=${current.stage} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          Log.w(TAG, toMsg)
          com.remmi.browser.util.DebugLogManager.log(toMsg)

          val oldProg = navProgressStates[tabId] ?: 0
          navLoadingStates[tabId] = false
          navProgressStates[tabId] = 0
          logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "recovery_timeout")
          logRecoveryUrlState(tabId, navId, gen, currUrl, false, current.targetUrl, "RECOVERY_TARGET", "FINALIZE_TIMEOUT")

          sessionCallbacks[tabId]?.onLoadingChange(false)
          sessionCallbacks[tabId]?.onProgressChange(0)
        }
      }
      recovery.timeoutRunnable = timeoutRunnable
      mainHandler.postDelayed(timeoutRunnable, 15000L)
      activeRecoveries[tabId] = recovery
      transitionRecoveryState(tabId, RecoveryState.IN_FLIGHT, navId, gen, "dispatched_load")

      val dispMsg = "[FORENSIC][CONTENT_RECOVERY_DISPATCHED] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, dispMsg)
      com.remmi.browser.util.DebugLogManager.log(dispMsg)
    } catch (e: Exception) {
      transitionRecoveryState(tabId, RecoveryState.FAILED, navId, gen, "dispatch_exception_${e.message}")
      val failMsg = "[FORENSIC][CONTENT_RECOVERY_FAILED] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen error=${e.message} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.e(TAG, failMsg, e)
      com.remmi.browser.util.DebugLogManager.log(failMsg)
    }
  }

  private fun resumePendingContentRecoveryIfAny(tabId: String) {
    val pendingRecovery = pendingContentRecoveries[tabId] ?: return
    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val view = attachedViews[tabId]
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currentGen = navGenerations[tabId] ?: 0L
    val now = android.os.SystemClock.elapsedRealtime()

    // 4. RACE PROTECTION
    // Verify active session ownership
    if (session == null || session !== pendingRecovery.session || sessionLifecycleStates[session] == SessionLifecycleState.CLOSED) {
      pendingContentRecoveries.remove(tabId)
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRecovery.navId, pendingRecovery.generation, "stale_or_inactive_session")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=${pendingRecovery.url} gen=${pendingRecovery.generation} reason=stale_or_inactive_session elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    if (!isViewAttached(tabId)) {
      return
    }

    // Preserve generation checks so stale recovery cannot override a newer user navigation
    val hasPendingNav = pendingNavigations.containsKey(tabId)
    if (hasPendingNav || currentGen > pendingRecovery.generation) {
      pendingContentRecoveries.remove(tabId)
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRecovery.navId, pendingRecovery.generation, "superseded_by_newer_navigation")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=${pendingRecovery.url} gen=${pendingRecovery.generation} currentGen=$currentGen reason=superseded_by_newer_navigation elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // Verify URL validity
    val targetUrl = pendingRecovery.url.ifBlank {
      lastObservedUrls[tabId]?.takeIf { !isInternalOrIgnoredUrl(it) }
        ?: TabManager.getInstance().getTab(tabId)?.url?.takeIf { !isInternalOrIgnoredUrl(it) }
        ?: lastDispatchedUrls[tabId]?.takeIf { !isInternalOrIgnoredUrl(it) }
        ?: ""
    }
    if (isInternalOrIgnoredUrl(targetUrl)) {
      pendingContentRecoveries.remove(tabId)
      transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "invalid_or_blank_url")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} reason=invalid_or_blank_url elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // Check generation loop suppression
    val lastRecoveredGen = lastRecoveredGenerations[tabId]
    if (lastRecoveredGen != null && lastRecoveredGen == pendingRecovery.generation) {
      pendingContentRecoveries.remove(tabId)
      transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "max_attempts_exceeded")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} reason=max_attempts_exceeded elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    lastDispatchedUrls[tabId] = targetUrl
    val resumeMsg = "[FORENSIC][CONTENT_RECOVERY_RESUME] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} termination=${pendingRecovery.terminationType} elapsedRealtime=$now"
    Log.i(TAG, resumeMsg)
    com.remmi.browser.util.DebugLogManager.log(resumeMsg)

    lastRecoveredGenerations[tabId] = pendingRecovery.generation

    transitionRecoveryState(tabId, RecoveryState.STARTING, pendingRecovery.navId, pendingRecovery.generation, "view_reattached")
    val startMsg = "[FORENSIC][CONTENT_RECOVERY_START] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} termination=${pendingRecovery.terminationType} elapsedRealtime=$now"
    Log.i(TAG, startMsg)
    com.remmi.browser.util.DebugLogManager.log(startMsg)

    // Safely reopen session if needed
    if (!session.isOpen && session !in pendingGeckoOpenSessions) {
      openSessionSafely(session, tabId, "contentRecovery")
    }

    if (view != null) {
      try {
        if (view.session === session) {
          try {
            view.releaseSession()
          } catch (_: Exception) {}
        }
        view.setSession(session)
        session.setActive(true)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] Failed to link session to view in resume: ${e.message}")
      }
    }

    val loadMsg = "[FORENSIC][CONTENT_RECOVERY_LOAD] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    Log.i(TAG, loadMsg)
    com.remmi.browser.util.DebugLogManager.log(loadMsg)

    try {
      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, session, targetUrl)
      } else {
        session.loadUri(targetUrl)
      }
      // clear the pending recovery state only after dispatch is accepted
      pendingContentRecoveries.remove(tabId)

      val recovery = ActiveRecovery(
        tabId = tabId,
        session = session,
        targetUrl = targetUrl,
        generation = pendingRecovery.generation,
        navId = pendingRecovery.navId,
        startTime = now,
        stage = RecoveryStage.DISPATCHED,
      )
      val timeoutRunnable = Runnable {
        val current = activeRecoveries[tabId]
        if (current != null && current.generation == pendingRecovery.generation && current.stage != RecoveryStage.SUCCESS && current.stage != RecoveryStage.FAILED) {
          activeRecoveries.remove(tabId)
          lastRecoveredGenerations[tabId] = pendingRecovery.generation
          transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "recovery_timeout")
          val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} stage=${current.stage} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          Log.w(TAG, toMsg)
          com.remmi.browser.util.DebugLogManager.log(toMsg)
        }
      }
      recovery.timeoutRunnable = timeoutRunnable
      mainHandler.postDelayed(timeoutRunnable, 15000L)
      activeRecoveries[tabId] = recovery
      transitionRecoveryState(tabId, RecoveryState.IN_FLIGHT, pendingRecovery.navId, pendingRecovery.generation, "dispatched_load")

      val dispMsg = "[FORENSIC][CONTENT_RECOVERY_DISPATCHED] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, dispMsg)
      com.remmi.browser.util.DebugLogManager.log(dispMsg)
    } catch (e: Exception) {
      transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "dispatch_exception_${e.message}")
      val failMsg = "[FORENSIC][CONTENT_RECOVERY_FAILED] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} error=${e.message} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.e(TAG, failMsg, e)
      com.remmi.browser.util.DebugLogManager.log(failMsg)
    }
  }

  fun checkViewInvariants(targetTabId: String? = null, reason: String = "") {
    val allTabIds = (activeSessions.keys + attachedViews.keys).distinct()
    for (tId in allTabIds) {
      val session = activeSessions[tId]
      val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
      val view = attachedViews[tId]
      val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
      val tag = view?.tag?.toString() ?: "null"
      val url = lastDispatchedUrls[tId] ?: "none"
      
      val duplicateViewTabs = attachedViews.filter { it.value === view && view != null }.keys.toList()
      val isMultiMapped = duplicateViewTabs.size > 1
      
      val invMsg = "[FORENSIC][VIEW_INVARIANT] tabId=$tId view=$viewId session=$sessId attachedOwner=$tId tag=$tag reason=$reason url=$url isMultiMapped=$isMultiMapped multiTabs=$duplicateViewTabs"
      Log.i(TAG, invMsg)
      com.remmi.browser.util.DebugLogManager.log(invMsg)
      
      if (isMultiMapped) {
        val warnMsg = "[FORENSIC][VIEW_INVARIANT_VIOLATION] DUPLICATE_VIEW_MAPPING view=$viewId mappedTo=$duplicateViewTabs"
        Log.e(TAG, warnMsg)
        com.remmi.browser.util.DebugLogManager.log(warnMsg)
      }
      if (view != null && tag != tId) {
        val isUntagged = (view.tag == null)
        val warnMsg = "[FORENSIC][VIEW_INVARIANT_VIOLATION] TAG_MISMATCH tabId=$tId view=$viewId tag=$tag isUntagged=$isUntagged"
        Log.w(TAG, warnMsg)
        com.remmi.browser.util.DebugLogManager.log(warnMsg)
      }
    }
  }

  suspend fun attachView(
    tabId: String,
    geckoView: GeckoView,
    profile: PrivacyProfile,
    isDesktopMode: Boolean,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    callbacks: GeckoTabCallbacks,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("ATTACH_VIEW id=$tabId")

    // Multiple attach triggers are expected around resume/recomposition. Only the first one
    // may cross the session-binding path; the others must wait for the next normal lifecycle event.
    if (!attachingTabs.add(tabId)) {
      val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
      val msg = "[FORENSIC][GECKO_VIEW_ATTACH_SKIPPED] tabId=$tabId view=$gvId reason=attach_in_flight elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, msg)
      com.remmi.browser.util.DebugLogManager.log(msg)
      return@withContext
    }

    try {
      sessionCallbacks[tabId] = callbacks
    
    val existingSession = activeSessions[tabId]
    val existingSessId = existingSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
    val gen = navGenerations[tabId] ?: 0L
    val threadName = Thread.currentThread().name
    val now = android.os.SystemClock.elapsedRealtime()
    val currUrl = lastDispatchedUrls[tabId] ?: "none"
    val startMsg = "[FORENSIC] [GECKO_VIEW_ATTACH_START] tabId=$tabId session=$existingSessId view=$gvId gen=$gen url=$currUrl thread=$threadName elapsedRealtime=$now"
    Log.i(TAG, startMsg)
    com.remmi.browser.util.DebugLogManager.log(startMsg)
    
    checkViewInvariants(tabId, "ATTACH_START")
    
    if (_initState.value == GeckoInitState.NOT_STARTED) {
      Log.d(TAG, "[GECKO] attachView initializing runtime for tabId=$tabId")
      initializeRuntimeAsync()
    }

    if (_initState.value != GeckoInitState.READY) {
      Log.d(TAG, "[GECKO] attachView suspending for runtime readiness on tabId=$tabId")
      _initState.first { it == GeckoInitState.READY || it == GeckoInitState.FAILED }
    }
    
    if (_initState.value == GeckoInitState.FAILED || (runtime == null && uriLoaderForTest == null)) {
      Log.e(TAG, "[GECKO] attachView failed: runtime is not ready")
      return@withContext
    }

    // Clean up any stale mapping for this geckoView from other tabs to ensure no duplicate View mapping
    attachedViews.entries.filter { it.key != tabId && it.value === geckoView }.forEach { entry ->
      attachedViews.remove(entry.key)
      _viewAttachmentStates.getOrPut(entry.key) { MutableStateFlow(false) }.value = false
    }

    val currentAttachedView = attachedViews[tabId]
    if (existingSession != null && currentAttachedView === geckoView && geckoView.session === existingSession) {
      val viewId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
      val skipMsg = "[FORENSIC] [GECKO_VIEW_ATTACH_RESUME] tabId=$tabId session=$existingSessId view=$viewId gen=$gen reason=idempotency_match elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      
      existingSession.setActive(true)
      _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.value = true

      if (!existingSession.isOpen && existingSession !in pendingGeckoOpenSessions) {
        openSessionSafely(existingSession, tabId, "attachView:RESUME_UNOPENED")
      }

      val currentNav = sessionNavStates[tabId]
      if (currentNav != null) {
        callbacks.onNavStateChange(currentNav.first, currentNav.second)
      }
      dispatchPendingNavigationIfReady(tabId)
      resumePendingContentRecoveryIfAny(tabId)
      checkViewInvariants(tabId, "ATTACH_SKIP_IDEMPOTENT")
      return@withContext
    }

    val session = getOrCreateSessionInternal(tabId, profile, securityLevel, containerType, isDesktopMode, openSession = false)
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    try {
      if (geckoView.session != session) {
        geckoView.setSession(session)
      }
      session.setActive(true)
      attachedViews[tabId] = geckoView
      _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.value = true
      viewGenerations[tabId] = (viewGenerations[tabId] ?: 0L) + 1L

      val isNewSessionPending = session in pendingGeckoOpenSessions
      sessionLifecycleStates[session] = SessionLifecycleState.ATTACHED
      logSessionLifecycle(tabId, session, if (isNewSessionPending) "attachView:ATTACH_UNOPENED_PENDING_GECKO" else "attachView:ATTACH_SUCCESS")
      logSessionViewBinding(tabId, session, geckoView, true, "attachView")

      if (!session.isOpen && !isNewSessionPending) {
        openSessionSafely(session, tabId, "attachView:REGULAR_TAB")
      }

      val doneMsg = "[FORENSIC] [GECKO_VIEW_ATTACH_DONE] tabId=$tabId session=$sessId view=$gvId gen=$gen thread=$threadName elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, doneMsg)
      com.remmi.browser.util.DebugLogManager.log(doneMsg)

      checkViewInvariants(tabId, "ATTACH_DONE")
      val currentNav = sessionNavStates[tabId]
      if (currentNav != null) {
        callbacks.onNavStateChange(currentNav.first, currentNav.second)
      }
      dispatchPendingNavigationIfReady(tabId)
      resumePendingContentRecoveryIfAny(tabId)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] attachView session-binding error on tabId=$tabId: ${e.message}")
    }
    // Close the outer attach guard so every exit path releases the in-flight marker.
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] attachView error on tabId=$tabId: ${e.message}")
    } finally {
      attachingTabs.remove(tabId)
    }
  }

  fun detachViewSync(
    tabId: String,
    geckoView: GeckoView? = null,
  ) {
    assertMainThread("DETACH_VIEW_SYNC id=$tabId")
    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gvId = geckoView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gen = navGenerations[tabId] ?: 0L
    val threadName = Thread.currentThread().name
    val now = android.os.SystemClock.elapsedRealtime()
    val currUrl = lastDispatchedUrls[tabId] ?: "none"
    val detachMsg = "[FORENSIC] [GECKO_VIEW_DETACH] tabId=$tabId session=$sessId view=$gvId gen=$gen url=$currUrl thread=$threadName elapsedRealtime=$now"
    Log.i(TAG, detachMsg)
    com.remmi.browser.util.DebugLogManager.log(detachMsg)

    logSessionViewBinding(tabId, session, geckoView, false, "detachViewSync")
    logDestructiveOp("DETACH_VIEW", tabId, session, geckoView, currUrl, "detachViewSync")
    checkPostNavFailure(tabId, "DETACH_VIEW", currUrl)

    attachedViews.remove(tabId)
    _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.value = false

    if (session != null) {
      val currentState = sessionLifecycleStates[session]
      if (currentState != SessionLifecycleState.CLOSED) {
        sessionLifecycleStates[session] = SessionLifecycleState.DETACHED
        logSessionLifecycle(tabId, session, "detachViewSync")
      }
    }

    try {
      geckoView?.releaseSession()
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] releaseSession error: ${e.message}")
    }
    // Tag is only cleared after ownership removal to prevent invariant violations
    geckoView?.tag = null

    onMainSession(tabId, "DETACH_SET_INACTIVE") { sessionObj ->
      try {
        sessionObj.setActive(false)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] setActive(false) notice: ${e.message}")
      }
    }
    checkViewInvariants(tabId, "DETACH_DONE")
  }

  suspend fun detachView(
    tabId: String,
    geckoView: GeckoView? = null,
  ) = withContext(Dispatchers.Main.immediate) {
    detachViewSync(tabId, geckoView)
  }

  suspend fun setTabActive(tabId: String, active: Boolean) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("SET_TAB_ACTIVE id=$tabId active=$active")
    withSession(tabId, "SET_TAB_ACTIVE") { session ->
      try {
        session.setActive(active)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] setActive($active) notice: ${e.message}")
      }
    }
  }

  suspend fun updateTabSettings(
    tabId: String,
    isDesktopMode: Boolean,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("UPDATE_TAB_SETTINGS id=$tabId")
    withSession(tabId, "UPDATE_TAB_SETTINGS") { session ->
      try {
        session.settings.userAgentMode = if (isDesktopMode || profile == PrivacyProfile.GHOST) {
          GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
          GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (isDesktopMode) {
          GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
          GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        session.settings.allowJavascript = securityLevel.javascriptEnabled
        AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] updateTabSettings notice: ${e.message}")
      }
    }
  }

  // --- High-Level Navigation & Session Commands ---

  fun loadUrl(tabId: String, url: String, forceReload: Boolean = false) {
    if (url.isBlank()) return
    val tab = TabManager.getInstance().getTab(tabId)
    val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
    val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
    if (check.decision == com.remmi.browser.security.NavigationDecision.BLOCK) {
      Log.w(TAG, "Blocked navigation to '$url' reason: ${check.reason}")
      return
    }
    val targetUrl = check.sanitizedUrl ?: url
    val host = try { java.net.URI(if (targetUrl.contains("://")) targetUrl else "https://$targetUrl").host ?: "" } catch (_: Exception) { "" }
    if (host.isNotBlank()) {
      applySiteSecurityPolicy(tabId, host)
    }
    
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { loadUrl(tabId, targetUrl, forceReload) }
      return
    }
    assertMainThread("LOAD_URL id=$tabId")
    android.util.Log.i(TAG, "STATE_LOG: FIRST_PAGE_START (time=${android.os.SystemClock.elapsedRealtime()})")
    
    val (navId, gen) = allocateNavigationGeneration(tabId, "loadUrl", targetUrl)
    val now = android.os.SystemClock.elapsedRealtime()
    val navReqMsg = "[FORENSIC] [NAV_REQUESTED] tabId=$tabId navId=$navId url=$targetUrl gen=$gen trigger=loadUrl elapsedRealtime=$now"
    Log.i(TAG, navReqMsg)
    com.remmi.browser.util.DebugLogManager.log(navReqMsg)

    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val threadName = Thread.currentThread().name

    val activeRecovery = activeRecoveries.remove(tabId)
    if (activeRecovery != null) {
      activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, activeRecovery.navId, activeRecovery.generation, "superseded_by_loadUrl")
      val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId session=$sessId url=${activeRecovery.targetUrl} newUrl=$targetUrl gen=${activeRecovery.generation} newGen=$gen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, superMsg)
      com.remmi.browser.util.DebugLogManager.log(superMsg)
    }
    val pendingRec = pendingContentRecoveries.remove(tabId)
    if (pendingRec != null) {
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRec.navId, pendingRec.generation, "superseded_by_loadUrl")
    }

    if (_initState.value == GeckoInitState.NOT_STARTED) {
      Log.d(TAG, "[GECKO] loadUrl requesting init on tabId=$tabId")
      initializeRuntimeAsync()
    }

    val isRuntimeReady = (_initState.value == GeckoInitState.READY && (runtime != null || uriLoaderForTest != null))
    val isAttached = isViewAttached(tabId)
    val torLifecycle = com.remmi.browser.security.TorLifecycleManager.getInstance(context)
    val isTorGateOpen = if (isGhost) torLifecycle.isTorReady.value else true

    if (!isRuntimeReady || !isAttached || !isTorGateOpen) {
      val queueMsg = "[FORENSIC] [GECKO_NAV_QUEUE] tabId=$tabId session=$sessId navId=$navId gen=$gen url=$targetUrl thread=$threadName reason=runtimeReady=$isRuntimeReady,attached=$isAttached,torGateOpen=$isTorGateOpen"
      Log.i(TAG, queueMsg)
      com.remmi.browser.util.DebugLogManager.log(queueMsg)

      // Store latest navigation only (replaces any previous pending navigation for this tab)
      pendingNavigations[tabId] = PendingNavigation(targetUrl, gen, navId)
      if (isGhost && !isTorGateOpen) {
        torLifecycle.queueNavigation(tabId, targetUrl, forceReload)
        TabManager.getInstance().updateTab(tabId) {
          it.copy(
            url = targetUrl,
            isLoading = true,
            progress = torLifecycle.bootstrapProgress.value.coerceAtLeast(15)
          )
        }
      }
      return
    }

    // View is attached and runtime is ready!
    val currentSession = activeSessions[tabId] ?: run {
      val profile = tab?.profile ?: currentProfile
      val secLevel = tab?.securityLevel ?: SecurityLevel.STANDARD
      val container = tab?.containerType ?: ContainerType.fromProfile(profile)
      val isDesktop = tab?.isDesktopMode ?: false
      getOrCreateSessionInternal(tabId, profile, secLevel, container, isDesktop)
    }
    val currentSessId = "0x" + Integer.toHexString(System.identityHashCode(currentSession))
    val currentViewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"

    val isRecoveryActive = activeRecovery != null || pendingContentRecoveries.containsKey(tabId)
    val isActualDuplicate = !forceReload && (lastDispatchedUrls[tabId] == targetUrl || areUrlsEquivalent(lastDispatchedUrls[tabId], targetUrl)) && !isRecoveryActive

    if (isActualDuplicate) {
      logNavDuplicateClassification(
        tabId = tabId,
        navId = navId,
        generation = gen,
        previousNavId = currentNavIds[tabId] ?: 0L,
        previousGeneration = gen,
        classification = "APP_LOAD_URL",
        trigger = "loadUrl",
        reason = "url_already_dispatched"
      )
      logNavAllocationRejected(
        tabId = tabId,
        navId = navId,
        generation = gen,
        url = targetUrl,
        trigger = "loadUrl",
        reason = "url_already_dispatched"
      )
      val skipMsg = "[FORENSIC] [GECKO_NAV_SKIPPED_DUPLICATE] tabId=$tabId session=$currentSessId view=$currentViewId navId=$navId gen=$gen url=$targetUrl thread=$threadName"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      return
    }

    lastDispatchedUrls[tabId] = targetUrl
    pendingNavigations.remove(tabId)
    dispatchedNavigationsHistory.getOrPut(tabId) { mutableListOf() }.add(targetUrl)
    val dispatchMsg = "[FORENSIC] [GECKO_NAV_DISPATCH] tabId=$tabId session=$currentSessId view=$currentViewId navId=$navId gen=$gen url=$targetUrl thread=$threadName"
    Log.i(TAG, dispatchMsg)
    com.remmi.browser.util.DebugLogManager.log(dispatchMsg)

    try {
      if (!currentSession.isOpen && currentSession !in pendingGeckoOpenSessions) {
        openSessionSafely(currentSession, tabId, "loadUrl")
      }

      val attachedView = attachedViews[tabId]
      if (attachedView != null) {
        try {
          if (attachedView.session !== currentSession) {
            attachedView.setSession(currentSession)
          }
          currentSession.setActive(true)
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] Failed to re-link session to GeckoView on loadUrl: ${e.message}")
        }
      }

      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, currentSession, targetUrl)
      } else {
        currentSession.loadUri(targetUrl)
      }
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] loadUrl error on tabId=$tabId: ${e.message}")
    }
  }

  fun reload(tabId: String) {
    val targetUrl = lastDispatchedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: lastObservedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: TabManager.getInstance().getTab(tabId)?.url?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }

    val pendingRec = pendingContentRecoveries.remove(tabId)
    if (pendingRec != null) {
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRec.navId, pendingRec.generation, "superseded_by_reload")
    }
    val activeRecovery = activeRecoveries.remove(tabId)
    if (activeRecovery != null) {
      activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, activeRecovery.navId, activeRecovery.generation, "superseded_by_reload")
      val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId url=${activeRecovery.targetUrl} reason=reload elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, superMsg)
      com.remmi.browser.util.DebugLogManager.log(superMsg)
    }

    lastRecoveredGenerations.remove(tabId)

    if (!targetUrl.isNullOrBlank()) {
      loadUrl(tabId, targetUrl, forceReload = true)
    } else {
      onMainSession(tabId, "RELOAD") { session ->
        val url = lastDispatchedUrls[tabId] ?: "reload"
        allocateNavigationGeneration(tabId, "reload", url)
        session.reload()
      }
    }
  }

  fun resetToNewTab(tabId: String) {
    onMainSession(tabId, "RESET_TO_NEW_TAB") { session ->
      Log.i(TAG, "[FORENSIC] NAV_NEW_TAB_TRANSITION tabId=$tabId")
      logDestructiveOp("RESET_TO_NEW_TAB", tabId, session, null, null, "resetToNewTab")
      checkPostNavFailure(tabId, "RESET_TO_NEW_TAB")
      lastDispatchedUrls[tabId] = "about:blank"
      inFlightNavigations.remove(tabId)
      val pendingRec = pendingContentRecoveries.remove(tabId)
      if (pendingRec != null) {
        transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRec.navId, pendingRec.generation, "superseded_by_reset_to_new_tab")
      }
      val activeRecovery = activeRecoveries.remove(tabId)
      if (activeRecovery != null) {
        activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, activeRecovery.navId, activeRecovery.generation, "superseded_by_reset_to_new_tab")
        val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId url=${activeRecovery.targetUrl} reason=reset_to_new_tab elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, superMsg)
        com.remmi.browser.util.DebugLogManager.log(superMsg)
      }
      session.load(GeckoSession.Loader().uri("about:blank").flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
    }
  }

  fun stopLoading(tabId: String) {
    onMainSession(tabId, "STOP_LOADING") { session ->
      logDestructiveOp("STOP_LOADING", tabId, session, null, null, "stopLoading")
      checkPostNavFailure(tabId, "STOP_LOADING")
      session.stop()
    }
  }

  fun stop(tabId: String) {
    stopLoading(tabId)
  }

  fun goBack(tabId: String) {
    onMainSession(tabId, "GO_BACK") { session ->
      allocateNavigationGeneration(tabId, "goBack", "history_back")
      Log.i(TAG, "[FORENSIC] NAV_BACK_GECKO tabId=$tabId sessionHash=${session.hashCode()} thread=${Thread.currentThread().name}")
      session.goBack()
    }
  }

  fun goForward(tabId: String) {
    onMainSession(tabId, "GO_FORWARD") { session ->
      allocateNavigationGeneration(tabId, "goForward", "history_forward")
      Log.i(TAG, "[FORENSIC] NAV_FORWARD_GECKO tabId=$tabId sessionHash=${session.hashCode()} thread=${Thread.currentThread().name}")
      session.goForward()
    }
  }

  fun canGoBack(tabId: String): Boolean {
    return sessionNavStates[tabId]?.first ?: false
  }

  fun canGoForward(tabId: String): Boolean {
    return sessionNavStates[tabId]?.second ?: false
  }

  fun getNavState(tabId: String): Pair<Boolean, Boolean> {
    return sessionNavStates[tabId] ?: Pair(false, false)
  }

  fun findInPage(tabId: String, query: String, backwards: Boolean = false) {
    onMainSession(tabId, "FIND_IN_PAGE") { session ->
      val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
      session.finder.find(query, flags)
    }
  }

  fun clearFindInPage(tabId: String) {
    onMainSession(tabId, "CLEAR_FIND_IN_PAGE") { session ->
      session.finder.clear()
    }
  }

  fun executeScript(tabId: String, script: String) {
    com.remmi.adblock.BlockExtension.getInstance().executeScript(tabId, script)
  }

  fun printPage(activityContext: Context, tabId: String, pageTitle: String, onFinished: (() -> Unit)? = null) {
    onMainSession(tabId, "PRINT_PAGE") { session ->
      try {
        PdfPrintHelper.printPage(activityContext, { session.saveAsPdf() }, pageTitle, onFinished)
      } catch (e: Exception) {
        Log.e(TAG, "printPage error: ${e.message}", e)
        Toast.makeText(activityContext, "Print error: ${e.message}", Toast.LENGTH_SHORT).show()
        onFinished?.invoke()
      }
    }
  }

  fun exportPageAsPdf(
    tabId: String,
    pageTitle: String,
    onFinished: ((File?) -> Unit)? = null,
  ) {
    onMainSession(tabId, "EXPORT_PAGE_AS_PDF") { session ->
      try {
        PdfPrintHelper.exportPageAsPdf(context, { session.saveAsPdf() }, pageTitle, onFinished)
      } catch (e: Exception) {
        Log.e(TAG, "exportPageAsPdf error: ${e.message}", e)
        onFinished?.invoke(null)
      }
    }
  }

  suspend fun <T> executeOnSession(tabId: String, block: (GeckoSession) -> T): T? = withContext(Dispatchers.Main.immediate) {
    assertMainThread("EXECUTE_ON_SESSION id=$tabId")
    val session = activeSessions[tabId] ?: return@withContext null
    try {
      block(session)
    } catch (e: Exception) {
      Log.e(TAG, "executeOnSession error on tabId=$tabId: ${e.message}", e)
      null
    }
  }

  suspend fun closeSessionSafely(tabId: String): CloseResult = withContext(Dispatchers.Main.immediate) {
    assertMainThread("CLOSE_SESSION_SAFELY id=$tabId")
    sessionCallbacks.remove(tabId)
    sessionNavStates.remove(tabId)
    attachedViews.remove(tabId)
    val pooledGv = geckoViewPool.remove(tabId)
    if (pooledGv != null) {
      (pooledGv.parent as? android.view.ViewGroup)?.removeView(pooledGv)
      try {
        pooledGv.releaseSession()
      } catch (_: Exception) {}
    }
    _viewAttachmentStates.remove(tabId)
    pendingNavigations.remove(tabId)
    pendingContentRecoveries.remove(tabId)
    val activeRecovery = activeRecoveries.remove(tabId)
    activeRecovery?.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    lastDispatchedUrls.remove(tabId)
    lastObservedUrls.remove(tabId)
    latestProgressUrls.remove(tabId)
    latestLocationUrls.remove(tabId)
    dispatchedNavigationsHistory.remove(tabId)
    currentScrollPositions.remove(tabId)
    navGenerations.remove(tabId)
    lastRecoveredGenerations.remove(tabId)
    val session = activeSessions.remove(tabId)
    if (session == null) {
      Log.d(TAG, "[GECKO] operation=CLOSE_NOT_FOUND id=$tabId thread=main")
      return@withContext CloseResult.NotFound
    }
    sessionLifecycleStates[session] = SessionLifecycleState.CLOSED
    sessionOwners.remove(session)
    pendingGeckoOpenSessions.remove(session)
    logSessionLifecycle(tabId, session, "closeSessionSafely")
    logDestructiveOp("CLOSE_SESSION", tabId, session, null, null, "closeSessionSafely")
    checkPostNavFailure(tabId, "CLOSE_SESSION")
    try {
      // Null out delegates to eliminate trailing asynchronous callbacks
      session.navigationDelegate = null
      session.progressDelegate = null
      session.contentDelegate = null
      session.permissionDelegate = null
      session.promptDelegate = null
      com.remmi.browser.security.permissions.PermissionSessionManager.getInstance().onTabClosed(tabId)
      if (session.isOpen) {
        session.close()
      }
      Log.d(TAG, "[GECKO] operation=CLOSE_COMPLETED id=$tabId thread=main")
      CloseResult.Success
    } catch (t: Throwable) {
      Log.w(TAG, "[GECKO] operation=CLOSE_NOTICE id=$tabId thread=main error=${t.message}")
      CloseResult.Success // Soft-success since resources and map entry are detached
    }
  }

  suspend fun closeAllSessionsSafely() = withContext(Dispatchers.Main.immediate) {
    assertMainThread("CLOSE_ALL")
    sessionCallbacks.clear()
    sessionNavStates.clear()
    attachedViews.clear()
    geckoViewPool.values.forEach { pooledGv ->
      (pooledGv.parent as? android.view.ViewGroup)?.removeView(pooledGv)
      try {
        pooledGv.releaseSession()
      } catch (_: Exception) {}
    }
    geckoViewPool.clear()
    _viewAttachmentStates.clear()
    pendingNavigations.clear()
    pendingContentRecoveries.clear()
    activeRecoveries.values.forEach { it.timeoutRunnable?.let { r -> mainHandler.removeCallbacks(r) } }
    activeRecoveries.clear()
    lastDispatchedUrls.clear()
    lastObservedUrls.clear()
    latestProgressUrls.clear()
    latestLocationUrls.clear()
    dispatchedNavigationsHistory.clear()
    navGenerations.clear()
    lastRecoveredGenerations.clear()
    val sessionsToClose = activeSessions.values.toList()
    activeSessions.clear()
    sessionOwners.clear()
    pendingGeckoOpenSessions.clear()
    sessionsToClose.forEach { session ->
      sessionLifecycleStates[session] = SessionLifecycleState.CLOSED
      logSessionLifecycle("ALL", session, "closeAllSessionsSafely")
      try {
        session.navigationDelegate = null
        session.progressDelegate = null
        session.contentDelegate = null
        if (session.isOpen) {
          session.close()
        }
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] operation=CLOSE_ALL_NOTICE: ${e.message}")
      }
    }
    Log.d(TAG, "[GECKO] operation=CLOSE_ALL_COMPLETED count=${sessionsToClose.size} thread=main")
  }

  /**
   * Complete, atomic destruction of all browser tabs, sessions, delegates, and view bindings.
   * Single source of truth for halting browsing activity during Panic Wipe or full reset.
   */
  suspend fun destroyAllBrowserState(): Boolean = withContext(Dispatchers.Main.immediate) {
    assertMainThread("DESTROY_ALL_BROWSER_STATE")
    try {
      // 1. Clear callbacks and delegates
      sessionCallbacks.clear()
      sessionNavStates.clear()
      attachedViews.clear()
      _viewAttachmentStates.clear()
      pendingNavigations.clear()
      pendingContentRecoveries.clear()
      activeRecoveries.values.forEach { it.timeoutRunnable?.let { r -> mainHandler.removeCallbacks(r) } }
      activeRecoveries.clear()
      lastDispatchedUrls.clear()
      lastObservedUrls.clear()
      latestProgressUrls.clear()
      latestLocationUrls.clear()
      dispatchedNavigationsHistory.clear()
      navGenerations.clear()
      lastRecoveredGenerations.clear()

      // 2. Stop all running sessions and close
      val sessions = activeSessions.values.toList()
      activeSessions.clear()
      sessionOwners.clear()
      pendingGeckoOpenSessions.clear()
      var allSessionsStopped = true
      var allSessionsClosed = true
      sessions.forEach { session ->
        sessionLifecycleStates[session] = SessionLifecycleState.CLOSED
        logSessionLifecycle("ALL", session, "destroyAllBrowserState")
        try {
          session.stop()
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] destroyAllBrowserState session stop notice: ${e.message}")
          allSessionsStopped = false
        }
        try {
          session.navigationDelegate = null
          session.progressDelegate = null
          session.contentDelegate = null
          session.setActive(false)
          if (session.isOpen) {
            session.close()
          }
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] destroyAllBrowserState session close notice: ${e.message}")
          allSessionsClosed = false
        }
      }

      // 3. Notify TabManager to purge tab list
      var tabsCleared = true
      try {
        TabManager.getInstance().closeAllTabs()
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] destroyAllBrowserState tab close notice: ${e.message}")
        tabsCleared = false
      }

      // Reset preference cache and cancel pending engine scope background jobs
      runtime?.let { GeckoPreferenceController.resetCache(it) }
      GeckoPreferenceController.resetCache()
      engineSupervisorJob.children.forEach { it.cancel() }

      val success = allSessionsStopped && allSessionsClosed && tabsCleared
      Log.i(TAG, "[GECKO] All browser state destroyed (success=$success, stopped=$allSessionsStopped, closed=$allSessionsClosed, tabs=$tabsCleared, ${sessions.size} sessions closed).")
      success
    } catch (e: Exception) {
      Log.e(TAG, "[GECKO] destroyAllBrowserState encountered error: ${e.message}", e)
      false
    }
  }

  suspend fun clearCookiesAndCacheSafely(): Boolean = withContext(Dispatchers.Main.immediate) {
    closeAllSessionsSafely()
    val rt = runtime ?: return@withContext true
    try {
      suspendCancellableCoroutine { continuation ->
        val geckoResult = rt.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
        geckoResult.accept(
          { continuation.resume(true) },
          { err ->
            Log.w(TAG, "Gecko clearData returned error: ${err?.message}")
            continuation.resume(false)
          }
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error clearing Gecko storage data: ${e.message}")
      false
    }
  }

  fun onTrimMemory(level: Int) {
    Log.d(TAG, "onTrimMemory level=$level")
    mainHandler.post {
      // In critical or complete memory trimming, prune idle / non-attached pooled views
      if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
          level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
          level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
        val attachedTabIds = attachedViews.keys.toSet()
        val pooledEntries = geckoViewPool.entries.toList()
        for ((tabId, gv) in pooledEntries) {
          if (!attachedTabIds.contains(tabId) && gv.parent == null) {
            geckoViewPool.remove(tabId)
            try {
              gv.releaseSession()
            } catch (_: Exception) {}
            Log.d(TAG, "[MEMORY_TRIM] Released idle pooled GeckoView for tabId=$tabId")
          }
        }
      }
    }
  }

  fun onLowMemory() {
    Log.d(TAG, "onLowMemory")
    onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
  }

  companion object {
    private const val TAG = "GeckoEngineManager"

    @Volatile
    private var INSTANCE: GeckoEngineManager? = null

    fun peekInitState(): String? {
      return INSTANCE?._initState?.value?.name
    }

    fun getInstance(context: Context): GeckoEngineManager {
      return INSTANCE ?: synchronized(this) {
        if (INSTANCE != null) {
          INSTANCE!!
        } else {
          com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(context, com.remmi.browser.util.StartupPhase.GECKO_MANAGER_CONSTRUCT_START)
          val mgr = GeckoEngineManager(context.applicationContext).also { INSTANCE = it }
          com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(context, com.remmi.browser.util.StartupPhase.GECKO_MANAGER_CONSTRUCT_END)
          mgr
        }
      }
    }
  }
}
