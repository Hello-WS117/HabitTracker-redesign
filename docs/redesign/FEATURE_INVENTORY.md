# Current Feature Inventory

This document describes the user-visible behavior of HabitTracker version 1.0.50. It is a preservation contract for a UI redesign, not a proposal for new behavior.

## Product Model

HabitTracker is a local-first Android task, habit, and routine scheduler. It generates dated occurrences from task rules, records decisions on each occurrence, and preserves history independently from future schedule changes.

The app has five primary destinations:

| Destination | Current title | Primary purpose |
| --- | --- | --- |
| Today | Daily Checklist | Act on tasks due for the current app day |
| Calendar | Monthly Calendar | Inspect occurrences and status by date or task |
| Tasks | Task Editor | Create, import, edit, archive, group, and delete tasks |
| Stats | Task Detail | Inspect metrics, occurrence history, notes, and activity logs |
| Settings | Backup & Settings | Configure day boundary, reminders, permissions, theme, and backups |

## App Day And Date Boundary

- The app day changes at a configurable clock time rather than always at midnight.
- The default boundary is 3:00 AM.
- Before the configured boundary, actions count toward the prior calendar date.
- The UI calls this the daily reset time. The implementation and older documents may call it the operational day.
- Schedule maintenance marks or moves unattended past occurrences after the day changes.
- The selected Calendar date and Today date remain distinct concepts.

## Today

The Today destination is organized in this order:

1. Daily completion summary, remaining count, reset time, percentage, and progress bar.
2. Due manual phase-review cards.
3. Quick-add button for a one-time task.
4. Daily checklist heading.
5. Eligible "Done yesterday" corrections.
6. Pending daily task cards.
7. Collapsed Completed, Skipped, Pushed, and Missed sections.
8. Pending long-term tasks.
9. Collapsed recently completed long-term tasks.

Only occurrences assigned to the current app day appear in the daily checklist. Overdue long-term tasks are the deliberate exception: they remain in their separate section until completed.

### Daily Task Card

A card may show:

- Task name.
- Morning, Noon, Evening, or General time classification.
- Current status.
- Current sequence item as `Today: item name`.
- Manual phase minimum length.
- Length-based cycle completion, disruption, remaining counts, and progress bar.
- Nested exercises for the current workout day.
- Collapsible task notes, occurrence note, and matching sequence-step note history.
- Collapsible full sequence list with exactly one current item marked Today.
- Actions allowed by the task type and current state.

### Checklist Actions

| Action | Behavior |
| --- | --- |
| Complete | Marks the occurrence complete and records history. A workout with required exercises completes automatically when all required exercises are checked. |
| Skip | Marks the occurrence skipped without completing it. Long-term tasks do not offer Skip. |
| Push | Moves a pushable occurrence to the next allowed day. Sequence pushes preserve sequence order and may move later pending items. |
| Done yesterday | Corrects the next pending pushable occurrence when it should count for the prior day and realigns following dates. |
| Undo | Returns a decided occurrence to an actionable state and repairs any associated schedule effects. |
| Note | Adds or edits a note on this occurrence. Sequence occurrence notes belong only to that sequence step's history. |
| Stats | Opens that task in Task Detail. |
| Switch item | Exchanges today's sequence item with another pending item without changing the rest of the sequence order. |
| Set point | Changes the current sequence position and carries the routine forward from the selected item. |

### Statuses

| Status | Meaning |
| --- | --- |
| Pending | Due and no decision has been recorded. |
| Completed | Finished. |
| Skipped | Intentionally not performed. |
| Missed | The app day passed with no action when the configured behavior was Mark missed. |
| Pushed | Shifted forward to a later allowed date. |

Action-generated messages such as "Marked complete" and "Pushed forward" remain activity logs. They are not displayed as user notes.

## Task Types

### Simple Habit

