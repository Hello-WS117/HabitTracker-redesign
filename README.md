# Habit Tracker Releases

This public repository is the release-only distribution channel for Habit Tracker. The current application source is maintained in a private repository and is not published here.

## Download

- [Download the latest Android release](https://github.com/Hello-WS117/HabitTracker-redesign/releases/latest)
- [View all releases](https://github.com/Hello-WS117/HabitTracker-redesign/releases)

Install the APK over the existing app to retain local data. The Android package name and signing identity remain unchanged across supported upgrades. Creating a backup before updating is still recommended.

## In-App Updates

The app checks [`update/stable.json`](update/stable.json) for the current stable version and downloads the corresponding signed APK from GitHub Releases. This repository name and manifest path are retained for compatibility with installed versions.

## Published Here

- Signed release APKs
- Release notes
- APK checksums and update metadata

Application source code, signing keys, credentials, personal habit data, and backup files are not published in this repository.

## Current Stable Release

The current stable release is `v1.0.90`. Its package, checksum, signer identity, and download URL are recorded in [`update/stable.json`](update/stable.json).
