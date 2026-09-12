package com.remmi.browser.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProfileConfigRenderingFlagsTest {

  private lateinit var context: Application
  private lateinit var configManager: ProfileConfigManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    configManager = ProfileConfigManager.getInstance(context)
  }

  @Test
  fun test1_ShieldPrefs_DoesNotContainForcedGpuFlags() {
    val prefs = configManager.generateShieldPrefs()

    // Must NOT contain forced WebRender or forced layers acceleration
    assertFalse("Shield prefs must not force gfx.webrender.all", prefs.contains("gfx.webrender.all"))
    assertFalse("Shield prefs must not force layers.acceleration.force-enabled", prefs.contains("layers.acceleration.force-enabled"))

    // Must preserve critical privacy and security settings
    assertTrue("Shield prefs must contain TRR DoH configuration", prefs.contains("network.trr.mode"))
    assertTrue("Shield prefs must contain ECH configuration", prefs.contains("network.dns.echconfig.enabled"))
    assertTrue("Shield prefs must contain WebRTC leak block", prefs.contains("""user_pref("media.peerconnection.enabled", false);"""))
    assertTrue("Shield prefs must contain Referrer trimming", prefs.contains("network.http.referer.trimmingPolicy"))
    assertTrue("Shield prefs must contain Canvas protection", prefs.contains("privacy.resistFingerprinting.randomDataOnCanvasExtract"))
  }

  @Test
  fun test2_TorPrefs_DoesNotContainForcedGpuFlags() {
    val prefs = configManager.generateTorPrefs(socksPort = 9050)

    // Must NOT contain forced WebRender or forced layers acceleration
    assertFalse("Tor prefs must not force gfx.webrender.all", prefs.contains("gfx.webrender.all"))
    assertFalse("Tor prefs must not force layers.acceleration.force-enabled", prefs.contains("layers.acceleration.force-enabled"))

    // Must preserve critical privacy and Tor routing settings
    assertTrue("Tor prefs must contain SOCKS proxy routing", prefs.contains("""user_pref("network.proxy.type", 1);"""))
    assertTrue("Tor prefs must contain SOCKS remote DNS", prefs.contains("""user_pref("network.proxy.socks_remote_dns", true);"""))
    assertTrue("Tor prefs must disable failover to direct", prefs.contains("""user_pref("network.proxy.failover_direct", false);"""))
    assertTrue("Tor prefs must unblock .onion", prefs.contains("""user_pref("network.dns.blockDotOnion", false);"""))
    assertTrue("Tor prefs must contain RFP letterboxing", prefs.contains("""user_pref("privacy.resistFingerprinting.letterboxing", true);"""))
    assertTrue("Tor prefs must contain 2-core hardware concurrency hiding", prefs.contains("""user_pref("dom.maxHardwareConcurrency", 2);"""))
  }

  @Test
  fun test3_YamlConfigs_DoNotContainForcedGpuFlags() {
    val shieldYaml = configManager.generateShieldYamlConfig()
    val torYaml = configManager.generateTorYamlConfig(socksPort = 9050)

    assertFalse(shieldYaml.contains("gfx.webrender.all"))
    assertFalse(shieldYaml.contains("layers.acceleration.force-enabled"))

    assertFalse(torYaml.contains("gfx.webrender.all"))
    assertFalse(torYaml.contains("layers.acceleration.force-enabled"))
  }

  @Test
  fun test4_AtomicWriteAndVerify_SucceedsWithoutForcedGpuFlags() {
    val shieldRes = configManager.writeProfileConfigAtomic(Mode.SHIELD)
    assertTrue("Shield profile write must succeed", shieldRes.isSuccess)
    assertTrue("Shield profile verification must pass", configManager.verifyProfileConfig(Mode.SHIELD))

    val torRes = configManager.writeProfileConfigAtomic(Mode.TOR, socksPort = 9050)
    assertTrue("Tor profile write must succeed", torRes.isSuccess)
    assertTrue("Tor profile verification must pass", configManager.verifyProfileConfig(Mode.TOR))

    // Ensure written files on disk do not contain forced GPU flags
    val shieldFile = configManager.getUserJsFile(Mode.SHIELD)
    val shieldContent = shieldFile.readText()
    assertFalse("Written shield user.js must not contain gfx.webrender.all", shieldContent.contains("gfx.webrender.all"))
    assertFalse("Written shield user.js must not contain layers.acceleration.force-enabled", shieldContent.contains("layers.acceleration.force-enabled"))

    val torFile = configManager.getUserJsFile(Mode.TOR)
    val torContent = torFile.readText()
    assertFalse("Written tor user.js must not contain gfx.webrender.all", torContent.contains("gfx.webrender.all"))
    assertFalse("Written tor user.js must not contain layers.acceleration.force-enabled", torContent.contains("layers.acceleration.force-enabled"))
  }

  @Test
  fun test5_VerificationRejectsForcedGpuFlags() {
    // If a corrupted or legacy profile with forced flags exists, verifyProfileConfig must return false
    val shieldFile = configManager.getUserJsFile(Mode.SHIELD)
    shieldFile.parentFile?.mkdirs()
    val legacyContent = configManager.generateShieldPrefs() + "\nuser_pref(\"gfx.webrender.all\", true);\n"
    shieldFile.writeText(legacyContent)

    assertFalse("Verification must reject user.js containing forced gfx.webrender.all", configManager.verifyProfileConfig(Mode.SHIELD))

    // Calling ensureProfilesReady repairs it cleanly
    val repaired = configManager.ensureProfilesReady()
    assertTrue("ensureProfilesReady must repair profile config", repaired)
    assertTrue("Repaired shield config must pass verification", configManager.verifyProfileConfig(Mode.SHIELD))
  }

  @Test
  fun test6_TimerPrecisionIsCompatibilitySafeAndProfileIsolated() {
    val shieldPrefs = configManager.generateShieldPrefs()
    val torPrefs = configManager.generateTorPrefs(socksPort = 9050)

    // Shield Mode: 2ms (2000 microseconds) for 60fps/120fps smooth animations & games without jitter
    assertTrue("Shield prefs must enable reduceTimerPrecision", shieldPrefs.contains("""user_pref("privacy.reduceTimerPrecision", true);"""))
    assertTrue("Shield prefs must use 2000us (2ms) precision", shieldPrefs.contains("""user_pref("privacy.reduceTimerPrecision.microseconds", 2000);"""))
    assertFalse("Shield prefs must not use excessive 20000us precision", shieldPrefs.contains("20000"))

    // Tor Mode: 16.6ms (16666 microseconds) aligned with standard 60Hz frame intervals
    assertTrue("Tor prefs must enable reduceTimerPrecision", torPrefs.contains("""user_pref("privacy.reduceTimerPrecision", true);"""))
    assertTrue("Tor prefs must use 16666us precision", torPrefs.contains("""user_pref("privacy.reduceTimerPrecision.microseconds", 16666);"""))
    assertTrue("Tor prefs must set RFP timer precision to 16666us", torPrefs.contains("""user_pref("privacy.resistFingerprinting.reduceTimerPrecision.microseconds", 16666);"""))
    assertFalse("Tor prefs must not use excessive 100000us lag precision", torPrefs.contains("100000"))
  }
}
