package com.remmi.browser.security

import android.content.Context
import android.util.Log
import com.remmi.browser.util.DebugLogManager
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * High-level mode selection for Remmi Browser.
 * Maps cleanly to native GeckoView profiles and privacy configurations.
 */
enum class Mode {
  SHIELD,
  TOR;

  val privacyProfile: PrivacyProfile
    get() = when (this) {
      SHIELD -> PrivacyProfile.SHIELD
      TOR -> PrivacyProfile.GHOST
    }

  companion object {
    fun fromProfile(profile: PrivacyProfile): Mode = when (profile) {
      PrivacyProfile.GHOST -> TOR
      PrivacyProfile.SHIELD, PrivacyProfile.INCOGNITO -> SHIELD
    }
  }
}

/**
 * ProfileConfigManager
 *
 * Manages writing and verifying distinct engine-level preference sets (`user.js` / `*_prefs.js`)
 * into their respective profile directories:
 * - Shield Mode: /data/user/0/<package>/files/profiles/shield
 * - Tor Mode:    /data/user/0/<package>/files/profiles/tor
 *
 * Enforces atomic writes (write -> fsync -> rename) and verification before GeckoRuntime init.
 * Zero JavaScript monkey-patching: all protections are strictly engine-level Gecko preferences.
 */
class ProfileConfigManager(private val context: Context) {

  companion object {
    private const val TAG = "ProfileConfigManager"
    private const val PROFILES_DIR_NAME = "profiles"
    private const val SHIELD_SUBDIR = "shield"
    private const val TOR_SUBDIR = "tor"

    const val PREFS_FILE_USER_JS = "user.js"
    const val PREFS_FILE_SHIELD = "shield_prefs.js"
    const val PREFS_FILE_TOR = "tor_prefs.js"
    const val CONFIG_FILE_YAML = "geckoview-config.yaml"

    @Volatile
    private var instance: ProfileConfigManager? = null

    fun getInstance(context: Context): ProfileConfigManager {
      return instance ?: synchronized(this) {
        instance ?: ProfileConfigManager(context.applicationContext).also { instance = it }
      }
    }
  }

  val baseProfilesDir: File
    get() = File(context.filesDir, PROFILES_DIR_NAME)

  fun getProfileDir(mode: Mode): File {
    val subdir = when (mode) {
      Mode.SHIELD -> SHIELD_SUBDIR
      Mode.TOR -> TOR_SUBDIR
    }
    return File(baseProfilesDir, subdir)
  }

  fun getUserJsFile(mode: Mode): File {
    return File(getProfileDir(mode), PREFS_FILE_USER_JS)
  }

  fun getSpecificPrefsFile(mode: Mode): File {
    val filename = when (mode) {
      Mode.SHIELD -> PREFS_FILE_SHIELD
      Mode.TOR -> PREFS_FILE_TOR
    }
    return File(getProfileDir(mode), filename)
  }

  fun getYamlConfigFile(mode: Mode): File {
    return File(getProfileDir(mode), CONFIG_FILE_YAML)
  }

