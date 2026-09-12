package com.remmi.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.util.DebugLogManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoggingOptimizationTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    DebugLogManager.init(context)
    DebugLogManager.clear()
  }

  @Test
  fun testSanitizeFastPathNonSensitiveString() {
    val simpleMsg = "Tab switched to tab_123 successfully"
    val result = DebugLogManager.sanitize(simpleMsg)
    assertEquals(simpleMsg, result)
  }

  @Test
  fun testSanitizeRedactsQueryAndSecrets() {
    val sensitiveUrl = "https://example.com/login?user=alice&password=secret123"
    val result = DebugLogManager.sanitize(sensitiveUrl)
    assertTrue("Should redact query params", result.contains("?[REDACTED_QUERY]"))
    assertFalse("Must not contain plain password", result.contains("secret123"))

    val authHeader = "Authorization: Bearer mySecretToken123456"
    val authResult = DebugLogManager.sanitize(authHeader)
    assertTrue("Should redact auth", authResult.contains("[REDACTED]"))
    assertFalse("Must not contain secret token", authResult.contains("mySecretToken123456"))
  }

  @Test
  fun testSanitizeDownloadUrlRedaction() {
    val downloadMsg = "Download request downloadUrl=https://storage.example.com/files/document.pdf"
    val result = DebugLogManager.sanitize(downloadMsg)
    assertTrue("Should redact downloadUrl", result.contains("downloadUrl=<REDACTED>"))
    assertTrue("Should include downloadHost", result.contains("downloadHost=storage.example.com"))
  }

  @Test
  fun testDiagnosticLogBuffering() {
    DebugLogManager.log("[FORENSIC] [NAV_PAGE_START] tabId=tab_1 session=0x123 url=https://example.com")
    val events = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Milestone logs must be buffered", events.any { it.contains("[NAV_PAGE_START]") })
  }
}
