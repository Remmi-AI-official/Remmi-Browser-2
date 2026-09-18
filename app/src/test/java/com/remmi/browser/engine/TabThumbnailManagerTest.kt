package com.remmi.browser.engine

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TabThumbnailManagerTest {

  private lateinit var context: Application
  private lateinit var thumbnailManager: TabThumbnailManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    thumbnailManager = TabThumbnailManager.getInstance(context)
    thumbnailManager.clearAll()
    thumbnailManager.captureRequestCounter.set(0)
    thumbnailManager.captureExecutedCounter.set(0)
    thumbnailManager.captureSkippedCounter.set(0)
  }

  @Test
  fun test1_SaveAndGetThumbnail_MemoryAndDisk() = runBlocking {
    val tabId = "tab_test_1"
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    thumbnailManager.saveThumbnail(tabId, bitmap)

    // Wait briefly for background IO save
    delay(200)

    val loaded = thumbnailManager.getThumbnail(tabId)
    assertNotNull("Loaded thumbnail must not be null", loaded)
    assertEquals(360, loaded!!.width) // Scaled width
    assertTrue(thumbnailManager.thumbnailVersions.value.containsKey(tabId))
  }

  @Test
  fun test2_RemoveThumbnailAndClearAll() = runBlocking {
    val tabId = "tab_test_2"
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    thumbnailManager.saveThumbnail(tabId, bitmap)
    delay(200)
    assertNotNull(thumbnailManager.getThumbnail(tabId))

    thumbnailManager.removeThumbnail(tabId)
    delay(100)
    assertNull(thumbnailManager.getThumbnail(tabId))
    assertFalse(thumbnailManager.thumbnailVersions.value.containsKey(tabId))

    // Clear all
    thumbnailManager.saveThumbnail("tab_a", bitmap)
    thumbnailManager.saveThumbnail("tab_b", bitmap)
    delay(200)
    thumbnailManager.clearAll()
    delay(100)
    assertNull(thumbnailManager.getThumbnail("tab_a"))
    assertNull(thumbnailManager.getThumbnail("tab_b"))
    assertTrue(thumbnailManager.thumbnailVersions.value.isEmpty())
  }

  @Test
  fun test3_DebouncedCapture_BatchesMultipleCalls() = runBlocking {
    val geckoView = GeckoView(context)
    val session = GeckoSession()
    geckoView.setSession(session)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    val tabId = "tab_debounce_test"

    // Simulate 5 rapid capture requests (e.g. rapid NAV_STOP or subresources)
    for (i in 1..5) {
      thumbnailManager.captureGeckoView(tabId, geckoView, debounceMs = 200L)
    }

    assertEquals(5, thumbnailManager.captureRequestCounter.get())

    // Before debounce timer fires: 0 executions
    assertEquals(0, thumbnailManager.captureExecutedCounter.get())

    // Advance shadow looper past debounce duration
    shadowOf(android.os.Looper.getMainLooper()).runToEndOfTasks()

    // Exactly 1 execution occurred for the 5 calls
    assertEquals(1, thumbnailManager.captureExecutedCounter.get())
  }

  @Test
  fun test4_DuplicateCapture_WithinMinInterval_IsSkippedOrPostponed() = runBlocking {
    val geckoView = GeckoView(context)
    val session = GeckoSession()
    geckoView.setSession(session)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    val tabId = "tab_duplicate_test"

    // First capture: immediate
    thumbnailManager.captureGeckoView(tabId, geckoView, debounceMs = 0L, force = true)
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(1, thumbnailManager.captureExecutedCounter.get())

    // Request non-forced capture immediately after (within MIN_CAPTURE_INTERVAL_MS)
    thumbnailManager.captureGeckoView(tabId, geckoView, debounceMs = 100L, force = false)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    // Capture request was received, but skipped/postponed
    assertEquals(2, thumbnailManager.captureRequestCounter.get())
    assertTrue(thumbnailManager.captureSkippedCounter.get() >= 1)
  }

  @Test
  fun test5_ForceCapture_ExecutesImmediately() = runBlocking {
    val geckoView = GeckoView(context)
    val session = GeckoSession()
    geckoView.setSession(session)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    val tabId = "tab_force_test"

    // Force capture
    thumbnailManager.captureGeckoView(tabId, geckoView, debounceMs = 0L, force = true)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(1, thumbnailManager.captureExecutedCounter.get())
  }
}
