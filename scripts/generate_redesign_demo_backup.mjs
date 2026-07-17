#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const DAY_MILLIS = 24 * 60 * 60 * 1000;
const WEEKDAYS = [
  "SUNDAY",
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
];

function localToday() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function parseDate(value) {
  if (!DATE_PATTERN.test(value)) {
    throw new Error(`Date must use YYYY-MM-DD: ${value}`);
  }
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value) {
    throw new Error(`Invalid calendar date: ${value}`);
  }
  return date;
}

function addDays(value, days) {
  return new Date(parseDate(value).getTime() + days * DAY_MILLIS)
    .toISOString()
    .slice(0, 10);
}

function weekday(value) {
  return WEEKDAYS[parseDate(value).getUTCDay()];
}

function timestamp(value, time = "08:00:00") {
  return `${value}T${time}`;
}

const baseDate = process.argv[2] ?? localToday();
parseDate(baseDate);

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outputPath = path.join(
  repositoryRoot,
  "docs",
  "redesign",
  "demo",
  "habittracker-redesign-demo-v1.json",
);
const createdDate = addDays(baseDate, -45);
const createdAt = timestamp(createdDate, "09:00:00");
const updatedAt = timestamp(baseDate, "07:30:00");

function task({
  id,
  name,
  taskType,
  notes = "",
  isActive = true,
  archived = false,
  reminder = false,
  calendarVisible = true,
  blockedDays = [],
  timeOfDay = "GENERAL",
  pushable = false,
  noActionBehavior = "MARK_MISSED",
}) {
  return {
    id,
    name,
    taskType,
    notes,
    isActive,
    archived,
    createdAt,
    updatedAt,
    defaultReminderEnabled: reminder,
    calendarVisible,
    blockedDays,
    timeOfDay,
    pushable,
    noActionBehavior,
  };
}

function rule({
  id,
  taskId = id,
  ruleType,
  intervalDays = null,
  weekdays = [],
  cycleDefinition = "",
  startDate,
  endDate = null,
  durationDays = null,
  startsAfterTaskId = null,
  skipBlockedDaysBehavior = "SKIP_BLOCKED_DAY",
  lastGeneratedDate = addDays(baseDate, 60),
  autoRestartBehavior = "OFF",
  autoRestartTiming = "TODAY",
  autoRestartResetThresholdPercent = 50,
  autoRestartBlockedDays = [],
  autoRestartCurrentStartDate = null,
  autoRestartLastRestartedAt = null,
}) {
  return {
    id,
    taskId,
    ruleType,
    intervalDays,
    weekdays,
    cycleDefinition,
    startDate,
    endDate,
    durationDays,
    startsAfterTaskId,
    skipBlockedDaysBehavior,
    lastGeneratedDate,
    autoRestartBehavior,
    autoRestartTiming,
    autoRestartResetThresholdPercent,
    autoRestartBlockedDays,
    autoRestartCurrentStartDate,
    autoRestartLastRestartedAt,
    createdAt,
    updatedAt,
  };
}

function occurrence({
  id,
  taskId,
  date,
  status = "PENDING",
  sequenceItemId = null,
  isShifted = false,
  originalDate = null,
  note = "",
}) {
  return {
    id,
    taskId,
    recurrenceRuleId: taskId,
    scheduledDate: date,
    operationalDate: date,
    status,
    sequenceItemId,
    isShifted,
    originalDate,
    note,
    createdAt,
    updatedAt: timestamp(date, "20:00:00"),
  };
}

let nextLogId = 1;
function log({ occurrenceId = null, taskId, action, date, note }) {
  return {
    id: nextLogId++,
    occurrenceId,
    taskId,
    action,
    timestamp: timestamp(date, "20:05:00"),
    operationalDate: date,
    note,
    createdAt: timestamp(date, "20:05:00"),
  };
}

