# Remmi Browser — Release Verification Report

## Verdict

**Release-hardening is applied to the source tree, but the APK/AAB was not locally produced in this environment.** The repository is prepared for a real CI release build after the maintainer configures the real Android signing keystore and runs device/network verification.

## Applied release fixes

- Centralized auxiliary HTTP routing through `NetworkRouteAuthority`.
- Routed favicon requests through the authoritative route and blocked private/local targets.
- Removed raw SOCKS proxy logic from Reader translation and made Ghost translation fail-safe.
- Made Ghost/.onion navigation require a fully verified READY Tor route.
- Routed external HTTP/HTTPS VIEW intents through `TabManager` + navigation security validation.
- Removed the stale `127.0.0.1:9050` Ghost fallback from anti-fingerprinting configuration.
- Removed automatic crash-report export to public Downloads; crash reports are retained in app-private `noBackupFilesDir` storage with bounded retention.
- Removed fallback release signing credentials; release builds now fail closed when signing secrets are absent.
- Added CI keystore/alias validation and APK/AAB signature verification.
- Updated Android compile/target SDK to API 36.
- Updated release version to `1.0.2` / `versionCode 3` (verify the code is greater than any already-published Play build before upload).
- Removed unused Firebase/Google-services release plumbing and related secrets from Gradle configuration.
- Added public release hygiene documents: README, SECURITY, PRIVACY, CHANGELOG, and RELEASE_CHECKLIST.
- Removed one-off patch/test/wait scripts and the root signing test helper from the public release tree.
- Added source-level release regression tests covering the security invariants above.
- Updated jsoup from 1.22.1 to 1.23.2.

## Validation completed here

- JSON metadata parsing: PASS.
- TOML version-catalog parsing: PASS.
- Android XML parsing: PASS.
- Release workflow static invariant checks: PASS.
- Required release-document checks: PASS.
- Secret-pattern scan of application/release configuration: PASS; no embedded credentials or private keys detected by the scan.
- Modified Kotlin source syntax scan: PASS; no Kotlin parser/syntax diagnostics found.
- `gradlew` executable bit: PASS.
- Gradle release/unit-test execution: **NOT VERIFIED locally** because the environment could not download the Gradle 9.3.1 distribution and does not have the required Android SDK installed.

## Required before publishing the binary

1. Configure these GitHub Actions secrets with the real production signing key:
   - `REMMI_RELEASE_KEYSTORE_B64`
   - `REMMI_RELEASE_STORE_PASSWORD`
   - `REMMI_RELEASE_KEY_ALIAS`
   - `REMMI_RELEASE_KEY_PASSWORD`
2. Run the release CI and require unit tests, release lint, APK/AAB build, and signature verification to pass.
3. On physical Android devices, verify Ghost traffic with packet/DNS capture, including failed-start and Tor-restart cases; verify `.onion` is fail-closed before Tor READY.
4. Verify WebView/PWA launches, foreground-service behavior, biometric vault flows, downloads, reader translation, location/camera/microphone prompts, and panic-wipe behavior on supported Android versions.
5. Review Google Play Data Safety, privacy-policy URL, app-content declarations, and the final target/API requirements in Play Console.
6. Add the project's chosen `LICENSE` before presenting the repository itself as open-source/reusable.
7. Confirm `versionCode 3` is higher than the latest already-published production build, if one exists.

## Important scope note

The source tree is hardened against the release blockers identified in the static audit. This report intentionally does **not** claim a successful APK/AAB build, successful device test, or successful packet-capture test because those require an Android/CI environment and physical network validation.
## Jitter / GeckoView navigation fix

### Symptom observed

On normal web navigation, the browser could visibly blink/jitter even when the destination page had loaded successfully. The supplied trace shows a real page (`https://duckduckgo.com/`) repeatedly reaching `NAV_STOP success=true`, followed by transient `about:blank` state, `BROWSER_VIEW_UNMOUNT`, `VIEW_ON_RELEASE`, a reset-to-new-tab operation, and a fresh Gecko navigation.

### Root cause

The Compose layer treated transient Gecko `about:blank` callbacks as an immediate New Tab state. That could swap `BrowserView` out of the composition while Gecko was still attaching/loading the real page. At the same time, lifecycle/recomposition paths could request duplicate `GeckoView` attachment. No-op `TabManager.updateTab()` calls were also emitting state repeatedly, increasing recomposition pressure during heavy tracker/ad-block activity.

### Applied fix

- Added intent-aware New Tab state so transient `about:blank` does not replace a real active page.
- Suppressed blank URL callbacks when a real navigation target is still active, while preserving explicit Home / Back-to-Home behavior.
- Added an in-flight per-tab `GeckoView` attach guard to stop lifecycle/recomposition attach races.
- Prevented `BrowserView` from reacting to a transient blank by resetting the tab or dispatching another load.
- Changed `TabManager.updateTab()` to emit only when the resulting `BrowserTab` actually changes.

### Validation

- Jitter-fix source invariant checks: PASS.
- Behavioral model for transient blank vs explicit Home: PASS.
- Kotlin parser/syntax scan of modified files: PASS; no syntax diagnostics reached before type resolution.
- Full Android Gradle build: NOT VERIFIED in this environment because the Gradle 9.3.1 distribution and Android SDK are not locally available and external download is blocked.

### Device verification still required

Before public binary release, test at minimum: DuckDuckGo, Google, YouTube, a JS-heavy SPA, redirects, history Back/Forward, opening a link in a new tab, explicit Home, tab switching, Ghost mode navigation, and repeated foreground/background transitions. The acceptance criterion is no visible BrowserView-to-NewTab flash during normal navigation and no unexpected `resetToNewTab` after a successful terminal page load.

