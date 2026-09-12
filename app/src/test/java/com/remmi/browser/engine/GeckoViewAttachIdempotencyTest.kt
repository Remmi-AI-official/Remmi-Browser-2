package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.gecko.GeckoAppShell
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GeckoViewAttachIdempotencyTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager

  private val testCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {}
    override fun onTitleChange(title: String) {}
    override fun onProgressChange(progress: Int) {}
    override fun onLoadingChange(isLoading: Boolean) {}
    override fun onSecurityChange(isSecure: Boolean) {}
    override fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
    override fun onTrackerBlocked(url: String, type: String) {}
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    GeckoAppShell.setApplicationContext(context)
    DebugLogManager.init(context)
    DebugLogManager.clear()

    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()

    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
    manager.uriLoaderForTest = { _, _, _ -> }
    manager.sessionOpenerForTest = { _, _ -> }
  }

  @After
  fun tearDown() = runBlocking {
    manager.uriLoaderForTest = null
    manager.sessionOpenerForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
    DebugLogManager.clear()
  }

  @Test
  fun test1_SingleStableGeckoViewPerTab() {
    val tabId = "tab_stable_view_1"
    val view1 = manager.getOrCreateGeckoView(context, tabId)
    val view2 = manager.getOrCreateGeckoView(context, tabId)

    assertSame("getOrCreateGeckoView must return the identical stable GeckoView instance for the same tabId", view1, view2)
    assertEquals("GeckoView tag must equal tabId", tabId, view1.tag)
  }

  @Test
  fun test2_AttachViewIsStrictlyIdempotent() = runBlocking {
    val tab = tabManager.createTab("https://example.com")
    val tabId = tab.id

    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = manager.getOrCreateGeckoView(context, tabId)

    // First attach
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )

    val attachedViewFirst = manager.getAttachedView(tabId)
    val sessionFirst = geckoView.session
    assertNotNull("Attached view must not be null after attach", attachedViewFirst)
    assertSame("Attached view must be the provided geckoView", geckoView, attachedViewFirst)
    assertTrue("Tab view must be marked attached", manager.isViewAttached(tabId))

    // Second attach (simulating recomposition / URL update)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )

    val attachedViewSecond = manager.getAttachedView(tabId)
    val sessionSecond = geckoView.session
    assertSame("Second attach must not swap or recreate the attached view", attachedViewFirst, attachedViewSecond)
    assertSame("Second attach must not swap or recreate the GeckoSession", sessionFirst, sessionSecond)
    assertTrue("Tab view must remain attached", manager.isViewAttached(tabId))

    // Third attach
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )

    assertSame("Third attach must remain strictly idempotent", attachedViewFirst, manager.getAttachedView(tabId))
  }

  @Test
  fun test3_UrlChangesDoNotDetachView() = runBlocking {
    val tab = tabManager.createTab("https://example.com/initial")
    val tabId = tab.id

    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = manager.getOrCreateGeckoView(context, tabId)

    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )

    assertTrue("View must be attached initially", manager.isViewAttached(tabId))

    // Dispatches URL 1
    manager.loadUrl(tabId, "https://example.com/page1")
    assertTrue("View must remain attached after loadUrl 1", manager.isViewAttached(tabId))
    assertSame("Attached view instance must not change", geckoView, manager.getAttachedView(tabId))

    // Dispatches URL 2
    manager.loadUrl(tabId, "https://example.com/page2")
    assertTrue("View must remain attached after loadUrl 2", manager.isViewAttached(tabId))
    assertSame("Attached view instance must not change", geckoView, manager.getAttachedView(tabId))
  }
}