const tasks = [
  task({
    id: 1,
    name: "Morning planning",
    taskType: "SIMPLE_HABIT",
    notes: "Choose the three outcomes that matter most today.",
    timeOfDay: "MORNING",
  }),
  task({
    id: 2,
    name: "Clean car",
    taskType: "QUICK_ONE_TIME",
    notes: "Synthetic one-time task for redesign review.",
    pushable: true,
    noActionBehavior: "AUTO_PUSH",
  }),
  task({
    id: 3,
    name: "Hair oil",
    taskType: "INTERVAL",
    notes: "Apply a small amount and note any irritation.",
    timeOfDay: "EVENING",
    pushable: true,
  }),
  task({
    id: 4,
    name: "Weekly planning",
    taskType: "WEEKDAY_BASED",
    notes: "Review open commitments and choose next actions.",
    timeOfDay: "NOON",
  }),
  task({
    id: 5,
    name: "Foundation strength",
    taskType: "SEQUENCE_ROUTINE",
    notes: "Keep effort controlled and stop if form changes.",
    timeOfDay: "MORNING",
    pushable: true,
    noActionBehavior: "AUTO_PUSH",
  }),
  task({
    id: 6,
    name: "Replace air filter",
    taskType: "LONG_TERM",
    notes: "Replace the main return filter and record the size used.",
  }),
  task({
    id: 7,
    name: "CO2 tables",
    taskType: "INTERVAL",
    notes: "Fourteen-day training cycle with automatic restart protection.",
    timeOfDay: "EVENING",
    pushable: true,
    noActionBehavior: "AUTO_PUSH",
  }),
  task({
    id: 8,
    name: "Power progression",
    taskType: "SEQUENCE_ROUTINE",
    notes: "Upcoming synthetic phase used to demonstrate grouped programs.",
    isActive: false,
    timeOfDay: "MORNING",
    pushable: true,
    noActionBehavior: "AUTO_PUSH",
  }),
  task({
    id: 9,
    name: "Archived meditation",
    taskType: "SIMPLE_HABIT",
    notes: "Archived example retained only for history review.",
    isActive: false,
    archived: true,
    calendarVisible: false,
    timeOfDay: "EVENING",
  }),
];

const cycleStart = addDays(baseDate, -8);
const recurrenceRules = [
  rule({ id: 1, ruleType: "DAILY", startDate: addDays(baseDate, -10) }),
  rule({
    id: 2,
    ruleType: "DAILY",
    startDate: baseDate,
    endDate: baseDate,
    durationDays: 1,
    lastGeneratedDate: baseDate,
  }),
  rule({ id: 3, ruleType: "EVERY_X_DAYS", intervalDays: 2, startDate: addDays(baseDate, -6) }),
  rule({
    id: 4,
    ruleType: "WEEKDAYS",
    weekdays: [weekday(baseDate), weekday(addDays(baseDate, 3))].sort(),
    startDate: addDays(baseDate, -14),
  }),
  rule({
    id: 5,
    ruleType: "SEQUENCE",
    intervalDays: 1,
    startDate: addDays(baseDate, -15),
    skipBlockedDaysBehavior: "MOVE_TO_NEXT_VALID_DAY",
  }),
  rule({
    id: 6,
    ruleType: "EVERY_X_MONTHS",
    intervalDays: 6,
    cycleDefinition: "COMPLETION_DATE",
    startDate: addDays(baseDate, -3),
    skipBlockedDaysBehavior: "MOVE_TO_NEXT_VALID_DAY",
    lastGeneratedDate: addDays(baseDate, 3650),
  }),
  rule({
    id: 7,
    ruleType: "EVERY_X_DAYS",
    intervalDays: 2,
    startDate: cycleStart,
    endDate: addDays(cycleStart, 13),
    durationDays: 14,
    lastGeneratedDate: addDays(cycleStart, 13),
    autoRestartBehavior: "AUTO_RESTART",
    autoRestartTiming: "TOMORROW",
    autoRestartResetThresholdPercent: 50,
    autoRestartBlockedDays: ["SUNDAY"],
    autoRestartCurrentStartDate: cycleStart,
  }),
  rule({
    id: 8,
    ruleType: "SEQUENCE",
    intervalDays: 1,
    startDate: addDays(baseDate, 1),
    skipBlockedDaysBehavior: "MOVE_TO_NEXT_VALID_DAY",
  }),
  rule({ id: 9, ruleType: "DAILY", startDate: addDays(baseDate, -30) }),
];

