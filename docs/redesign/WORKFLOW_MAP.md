# Workflow Map

This map describes the main user journeys and the state changes a redesign must continue to support.

## 1. Open The App And Review Today

Entry: launch the app or select Today.

1. Resolve the current app day from the device clock and configured reset time.
2. Run schedule maintenance and load persisted tasks, occurrences, phase reviews, settings, and timers.
3. Show completion summary and any phase review due.
4. Show pending daily occurrences in time-of-day order.
5. Keep decided daily states in collapsed status sections.
6. Show overdue long-term tasks after all daily sections.

Important states:

- Empty day.
- Pending daily tasks.
- A mixture of completed, skipped, missed, and pushed tasks.
- A due phase review.
- An overdue long-term task.
- A running exercise timer.

## 2. Create A Standard Task

Entry: Tasks destination.

1. Enter name and optional notes.
2. Choose start date.
3. Optionally choose Set end date or Set length.
4. Choose one task type.
5. Configure recurrence-specific controls.
6. Choose General, Morning, Noon, or Evening.
7. Configure Push and no-action behavior when available.
8. Configure blocked days and blocked-date behavior.
9. Configure reminder eligibility, calendar visibility, and active state.
10. Create task.
11. Generate future occurrences and refresh Today, Calendar, Tasks, and Stats.

Validation and dependent UI:

- Name is required.
- End date is hidden while No end is selected.
- Interval cannot be 1 day.
- Weekday recurrence needs at least one selected weekday.
- Sequence recurrence needs at least one item.
- Long-term recurrence requires a positive interval and unit.
- Auto-restart controls appear only after Set length.
- Start after choices include other tasks that have a fixed length and end date.

## 3. Quick Add A One-Time Task

Entry: Today, Quick add 1-time task.

1. Enter a name.
2. Create the task due today with Auto-push behavior.
3. Act on it with Complete, Skip, or Push.
4. If no action is taken, maintenance moves it forward until it is completed or explicitly skipped.

The one-time task does not appear in Active tasks or as a Calendar filter chip.

## 4. Complete, Skip, Push, Or Undo

Entry: a Today task card.

### Complete

`Pending -> Completed`

- Record a completion log.
- Update summary, metrics, history, and calendar marker.
- For a completion-anchored long-term task, calculate its next due date from this completion date.

### Skip

`Pending -> Skipped`

- Record a skip log.
- Leave sequence order unchanged unless other explicit scheduling logic applies.

### Push

`Pending -> Pushed`, plus a later pending occurrence.

- Find the next valid non-blocked date.
- Preserve original-date history.
- For sequences, keep later pending items in order.
- For fixed-length tasks, update disruption progress and evaluate auto-restart.

### Undo

`Completed/Skipped/Missed/Pushed -> actionable state`

- Reverse the decision.
- Remove or repair generated future effects where needed.
- Restore long-term future timing when undoing completion.
- Recalculate stats and Today sections.

## 5. Correct A Task As Done Yesterday

Entry: Today eligibility card or a pushable card.

1. The app identifies the next pending pushable occurrence that can legally move to yesterday.
2. Select Done yesterday.
3. Mark it completed on the prior app day.
4. Realign later pending occurrences using the task spacing and blocked days.
5. Preserve existing historical occurrences.

## 6. Perform A Nested Workout

Entry: a sequence occurrence with exercises.

1. Review the current workout-day title and full exercise list.
2. Read prescription and instructions for each exercise.
3. Start a row timer when the prescription contains a duration.
4. Leave the app or lock the phone while the foreground timer continues.
5. Hear the completion sound when time expires.
6. Check each required exercise when complete.
7. Mark a conditional exercise Not needed when its condition does not apply.
8. Automatically complete the workout occurrence when every required exercise is checked.
9. Uncheck an exercise to reopen an accidentally completed workout.
10. Add an occurrence note for this specific sequence item.

## 7. Adjust A Sequence Without Rebuilding It

Entry: a pending sequence task on Today.

### Switch item

1. Open Switch item.
2. Choose another pending occurrence from the same sequence.
3. Exchange the two item assignments.
4. Keep all other dates and items unchanged.

### Set point

1. Open Set point.
2. Choose any sequence item.
3. Assign that item to today.
4. Continue later pending occurrences in order from that point.

The expanded sequence list must identify only the current position as Today, even when an item name appears more than once.

## 8. Create A Multi-Phase Program

Entry: Tasks, Bulk phases.

