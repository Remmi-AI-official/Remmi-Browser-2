package com.remmi.browser.security

import com.remmi.browser.security.crypto.PasswordCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PasswordVaultSecurityTest {

  @Test
  fun testPinValidation() {
    assertTrue(PasswordCryptoEngine.isPinValid("74918293".toCharArray())) // 8 digits
    assertTrue(PasswordCryptoEngine.isPinValid("93847281".toCharArray())) // 8 digits
    assertFalse(PasswordCryptoEngine.isPinValid("749182".toCharArray())) // 6 digits (< 8 digits)
    assertFalse(PasswordCryptoEngine.isPinValid("12345678".toCharArray())) // sequential pattern
    assertFalse(PasswordCryptoEngine.isPinValid("11111111".toCharArray())) // repeating digits
    assertFalse(PasswordCryptoEngine.isPinValid("12121212".toCharArray())) // periodic pattern
    assertFalse(PasswordCryptoEngine.isPinValid("123".toCharArray())) // too short (< 8 digits)
    assertFalse(PasswordCryptoEngine.isPinValid("7491829a".toCharArray())) // non-digit
  }

  @Test
  fun testKeyDerivationAndAesGcmRoundtrip() {
    val masterPassword = "TestMasterPassword!2026".toCharArray()
    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(masterPassword)

    val testData = "super_sensitive_api_key_42".toByteArray(StandardCharsets.UTF_8)
    val ciphertext = PasswordCryptoEngine.encryptAesGcm(kdfResult.kek, testData)

    val decrypted = PasswordCryptoEngine.decryptAesGcm(kdfResult.kek, ciphertext)
    assertEquals("super_sensitive_api_key_42", String(decrypted, StandardCharsets.UTF_8))
  }

  @Test
  fun testVerifierComputation() {
    val masterPassword = "VaultAccessKey#999".toCharArray()
    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(masterPassword)

    val (verifier, salt) = PasswordCryptoEngine.computeVerifier(kdfResult.kek)
    assertTrue(PasswordCryptoEngine.verifyKek(kdfResult.kek, verifier, salt))

    val wrongKek = PasswordCryptoEngine.generateSecureRandomBytes(32)
    assertFalse(PasswordCryptoEngine.verifyKek(wrongKek, verifier, salt))
  }

  @Test
  fun testExactOriginCanonicalization() {
    assertEquals("https://example.com", PasswordCryptoEngine.canonicalizeOrigin("https://example.com/login"))
    assertEquals("https://example.com", PasswordCryptoEngine.canonicalizeOrigin("https://example.com:443/login"))
    assertEquals("https://example.com:8443", PasswordCryptoEngine.canonicalizeOrigin("https://example.com:8443/login"))
    assertEquals("https://sub.example.com", PasswordCryptoEngine.canonicalizeOrigin("https://sub.example.com/"))
    assertEquals(null, PasswordCryptoEngine.canonicalizeOrigin("http://example.com/login"))
  }

  @Test
  fun testSiteHashingConsistency() {
    val hash1 = PasswordCryptoEngine.hashSiteUrl("https://google.com/search")
    val hash2 = PasswordCryptoEngine.hashSiteUrl("https://google.com:443/login")
    val hash3 = PasswordCryptoEngine.hashSiteUrl("https://www.google.com/login")
    assertEquals(hash1, hash2)
    assertNotEquals(hash1, hash3) // Subdomains must have distinct hashes in exact-origin mode
  }
}
