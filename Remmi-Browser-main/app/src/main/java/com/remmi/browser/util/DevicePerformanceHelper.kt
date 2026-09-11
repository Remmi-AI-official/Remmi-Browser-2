package com.remmi.browser.util

import android.app.ActivityManager
import android.content.Context

object DevicePerformanceHelper {
  @Volatile
  private var cachedIsLowEnd: Boolean? = null

  fun isLowEndDevice(context: Context): Boolean {
    cachedIsLowEnd?.let { return it }

    return try {
      val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val isLowRam = actManager?.isLowRamDevice == true
      val cores = Runtime.getRuntime().availableProcessors()
      val memInfo = ActivityManager.MemoryInfo()
      actManager?.getMemoryInfo(memInfo)
      val totalRamMb = memInfo.totalMem / (1024L * 1024L)

      val result = isLowRam || cores <= 4 || totalRamMb < 3500L
      cachedIsLowEnd = result
      result
    } catch (_: Exception) {
      false
    }.also { cachedIsLowEnd = it }
  }
}
