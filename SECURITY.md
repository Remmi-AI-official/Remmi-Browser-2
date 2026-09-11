# Security Policy

## Supported versions

Security fixes are maintained against the latest published release and the default branch.

## Reporting a vulnerability

Do not post exploit details, tokens, crash dumps, or private browsing data in public issues. Use a private security channel controlled by the project maintainers.

Include the affected version, Android version, reproduction steps, expected behavior, actual behavior, and impact.

## Release security requirements

Release artifacts must be built in CI with a project-controlled signing key. Missing signing secrets must fail the build; no generated fallback release key is permitted.

Ghost/Tor privacy claims must be validated with packet-capture and route verification on physical devices before release.
