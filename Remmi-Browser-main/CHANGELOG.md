# Changelog

## Jitter / GeckoView stability fix

- Prevent transient Gecko `about:blank` callbacks from swapping the Compose content to the New Tab page during real navigation.
- Guard against concurrent `GeckoView` attach calls from lifecycle/recomposition races.
- Suppress no-op `TabManager` state emissions to reduce unnecessary BrowserScreen recompositions during heavy tracker blocking.
- Preserve explicit Home and Back-to-Home behavior through an intent-based New Tab state.

## 1.0.2

Release-readiness hardening for the first public beta/production candidate.

- Routed favicon and Reader translation auxiliary traffic through the central network authority.
- Added DNS rebinding protection for normal-mode auxiliary HTTP clients.
- Made Ghost and .onion navigation fail closed until the Tor route is verified `READY`.
- Routed external HTTP(S) intents through the same navigation security gate.
- Moved automatic crash diagnostics to app-private no-backup storage.
- Removed release-signing fallback keys and made CI signing fail closed.
- Updated Android target/compile SDK to API 36 and aligned release CI to JDK 17.
- Updated jsoup to 1.23.2 and added public privacy/security/release documentation.
- Removed one-off source patch scripts from the public release tree.
