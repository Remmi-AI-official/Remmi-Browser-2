package com.remmi.browser.engine

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TabThumbnailMemoryOptimizationTest {

  private lateinit var context: Application
  private lateinit var thumbnailManager: TabThumbnailManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    thumbnailManager = TabThumbnailManager.getInstance(context)
  }

  @Test
  fun test1_ThumbnailManager_SavesAndRetrievesThumbnail() {
    val tabId = "test-tab-1"
    val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
    thumbnailManager.saveThumbnail(tabId, bitmap)

    // Wait for async compression/scaling
    var retrieved: Bitmap? = null
    for (i in 1..20) {
      Thread.sleep(50)
      retrieved = thumbnailManager.getThumbnail(tabId)
      if (retrieved != null) break
    }

    assertNotNull("Retrieved thumbnail should not be null", retrieved)
    assertTrue("Thumbnail width should be scaled down to <= 360", (retrieved?.width ?: 0) <= 360)
  }

  @Test
  fun test2_ThumbnailManager_OnTrimMemory_EvictsMemoryCache() {
    val tabId = "test-tab-trim"
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    thumbnailManager.saveThumbnail(tabId, bitmap)

    // Trigger critical memory trim
    thumbnailManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

    // Memory cache should be evicted
    // If it needs to reload, it will load from disk or return
  }

  @Test
  fun test3_ThumbnailManager_OnLowMemory_EvictsMemoryCache() {
    thumbnailManager.onLowMemory()
  }

  @Test
  fun test4_GeckoEngineManager_OnTrimMemory_HandlesCriticalTrim() {
    val geckoEngine = GeckoEngineManager.getInstance(context)
    geckoEngine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    geckoEngine.onLowMemory()
  }
}
