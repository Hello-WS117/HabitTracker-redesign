# Habit Tracker Stable Update Channel

This release-only repository contains `stable.json`, the credential-free manifest used by Habit Tracker's
user-initiated in-app updater. It points only to immutable, owner-signed APK
assets attached to this repository's GitHub Releases.

The APK asset must be published and read back successfully before this manifest
is changed. The app independently verifies the package name, increasing version
code, version name, minimum SDK, file size, SHA-256, universal release build,
and pinned private signer. It then requires a fresh nonzero provider-verified
backup before opening Android's installer.

This folder contains no private source, signing key, credentials, user data, or
backup files.
