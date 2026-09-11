package com.remmi.browser.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.remmi.browser.MainActivity
import com.remmi.browser.ui.webapp.WebAppActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

object ShortcutHelper {

  fun isShortcutSupported(context: Context): Boolean {
    return ShortcutManagerCompat.isRequestPinShortcutSupported(context)
  }

  fun createHomeScreenShortcut(
    context: Context,
    url: String,
    title: String,
    isInstallMode: Boolean = false,
    onSuccess: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
  ) {
    val targetUrl = url.trim()
    if (targetUrl.isBlank() || targetUrl == "about:blank" || targetUrl == "remmi://newtab") {
      val errorMsg = "Cannot create shortcut for blank page"
      Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
      onError?.invoke(errorMsg)
      return
    }

    val displayTitle = title.trim().ifBlank {
      try {
        val uri = URI(targetUrl)
        uri.host?.removePrefix("www.") ?: "Web App"
      } catch (_: Exception) {
        "Web App"
      }
    }

    // Launch in background to fetch favicon and create the pin shortcut with real logo
    CoroutineScope(Dispatchers.Main).launch {
      try {
        val logoBitmap = withContext(Dispatchers.IO) {
          FaviconHelper.fetchFaviconBitmap(targetUrl)
        }

        val shortcutIntent = if (isInstallMode) {
          // Dedicated WebAppActivity: launches as standalone Web App outside the browser!
          Intent(context, WebAppActivity::class.java).apply {
            action = "com.remmi.browser.ACTION_OPEN_WEBAPP"
            data = Uri.parse(targetUrl)
            putExtra("EXTRA_URL", targetUrl)
            putExtra("EXTRA_TITLE", displayTitle)
            putExtra("EXTRA_IS_STANDALONE", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
          }
        } else {
          // Standard Browser Shortcut: opens in Remmi Browser
          Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(targetUrl)
            putExtra("EXTRA_URL", targetUrl)
            putExtra("EXTRA_TITLE", displayTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
          }
        }

        val iconBitmap = FaviconHelper.generateLauncherIcon(
          context = context,
          logoBitmap = logoBitmap,
          title = displayTitle,
          url = targetUrl,
          isInstallMode = isInstallMode
        )
        val iconCompat = IconCompat.createWithBitmap(iconBitmap)
        val shortcutId = (if (isInstallMode) "pwa_" else "shortcut_") + (targetUrl.hashCode() and 0x7FFFFFFF)

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
          val pinShortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(displayTitle.take(25))
            .setLongLabel(displayTitle)
            .setIcon(iconCompat)
            .setIntent(shortcutIntent)
            .setAlwaysBadged()
            .build()

          val success = ShortcutManagerCompat.requestPinShortcut(context, pinShortcutInfo, null)
          if (success) {
            val msg = if (isInstallMode) "Installed '$displayTitle' on Home screen" else "Added shortcut for '$displayTitle'"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onSuccess?.invoke()
          } else {
            val errorMsg = "Could not request shortcut pinning"
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
            onError?.invoke(errorMsg)
          }
        } else {
          // Fallback for older launcher broadcasts
          @Suppress("DEPRECATION")
          val addIntent = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            putExtra(Intent.EXTRA_SHORTCUT_NAME, displayTitle)
            putExtra(Intent.EXTRA_SHORTCUT_ICON, iconBitmap)
            putExtra("duplicate", false)
          }
          context.sendBroadcast(addIntent)
          val msg = if (isInstallMode) "Installed '$displayTitle' on Home screen" else "Added shortcut for '$displayTitle'"
          Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
          onSuccess?.invoke()
        }
      } catch (e: Exception) {
        val errorMsg = "Failed to create shortcut: ${e.message}"
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
        onError?.invoke(errorMsg)
      }
    }
  }
}
