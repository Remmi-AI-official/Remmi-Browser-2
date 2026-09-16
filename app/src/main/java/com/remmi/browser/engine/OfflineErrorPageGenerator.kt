package com.remmi.browser.engine

import java.net.URI
import java.net.URLEncoder

object OfflineErrorPageGenerator {

  fun generate(
    targetUrl: String,
    errorCode: String = "ERR_INTERNET_DISCONNECTED",
    isDark: Boolean = true,
  ): String {
    val escapedTargetUrl = targetUrl.replace("\"", "&quot;").replace("'", "\\'")
    val isOnion = targetUrl.contains(".onion", ignoreCase = true)
    val isHttps = targetUrl.startsWith("https://", ignoreCase = true)
    val httpFallbackUrl = if (isOnion && isHttps && targetUrl.length > 8) {
      "http://" + targetUrl.substring(8)
    } else null
    val escapedHttpFallbackUrl = httpFallbackUrl?.replace("\"", "&quot;")?.replace("'", "\\'")

    val host = try {
      val parsed = URI(targetUrl)
      parsed.host ?: targetUrl
    } catch (_: Exception) {
      targetUrl
    }
    val escapedHost = host.replace("\"", "&quot;").replace("'", "\\'").take(50)

    val bg = if (isDark) "#0f172a" else "#f8fafc"
    val cardBg = if (isDark) "#1e293b" else "#ffffff"
    val cardBorder = if (isDark) "#334155" else "#e2e8f0"
    val textPrimary = if (isDark) "#f8fafc" else "#0f172a"
    val textSecondary = if (isDark) "#94a3b8" else "#64748b"
    val accentColor = if (isOnion) "#a855f7" else "#388bfd"
    val errCodeColor = if (isDark) "#64748b" else "#94a3b8"
    val buttonBg = if (isOnion) "#9333ea" else "#2563eb"
    val buttonText = "#ffffff"

    val isV2Onion = isOnion && (host.endsWith(".onion", ignoreCase = true) && host.substringBefore(".onion").length == 16)
    val title = if (isV2Onion) {
      "V2 Onion Deprecated"
    } else if (isOnion) {
      "Onion Site Unreachable"
    } else {
      "You're not connected"
    }
    val subtitle = if (isV2Onion) {
      "<strong>$escapedHost</strong> is an obsolete 16-character v2 Onion service. The Tor network permanently retired v2 services in 2021. Please use the modern 56-character v3 .onion address."
    } else if (isOnion) {
      "Could not establish a secure circuit to <strong>$escapedHost</strong> over the Tor network."
    } else {
      "And the web just isn't the same without you. Let's get you back online!"
    }

    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>$title</title>
  <style>
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      -webkit-tap-highlight-color: transparent;
    }
    body {
      background-color: $bg;
      color: $textPrimary;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
      padding: 32px 20px;
      line-height: 1.5;
    }
    .card {
      background-color: $cardBg;
      border: 1px solid $cardBorder;
      border-radius: 16px;
      max-width: 480px;
      width: 100%;
      padding: 32px 24px;
      box-shadow: 0 4px 20px rgba(0, 0, 0, ${if (isDark) "0.4" else "0.08"});
    }
    .icon-wrapper {
      margin-bottom: 20px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 56px;
      height: 56px;
      border-radius: 14px;
      background: ${if (isOnion) (if (isDark) "#2e1065" else "#f3e8ff") else (if (isDark) "#1e3a8a" else "#eff6ff")};
      color: $accentColor;
    }
    .title {
      font-size: 22px;
      font-weight: 700;
      letter-spacing: -0.3px;
      margin-bottom: 10px;
      color: $textPrimary;
    }
    .subtitle {
      font-size: 14.5px;
      color: $textSecondary;
      margin-bottom: 20px;
      line-height: 1.5;
    }
    .try-box {
      background: ${if (isDark) "#0f172a" else "#f1f5f9"};
      border-radius: 10px;
      padding: 14px 16px;
      margin-bottom: 20px;
      font-size: 13.5px;
      color: $textSecondary;
      line-height: 1.6;
    }
    .try-box strong {
      color: $textPrimary;
      display: block;
      margin-bottom: 6px;
      font-size: 14px;
    }
    .try-list {
      list-style-type: none;
      padding-left: 0;
    }
    .try-list li {
      position: relative;
      padding-left: 18px;
      margin-bottom: 4px;
    }
    .try-list li::before {
      content: "•";
      position: absolute;
      left: 4px;
      color: $accentColor;
      font-weight: bold;
    }
    .error-code {
      font-size: 11.5px;
      font-family: ui-monospace, "SF Mono", "Cascadia Code", Roboto, monospace;
      color: $errCodeColor;
      letter-spacing: 0.5px;
      margin-bottom: 20px;
      text-transform: uppercase;
    }
    .btn-group {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      padding: 12px 16px;
      font-size: 14px;
      font-weight: 600;
      border-radius: 10px;
      cursor: pointer;
      border: none;
      transition: opacity 0.15s ease, transform 0.1s ease;
      text-align: center;
    }
    .btn:active {
      transform: scale(0.98);
      opacity: 0.85;
    }
    .btn-primary {
      background-color: $buttonBg;
      color: $buttonText;
    }
    .btn-secondary {
      background-color: transparent;
      color: $textPrimary;
      border: 1px solid $cardBorder;
    }
    .btn-tertiary {
      background-color: transparent;
      color: $accentColor;
      font-size: 13.5px;
      padding: 8px;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.5; }
    }
    .loading {
      animation: pulse 1.2s infinite;
      pointer-events: none;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="icon-wrapper">
      ${if (isOnion) """
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2a10 10 0 0 1 10 10c0 5.523-4.477 10-10 10S2 17.523 2 12A10 10 0 0 1 12 2z"/>
        <path d="M12 6a6 6 0 0 1 6 6c0 3.314-2.686 6-6 6s-6-2.686-6-6a6 6 0 0 1 6-6z"/>
        <circle cx="12" cy="12" r="2"/>
      </svg>
      """ else """
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <line x1="2" y1="12" x2="22" y2="12"/>
        <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
      </svg>
      """}
    </div>

    <h1 class="title">$title</h1>
    <p class="subtitle">$subtitle</p>

    <div class="try-box">
      <strong>${if (isOnion) "Tor Onion Diagnosis:" else "Try:"}</strong>
      <ul class="try-list">
        ${if (isV2Onion) """
        <li>v2 onion domains (.onion with 16 characters) are deprecated and no longer routable on the Tor network.</li>
        <li>Search for the site's official 56-character v3 .onion link.</li>
        """ else if (isOnion) """
        <li>Onion hidden services can take 15-30 seconds to establish circuits.</li>
        <li>The onion host may be temporarily offline or under heavy traffic.</li>
        ${if (httpFallbackUrl != null) "<li>Hidden services are natively encrypted by Tor; HTTPS is often not configured.</li>" else ""}
        """ else """
        <li>Turning off airplane mode</li>
        <li>Turning on mobile data or Wi-Fi</li>
        <li>Checking the signal in your area</li>
        """}
      </ul>
    </div>

    <div class="error-code">$errorCode</div>

    <div class="btn-group">
      ${if (httpFallbackUrl != null) """
      <button class="btn btn-primary" onclick="tryHttpFallback()">
        OPEN VIA HTTP (TOR ENCRYPTED)
      </button>
      <button id="retryBtn" class="btn btn-secondary" onclick="retryNavigation()">
        RETRY HTTPS
      </button>
      """ else """
      <button id="retryBtn" class="btn btn-primary" onclick="retryNavigation()">
        ${if (isOnion) "RETRY ONION CIRCUIT" else "RELOAD PAGE"}
      </button>
      """}
      <button class="btn btn-tertiary" onclick="handleCancel()">
        Return to Home
      </button>
    </div>
  </div>

  <script>
    const targetUrl = "$escapedTargetUrl";
    const httpFallback = ${if (escapedHttpFallbackUrl != null) "\"$escapedHttpFallbackUrl\"" else "null"};
    let isRetrying = false;

    function retryNavigation() {
      if (isRetrying) return;
      isRetrying = true;
      const btn = document.getElementById('retryBtn');
      if (btn) {
        btn.textContent = "Connecting to circuit...";
        btn.classList.add('loading');
      }
      if (targetUrl && !targetUrl.startsWith("data:")) {
        window.location.href = targetUrl;
      } else {
        window.location.reload();
      }
    }

    function tryHttpFallback() {
      if (httpFallback) {
        window.location.href = httpFallback;
      }
    }

    function handleCancel() {
      if (window.history.length > 1) {
        window.history.back();
      } else {
        window.location.href = "about:home";
      }
    }

    window.addEventListener('online', function() {
      retryNavigation();
    });
  </script>
</body>
</html>
    """.trimIndent()
  }

  fun toDataUri(targetUrl: String, errorCode: String = "ERR_INTERNET_DISCONNECTED", isDark: Boolean = true): String {
    val html = generate(targetUrl, errorCode, isDark)
    val encoded = URLEncoder.encode(html, "UTF-8").replace("+", "%20")
    return "data:text/html;charset=utf-8,$encoded"
  }
}
