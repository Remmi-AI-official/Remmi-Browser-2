package com.remmi.browser.security.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Interface allowing PermissionSessionManager to request Android runtime permissions via Activity.
 */
interface AndroidPermissionRequester {
  fun requestAndroidPermissions(
    permissions: Array<String>,
    onResult: (Map<String, Boolean>) -> Unit
  )
}

/**
 * Holds in-memory session-scoped permissions and active media state for an individual tab.
 */
data class TabPermissionSession(
  val tabId: String,
  val sessionId: String = UUID.randomUUID().toString(),
  var origin: String,
  var cameraAllowed: Boolean = false,
  var microphoneAllowed: Boolean = false,
  var geolocationAllowed: Boolean = false,
  var activeMediaCallback: GeckoSession.PermissionDelegate.MediaCallback? = null,
  var activeVideoSource: GeckoSession.PermissionDelegate.MediaSource? = null,
  var activeAudioSource: GeckoSession.PermissionDelegate.MediaSource? = null,
  var isCameraActive: Boolean = false,
  var isMicrophoneActive: Boolean = false,
  var isGeolocationActive: Boolean = false,
  val createdAt: Long = SystemClock.elapsedRealtime()
)

data class PendingPermissionRequest(
  val requestId: String = UUID.randomUUID().toString(),
  val tabId: String,
  val origin: String,
  val capability: PermissionType,
  val isGhost: Boolean,
  val createdAt: Long = SystemClock.elapsedRealtime(),
  val executePrompt: () -> Unit
)

class PermissionSessionManager private constructor() {

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  // Tab-isolated sessions
  private val tabSessions = ConcurrentHashMap<String, TabPermissionSession>()

  // Prompt queue per tab to prevent permission spam/storms
  private val tabPromptQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingPermissionRequest>>()
  private val currentPromptingTab = ConcurrentHashMap<String, PendingPermissionRequest>()

  // Active UI prompt state for Jetpack Compose
  private val _activePrompt = MutableStateFlow<PermissionPromptRequest?>(null)
  val activePrompt = _activePrompt.asStateFlow()

  // Active indicator state per tab
  private val _activeIndicators = MutableStateFlow<Map<String, ActivePermissionState>>(emptyMap())
  val activeIndicators = _activeIndicators.asStateFlow()

  var androidPermissionRequester: AndroidPermissionRequester? = null

  // =========================================================================
  // MEDIA PERMISSION REQUEST (Camera / Mic)
  // =========================================================================

