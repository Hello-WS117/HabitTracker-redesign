# UI Redesign Brief

## Objective

Redesign HabitTracker into a clearer, faster, and more polished daily-use Android application while preserving every behavior in `FEATURE_INVENTORY.md`.

The result must be a working Jetpack Compose implementation, not a static mockup or marketing concept.

## Product Audience

The primary user manages daily habits, one-time tasks, long-running maintenance, workout sequences, and multi-phase programs. The app is opened repeatedly throughout the day. It should optimize for quick scanning, low-friction decisions, and confidence that schedule changes will behave predictably.

## Desired Character

- Quiet, focused, and operational.
- Dense enough for repeated use without feeling crowded.
- Strong hierarchy between due work, supporting context, and history.
- Clear status and progress communication without decorative clutter.
- Suitable for health, training, household, and general task routines without adopting one domain's visual language.

Avoid a marketing aesthetic, oversized hero content, decorative gradients, ornamental cards, or a one-color interface.

## Non-Goals

Do not change or remove:

- Task types or scheduling semantics.
- Database entities, Room migrations, backup schema, or package ID.
- Day-boundary behavior.
- Reminder delivery contracts.
- Complete, Skip, Push, Done yesterday, Undo, Switch item, or Set point behavior.
- Long-term recurrence anchors.
- Phase advancement and extension behavior.
- Auto-restart calculations or history preservation.
- Exercise completion and timer behavior.
- Existing test tags unless a test is deliberately updated with an equivalent stable replacement.

Do not add accounts, cloud synchronization, analytics, advertising, or network access.

## Information Architecture

Retain five primary destinations:

- Today
- Calendar
- Tasks
- Stats
- Settings

The redesign may improve titles, spacing, visual grouping, and navigation presentation, but the five destinations and their major workflows must remain directly reachable.

## Today Requirements

- Make pending daily work the strongest visual priority.
- Keep long-term tasks below all daily task sections with unmistakable separation.
- Keep phase reviews above the daily checklist because they can change the active program.
- Keep completed, skipped, pushed, and missed work collapsed initially.
- Preserve quick-add for one-time tasks.
- Show Morning, Noon, Evening, and General as compact peer classifications.
- Do not show redundant task-type labels on pending cards.
- Show sequence progress without listing the full sequence until expanded.
- Show fixed-length progress as text plus a readable segmented or stacked bar for completed, disrupted, and remaining slots.
- Make the distinction between status, task notes, occurrence notes, and sequence-step history obvious.
- Keep destructive or uncommon actions less prominent than Complete.

## Workout Requirements

- Show the complete workout for the current sequence day.
- Give each exercise a stable row that does not shift when its checkbox, timer, prescription, or conditional state changes.
- Keep exercise name, prescription, instructions, requirement, checkbox, and timer legible at phone width.
- Make the timer icon and remaining time understandable without enlarging the row excessively.
- Clearly communicate automatic day completion after all required exercises are checked.
- Keep Conditional and Not needed distinct from completion.

## Calendar Requirements

- Keep month navigation and Today shortcut predictable.
- Support rapid horizontal task filtering.
- Keep long-term filters at the end with a visible separator.
- Exclude one-time tasks from individual filter chips.
- Make multiple status markers distinguishable in light and dark themes, including for users who cannot rely on color alone.
- Keep the selected date details close enough to the month grid that the relationship is obvious.
- Avoid calendar cell resizing as markers change.

## Task Editor Requirements

- The creation/edit form must remain the first content and Edit must scroll to it.
- Make task type, dates, recurrence, cycle timing, behavior, blocked days, reminders, and visibility understandable as a progressive form.
- Hide End date until Set end date is selected.
- Keep calendar date pickers for start and end dates.
- Keep time-of-day choices adjacent and equally weighted.
- Keep Active tasks and Long-term tasks collapsed initially.
- Keep phased programs grouped as one top-level item with expandable phase details.
- Omit one-time tasks from Active tasks.
- Make archive reversible and permanent delete clearly destructive.
- Preserve Bulk phases and Sequence bulk paste as first-class creation paths.

## Stats Requirements

- Support fast task switching without overwhelming the top of the screen.
- Present metrics in a compact, comparable grid.
- Keep Recent history and Activity log conceptually distinct.
- Show user notes beneath date and sequence item, using typography or surface treatment different from action/status metadata.
- Do not repeat action-generated text as if it were a note.
- Preserve Calendar history access.

## Settings Requirements

- Use real clock-time pickers for every time setting.
- Keep reminder enablement separate from the chosen time.
- Clearly show whether exact alarms and notifications are allowed.
- Keep manual backup, automatic backup, and restore understandable as separate operations.
- Keep restore replacement warnings and confirmation conspicuous.
- Do not reintroduce fixed morning/noon/evening preset blocks; each is independently configurable.

## Visual System

- Use Material 3 components where they fit, with a restrained custom theme.
- Use familiar Material icons for navigation and actions.
- Keep cards at 8 dp radius or less.
- Do not place cards inside cards.
- Use full-width unframed sections where a card does not add a real boundary.
- Use 48 dp minimum touch targets.
- Maintain stable component dimensions for status chips, calendar cells, progress bars, icon buttons, and exercise controls.
- Do not scale typography with viewport width.
- Use zero letter spacing unless an existing Material text style supplies otherwise.
- Limit prominent accent colors and preserve semantic status colors.
- Support System, Light, and Dark themes.
- Verify contrast for text, icons, status markers, disabled actions, and progress states.

## Responsive And Accessibility Requirements

Test at minimum:

- 360 x 800 dp portrait.
- 412 x 915 dp portrait.
- Large font scale.
- Light and dark themes.
- Long task, phase, sequence item, exercise, and note text.

Required behavior:

- No clipped labels or overlapping controls.
- Buttons wrap or reflow without changing unrelated layout.
- Every icon-only control has a content description and tooltip where unfamiliar.
- Calendar days expose date, selection, today state, item count, and statuses to accessibility services.
- Status must not be communicated through color alone.
- Dynamic content such as a running timer must not resize its row.
- Existing Compose test tags should remain stable where practical.

## Architecture Guidance

The current UI is concentrated in:

- `app/src/main/java/com/example/habittracker/ui/HabitTrackerApp.kt`
- `app/src/main/java/com/example/habittracker/ui/HabitTrackerUiState.kt`

The redesign may split large composables into focused files and introduce a small design system. Keep the state store, repository, Room entities, scheduling generator, reminder layer, and backup models behind their current ownership boundaries.

Preferred approach:

1. Add theme tokens and reusable low-level components.
2. Move screen composables into separate files without changing behavior.
3. Redesign one destination at a time.
4. Keep each step compiling and tested.
5. Avoid a broad state-management rewrite during visual work.

## Required Deliverables

- Working Compose implementation for all five destinations.
- Light and dark theme screenshots at both required phone widths.
- Screenshots for empty, pending, mixed-status, long-term, workout, phase-review, and restore-confirmation states.
- Updated tests for any intentional structural or accessibility changes.
- A short change map describing which composables moved or changed.
- A list of any behavior that could not be preserved, with evidence and rationale.

## Acceptance Gates

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Also run connected tests on an Android emulator when available:

```bash
./gradlew :app:connectedDebugAndroidTest --console=plain
```

The redesign is not complete until:

- All automated gates pass.
- The sanitized demo backup restores successfully.
- Every workflow in `WORKFLOW_MAP.md` remains reachable.
- Screenshot review finds no clipping, overlap, blank canvas, accidental nested cards, or unreadable status distinctions.
- App data survives install-over upgrade with the same package ID.
- No signing material, private backup, or personal data is added to Git.
