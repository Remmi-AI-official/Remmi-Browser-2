package com.remmi.browser.security.permissions

import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

object OriginCanonicalizer {

  /**
   * Canonicalize an exact origin from a URI string.
   * Returns "scheme://host[:port]" with normalized lowercase scheme and host.
   * Returns null if the URI is not a valid http, https, or wss origin.
   */
  fun canonicalize(rawUri: String?): String? {
    if (rawUri.isNullOrBlank()) return null
    return try {
      val uri = Uri.parse(rawUri.trim())
      val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
      if (scheme != "https" && scheme != "http" && scheme != "wss" && scheme != "ws") {
        return null
      }
      val host = uri.host?.lowercase(Locale.US) ?: return null
      if (host.isBlank()) return null

      val port = uri.port
      val isDefaultPort = (scheme == "http" && (port == -1 || port == 80)) ||
                          (scheme == "https" && (port == -1 || port == 443)) ||
                          (scheme == "ws" && (port == -1 || port == 80)) ||
                          (scheme == "wss" && (port == -1 || port == 443))

      if (port != -1 && !isDefaultPort) {
        "$scheme://$host:$port"
      } else {
        "$scheme://$host"
      }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Extracts displayable hostname or origin for dialogs.
   */
  fun getDisplayHost(rawUri: String?): String {
    val canonical = canonicalize(rawUri) ?: return "Unknown Site"
    return try {
      val uri = Uri.parse(canonical)
      val host = uri.host ?: canonical
      val port = uri.port
      if (port != -1 && port != 80 && port != 443) {
        "$host:$port"
      } else {
        host
      }
    } catch (_: Exception) {
      canonical
    }
  }

  /**
   * Generates a safe, non-sensitive hash of the origin for structured security diagnostics.
   */
  fun getOriginHash(origin: String?): String {
    if (origin.isNullOrBlank()) return "null"
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      val hashBytes = digest.digest(origin.toByteArray(Charsets.UTF_8))
      hashBytes.take(6).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
      "hash_err"
    }
  }

  /**
   * Verifies if two origins are strictly identical.
   */
  fun isSameExactOrigin(originA: String?, originB: String?): Boolean {
    val canA = canonicalize(originA) ?: return false
    val canB = canonicalize(originB) ?: return false
    return canA == canB
  }
}