  fun handleMediaPermissionRequest(
    session: GeckoSession,
    tabId: String,
    uri: String,
    video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
    audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
    callback: GeckoSession.PermissionDelegate.MediaCallback,
    isGhost: Boolean
  ) {
    val canonicalOrigin = OriginCanonicalizer.canonicalize(uri)
    val originHash = OriginCanonicalizer.getOriginHash(canonicalOrigin)

    logSecurity("REQUEST", "type=MEDIA originHash=$originHash tabId=$tabId videoCount=${video?.size ?: 0} audioCount=${audio?.size ?: 0}")

    if (canonicalOrigin == null) {
      logSecurity("ORIGIN_CHECK", "decision=DENY reason=INVALID_OR_INSECURE_ORIGIN originHash=$originHash tabId=$tabId")
      callback.reject()
      return
    }

    val hasCamera = !video.isNullOrEmpty()
    val hasMic = !audio.isNullOrEmpty()

    if (!hasCamera && !hasMic) {
      logSecurity("CONTENT_DENY", "reason=NO_MEDIA_SOURCES originHash=$originHash tabId=$tabId")
      callback.reject()
      return
    }

    val capability = when {
      hasCamera && hasMic -> PermissionType.CAMERA_AND_MICROPHONE
      hasCamera -> PermissionType.CAMERA
      else -> PermissionType.MICROPHONE
    }

    val tabSession = getOrCreateSession(tabId, canonicalOrigin)

    // Check if exact-origin is already allowed in this active session
    val isCamSatisfied = !hasCamera || tabSession.cameraAllowed
    val isMicSatisfied = !hasMic || tabSession.microphoneAllowed

    if (isCamSatisfied && isMicSatisfied && OriginCanonicalizer.isSameExactOrigin(tabSession.origin, canonicalOrigin)) {
      logSecurity("CONTENT_GRANT", "type=$capability sessionReused=true originHash=$originHash tabId=$tabId")
      grantMediaSources(tabSession, video, audio, callback)
      return
    }

    // Queue request to prevent dialog spam
    val requestId = UUID.randomUUID().toString()
    val pendingReq = PendingPermissionRequest(
      requestId = requestId,
      tabId = tabId,
      origin = canonicalOrigin,
      capability = capability,
      isGhost = isGhost
    ) {
      val promptRequest = PermissionPromptRequest(
        requestId = requestId,
        tabId = tabId,
        origin = canonicalOrigin,
        displayHost = OriginCanonicalizer.getDisplayHost(canonicalOrigin),
        permissionType = capability,
        isGhost = isGhost,
        onDecision = { decision ->
          _activePrompt.value = null
          currentPromptingTab.remove(tabId)

          when (decision) {
            PermissionDecision.ALLOW_ONCE,
            PermissionDecision.ALLOW_SESSION -> {
              val rememberForSession = (decision == PermissionDecision.ALLOW_SESSION) && !isGhost
              requestAndroidPermissionsForMedia(
                tabId = tabId,
                origin = canonicalOrigin,
                hasCamera = hasCamera,
                hasMic = hasMic
              ) { androidGranted ->
                if (androidGranted) {
                  logSecurity("CONTENT_GRANT", "type=$capability sessionScoped=$rememberForSession originHash=$originHash tabId=$tabId")
                  val currentTabSession = getOrCreateSession(tabId, canonicalOrigin)
                  if (rememberForSession) {
                    if (hasCamera) currentTabSession.cameraAllowed = true
                    if (hasMic) currentTabSession.microphoneAllowed = true
                  }
                  grantMediaSources(currentTabSession, video, audio, callback)
                } else {
                  logSecurity("CONTENT_DENY", "reason=ANDROID_PERMISSION_DENIED originHash=$originHash tabId=$tabId")
                  callback.reject()
                }
                processNextPrompt(tabId)
              }
            }
            PermissionDecision.DENY -> {
              logSecurity("CONTENT_DENY", "reason=USER_DENIED originHash=$originHash tabId=$tabId")
              callback.reject()
              processNextPrompt(tabId)
            }
          }
        }
      )
      _activePrompt.value = promptRequest
    }

    enqueuePrompt(tabId, pendingReq)
  }

  private fun grantMediaSources(
    session: TabPermissionSession,
    video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
    audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
    callback: GeckoSession.PermissionDelegate.MediaCallback
  ) {
    val selectedVideo = video?.firstOrNull()
    val selectedAudio = audio?.firstOrNull()

    session.activeMediaCallback = callback
    session.activeVideoSource = selectedVideo
    session.activeAudioSource = selectedAudio
    session.isCameraActive = selectedVideo != null
    session.isMicrophoneActive = selectedAudio != null

    if (selectedVideo != null) {
      callback.grant(selectedVideo, selectedAudio)
    } else if (selectedAudio != null) {
      callback.grant(null, selectedAudio)
    }

    updateIndicator(session.tabId)
    val originHash = OriginCanonicalizer.getOriginHash(session.origin)
    logSecurity("MEDIA_START", "camera=${session.isCameraActive} microphone=${session.isMicrophoneActive} originHash=$originHash tabId=${session.tabId}")
  }

  private fun requestAndroidPermissionsForMedia(
    tabId: String,
    origin: String,
    hasCamera: Boolean,
    hasMic: Boolean,
    onResult: (Boolean) -> Unit
  ) {
    val requiredPerms = mutableListOf<String>()
    if (hasCamera) requiredPerms.add(Manifest.permission.CAMERA)
    if (hasMic) requiredPerms.add(Manifest.permission.RECORD_AUDIO)

    val requester = androidPermissionRequester
    if (requester == null) {
      logSecurity("ANDROID_REQUEST", "error=NO_REQUESTER_ATTACHED originHash=${OriginCanonicalizer.getOriginHash(origin)} tabId=$tabId")
      onResult(false)
      return
    }

    logSecurity("ANDROID_REQUEST", "permissions=${requiredPerms.joinToString(",")} originHash=${OriginCanonicalizer.getOriginHash(origin)} tabId=$tabId")
    requester.requestAndroidPermissions(requiredPerms.toTypedArray()) { results ->
      val allGranted = requiredPerms.all { results[it] == true }
      onResult(allGranted)
    }
  }