const scheduledOccurrences = [
  occurrence({ id: 1001, taskId: 1, date: addDays(baseDate, -4), status: "SHIFTED", isShifted: true, originalDate: addDays(baseDate, -4), note: "Pushed forward" }),
  occurrence({ id: 1002, taskId: 1, date: addDays(baseDate, -3), status: "COMPLETED", isShifted: true, originalDate: addDays(baseDate, -4) }),
  occurrence({ id: 1003, taskId: 1, date: addDays(baseDate, -2), status: "SKIPPED" }),
  occurrence({ id: 1004, taskId: 1, date: addDays(baseDate, -1), status: "MISSED" }),
  occurrence({ id: 1005, taskId: 1, date: baseDate }),
  occurrence({ id: 1006, taskId: 1, date: addDays(baseDate, 1) }),
  occurrence({ id: 1007, taskId: 1, date: addDays(baseDate, 2) }),

  occurrence({ id: 2001, taskId: 2, date: baseDate }),

  occurrence({ id: 3001, taskId: 3, date: addDays(baseDate, -6), status: "COMPLETED", note: "Used 3 drops; no irritation." }),
  occurrence({ id: 3002, taskId: 3, date: addDays(baseDate, -4), status: "COMPLETED" }),
  occurrence({ id: 3003, taskId: 3, date: addDays(baseDate, -2), status: "COMPLETED" }),
  occurrence({ id: 3004, taskId: 3, date: baseDate }),
  occurrence({ id: 3005, taskId: 3, date: addDays(baseDate, 2) }),
  occurrence({ id: 3006, taskId: 3, date: addDays(baseDate, 4) }),

  occurrence({ id: 4001, taskId: 4, date: addDays(baseDate, -7), status: "COMPLETED", note: "Moved one priority into next week." }),
  occurrence({ id: 4002, taskId: 4, date: baseDate }),
  occurrence({ id: 4003, taskId: 4, date: addDays(baseDate, 3) }),

  occurrence({ id: 5001, taskId: 5, date: addDays(baseDate, -6), status: "COMPLETED", sequenceItemId: 501, note: "Used 14 kg; kept the last reps smooth." }),
  occurrence({ id: 5002, taskId: 5, date: addDays(baseDate, -5), status: "COMPLETED", sequenceItemId: 502 }),
  occurrence({ id: 5003, taskId: 5, date: addDays(baseDate, -4), status: "SKIPPED", sequenceItemId: 503 }),
  occurrence({ id: 5004, taskId: 5, date: addDays(baseDate, -3), status: "COMPLETED", sequenceItemId: 501, note: "Used 16 kg; balance felt steady." }),
  occurrence({ id: 5005, taskId: 5, date: addDays(baseDate, -2), status: "COMPLETED", sequenceItemId: 502 }),
  occurrence({ id: 5006, taskId: 5, date: addDays(baseDate, -1), status: "SKIPPED", sequenceItemId: 503 }),
  occurrence({ id: 5007, taskId: 5, date: baseDate, sequenceItemId: 501 }),
  occurrence({ id: 5008, taskId: 5, date: addDays(baseDate, 1), sequenceItemId: 502 }),
  occurrence({ id: 5009, taskId: 5, date: addDays(baseDate, 2), sequenceItemId: 503 }),
  occurrence({ id: 5010, taskId: 5, date: addDays(baseDate, 3), sequenceItemId: 501 }),
  occurrence({ id: 5011, taskId: 5, date: addDays(baseDate, 4), sequenceItemId: 502 }),
  occurrence({ id: 5012, taskId: 5, date: addDays(baseDate, 5), sequenceItemId: 503 }),

  occurrence({ id: 6001, taskId: 6, date: addDays(baseDate, -3) }),

  occurrence({ id: 7001, taskId: 7, date: addDays(cycleStart, 0), status: "COMPLETED" }),
  occurrence({ id: 7002, taskId: 7, date: addDays(cycleStart, 2), status: "COMPLETED" }),
  occurrence({ id: 7003, taskId: 7, date: addDays(cycleStart, 4), status: "COMPLETED" }),
  occurrence({ id: 7004, taskId: 7, date: addDays(cycleStart, 6), status: "SKIPPED" }),
  occurrence({ id: 7005, taskId: 7, date: addDays(cycleStart, 8) }),
  occurrence({ id: 7006, taskId: 7, date: addDays(cycleStart, 10) }),
  occurrence({ id: 7007, taskId: 7, date: addDays(cycleStart, 12) }),

  occurrence({ id: 9001, taskId: 9, date: addDays(baseDate, -20), status: "COMPLETED" }),
];

