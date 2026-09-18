package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Constructor

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OnionNavigationRegressionTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager

  private val dummyCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {}
    override fun onTitleChange(title: String) {}
    override fun onProgressChange(progress: Int) {}
    override fun onLoadingChange(isLoading: Boolean) {}
    override fun onSecurityChange(isSecure: Boolean) {}
    override fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
    override fun onTrackerBlocked(url: String, type: String) {}
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    DebugLogManager.init(context)
    DebugLogManager.clear()
    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
    manager.uriLoaderForTest = { _, _, _ -> }
    com.remmi.browser.security.TorLifecycleManager.getInstance(context).setTorReadyForTesting(true)
  }

  @After
  fun tearDown() = runBlocking {
    manager.uriLoaderForTest = null
    manager.sessionOpenerForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
    ShadowLooper.idleMainLooper()
  }

  private fun createWebRequestError(category: Int, code: Int): WebRequestError {
    val constructor = WebRequestError::class.java.declaredConstructors.first()
    constructor.isAccessible = true
    val params = arrayOfNulls<Any>(constructor.parameterCount)
    for (i in constructor.parameterTypes.indices) {
      val type = constructor.parameterTypes[i]
      params[i] = when {
        type == String::class.java -> "https://test.onion"
        type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> if (i == 1) category else code
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 0L
        type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
        else -> null
      }
    }
    val instance = constructor.newInstance(*params) as WebRequestError
    for (field in WebRequestError::class.java.declaredFields) {
      field.isAccessible = true
      val name = field.name.lowercase()
      if (name.contains("cat") || field.type == Int::class.javaPrimitiveType && name.contains("c")) {
        try { field.set(instance, category) } catch (_: Exception) {}
      }
      if (name.contains("code") || name.contains("error") || name.contains("err")) {
        try { field.set(instance, code) } catch (_: Exception) {}
      }
    }
    return instance
  }

  /**
   * Test A: http://foo.onion and https://foo.onion are NOT equivalent.
   */
  @Test
  fun testA_OnionHttpAndHttpsAreNotEquivalent() {
    val httpUrl = "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"
    val httpsUrl = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"

    assertFalse(
      "HTTP and HTTPS onion URLs must NOT be considered equivalent",
      manager.areUrlsEquivalent(httpUrl, httpsUrl)
    )

    // With paths and ports
    assertFalse(
      manager.areUrlsEquivalent("http://foo.onion/path", "https://foo.onion/path")
    )
    assertFalse(
      manager.areUrlsEquivalent("http://foo.onion:8080/path", "http://foo.onion:80/path")
    )

    // Exact matches must still be equivalent
    assertTrue(
      manager.areUrlsEquivalent("http://foo.onion/path", "http://foo.onion/path")
    )
    assertTrue(
      manager.areUrlsEquivalent("http://foo.onion/", "http://foo.onion")
    )
    assertTrue(
      manager.areUrlsEquivalent("https://foo.onion/a?b=c", "https://foo.onion/a?b=c")
    )
  }

  /**
   * Test B: Explicit http onion + HTTPS bad-cert -> one HTTP fallback.
   */
  @Test
  fun testB_ExplicitHttpOnion_HttpsBadCert_TriggersOneFallback() = runBlocking {
    val httpOnion = "http://testonionhost12345678.onion/index.html"
    val httpsOnion = "https://testonionhost12345678.onion/index.html"

    val tab = tabManager.createTab(url = "about:blank", profile = PrivacyProfile.SHIELD)
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )
    ShadowLooper.idleMainLooper()

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      loadedUrls.add(url)
    }

    manager.loadUrl(tabId, httpOnion)
    ShadowLooper.idleMainLooper()

    assertEquals("Original requested URL must be HTTP", httpOnion, manager.getOriginalRequestedUrl(tabId))
    assertEquals("Original requested scheme must be http", "http", manager.getOriginalRequestedScheme(tabId))

    // Simulate Gecko hitting HTTPS and encountering ERROR_SECURITY_BAD_CERT
    val navDelegate = session.navigationDelegate
    assertNotNull("NavigationDelegate must be attached", navDelegate)

    val certError = createWebRequestError(
      category = WebRequestError.ERROR_CATEGORY_SECURITY,
      code = WebRequestError.ERROR_SECURITY_BAD_CERT
    )

    loadedUrls.clear()
    val errorResult = navDelegate?.onLoadError(session, httpsOnion, certError)
    ShadowLooper.idleMainLooper()

    // Fallback should be triggered: onLoadError returns null and dispatches reload of original http URL
    assertNull("Fallback should return null from onLoadError to allow retry", errorResult)
    assertTrue("Should have re-dispatched HTTP fallback URL", loadedUrls.contains(httpOnion))
  }

  /**
   * Test C: Explicit https onion + bad-cert -> no HTTP downgrade.
   */
  @Test
  fun testC_ExplicitHttpsOnion_BadCert_NoHttpDowngrade() = runBlocking {
    val httpsOnion = "https://testonionhost12345678.onion/index.html"

    val tab = tabManager.createTab(url = "about:blank", profile = PrivacyProfile.SHIELD)
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )
    ShadowLooper.idleMainLooper()

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      loadedUrls.add(url)
    }

    manager.loadUrl(tabId, httpsOnion)
    ShadowLooper.idleMainLooper()

    assertEquals("Original requested URL must be HTTPS", httpsOnion, manager.getOriginalRequestedUrl(tabId))
    assertEquals("Original requested scheme must be https", "https", manager.getOriginalRequestedScheme(tabId))

    val navDelegate = session.navigationDelegate
    assertNotNull("NavigationDelegate must be attached", navDelegate)

    val certError = createWebRequestError(
      category = WebRequestError.ERROR_CATEGORY_SECURITY,
      code = WebRequestError.ERROR_SECURITY_BAD_CERT
    )

    loadedUrls.clear()
    val errorResult = navDelegate?.onLoadError(session, httpsOnion, certError)
    ShadowLooper.idleMainLooper()

    // Should NOT downgrade to HTTP; must return native about:certerror result
    assertNotNull("Must return native cert error page GeckoResult", errorResult)
    assertTrue("No HTTP fallback loads must be triggered for explicit HTTPS", loadedUrls.none { it.startsWith("http://") })
    assertEquals("CERTIFICATE_ERROR", manager.getLastOriginalFailure(tabId))
  }

  /**
   * Test D: Repeated certificate errors do not create recovery loops.
   */
  @Test
  fun testD_RepeatedCertErrors_DoNotCreateRecoveryLoops() = runBlocking {
    val httpOnion = "http://loopingcertonionhost.onion/"
    val httpsOnion = "https://loopingcertonionhost.onion/"

    val tab = tabManager.createTab(url = "about:blank", profile = PrivacyProfile.SHIELD)
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )
    ShadowLooper.idleMainLooper()

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      loadedUrls.add(url)
    }

    manager.loadUrl(tabId, httpOnion)
    ShadowLooper.idleMainLooper()

    val navDelegate = session.navigationDelegate
    val certError = createWebRequestError(
      category = WebRequestError.ERROR_CATEGORY_SECURITY,
      code = WebRequestError.ERROR_SECURITY_BAD_CERT
    )

    // First attempt: fallback happens
    val firstResult = navDelegate?.onLoadError(session, httpsOnion, certError)
    ShadowLooper.idleMainLooper()
    assertNull(firstResult)

    // Second attempt on the same failure (e.g. redirected to HTTPS again and failed again):
    loadedUrls.clear()
    val secondResult = navDelegate?.onLoadError(session, httpsOnion, certError)
    ShadowLooper.idleMainLooper()

    // Second attempt must be terminal: returns about:certerror, no more fallback loads
    assertNotNull("Second attempt must be terminal and return cert error page", secondResult)
    assertTrue("No infinite reload loop allowed", loadedUrls.isEmpty())
    assertEquals("CERTIFICATE_ERROR", manager.getLastOriginalFailure(tabId))
  }

  /**
   * Test E: Certificate error does not trigger content-process recovery.
   */
  @Test
  fun testE_CertificateError_DoesNotTriggerContentProcessRecovery() = runBlocking {
    val httpsOnion = "https://certtestonionhost.onion/"

    val tab = tabManager.createTab(url = "about:blank", profile = PrivacyProfile.SHIELD)
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )
    ShadowLooper.idleMainLooper()

    manager.loadUrl(tabId, httpsOnion)
    ShadowLooper.idleMainLooper()

    val navDelegate = session.navigationDelegate
    val certError = createWebRequestError(
      category = WebRequestError.ERROR_CATEGORY_SECURITY,
      code = WebRequestError.ERROR_SECURITY_BAD_CERT
    )

    navDelegate?.onLoadError(session, httpsOnion, certError)
    ShadowLooper.idleMainLooper()

    assertEquals("CERTIFICATE_ERROR", manager.getLastOriginalFailure(tabId))

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      loadedUrls.add(url)
    }

    // Trigger onCrash
    session.contentDelegate?.onCrash(session)
    ShadowLooper.idleMainLooper()

    assertTrue("Crash recovery must be suppressed for terminal certificate errors", loadedUrls.isEmpty())
  }

  /**
   * Test F: Valid HTTP onion remains HTTP when no server redirect occurs.
   */
  @Test
  fun testF_ValidHttpOnion_RemainsHttpWhenNoRedirectOccurs() = runBlocking {
    val httpOnion = "http://plainhttponionhost123.onion/home"

    val tab = tabManager.createTab(url = "about:blank", profile = PrivacyProfile.SHIELD)
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )
    ShadowLooper.idleMainLooper()

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      loadedUrls.add(url)
    }

    manager.loadUrl(tabId, httpOnion)
    ShadowLooper.idleMainLooper()

    assertEquals(1, loadedUrls.size)
    assertEquals(httpOnion, loadedUrls[0])
    assertEquals(httpOnion, manager.getOriginalRequestedUrl(tabId))
    assertEquals("http", manager.getOriginalRequestedScheme(tabId))
  }

  /**
   * Test G: Existing clearnet HTTP/HTTPS duplicate-navigation behaviour remains unchanged.
   */
  @Test
  fun testG_ClearnetHttpAndHttps_EquivalenceUnchanged() {
    val clearnetHttp = "http://example.com/search?q=test"
    val clearnetHttps = "https://example.com/search?q=test"

    assertTrue(
      "Clearnet HTTP and HTTPS URLs must remain equivalent for duplicate navigation suppression",
      manager.areUrlsEquivalent(clearnetHttp, clearnetHttps)
    )

    assertTrue(
      manager.areUrlsEquivalent("http://www.google.com/", "https://google.com/")
    )
  }
}
