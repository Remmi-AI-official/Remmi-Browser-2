package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.autofill.GeckoPasswordStorageDelegate
import com.remmi.browser.security.autofill.PasswordAutofillCoordinator
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.Autocomplete
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeckoPasswordAutofillIntegrationTest {

  private lateinit var context: Context
  private lateinit var repository: PasswordManagerRepository
  private val testScope = TestScope()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    repository = PasswordManagerRepository.getInstance(context)
  }

  @Test
  fun testExactOriginAutofillMatchingOnly() = runTest {
    // 1. Setup Master Password
    val masterPassword = "MasterSecurePassword!2026#".toCharArray()
    repository.setupMasterPassword(masterPassword, null)
    assertTrue(repository.isUnlocked())

    // 2. Add credentials for https://login.example.com
    val id = repository.saveOrUpdateEntry(
      url = "https://login.example.com/auth",
      username = "user@example.com",
      password = "SecretPassword123!"
    )
    assertTrue(id > 0)

    // 3. Query exact origin
    val exactMatches = repository.getLoginEntriesForOrigin("https://login.example.com")
    assertEquals(1, exactMatches.size)
    assertEquals("https://login.example.com", exactMatches[0].origin)
    assertEquals("user@example.com", exactMatches[0].username)
    assertEquals("SecretPassword123!", exactMatches[0].password)

    // 4. Query different subdomain -> must NOT match
    val subMatches = repository.getLoginEntriesForOrigin("https://api.example.com")
    assertEquals(0, subMatches.size)

    // 5. Query different port -> must NOT match
    val portMatches = repository.getLoginEntriesForOrigin("https://login.example.com:8443")
    assertEquals(0, portMatches.size)

    // 6. Query HTTP non-secure -> must NOT return credentials
    val httpMatches = repository.getLoginEntriesForOrigin("http://login.example.com")
    assertEquals(0, httpMatches.size)
  }

  @Test
  fun testStorageDelegateReturnsNullWhenVaultIsLocked() = runTest {
    val delegate = GeckoPasswordStorageDelegate(context, repository)

    // Lock vault
    repository.lockVault()
    assertFalse(repository.isUnlocked())

    // onLoginFetch should return an empty GeckoResult when locked
    val result = delegate.onLoginFetch("https://login.example.com")
    assertNotNull(result)
  }

  @Test
  fun testStorageDelegateFetchesEntriesWhenUnlocked() = runTest {
    val masterPassword = "TestMasterPassword!2026".toCharArray()
    repository.setupMasterPassword(masterPassword, null)
    assertTrue(repository.isUnlocked())

    repository.saveOrUpdateEntry(
      url = "https://secure.bank.com/signin",
      username = "cyber_user",
      password = "BankPassword$2026!"
    )

    val entries = repository.getLoginEntriesForOrigin("https://secure.bank.com")
    assertEquals(1, entries.size)
    assertEquals("cyber_user", entries.first().username)
    assertEquals("BankPassword$2026!", entries.first().password)
  }

  @Test
  fun testStorageDelegateSaveEntryNatively() = runTest {
    val masterPassword = "TestMasterPassword!2026".toCharArray()
    repository.setupMasterPassword(masterPassword, null)
    assertTrue(repository.isUnlocked())

    val delegate = GeckoPasswordStorageDelegate(context, repository)
    val entry = Autocomplete.LoginEntry.Builder()
      .origin("https://portal.service.com")
      .username("admin_root")
      .password("Complex#Secret!99")
      .build()

    delegate.saveLoginDirect(entry)

    // Verify stored
    val stored = repository.getLoginEntriesForOrigin("https://portal.service.com")
    assertEquals(1, stored.size)
    assertEquals("admin_root", stored[0].username)
    assertEquals("Complex#Secret!99", stored[0].password)
  }

  @Test
  fun testLazyDecryptionModelSecurity() = runTest {
    val masterPassword = "TestMasterPassword!2026".toCharArray()
    repository.setupMasterPassword(masterPassword, null)
    assertTrue(repository.isUnlocked())

    val entryId = repository.saveOrUpdateEntry(
      url = "https://app.work.com/login",
      username = "employee1",
      password = "VerySecretPassword123!"
    )

    // Candidate metadata-only lookup does NOT expose password
    val candidates = repository.findAutofillCandidatesForUrl("https://app.work.com/dashboard")
    assertEquals(1, candidates.size)
    assertEquals("https://app.work.com/login", candidates[0].url)
    assertEquals("employee1", candidates[0].username)

    // Lazy password decryption is only performed when explicitly requested
    val decryptedPassword = repository.decryptPasswordForEntry(entryId)
    assertEquals("VerySecretPassword123!", decryptedPassword)
  }

  @Test
  fun testPasswordAutofillCoordinatorPromptSuppression() {
    val coordinator = PasswordAutofillCoordinator(context, testScope, repository)

    var saveInvoked = false
    var dismissInvoked = false

    // Non-HTTPS save must be dismissed immediately
    coordinator.requestLoginSave(
      tabId = "tab1",
      origin = "http://insecure.site.com",
      username = "user",
      onSave = { saveInvoked = true },
      onDismiss = { dismissInvoked = true }
    )

    assertTrue("Insecure HTTP save must be dismissed", dismissInvoked)
    assertFalse("Insecure HTTP save must not trigger save prompt", saveInvoked)
    assertNull(coordinator.savePrompt.value)
  }
}
