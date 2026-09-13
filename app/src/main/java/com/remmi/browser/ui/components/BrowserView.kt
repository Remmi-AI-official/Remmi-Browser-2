package com.remmi.browser.ui.components

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.remmi.browser.downloads.DownloadConfirmationRequest
import com.remmi.browser.downloads.DownloadHandler
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.GeckoTabCallbacks
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.reader.ReaderExtractor
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

@Composable
fun BrowserView(
  tab: BrowserTab,
  onUrlChange: (String) -> Unit,
  onTitleChange: (String) -> Unit,
  onProgressChange: (Int) -> Unit,
  onLoadingChange: (Boolean) -> Unit,
  onSecurityChange: (Boolean) -> Unit,
  onNavStateChange: (canGoBack: Boolean, canGoForward: Boolean) -> Unit,
  onTrackerBlocked: (url: String, type: String) -> Unit,
  onReaderArticleExtracted: ((ReaderArticle?) -> Unit)? = null,
  onContextMenuRequested: ((WebContextMenuData) -> Unit)? = null,
  onDownloadRequested: ((DownloadConfirmationRequest) -> Unit)? = null,
  onScrollChange: ((isScrollingDown: Boolean) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val geckoEngine = remember { GeckoEngineManager.getInstance(context) }
  val downloadHandler = remember { DownloadHandler.getInstance(context) }

  var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }
  var swipeRefreshRef by remember { mutableStateOf<WebSwipeRefreshLayout?>(null) }
  var isCurrentlyLoading by remember(tab.id) { mutableStateOf(tab.isLoading) }
  var progressFloat by remember(tab.id) { mutableFloatStateOf(if (tab.isLoading) (tab.progress.coerceAtLeast(15).toFloat() / 100f) else 0f) }

  LaunchedEffect(tab.isLoading, tab.progress) {
    isCurrentlyLoading = tab.isLoading
    if (tab.isLoading) {
      val prog = if (tab.progress > 0) (tab.progress.toFloat() / 100f).coerceIn(0.15f, 1f) else 0.15f
      progressFloat = prog
    } else {
      progressFloat = 0f
      swipeRefreshRef?.isRefreshing = false
    }
  }

  var lastNavigatedUrl by remember(tab.id) { mutableStateOf(geckoEngine.getLastDispatchedUrl(tab.id) ?: "") }
  var isAttachingOrAttached by remember(tab.id) { mutableStateOf(false) }
  val isViewAttached by geckoEngine.getViewAttachmentState(tab.id).collectAsState()
  val presentationState by geckoEngine.getPresentationStateFlow(tab.id).collectAsState()

  val currentOnUrlChange by rememberUpdatedState(onUrlChange)
  val currentOnTitleChange by rememberUpdatedState(onTitleChange)
  val currentOnProgressChange by rememberUpdatedState(onProgressChange)
  val currentOnLoadingChange by rememberUpdatedState(onLoadingChange)
  val currentOnSecurityChange by rememberUpdatedState(onSecurityChange)
  val currentOnNavStateChange by rememberUpdatedState(onNavStateChange)
  val currentOnTrackerBlocked by rememberUpdatedState(onTrackerBlocked)
  val currentOnScrollChange by rememberUpdatedState(onScrollChange)
  val currentOnReaderArticleExtracted by rememberUpdatedState(onReaderArticleExtracted)
  val currentOnDownloadRequested by rememberUpdatedState(onDownloadRequested)
  val currentOnContextMenuRequested by rememberUpdatedState(onContextMenuRequested)

  // Callbacks bundle decoupled from GeckoSession
  val tabCallbacks = remember(tab.id, tab.profile) {
    object : GeckoTabCallbacks {
      override fun onUrlChange(url: String) {
        val isBlank = url.isBlank() || url == "about:blank" || url == "remmi://newtab" || url == "about:home"
        if (isBlank && geckoEngine.isRealNavigationInProgress(tab.id)) {
          Log.i("BrowserView", "[FORENSIC][SUPPRESS_TRANSIENT_BLANK_CALLBACK] tabId=${tab.id} url=$url suppressed during REAL_NAVIGATION_IN_PROGRESS")
          return
        }
        lastNavigatedUrl = url
        currentOnUrlChange(url)
      }

      override fun onTitleChange(title: String) {
        currentOnTitleChange(title)
      }

      override fun onProgressChange(progress: Int) {
        if (progress in 1..99) {
          isCurrentlyLoading = true
          progressFloat = (progress.toFloat() / 100f).coerceIn(0.08f, 1f)
          currentOnProgressChange(progress)
        } else if (progress >= 100) {
          isCurrentlyLoading = false
          progressFloat = 1f
          currentOnProgressChange(100)
        } else {
          progressFloat = 0f
          currentOnProgressChange(0)
        }
      }

      override fun onLoadingChange(isLoading: Boolean) {
        if (!isLoading && geckoEngine.isRealNavigationInProgress(tab.id)) {
          Log.i("BrowserView", "[FORENSIC][SUPPRESS_TRANSIENT_LOADING_STOP] tabId=${tab.id} isLoading=false suppressed during REAL_NAVIGATION_IN_PROGRESS")
          return
        }
        isCurrentlyLoading = isLoading
        currentOnLoadingChange(isLoading)
        if (isLoading) {
          if (progressFloat <= 0f) progressFloat = 0.15f
        } else {
          progressFloat = 0f
          android.util.Log.i("AppStartup", "STATE_LOG: FIRST_PAGE_STOP (time=${android.os.SystemClock.elapsedRealtime()})")
          // Isolation step: disabled automatic thumbnail capture on page stop to prevent capturePixels() from blocking or causing flicker
          // val isRealWebUrl = tab.url.isNotBlank() && tab.url != "about:blank" && tab.url != "remmi://newtab" && tab.url != "about:home"
          // if (isRealWebUrl) {
          //   geckoViewRef?.let { gv ->
          //     com.remmi.browser.engine.TabThumbnailManager.getInstance(context).captureGeckoView(tab.id, gv)
          //   }
          // }
        }
      }

      override fun onSecurityChange(isSecure: Boolean) {
        currentOnSecurityChange(isSecure)
      }

      override fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {
        currentOnNavStateChange(canGoBack, canGoForward)
      }

      override fun onTrackerBlocked(url: String, type: String) {
        currentOnTrackerBlocked(url, type)
      }

      override fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {
        currentOnScrollChange?.invoke(isScrollingDown)
      }

      override fun onExternalResponse(response: WebResponse) {
        val uri = response.uri
        val contentDisposition = response.headers["Content-Disposition"]
        val filename = contentDisposition?.substringAfter("filename=")?.trim('"', '\'', ' ')
          ?: uri.substringAfterLast('/').substringBefore('?').ifEmpty { "download_${System.currentTimeMillis()}" }
        val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0L

        val requester = currentOnDownloadRequested
        if (requester != null) {
          requester(
            DownloadConfirmationRequest(
              url = uri,
              suggestedFilename = filename,
              mimeType = contentType,
              contentLength = contentLength,
              isGhost = tab.profile == PrivacyProfile.GHOST,
              webResponse = response,
              onConfirm = { confirmedName ->
                downloadHandler.enqueueDownload(
                  url = uri,
                  suggestedFilename = confirmedName,
                  mimeType = contentType,
                  contentLength = contentLength,
                  isGhost = tab.profile == PrivacyProfile.GHOST,
                  webResponse = response
                )
              },
              onCancel = {
                // User declined download
              }
            )
          )
        } else {
          downloadHandler.enqueueDownload(
            url = uri,
            suggestedFilename = filename,
            mimeType = contentType,
            contentLength = contentLength,
            isGhost = tab.profile == PrivacyProfile.GHOST,
            webResponse = response
          )
        }
      }

      override fun onContextMenu(data: WebContextMenuData) {
        currentOnContextMenuRequested?.invoke(data)
      }
    }
  }

  // Explicit lifecycle observer to re-activate tab session safely on app resume/pause
  DisposableEffect(tab.id) {
    val sessId = geckoEngine.getSession(tab.id)?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val mountMsg = "[FORENSIC][BROWSER_VIEW_MOUNT] tabId=${tab.id} session=$sessId url=${tab.url} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    android.util.Log.i("BrowserView", mountMsg)
    com.remmi.browser.util.DebugLogManager.log(mountMsg)
    onDispose {
      val unmountCaller = if (com.remmi.browser.BuildConfig.DEBUG) {
        try { Thread.currentThread().stackTrace.take(6).joinToString(" -> ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } } catch (_: Exception) { "unknown" }
      } else {
        "release"
      }
      val unmountMsg = "[FORENSIC][BROWSER_VIEW_UNMOUNT] tabId=${tab.id} session=$sessId url=${tab.url} reason=BrowserView_disposed caller=$unmountCaller elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      if (com.remmi.browser.BuildConfig.DEBUG) {
        android.util.Log.d("BrowserView", unmountMsg)
      }
      com.remmi.browser.util.DebugLogManager.log(unmountMsg)
    }
  }

  DisposableEffect(lifecycleOwner, tab.id) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          scope.launch {
            geckoEngine.setTabActive(tab.id, true)
            geckoViewRef?.let { gv ->
              gv.visibility = View.VISIBLE
              geckoEngine.attachView(
                tabId = tab.id,
                geckoView = gv,
                profile = tab.profile,
                isDesktopMode = tab.isDesktopMode,
                securityLevel = tab.securityLevel,
                containerType = tab.containerType,
                callbacks = tabCallbacks,
              )
            }
          }
        }
        Lifecycle.Event.ON_PAUSE -> {
          scope.launch {
            geckoEngine.setTabActive(tab.id, false)
          }
        }
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Synchronize tab settings with the underlying GeckoSession
  LaunchedEffect(tab.id, tab.profile, tab.isDesktopMode, tab.securityLevel, tab.containerType) {
    geckoEngine.updateTabSettings(tab.id, tab.isDesktopMode, tab.profile, tab.securityLevel)
  }

  // Handle URL navigation safely through GeckoEngineManager without feedback loops
  // Invariant: ONLY loadUrl when view attachment is complete and url is genuinely new
  LaunchedEffect(tab.url, isViewAttached) {
    if (!isViewAttached) return@LaunchedEffect
    if (tab.url.isBlank()) return@LaunchedEffect

    val currentDispatched = geckoEngine.getLastDispatchedUrl(tab.id)

    // Do not turn Gecko's transient about:blank lifecycle state into an actual navigation.
    // During document attach/replacement Gecko can briefly expose about:blank even though the
    // app's authoritative dispatched URL is still a real page. Resetting to a new-tab URL here
    // causes the Compose tree to swap and the GeckoView to blink.
    val isTransientBlankForRealTarget =
      (tab.url == "about:blank" || tab.url == "remmi://newtab" || tab.url == "about:home" || tab.url.isBlank()) &&
      (geckoEngine.isRealNavigationInProgress(tab.id) ||
       (!currentDispatched.isNullOrBlank() &&
        !geckoEngine.isInternalOrIgnoredUrl(currentDispatched) &&
        currentDispatched != tab.url))
    if (isTransientBlankForRealTarget) {
      Log.i("BrowserView", "[FORENSIC][SKIP_TRANSIENT_BLANK_LOAD] tabId=${tab.id} tabUrl=${tab.url} dispatchedUrl=$currentDispatched")
      return@LaunchedEffect
    }

    val isAlreadyDispatched = currentDispatched != null && (currentDispatched == tab.url || geckoEngine.areUrlsEquivalent(tab.url, currentDispatched))
    val isSameAsLastNav = tab.url == lastNavigatedUrl
    val isMatch = isAlreadyDispatched || isSameAsLastNav

    val decision = if (isMatch) {
      "SKIP_ALREADY_LOADED"
    } else {
      "DISPATCH_LOAD"
    }

    if (com.remmi.browser.BuildConfig.DEBUG || decision == "DISPATCH_LOAD") {
      val updateDecisionMsg = "[FORENSIC][BROWSER_VIEW_UPDATE_DECISION] source=LaunchedEffect tabId=${tab.id} tabUrl=${tab.url} lastNavigatedUrl=$lastNavigatedUrl lastDispatchedUrl=$currentDispatched decision=$decision elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      if (com.remmi.browser.BuildConfig.DEBUG) {
        Log.d("BrowserView", updateDecisionMsg)
      }
      com.remmi.browser.util.DebugLogManager.log(updateDecisionMsg)
    }

    if (decision == "DISPATCH_LOAD") {
      lastNavigatedUrl = tab.url
      if (tab.url == "about:blank" || tab.url == "remmi://newtab" || tab.url == "about:home") {
        geckoEngine.resetToNewTab(tab.id)
      } else {
        geckoEngine.loadUrl(tab.id, tab.url)
      }
    } else {
      lastNavigatedUrl = tab.url
    }
  }

  // Handle Reader Mode trigger with real web content extraction
  LaunchedEffect(tab.isReaderMode, tab.url) {
    if (tab.isReaderMode && tab.readerArticle == null && tab.url.isNotBlank() && tab.url != "about:blank") {
      try {
        val blockExtension = geckoEngine.blockExtension
        val htmlDeferred = CompletableDeferred<String>()
        
        blockExtension.extractTabHtml(
          tabId = tab.id,
          callback = { extractedUrl, html ->
            if (extractedUrl == tab.url || html.isNotEmpty()) {
              htmlDeferred.complete(html)
            }
          }
        )

        val pageHtml = withTimeoutOrNull(2500L) { htmlDeferred.await() }

        val extracted = if (!pageHtml.isNullOrBlank()) {
          val domain = try {
            java.net.URI(tab.url).host ?: tab.url.substringAfter("://").substringBefore('/')
          } catch (e: Exception) {
            tab.url.substringAfter("://").substringBefore('/')
          }
          ReaderExtractor.parseHtmlDocument(pageHtml, tab.url, tab.title, domain)
        } else {
          val isGhost = tab.profile == PrivacyProfile.GHOST
          ReaderExtractor.extractFromUrl(context, tab.url, tab.title, isGhost)
        }

        onReaderArticleExtracted?.invoke(extracted)
      } catch (e: Exception) {
        Log.e("BrowserView", "Failed to extract clean reader article for ${tab.url}", e)
        onReaderArticleExtracted?.invoke(null)
      }
    }
  }

  val surfaceColor = if (ThemeCyber.colors.isLight) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#121824")

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.surface)
  ) {
    AndroidView<WebSwipeRefreshLayout>(
      modifier = Modifier
        .fillMaxSize()
        .alpha(1f)
        .background(ThemeCyber.colors.surface)
        .testTag("gecko_browser_view"),
      factory = { ctx ->
        val swipeLayout = WebSwipeRefreshLayout(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          setBackgroundColor(surfaceColor)
          isEnabled = false
          setColorSchemeColors(
            android.graphics.Color.parseColor("#00E5FF"),
            android.graphics.Color.parseColor("#7C4DFF"),
            android.graphics.Color.parseColor("#00E676")
          )
          setProgressBackgroundColorSchemeColor(android.graphics.Color.parseColor("#121824"))
        }
        val gv = geckoEngine.getOrCreateGeckoView(ctx, tab.id).apply {
          if (parent !== swipeLayout) {
            (parent as? ViewGroup)?.removeView(this)
            setBackgroundColor(surfaceColor)
            tag = tab.id
            swipeLayout.addView(this)
          }
          alpha = 1f
          setDynamicToolbarMaxHeight(0)
          geckoViewRef = this
        }
        swipeLayout.canScrollUpCallback = {
          val scrollY = geckoEngine.getScrollY(tab.id)
          if (scrollY > 10) {
            true
          } else {
            gv.canScrollVertically(-1)
          }
        }
        swipeLayout.setOnRefreshListener {
          geckoEngine.reload(tab.id)
        }
        swipeRefreshRef = swipeLayout

        val gvId = "0x" + Integer.toHexString(System.identityHashCode(gv))
        val now = android.os.SystemClock.elapsedRealtime()
        val msg = "[FORENSIC][VIEW_FACTORY] tabId=${tab.id} view=$gvId tag=${gv.tag} url=${tab.url} elapsedRealtime=$now"
        android.util.Log.i("BrowserView", msg)
        com.remmi.browser.util.DebugLogManager.log(msg)

        isAttachingOrAttached = true
        scope.launch {
          geckoEngine.attachView(
            tabId = tab.id,
            geckoView = gv,
            profile = tab.profile,
            isDesktopMode = tab.isDesktopMode,
            securityLevel = tab.securityLevel,
            containerType = tab.containerType,
            callbacks = tabCallbacks,
          )
        }
        swipeLayout
      },
      update = { swipeLayout ->
        swipeRefreshRef = swipeLayout
        val targetGeckoView = geckoEngine.getOrCreateGeckoView(swipeLayout.context, tab.id)
        if (targetGeckoView.parent !== swipeLayout) {
          (targetGeckoView.parent as? ViewGroup)?.removeView(targetGeckoView)
          targetGeckoView.setBackgroundColor(surfaceColor)
          targetGeckoView.tag = tab.id
          swipeLayout.addView(targetGeckoView)
          // Atomically remove old views only after the target replacement view is attached
          for (i in swipeLayout.childCount - 1 downTo 0) {
            val child = swipeLayout.getChildAt(i)
            if (child !== targetGeckoView) {
              swipeLayout.removeViewAt(i)
            }
          }
        }
        val geckoView = targetGeckoView
        geckoView.alpha = 1f
        geckoView.setDynamicToolbarMaxHeight(0)
        geckoViewRef = geckoView
        val prevTag = geckoView.tag as? String
        val isTagMatch = prevTag == tab.id

        if (com.remmi.browser.BuildConfig.DEBUG) {
          val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
          val now = android.os.SystemClock.elapsedRealtime()
          val updateMsg = "[FORENSIC][VIEW_UPDATE] tabId=${tab.id} view=$gvId tag=$prevTag isTagMatch=$isTagMatch url=${tab.url} elapsedRealtime=$now"
          android.util.Log.d("BrowserView", updateMsg)
          com.remmi.browser.util.DebugLogManager.log(updateMsg)
        }

        val isSessionAttached = geckoView.session != null && geckoEngine.getAttachedView(tab.id) === geckoView
        val isAlreadyMatched = isTagMatch && isSessionAttached

        if (!isAlreadyMatched) {
          isAttachingOrAttached = true
          val oldTabId = if (!isTagMatch) prevTag else null
          geckoView.tag = tab.id
          scope.launch {
            if (oldTabId != null && oldTabId != tab.id) {
              geckoEngine.detachView(oldTabId, geckoView)
            }
            geckoEngine.attachView(
              tabId = tab.id,
              geckoView = geckoView,
              profile = tab.profile,
              isDesktopMode = tab.isDesktopMode,
              securityLevel = tab.securityLevel,
              containerType = tab.containerType,
              callbacks = tabCallbacks,
            )
          }
        }
        swipeLayout.canScrollUpCallback = {
          val scrollY = geckoEngine.getScrollY(tab.id)
          if (scrollY > 10) {
            true
          } else {
            geckoView.canScrollVertically(-1)
          }
        }
        swipeLayout.setOnRefreshListener {
          geckoEngine.reload(tab.id)
        }
        swipeLayout.isEnabled = false
        if (!tab.isLoading && swipeLayout.isRefreshing) {
          swipeLayout.isRefreshing = false
        }
      },
      onRelease = { swipeLayout ->
        val geckoView = (swipeLayout.getChildAt(0) as? GeckoView) ?: geckoViewRef
        if (geckoView != null) {
          val currentTag = geckoView.tag as? String ?: tab.id
          val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
          val now = android.os.SystemClock.elapsedRealtime()
          val relMsg = "[FORENSIC][VIEW_ON_RELEASE] tabId=${tab.id} view=$gvId tag=$currentTag url=${tab.url} elapsedRealtime=$now"
          if (com.remmi.browser.BuildConfig.DEBUG) {
            android.util.Log.d("BrowserView", relMsg)
          }
          com.remmi.browser.util.DebugLogManager.log(relMsg)
        }
        // Preserve existing GeckoView and session in geckoEngine.
        // Do NOT capture thumbnail here (captured safely on page load completion).
        // Do NOT call removeAllViews() or immediately detach GeckoView during transient Compose updates/recompositions.
        swipeRefreshRef = null
      },
    )
  }
}
