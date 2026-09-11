# Remmi Browser

Remmi Browser is an Android privacy-focused web browser built around GeckoView, optional Tor routing, tracking protection, and local credential storage.

## Release status

This repository is suitable for source review and beta testing. A production release should be cut only from a CI build signed with the project's real release keystore and after the release checklist is completed.

## Privacy model

- Browser data and diagnostic reports are stored on-device by default.
- Crash diagnostics are kept in app-private no-backup storage; they are not automatically exported to public/shared Downloads.
- Ghost/Tor-required auxiliary HTTP clients are created through the central route authority and fail closed if a verified Tor route is unavailable.
- Reader translation sends the requested text to Google Translate when the user explicitly invokes translation.
- Shield-mode DNS is configured for Cloudflare DNS-over-HTTPS; Ghost-mode connectivity checks use Tor-routed verification endpoints.

See [`PRIVACY.md`](PRIVACY.md), [`SECURITY.md`](SECURITY.md), and [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).

## Building

Use Android Studio with Android 16 / API 36 installed. The project uses Gradle Wrapper + AGP and Java 17 bytecode settings (CI uses JDK 17 for deterministic Android builds).

Debug builds use the standard Android debug signing configuration. Release builds intentionally require these environment variables: `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. CI maps the project-owned GitHub secrets `REMMI_RELEASE_KEYSTORE_B64`, `REMMI_RELEASE_STORE_PASSWORD`, `REMMI_RELEASE_KEY_ALIAS`, and `REMMI_RELEASE_KEY_PASSWORD` to those variables.

## License

No license is declared in this repository yet. Add the project owner's chosen open-source license before publishing source code for reuse.
