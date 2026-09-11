package com.remmi.browser.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regression guards for release-critical privacy/security invariants.
 * These tests intentionally avoid Android runtime dependencies so CI can catch
 * accidental reintroduction of dangerous fallbacks even before an APK build.
 */
class ReleaseSecurityRegressionTest {
  private val javaRoot = File("src/main/java")
  private val buildFile = File("build.gradle.kts")
  private val workflowFile = File("../.github/workflows/release.yml")

  @Test
  fun faviconTrafficMustUseTheCentralRouteAuthorityAndBlockPrivateTargets() {
    val source = File(javaRoot, "com/remmi/browser/util/FaviconHelper.kt").readText()

    assertTrue("Favicon traffic must use NetworkRouteAuthority", source.contains("NetworkRouteAuthority.createHttpClient"))
    assertTrue("Favicon helper must reject private/local hosts", source.contains("NavigationSecurityAuthority.isPrivateOrLocalHost"))
    assertFalse("Favicon helper must not create a standalone OkHttp client", source.contains("OkHttpClient.Builder"))
  }

  @Test
  fun normalAuxiliaryClientsMustUseTheCentralDnsRebindingGuard() {
    val source = File(javaRoot, "com/remmi/browser/security/NetworkRouteAuthority.kt").readText()
    assertTrue("NetworkRouteAuthority must validate normal-mode DNS answers", source.contains("antiRebindingDns"))
    assertTrue("Normal-mode clients must install the DNS guard", source.contains("builder.dns(antiRebindingDns)"))
    assertTrue("Tor-mode clients must not replace remote SOCKS DNS with system DNS", source.contains("if (!requiresTor)"))
  }

  @Test
  fun ghostTranslationMustNotHaveAStaleTorPortFallback() {
    val source = File(javaRoot, "com/remmi/browser/reader/ReaderTranslator.kt").readText()
    assertTrue("Reader translation must use the central route authority", source.contains("NetworkRouteAuthority"))
    assertFalse("Reader translation must not hardcode the Tor SOCKS port", source.contains("127.0.0.1:9050"))
    assertFalse("Reader translation must not construct a raw SOCKS Proxy", source.contains("Proxy.Type.SOCKS"))
  }

  @Test
  fun ghostPreferencesMustFailClosedInsteadOfUsingDefault9050() {
    val source = File(javaRoot, "com/remmi/browser/security/AntiFingerprint.kt").readText()
    assertTrue("Ghost preferences must reject a missing Tor port", source.contains("Ghost preferences require a verified Tor SOCKS port"))
    assertFalse("Legacy stale 9050 fallback must not return", Regex("if\\s*\\(activePort\\s*>\\s*0\\)\\s*activePort\\s*else\\s*9050").containsMatchIn(source))
  }

  @Test
  fun crashReportsMustStayAppPrivateByDefault() {
    val source = File(javaRoot, "com/remmi/browser/util/CrashHandlerHelper.kt").readText()
    assertTrue("Crash reports must use no-backup private storage", source.contains("noBackupFilesDir"))
    assertFalse("Crash handler must not write to public Downloads", source.contains("MediaStore.Downloads"))
    assertFalse("Crash handler must not use the public Downloads directory", source.contains("DIRECTORY_DOWNLOADS"))
    assertFalse("Crash handler must not expose a public save-to-downloads API", source.contains("saveToDownloads"))
  }

  @Test
  fun releaseBuildAndWorkflowMustFailClosedOnSigningAndTargetCurrentPlayApi() {
    val gradle = buildFile.readText()
    val workflow = workflowFile.readText()

    assertTrue("Release target SDK must be API 36+", Regex("targetSdk\\s*=\\s*36").containsMatchIn(gradle))
    assertTrue("Release compile SDK must be API 36+", Regex("compileSdk\\s*=\\s*36").containsMatchIn(gradle))
    assertFalse("Release build must not generate a fallback signing key", workflow.contains("keytool -genkeypair"))
    assertTrue("Release workflow must require the signing key secret", workflow.contains("secrets.REMMI_RELEASE_KEYSTORE_B64"))
    assertTrue("Release workflow must verify the supplied signing keystore", workflow.contains("keytool -list"))
    assertFalse("Release config must not use the old hardcoded debug/release key alias", gradle.contains("android_release"))
    assertFalse("Release config must not depend on a checked-in debug.keystore", gradle.contains("debug.keystore"))
  }

  @Test
  fun sourceReleaseMustNotClaimCompletelyOfflineOperation() {
    val sourceFiles = javaRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
    val offending = sourceFiles.filter { file ->
      file.readText().contains("completely offline", ignoreCase = true)
    }.toList()
    assertTrue("Product copy must not claim completely-offline operation when online translation exists", offending.isEmpty())
  }

  @Test
  fun rootReleaseDocumentationMustExist() {
    val required = listOf("README.md", "SECURITY.md", "PRIVACY.md", "RELEASE_CHECKLIST.md", "CHANGELOG.md")
    for (name in required) {
      assertTrue("Missing public release document: $name", File("../$name").isFile)
    }
  }
}
