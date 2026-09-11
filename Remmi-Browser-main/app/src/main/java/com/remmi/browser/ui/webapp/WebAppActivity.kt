package com.remmi.browser.ui.webapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import coil3.compose.SubcomposeAsyncImage
import com.remmi.browser.MainActivity
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.GeckoTabCallbacks
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.RedirectInspector
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.RemmiTheme
import com.remmi.browser.ui.theme.ThemeCyber
import com.remmi.browser.util.FaviconHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import java.net.URI
import java.util.UUID

class WebAppActivity : FragmentActivity() {

  private val webAppTabId = "pwa_" + UUID.randomUUID().toString().take(8)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val targetUrl = intent.getStringExtra("EXTRA_URL")
      ?: intent.dataString
      ?: ""

    // WebAppActivity is exported for launcher shortcuts, so never fall back to an implicit
    // third-party site when an external caller omits/poisons the URL.
    if (targetUrl.isBlank() || !RedirectInspector.isSchemeSafeForNavigation(targetUrl)) {
      finish()
      return
    }

    val initialTitle = intent.getStringExtra("EXTRA_TITLE") ?: try {
      URI(targetUrl).host?.removePrefix("www.") ?: "Web App"
    } catch (_: Exception) {
      "Web App"
    }

    val settingsRepo = SettingsRepository.getInstance(applicationContext)

    setContent {
      val settings by settingsRepo.settings.collectAsState()
      RemmiTheme(
        appearanceMode = settings.appearanceMode,
        cyberTheme = settings.cyberTheme,
        cyberHudEnabled = settings.cyberHudEnabled,
        pureBlackOled = settings.pureBlackOled,
        browserFont = settings.browserFont,
      ) {
        WebAppScreen(
          initialUrl = targetUrl,
          initialTitle = initialTitle,
          tabId = webAppTabId,
          onClose = { finish() }
        )
      }
    }
  }

  override fun onDestroy() {
    val engine = GeckoEngineManager.getInstance(applicationContext)
    lifecycleScope.launch(Dispatchers.Main) {
      engine.closeSessionSafely(webAppTabId)
    }
    super.onDestroy()
  }
}

@Composable
fun WebAppScreen(
  initialUrl: String,
  initialTitle: String,
  tabId: String,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val geckoEngine = remember { GeckoEngineManager.getInstance(context) }
  val scope = rememberCoroutineScope()

  var currentUrl by remember { mutableStateOf(initialUrl) }
  var currentTitle by remember { mutableStateOf(initialTitle) }
  var isLoading by remember { mutableStateOf(true) }
  var hasLoadedOnce by remember { mutableStateOf(false) }
  var canGoBack by remember { mutableStateOf(false) }
  var canGoForward by remember { mutableStateOf(false) }

  val displayHost = remember(currentUrl) {
    try {
      URI(currentUrl).host?.removePrefix("www.") ?: currentUrl
    } catch (_: Exception) {
      currentUrl
    }
  }

  val initialChar = remember(displayHost, initialTitle) {
    val src = if (displayHost.isNotBlank()) displayHost else initialTitle
    src.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
  }

  val faviconCandidates = remember(initialUrl) {
    FaviconHelper.getCandidateFaviconUrls(initialUrl)
  }
  val primaryFaviconUrl = faviconCandidates.firstOrNull()

  // Handle hardware back button inside PWA
  BackHandler {
    if (canGoBack) {
      geckoEngine.goBack(tabId)
    } else {
      onClose()
    }
  }

  val tabCallbacks = remember {
    object : GeckoTabCallbacks {
      override fun onTitleChange(title: String) {
        if (title.isNotBlank() && !title.contains("about:blank")) {
          currentTitle = title
        }
      }

      override fun onUrlChange(url: String) {
        if (url.isNotBlank() && url != "about:blank") {
          currentUrl = url
        }
      }

      override fun onLoadingChange(loading: Boolean) {
        isLoading = loading
        if (!loading) {
          hasLoadedOnce = true
        }
      }

      override fun onProgressChange(progress: Int) {
        if (progress >= 70) {
          hasLoadedOnce = true
        }
      }

      override fun onSecurityChange(secure: Boolean) {}

      override fun onNavStateChange(canBack: Boolean, canForward: Boolean) {
        canGoBack = canBack
        canGoForward = canForward
      }
    }
  }

  // Pure Native App layout: No browser headers, no top bars, pure edge-to-edge web app canvas
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    // 1. Standalone GeckoView Web App Container
    AndroidView(
      factory = { ctx ->
        GeckoView(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          visibility = View.VISIBLE
          isFocusable = true
          isFocusableInTouchMode = true
          isNestedScrollingEnabled = false
          tag = tabId

          scope.launch {
            geckoEngine.attachView(
              tabId = tabId,
              geckoView = this@apply,
              profile = PrivacyProfile.SHIELD,
              isDesktopMode = false,
              securityLevel = SecurityLevel.STANDARD,
              containerType = ContainerType.NORMAL,
              callbacks = tabCallbacks,
            )
            geckoEngine.loadUrl(tabId, initialUrl)
          }
        }
      },
      modifier = Modifier.fillMaxSize()
    )

    // 2. Native Android App Splash Screen (shown while app loads, then smoothly fades out)
    AnimatedVisibility(
      visible = !hasLoadedOnce,
      enter = fadeIn(),
      exit = fadeOut(animationSpec = tween(durationMillis = 350)),
      modifier = Modifier.fillMaxSize()
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(ThemeCyber.colors.background),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.padding(32.dp)
        ) {
          // Native App Logo
          Box(
            modifier = Modifier
              .size(92.dp)
              .shadow(16.dp, RoundedCornerShape(22.dp))
              .clip(RoundedCornerShape(22.dp))
              .background(Color.White)
              .border(1.5.dp, ThemeCyber.colors.primary.copy(alpha = 0.6f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
          ) {
            if (primaryFaviconUrl != null) {
              SubcomposeAsyncImage(
                model = primaryFaviconUrl,
                contentDescription = "App Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .size(64.dp)
                  .padding(4.dp),
                loading = {
                  Text(
                    text = initialChar,
                    color = Color.Black,
                    fontFamily = CyberMonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp
                  )
                },
                error = {
                  Text(
                    text = initialChar,
                    color = Color.Black,
                    fontFamily = CyberMonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp
                  )
                }
              )
            } else {
              Text(
                text = initialChar,
                color = Color.Black,
                fontFamily = CyberMonoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 38.sp
              )
            }
          }

          Spacer(modifier = Modifier.height(20.dp))

          // App Name
          Text(
            text = initialTitle,
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
          )

          Spacer(modifier = Modifier.height(6.dp))

          // Domain Subtitle
          Text(
            text = displayHost,
            color = ThemeCyber.colors.textSecondary,
            fontFamily = CyberMonoFamily,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  }
}
