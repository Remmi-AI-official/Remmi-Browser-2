# Remmi Browser Release Checklist

## Source / metadata

- [ ] Add a project license before publishing reusable source code.
- [ ] Update versionName/versionCode for the release.
- [ ] Confirm Play Store listing, privacy-policy URL, support contact, and content rating are ready.

## Build

- [ ] Android SDK Platform 36 is installed.
- [ ] `./gradlew test` passes.
- [ ] `./gradlew lintRelease` passes.
- [ ] `./gradlew assembleRelease` and `./gradlew bundleRelease` pass in CI.
- [ ] Configure GitHub secrets `REMMI_RELEASE_KEYSTORE_B64`, `REMMI_RELEASE_STORE_PASSWORD`, `REMMI_RELEASE_KEY_ALIAS`, and `REMMI_RELEASE_KEY_PASSWORD`.
- [ ] CI release signing uses only the real project keystore; no fallback key.
- [ ] Verify artifact SHA-256 hashes and signing certificate fingerprint.

## Privacy / security

- [ ] Verify Ghost mode with packet capture: favicon, reader, translation, images, and other auxiliary clients do not bypass Tor.
- [ ] Verify `.onion` traffic fails closed until the Tor route is verified.
- [ ] Verify crash reports stay app-private until the user explicitly shares them.
- [ ] Review Android permissions and Play Data Safety disclosures against the current binary.
- [ ] Test panic wipe, password vault, backup/restore, and extension installation on physical devices.

## Android 16 / Play

- [ ] Test behavior changes introduced when targeting API 36, including edge-to-edge and foreground-service behavior.
- [ ] Upload a signed AAB to Play Console internal testing before production rollout.