1. Copy the in-app AI formatting instructions.
2. Ask an AI to return PHASE, REVIEW, DAY, EXERCISE, and END PHASE rows.
3. Paste all phases together.
4. Resolve any row-level format issues.
5. Review the preview table, dates, minimum lengths, sequence days, exercise counts, and advancement modes.
6. Apply once to create the whole routine plan.
7. Keep the first phase active and later phases grouped as upcoming.

## 9. Review And Advance A Manual Phase

Entry: Today after the active phase minimum duration has elapsed.

1. Read the top-level progression condition.
2. Review the minimum length, elapsed days, and next phase name.
3. Choose Advance tomorrow when ready.
4. The current phase closes today and the next phase activates on the next allowed day.

Alternative:

1. Choose Extend 1 week.
2. Keep the current phase active.
3. Record the extension in history.
4. Show the review again after seven days.

Automatic phases perform the transition when their minimum duration ends without showing this decision card.

## 10. Manage A Long-Term Task

Entry: create Long-term in Tasks, then use Today when due.

1. Set interval and Days, Weeks, Months, or Years.
2. Choose Completion date or Due date recurrence anchor.
3. Wait until due; it then appears in Long-term tasks.
4. Leave it incomplete across days without losing it.
5. Complete it when done.
6. Calculate its next occurrence from the selected anchor.
7. Undo immediately when completion was accidental.

There is no Skip or Push action.

## 11. Configure A Fixed-Length Auto-Restart

Entry: a supported task in Tasks.

1. Enable Set length and choose the number of days.
2. Choose Suggest restart or Auto restart.
3. Set a threshold percentage.
4. Choose Today, Tomorrow, or Next valid day.
5. Optionally block restart weekdays.
6. Save.
7. Accumulate distinct disrupted dates through skips, misses, or pushes.
8. At the rounded-up threshold, log a suggestion or restart automatically.
9. On restart, preserve history, remove future pending occurrences, shift the cycle window, regenerate, and realign dependents.

## 12. Inspect Calendar And Add A Historical Note

Entry: Calendar.

1. Move to a month or return to Today.
2. Select All tasks or one task chip.
3. Read status markers in the month grid.
4. Select a date.
5. Inspect that date's task rows.
6. Open a task detail or add/edit an occurrence note.
7. For a sequence occurrence, retain the note under only that sequence item.

## 13. Inspect Stats And History

Entry: Stats or a card's Stats action.

1. Select a task.
2. Review recurrence and behavior summary.
3. Review streak, completion, skip, miss, push, and total metrics.
4. Scan recent occurrence history.
5. Distinguish user notes from statuses and activity labels.
6. Review the activity log for edits, automatic behavior, phase transitions, and cycle events.
7. Open Calendar history for date context.

## 14. Edit, Archive, Or Delete

Entry: Tasks, expand Active tasks, Long-term tasks, or archived/inactive items.

### Edit

1. Select Edit.
2. Scroll automatically to the form.
3. Change fields and Save task.
4. Preserve historical occurrences and regenerate only affected future pending occurrences.

### Archive

1. Archive one task or an entire phase group.
2. Hide it from active Today, Calendar all-task, and maintenance behavior.
3. Preserve history and allow unarchive.

### Delete

1. Choose permanent Delete and confirm.
2. Remove the task or all tasks in a phase group plus linked records.
3. This action is intentionally irreversible inside the app; backups remain the recovery path.

## 15. Configure Reminders

Entry: Settings.

1. Enable Daily review, Late-day unchecked reminder, and/or Task time reminders.
2. Set each clock time with a time picker.
3. Grant notification permission.
4. Grant exact-alarm access.
5. Keep task-level Reminders enabled for eligible tasks.
6. Reconcile alarms after any setting change.

Expected notification behavior:

- Daily review reports the number of eligible due tasks.
- Late-day posts only when eligible pending tasks remain.
- Morning, Noon, and Evening reminders use each task's classification.
- Lock-screen text remains generic.

## 16. Back Up And Restore

Entry: Settings, Backup and restore.

### Manual backup

1. Select Back up now.
2. Choose a destination in Android's document picker.
3. Write a timestamped JSON document.
4. Read it back and validate non-zero content before reporting success.

### Automatic backup

1. Enable Auto app backup.
2. Choose a document-tree folder.
3. Set the interval in days.
4. WorkManager writes and verifies backups at the configured cadence.

### Restore

1. Select Restore backup and choose a JSON file.
2. Review the replacement warning.
3. Optionally select Back up first.
4. Confirm restore.
5. Validate the complete file before replacing local data.
6. Reload all screens and reschedule reminders.

Failure path: reject the file with a non-technical error category and retain all pre-restore data.
