# Remmi Browser V1 Release Checklist & Verification Matrix

This matrix distinguishes between automated local JVM / CI test gates and mandatory real-device validation. Physical-device items that cannot be run in headless cloud/JVM environments are explicitly marked as **[PENDING REAL-DEVICE VALIDATION]**.

---

## 1. Automated Build & CI Test Gates (Verified in CI / JVM)

- [x] **Compile & Build:** `compile_applet` and `./gradlew assembleDebug` complete cleanly.
- [x] **Release Signing Security:** Committed keystores (`release-keystore.base64`, `debug.keystore.base64`) removed from repo and gitignored.
- [x] **CI Fail-Closed Signing:** `.github/workflows/release.yml` rejects builds without GitHub Secrets (`REMMI_RELEASE_KEYSTORE_B64`, etc.). No unauthenticated fallbacks.
- [x] **Unsuppressed CI Verification:** All `|| true` removed from `apksigner` and `jarsigner` commands in release CI.
- [x] **Release Test Enforcement:** CI executes `testDebugUnitTest` and `lintRelease` without `-x test` or `-x lint`.
- [x] **Artifact Validation:** Release workflow verifies non-empty APK and AAB, package ID `com.remmi.browser`, minSdk 26, targetSdk 36, non-debuggable flags, and generates `SHA256SUMS.txt`.
- [x] **Tor Config Hygiene:** Stable `torrc` preserved without experimental/unsupported directives (`ConnectTimeout`, `MaxClientCircuits`, `CircuitIdleTimeout`).
- [x] **Fail-Closed Architecture:** `NavigationSecurityAuthority`, `NetworkRouteAuthority`, and `CurrentTorRoute` maintain fail-closed routing and generation matching.

---

## 2. Real-Device Functional & Performance Validation (Physical Device Required)

The following tests require physical Android hardware (Android 8.0 - Android 16) with cellular/Wi-Fi connectivity, biometric hardware, and camera/microphone peripherals:

### A. Core Browsing & Engine Reliability
- [ ] **[PENDING REAL-DEVICE VALIDATION] Normal HTTP/HTTPS Browsing:** Navigate to top 50 web properties (e.g. Wikipedia, GitHub, DuckDuckGo, Reddit). Confirm full DOM rendering, CSS flexbox/grid layout fidelity, Web Fonts, and TLS certificate validation.
- [ ] **[PENDING REAL-DEVICE VALIDATION] JavaScript-Heavy Applications:** Test Single Page Applications (Google Docs, YouTube, X/Twitter, Figma viewer). Verify WebAssembly execution, IndexedDB storage, and smooth 60/120Hz scroll physics.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Multiple Tabs & Memory Pressure:** Open 20+ active tabs concurrently. Verify tab switching latency (<150ms), LRU memory reclamation, and thumbnail caching without OOM crashes.
- [ ] **[PENDING REAL-DEVICE VALIDATION] App Lifecycle (Background / Foreground):** Background app for 5+ minutes while streaming audio or loading pages. Return to foreground; verify session restoration without ANRs or white-screen flash.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Process Recreation & OS Death:** Enable "Don't keep activities" in Android Developer Options. Navigate to complex forms, switch apps, return; verify form input state and backstack restoration.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Screen Rotation & Window Resizing:** Rotate device between portrait and landscape during video playback, text entry, and page load. Verify Compose recomposition, edge-to-edge insets, and zero UI stutter.

### B. Tor & Ghost Mode Fail-Closed Security
- [ ] **[PENDING REAL-DEVICE VALIDATION] Clearnet → Ghost → Verified Tor Transition:** Start in standard Clearnet mode. Open Ghost tab. Confirm foreground service notification, Tor bootstrap to 100%, SOCKS5 handshake verification, and remote exit IP verification.
- [ ] **[PENDING REAL-DEVICE VALIDATION] .onion Navigation:** Navigate to verified `.onion` hidden services (e.g., `duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion`). Confirm end-to-end rendezvous circuit negotiation and page rendering.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Tor Unavailable Fail-Closed:** Simulate Tor daemon launch failure (e.g., kill process or corrupt port). Confirm Ghost mode displays explicit fail-closed error dialog and refuses clearnet egress.
- [ ] **[PENDING REAL-DEVICE VALIDATION] SOCKS Verification Failure:** Inject local port blockage on 9050. Confirm Ghost navigation aborts immediately without network leakage.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Tor Restart & Route Invalidation:** Terminate Tor daemon while an active download or page load is in-flight. Confirm active request terminates with network error and never falls back to clearnet route.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Network Interface Changes (Wi-Fi ↔ Cellular):** Switch from Wi-Fi to Mobile Data during active Tor circuit. Verify circuit reconnection or clean fail-closed notification without packet leakage.

### C. Privacy, Content Filtering & Hardware Features
- [ ] **[PENDING REAL-DEVICE VALIDATION] Adblock Engine (Enabled vs. Disabled):** Toggle Adblock in settings. Benchmark page load times on heavy ad sites (e.g., news portals). Verify cosmetic DOM element hiding and zero WebExtension crashes.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Password Vault & Biometric Unlock:** Save credentials for 3 distinct sites. Lock device/vault. Unlock via fingerprint/face biometric. Verify AES-256-GCM decryption and autofill prompt in GeckoView.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Panic / Emergency Wipe:** Trigger Panic button from toolbar/menu. Verify immediate termination of Gecko engine, destruction of all cookies, cache, local storage, history, and active Tor sessions.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Runtime Permissions (Camera, Microphone, Geolocation):** Navigate to WebRTC test site (e.g., `webcamtests.com`). Verify Android 14/15/16 runtime permission prompts and camera/mic indicator dots in status bar.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Backup & Restore:** Export encrypted vault/settings backup file to scoped storage. Clear app data. Restore from backup file and confirm master password challenge.
- [ ] **[PENDING REAL-DEVICE VALIDATION] File Downloads:** Download large files (PDF, ZIP, APK, ISO > 500MB) in Clearnet and Ghost mode. Confirm notification download progress, pause/resume capability, and Scoped Storage isolation.

---

## 3. Google Play & Android 16 Release Hygiene

- [x] **SDK Targets:** `compileSdk 36`, `targetSdk 36`, `minSdk 26`.
- [x] **Branding Audit:** Pure "Remmi Browser" branding throughout; zero "Netrunner" legacy references.
- [x] **UsesCleartextTraffic:** Enforced `android:usesCleartextTraffic="false"` in `AndroidManifest.xml`.
- [x] **Debuggable Strip:** Release builds compiled with `android:debuggable="false"`.
- [ ] **[PENDING REAL-DEVICE VALIDATION] Play Console Internal Track:** Upload signed AAB bundle to Google Play Console Internal Testing track and verify automated pre-launch report (Firebase Test Lab crawl).
