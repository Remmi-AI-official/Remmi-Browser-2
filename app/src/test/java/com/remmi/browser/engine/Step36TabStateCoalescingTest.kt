package com.remmi.browser.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.PrivacyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Step36TabStateCoalescingTest {

  private lateinit var tabManager: TabManager

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    tabManager = TabManager.getInstance()
    tabManager.isAutoFlushEnabled = false
    tabManager.closeAllTabs()
    tabManager.stateEmissionCounter.set(0)
    tabManager.tabUpdateCounter.set(0)
    tabManager.trackerEventCounter.set(0)
  }

  @After
  fun tearDown() {
    tabManager.isAutoFlushEnabled = true
  }

  @Test
  fun test1_TrackerUpdates_DoNotEmitStateFlowImmediately() = runBlocking {
    val tab = tabManager.createTab("https://example.com")
    val initialEmissions = tabManager.stateEmissionCounter.get()
    val initialTab = tabManager.getTab(tab.id)
    assertNotNull(initialTab)
    assertEquals(0, initialTab!!.blockedTrackersCount)

    // Fire multiple tracker blocked events rapidly
    tabManager.incrementTrackerCount(tab.id, "https://doubleclick.net/ad.js")
    tabManager.incrementTrackerCount(tab.id, "https://google-analytics.com/analytics.js")
    tabManager.incrementTrackerCount(tab.id, "https://facebook.com/tr")
    tabManager.incrementTrackerCount(tab.id, "https://coinhive.com/miner.js")
    tabManager.incrementTrackerCount(tab.id, "https://fingerprintjs.com/fp.js")

    // State emission counter must NOT have increased
    val emissionsAfterTrackers = tabManager.stateEmissionCounter.get()
    assertEquals(
      "Tracker blocked events must not trigger immediate StateFlow emissions",
      initialEmissions,
      emissionsAfterTrackers
    )

    // Tracker event counter must be updated to 5
    assertEquals(5, tabManager.trackerEventCounter.get())

    // Internal live tab query must reflect the latest accurate stats without delay
    val liveTab = tabManager.getTab(tab.id)
    assertNotNull(liveTab)
    assertEquals(5, liveTab!!.blockedTrackersCount)
    assertEquals(1, liveTab.adsBlockedCount)
    assertEquals(1, liveTab.analyticsBlockedCount)
    assertEquals(1, liveTab.socialBlockedCount)
    assertEquals(1, liveTab.cryptomineBlockedCount)
    assertEquals(1, liveTab.fingerprintBlockedCount)
    assertEquals(5, liveTab.blockedLog.size)
  }

  @Test
  fun test2_CoalescedFlush_EmitsSingleUpdate() = runBlocking {
    val tab = tabManager.createTab("https://example.com")
    val initialEmissions = tabManager.stateEmissionCounter.get()

    // Rapidly fire 20 tracker events
    for (i in 1..20) {
      tabManager.incrementTrackerCount(tab.id, "https://tracker$i.com/track")
    }

    // Still no StateFlow emissions
    assertEquals(initialEmissions, tabManager.stateEmissionCounter.get())

    // Now trigger a batched/coalesced flush
    tabManager.flushPendingTrackerStats(tab.id)

    // Exactly 1 StateFlow emission occurred for all 20 events
    assertEquals(initialEmissions + 1, tabManager.stateEmissionCounter.get())

    // Tabs StateFlow contains the latest tracker count
    val tabInStateFlow = tabManager.tabs.value.find { it.id == tab.id }
    assertNotNull(tabInStateFlow)
    assertEquals(20, tabInStateFlow!!.blockedTrackersCount)
    assertEquals(20, tabInStateFlow.blockedLog.size)

    // Second flush without new tracker events should be a no-op (no extra emissions)
    tabManager.flushPendingTrackerStats(tab.id)
    assertEquals(initialEmissions + 1, tabManager.stateEmissionCounter.get())
  }

  @Test
  fun test3_IdenticalUpdates_DoNotEmitStateFlow() = runBlocking {
    val tab = tabManager.createTab("https://example.com")
    val initialEmissions = tabManager.stateEmissionCounter.get()

    // 1. Identical URL update
    tabManager.updateTab(tab.id) { it.copy(url = "https://example.com") }
    assertEquals("Identical URL update must not emit", initialEmissions, tabManager.stateEmissionCounter.get())

    // 2. Identical title update
    tabManager.updateTab(tab.id) { it.copy(title = tab.title) }
    assertEquals("Identical title update must not emit", initialEmissions, tabManager.stateEmissionCounter.get())

    // 3. Identical loading & progress update
    tabManager.updateTab(tab.id) { it.copy(isLoading = tab.isLoading, progress = tab.progress) }
    assertEquals("Identical loading/progress update must not emit", initialEmissions, tabManager.stateEmissionCounter.get())

    // 4. Return same instance directly
    tabManager.updateTab(tab.id) { it }
    assertEquals("No-op update returning same instance must not emit", initialEmissions, tabManager.stateEmissionCounter.get())
  }

  @Test
  fun test4_UserVisibleChanges_EmitStateFlowAndIntegratePendingTrackers() = runBlocking {
    val tab = tabManager.createTab("https://example.com")
    val initialEmissions = tabManager.stateEmissionCounter.get()

    // Add tracker events
    tabManager.incrementTrackerCount(tab.id, "https://doubleclick.net/ad.js")
    tabManager.incrementTrackerCount(tab.id, "https://google-analytics.com/analytics.js")

    // Update user-visible title -> single emission incorporates both the title and tracker stats
    tabManager.updateTab(tab.id) { it.copy(title = "Example Domain") }
    assertEquals(initialEmissions + 1, tabManager.stateEmissionCounter.get())

    val tabInStateFlow = tabManager.tabs.value.find { it.id == tab.id }
    assertNotNull(tabInStateFlow)
    assertEquals("Example Domain", tabInStateFlow!!.title)
    assertEquals(2, tabInStateFlow.blockedTrackersCount)
  }

  @Test
  fun test5_ConcurrentTrackerEvents_ThreadSafety() = runBlocking {
    val tab = tabManager.createTab("https://example.com")

    // Fire 100 concurrent tracker events across multiple coroutines
    val jobs = (1..100).map { i ->
      async(Dispatchers.Default) {
        tabManager.incrementTrackerCount(tab.id, "https://tracker$i.org/pixel")
      }
    }
    jobs.awaitAll()

    val liveTab = tabManager.getTab(tab.id)
    assertNotNull(liveTab)
    assertEquals(100, liveTab!!.blockedTrackersCount)
    assertEquals(100, liveTab.blockedLog.size)
  }
}
