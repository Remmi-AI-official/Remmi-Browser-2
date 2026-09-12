package com.remmi.browser.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * TabThumbnailManager
 * Memory-efficient, lifecycle-aware thumbnail caching system for tab switcher.
 * - In-Memory LruCache for instant rendering
 * - Disk file persistence in app cache directory
 * - Reactive state flow to trigger Composable recompositions
 * - Debounced capture & duplicate capture skipping to eliminate GPU/UI churn
 * - Non-blocking async I/O and bitmap compression
 */
class TabThumbnailManager private constructor(private val context: Context) {

  private val mainScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
  private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val thumbnailDir = File(context.cacheDir, "tab_thumbnails").apply { mkdirs() }

  // LRU memory cache: 1/8th of available app memory
  private val memoryCache: LruCache<String, Bitmap>

  // Version tracker to notify Jetpack Compose when a thumbnail is updated
  private val _thumbnailVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
  val thumbnailVersions: StateFlow<Map<String, Long>> = _thumbnailVersions.asStateFlow()

  // Concurrency & Debounce controls
  private val pendingCaptureJobs = ConcurrentHashMap<String, Job>()
  private val inFlightCaptures = ConcurrentHashMap.newKeySet<String>()
  private val lastCaptureTime = ConcurrentHashMap<String, Long>()

