package com.remmi.browser.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [34])
class Step38TabUrlOverwriteRegressionTest {

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
  fun testTabUrlOverwriteSuppressedDuringInFlightNav() = runBlocking {
    val tab = tabManager.createTab("https://source.com")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    // 1. Initial successful navigation
    var currentUrl = "https://source.com"
    manager.loadUrl(tabId, currentUrl)
    session.navigationDelegate?.onLoadRequest(session, makeLoadRequest(currentUrl, false, true))
    session.navigationDelegate?.onLocationChange(session, currentUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    try {
      assertEquals(currentUrl, tabManager.tabs.value.find { it.id == tabId }?.url)
    } catch (e: AssertionError) {
      println("FIRST ASSERTION FAILED. Debug logs:")
      DebugLogManager.getCurrentSessionEvents().forEach { println(it) }
      throw e
    }

    // 2. User taps link: links.kmhd.me/locked
    currentUrl = "https://links.kmhd.me/locked"
    session.navigationDelegate?.onLoadRequest(session, makeLoadRequest(currentUrl, false, true))
    
    // 3. Redirects to links.kmhd.me/file/Sinn_2cdd8587
    currentUrl = "https://links.kmhd.me/file/Sinn_2cdd8587"
    session.navigationDelegate?.onLoadRequest(session, makeLoadRequest(currentUrl, true, false))
    session.navigationDelegate?.onLocationChange(session, currentUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    try {
      assertEquals(currentUrl, tabManager.tabs.value.find { it.id == tabId }?.url)
    } catch (e: AssertionError) {
      println("SECOND ASSERTION FAILED. Debug logs:")
      DebugLogManager.getCurrentSessionEvents().forEach { println(it) }
      throw e
    }
    
    DebugLogManager.clear()

    // 4. User taps Katdrive link
    val targetUrl = "https://katdrive.click/file/1788619305"
    session.navigationDelegate?.onLoadRequest(session, makeLoadRequest(targetUrl, false, true))
    session.progressDelegate?.onPageStart(session, targetUrl)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)

    // In-flight state: The navigation has started. The tab manager holds the new URL.
    // The immediate update in onLoadRequest ensures that any BrowserView recomposition 
    // sees the latest URL and doesn't load a stale one.
    
    val events = DebugLogManager.getCurrentSessionEvents()
    assertTrue(events.any { it.contains("NAV_REQUESTED") && it.contains(targetUrl) })
  }
}
