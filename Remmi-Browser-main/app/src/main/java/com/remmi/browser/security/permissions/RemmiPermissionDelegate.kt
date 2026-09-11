package com.remmi.browser.security.permissions

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class RemmiPermissionDelegate(
  private val tabId: String,
  private val isGhost: Boolean,
  private val sessionManager: PermissionSessionManager = PermissionSessionManager.getInstance()
) : GeckoSession.PermissionDelegate {

  override fun onAndroidPermissionsRequest(
    session: GeckoSession,
    permissions: Array<out String>?,
    callback: GeckoSession.PermissionDelegate.Callback
  ) {
    if (permissions.isNullOrEmpty()) {
      callback.grant()
      return
    }

    val requester = sessionManager.androidPermissionRequester
    if (requester == null) {
      callback.reject()
      return
    }

    requester.requestAndroidPermissions(permissions.filterNotNull().toTypedArray()) { results ->
      val allGranted = permissions.all { results[it] == true }
      if (allGranted) {
        callback.grant()
      } else {
        callback.reject()
      }
    }
  }

  override fun onContentPermissionRequest(
    session: GeckoSession,
    perm: GeckoSession.PermissionDelegate.ContentPermission
  ): GeckoResult<Int>? {
    return sessionManager.handleContentPermissionRequest(session, tabId, perm, isGhost)
  }

  override fun onMediaPermissionRequest(
    session: GeckoSession,
    uri: String,
    video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
    audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
    callback: GeckoSession.PermissionDelegate.MediaCallback
  ) {
    sessionManager.handleMediaPermissionRequest(session, tabId, uri, video, audio, callback, isGhost)
  }
}
