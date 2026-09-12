package com.remmi.browser.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BlinkAndTransientBlankRegressionTest {

  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager
  private lateinit var context: Application

  private val testCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {
      val tabs = tabManager.tabs.value
      for (tab in tabs) {
        tabManager.updateTab(tab.id) { it.copy(url = url) }
      }
    }
  }

  private fun makeLoadRequest(
    uri: String,
    isRedirect: Boolean = false,
    hasUserGesture: Boolean = false
  ): GeckoSession.NavigationDelegate.LoadRequest {
    val constructor = GeckoSession.NavigationDelegate.LoadRequest::class.java.getDeclaredConstructor()
    constructor.isAccessible = true
    val req = constructor.newInstance()
    val uriField = req::class.java.getDeclaredField("uri")
    uriField.isAccessible = true
    uriField.set(req, uri)
    val isRedirectField = req::class.java.getDeclaredField("isRedirect")
    isRedirectField.isAccessible = true
    isRedirectField.setBoolean(req, isRedirect)
    val hasUserGestureField = req::class.java.getDeclaredField("hasUserGesture")
    hasUserGestureField.isAccessible = true
    hasUserGestureField.setBoolean(req, hasUserGesture)
    return req
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
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
  fun testTransientAboutBlankSuppressedOnCommittedPage() = runBlocking {
    val tab = tabManager.createTab("https://example.org")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    // 1. Initial navigation committed
    val siteUrl = "https://example.org"
    manager.loadUrl(tabId, siteUrl)
    session.navigationDelegate?.onLoadRequest(session, makeLoadRequest(siteUrl, false, true))
    session.navigationDelegate?.onLocationChange(session, siteUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    val tabAfterNav = tabManager.getTab(tabId)!!
    assertEquals(siteUrl, tabAfterNav.url)
    assertEquals(siteUrl, tabAfterNav.lastCommittedUrl)
    assertFalse(tabAfterNav.explicitHomeIntent)

    // 2. Gecko sends transient about:blank callback (e.g. document swap / reload / iframe attach)
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)

    // TabManager and Tab state MUST NOT have been overwritten with about:blank
    val tabAfterTransientBlank = tabManager.getTab(tabId)!!
    assertEquals(siteUrl, tabAfterTransientBlank.url)
    assertEquals(siteUrl, tabAfterTransientBlank.lastCommittedUrl)
    assertEquals(siteUrl, tabAfterTransientBlank.visibleUrl)
    assertFalse(tabAfterTransientBlank.explicitHomeIntent)

    // 3. User explicitly requests New Tab / Home
    tabManager.setExplicitHomeIntent(tabId, true)
    manager.resetToNewTab(tabId)
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)

    val tabAfterExplicitHome = tabManager.getTab(tabId)!!
    assertEquals("about:blank", tabAfterExplicitHome.url)
    assertTrue(tabAfterExplicitHome.explicitHomeIntent)
  }

  @Test
  fun testNavigationTransactionIdIncrementsOnNewNavigation() = runBlocking {
    val tab1 = tabManager.createTab("about:blank")
    val tx1 = tab1.navigationTransactionId

    // Navigate the blank tab to a new URL
    tabManager.openOrNavigateTab("https://second.com")
    val tab1Updated = tabManager.getTab(tab1.id)!!
    val tx2 = tab1Updated.navigationTransactionId
    assertTrue(tx2 > tx1)
    assertEquals("https://second.com", tab1Updated.requestedUrl)
    assertFalse(tab1Updated.explicitHomeIntent)

    val tx3 = tabManager.nextNavigationTransactionId(tab1.id)
    assertTrue(tx3 > tx2)
  }
}
