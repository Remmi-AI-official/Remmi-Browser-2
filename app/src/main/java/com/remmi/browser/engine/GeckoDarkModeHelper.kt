package com.remmi.browser.engine

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import com.remmi.browser.storage.AppearanceMode
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.theme.CyberTheme
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet

/**
 * GeckoDarkModeHelper
 *
 * Central authority for:
 * 1. Color Scheme Signaling: preferredColorScheme (COLOR_SCHEME_DARK / COLOR_SCHEME_LIGHT)
 * 2. White Screen Flash Prevention: Pre-navigation surface color enforcement (#121212 / #FFFFFF)
 * 3. Smart Dark Mode WebExtension installation & session management
 * 4. Site-specific Dark Mode override & bypass management
 */
object GeckoDarkModeHelper {
  private const val TAG = "GeckoDarkModeHelper"
  private const val PREFS_NAME = "remmi_dark_mode_prefs"
  private const val KEY_DISABLED_SITES = "disabled_sites_set"

  const val COLOR_DARK_HEX = "#121212"
  const val COLOR_LIGHT_HEX = "#FFFFFF"

  val COLOR_DARK: Int = Color.parseColor(COLOR_DARK_HEX)
  val COLOR_LIGHT: Int = Color.parseColor(COLOR_LIGHT_HEX)

  const val EXTENSION_ID = "darkmode@remmi.browser"
  const val EXTENSION_URI = "resource://android/assets/extensions/remmi_dark_mode/"

  private val disabledSites = CopyOnWriteArraySet<String>()
  private var prefs: SharedPreferences? = null
  private var installedExtension: WebExtension? = null
  private var isInitialized = false

  fun init(context: Context, runtime: GeckoRuntime) {
    if (isInitialized) return
    isInitialized = true
    initPrefs(context)
    installExtension(runtime)
    syncThemeToGecko(context, runtime)
  }

  private fun initPrefs(context: Context) {
    if (prefs == null) {
      val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs = p
      val saved = p.getStringSet(KEY_DISABLED_SITES, emptySet()) ?: emptySet()
      disabledSites.clear()
      disabledSites.addAll(saved)
    }
  }

  /**
   * Installs the smart-inversion WebExtension as a built-in extension in GeckoView.
   */
  fun installExtension(runtime: GeckoRuntime) {
    try {
      runtime.webExtensionController
        .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
        .accept(
          { ext ->
            installedExtension = ext
            Log.i(TAG, "Remmi Smart Dark Mode WebExtension successfully registered: ${ext?.id}")
          },
          { throwable ->
            Log.w(TAG, "ensureBuiltIn notice, falling back to installBuiltIn: ${throwable?.message}")
            try {
              runtime.webExtensionController
                .installBuiltIn(EXTENSION_URI)
                .accept(
                  { fallbackExt ->
                    installedExtension = fallbackExt
                    Log.i(TAG, "Remmi Smart Dark Mode WebExtension fallback registered: ${fallbackExt?.id}")
                  },
                  { fallbackErr ->
                    Log.w(TAG, "Dark Mode WebExtension install error: ${fallbackErr?.message}")
                  }
                )
            } catch (e: Exception) {
              Log.w(TAG, "installBuiltIn exception: ${e.message}")
            }
          }
        )
    } catch (e: Exception) {
      Log.w(TAG, "installExtension exception: ${e.message}")
    }
  }

  /**
   * Evaluates whether the browser is currently running in Dark Mode.
   */
  fun isBrowserInDarkMode(context: Context): Boolean {
    val settings = SettingsRepository.getInstance(context).settings.value
    if (settings.pureBlackOled) return true
    if (settings.appearanceMode == AppearanceMode.DARK) return true
    if (settings.cyberTheme != CyberTheme.NORMAL_DEFAULT) return true
    return false
  }

  /**
   * Determines the authoritative background color to eliminate white screen flashes.
   */
  fun getCanvasBackgroundColor(context: Context): Int {
    return if (isBrowserInDarkMode(context)) COLOR_DARK else COLOR_LIGHT
  }

  /**
   * Applies the background color to the GeckoView before page navigation begins,
   * completely eliminating white screen flash during document load.
   */
  fun prepareViewForNavigation(geckoView: GeckoView?, context: Context) {
    val color = getCanvasBackgroundColor(context)
    geckoView?.setBackgroundColor(color)
  }

