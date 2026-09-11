package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.engine.GeckoPreferenceController
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardeningAuditVerificationTest {

  private lateinit var context: Context
  private val moshi = Moshi.Builder().build()
  private val adapter = moshi.adapter(TorCheckApiResponse::class.java)

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    CurrentTorRoute.clearRoute()
    GeckoPreferenceController.resetCache()
  }

  @After
  fun tearDown() {
    CurrentTorRoute.clearRoute()
    GeckoPreferenceController.resetCache()
  }

  @Test
  fun testTorCheckApiResponse_validTor() {
    val json = """{"IsTor": true, "IP": "185.220.101.5"}"""
    val parsed = adapter.fromJson(json)
    assertTrue(parsed?.isTor == true)
    assertEquals("185.220.101.5", parsed?.ip)
  }

  @Test
  fun testTorCheckApiResponse_notTor() {
    val json = """{"IsTor": false, "IP": "1.2.3.4"}"""
    val parsed = adapter.fromJson(json)
    assertFalse(parsed?.isTor == true)
    assertEquals("1.2.3.4", parsed?.ip)
  }

  @Test
  fun testTorCheckApiResponse_evasionWithSubstringDoesNotFoolMoshi() {
    // Attack vector: response body contains substring "\"IsTor\":true" in an unverified field
    val json = """{"IsTor": false, "IP": "1.2.3.4", "Comment": "Spoofing \"IsTor\":true"}"""
    val parsed = adapter.fromJson(json)
    // Moshi strictly parses the actual boolean property "IsTor"
    assertFalse(parsed?.isTor == true)
  }

  @Test
  fun testTorCheckApiResponse_missingFieldsDefaults() {
    val json = """{}"""
    val parsed = adapter.fromJson(json)
    assertFalse(parsed?.isTor == true)
    assertNull(parsed?.ip)
  }

  @Test
  fun testGeckoPreferenceController_resetCacheClearsState() {
    val controller = GeckoPreferenceController(null)
    GeckoPreferenceController.resetCache()
    // Verifies resetCache can be invoked without exception across lifecycle transitions
    GeckoPreferenceController.resetCache(null)
  }

  @Test
  fun testClipboardManager_failsClosedInGhostModeWithoutTor() = runBlocking {
    CurrentTorRoute.clearRoute()
    val clipboardManager = ClipboardManager(context)
    // When ghost mode is active or onion address is requested with no verified Tor route,
    // copyImageDirect fails closed and returns false instead of leaking clearnet traffic
    val result = clipboardManager.copyImageDirect("http://expyuz5wqqgahqda.onion/image.png")
    assertFalse("Must fail-closed for unrouted onion/ghost image download", result)
  }
}
