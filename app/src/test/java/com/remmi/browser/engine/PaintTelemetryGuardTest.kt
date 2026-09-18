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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaintTelemetryGuardTest {
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager
  private lateinit var context: Application

  private val firstCompositeCount = AtomicInteger(0)
  private val firstContentfulPaintCount = AtomicInteger(0)
  private val paintStatusResetCount = AtomicInteger(0)

  private val testCallbacks = object : GeckoTabCallbacks {
    override fun onFirstComposite() {
      firstCompositeCount.incrementAndGet()
    }

    override fun onFirstContentfulPaint() {
      firstContentfulPaintCount.incrementAndGet()
    }

    override fun onPaintStatusReset() {
      paintStatusResetCount.incrementAndGet()
    }
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    DebugLogManager.init(context)
    DebugLogManager.clear()
    firstCompositeCount.set(0)
    firstContentfulPaintCount.set(0)
    paintStatusResetCount.set(0)

    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
    manager.uriLoaderForTest = { _, _, _ -> }

    try {
      val fakeRuntime = org.mozilla.geckoview.GeckoRuntime.getDefault(context)
      manager.setRuntimeForTesting(fakeRuntime)
    } catch (_: Exception) {}

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
  fun testFirstCompositeAndFirstContentfulPaintAreOneShotPerNavigation() = runBlocking {
    val tabId = "tab_paint_test_1"
    val session = GeckoSession()
    manager.setSessionForTesting(tabId, session)
    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    // Allocate genuine navigation
    manager.allocateNavigationGeneration(tabId, "loadUrl", "https://example.com")

    // First emission should succeed
    val composite1 = manager.tryEmitFirstComposite(tabId, session)
    assertTrue("First composite must succeed on first call", composite1)
    assertEquals(1, firstCompositeCount.get())

    // Repeated emission within same navigation must be blocked (one-shot)
    val composite2 = manager.tryEmitFirstComposite(tabId, session)
    assertFalse("Repeated first composite must be blocked", composite2)
    assertEquals(1, firstCompositeCount.get())

    // First contentful paint should succeed once
    val fcp1 = manager.tryEmitFirstContentfulPaint(tabId, session)
    assertTrue("First contentful paint must succeed on first call", fcp1)
    assertEquals(1, firstContentfulPaintCount.get())

    // Repeated first contentful paint must be blocked (one-shot)
    val fcp2 = manager.tryEmitFirstContentfulPaint(tabId, session)
    assertFalse("Repeated first contentful paint must be blocked", fcp2)
    assertEquals(1, firstContentfulPaintCount.get())
  }

  @Test
  fun testPaintStatusResetRunsOnlyOnGenuinelyNewNavigation() = runBlocking {
    val tabId = "tab_paint_test_2"
    val session = GeckoSession()
    manager.setSessionForTesting(tabId, session)
    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    // Genuine navigation 1: allocateNavigationGeneration runs tryEmitPaintStatusReset once
    manager.allocateNavigationGeneration(tabId, "loadUrl", "https://example.com/page1")
    assertEquals("Paint status reset should have fired once on new navigation", 1, paintStatusResetCount.get())

    // Any repeated attempt for navigation 1 must be blocked
    val resetAgain = manager.tryEmitPaintStatusReset(tabId, session)
    assertFalse("Repeated paint status reset must be blocked", resetAgain)
    assertEquals(1, paintStatusResetCount.get())

    // Location changed (in-page hash, onLocationChange) is NOT a genuinely new navigation and must NOT reset paint state
    manager.allocateNavigationGeneration(tabId, "LOCATION_CHANGED", "https://example.com/page1#anchor")
    val resetOnLocation = manager.tryEmitPaintStatusReset(tabId, session)
    assertFalse("LOCATION_CHANGED must not trigger paint status reset", resetOnLocation)
    assertEquals("Paint status reset count must remain 1 after location change", 1, paintStatusResetCount.get())

    // Genuinely new navigation 2 (loadUrl): paint status reset fires once for the new navigation
    manager.allocateNavigationGeneration(tabId, "loadUrl", "https://example.com/page2")
    assertEquals("Paint status reset should fire once for second genuine navigation", 2, paintStatusResetCount.get())

    // Repeated call on navigation 2 is blocked
    val reset2Again = manager.tryEmitPaintStatusReset(tabId, session)
    assertFalse("Repeated paint status reset on new navigation must be blocked", reset2Again)
    assertEquals(2, paintStatusResetCount.get())
  }

  @Test
  fun testPaintStateNotResetByProgressOrLoading() = runBlocking {
    val tabId = "tab_paint_test_3"
    val session = GeckoSession()
    manager.setSessionForTesting(tabId, session)
    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    manager.loadUrl(tabId, "https://example.com")
    assertTrue("Document rendered state should remain true", manager.isDocumentRendered(tabId))

    // Call delegate progress & loading callbacks
    session.progressDelegate?.onPageStart(session, "https://example.com")
    assertTrue("Page start must not reset document rendered state", manager.isDocumentRendered(tabId))

    session.progressDelegate?.onProgressChange(session, 50)
    assertTrue("Progress change must not reset document rendered state", manager.isDocumentRendered(tabId))

    session.progressDelegate?.onPageStop(session, true)
    assertTrue("Page stop must not reset document rendered state", manager.isDocumentRendered(tabId))
  }

  @Test
  fun testPaintCallbacksArePassiveDiagnosticsOnly() = runBlocking {
    val tabId = "tab_paint_passive_test"
    val session = GeckoSession()
    manager.setSessionForTesting(tabId, session)
    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    manager.allocateNavigationGeneration(tabId, "loadUrl", "https://example.com/passive")
    val initialNavGen = manager.getNavGeneration(tabId)
    val initialSession = manager.getSession(tabId)

    // Fire composite and paint
    val compositeSuccess = manager.tryEmitFirstComposite(tabId, session)
    assertTrue("First composite emitted", compositeSuccess)

    val paintSuccess = manager.tryEmitFirstContentfulPaint(tabId, session)
    assertTrue("First contentful paint emitted", paintSuccess)

    // Verify engine and session state did NOT reset, reload, or change
    assertEquals("Session must remain unchanged after paint events", initialSession, manager.getSession(tabId))
    assertEquals("Navigation generation must remain unchanged after paint events", initialNavGen, manager.getNavGeneration(tabId))
    assertTrue("View must remain attached", manager.isViewAttached(tabId))

    // Verify forensic logs exist passively
    val forensicLogs = DebugLogManager.getRecentEvents()
    assertTrue("Forensic logs must contain FIRST_COMPOSITE", forensicLogs.any { it.contains("FIRST_COMPOSITE") })
    assertTrue("Forensic logs must contain FIRST_CONTENTFUL_PAINT", forensicLogs.any { it.contains("FIRST_CONTENTFUL_PAINT") })
  }
}
