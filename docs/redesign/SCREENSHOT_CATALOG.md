# Screenshot Catalog

The images in `screenshots/` are production UI references captured with the sanitized redesign demo backup. They document current information and controls, not a visual target that must be copied.

| File | State represented | Redesign behaviors to preserve |
| --- | --- | --- |
| `01-today-phase-review.png` | Today summary and due manual phase review | Review priority, minimum duration, Advance tomorrow, Extend 1 week |
| `02-today-daily-tasks.png` | Pending daily task cards | Time classification, status, Complete/Skip/Push/Note/Stats hierarchy |
| `03-today-workout.png` | Nested sequence workout | Full exercise list, prescriptions, checkboxes, conditional state, row timers |
| `04-today-cycle.png` | Fixed-length cycle task | Completed/disrupted/remaining progress and reset threshold |
| `05-today-long-term.png` | Overdue long-term task | Separate placement below daily tasks, persistent due state, Complete-only action |
| `06-calendar.png` | Month grid and task filters | Stable cells, status legend, horizontal filters, long-term separator |
| `07-tasks-create.png` | Task creation form | Bulk phases, date controls, task types, progressive configuration |
| `08-tasks-routine-group.png` | Active phased routine group | Collapsed active section and one top-level grouped program |
| `09-bulk-phases.png` | Multi-phase importer | Top-level paste, copyable AI guide, validation, and preview workflow |
| `10-stats.png` | Task metrics and history summary | Compact metrics and occurrence history |
| `11-stats-history.png` | Sequence-item history and user note | Status tags, per-item context, and visually separate user note |
| `12-settings-reminders.png` | Reminder and alarm settings | Clock pickers, alarm permission state, late-day and daily review controls |
| `13-settings-backup.png` | Backup settings | Manual and automatic backup separation, interval, folder, and status |

## Capture Notes

- Device profile: Android API 35 phone emulator.
- Data source: `docs/redesign/demo/habittracker-redesign-demo-v1.json`.
- The screenshots contain only synthetic task names and notes.
- Status colors should be treated as semantic references; a redesign must also provide non-color status cues.
- Additional narrow-width and dark-theme screenshots may be added during redesign QA rather than treated as frozen golden images.