  /**
   * Generates the authoritative engine preferences content for Shield Mode (user.js format).
   */
  fun generateShieldPrefs(dohUri: String = "https://cloudflare-dns.com/dns-query"): String {
    return buildString {
      appendLine("// ==========================================================================")
      appendLine("// REMMI BROWSER ENGINE CONFIGURATION - SHIELD MODE PROFILE")
      appendLine("// Strict Tracking Protection, Native DoH + ECH, Leak Prevention, Clamped Timers")
      appendLine("// ZERO CLIENT-SIDE JAVASCRIPT MONKEY-PATCHING - ENGINE LEVEL HARDENING")
      appendLine("// ==========================================================================")
      appendLine()
      appendLine("// DNS-over-HTTPS & ECH (Cloudflare DNS + Encrypted Client Hello)")
      appendLine("""user_pref("network.trr.mode", 2);""")
      appendLine("""user_pref("network.trr.uri", "$dohUri");""")
      appendLine("""user_pref("network.dns.echconfig.enabled", true);""")
      appendLine("""user_pref("security.tls.ech.grease_probability", 100);""")
      appendLine("""user_pref("network.dns.use_https_rr_as_alpn", true);""")
      appendLine()
      appendLine("// Network & Leak Prevention")
      appendLine("""user_pref("media.peerconnection.enabled", false); // WebRTC leaks blocked""")
      appendLine("""user_pref("network.proxy.type", 0); // Direct clearnet connection""")
      appendLine("""user_pref("network.captive-portal-service.enabled", false);""")
      appendLine("""user_pref("network.dns.disablePrefetch", true);""")
      appendLine("""user_pref("network.dns.disablePrefetchFromHTTPS", true);""")
      appendLine("""user_pref("dom.security.https_only_mode", true);""")
      appendLine()
      // Allow trimmed cross-origin referrers so YouTube/GoogleVideo APIs don't break
      appendLine("""user_pref("network.http.referer.XOriginPolicy", 0);""")
      appendLine("""user_pref("network.http.referer.defaultPolicy", 3); // no-referrer-when-downgrade""")
      appendLine("""user_pref("network.http.referer.trimmingPolicy", 0);""")
      appendLine()
      appendLine("// Privacy Headers & Timing Clamping")
      appendLine("""user_pref("privacy.donottrackheader.enabled", true);""")
      appendLine("""user_pref("privacy.globalprivacycontrol.enabled", true);""")
      appendLine("""user_pref("privacy.reduceTimerPrecision", true);""")
      appendLine("""user_pref("privacy.reduceTimerPrecision.microseconds", 2000);""") // 2ms standard timing jitter protection (fluid 60fps/120fps animations)
      appendLine("""user_pref("privacy.fingerprintingProtection", true);""")
      appendLine("""user_pref("privacy.fingerprintingProtection.overrides", "+AllTargets,-FrameRate");""")
      appendLine("""user_pref("privacy.firstparty.isolate", true);""")
      appendLine()
      appendLine("// Hardware & Media Compatibility (WebAudio enabled, Canvas random noise disabled)")
      appendLine("""user_pref("dom.webaudio.enabled", true);""")
      appendLine("""user_pref("privacy.resistFingerprinting", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.letterboxing", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomDataOnCanvasExtract", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts", true);""")
      appendLine("""user_pref("privacy.resistFingerprinting.block_mozAddonManager", true);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomization.canvas.use_siphash", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomization.daily_reset.enabled", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomization.daily_reset.private.enabled", false);""")
      appendLine("""user_pref("dom.maxHardwareConcurrency", 2);""")
      appendLine("""user_pref("dom.workers.maxHardwareConcurrency", 2);""")
      appendLine()
      appendLine("// Performance & APZ Smooth Scrolling")
      appendLine("""user_pref("general.smoothScroll", true);""")
      appendLine("""user_pref("general.smoothScroll.msdPhysics.enabled", false);""")
      appendLine("""user_pref("general.smoothScroll.msdPhysics.continuousMode", false);""")
      appendLine("""user_pref("apz.overscroll.enabled", true);""")
      appendLine("""user_pref("apz.allow_zooming", true);""")
      appendLine("""user_pref("apz.paint_skipping.enabled", false);""")
      appendLine("""user_pref("apz.peek_messages.enabled", false);""")
      appendLine("""user_pref("image.mem.surfacecache.max_size_kb", 24576);""")
      appendLine("""user_pref("browser.low_end_device_optimizations", true);""")
      appendLine()
      appendLine("// Form & Password Manager Engine Integration")
      appendLine("""user_pref("signon.rememberSignons", true);""")
      appendLine("""user_pref("signon.autofillForms", false);""")
      appendLine("""user_pref("signon.formlessCapture.enabled", true);""")
      appendLine("""user_pref("signon.storeWhenAutocompleteOff", true);""")
      appendLine("""user_pref("signon.overrideAutocompleteOff", true);""")
      appendLine("""user_pref("signon.showAutoCompleteFooter", true);""")
      appendLine("""user_pref("signon.schemeUpgrades", true);""")
    }
  }

