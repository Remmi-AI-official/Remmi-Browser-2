package com.remmi.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkRouteAuthority
import com.remmi.browser.security.NavigationSecurityAuthority
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI

object FaviconHelper {

  /**
   * Generates candidate URLs to fetch the site favicon / logo
   */
  fun getCandidateFaviconUrls(pageUrl: String): List<String> {
    val host = try {
      val uri = URI(pageUrl)
      val scheme = uri.scheme?.lowercase()
      if (scheme != "http" && scheme != "https") return emptyList()
      uri.host?.removePrefix("www.") ?: return emptyList()
    } catch (_: Exception) {
      return emptyList()
    }

    // Favicon fetching is network access just like navigation. Never let an
    // auxiliary request become an SSRF primitive against localhost/private LANs.
    // .onion remains subject to NetworkRouteAuthority's Tor gate below.
    val isOnion = NetworkRouteAuthority.isOnionDestination(pageUrl)
    if (host.isBlank() || host == "about:blank" || host == "remmi://newtab" ||
        NavigationSecurityAuthority.isPrivateOrLocalHost(host, isOnion)) {
      return emptyList()
    }

    return listOf(
      "https://$host/apple-touch-icon.png",
      "https://$host/favicon.ico"
    )
  }

  /**
   * Asynchronously fetches the favicon bitmap for a website
   */
  suspend fun fetchFaviconBitmap(pageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
    val candidates = getCandidateFaviconUrls(pageUrl)
    val ghostActive = CurrentTorRoute.isGhostActive

    for (candUrl in candidates) {
      try {
        // Favicon requests are auxiliary network traffic too. In Ghost mode, or for .onion
        // destinations, route them through the same verified Tor authority as the browser.
        val client = NetworkRouteAuthority.createHttpClient(
          isGhost = ghostActive,
          targetUrl = candUrl,
          connectTimeoutSeconds = 3L,
          readTimeoutSeconds = 3L,
          followRedirects = false
        )
        val request = Request.Builder()
          .url(candUrl)
          .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
          .build()

        client.newCall(request).execute().use { response ->
          if (response.isSuccessful) {
            val bytes = response.body?.bytes()
            if (bytes != null && bytes.isNotEmpty()) {
              val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
              if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                return@withContext bitmap
              }
            }
          }
        }
      } catch (_: Exception) {
        // Try next candidate. Route failures are intentionally fail-closed.
      }
    }
    null
  }

  /**
   * Generates a 192x192 adaptive launcher icon containing the real website logo or a fallback monogram.
   */
  fun generateLauncherIcon(
    context: Context,
    logoBitmap: Bitmap?,
    title: String,
    url: String,
    isInstallMode: Boolean
  ): Bitmap {
    val size = 192
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    val host = try {
      val uri = URI(url)
      uri.host?.removePrefix("www.") ?: title
    } catch (_: Exception) {
      title
    }

    // Outer Background Rounded Box
    val colorHue = (kotlin.math.abs(host.hashCode()) % 360).toFloat()
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      // Sleek cyber background
      color = Color.rgb(18, 24, 38)
    }

    val padding = 6f
    val rect = RectF(padding, padding, size - padding, size - padding)
    val cornerRadius = 40f
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

    // Glowing border outline
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3.5f
      color = if (isInstallMode) Color.rgb(0, 230, 255) else Color.rgb(0, 255, 204)
    }
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

    if (logoBitmap != null && !logoBitmap.isRecycled) {
      // Draw a subtle white / light background disc/plate for dark or transparent logos
      val platePadding = 26f
      val plateRect = RectF(platePadding, platePadding, size - platePadding, size - platePadding)
      val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(248, 250, 252)
      }
      val plateRadius = 28f
      canvas.drawRoundRect(plateRect, plateRadius, plateRadius, platePaint)

      // Draw the actual website logo centered inside the plate
      val iconInnerPadding = 36f
      val destRect = RectF(iconInnerPadding, iconInnerPadding, size - iconInnerPadding, size - iconInnerPadding)
      val srcRect = Rect(0, 0, logoBitmap.width, logoBitmap.height)
      val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
      }
      canvas.drawBitmap(logoBitmap, srcRect, destRect, iconPaint)
    } else {
      // Fallback: Letter Monogram
      val initial = (host.removePrefix("www.").firstOrNull() ?: title.firstOrNull() ?: 'W').uppercaseChar().toString()
      val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isInstallMode) Color.rgb(0, 230, 255) else Color.rgb(0, 255, 204)
        textSize = 80f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
      }
      val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f) - 4f
      canvas.drawText(initial, size / 2f, yPos, textPaint)
    }

    // Corner badge: "↓" for Install (Web App) vs "R" for Shortcut (Remmi Browser)
    val badgeRadius = 24f
    val badgeCx = size - 26f
    val badgeCy = size - 26f

    val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.rgb(10, 14, 23)
    }
    val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.5f
      color = if (isInstallMode) Color.rgb(0, 230, 255) else Color.rgb(0, 255, 204)
    }
    canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgeBgPaint)
    canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgeBorderPaint)

    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isInstallMode) Color.rgb(0, 230, 255) else Color.rgb(0, 255, 204)
      textSize = 22f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    val badgeYPos = badgeCy - ((badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f)
    val badgeSymbol = if (isInstallMode) "↓" else "R"
    canvas.drawText(badgeSymbol, badgeCx, badgeYPos, badgeTextPaint)

    return output
  }
}
