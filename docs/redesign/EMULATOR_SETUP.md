# Emulator And Demo Setup

These instructions let a coding AI or developer run the current app with sanitized representative data.

## Prerequisites

- JDK 17.
- Android SDK with platform 35, build-tools, platform-tools, and emulator.
- An Android API 35 phone AVD with Google APIs or the standard Android document picker.
- Node.js 18 or newer to regenerate date-relative demo data.

Set `JAVA_HOME` and `ANDROID_HOME` for the local installation. Android Studio can manage the same prerequisites graphically.

## Build The Debug App

From the repository root:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The public repository does not contain private release signing credentials. Use the attached GitHub Release APK to inspect the owner-signed build, and use the debug APK for redesign development.

## Start And Install

Start an API 35 phone AVD in Android Studio, or use the emulator CLI:

```bash
emulator @YOUR_AVD_NAME
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.habittracker/.MainActivity
```

## Generate Date-Relative Demo Data

The committed demo backup is anchored to the date shown in its `exportedAt` field. Regenerate it for today's date before a fresh visual review:

```bash
node scripts/generate_redesign_demo_backup.mjs
```

To choose a specific app date:

```bash
node scripts/generate_redesign_demo_backup.mjs 2026-07-16
```

The generator writes:

```text
docs/redesign/demo/habittracker-redesign-demo-v1.json
```

Validate the generated file against the app's real restore models:

```bash
./gradlew :app:testDebugUnitTest --tests '*RedesignDemoBackupTest' --console=plain
```

## Restore Demo Data

Push the backup to the emulator:

```bash
adb push docs/redesign/demo/habittracker-redesign-demo-v1.json /sdcard/Download/
```

In the app:

1. Open Settings.
2. Select Restore backup.
3. Choose Downloads and `habittracker-redesign-demo-v1.json`.
4. Select Confirm restore.
5. Return to Today.

The demo intentionally disables reminders and auto backup so it cannot create background noise on a review emulator.

## Demo Contents

The fixture includes:

- Daily, one-time, interval, weekday, sequence, and long-term task types.
- Pending, completed, skipped, missed, and pushed occurrences.
- A due manual phase review and an upcoming second phase.
- A nested workout with required and conditional exercises.
- Timer-compatible 30-second, 45-second, 60-second, and minute prescriptions.
- A prior sequence-step note visible as history.
- A 14-day interval cycle with completed, disrupted, and remaining slots plus Auto restart.
- An overdue long-term task using completion-date recurrence.
- An archived task.

All names and notes are synthetic and contain no owner backup data.

## Capture Screenshots

Set a stable device size before capture when needed:

```bash
adb shell wm size 1080x2400
adb shell wm density 420
```

Capture the current screen:

```bash
adb exec-out screencap -p > screenshot.png
```

Capture at least:

- Today summary and phase review.
- Today nested workout and cycle progress.
- Calendar month and selected-date details.
- Task creation form.
- Active phased-program group.
- Bulk phases input/preview.
- Stats metrics and note history.
- Settings reminders, permissions, and backup controls.
- Light and dark themes at narrow and standard phone widths.

Reset any `wm size` or `wm density` override afterward:

```bash
adb shell wm size reset
adb shell wm density reset
```

## Clean Reinstall

To remove all emulator app data:

```bash
adb shell pm clear com.example.habittracker
```

Do not run this against the owner's daily-use phone unless data has been safely backed up and deletion is intentional.
