# HabitTracker UI Redesign Handoff

This directory is the product and engineering handoff for redesigning HabitTracker without changing its scheduling behavior, stored data, reminders, or backup compatibility.

## Start Here

1. Read [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) for the current product behavior.
2. Read [`WORKFLOW_MAP.md`](WORKFLOW_MAP.md) for the main user journeys and state transitions.
3. Read [`REDESIGN_BRIEF.md`](REDESIGN_BRIEF.md) for visual goals, constraints, and acceptance criteria.
4. Use [`AI_HANDOFF_PROMPT.md`](AI_HANDOFF_PROMPT.md) as the initial prompt for a coding AI.
5. Follow [`EMULATOR_SETUP.md`](EMULATOR_SETUP.md) to run the app with sanitized representative data.
6. Compare against [`SCREENSHOT_CATALOG.md`](SCREENSHOT_CATALOG.md) and the files in `screenshots/`.

## Handoff Artifacts

| Artifact | Purpose |
| --- | --- |
| `FEATURE_INVENTORY.md` | Current screens, task types, actions, scheduling rules, reminders, history, and persistence behavior |
| `WORKFLOW_MAP.md` | End-to-end workflows and important UI states |
| `REDESIGN_BRIEF.md` | Design objectives, non-goals, guardrails, and required deliverables |
| `AI_HANDOFF_PROMPT.md` | Copyable implementation prompt for another coding AI |
| `EMULATOR_SETUP.md` | Build, install, demo-restore, and screenshot instructions |
| `PUBLIC_README.md` | Public repository landing page and reviewer quick start |
| `PUBLIC_RELEASE_SIGNING.md` | Public-safe debug/release build boundary |
| `demo/habittracker-redesign-demo-v1.json` | Sanitized backup with all task types and representative states |
| `screenshots/` | Labeled production UI reference images |

## Product Snapshot

- App version: `1.0.48` (`versionCode 49`)
- Android package: `com.example.habittracker`
- Minimum Android version: API 26
- Target Android version: API 35
- UI: Jetpack Compose with Material 3
- Persistence: Room and DataStore
- Background behavior: AlarmManager, WorkManager, and a foreground exercise-timer service
- Network behavior: no app `INTERNET` permission, analytics, or remote account system

## Source Of Truth

Use this priority when two artifacts appear to disagree:

1. Automated tests and persisted model contracts
2. `FEATURE_INVENTORY.md`
3. Current production UI behavior
4. Reference screenshots
5. Older documents elsewhere under `docs/`

The older MVP, verification, and device-acceptance documents contain historical context and test evidence. They are not a complete current product specification.

## Public Snapshot Safety

The public redesign repository is a single sanitized source snapshot. It intentionally excludes:

- Git history from the private repository
- `local.properties`, release keystores, passwords, and tokens
- Personal app backups and Google Drive data
- Private QA scratch files, APK staging folders, and device evidence
- Machine-specific Android SDK paths and device serials

The public repository contains no open-source license. Public visibility permits review and collaboration through GitHub but does not grant a general reuse license.
