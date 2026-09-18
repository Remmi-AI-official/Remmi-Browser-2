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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mozilla.geckoview.WebResponse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Step40NewSessionLifecycleRegressionTest {
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
    
    override fun onExternalResponse(response: WebResponse) {
    }
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
    
    try {
      val fakeRuntime = org.mozilla.geckoview.GeckoRuntime.getDefault(context)
      manager.setRuntimeForTesting(fakeRuntime)
    } catch (_: Exception) {
      // In Robolectric environment without native gecko libs, native runtime init is omitted
    }
    
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
  fun testOnNewSessionLifecycle() = runBlocking {
    val tab = tabManager.createTab("https://example.com")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    val newTabUrl = "https://example.com/target-blank"
    val result = session.navigationDelegate?.onNewSession(session, newTabUrl)
    
    assertNotNull(result)
    
    var newSession: GeckoSession? = null
    result!!.then { res ->
      newSession = res
      org.mozilla.geckoview.GeckoResult.fromValue(res)
    }
    for (i in 0 until 50) {
      if (newSession != null) break
      kotlinx.coroutines.delay(20)
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
    }
    assertNotNull("Should return a new session", newSession)
    println("FORENSIC LOGS:\n" + DebugLogManager.getCurrentSessionEvents().joinToString("\n"))
    assertFalse("Returned session MUST BE UNOPENED", newSession!!.isOpen)
    
    val tabs = tabManager.tabs.value
    assertEquals(3, tabs.size)
    val newTab = tabs.last()
    assertEquals(newTabUrl, newTab.url)
    assertTrue("Should be opened from link", newTab.openedFromLink)
    assertEquals(tabId, newTab.parentTabId)
    assertEquals(0, newTab.inTabNavigationCount)
    
    val activeSessionInManager = manager.getSessionForTest(newTab.id)
    assertEquals(newSession, activeSessionInManager)
    
    // Check generated events
    val events = DebugLogManager.getCurrentSessionEvents()
    val hasReturnEvent = events.any { it.contains("[FORENSIC][NEW_SESSION_RETURN]") && it.contains("isOpen=false") }
    assertTrue("Should log that it is returning unopened session", hasReturnEvent)
  }

  @Test
  fun testCloseTabSwitchesToParentTab() {
    val parentTab = tabManager.createTab("https://example.com/parent")
    val childTab = tabManager.createTab(
      url = "https://example.com/child",
      parentTabId = parentTab.id,
      openedFromLink = true
    )

    assertEquals(childTab.id, tabManager.activeTab?.id)
    assertEquals(0, childTab.inTabNavigationCount)

    // When child tab is closed, it must return directly to parent tab
    tabManager.closeTab(childTab.id, switchToParent = true)
    assertEquals(parentTab.id, tabManager.activeTab?.id)
  }
}