  // =========================================================================
  // CONTENT PERMISSION REQUEST (Geolocation, Notifications, etc.)
  // =========================================================================

  fun handleContentPermissionRequest(
    session: GeckoSession,
    tabId: String,
    perm: GeckoSession.PermissionDelegate.ContentPermission,
    isGhost: Boolean
  ): GeckoResult<Int>? {
    val result = GeckoResult<Int>()
    val canonicalOrigin = OriginCanonicalizer.canonicalize(perm.uri)
    val originHash = OriginCanonicalizer.getOriginHash(canonicalOrigin)

    val permName = when (perm.permission) {
      GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "GEOLOCATION"
      GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "NOTIFICATION"
      GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "STORAGE"
      GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "MEDIA_KEY_SYSTEM_ACCESS"
      GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "STORAGE_ACCESS"
      else -> "UNKNOWN_${perm.permission}"
    }

    logSecurity("REQUEST", "type=$permName originHash=$originHash tabId=$tabId")

    // Auto-grant Media Key & Storage Access Permissions for media playback & streaming
    if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ||
        perm.permission == GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS) {
      logSecurity("CONTENT_GRANT", "type=$permName autoGrant=true originHash=$originHash tabId=$tabId")
      result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
      return result
    }

    if (canonicalOrigin == null) {
      logSecurity("ORIGIN_CHECK", "decision=DENY reason=INVALID_OR_INSECURE_ORIGIN originHash=$originHash tabId=$tabId")
      result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
      return result
    }

