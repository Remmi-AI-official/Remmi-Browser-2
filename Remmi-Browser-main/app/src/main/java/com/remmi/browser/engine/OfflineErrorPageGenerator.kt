package com.remmi.browser.engine

import java.net.URLEncoder

object OfflineErrorPageGenerator {

  fun generate(
    targetUrl: String,
    errorCode: String = "ERR_INTERNET_DISCONNECTED",
    isDark: Boolean = true,
  ): String {
    val escapedTargetUrl = targetUrl.replace("\"", "&quot;").replace("'", "\\'")
    val bg = if (isDark) "#1f1f1f" else "#f9f9f9"
    val textPrimary = if (isDark) "#ffffff" else "#202124"
    val textSecondary = if (isDark) "#d0d0d0" else "#5f6368"
    val accentColor = "#388bfd"
    val errCodeColor = if (isDark) "#888888" else "#70757a"

    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>Offline • You're not connected</title>
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
      justify-content: flex-start;
      padding: 40px 24px 32px 24px;
      line-height: 1.5;
    }
    .container {
      max-width: 480px;
      width: 100%;
      margin: 0 auto;
    }
    .icon-wrapper {
      margin-bottom: 28px;
      display: inline-block;
    }
    .globe-icon {
      width: 72px;
      height: 72px;
      stroke: $textPrimary;
      stroke-width: 1.6;
      fill: none;
    }
    .title {
      font-size: 26px;
      font-weight: 700;
      letter-spacing: -0.3px;
      margin-bottom: 16px;
      color: $textPrimary;
    }
    .subtitle {
      font-size: 15.5px;
      color: $textSecondary;
      margin-bottom: 24px;
      line-height: 1.45;
    }
    .try-heading {
      font-size: 15.5px;
      font-weight: 600;
      margin-bottom: 12px;
      color: $textPrimary;
    }
    .try-list {
      list-style-type: none;
      margin-bottom: 32px;
    }
    .try-list li {
      position: relative;
      padding-left: 20px;
      margin-bottom: 8px;
      font-size: 15px;
      color: $textSecondary;
    }
    .try-list li::before {
      content: "•";
      position: absolute;
      left: 4px;
      top: -1px;
      font-size: 18px;
      color: $textSecondary;
    }
    .error-code {
      font-size: 11.5px;
      font-family: ui-monospace, "SF Mono", "Cascadia Code", Roboto, monospace;
      color: $errCodeColor;
      letter-spacing: 0.5px;
      margin-bottom: 10px;
      text-transform: uppercase;
    }
    .action-row {
      margin-top: 6px;
      margin-bottom: 14px;
    }
    .reload-btn {
      color: $accentColor;
      font-size: 15px;
      font-weight: 700;
      text-decoration: underline;
      letter-spacing: 0.2px;
      cursor: pointer;
      display: inline-block;
      text-transform: uppercase;
      padding: 4px 0;
    }
    .reload-btn:active {
      opacity: 0.7;
    }
    .footer-note {
      font-size: 14.5px;
      color: $textSecondary;
      line-height: 1.4;
    }
    .cancel-link {
      color: $accentColor;
      text-decoration: underline;
      cursor: pointer;
    }
    .cancel-link:active {
      opacity: 0.7;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.6; }
    }
    .loading-state {
      animation: pulse 1.5s infinite;
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="icon-wrapper">
      <svg class="globe-icon" viewBox="0 0 24 24">
        <!-- Globe circle -->
        <circle cx="12" cy="12" r="9.5"></circle>
        <!-- Equator line -->
        <line x1="2.5" y1="12" x2="21.5" y2="12"></line>
        <!-- Meridian loop -->
        <ellipse cx="12" cy="12" rx="4.5" ry="9.5"></ellipse>
        <!-- Disconnection Slash -->
        <circle cx="17.5" cy="17.5" r="4.5" fill="$bg" stroke="$textPrimary" stroke-width="1.6"></circle>
        <line x1="14.5" y1="14.5" x2="20.5" y2="20.5" stroke="$textPrimary" stroke-width="1.8" stroke-linecap="round"></line>
      </svg>
    </div>

    <h1 class="title">You're not connected</h1>

    <p class="subtitle">And the web just isn't the same without you. Let's get you back online!</p>

    <p class="try-heading">Try:</p>
    <ul class="try-list">
      <li>Turning off airplane mode</li>
      <li>Turning on mobile data or Wi-Fi</li>
      <li>Checking the signal in your area</li>
    </ul>

    <div class="error-code">$errorCode</div>

    <div class="action-row">
      <a id="reloadBtn" class="reload-btn" onclick="retryNavigation()">LOAD PAGE LATER</a>
    </div>

    <p class="footer-note">
      Remmi Browser will let you know when this page is ready. <a class="cancel-link" onclick="handleCancel()">Cancel</a>
    </p>
  </div>

  <script>
    const targetUrl = "$escapedTargetUrl";
    let isRetrying = false;

    function retryNavigation() {
      if (isRetrying) return;
      const btn = document.getElementById('reloadBtn');
      if (btn) {
        btn.textContent = "CHECKING CONNECTION...";
        btn.classList.add('loading-state');
      }
      isRetrying = true;
      if (targetUrl && targetUrl !== "" && !targetUrl.startsWith("data:")) {
        window.location.href = targetUrl;
      } else {
        window.location.reload();
      }
    }

    function handleCancel() {
      if (window.history.length > 1) {
        window.history.back();
      } else {
        window.location.href = "about:home";
      }
    }

    // Auto-reload as soon as internet connection is restored
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
