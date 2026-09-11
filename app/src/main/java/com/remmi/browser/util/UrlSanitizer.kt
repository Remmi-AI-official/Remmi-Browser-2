package com.remmi.browser.util

import android.net.Uri
import java.security.MessageDigest

/**
 * Utility for sanitizing and hashing sensitive URLs before logging.
 * Prevents raw authorization signatures, temporary download tokens,
 * credentials, and signed URLs from appearing in forensic/debug logs.
 */
object UrlSanitizer {

  fun sha256(input: String?): String {
    if (input.isNullOrBlank()) return ""
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
      hashBytes.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
      ""
    }
  }

  fun extractHost(url: String?): String {
    if (url.isNullOrBlank()) return "unknown"
    return try {
      val uri = Uri.parse(url)
      val host = uri.host
      if (!host.isNullOrBlank()) host else "unknown"
    } catch (_: Exception) {
      "unknown"
    }
  }

  /**
   * Identifies whether a URL points to signed cloud storage, temporary authorization,
   * or contains sensitive signatures and tokens in its query or path.
   */
  fun isSensitiveOrSignedUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()

    val isSignedStorageHost = lower.contains("googleusercontent.com") ||
      lower.contains("s3.amazonaws.com") ||
      lower.contains("storage.googleapis.com") ||
      lower.contains("blob.core.windows.net") ||
      lower.contains("cloudfront.net") ||
      lower.contains("digitaloceanspaces.com")

    val hasSignatureOrToken = lower.contains("x-amz-signature") ||
      lower.contains("x-amz-security-token") ||
      lower.contains("x-goog-signature") ||
      lower.contains("signature=") ||
      lower.contains("sig=") ||
      lower.contains("token=") ||
      lower.contains("auth=") ||
      lower.contains("access_token=") ||
      lower.contains("key-pair-id=") ||
      lower.contains("temp-url-sig=")

    val isAuthPath = lower.contains("/oauth") ||
      lower.contains("/auth/") ||
      lower.contains("/login/") ||
      lower.contains("/signin/") ||
      lower.contains("/session/")

    return isSignedStorageHost || hasSignatureOrToken || isAuthPath
  }

  /**
   * Format for COMPLETED_DOWNLOAD log details:
   * downloadUrl=<REDACTED> downloadHost=$host urlHash=$hash
   */
  fun formatCompletedDownloadLog(downloadUrl: String?, visiblePageUrl: String?): String {
    val host = extractHost(downloadUrl)
    val hash = sha256(downloadUrl)
    val visibleHost = visiblePageUrl?.let { extractHost(it) } ?: "none"
    return "downloadUrl=<REDACTED> downloadHost=$host urlHash=$hash visiblePageHost=$visibleHost"
  }

  /**
   * Format for NAV_EXTERNAL_RESPONSE log details:
   * urlHost=$host urlHash=$hash
   */
  fun formatExternalResponseLog(tabId: String, uri: String?, revertedUrl: String?): String {
    val urlHost = extractHost(uri)
    val urlHash = sha256(uri)
    val revertedUrlHost = revertedUrl?.let { extractHost(it) } ?: "none"
    return "[FORENSIC] [NAV_EXTERNAL_RESPONSE] tabId=$tabId urlHost=$urlHost urlHash=$urlHash revertedUrlHost=$revertedUrlHost"
  }
}