- Generates every allowed app day.
- Equivalent to daily recurrence, so the Interval editor does not offer every 1 day.
- Can optionally allow manual Push.
- Unattended behavior can be Mark missed, Auto-skip, or Auto-push.
- Can have a fixed length and participate in dependent length-based timing.

### One-Time Task

- Has one initial due date.
- Is quick-addable from Today or configurable in the full Task Editor.
- Does not appear as a selectable task chip in the Calendar filter row, though its occurrence still appears in all-task calendar results.
- Does not appear under Active tasks in the Task Editor.
- Is pushable.
- Defaults to Auto-push each day until completed, but the user can explicitly Complete, Skip, or Push it.
- Does not show cycle progress.

### Interval Task

- Repeats every N days, with a minimum interval of 2 days.
- The start date is the recurrence anchor.
- Blocked-day behavior determines whether blocked dates are omitted or moved.
- Completion does not reset the normal recurrence anchor.
- Can have a fixed length, dependencies, and auto-restart behavior.

### Weekday Task

- Repeats on one or more selected weekdays.
- Selected weekdays and blocked days are independent settings.
- Can have a fixed length, dependencies, and auto-restart behavior.

### Sequence Task

- Cycles through an ordered list of named items.
- Supports one or more days between sequence items.
- The Today card shows only the current item as Today.
- The full sequence can be expanded.
- The current point can be changed without restarting from the first item.
- Today's item can be swapped with another pending item without changing all later sequence positions.
- Push preserves the routine order when future pending items move.
- Each sequence item has its own occurrence-note history.
- A sequence item may contain a nested workout day with independently checkable exercises.
- Can have a fixed length, dependencies, and auto-restart behavior.

### Long-Term Task

- Repeats by a number of days, weeks, months, or years.
- Appears below daily tasks in a separate Today section once due.
- Remains visible when overdue until completed.
- Offers Complete and Undo but not Skip or Push.
- Can calculate the next occurrence from either:
  - Completion date, which is the default and shifts the next due date when completed late.
  - Original due date, which preserves a fixed cadence.
- Appears in a separate section at the end of the Calendar filter row.
- Appears in a separate collapsed Long-term tasks section in the Task Editor.

## Common Task Configuration

The full editor supports these fields where applicable:

- Name and task-level notes.
- Start date using a calendar picker.
- Optional end date using No end or Set end date and a calendar picker.
- Task type.
- Morning, Noon, Evening, or General classification.
- Recurrence-specific controls.
- Allowed manual Push.
- No-action behavior.
- Blocked weekdays.
- Behavior when a generated date is blocked.
- Reminder eligibility.
- Calendar visibility.
- Active and archived state.

### Blocked-Date Behavior

| Choice | Current behavior |
| --- | --- |
| Skip that date | Do not generate an occurrence on that blocked date. |
| Move to next allowed day | Move the occurrence forward to the next non-blocked date. |
| Skip until changed | Currently omits blocked dates until the task configuration changes; it does not show an interactive same-day prompt. |

### No-Action Behavior

| Choice | Behavior after the app day passes |
| --- | --- |
| Mark missed | Preserve the occurrence as Missed. |
| Auto-skip | Mark it Skipped automatically. |
| Auto-push | Move it to the next valid date and enable Push if necessary. |

## Length-Based Timing And Dependencies

- Simple, Interval, Weekday, and Sequence tasks can have a fixed number of days.
- Enabling Set length calculates and shows the end date.
- A fixed-length task shows cycle progress only when it has more than one day.
- Progress counts expected recurrence slots within the current length window, not all calendar days.
- Progress distinguishes completed, disrupted, and remaining slots.
- Another fixed-length task can use Start after to begin after a selected parent task ends.
- Editing, shifting, or restarting a parent realigns dependent future rules while preserving historical decisions.

## Auto-Restart For Length-Based Tasks

Auto-restart belongs to an individual length-based task, not to a multi-task cycle-group editor.