const completionLogs = [
  log({ occurrenceId: 1001, taskId: 1, action: "SHIFTED_FORWARD", date: addDays(baseDate, -4), note: `Pushed to ${addDays(baseDate, -3)}` }),
  log({ occurrenceId: 1002, taskId: 1, action: "COMPLETED", date: addDays(baseDate, -3), note: "Marked complete" }),
  log({ occurrenceId: 1003, taskId: 1, action: "SKIPPED", date: addDays(baseDate, -2), note: "Skipped intentionally" }),
  log({ occurrenceId: 1004, taskId: 1, action: "MARKED_MISSED", date: addDays(baseDate, -1), note: "Marked missed after day reset" }),
  log({ occurrenceId: 3001, taskId: 3, action: "COMPLETED", date: addDays(baseDate, -6), note: "Marked complete" }),
  log({ occurrenceId: 3002, taskId: 3, action: "COMPLETED", date: addDays(baseDate, -4), note: "Marked complete" }),
  log({ occurrenceId: 3003, taskId: 3, action: "COMPLETED", date: addDays(baseDate, -2), note: "Marked complete" }),
  log({ occurrenceId: 4001, taskId: 4, action: "COMPLETED", date: addDays(baseDate, -7), note: "Marked complete" }),
  log({ occurrenceId: 5001, taskId: 5, action: "COMPLETED", date: addDays(baseDate, -6), note: "Completed all required exercises" }),
  log({ occurrenceId: 5002, taskId: 5, action: "COMPLETED", date: addDays(baseDate, -5), note: "Completed all required exercises" }),
  log({ occurrenceId: 5003, taskId: 5, action: "SKIPPED", date: addDays(baseDate, -4), note: "Skipped intentionally" }),
  log({ occurrenceId: 5004, taskId: 5, action: "COMPLETED", date: addDays(baseDate, -3), note: "Completed all required exercises" }),
  log({ occurrenceId: 5005, taskId: 5, action: "COMPLETED", date: addDays(baseDate, -2), note: "Completed all required exercises" }),
  log({ occurrenceId: 5006, taskId: 5, action: "SKIPPED", date: addDays(baseDate, -1), note: "Skipped intentionally" }),
  log({ occurrenceId: 7001, taskId: 7, action: "COMPLETED", date: addDays(cycleStart, 0), note: "Marked complete" }),
  log({ occurrenceId: 7002, taskId: 7, action: "COMPLETED", date: addDays(cycleStart, 2), note: "Marked complete" }),
  log({ occurrenceId: 7003, taskId: 7, action: "COMPLETED", date: addDays(cycleStart, 4), note: "Marked complete" }),
  log({ occurrenceId: 7004, taskId: 7, action: "SKIPPED", date: addDays(cycleStart, 6), note: "Skipped intentionally" }),
  log({ occurrenceId: 9001, taskId: 9, action: "COMPLETED", date: addDays(baseDate, -20), note: "Marked complete" }),
];

