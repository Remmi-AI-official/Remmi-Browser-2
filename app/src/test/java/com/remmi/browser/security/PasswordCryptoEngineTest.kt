package com.remmi.browser.security

import com.remmi.browser.security.crypto.PasswordBackupManager
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import com.remmi.browser.storage.PasswordEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordCryptoEngineTest {

  @Test
  fun testArgon2idKeyDerivationAndVerifier() {
    val masterPassword = "Cyber#Security!2026Pass".toCharArray()
    val salt = PasswordCryptoEngine.generateSecureRandomBytes(PasswordCryptoEngine.SALT_LENGTH_BYTES)

    // 1. Derive key
    val derivationResult = PasswordCryptoEngine.deriveKeyEncryptionKey(masterPassword, salt)
    assertNotNull(derivationResult.kek)
    assertEquals(32, derivationResult.kek.size)

    // 2. Generate verifier
    val (verifier, verifierSalt) = PasswordCryptoEngine.computeVerifier(derivationResult.kek)
    assertNotNull(verifier)
    assertEquals(32, verifier.size)

    // 3. Verify valid key
    val isValid = PasswordCryptoEngine.verifyKek(derivationResult.kek, verifier, verifierSalt)
    assertTrue("Key verifier must validate correct derived key", isValid)

    // 4. Verify invalid key fails
    val wrongKey = ByteArray(32) { (it + 1).toByte() }
    val isWrongValid = PasswordCryptoEngine.verifyKek(wrongKey, verifier, verifierSalt)
    assertFalse("Wrong key must fail verification", isWrongValid)
  }

  @Test
  fun testAes256GcmEncryptionDecryption() {
    val key = ByteArray(32) { it.toByte() }
    val plaintextPassword = "SuperSecretBankPassword#999"
    val plaintextBytes = plaintextPassword.toByteArray(StandardCharsets.UTF_8)

    // Encrypt
    val encrypted = PasswordCryptoEngine.encryptAesGcm(key, plaintextBytes)
    assertNotNull(encrypted.iv)
    assertEquals(12, encrypted.iv.size)
    assertNotNull(encrypted.ciphertext)
    assertNotNull(encrypted.authTag)
    assertEquals(16, encrypted.authTag.size)

    // Decrypt
    val decryptedBytes = PasswordCryptoEngine.decryptAesGcm(key, encrypted.ciphertext, encrypted.iv, encrypted.authTag)
    assertEquals(plaintextPassword, String(decryptedBytes, StandardCharsets.UTF_8))

    // Zeroize test
    PasswordCryptoEngine.zeroize(decryptedBytes)
    assertEquals(0, decryptedBytes[0].toInt())
  }

  @Test(expected = Exception::class)
  fun testAes256GcmTamperedCiphertextThrows() {
    val key = ByteArray(32) { it.toByte() }
    val plaintextBytes = "SecretPayloadData".toByteArray(StandardCharsets.UTF_8)

    val encrypted = PasswordCryptoEngine.encryptAesGcm(key, plaintextBytes)

    // Tamper with ciphertext
    val tamperedCiphertext = encrypted.ciphertext.clone()
    tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

    // Should throw due to GCM authentication tag mismatch
    PasswordCryptoEngine.decryptAesGcm(key, tamperedCiphertext, encrypted.iv, encrypted.authTag)
  }

  @Test
  fun testPasswordGeneratorAndStrength() {
    val generated = PasswordCryptoEngine.generatePassword(
      length = 24,
      includeUpper = true,
      includeLower = true,
      includeDigits = true,
      includeSymbols = true
    )
    assertEquals(24, generated.length)

    val strength = PasswordCryptoEngine.evaluatePasswordStrength(generated.toCharArray())
    assertTrue("24-char diverse password must have high strength score (>=80)", strength.score >= 80)
    assertTrue("Must satisfy min length", strength.hasMinLength)
    assertTrue("Must have uppercase", strength.hasUppercase)
    assertTrue("Must have lowercase", strength.hasLowercase)
    assertTrue("Must have digit", strength.hasDigit)
    assertTrue("Must have symbol", strength.hasSymbol)
  }

  @Test
  fun testEncryptedBackupExportAndImport() {
    val backupPassword = "BackupMasterSecurePassword!888".toCharArray()
    val dek = PasswordCryptoEngine.generateSecureRandomBytes(32)
    val entries = listOf(
      PasswordEntryEntity(
        id = 1L,
        siteUrlHash = "https://bank.cyber.local".hashCode().toString(),
        siteUrlEncrypted = "https://bank.cyber.local".toByteArray(),
        usernameEncrypted = "agent_007".toByteArray(),
        passwordEncrypted = "TopSecretBank123!".toByteArray(),
        notesEncrypted = "Primary bank login".toByteArray(),
        iv = ByteArray(12),
        authTag = ByteArray(16),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
      ),
      PasswordEntryEntity(
        id = 2L,
        siteUrlHash = "https://mail.cyber.local".hashCode().toString(),
        siteUrlEncrypted = "https://mail.cyber.local".toByteArray(),
        usernameEncrypted = "secops@remmi.local".toByteArray(),
        passwordEncrypted = "MailSecret999!".toByteArray(),
        notesEncrypted = "Encrypted mail server".toByteArray(),
        iv = ByteArray(12),
        authTag = ByteArray(16),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
      )
    )

    // Export
    val backupJsonString = PasswordBackupManager.exportEncryptedBackup(entries, backupPassword, dek)
    assertNotNull(backupJsonString)
    assertTrue(backupJsonString.contains("remmi_vault_backup"))

    // Import
    val (importedDek, importedEntries) = PasswordBackupManager.importEncryptedBackup(backupJsonString, backupPassword, dek)
    assertEquals(2, importedEntries.size)
    assertEquals("https://bank.cyber.local".hashCode().toString(), importedEntries[0].siteUrlHash)
    assertEquals("agent_007", String(importedEntries[0].usernameEncrypted))
    assertEquals("secops@remmi.local", String(importedEntries[1].usernameEncrypted))
    assertNotNull(importedDek)
  }

  @Test
  fun testPinValidationAndKekDerivation() {
    val valid8DigitPin = "83749215".toCharArray()
    val valid12DigitPin = "948271635019".toCharArray()
    val shortPin = "8492011".toCharArray() // 7 digits (< 8)
    val nonDigitPin = "1234a5678".toCharArray()
    val longPin = "1234567890123".toCharArray() // 13 digits (> 12)
    val ascendingPin = "12345678".toCharArray()
    val descendingPin = "87654321".toCharArray()
    val repeatedPin = "77777777".toCharArray()
    val periodicPin2 = "12121212".toCharArray()
    val periodicPin3 = "12312312".toCharArray()

    assertTrue("8 digit non-pattern PIN must be valid", PasswordCryptoEngine.isPinValid(valid8DigitPin))
    assertTrue("12 digit non-pattern PIN must be valid", PasswordCryptoEngine.isPinValid(valid12DigitPin))
    assertFalse("Short PIN (<8) must be invalid", PasswordCryptoEngine.isPinValid(shortPin))
    assertFalse("Non-digit PIN must be invalid", PasswordCryptoEngine.isPinValid(nonDigitPin))
    assertFalse("Long PIN (>12) must be invalid", PasswordCryptoEngine.isPinValid(longPin))
    assertFalse("Ascending PIN must be rejected", PasswordCryptoEngine.isPinValid(ascendingPin))
    assertFalse("Descending PIN must be rejected", PasswordCryptoEngine.isPinValid(descendingPin))
    assertFalse("Repeated PIN must be rejected", PasswordCryptoEngine.isPinValid(repeatedPin))
    assertFalse("Periodic length 2 PIN must be rejected", PasswordCryptoEngine.isPinValid(periodicPin2))
    assertFalse("Periodic length 3 PIN must be rejected", PasswordCryptoEngine.isPinValid(periodicPin3))

    val salt = PasswordCryptoEngine.generateSecureRandomBytes(PasswordCryptoEngine.SALT_LENGTH_BYTES)
    val pinDerivation = PasswordCryptoEngine.deriveKeyEncryptionKey(valid8DigitPin, salt)
    assertNotNull(pinDerivation.kek)
    assertEquals(32, pinDerivation.kek.size)

    val (verifier, verifierSalt) = PasswordCryptoEngine.computeVerifier(pinDerivation.kek)
    assertTrue("PIN KEK verifier should pass with correct KEK", PasswordCryptoEngine.verifyKek(pinDerivation.kek, verifier, verifierSalt))
  }

  @Test
  fun testExactOriginCanonicalizationAndHashing() {
    // Exact canonical form: https://host:port (with default 443 stripped)
    assertEquals("https://example.com", PasswordCryptoEngine.canonicalizeOrigin("https://example.com/login?foo=bar#hash"))
    assertEquals("https://example.com", PasswordCryptoEngine.canonicalizeOrigin("HTTPS://EXAMPLE.COM:443/auth"))
    assertEquals("https://example.com:8443", PasswordCryptoEngine.canonicalizeOrigin("https://example.com:8443/api"))
    assertEquals("https://sub.example.com", PasswordCryptoEngine.canonicalizeOrigin("https://sub.example.com/"))

    // Non-HTTPS should be rejected
    org.junit.Assert.assertNull(PasswordCryptoEngine.canonicalizeOrigin("http://example.com/login"))
    org.junit.Assert.assertNull(PasswordCryptoEngine.canonicalizeOrigin("javascript:void(0)"))
    org.junit.Assert.assertNull(PasswordCryptoEngine.canonicalizeOrigin("data:text/html,test"))

    // Origin isolation: different origins produce different hashes
    val hash1 = PasswordCryptoEngine.hashSiteUrl("https://example.com/path1")
    val hash2 = PasswordCryptoEngine.hashSiteUrl("https://example.com/path2")
    val hashSub = PasswordCryptoEngine.hashSiteUrl("https://sub.example.com")
    val hashPort = PasswordCryptoEngine.hashSiteUrl("https://example.com:8443")

    assertEquals("Same origin different path must yield identical hash", hash1, hash2)
    org.junit.Assert.assertNotEquals("Subdomain must NOT match base domain hash", hash1, hashSub)
    org.junit.Assert.assertNotEquals("Different port must NOT match base port hash", hash1, hashPort)
  }

  @Test
  fun testAes256GcmPackedEncryptionDecryption() {
    val key = ByteArray(32) { (it * 7).toByte() }
    val secretText = "Super#Secret$2026Password"
    val plaintext = secretText.toByteArray(StandardCharsets.UTF_8)

    val packedBlob = PasswordCryptoEngine.encryptAesGcmPacked(key, plaintext)
    assertNotNull(packedBlob)
    assertTrue(packedBlob.size >= PasswordCryptoEngine.IV_LENGTH_BYTES + PasswordCryptoEngine.AUTH_TAG_LENGTH_BYTES + plaintext.size)

    val decrypted = PasswordCryptoEngine.decryptAesGcmPacked(key, packedBlob)
    assertEquals(secretText, String(decrypted, StandardCharsets.UTF_8))
  }
}
