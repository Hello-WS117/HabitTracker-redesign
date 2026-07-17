# HabitTracker UI Redesign Reference

This public repository is a sanitized, single-history snapshot of the HabitTracker Android app prepared for an independent UI redesign. It includes runnable source, a behavior inventory, workflow contracts, representative demo data, and emulator screenshots.

The current interface is reference material, not the visual target. A redesign should improve hierarchy and usability while preserving the scheduling, persistence, reminder, timer, history, and backup behavior documented in [`docs/redesign/`](docs/redesign/README.md).

## Start Here

1. Read [`docs/redesign/README.md`](docs/redesign/README.md).
2. Use [`docs/redesign/AI_HANDOFF_PROMPT.md`](docs/redesign/AI_HANDOFF_PROMPT.md) with the coding AI performing the redesign.
3. Follow [`docs/redesign/EMULATOR_SETUP.md`](docs/redesign/EMULATOR_SETUP.md) to build the app and restore the synthetic demo.
4. Compare the running app with [`docs/redesign/SCREENSHOT_CATALOG.md`](docs/redesign/SCREENSHOT_CATALOG.md).

## Run The App

Prerequisites: JDK 17 and an Android SDK with API 35.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Restore `docs/redesign/demo/habittracker-redesign-demo-v1.json` from **Settings > Restore from file** to populate every major task type and workflow state.

An owner-signed reference APK is attached to the [`v1.0.48` release](https://github.com/Hello-WS117/HabitTracker-redesign/releases/tag/v1.0.48). It is provided so a designer can inspect the current app without configuring a build environment.

## Safety And Scope

This snapshot excludes private Git history, personal backups, signing keys, credentials, device evidence, machine-specific paths, and private release infrastructure. The demo data is synthetic.

The repository intentionally has no open-source license. Public visibility allows inspection and redesign collaboration, but does not grant a general license to copy, redistribute, or reuse the code.
