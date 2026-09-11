package com.remmi.browser.security

import com.remmi.browser.security.permissions.OriginCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OriginCanonicalizerUnitTest {

  @Test
  fun testExactOriginNormalization() {
    assertEquals("https://example.com", OriginCanonicalizer.canonicalize("https://example.com/"))
    assertEquals("https://example.com", OriginCanonicalizer.canonicalize("https://example.com/path/to/page?q=1#sec"))
    assertEquals("http://example.com", OriginCanonicalizer.canonicalize("http://EXAMPLE.COM/"))
    assertEquals("https://example.com:8443", OriginCanonicalizer.canonicalize("https://example.com:8443/"))
  }

  @Test
  fun testExactOriginDistinction() {
    // Scheme mismatch
    assertFalse(OriginCanonicalizer.isSameExactOrigin("https://example.com", "http://example.com"))

    // Subdomain mismatch
    assertFalse(OriginCanonicalizer.isSameExactOrigin("https://example.com", "https://www.example.com"))
    assertFalse(OriginCanonicalizer.isSameExactOrigin("https://example.com", "https://sub.example.com"))

    // Port mismatch
    assertFalse(OriginCanonicalizer.isSameExactOrigin("https://example.com", "https://example.com:8443"))
    assertFalse(OriginCanonicalizer.isSameExactOrigin("http://example.com:8080", "http://example.com:80"))

    // Identical
    assertTrue(OriginCanonicalizer.isSameExactOrigin("https://example.com/a", "https://EXAMPLE.COM/b/c?x=1"))
  }

  @Test
  fun testInsecureAndNonWebSchemesRejected() {
    assertNull(OriginCanonicalizer.canonicalize("javascript:alert(1)"))
    assertNull(OriginCanonicalizer.canonicalize("data:text/html,<h1>hi</h1>"))
    assertNull(OriginCanonicalizer.canonicalize("file:///sdcard/test.txt"))
    assertNull(OriginCanonicalizer.canonicalize("about:blank"))
    assertNull(OriginCanonicalizer.canonicalize(""))
    assertNull(OriginCanonicalizer.canonicalize(null))
  }

  @Test
  fun testOriginHashNonSensitive() {
    val hash1 = OriginCanonicalizer.getOriginHash("https://example.com")
    val hash2 = OriginCanonicalizer.getOriginHash("https://example.com")
    val hash3 = OriginCanonicalizer.getOriginHash("https://other.org")

    assertNotNull(hash1)
    assertEquals(hash1, hash2)
    assertFalse(hash1 == hash3)
    // Hash should not reveal full raw url
    assertFalse(hash1.contains("https"))
  }
}