  /**
   * Sets preferredColorScheme on GeckoRuntime:
   * - Dark: GeckoRuntimeSettings.COLOR_SCHEME_DARK
   * - Light: GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
   */
  fun setPreferredColorScheme(runtime: GeckoRuntime?, isDark: Boolean) {
    if (runtime == null) return
    try {
      runtime.settings.preferredColorScheme = if (isDark) {
        GeckoRuntimeSettings.COLOR_SCHEME_DARK
      } else {
        GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
      }
      Log.i(TAG, "GeckoRuntime preferredColorScheme set to: ${if (isDark) "COLOR_SCHEME_DARK" else "COLOR_SCHEME_LIGHT"}")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to update preferredColorScheme: ${e.message}")
    }
  }

  /**
   * Synchronizes browser theme state to the GeckoView runtime.
   */
  fun syncThemeToGecko(context: Context, runtime: GeckoRuntime?) {
    val isDark = isBrowserInDarkMode(context)
    setPreferredColorScheme(runtime, isDark)
  }

  /**
   * Extracts clean hostname from URL.
   */
  fun extractHost(url: String): String {
    if (url.isBlank()) return ""
    return try {
      val parsedUri = URI(if (url.contains("://")) url else "https://$url")
      parsedUri.host?.lowercase() ?: ""
    } catch (_: Exception) {
      ""
    }
  }

  /**
   * Checks if dark mode is active for the specified site.
   */
  fun isSiteDarkModeEnabled(url: String, globalForcedDark: Boolean): Boolean {
    if (!globalForcedDark) return false
    val host = extractHost(url)
    if (host.isBlank()) return true
    return !disabledSites.contains(host)
  }

  /**
   * Toggles dark mode override for the specified site.
   * Returns true if site dark mode is now enabled, false if disabled.
   */
  fun toggleSiteDarkMode(context: Context, url: String, globalForcedDark: Boolean): Boolean {
    initPrefs(context)
    val host = extractHost(url)
    if (host.isBlank()) return true

    val currentlyEnabled = !disabledSites.contains(host)
    if (currentlyEnabled) {
      disabledSites.add(host)
    } else {
      disabledSites.remove(host)
    }

    prefs?.edit()?.putStringSet(KEY_DISABLED_SITES, disabledSites.toSet())?.apply()
    return !disabledSites.contains(host)
  }

  /**
   * Applies smart dark mode script to a GeckoSession on navigation start/stop.
   */
  fun applySmartDarkToSession(session: GeckoSession, url: String, context: Context) {
    val settings = SettingsRepository.getInstance(context).settings.value
    val isDark = isBrowserInDarkMode(context)
    val forceDarkGlobal = settings.darkThemeForAllWebPages

    if (!isDark || !forceDarkGlobal) {
      val removeScript = "javascript:(function(){try{document.documentElement.classList.remove('remmi-smart-dark');}catch(e){}})();"
      try { session.loadUri(removeScript) } catch (_: Exception) {}
      return
    }

    val host = extractHost(url)
    if (host.isNotBlank() && disabledSites.contains(host)) {
      val removeScript = "javascript:(function(){try{document.documentElement.classList.remove('remmi-smart-dark');}catch(e){}})();"
      try { session.loadUri(removeScript) } catch (_: Exception) {}
      return
    }

    val smartDarkScript = """
      javascript:(function(){
        try {
          if (window.getComputedStyle && window.getComputedStyle(document.documentElement).colorScheme === 'dark') {
            document.documentElement.classList.remove('remmi-smart-dark');
            return;
          }
          if (!document.getElementById('__remmi_smart_dark_style')) {
            var s = document.createElement('style');
            s.id = '__remmi_smart_dark_style';
            s.textContent = 'html.remmi-smart-dark{background-color:#121212!important;filter:invert(90%) hue-rotate(180deg)!important;}html.remmi-smart-dark img,html.remmi-smart-dark video,html.remmi-smart-dark canvas,html.remmi-smart-dark svg,html.remmi-smart-dark iframe,html.remmi-smart-dark embed,html.remmi-smart-dark object,html.remmi-smart-dark [style*="background-image"]:not([style*="background-image: none"]){filter:invert(100%) hue-rotate(180deg)!important;}html.remmi-smart-dark :fullscreen,html.remmi-smart-dark :-webkit-full-screen,html.remmi-smart-dark :fullscreen *{filter:none!important;}';
            (document.head || document.documentElement).appendChild(s);
          }
          document.documentElement.classList.add('remmi-smart-dark');
        } catch(e) {}
      })();
    """.trimIndent().replace("\n", "")

    try {
      session.loadUri(smartDarkScript)
    } catch (_: Exception) {}
  }
}
