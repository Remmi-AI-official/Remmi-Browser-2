# Remmi Browser Privacy Notice

## Data stored on the device

Remmi Browser stores browser state, tabs, settings, local content, and credential-vault data on the device as needed for the features you use. Crash and abnormal-termination diagnostics are stored in app-private no-backup storage and are not automatically exported to shared/public storage.

## Network activity

Normal browsing connects to destinations chosen by you or by the search/website features you invoke. Tracking protection and content blocking may prevent third-party requests. Shield-mode DNS is configured to use Cloudflare DNS-over-HTTPS (`https://cloudflare-dns.com/dns-query`) unless the underlying browser/runtime overrides that setting.

Ghost/Tor mode is designed to route required auxiliary network clients through the verified local Tor SOCKS route and to fail closed when that route is unavailable. Ghost connectivity verification may contact Tor Project's check API and a fallback public IP endpoint through the Tor route. No software can guarantee anonymity against every endpoint, configuration, device compromise, or traffic-analysis adversary.

## Reader translation

When you explicitly request Reader translation, the selected text is sent to Google's translation endpoint so a translation can be returned. Do not use this feature for text that must never leave the device.

## Diagnostics

Diagnostic reports may contain device metadata, software state, and sanitized browsing-host context needed for troubleshooting. Sharing a report is a user action.

## Permissions

Camera, microphone, and location permissions are requested only for website features that need them. Notifications may be used for browser/Tor foreground-service behavior.

## Policy changes

Update this notice before release whenever the app gains a new network service, analytics SDK, advertising SDK, account system, or other data-processing behavior.