  /**
   * Generates the authoritative engine preferences content for Tor Mode (user.js format).
   */
  fun generateTorPrefs(socksPort: Int = 9050): String {
    return buildString {
      appendLine("// ==========================================================================")
      appendLine("// REMMI BROWSER ENGINE CONFIGURATION - TOR MODE PROFILE")
      appendLine("// High-Anonymity Native Tor Routing, Zero DNS Leaks, Canvas & Audio Hardening")
      appendLine("// ZERO CLIENT-SIDE JAVASCRIPT MONKEY-PATCHING - ENGINE LEVEL HARDENING")
      appendLine("// ==========================================================================")
      appendLine()
      appendLine("// Native Tor SOCKS5 Proxy Routing (Fail-closed)")
      appendLine("""user_pref("network.proxy.type", 1);""")
      appendLine("""user_pref("network.proxy.socks", "127.0.0.1");""")
      appendLine("""user_pref("network.proxy.socks_port", $socksPort);""")
      appendLine("""user_pref("network.proxy.socks_version", 5);""")
      appendLine("""user_pref("network.proxy.socks_remote_dns", true); // MANDATORY: Remote DNS over Tor""")
      appendLine("""user_pref("network.proxy.socks5_remote_dns", true);""")
      appendLine("""user_pref("network.proxy.failover_direct", false); // Zero clearnet leak""")
      appendLine("""user_pref("network.proxy.allow_bypass", false);""")
      appendLine("""user_pref("network.proxy.no_proxies_on", "");""")
      appendLine("""user_pref("network.trr.mode", 0); // Completely disable DoH in Tor Mode""")
      appendLine("""user_pref("network.dns.echconfig.enabled", false); // Let Tor handle exit node encryption""")
      appendLine()
      appendLine("// Anti-Fingerprinting (Tor Standard RFP + Canvas Protection + Letterboxing)")
      appendLine("""user_pref("privacy.resistFingerprinting", true);""")
      appendLine("""user_pref("privacy.resistFingerprinting.letterboxing", true);""")
      appendLine("""user_pref("privacy.resistFingerprinting.letterboxing.dimensions", "360x640, 400x700, 480x800, 800x600");""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomDataOnCanvasExtract", false); // Fix 100% unique paradox""")
      appendLine("""user_pref("privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts", true); // Auto-decline canvas probing""")
      appendLine("""user_pref("privacy.resistFingerprinting.block_mozAddonManager", true);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomization.canvas.use_siphash", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomization.daily_reset.enabled", false);""")
      appendLine("""user_pref("privacy.resistFingerprinting.randomization.daily_reset.private.enabled", false);""")
      appendLine("""user_pref("general.platform.override", "Linux aarch64");""")
      appendLine("""user_pref("general.oscpu.override", "Linux aarch64");""")
      appendLine("""user_pref("general.useragent.override", "Mozilla/5.0 (Android; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0");""")
      appendLine()
      appendLine("// CRITICAL: Unblock .onion domain resolution (Default in Firefox is true, which kills .onion)")
      appendLine("""user_pref("network.dns.blockDotOnion", false);""")
      appendLine()
      appendLine("// Allow HTTP connections on .onion (Hidden services use onion-crypto, not traditional TLS)")
      appendLine("""user_pref("dom.security.https_only_mode", false);""")
      appendLine("""user_pref("dom.security.https_only_mode_pbm", false);""")
      appendLine("""user_pref("dom.security.https_only_mode.upgrade_onion", false);""")
      appendLine("""user_pref("dom.security.https_first", false);""")
      appendLine("""user_pref("dom.security.https_first_pbm", false);""")
      appendLine("""user_pref("dom.securecontext.allowlist_onions", true);""")
      appendLine("""user_pref("security.certerror.hideAddException", false);""")
      appendLine("""user_pref("security.dialog_enable_delay", 0);""")
      appendLine("""user_pref("security.cert_pinning.enforcement_level", 0);""")
      appendLine("""user_pref("security.enterprise_roots.enabled", true);""")
      appendLine("""user_pref("security.ssl.enable_ocsp_stapling", false);""")
      appendLine("""user_pref("network.http.rcwn.enabled", false);""")
      appendLine()
      appendLine("// Hardware & Audio Lockdown")
      appendLine("""user_pref("dom.webaudio.enabled", false); // Neutralize AudioContext buffer fingerprinting""")
      appendLine("""user_pref("dom.maxHardwareConcurrency", 2); // Generic 2-core bucket to hide CPU specs""")
      appendLine("""user_pref("dom.workers.maxHardwareConcurrency", 2);""")
      appendLine("""user_pref("media.peerconnection.enabled", false); // Block WebRTC entirely""")
      appendLine("""user_pref("media.navigator.enabled", false);""")
      appendLine("""user_pref("dom.battery.enabled", false);""")
      appendLine("""user_pref("device.sensors.enabled", false);""")
      appendLine()
      // Allow trimmed cross-origin referrers so YouTube/GoogleVideo APIs don't break
      appendLine("""user_pref("network.http.referer.XOriginPolicy", 0);""")
      appendLine("""user_pref("network.http.referer.defaultPolicy", 3); // no-referrer-when-downgrade""")
      appendLine("""user_pref("network.http.referer.trimmingPolicy", 0);""")
      appendLine("""user_pref("privacy.reduceTimerPrecision", true);""")
      appendLine("""user_pref("privacy.reduceTimerPrecision.microseconds", 16666);""") // 16.6ms standard 60Hz frame time clamping (replaces 100ms lag)
      appendLine("""user_pref("privacy.resistFingerprinting.reduceTimerPrecision.microseconds", 16666);""")
      appendLine()
      appendLine("// Performance & Memory BFCache")
      appendLine("""user_pref("general.smoothScroll", true);""")
      appendLine("""user_pref("general.smoothScroll.msdPhysics.enabled", false);""")
      appendLine("""user_pref("general.smoothScroll.msdPhysics.continuousMode", false);""")
      appendLine("""user_pref("apz.paint_skipping.enabled", false);""")
      appendLine("""user_pref("apz.peek_messages.enabled", false);""")
      appendLine("""user_pref("browser.cache.memory.enable", true);""")
      appendLine("""user_pref("browser.cache.memory.capacity", 16384);""")
      appendLine("""user_pref("image.mem.surfacecache.max_size_kb", 24576);""")
      appendLine("""user_pref("browser.low_end_device_optimizations", true);""")
    }
  }

