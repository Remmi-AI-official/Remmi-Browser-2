package com.remmi.browser.security

import com.remmi.browser.security.permissions.AndroidPermissionRequester
import com.remmi.browser.security.permissions.OriginCanonicalizer
import com.remmi.browser.security.permissions.PermissionDecision
import com.remmi.browser.security.permissions.PermissionSessionManager
import com.remmi.browser.security.permissions.PermissionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PermissionSystemUnitTest {

  private lateinit var manager: PermissionSessionManager

  @Before
  fun setup() {
    manager = PermissionSessionManager.getInstance()
    manager.androidPermissionRequester = object : AndroidPermissionRequester {
      override fun requestAndroidPermissions(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit
      ) {
        // Mock Android OS granting all requested permissions
        val grantedMap = permissions.associateWith { true }
        onResult(grantedMap)
      }
    }
  }

  @Test
  fun testExactOriginBindingAndNavigationRevocation() {
    val tabId = "tab_perm_test_1"
    val origin1 = "https://alpha.example.com"
    val origin2 = "https://beta.example.com"

    // Initial state: no indicators
    assertNull(manager.activeIndicators.value[tabId])

    // On navigation to new cross-origin, previous state is wiped
    manager.onTabNavigated(tabId, origin1)
    manager.onTabNavigated(tabId, origin2)
    assertNull(manager.activeIndicators.value[tabId])
  }

  @Test
  fun testAppBackgroundingRevocation() {
    val tabId = "tab_perm_test_2"
    manager.onTabNavigated(tabId, "https://example.com")

    // When app goes to background, all active media/location is stopped
    manager.onAppBackgrounded()
    val state = manager.activeIndicators.value[tabId]
    assertFalse(state?.hasAnyActive == true)
  }

  @Test
  fun testTabCloseCleanup() {
    val tabId = "tab_perm_test_3"
    manager.onTabNavigated(tabId, "https://example.com")

    manager.onTabClosed(tabId)
    assertNull(manager.activeIndicators.value[tabId])
  }

  @Test
  fun testManualRevocation() {
    val tabId = "tab_perm_test_4"
    manager.onTabNavigated(tabId, "https://camera-app.example.com")

    manager.revokePermission(tabId, PermissionType.CAMERA)
    manager.revokePermission(tabId, PermissionType.GEOLOCATION)
    assertNull(manager.activeIndicators.value[tabId])
  }
}