const backup = {
  schemaVersion: 1,
  exportedAt: timestamp(baseDate, "12:00:00"),
  tasks,
  recurrenceRules,
  scheduledOccurrences,
  completionLogs,
  workoutSequences: [
    { id: 50, taskId: 5, name: "Foundation strength sequence", createdAt, updatedAt },
    { id: 80, taskId: 8, name: "Power progression sequence", createdAt, updatedAt },
  ],
  sequenceItems: [
    { id: 501, sequenceId: 50, name: "Day 1 - Strength and balance", position: 0, notes: "Controlled strength day" },
    { id: 502, sequenceId: 50, name: "Day 2 - Easy cardio", position: 1, notes: "Stay conversational" },
    { id: 503, sequenceId: 50, name: "Day 3 - Rest day", position: 2, notes: "Light walking is optional" },
    { id: 801, sequenceId: 80, name: "Day 1 - Power", position: 0, notes: "Upcoming phase" },
    { id: 802, sequenceId: 80, name: "Day 2 - Recovery", position: 1, notes: "Upcoming phase" },
  ],
  sequenceExercises: [
    { id: 5101, sequenceItemId: 501, position: 0, name: "Goblet squat", prescription: "3 sets x 8 reps", instructions: "Use a controlled three-second lowering phase", requirement: "REQUIRED" },
    { id: 5102, sequenceItemId: 501, position: 1, name: "Single-leg balance", prescription: "3 sets x 45 seconds per side", instructions: "Keep the tripod of the foot in contact with the floor", requirement: "REQUIRED" },
    { id: 5103, sequenceItemId: 501, position: 2, name: "Calf isometric hold", prescription: "5 sets x 30 seconds per side", instructions: "Use only when the lower leg feels stiff", requirement: "CONDITIONAL" },
    { id: 5201, sequenceItemId: 502, position: 0, name: "Zone 2 bike", prescription: "25 minutes at conversational pace", instructions: "Keep resistance smooth and moderate", requirement: "REQUIRED" },
    { id: 5202, sequenceItemId: 502, position: 1, name: "Hip mobility", prescription: "2 sets x 60 seconds per side", instructions: "Move slowly without forcing range", requirement: "REQUIRED" },
    { id: 8101, sequenceItemId: 801, position: 0, name: "Low pogo hops", prescription: "3 sets x 20 reps", instructions: "Stop if landing quality changes", requirement: "REQUIRED" },
    { id: 8201, sequenceItemId: 802, position: 0, name: "Easy walk", prescription: "20 minutes", instructions: "Keep the pace relaxed", requirement: "REQUIRED" },
  ],
  occurrenceExerciseChecks: [
    { id: 1, occurrenceId: 5001, sequenceExerciseId: 5101, status: "COMPLETED", updatedAt },
    { id: 2, occurrenceId: 5001, sequenceExerciseId: 5102, status: "COMPLETED", updatedAt },
    { id: 3, occurrenceId: 5001, sequenceExerciseId: 5103, status: "NOT_NEEDED", updatedAt },
    { id: 4, occurrenceId: 5004, sequenceExerciseId: 5101, status: "COMPLETED", updatedAt },
    { id: 5, occurrenceId: 5004, sequenceExerciseId: 5102, status: "COMPLETED", updatedAt },
    { id: 6, occurrenceId: 5004, sequenceExerciseId: 5103, status: "NOT_NEEDED", updatedAt },
    { id: 7, occurrenceId: 5007, sequenceExerciseId: 5101, status: "COMPLETED", updatedAt },
    { id: 8, occurrenceId: 5007, sequenceExerciseId: 5102, status: "PENDING", updatedAt },
    { id: 9, occurrenceId: 5007, sequenceExerciseId: 5103, status: "NOT_NEEDED", updatedAt },
  ],
  routinePlans: [
    { id: 1, name: "Foundation to power program", createdAt, updatedAt },
  ],
  routinePhases: [
    {
      id: 1,
      routinePlanId: 1,
      taskId: 5,
      position: 0,
      advanceMode: "MANUAL",
      minimumDays: 14,
      progressionNote: "Have energy, soreness, and movement quality remained stable for two weeks?",
      status: "ACTIVE",
      activatedDate: addDays(baseDate, -14),
      advancedAt: null,
      lastReviewedDate: null,
      createdAt,
      updatedAt,
    },
    {
      id: 2,
      routinePlanId: 1,
      taskId: 8,
      position: 1,
      advanceMode: "MANUAL",
      minimumDays: 14,
      progressionNote: "Were all power sessions comfortable during and the following day?",
      status: "UPCOMING",
      activatedDate: null,
      advancedAt: null,
      lastReviewedDate: null,
      createdAt,
      updatedAt,
    },
  ],
  cycleGroups: [],
  cycleTaskMemberships: [],
  cycleLogs: [],
  appSettings: {
    dayRolloverTime: "03:00",
    dailyReviewReminderTime: "08:00",
    lateDayReminderTime: "20:00",
    morningTaskReminderTime: "08:00",
    noonTaskReminderTime: "12:00",
    eveningTaskReminderTime: "18:00",
    dailyReviewEnabled: false,
    lateDayReminderEnabled: false,
    taskTimeReminderEnabled: false,
    exactAlarmPermissionPromptShown: false,
    defaultBlockedDays: "SUNDAY",
    themePreference: "light",
    backupLastExportedAt: "",
    autoBackupEnabled: false,
    autoBackupIntervalDays: 7,
    autoBackupFolderUri: "",
    autoBackupLastRunAt: "",
  },
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(backup, null, 2)}\n`, "utf8");

const bytes = fs.statSync(outputPath).size;
process.stdout.write(`Generated ${path.relative(repositoryRoot, outputPath)} for ${baseDate} (${bytes} bytes)\n`);