  fun generateTorYamlConfig(socksPort: Int = 9050): String {
    return buildString {
      appendLine("prefs:")
      appendLine("  network.proxy.type: 1")
      appendLine("""  network.proxy.socks: "127.0.0.1"""")
      appendLine("  network.proxy.socks_port: $socksPort")
      appendLine("  network.proxy.socks_version: 5")
      appendLine("  network.proxy.socks_remote_dns: true")
      appendLine("  network.proxy.socks5_remote_dns: true")
      appendLine("  network.proxy.failover_direct: false")
      appendLine("  network.proxy.allow_bypass: false")
      appendLine("""  network.proxy.no_proxies_on: """"")
      appendLine("  network.trr.mode: 0")
      appendLine("  network.dns.echconfig.enabled: false")
      appendLine("  network.dns.blockDotOnion: false")
      appendLine("  dom.security.https_only_mode: false")
      appendLine("  dom.security.https_only_mode_pbm: false")
      appendLine("  dom.security.https_only_mode.upgrade_onion: false")
      appendLine("  dom.security.https_first: false")
      appendLine("  dom.security.https_first_pbm: false")
      appendLine("  dom.securecontext.allowlist_onions: true")
      appendLine("  security.certerror.hideAddException: false")
      appendLine("  security.dialog_enable_delay: 0")
      appendLine("  security.cert_pinning.enforcement_level: 0")
      appendLine("  security.enterprise_roots.enabled: true")
      appendLine("  security.ssl.enable_ocsp_stapling: false")
      appendLine("  network.http.rcwn.enabled: false")
      appendLine("  privacy.resistFingerprinting: true")
      appendLine("  privacy.resistFingerprinting.letterboxing: true")
      appendLine("""  privacy.resistFingerprinting.letterboxing.dimensions: "360x640, 400x700, 480x800, 800x600"""")
      appendLine("  privacy.resistFingerprinting.randomDataOnCanvasExtract: false")
      appendLine("  privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts: true")
      appendLine("  privacy.resistFingerprinting.block_mozAddonManager: true")
      appendLine("  privacy.resistFingerprinting.randomization.canvas.use_siphash: false")
      appendLine("  privacy.resistFingerprinting.randomization.daily_reset.enabled: false")
      appendLine("  privacy.resistFingerprinting.randomization.daily_reset.private.enabled: false")
      appendLine("""  general.platform.override: "Linux aarch64"""")
      appendLine("""  general.oscpu.override: "Linux aarch64"""")
      appendLine("""  general.useragent.override: "Mozilla/5.0 (Android; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"""")
      appendLine("  dom.webaudio.enabled: false")
      appendLine("  dom.maxHardwareConcurrency: 2")
      appendLine("  media.peerconnection.enabled: false")
      appendLine("  media.navigator.enabled: false")
      appendLine("  dom.battery.enabled: false")
      appendLine("  device.sensors.enabled: false")
      appendLine("  network.http.referer.trimmingPolicy: 0")
      appendLine("  network.http.referer.defaultPolicy: 3")
      appendLine("  network.http.referer.XOriginPolicy: 0")
      appendLine("  privacy.reduceTimerPrecision: true")
      appendLine("  privacy.reduceTimerPrecision.microseconds: 16666")
      appendLine("  general.smoothScroll: true")
      appendLine("  browser.cache.memory.enable: true")
      appendLine("  browser.cache.memory.capacity: 16384")
      appendLine("  image.mem.surfacecache.max_size_kb: 24576")
      appendLine("  browser.low_end_device_optimizations: true")
    }
  }

  fun generateShieldYamlConfig(dohUri: String = "https://cloudflare-dns.com/dns-query"): String {
    return buildString {
      appendLine("prefs:")
      appendLine("  network.proxy.type: 0")
      appendLine("""  network.proxy.socks: """"")
      appendLine("  network.proxy.socks_port: 0")
      appendLine("  network.trr.mode: 2")
      appendLine("""  network.trr.uri: "$dohUri"""")
      appendLine("  network.dns.echconfig.enabled: true")
      appendLine("  network.dns.use_https_rr_as_alpn: true")
      appendLine("  privacy.resistFingerprinting: false")
      appendLine("  privacy.resistFingerprinting.randomDataOnCanvasExtract: false")
      appendLine("  privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts: true")
      appendLine("  privacy.resistFingerprinting.block_mozAddonManager: true")
      appendLine("  privacy.resistFingerprinting.randomization.canvas.use_siphash: false")
      appendLine("  privacy.resistFingerprinting.randomization.daily_reset.enabled: false")
      appendLine("  privacy.resistFingerprinting.randomization.daily_reset.private.enabled: false")
      appendLine("  dom.webaudio.enabled: true")
      appendLine("  dom.maxHardwareConcurrency: 2")
      appendLine("  media.peerconnection.enabled: false")
      appendLine("  privacy.reduceTimerPrecision: true")
      appendLine("  privacy.reduceTimerPrecision.microseconds: 2000")
      appendLine("  general.smoothScroll: true")
      appendLine("  image.mem.surfacecache.max_size_kb: 24576")
      appendLine("  browser.low_end_device_optimizations: true")
    }
  }

  /**
   * Atomically writes configuration files to the specified profile folder.
   * Pattern: Write to .tmp file -> FileOutputStream.fd.sync() -> Atomic Rename.
   *
   * Writes both `user.js` and `*_prefs.js` to ensure engine discovery under any standard path.
   */
  @Synchronized
  fun writeProfileConfigAtomic(
    mode: Mode,
    socksPort: Int = 9050,
    dohUri: String = "https://cloudflare-dns.com/dns-query"
  ): Result<File> {
    val profileDir = getProfileDir(mode)
    if (!profileDir.exists()) {
      val created = profileDir.mkdirs()
      if (!created && !profileDir.exists()) {
        val err = IllegalStateException("Failed to create profile directory: ${profileDir.absolutePath}")
        Log.e(TAG, err.message, err)
        return Result.failure(err)
      }
    }

    val content = when (mode) {
      Mode.SHIELD -> generateShieldPrefs(dohUri)
      Mode.TOR -> generateTorPrefs(socksPort)
    }

    val yamlContent = when (mode) {
      Mode.SHIELD -> generateShieldYamlConfig(dohUri)
      Mode.TOR -> generateTorYamlConfig(socksPort)
    }

    val targetUserJs = getUserJsFile(mode)
    val specificPrefsFile = getSpecificPrefsFile(mode)
    val yamlConfigFile = getYamlConfigFile(mode)

    return try {
      // 1. Write user.js atomically
      atomicWriteFile(targetUserJs, content)

      // 2. Also write mode-specific file (shield_prefs.js / tor_prefs.js) for explicit archiving
      atomicWriteFile(specificPrefsFile, content)

      // 3. Remove any stale yaml config files to prevent DebugConfig crash in release builds
      try {
        if (yamlConfigFile.exists()) {
          yamlConfigFile.delete()
        }
      } catch (_: Throwable) {}

      // 4. Also mirror user.js into any default mozilla profile directories if present
      syncToMozillaProfiles(content)

      // 5. Verify files on disk
      val verified = verifyProfileConfig(mode)
      if (!verified) {
        val err = IllegalStateException("Profile verification failed for mode $mode at ${targetUserJs.absolutePath}")
        Log.e(TAG, err.message, err)
        return Result.failure(err)
      }

      DebugLogManager.log("[PROFILE_CONFIG] Atomically written and verified for $mode at ${targetUserJs.absolutePath} (size=${targetUserJs.length()} bytes)")
      Log.i(TAG, "Profile config successfully verified for $mode at ${targetUserJs.absolutePath}")
      Result.success(targetUserJs)
    } catch (t: Throwable) {
      Log.e(TAG, "Error writing atomic profile config for $mode: ${t.message}", t)
      Result.failure(t)
    }
  }

  private fun syncToMozillaProfiles(content: String) {
    try {
      val mozillaDir = File(context.filesDir, "mozilla")
      if (mozillaDir.exists() && mozillaDir.isDirectory) {
        mozillaDir.walkTopDown().maxDepth(2).filter { it.isDirectory }.forEach { dir ->
          val targetFile = File(dir, PREFS_FILE_USER_JS)
          atomicWriteFile(targetFile, content)
        }
      }
    } catch (_: Exception) {}
  }

  private fun atomicWriteFile(targetFile: File, content: String) {
    val parentDir = targetFile.parentFile ?: context.filesDir
    val tmpFile = File(parentDir, "${targetFile.name}.tmp_${System.currentTimeMillis()}")

    FileOutputStream(tmpFile).use { fos ->
      fos.write(content.toByteArray(Charsets.UTF_8))
      fos.flush()
      fos.fd.sync() // Ensure physical disk commit
    }

    try {
      Files.move(
        tmpFile.toPath(),
        targetFile.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    } catch (e: Exception) {
      // Fallback if filesystem does not support ATOMIC_MOVE
      if (targetFile.exists()) {
        targetFile.delete()
      }
      val renamed = tmpFile.renameTo(targetFile)
      if (!renamed) {
        throw IllegalStateException("Failed to move temporary config file $tmpFile to $targetFile")
      }
    }
  }

  /**
   * Verifies that the written profile configuration exists, is non-empty, and contains
   * mandatory preferences for the given mode.
   */
  fun verifyProfileConfig(mode: Mode): Boolean {
    val userJs = getUserJsFile(mode)
    if (!userJs.exists() || !userJs.canRead() || userJs.length() == 0L) {
      Log.w(TAG, "Verification failed: user.js missing or empty at ${userJs.absolutePath}")
      return false
    }

    val content = try {
      userJs.readText(Charsets.UTF_8)
    } catch (e: Exception) {
      Log.e(TAG, "Verification failed reading user.js: ${e.message}")
      return false
    }

    return when (mode) {
      Mode.SHIELD -> {
        content.contains("network.trr.mode") &&
        content.contains("network.dns.echconfig.enabled") &&
        content.contains("network.http.referer.trimmingPolicy") &&
        content.contains("dom.webaudio.enabled") &&
        content.contains("privacy.resistFingerprinting.randomDataOnCanvasExtract") &&
        !content.contains("gfx.webrender.all") &&
        !content.contains("layers.acceleration.force-enabled")
      }
      Mode.TOR -> {
        content.contains("network.proxy.type") &&
        content.contains("network.proxy.socks_remote_dns") &&
        content.contains("privacy.resistFingerprinting.letterboxing") &&
        content.contains("dom.maxHardwareConcurrency") &&
        content.contains("privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts") &&
        !content.contains("gfx.webrender.all") &&
        !content.contains("layers.acceleration.force-enabled")
      }
    }
  }

  /**
   * Ensures that profile directories and configuration files for both modes exist and are valid.
   */
  fun ensureProfilesReady(torPort: Int = 9050): Boolean {
    val shieldOk = writeProfileConfigAtomic(Mode.SHIELD).isSuccess
    val torOk = writeProfileConfigAtomic(Mode.TOR, socksPort = torPort).isSuccess
    return shieldOk && torOk
  }

  /**
   * Safely clears temporary cache or state files in the profile directory to prevent cross-profile leakage.
   */
  fun cleanProfileState(mode: Mode) {
    try {
      val profileDir = getProfileDir(mode)
      if (profileDir.exists()) {
        profileDir.listFiles()?.forEach { file ->
          // Retain configuration files, remove transient session and cache files
          if (file.name != PREFS_FILE_USER_JS &&
              file.name != PREFS_FILE_SHIELD &&
              file.name != PREFS_FILE_TOR) {
            file.deleteRecursively()
          }
        }
      }
      DebugLogManager.log("[PROFILE_CONFIG] Cleaned transient state for $mode")
    } catch (e: Exception) {
      Log.w(TAG, "Failed cleaning profile state for $mode: ${e.message}")
    }
  }
}