  // Metric counters for diagnostics & testing
  val captureRequestCounter = java.util.concurrent.atomic.AtomicInteger(0)
  val captureExecutedCounter = java.util.concurrent.atomic.AtomicInteger(0)
  val captureSkippedCounter = java.util.concurrent.atomic.AtomicInteger(0)

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    val cacheSizeKb = (maxMemoryKb / 16).coerceIn(1024 * 4, 1024 * 16) // 4MB to 16MB max
    memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
      override fun sizeOf(key: String, bitmap: Bitmap): Int {
        return bitmap.byteCount / 1024
      }
    }
  }

  fun onTrimMemory(level: Int) {
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
        level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
      Log.d(TAG, "onTrimMemory level=$level: Evicting thumbnail memory cache")
      memoryCache.evictAll()
    } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
               level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
      val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
      val halfSize = (maxMemoryKb / 32).coerceIn(1024 * 2, 1024 * 8)
      Log.d(TAG, "onTrimMemory level=$level: Trimming thumbnail memory cache to ${halfSize}KB")
      memoryCache.trimToSize(halfSize)
    }
  }

  fun onLowMemory() {
    Log.d(TAG, "onLowMemory: Evicting thumbnail memory cache")
    memoryCache.evictAll()
  }

  fun getThumbnail(tabId: String): Bitmap? {
    // 1. Check in-memory LRU Cache
    val cached = memoryCache.get(tabId)
    if (cached != null && !cached.isRecycled) {
      return cached
    }

    // 2. Try loading from disk synchronously if exists (lightweight RGB_565 thumbnail)
    val file = File(thumbnailDir, "thumb_$tabId.jpg")
    if (file.exists() && file.length() > 0) {
      try {
        val options = BitmapFactory.Options().apply {
          inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        if (bitmap != null) {
          memoryCache.put(tabId, bitmap)
          return bitmap
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to load disk thumbnail for $tabId: ${e.message}")
      }
    }
    return null
  }

  fun saveThumbnail(tabId: String, rawBitmap: Bitmap) {
    if (rawBitmap.isRecycled) return

    ioScope.launch(Dispatchers.Default) {
      try {
        val scaled = scaleToThumbnail(rawBitmap, TARGET_WIDTH)
        // Promptly release large raw capture buffer (can be ~10MB uncompressed)
        if (scaled !== rawBitmap && !rawBitmap.isRecycled) {
          try { rawBitmap.recycle() } catch (_: Throwable) {}
        }
        memoryCache.put(tabId, scaled)

        // Update reactive version trigger on main dispatcher
        val now = System.currentTimeMillis()
        _thumbnailVersions.value = _thumbnailVersions.value + (tabId to now)

        // Asynchronously persist compressed JPEG to disk
        withContext(Dispatchers.IO) {
          val file = File(thumbnailDir, "thumb_$tabId.jpg")
          FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to process/save thumbnail for $tabId: ${e.message}")
      }
    }
  }

  /**
   * Request a thumbnail capture for the given tab and GeckoView.
   * Debounces repeated calls (e.g. from NAV_STOP, multiple resource loads) and skips redundant captures.
   */
  fun captureGeckoView(
    tabId: String,
    geckoView: GeckoView,
    debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    force: Boolean = false
  ) {
    captureRequestCounter.incrementAndGet()

    val now = android.os.SystemClock.elapsedRealtime()
    val lastTime = lastCaptureTime[tabId] ?: 0L

    // If already captured very recently and not forced, debounce or skip
    if (!force && debounceMs > 0 && (now - lastTime) < MIN_CAPTURE_INTERVAL_MS) {
      captureSkippedCounter.incrementAndGet()
      // Cancel previous pending and schedule at remaining interval
      val remaining = (MIN_CAPTURE_INTERVAL_MS - (now - lastTime)).coerceAtLeast(debounceMs)
      scheduleDebouncedCapture(tabId, geckoView, remaining)
      return
    }

    if (debounceMs <= 0 || force) {
      // Immediate execution (e.g. tab switcher opened or view releasing)
      pendingCaptureJobs.remove(tabId)?.cancel()
      executeCapture(tabId, geckoView)
    } else {
      scheduleDebouncedCapture(tabId, geckoView, debounceMs)
    }
  }

  private fun scheduleDebouncedCapture(tabId: String, geckoView: GeckoView, debounceMs: Long) {
    pendingCaptureJobs.remove(tabId)?.cancel()
    val weakRef = WeakReference(geckoView)
    val job = mainScope.launch {
      delay(debounceMs)
      pendingCaptureJobs.remove(tabId)
      val gv = weakRef.get() ?: return@launch
      executeCapture(tabId, gv)
    }
    pendingCaptureJobs[tabId] = job
  }

  private fun executeCapture(tabId: String, geckoView: GeckoView) {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
      mainScope.launch {
        executeCapture(tabId, geckoView)
      }
      return
    }

    val session = geckoView.session
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
    val isOpen = session?.isOpen == true
    val now = android.os.SystemClock.elapsedRealtime()

    if (!isOpen) {
      return
    }

    // Check if capture is already in-flight for this tab to avoid GPU/hardware readback stalls
    if (!inFlightCaptures.add(tabId)) {
      captureSkippedCounter.incrementAndGet()
      Log.d(TAG, "Skipping capture for tab $tabId: capture already in flight")
      return
    }

    captureExecutedCounter.incrementAndGet()
    val msg = "[FORENSIC][CAPTURE_PIXELS] tabId=$tabId view=$gvId session=$sessId isOpen=$isOpen elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)

    try {
      geckoView.capturePixels()
        .accept(
          { bitmap ->
            inFlightCaptures.remove(tabId)
            lastCaptureTime[tabId] = android.os.SystemClock.elapsedRealtime()
            if (bitmap != null && !bitmap.isRecycled) {
              saveThumbnail(tabId, bitmap)
            }
          },
          { error ->
            inFlightCaptures.remove(tabId)
            Log.d(TAG, "capturePixels notice on tab $tabId: ${error?.message}")
          }
        )
    } catch (e: Exception) {
      inFlightCaptures.remove(tabId)
      Log.d(TAG, "captureGeckoView error on tab $tabId: ${e.message}")
    }
  }

  fun removeThumbnail(tabId: String) {
    pendingCaptureJobs.remove(tabId)?.cancel()
    inFlightCaptures.remove(tabId)
    lastCaptureTime.remove(tabId)
    memoryCache.remove(tabId)
    _thumbnailVersions.value = _thumbnailVersions.value - tabId
    ioScope.launch {
      try {
        val file = File(thumbnailDir, "thumb_$tabId.jpg")
        if (file.exists()) file.delete()
      } catch (_: Exception) {}
    }
  }

  fun clearAll() {
    pendingCaptureJobs.values.forEach { it.cancel() }
    pendingCaptureJobs.clear()
    inFlightCaptures.clear()
    lastCaptureTime.clear()
    memoryCache.evictAll()
    _thumbnailVersions.value = emptyMap()
    ioScope.launch {
      try {
        thumbnailDir.listFiles()?.forEach { it.delete() }
      } catch (_: Exception) {}
    }
  }

  private fun scaleToThumbnail(source: Bitmap, targetWidth: Int): Bitmap {
    if (source.width <= targetWidth && source.height <= targetWidth * 2) {
      return source
    }
    val aspect = source.height.toFloat() / source.width.toFloat()
    val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
  }

  companion object {
    private const val TAG = "TabThumbnailManager"
    private const val TARGET_WIDTH = 360
    const val DEFAULT_DEBOUNCE_MS = 600L
    const val MIN_CAPTURE_INTERVAL_MS = 1500L

    @Volatile
    private var INSTANCE: TabThumbnailManager? = null

    fun getInstance(context: Context): TabThumbnailManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: TabThumbnailManager(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