Settings:

- Restart behavior: Off, Suggest restart, or Auto restart.
- Reset threshold: 1 to 100 percent of the cycle duration.
- A tap changes the threshold by 1 percent; holding changes it repeatedly in 5 percent steps.
- Restart timing: Today, Tomorrow, or Next valid day.
- Optional blocked restart weekdays.

Disrupted days are distinct dates in the current cycle whose occurrence is Skipped, Missed, or Pushed. The threshold day count rounds up. For example, 50 percent of 14 days is 7 disrupted days.

When Auto restart triggers:

- Historical occurrences and logs remain intact.
- Future pending occurrences from the restart date are removed.
- The recurrence start and end are shifted to the new cycle window.
- Future occurrences are regenerated from the first sequence position when relevant.
- Dependents are realigned.
- A cycle-history entry records why the restart occurred.

Suggest restart records a suggestion rather than automatically changing the schedule.

## Sequence Workouts

Nested workouts attach exercises to one sequence item, not to the sequence as a whole.

Each exercise stores:

- Name.
- Prescription such as sets, reps, time, distance, or intensity.
- Instructions or form cues.
- Required or Conditional status.

Behavior:

- Required exercises use checkboxes.
- The workout occurrence completes when every required exercise is complete.
- Unchecking a required exercise reopens a completed workout day.
- Conditional exercises can be marked Not needed.
- Rest days may contain no exercises.
- A prescription containing a recognizable duration receives a row-level countdown timer.
- The timer can Start, Pause, Resume, and Restart.
- The timer uses a foreground service while running, remains active when the phone is locked, and plays a completion sound alongside other media.

## Bulk Sequence Import

- The Sequence task editor accepts one item per line.
- Bulk paste parses multiple items, previews the resulting rows, and applies them to the draft.
- A copyable AI formatting guide is available inside the dialog.

## Phased Programs

Bulk phases imports multiple related sequence tasks as one routine plan.

Structured import supports:

- A phase name and minimum length.
- Numbered workout days rather than weekday names.
- Nested exercises with prescriptions and instructions.
- Automatic or Manual advancement.
- A progression-review question for manual phases.
- Full preview before applying.

Routine behavior:

- A routine plan appears as one grouped item in Active tasks.
- Its phases remain individually editable and retain separate histories.
- Archiving or deleting the group applies to all phases.
- Exactly one phase is active at a time.
- Automatic phases advance after their minimum duration.
- Manual phases show a review card after their minimum duration.
- A manual review offers Advance tomorrow or Extend 1 week.
- Advancing closes the current phase, activates the next phase on the next valid day, and generates its occurrences.
- Extending records the review and asks again seven days later.
- The active phase minimum length appears on its Today task card.

## Calendar

- Month navigation supports previous month, next month, and return to Today.
- A horizontal filter row supports All tasks and individual task views.
- One-time tasks are excluded from individual filter chips.
- Long-term filter chips come last after a visual Long-term separator.
- Calendar cells show status markers for Completed, Skipped, Missed, Upcoming, Pushed, and Pending.
- Selecting a date lists its visible occurrences.
- Date rows show task name, current sequence item, status, notes, and relevant actions.
- Calendar visibility hides a task from calendar views without removing it from Today.
- Archived and inactive tasks are hidden from All tasks but remain inspectable when explicitly opened through their history paths.

## Task Editor And Lifecycle

- The task creation form is always at the top.
- Selecting Edit scrolls back to that form.
- Bulk phases is available above the standard fields.
- Active tasks start collapsed.
- Active phased programs are grouped as a single routine plan.
- Active long-term tasks have their own collapsed section.
- One-time tasks are omitted from Active tasks.
- Archived and inactive items are hidden behind a switch.
- Archive preserves all history and can be reversed.
- Permanent Delete removes the task and its associated schedule/history after confirmation.
- A phased program can be archived or permanently deleted as one group.

