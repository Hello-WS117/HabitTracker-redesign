# Copyable AI Handoff Prompt

Copy the prompt below into the coding AI that will perform the redesign.

```text
You are redesigning the production Android UI in this repository:
https://github.com/Hello-WS117/HabitTracker-redesign

Your job is to implement the redesign in Jetpack Compose, not merely describe it or produce static mockups.

Before editing code, read these files in order:
1. docs/redesign/FEATURE_INVENTORY.md
2. docs/redesign/WORKFLOW_MAP.md
3. docs/redesign/REDESIGN_BRIEF.md
4. docs/redesign/EMULATOR_SETUP.md
5. docs/redesign/SCREENSHOT_CATALOG.md

Run the existing app with the sanitized demo backup so you can inspect every major task type and state. Treat the feature inventory, workflow map, existing automated tests, Room schema, and backup schema as behavior contracts.

Primary objective:
Create a quieter, clearer, more polished daily-use interface with strong hierarchy, compact task cards, readable workouts, understandable progress, and reliable light/dark layouts.

Hard constraints:
- Preserve all task types and scheduling behavior.
- Preserve the package ID, Room entities/migrations, backup format, reminders, day boundary, and upgrade persistence.
- Preserve Complete, Skip, Push, Done yesterday, Undo, Switch item, Set point, phase review/extension, exercise checks/timers, long-term recurrence anchors, auto-restart, notes, archive, and delete behavior.
- Do not add network access, analytics, accounts, or cloud sync.
- Keep Material icons and accessibility semantics.
- Keep or deliberately update stable Compose test tags.
- Do not expose private signing material or add personal data.

Implementation process:
1. Audit the current composables and map them to the documented workflows.
2. Propose a concise component and screen structure before large edits.
3. Establish theme tokens and reusable primitives.
4. Implement one destination at a time while keeping the project compiling.
5. Use the demo backup to inspect empty, pending, decided, phase, workout, long-term, and history states.
6. Run unit tests, debug build, Android-test build, lint, and connected tests.
7. Capture before/after screenshots at 360 x 800 dp and 412 x 915 dp in light and dark themes.
8. Check every screenshot for clipped text, overlap, unstable dimensions, weak contrast, and redundant information.

Do not stop after presenting a plan. Carry the redesign through implementation and verification. Report any behavior you could not preserve before declaring the work complete.
```

## Optional Visual Direction Addendum

Append this only when the AI asks for a clearer aesthetic target:

```text
Visual direction: restrained operational utility. Use a neutral surface system with one primary accent plus semantic status colors. Favor typography, spacing, dividers, and alignment over decorative cards. Keep Today optimized for repeated scanning and one-handed action. Treat workouts as structured checklists, Calendar as a stable data grid, Tasks as a progressive editor, Stats as compact analysis, and Settings as grouped controls rather than a dashboard.
```