    if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
      // Deny background/persistent web permissions by default (least privilege)
      result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
      return result
    }

    val tabSession = getOrCreateSession(tabId, canonicalOrigin)
    if (tabSession.geolocationAllowed && OriginCanonicalizer.isSameExactOrigin(tabSession.origin, canonicalOrigin)) {
      logSecurity("CONTENT_GRANT", "type=GEOLOCATION sessionReused=true originHash=$originHash tabId=$tabId")
      tabSession.isGeolocationActive = true
      updateIndicator(tabId)
      logSecurity("LOCATION_START", "originHash=$originHash tabId=$tabId")
      result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
      return result
    }

    val requestId = UUID.randomUUID().toString()
    val pendingReq = PendingPermissionRequest(
      requestId = requestId,
      tabId = tabId,
      origin = canonicalOrigin,
      capability = PermissionType.GEOLOCATION,
      isGhost = isGhost
    ) {
      val promptRequest = PermissionPromptRequest(
        requestId = requestId,
        tabId = tabId,
        origin = canonicalOrigin,
        displayHost = OriginCanonicalizer.getDisplayHost(canonicalOrigin),
        permissionType = PermissionType.GEOLOCATION,
        isGhost = isGhost,
        onDecision = { decision ->
          _activePrompt.value = null
          currentPromptingTab.remove(tabId)

          when (decision) {
            PermissionDecision.ALLOW_ONCE,
            PermissionDecision.ALLOW_SESSION -> {
              val rememberForSession = (decision == PermissionDecision.ALLOW_SESSION) && !isGhost
              requestAndroidPermissionsForLocation(tabId, canonicalOrigin) { androidGranted ->
                if (androidGranted) {
                  logSecurity("CONTENT_GRANT", "type=GEOLOCATION sessionScoped=$rememberForSession originHash=$originHash tabId=$tabId")
                  val currentTabSession = getOrCreateSession(tabId, canonicalOrigin)
                  if (rememberForSession) {
                    currentTabSession.geolocationAllowed = true
                  }
                  currentTabSession.isGeolocationActive = true
                  updateIndicator(tabId)
                  logSecurity("LOCATION_START", "originHash=$originHash tabId=$tabId")
                  result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                } else {
                  logSecurity("CONTENT_DENY", "reason=ANDROID_PERMISSION_DENIED originHash=$originHash tabId=$tabId")
                  result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
                processNextPrompt(tabId)
              }
            }
            PermissionDecision.DENY -> {
              logSecurity("CONTENT_DENY", "reason=USER_DENIED originHash=$originHash tabId=$tabId")
              result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
              processNextPrompt(tabId)
            }
          }
        }
      )
      _activePrompt.value = promptRequest
    }

    enqueuePrompt(tabId, pendingReq)
    return result
  }

  private fun requestAndroidPermissionsForLocation(
    tabId: String,
    origin: String,
    onResult: (Boolean) -> Unit
  ) {
    val requiredPerms = arrayOf(
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.ACCESS_FINE_LOCATION
    )

    val requester = androidPermissionRequester
    if (requester == null) {
      logSecurity("ANDROID_REQUEST", "error=NO_REQUESTER_ATTACHED originHash=${OriginCanonicalizer.getOriginHash(origin)} tabId=$tabId")
      onResult(false)
      return
    }

    logSecurity("ANDROID_REQUEST", "permissions=LOCATION originHash=${OriginCanonicalizer.getOriginHash(origin)} tabId=$tabId")
    requester.requestAndroidPermissions(requiredPerms) { results ->
      val granted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    results[Manifest.permission.ACCESS_FINE_LOCATION] == true
      onResult(granted)
    }
  }

  // =========================================================================
  // QUEUING & PROMPT SPAM PROTECTION
  // =========================================================================

  private fun enqueuePrompt(tabId: String, request: PendingPermissionRequest) {
    val queue = tabPromptQueues.getOrPut(tabId) { ConcurrentLinkedQueue() }
    queue.offer(request)

    if (!currentPromptingTab.containsKey(tabId)) {
      processNextPrompt(tabId)
    }
  }

  private fun processNextPrompt(tabId: String) {
    val queue = tabPromptQueues[tabId] ?: return
    val nextRequest = queue.poll()
    if (nextRequest != null) {
      // Verify request is not stale (30-second TTL)
      val age = SystemClock.elapsedRealtime() - nextRequest.createdAt
      if (age > 30000) {
        logSecurity("SESSION_EXPIRE", "reason=PROMPT_TIMEOUT tabId=$tabId requestId=${nextRequest.requestId}")
        processNextPrompt(tabId)
        return
      }

      currentPromptingTab[tabId] = nextRequest
      scope.launch {
        nextRequest.executePrompt()
      }
    } else {
      currentPromptingTab.remove(tabId)
      if (_activePrompt.value?.tabId == tabId) {
        _activePrompt.value = null
      }
    }
  }

  // =========================================================================
  // TAB LIFECYCLE & CLEANUP HOOKS
  // =========================================================================

  /**
   * Called when a tab navigates to a new URL.
   * Invalidates temporary/session capabilities if cross-origin navigation occurred.
   */
  fun onTabNavigated(tabId: String, newUrl: String?) {
    val newCanonicalOrigin = OriginCanonicalizer.canonicalize(newUrl)
    val session = tabSessions[tabId] ?: return

    if (newCanonicalOrigin == null || !OriginCanonicalizer.isSameExactOrigin(session.origin, newCanonicalOrigin)) {
      val originHash = OriginCanonicalizer.getOriginHash(session.origin)
      logSecurity("SESSION_EXPIRE", "reason=CROSS_ORIGIN_NAVIGATION oldOriginHash=$originHash newOriginHash=${OriginCanonicalizer.getOriginHash(newCanonicalOrigin)} tabId=$tabId")
      stopMediaAndLocation(tabId, "CROSS_ORIGIN_NAVIGATION")
      tabSessions.remove(tabId)
      clearTabPromptQueue(tabId)
    }
  }

  /**
   * Called when a tab is closed or destroyed.
   * Immediately stops all camera/mic/location resources and frees memory.
   */
  fun onTabClosed(tabId: String) {
    logSecurity("SESSION_EXPIRE", "reason=TAB_CLOSED tabId=$tabId")
    stopMediaAndLocation(tabId, "TAB_CLOSED")
    tabSessions.remove(tabId)
    clearTabPromptQueue(tabId)
    updateIndicator(tabId)
  }

  /**
   * Called when the app moves to the background.
   * Enforces immediate termination of active media capture and temporary location.
   */
  fun onAppBackgrounded() {
    logSecurity("SESSION_EXPIRE", "reason=APP_BACKGROUNDED")
    tabSessions.keys.forEach { tabId ->
      stopMediaAndLocation(tabId, "APP_BACKGROUNDED")
    }
  }

  /**
   * Explicitly revoke permission and stop active resources for a tab (User taps "Stop Access").
   */
  fun revokePermission(tabId: String, capability: PermissionType) {
    val session = tabSessions[tabId] ?: return
    val originHash = OriginCanonicalizer.getOriginHash(session.origin)
    logSecurity("SESSION_EXPIRE", "reason=USER_REVOKED capability=$capability originHash=$originHash tabId=$tabId")

    when (capability) {
      PermissionType.CAMERA -> {
        session.cameraAllowed = false
        session.isCameraActive = false
        session.activeMediaCallback?.reject()
        session.activeMediaCallback = null
        logSecurity("MEDIA_STOP", "camera=true reason=USER_REVOKED originHash=$originHash tabId=$tabId")
      }
      PermissionType.MICROPHONE -> {
        session.microphoneAllowed = false
        session.isMicrophoneActive = false
        session.activeMediaCallback?.reject()
        session.activeMediaCallback = null
        logSecurity("MEDIA_STOP", "microphone=true reason=USER_REVOKED originHash=$originHash tabId=$tabId")
      }
      PermissionType.CAMERA_AND_MICROPHONE -> {
        session.cameraAllowed = false
        session.microphoneAllowed = false
        session.isCameraActive = false
        session.isMicrophoneActive = false
        session.activeMediaCallback?.reject()
        session.activeMediaCallback = null
        logSecurity("MEDIA_STOP", "camera=true microphone=true reason=USER_REVOKED originHash=$originHash tabId=$tabId")
      }
      PermissionType.GEOLOCATION -> {
        session.geolocationAllowed = false
        session.isGeolocationActive = false
        logSecurity("LOCATION_STOP", "reason=USER_REVOKED originHash=$originHash tabId=$tabId")
      }
      PermissionType.FILE_UPLOAD -> {}
    }

    updateIndicator(tabId)
  }

  private fun stopMediaAndLocation(tabId: String, reason: String) {
    val session = tabSessions[tabId] ?: return
    val originHash = OriginCanonicalizer.getOriginHash(session.origin)

    if (session.isCameraActive || session.isMicrophoneActive) {
      try {
        session.activeMediaCallback?.reject()
      } catch (_: Exception) {}
      session.activeMediaCallback = null
      session.isCameraActive = false
      session.isMicrophoneActive = false
      logSecurity("MEDIA_STOP", "camera=true microphone=true reason=$reason originHash=$originHash tabId=$tabId")
    }

    if (session.isGeolocationActive) {
      session.isGeolocationActive = false
      logSecurity("LOCATION_STOP", "reason=$reason originHash=$originHash tabId=$tabId")
    }

    updateIndicator(tabId)
  }

  private fun clearTabPromptQueue(tabId: String) {
    tabPromptQueues.remove(tabId)
    currentPromptingTab.remove(tabId)
    if (_activePrompt.value?.tabId == tabId) {
      _activePrompt.value = null
    }
  }

  private fun getOrCreateSession(tabId: String, canonicalOrigin: String): TabPermissionSession {
    val existing = tabSessions[tabId]
    return if (existing != null && OriginCanonicalizer.isSameExactOrigin(existing.origin, canonicalOrigin)) {
      existing
    } else {
      val fresh = TabPermissionSession(tabId = tabId, origin = canonicalOrigin)
      tabSessions[tabId] = fresh
      fresh
    }
  }

  private fun updateIndicator(tabId: String) {
    val session = tabSessions[tabId]
    val currentMap = _activeIndicators.value.toMutableMap()
    if (session != null && (session.isCameraActive || session.isMicrophoneActive || session.isGeolocationActive)) {
      currentMap[tabId] = ActivePermissionState(
        tabId = tabId,
        origin = session.origin,
        isCameraActive = session.isCameraActive,
        isMicrophoneActive = session.isMicrophoneActive,
        isGeolocationActive = session.isGeolocationActive
      )
    } else {
      currentMap.remove(tabId)
    }
    _activeIndicators.value = currentMap
  }

  private fun logSecurity(event: String, details: String) {
    val msg = "[PERMISSION][$event] $details timestamp=${System.currentTimeMillis()}"
    Log.i(TAG, msg)
    DebugLogManager.log(msg)
  }

  companion object {
    private const val TAG = "PermissionSessionMgr"

    @Volatile
    private var INSTANCE: PermissionSessionManager? = null

    fun getInstance(): PermissionSessionManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: PermissionSessionManager().also { INSTANCE = it }
      }
    }
  }
}