## Stats And History

Task Detail supports:

- Horizontal task selection, including archived tasks.
- Task recurrence, sequence, time classification, push setting, no-action setting, and task notes.
- Current streak and longest streak.
- Completion percentage and total completed.
- Skipped count and rate.
- Missed count and rate.
- Pushed count.
- Past occurrence total.
- Recent occurrence history with per-occurrence notes.
- Activity log for actions and schedule changes.
- Calendar history shortcut.

User notes appear as visually distinct text beneath the date and sequence item. Status changes are represented by status/action labels rather than duplicated as notes.

## Reminders

Reminder settings use clock-time pickers.

Available reminders:

- Daily review at a configured time.
- Late-day reminder only when eligible pending tasks remain.
- Morning task reminder.
- Noon task reminder.
- Evening task reminder.

Task-level Reminders controls eligibility. Android notification permission and exact-alarm access are both required for precise delivery. The Settings screen links to system permission controls and can refresh permission state.

Alarms are rescheduled after boot, package replacement, manual clock change, and timezone change. Notifications use generic count-based copy and private lock-screen visibility so task names and notes are not exposed.

## Settings

The Settings destination includes:

- Configurable new-day/reset clock time.
- Reminder enable switches and clock-time pickers.
- Default blocked weekdays for newly created tasks.
- System, Light, or Dark theme.
- Exact-alarm and notification permission status/actions.
- Manual backup and restore.
- Automatic backup enable switch, interval in days, and Android document-tree folder selection.

## Backup And Restore

- Backup format is versioned JSON with schema version 1.
- Manual backup stages and validates the complete payload in app-private storage before opening Android's document picker.
- Manual backup writes the selected final `.json` document directly and does not require the provider to support rename.
- Automatic backup uses a user-selected document-tree folder and WorkManager with retry backoff after provider failures.
- Automatic backup verifies a `.pending` document first, uses atomic rename when supported, and otherwise writes and verifies a final copy before deleting the pending document.
- A backup includes tasks, rules, occurrences, logs, sequences, exercises, exercise checks, routine plans/phases, auto-restart data, and settings.
- Every backup write is read back, checked for non-zero size, exact byte and provider-size equality, parseability, and schema validity.
- A verified app-private safety copy is retained before each external export.
- Settings reports the last verified byte size and the latest automatic-backup failure reason, and allows an immediate folder backup.
- Restore validates size, IDs, references, dates, enum values, sequence positions, and status relationships before replacing data.
- Restore requires explicit confirmation and offers Back up first.
- A failed restore leaves existing data intact.
- Reminder settings are reconciled after restore.

## Persistence And Upgrade Contracts

- Room stores tasks, recurrence rules, generated occurrences, logs, sequences, exercises, phases, and auto-restart data.
- DataStore stores lightweight app settings.
- Future occurrences are materialized rather than calculated only at render time.
- Normal tasks are generated at least 60 days ahead; long-term tasks use a longer horizon.
- Editing schedule fields deletes and regenerates only future pending occurrences.
- Completed, skipped, missed, pushed, and noted history remains preserved.
- Database migrations are versioned and exported under `app/schemas`.
- The package ID and release signing identity remain stable so installed data persists across upgrades.

## Redesign Behavior Guardrails

A redesign must not silently change:

- Which date is considered Today around the configured reset time.
- Which tasks appear in daily versus long-term sections.
- One-time Auto-push behavior.
- Interval anchoring or blocked-day handling.
- Sequence order, Set point, Switch item, or Push effects.
- Exercise completion rules and conditional Not needed state.
- Phase review timing, seven-day extension, or next-phase activation.
- Completion-based versus due-date long-term recurrence.
- Auto-restart threshold calculation and history preservation.
- Undo behavior.
- Calendar visibility semantics.
- Reminder eligibility and exact-alarm requirements.
- Backup compatibility, validation, or upgrade persistence.
