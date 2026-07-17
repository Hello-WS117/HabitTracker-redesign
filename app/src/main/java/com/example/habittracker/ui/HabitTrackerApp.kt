@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.habittracker.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.habittracker.backup.BackupRepository
import com.example.habittracker.backup.manualBackupFileName
import com.example.habittracker.data.CycleRestartBehavior
import com.example.habittracker.data.CycleRestartTiming
import com.example.habittracker.data.ExerciseCheckStatus
import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.LongTermRecurrenceAnchor
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.RoutinePhaseStatus
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskTimeOfDay
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HabitTrackerApp(store: HabitTrackerUiStore = rememberHabitTrackerUiStore()) {
    HabitTrackerTheme(themePreference = store.settings.themePreference) {
        ExerciseTimerCoordinator(store)
        Scaffold(
            topBar = {
                AppTopBar(
                    destination = store.currentDestination,
                    date = store.operationalDate,
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.height(72.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    AppDestination.values().forEach { destination ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav-${destination.name.lowercase()}"),
                            selected = store.currentDestination == destination,
                            onClick = { store.currentDestination = destination },
                            icon = { DestinationIcon(destination = destination) },
                            label = {
                                Text(
                                    text = destination.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (store.currentDestination) {
                    AppDestination.Today -> TodayScreen(store)
                    AppDestination.Calendar -> CalendarScreen(store)
                    AppDestination.Tasks -> TaskEditorScreen(store)
                    AppDestination.Stats -> StatsScreen(store)
                    AppDestination.Settings -> SettingsScreen(store)
                }
            }
        }
    }
}

@Composable
private fun AppTopBar(destination: AppDestination, date: LocalDate) {
    Column {
        TopAppBar(
            title = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = destinationIcon(destination),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            text = screenTitle(destination),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = date.fullDateLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
    }
}

@Composable
private fun HabitTrackerTheme(themePreference: String, content: @Composable () -> Unit) {
    val useDarkTheme = when (themePreference) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val lightScheme = lightColorScheme(
        primary = Color(0xFF176B59),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7EFE7),
        onPrimaryContainer = Color(0xFF0B3B30),
        secondary = Color(0xFF3D5F91),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE7FA),
        onSecondaryContainer = Color(0xFF163354),
        tertiary = Color(0xFFA85736),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDBCC),
        onTertiaryContainer = Color(0xFF4A1A08),
        error = Color(0xFFB3261E),
        background = Color(0xFFF6F8F6),
        onBackground = Color(0xFF1A1C1A),
        surface = Color.White,
        onSurface = Color(0xFF1A1C1A),
        surfaceVariant = Color(0xFFE8ECE8),
        onSurfaceVariant = Color(0xFF535A55),
        outline = Color(0xFF747A75),
    )
    val darkScheme = darkColorScheme(
        primary = Color(0xFF8ED8C2),
        onPrimary = Color(0xFF00382C),
        primaryContainer = Color(0xFF0B5142),
        onPrimaryContainer = Color(0xFFB5F2DF),
        secondary = Color(0xFFB4C9EE),
        onSecondary = Color(0xFF183153),
        secondaryContainer = Color(0xFF2D476C),
        onSecondaryContainer = Color(0xFFDCE7FA),
        tertiary = Color(0xFFFFB59A),
        onTertiary = Color(0xFF5B210D),
        tertiaryContainer = Color(0xFF78351E),
        onTertiaryContainer = Color(0xFFFFDBCC),
        error = Color(0xFFFFB4AB),
        background = Color(0xFF111411),
        onBackground = Color(0xFFE2E3DF),
        surface = Color(0xFF191D19),
        onSurface = Color(0xFFE2E3DF),
        surfaceVariant = Color(0xFF3E4842),
        onSurfaceVariant = Color(0xFFBCC6BF),
        outline = Color(0xFF87918B),
    )

    val typography = Typography(
        headlineLarge = appTextStyle(30.sp, FontWeight.Bold, 36.sp),
        headlineMedium = appTextStyle(26.sp, FontWeight.Bold, 32.sp),
        headlineSmall = appTextStyle(22.sp, FontWeight.Bold, 28.sp),
        titleLarge = appTextStyle(22.sp, FontWeight.SemiBold, 28.sp),
        titleMedium = appTextStyle(17.sp, FontWeight.SemiBold, 23.sp),
        titleSmall = appTextStyle(15.sp, FontWeight.SemiBold, 20.sp),
        bodyLarge = appTextStyle(16.sp, FontWeight.Normal, 23.sp),
        bodyMedium = appTextStyle(14.sp, FontWeight.Normal, 20.sp),
        bodySmall = appTextStyle(12.sp, FontWeight.Normal, 17.sp),
        labelLarge = appTextStyle(14.sp, FontWeight.SemiBold, 20.sp),
        labelMedium = appTextStyle(12.sp, FontWeight.Medium, 17.sp),
        labelSmall = appTextStyle(11.sp, FontWeight.Medium, 15.sp),
    )

    MaterialTheme(
        colorScheme = if (useDarkTheme) darkScheme else lightScheme,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(10.dp),
        ),
        content = content,
    )
}

private fun appTextStyle(fontSize: androidx.compose.ui.unit.TextUnit, weight: FontWeight, lineHeight: androidx.compose.ui.unit.TextUnit): TextStyle {
    return TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = fontSize,
        fontWeight = weight,
        lineHeight = lineHeight,
        letterSpacing = 0.sp,
    )
}

@Composable
private fun TodayScreen(store: HabitTrackerUiStore) {
    val due = store.todayOccurrences()
    val longTermDue = store.longTermDueOccurrences()
    val pendingLongTermDue = longTermDue.filter { it.status == HabitStatus.Pending }
    val completedLongTermDue = longTermDue.filter { it.status == HabitStatus.Completed }
    val yesterdayCandidates = store.yesterdayCompletionCandidates()
    val phaseReviews = store.pendingRoutinePhaseReviews()
    val pendingDue = due.filter { it.status == HabitStatus.Pending }
    val completedDue = due.filter { it.status == HabitStatus.Completed }
    val skippedDue = due.filter { it.status == HabitStatus.Skipped }
    val pushedDue = due.filter { it.status == HabitStatus.Shifted }
    val missedDue = due.filter { it.status == HabitStatus.Missed }
    val completed = due.count { it.status == HabitStatus.Completed }
    val pending = due.count { it.status == HabitStatus.Pending }
    val progress = if (due.isEmpty()) 0f else completed.toFloat() / due.size.toFloat()
    var completedExpanded by remember { mutableStateOf(false) }
    var skippedExpanded by remember { mutableStateOf(false) }
    var pushedExpanded by remember { mutableStateOf(false) }
    var missedExpanded by remember { mutableStateOf(false) }
    var completedLongTermExpanded by remember { mutableStateOf(false) }
    var quickAddOpen by remember { mutableStateOf(false) }
    var quickAddName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("today-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DailySummaryCard(
                completed = completed,
                total = due.size,
                pending = pending,
                progress = progress,
                rollover = store.settings.dayRolloverTime,
            )
        }

        items(phaseReviews, key = { "phase-review-${it.id}" }) { phase ->
            val task = store.taskById(phase.taskId)
            if (task != null) {
                RoutinePhaseReviewCard(
                    phase = phase,
                    task = task,
                    today = store.operationalDate,
                    onExtendOneWeek = { store.extendRoutinePhaseOneWeek(phase.id) },
                    onAdvance = { store.advanceRoutinePhase(phase.id) },
                )
            }
        }

        item {
            QuickAddOneTimeTaskButton(
                onClick = {
                    quickAddName = ""
                    quickAddOpen = true
                },
            )
        }

        item {
            SectionTitle("Daily checklist", trailing = "${due.size} scheduled")
        }

        if (yesterdayCandidates.isNotEmpty()) {
            item {
                SectionTitle("Done yesterday", trailing = "${yesterdayCandidates.size} eligible")
            }
            items(yesterdayCandidates, key = { "done-yesterday-${it.id}" }) { occurrence ->
                val task = store.taskById(occurrence.taskId)
                if (task != null) {
                    YesterdayCompletionCard(
                        occurrence = occurrence,
                        task = task,
                        onCompleteYesterday = { store.completeOccurrenceYesterday(occurrence.id) },
                        onOpenDetail = { store.selectTaskForDetail(task.id) },
                    )
                }
            }
        }

        if (due.isEmpty() && longTermDue.isEmpty() && yesterdayCandidates.isEmpty()) {
            item { EmptyState("No tasks are scheduled for this day.") }
        } else {
            items(pendingDue, key = { it.id }) { occurrence ->
                val task = store.taskById(occurrence.taskId)
                if (task != null) {
                    ChecklistCard(
                        occurrence = occurrence,
                        task = task,
                        routinePhase = store.activeRoutinePhaseForTask(task.id),
                        cycleProgress = store.progressForTaskCycleTiming(task),
                        exerciseTimerFor = store::exerciseTimerFor,
                        onToggleExerciseTimer = store::toggleExerciseTimer,
                        sequenceStepHistory = occurrence.sequenceItemName
                            ?.let { store.sequenceStepNoteHistory(task.id, it, occurrence.id) }
                            .orEmpty(),
                        sequenceSwapCandidates = store.sequenceSwapCandidates(occurrence.id),
                        onComplete = { store.completeOccurrence(occurrence.id) },
                        canCompleteYesterday = store.canCompleteOccurrenceYesterday(occurrence.id),
                        onCompleteYesterday = { store.completeOccurrenceYesterday(occurrence.id) },
                        onSkip = { store.skipOccurrence(occurrence.id) },
                        onShift = { store.pushOccurrenceForward(occurrence.id) },
                        onUndo = { store.undoOccurrenceDecision(occurrence.id) },
                        onSwitchSequenceItem = { targetOccurrenceId ->
                            store.swapSequenceOccurrenceItems(occurrence.id, targetOccurrenceId)
                        },
                        onSetSequencePoint = { targetSequenceIndex ->
                            store.setSequenceOccurrencePoint(occurrence.id, targetSequenceIndex)
                        },
                        onSaveNote = { store.updateOccurrenceNote(occurrence.id, it) },
                        onExerciseStatus = { exerciseId, status ->
                            store.setExerciseCheckStatus(occurrence.id, exerciseId, status)
                        },
                        onOpenDetail = { store.selectTaskForDetail(task.id) },
                    )
                }
            }
            statusSectionItems(
                title = "Completed",
                occurrences = completedDue,
                expanded = completedExpanded,
                onExpandedChange = { completedExpanded = it },
                store = store,
            )
            statusSectionItems(
                title = "Skipped",
                occurrences = skippedDue,
                expanded = skippedExpanded,
                onExpandedChange = { skippedExpanded = it },
                store = store,
            )
            statusSectionItems(
                title = "Pushed",
                occurrences = pushedDue,
                expanded = pushedExpanded,
                onExpandedChange = { pushedExpanded = it },
                store = store,
            )
            statusSectionItems(
                title = "Missed",
                occurrences = missedDue,
                expanded = missedExpanded,
                onExpandedChange = { missedExpanded = it },
                store = store,
            )
            if (pendingLongTermDue.isNotEmpty()) {
                item {
                    SectionTitle("Long-term tasks", trailing = "${pendingLongTermDue.size} due")
                }
                items(pendingLongTermDue, key = { "long-term-${it.id}" }) { occurrence ->
                    val task = store.taskById(occurrence.taskId)
                    if (task != null) {
                        ChecklistCard(
                            occurrence = occurrence,
                            task = task,
                            routinePhase = store.activeRoutinePhaseForTask(task.id),
                            cycleProgress = null,
                            exerciseTimerFor = store::exerciseTimerFor,
                            onToggleExerciseTimer = store::toggleExerciseTimer,
                            sequenceStepHistory = emptyList(),
                            sequenceSwapCandidates = emptyList(),
                            onComplete = { store.completeOccurrence(occurrence.id) },
                            canCompleteYesterday = false,
                            onCompleteYesterday = {},
                            onSkip = {},
                            onShift = {},
                            onUndo = { store.undoOccurrenceDecision(occurrence.id) },
                            onSwitchSequenceItem = {},
                            onSetSequencePoint = {},
                            onSaveNote = { store.updateOccurrenceNote(occurrence.id, it) },
                            onExerciseStatus = { exerciseId, status ->
                                store.setExerciseCheckStatus(occurrence.id, exerciseId, status)
                            },
                            onOpenDetail = { store.selectTaskForDetail(task.id) },
                        )
                    }
                }
            }
            statusSectionItems(
                title = "Completed long-term",
                occurrences = completedLongTermDue,
                expanded = completedLongTermExpanded,
                onExpandedChange = { completedLongTermExpanded = it },
                store = store,
            )
        }
    }

    if (quickAddOpen) {
        QuickAddOneTimeTaskDialog(
            name = quickAddName,
            onNameChange = { quickAddName = it },
            onDismiss = {
                quickAddName = ""
                quickAddOpen = false
            },
            onSave = {
                store.quickAddOneTimeTask(quickAddName)
                quickAddName = ""
                quickAddOpen = false
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statusSectionItems(
    title: String,
    occurrences: List<HabitOccurrenceUi>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    store: HabitTrackerUiStore,
) {
    if (occurrences.isEmpty()) return
    item {
        CollapsibleStatusHeader(
            title = title,
            count = occurrences.size,
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) },
        )
    }
    if (expanded) {
        items(occurrences, key = { "${title}-${it.id}" }) { occurrence ->
            val task = store.taskById(occurrence.taskId)
            if (task != null) {
                ChecklistCard(
                    occurrence = occurrence,
                    task = task,
                    routinePhase = store.activeRoutinePhaseForTask(task.id),
                    cycleProgress = store.progressForTaskCycleTiming(task),
                    exerciseTimerFor = store::exerciseTimerFor,
                    onToggleExerciseTimer = store::toggleExerciseTimer,
                    sequenceStepHistory = occurrence.sequenceItemName
                        ?.let { store.sequenceStepNoteHistory(task.id, it, occurrence.id) }
                        .orEmpty(),
                    sequenceSwapCandidates = store.sequenceSwapCandidates(occurrence.id),
                    onComplete = { store.completeOccurrence(occurrence.id) },
                    canCompleteYesterday = store.canCompleteOccurrenceYesterday(occurrence.id),
                    onCompleteYesterday = { store.completeOccurrenceYesterday(occurrence.id) },
                    onSkip = { store.skipOccurrence(occurrence.id) },
                    onShift = { store.pushOccurrenceForward(occurrence.id) },
                    onUndo = { store.undoOccurrenceDecision(occurrence.id) },
                    onSwitchSequenceItem = { targetOccurrenceId ->
                        store.swapSequenceOccurrenceItems(occurrence.id, targetOccurrenceId)
                    },
                    onSetSequencePoint = { targetSequenceIndex ->
                        store.setSequenceOccurrencePoint(occurrence.id, targetSequenceIndex)
                    },
                    onSaveNote = { store.updateOccurrenceNote(occurrence.id, it) },
                    onExerciseStatus = { exerciseId, status ->
                        store.setExerciseCheckStatus(occurrence.id, exerciseId, status)
                    },
                    onOpenDetail = { store.selectTaskForDetail(task.id) },
                )
            }
        }
    }
}

@Composable
private fun RoutinePhaseReviewCard(
    phase: HabitRoutinePhaseUi,
    task: HabitTaskUi,
    today: LocalDate,
    onExtendOneWeek: () -> Unit,
    onAdvance: () -> Unit,
) {
    val elapsedDays = phase.activatedDate
        ?.let { ChronoUnit.DAYS.between(it, today).toInt().coerceAtLeast(0) }
        ?: phase.minimumDays
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("phase-review-card-${phase.id}"),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "Phase review",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = task.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Minimum ${phaseMinimumLengthLabel(phase.minimumDays)} reached  |  $elapsedDays days elapsed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (phase.progressionNote.isNotBlank()) {
                Text(
                    text = phase.progressionNote,
                    modifier = Modifier.testTag("phase-review-note-${phase.id}"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            phase.nextPhaseName?.let { nextName ->
                Text(
                    text = "Next: $nextName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.testTag("phase-review-advance-${phase.id}"),
                    onClick = onAdvance,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (phase.nextPhaseName == null) "Finish phase" else "Advance tomorrow")
                }
                OutlinedButton(
                    modifier = Modifier.testTag("phase-review-extend-week-${phase.id}"),
                    onClick = onExtendOneWeek,
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Extend 1 week")
                }
            }
        }
    }
}

internal fun phaseMinimumLengthLabel(minimumDays: Int): String {
    val days = minimumDays.coerceAtLeast(1)
    return if (days >= 7 && days % 7 == 0) {
        (days / 7).weekCountLabel()
    } else {
        days.dayCountLabel()
    }
}

@Composable
private fun DailySummaryCard(
    completed: Int,
    total: Int,
    pending: Int,
    progress: Float,
    rollover: LocalTime,
) {
    val percentage = if (total == 0) 0 else (progress * 100f).roundToInt()
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF173D36)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "$completed of $total complete",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "$pending remaining  |  resets ${rollover.clockLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFC4AC),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(7.dp)
                            .background(Color(0xFF8ED8C2)),
                    )
                }
            }
        }
    }
}

@Composable
private fun YesterdayCompletionCard(
    occurrence: HabitOccurrenceUi,
    task: HabitTaskUi,
    onCompleteYesterday: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Card(
        modifier = Modifier.testTag("checklist-yesterday-candidate-${task.id}"),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Next due ${occurrence.operationalDate.fullDateLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TimeOfDayChip(task.timeOfDay)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.testTag("checklist-complete-yesterday-${task.id}"),
                    onClick = onCompleteYesterday,
                ) {
                    Text("Done yesterday")
                }
                TextButton(
                    modifier = Modifier.testTag("checklist-yesterday-stats-${task.id}"),
                    onClick = onOpenDetail,
                ) {
                    Text("Stats")
                }
            }
        }
    }
}

@Composable
private fun QuickAddOneTimeTaskButton(onClick: () -> Unit) {
    FilledTonalButton(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag("today-quick-add-one-time"),
        onClick = onClick,
    ) {
        Icon(Icons.Filled.AddTask, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Quick add 1-time task")
    }
}

@Composable
private fun QuickAddOneTimeTaskDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick add 1-time task") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("today-quick-add-one-time-name"),
                label = { Text("Task name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("today-quick-add-one-time-save"),
                onClick = onSave,
                enabled = name.trim().isNotEmpty(),
            ) {
                Text("Add task")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("today-quick-add-one-time-cancel"),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CollapsibleStatusHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    tagPrefix: String = "today",
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("$tagPrefix-${title.lowercase().replace(" ", "-")}-section"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }
    }
}

@Composable
private fun CollapsibleDetailsSection(
    title: String,
    expanded: Boolean,
    tagPrefix: String,
    onExpandedChange: (Boolean) -> Unit,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(!expanded) }
                .testTag("$tagPrefix-toggle"),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (trailing != null) {
                        Text(
                            text = trailing,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$tagPrefix-content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ChecklistCard(
    occurrence: HabitOccurrenceUi,
    task: HabitTaskUi,
    routinePhase: HabitRoutinePhaseUi?,
    cycleProgress: HabitCycleProgressUi?,
    exerciseTimerFor: (Int, Int, Int) -> HabitExerciseTimerUi,
    onToggleExerciseTimer: (Int, Int, Int, String) -> Unit,
    sequenceStepHistory: List<HabitOccurrenceUi>,
    sequenceSwapCandidates: List<HabitOccurrenceUi>,
    onComplete: () -> Unit,
    canCompleteYesterday: Boolean,
    onCompleteYesterday: () -> Unit,
    onSkip: () -> Unit,
    onShift: () -> Unit,
    onUndo: () -> Unit,
    onSwitchSequenceItem: (Int) -> Unit,
    onSetSequencePoint: (Int) -> Unit,
    onSaveNote: (String) -> Unit,
    onExerciseStatus: (Int, ExerciseCheckStatus) -> Unit,
    onOpenDetail: () -> Unit,
) {
    var noteOpen by remember(occurrence.id) { mutableStateOf(false) }
    val userNote = occurrence.userNote()
    var noteText by remember(occurrence.id, userNote) { mutableStateOf(userNote) }
    var switchOpen by remember(occurrence.id) { mutableStateOf(false) }
    var setPointOpen by remember(occurrence.id) { mutableStateOf(false) }
    var notesOpen by remember(occurrence.id) { mutableStateOf(false) }
    var sequenceItemsOpen by remember(occurrence.id) { mutableStateOf(false) }
    val pending = occurrence.status == HabitStatus.Pending
    val isLongTerm = task.type == HabitTaskType.LongTerm
    val usesPushAction = task.pushable && !isLongTerm
    val canSwitchSequenceItem = pending &&
        occurrence.sequenceItemName != null &&
        sequenceSwapCandidates.isNotEmpty()
    val canSetSequencePoint = pending &&
        task.type == HabitTaskType.Sequence &&
        task.sequenceItems.isNotEmpty() &&
        occurrence.sequenceItemName != null
    val workoutDay = occurrence.sequenceItemPosition
        ?.let { position -> task.workoutDays.firstOrNull { it.position == position } }
    val hasRequiredExercises = workoutDay?.exercises
        .orEmpty()
        .any { it.requirement == ExerciseRequirement.REQUIRED }
    val noteSectionCount = listOf(
        task.notes.isNotBlank(),
        userNote.isNotBlank(),
        sequenceStepHistory.isNotEmpty(),
    ).count { it }

    Card(
        modifier = Modifier.testTag("checklist-card-${task.id}"),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sequenceLine = occurrence.sequenceItemName?.let { "Today: $it" }
                    val longTermLine = if (isLongTerm) {
                        "Due ${occurrence.operationalDate.fullDateLabel()}  |  ${task.recurrenceSummary}"
                    } else {
                        null
                    }
                    val supportingLine = longTermLine ?: sequenceLine
                    if (supportingLine != null) {
                        Text(
                            text = supportingLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (routinePhase != null) {
                        Text(
                            text = "Phase minimum: ${phaseMinimumLengthLabel(routinePhase.minimumDays)}",
                            modifier = Modifier.testTag("checklist-phase-minimum-${task.id}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cycleProgress != null) {
                        ChecklistCycleProgress(
                            progress = cycleProgress,
                            taskId = task.id,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TimeOfDayChip(task.timeOfDay)
                    StatusChip(occurrence.status)
                }
            }
            if (workoutDay != null) {
                WorkoutDayChecklist(
                    taskId = task.id,
                    occurrence = occurrence,
                    workoutDay = workoutDay,
                    enabled = occurrence.status in setOf(HabitStatus.Pending, HabitStatus.Completed),
                    exerciseTimerFor = exerciseTimerFor,
                    onToggleExerciseTimer = onToggleExerciseTimer,
                    onExerciseStatus = onExerciseStatus,
                )
            }
            if (noteSectionCount > 0) {
                CollapsibleDetailsSection(
                    title = "Notes",
                    expanded = notesOpen,
                    tagPrefix = "checklist-notes-${task.id}",
                    trailing = noteSectionCount.toString(),
                    onExpandedChange = { notesOpen = it },
                ) {
                    if (task.notes.isNotBlank()) {
                        Text(
                            text = task.notes,
                            modifier = Modifier.testTag("checklist-task-notes-display-${task.id}"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (userNote.isNotBlank()) {
                        Text(
                            text = "Note: $userNote",
                            modifier = Modifier.testTag("checklist-note-display-${task.id}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sequenceStepHistory.isNotEmpty() && occurrence.sequenceItemName != null) {
                        SequenceStepHistory(
                            stepName = occurrence.sequenceItemName,
                            history = sequenceStepHistory,
                            modifier = Modifier.testTag("checklist-sequence-note-history-${task.id}"),
                        )
                    }
                }
            }
            if (task.type == HabitTaskType.Sequence && task.sequenceItems.isNotEmpty()) {
                CollapsibleDetailsSection(
                    title = "Sequence items",
                    expanded = sequenceItemsOpen,
                    tagPrefix = "checklist-sequence-items-${task.id}",
                    trailing = task.sequenceItems.size.toString(),
                    onExpandedChange = { sequenceItemsOpen = it },
                ) {
                    task.sequenceItems.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("checklist-sequence-item-${task.id}-$index"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = item,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCurrentSequenceItem(index, item, occurrence)) {
                                StatusPill("Today", HabitStatus.Pending)
                            }
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!hasRequiredExercises) {
                    Button(
                        modifier = Modifier.testTag("checklist-complete-${task.id}"),
                        onClick = onComplete,
                        enabled = pending,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (workoutDay == null) "Complete" else "Complete day")
                    }
                }
                if (usesPushAction && canCompleteYesterday) {
                    OutlinedButton(
                        modifier = Modifier.testTag("checklist-complete-yesterday-${task.id}"),
                        onClick = onCompleteYesterday,
                        enabled = pending,
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Done yesterday")
                    }
                }
                if (!isLongTerm) {
                    OutlinedButton(
                        modifier = Modifier.testTag("checklist-skip-${task.id}"),
                        onClick = onSkip,
                        enabled = pending,
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Skip")
                    }
                }
                if (usesPushAction) {
                    OutlinedButton(
                        modifier = Modifier.testTag("checklist-push-${task.id}"),
                        onClick = onShift,
                        enabled = pending,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Push")
                    }
                }
                if (canSwitchSequenceItem) {
                    OutlinedButton(
                        modifier = Modifier.testTag("checklist-sequence-switch-${task.id}"),
                        onClick = { switchOpen = true },
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Switch item")
                    }
                }
                if (canSetSequencePoint) {
                    OutlinedButton(
                        modifier = Modifier.testTag("checklist-sequence-set-point-${task.id}"),
                        onClick = { setPointOpen = true },
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Set point")
                    }
                }
                if (!pending) {
                    OutlinedButton(
                        modifier = Modifier.testTag("checklist-undo-${task.id}"),
                        onClick = onUndo,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                }
                TextButton(
                    modifier = Modifier.testTag("checklist-note-toggle-${task.id}"),
                    onClick = { noteOpen = !noteOpen },
                ) {
                    Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (noteOpen) "Close note" else "Note")
                }
                TextButton(
                    modifier = Modifier.testTag("checklist-stats-${task.id}"),
                    onClick = onOpenDetail,
                ) {
                    Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Stats")
                }
            }

            if (noteOpen) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("checklist-note-field-${task.id}"),
                    minLines = 2,
                    label = { Text(occurrence.sequenceItemName?.let { "$it note" } ?: "Occurrence note") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.testTag("checklist-note-save-${task.id}"),
                        onClick = {
                            onSaveNote(noteText)
                            noteOpen = false
                        },
                    ) {
                        Text("Save note")
                    }
                    TextButton(
                        modifier = Modifier.testTag("checklist-note-cancel-${task.id}"),
                        onClick = {
                            noteText = userNote
                            noteOpen = false
                        },
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
    if (switchOpen) {
        SequenceItemSwapDialog(
            occurrence = occurrence,
            candidates = sequenceSwapCandidates,
            tagPrefix = "checklist-sequence-switch-${task.id}",
            onSelect = { targetOccurrenceId ->
                onSwitchSequenceItem(targetOccurrenceId)
                switchOpen = false
            },
            onDismiss = { switchOpen = false },
        )
    }
    if (setPointOpen) {
        SequencePointDialog(
            occurrence = occurrence,
            task = task,
            tagPrefix = "checklist-sequence-set-point-${task.id}",
            onSelect = { targetSequenceIndex ->
                onSetSequencePoint(targetSequenceIndex)
                setPointOpen = false
            },
            onDismiss = { setPointOpen = false },
        )
    }
}

@Composable
private fun WorkoutDayChecklist(
    taskId: Int,
    occurrence: HabitOccurrenceUi,
    workoutDay: HabitWorkoutDayUi,
    enabled: Boolean,
    exerciseTimerFor: (Int, Int, Int) -> HabitExerciseTimerUi,
    onToggleExerciseTimer: (Int, Int, Int, String) -> Unit,
    onExerciseStatus: (Int, ExerciseCheckStatus) -> Unit,
) {
    val requiredExercises = workoutDay.exercises.filter { it.requirement == ExerciseRequirement.REQUIRED }
    val completedRequired = requiredExercises.count {
        occurrence.exerciseChecks[it.id] == ExerciseCheckStatus.COMPLETED
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workout-day-$taskId"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Today's workout",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (requiredExercises.isNotEmpty()) {
                Text(
                    text = "$completedRequired of ${requiredExercises.size} required",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (workoutDay.notes.isNotBlank()) {
            Text(
                text = workoutDay.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (workoutDay.exercises.isEmpty()) {
            Text(
                text = "Rest day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            workoutDay.exercises.forEachIndexed { index, exercise ->
                val status = occurrence.exerciseChecks[exercise.id] ?: ExerciseCheckStatus.PENDING
                val timerDurationSeconds = exerciseTimerDurationSeconds(exercise.prescription)
                val timer = timerDurationSeconds?.let { durationSeconds ->
                    exerciseTimerFor(occurrence.id, exercise.id, durationSeconds)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workout-exercise-$taskId-$index"),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = status == ExerciseCheckStatus.COMPLETED,
                        onCheckedChange = { checked ->
                            onExerciseStatus(
                                exercise.id,
                                if (checked) ExerciseCheckStatus.COMPLETED else ExerciseCheckStatus.PENDING,
                            )
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("workout-exercise-check-$taskId-$index"),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = exercise.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = exercise.prescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (exercise.instructions.isNotBlank()) {
                            Text(
                                text = exercise.instructions,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = if (exercise.requirement == ExerciseRequirement.REQUIRED) "Required" else "Conditional",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (timer != null || exercise.requirement == ExerciseRequirement.CONDITIONAL) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (timer != null) {
                                ExerciseTimerControl(
                                    taskId = taskId,
                                    exerciseIndex = index,
                                    exerciseName = exercise.name,
                                    timer = timer,
                                    enabled = enabled,
                                    onToggle = {
                                        onToggleExerciseTimer(
                                            occurrence.id,
                                            exercise.id,
                                            timerDurationSeconds,
                                            exercise.name,
                                        )
                                    },
                                )
                            }
                            if (exercise.requirement == ExerciseRequirement.CONDITIONAL) {
                                FilterChip(
                                    selected = status == ExerciseCheckStatus.NOT_NEEDED,
                                    enabled = enabled,
                                    onClick = {
                                        onExerciseStatus(
                                            exercise.id,
                                            if (status == ExerciseCheckStatus.NOT_NEEDED) {
                                                ExerciseCheckStatus.PENDING
                                            } else {
                                                ExerciseCheckStatus.NOT_NEEDED
                                            },
                                        )
                                    },
                                    label = { Text("Not needed") },
                                    modifier = Modifier.testTag("workout-exercise-not-needed-$taskId-$index"),
                                )
                            }
                        }
                    }
                }
                if (index != workoutDay.exercises.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 48.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseTimerControl(
    taskId: Int,
    exerciseIndex: Int,
    exerciseName: String,
    timer: HabitExerciseTimerUi,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val countdown = formatExerciseTimer(timer.remainingSeconds)
    val action = when {
        timer.isRunning -> "Pause"
        timer.isComplete -> "Restart"
        else -> "Start"
    }
    val icon = when {
        timer.isRunning -> Icons.Filled.Pause
        timer.isComplete -> Icons.Filled.Replay
        else -> Icons.Filled.PlayArrow
    }
    Column(
        modifier = Modifier.widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        IconButton(
            modifier = Modifier
                .size(40.dp)
                .testTag("workout-exercise-timer-$taskId-$exerciseIndex"),
            enabled = enabled,
            onClick = onToggle,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "$action $countdown timer for $exerciseName",
            )
        }
        Text(
            text = countdown,
            modifier = Modifier.testTag("workout-exercise-timer-value-$taskId-$exerciseIndex"),
            style = MaterialTheme.typography.labelMedium,
            color = if (timer.isRunning) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExerciseTimerCoordinator(store: HabitTrackerUiStore) {
    val hasRunningTimers = store.hasRunningExerciseTimers()
    LaunchedEffect(hasRunningTimers) {
        if (!hasRunningTimers) return@LaunchedEffect
        while (true) {
            store.tickExerciseTimers()
            delay(200)
        }
    }
}

internal fun isCurrentSequenceItem(
    index: Int,
    itemName: String,
    occurrence: HabitOccurrenceUi,
): Boolean {
    val position = occurrence.sequenceItemPosition
    return if (position != null) {
        position == index
    } else {
        itemName == occurrence.sequenceItemName
    }
}

@Composable
private fun ChecklistCycleProgress(
    progress: HabitCycleProgressUi,
    taskId: Int,
) {
    val expected = progress.expected.coerceAtLeast(1)
    val completedFraction = progress.completed.toFloat() / expected.toFloat()
    val disruptedEndFraction = (progress.completed + progress.disrupted).toFloat() / expected.toFloat()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag("checklist-cycle-progress-${taskId}"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = cycleProgressLabel(progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics {
                    contentDescription = cycleProgressContentDescription(progress)
                }
                .testTag("checklist-cycle-progress-bar-${taskId}"),
        ) {
            if (progress.disrupted > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(disruptedEndFraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.72f)),
                )
            }
            if (progress.completed > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(completedFraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private fun cycleProgressLabel(progress: HabitCycleProgressUi): String {
    return "Cycle progress: ${progress.completed} of ${progress.expected} complete, " +
        "${progress.disrupted} disrupted, ${progress.remaining} left"
}

private fun cycleProgressContentDescription(progress: HabitCycleProgressUi): String {
    return "Cycle progress ${progress.completed} of ${progress.expected} complete, " +
        "${progress.disrupted} disrupted, ${progress.remaining} left"
}

@Composable
private fun SequencePointDialog(
    occurrence: HabitOccurrenceUi,
    task: HabitTaskUi,
    tagPrefix: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set sequence point") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Current: ${occurrence.sequenceItemName.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .testTag("$tagPrefix-options"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(task.sequenceItems) { index, item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(index) }
                                .testTag("$tagPrefix-option-$index"),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = item,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isCurrentSequenceItem(index, item, occurrence)) {
                                    StatusPill("Today", HabitStatus.Pending)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-cancel"),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SequenceItemSwapDialog(
    occurrence: HabitOccurrenceUi,
    candidates: List<HabitOccurrenceUi>,
    tagPrefix: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch sequence item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Current: ${occurrence.sequenceItemName.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .testTag("$tagPrefix-options"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(candidate.id) }
                                .testTag("$tagPrefix-option-${candidate.id}"),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = candidate.sequenceItemName.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${candidate.operationalDate.fullDateLabel()} - ${candidate.status.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-cancel"),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SequenceStepHistory(
    stepName: String,
    history: List<HabitOccurrenceUi>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$stepName notes",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        history.forEach { occurrence ->
            Text(
                text = "${occurrence.operationalDate.monthDayLabel()}: ${occurrence.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CalendarScreen(store: HabitTrackerUiStore) {
    val month = store.calendarMonth
    val cells = remember(month) { calendarCells(month) }
    val selectedOccurrences = store.occurrencesForDate(store.selectedCalendarDate, store.calendarTaskFilterId)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("calendar-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MonthHeader(
                month = month,
                onPrevious = { store.moveCalendarMonth(-1) },
                onNext = { store.moveCalendarMonth(1) },
                onToday = { store.showCalendarToday() },
            )
        }

        item {
            TaskFilterRow(
                tasks = store.visibleTasks(includeArchived = true),
                selectedTaskId = store.calendarTaskFilterId,
                onSelected = { store.calendarTaskFilterId = it },
            )
        }

        item {
            CalendarLegend()
        }

        item {
            CalendarGrid(
                cells = cells,
                selectedDate = store.selectedCalendarDate,
                today = store.operationalDate,
                occurrenceProvider = { date -> store.occurrencesForDate(date, store.calendarTaskFilterId) },
                onDateSelected = { store.selectCalendarDate(it) },
            )
        }

        item {
            SectionTitle(
                title = store.selectedCalendarDate.fullDateLabel(),
                trailing = "${selectedOccurrences.size} item(s)",
                modifier = Modifier.testTag("calendar-selected-date-title"),
            )
        }

        if (selectedOccurrences.isEmpty()) {
            item { EmptyState("No visible tasks on this date.") }
        } else {
            items(selectedOccurrences, key = { it.id }) { occurrence ->
                val task = store.taskById(occurrence.taskId)
                if (task != null) {
                    CompactOccurrenceRow(
                        occurrence = occurrence,
                        task = task,
                        onOpen = { store.selectTaskForDetail(task.id) },
                        tagPrefix = "calendar-detail-${occurrence.id}",
                        currentOperationalDate = store.operationalDate,
                        onSaveNote = { store.updateOccurrenceNote(occurrence.id, it) },
                        sequenceSwapCandidates = store.sequenceSwapCandidates(occurrence.id),
                        onSwitchSequenceItem = { targetOccurrenceId ->
                            store.swapSequenceOccurrenceItems(occurrence.id, targetOccurrenceId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = Modifier.testTag("calendar-month-previous"),
                onClick = onPrevious,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous month",
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    modifier = Modifier.testTag("calendar-month-title"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    modifier = Modifier.testTag("calendar-month-today"),
                    onClick = onToday,
                ) {
                    Text("Today")
                }
            }
            IconButton(
                modifier = Modifier.testTag("calendar-month-next"),
                onClick = onNext,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next month",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun TaskFilterRow(
    tasks: List<HabitTaskUi>,
    selectedTaskId: Int?,
    onSelected: (Int?) -> Unit,
) {
    val sections = calendarFilterTaskSections(tasks)
    LazyRow(
        modifier = Modifier.testTag("calendar-filter-list"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 1.dp),
    ) {
        item {
            FilterChip(
                modifier = Modifier.testTag("calendar-filter-all"),
                selected = selectedTaskId == null,
                onClick = { onSelected(null) },
                label = { Text("All tasks") },
            )
        }
        items(sections.standardTasks, key = { it.id }) { task ->
            CalendarTaskFilterChip(
                task = task,
                selected = selectedTaskId == task.id,
                onSelected = { onSelected(task.id) },
            )
        }
        if (sections.longTermTasks.isNotEmpty()) {
            item {
                CalendarFilterSectionLabel("Long-term")
            }
        }
        items(sections.longTermTasks, key = { it.id }) { task ->
            CalendarTaskFilterChip(
                task = task,
                selected = selectedTaskId == task.id,
                onSelected = { onSelected(task.id) },
            )
        }
    }
}

internal data class CalendarFilterTaskSections(
    val standardTasks: List<HabitTaskUi>,
    val longTermTasks: List<HabitTaskUi>,
)

internal fun calendarFilterTaskSections(tasks: List<HabitTaskUi>): CalendarFilterTaskSections {
    val filterableTasks = tasks.filterNot { it.isOneTimeTask() }
    return CalendarFilterTaskSections(
        standardTasks = filterableTasks.filter { it.type != HabitTaskType.LongTerm },
        longTermTasks = filterableTasks.filter { it.type == HabitTaskType.LongTerm },
    )
}

internal fun HabitTaskUi.isOneTimeTask(): Boolean {
    return type == HabitTaskType.OneTime ||
        (
            type == HabitTaskType.Simple &&
                durationDays == 1 &&
                endDate != null
        )
}

@Composable
private fun CalendarTaskFilterChip(
    task: HabitTaskUi,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    FilterChip(
        modifier = Modifier.testTag("calendar-filter-${task.id}"),
        selected = selected,
        onClick = onSelected,
        label = {
            Text(
                text = task.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun CalendarFilterSectionLabel(label: String) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .testTag("calendar-filter-section-${label.lowercase().replace(" ", "-")}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(22.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CalendarGrid(
    cells: List<LocalDate?>,
    selectedDate: LocalDate,
    today: LocalDate,
    occurrenceProvider: (LocalDate) -> List<HabitOccurrenceUi>,
    onDateSelected: (LocalDate) -> Unit,
) {
    Card(
        modifier = Modifier.testTag("calendar-grid"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        CalendarDayCell(
                            date = date,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            currentOperationalDate = today,
                            occurrences = date?.let(occurrenceProvider).orEmpty(),
                            onClick = { if (date != null) onDateSelected(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate?,
    isSelected: Boolean,
    isToday: Boolean,
    currentOperationalDate: LocalDate,
    occurrences: List<HabitOccurrenceUi>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.secondary
        else -> Color.Transparent
    }
    val visibleStatuses = date?.let {
        occurrences
            .map { occurrence -> occurrence.calendarMarker(it, currentOperationalDate) }
            .distinct()
            .sortedBy { marker -> marker.displayOrder }
    }.orEmpty()
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .then(
                if (date != null) {
                    Modifier
                        .testTag(date.calendarDayTestTag())
                        .semantics {
                            contentDescription = calendarDayContentDescription(
                                date = date,
                                markers = visibleStatuses,
                                itemCount = occurrences.size,
                                isSelected = isSelected,
                                isToday = isToday,
                            )
                        }
                } else {
                    Modifier
                },
            )
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(5.dp),
    ) {
        if (date != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    visibleStatuses.take(5).forEach { marker ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(themedCalendarMarkerColor(marker))
                                .testTag(marker.calendarMarkerTestTag(date)),
                        )
                    }
                    if (occurrences.size > visibleStatuses.take(5).size) {
                        Text(
                            text = "+${occurrences.size - visibleStatuses.take(5).size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarLegend() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            CalendarLegendItem("Completed", themedStatusColor(HabitStatus.Completed))
            CalendarLegendItem("Skipped", themedStatusColor(HabitStatus.Skipped))
            CalendarLegendItem("Missed", themedStatusColor(HabitStatus.Missed))
            CalendarLegendItem("Upcoming", themedUpcomingColor())
            CalendarLegendItem("Pushed", themedStatusColor(HabitStatus.Shifted))
            CalendarLegendItem("Pending", themedStatusColor(HabitStatus.Pending))
        }
    }
}

@Composable
private fun CalendarLegendItem(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskEditorScreen(store: HabitTrackerUiStore) {
    var showArchivedTasks by remember { mutableStateOf(false) }
    var activeTasksExpanded by remember { mutableStateOf(false) }
    var longTermTasksExpanded by remember { mutableStateOf(false) }
    var phaseImportOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val allTasks = store.visibleTasks(includeArchived = true)
    val routineGroups = taskEditorRoutineGroups(allTasks, store.routinePhases)
    val groupedTaskIds = routineGroups.flatMap { group -> group.phases.map { it.task.id } }.toSet()
    val activeRoutineGroups = routineGroups.filter { it.isActive }
    val hiddenRoutineGroups = routineGroups.filterNot { it.isActive }
    val standaloneActiveTasks = taskEditorActiveTasks(store.visibleTasks())
        .filterNot { it.id in groupedTaskIds }
    val regularActiveTasks = standaloneActiveTasks.filter { it.type != HabitTaskType.LongTerm }
    val longTermActiveTasks = standaloneActiveTasks.filter { it.type == HabitTaskType.LongTerm }
    val hiddenTasks = allTasks
        .filter { it.archived || !it.isActive }
        .filterNot { it.id in groupedTaskIds }
    val activeTaskCount = regularActiveTasks.size + longTermActiveTasks.size + activeRoutineGroups.size
    val hiddenTaskCount = hiddenTasks.size + hiddenRoutineGroups.size
    fun editTask(task: HabitTaskUi) {
        store.editTask(task)
        scope.launch { listState.animateScrollToItem(0) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("task-editor-list"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TaskEditorCard(
                draft = store.draft,
                existingTasks = store.visibleTasks(includeArchived = true),
                onDraftChange = { store.draft = it },
                onSave = { store.saveDraft() },
                onClear = { store.clearDraft() },
                onBulkPhases = { phaseImportOpen = true },
            )
        }

        item {
            SectionTitle(
                "Tasks",
                trailing = taskEditorSummary(activeTaskCount, hiddenTaskCount, showArchivedTasks),
            )
        }

        if (hiddenTaskCount > 0) {
            item {
                SwitchRow(
                    title = "Archived and inactive tasks",
                    subtitle = if (showArchivedTasks) {
                        "$hiddenTaskCount visible"
                    } else {
                        "$hiddenTaskCount hidden"
                    },
                    checked = showArchivedTasks,
                    tagPrefix = "task-editor-hidden-tasks",
                    onCheckedChange = { showArchivedTasks = it },
                )
            }
        }

        if (activeTaskCount == 0 && (!showArchivedTasks || hiddenTaskCount == 0)) {
            item { EmptyState("No active tasks.") }
        }

        if (regularActiveTasks.isNotEmpty() || activeRoutineGroups.isNotEmpty()) {
            item {
                CollapsibleStatusHeader(
                    title = "Active tasks",
                    count = regularActiveTasks.size + activeRoutineGroups.size,
                    expanded = activeTasksExpanded,
                    tagPrefix = "task-editor",
                    onClick = { activeTasksExpanded = !activeTasksExpanded },
                )
            }
        }

        if (activeTasksExpanded) {
            items(activeRoutineGroups, key = { "active-routine-${it.planId}" }) { group ->
                RoutinePlanCard(
                    group = group,
                    onEditPhase = { editTask(it) },
                    onArchive = { store.archiveRoutinePlan(group.planId, archived = true) },
                    onDelete = { store.deleteRoutinePlanPermanently(group.planId) },
                    onStats = { store.selectTaskForDetail(it.id) },
                )
            }
            items(regularActiveTasks, key = { "active-${it.id}" }) { task ->
                TaskListCard(
                    task = task,
                    cycleProgress = store.progressForTaskCycleTiming(task),
                    onEdit = { editTask(task) },
                    onArchive = { store.archiveTask(task.id, !task.archived) },
                    onDelete = { store.deleteTaskPermanently(task.id) },
                    onStats = { store.selectTaskForDetail(task.id) },
                )
            }
        }

        if (longTermActiveTasks.isNotEmpty()) {
            item {
                CollapsibleStatusHeader(
                    title = "Long-term tasks",
                    count = longTermActiveTasks.size,
                    expanded = longTermTasksExpanded,
                    tagPrefix = "task-editor-long-term",
                    onClick = { longTermTasksExpanded = !longTermTasksExpanded },
                )
            }
        }

        if (longTermTasksExpanded) {
            items(longTermActiveTasks, key = { "long-term-active-${it.id}" }) { task ->
                TaskListCard(
                    task = task,
                    cycleProgress = store.progressForTaskCycleTiming(task),
                    onEdit = { editTask(task) },
                    onArchive = { store.archiveTask(task.id, !task.archived) },
                    onDelete = { store.deleteTaskPermanently(task.id) },
                    onStats = { store.selectTaskForDetail(task.id) },
                )
            }
        }

        if (showArchivedTasks && hiddenTaskCount > 0) {
            item {
                SectionTitle(
                    "Archived and inactive",
                    trailing = "$hiddenTaskCount hidden",
                )
            }
        }

        if (showArchivedTasks) {
            items(hiddenRoutineGroups, key = { "hidden-routine-${it.planId}" }) { group ->
                RoutinePlanCard(
                    group = group,
                    onEditPhase = { editTask(it) },
                    onArchive = { store.archiveRoutinePlan(group.planId, archived = !group.isArchived) },
                    onDelete = { store.deleteRoutinePlanPermanently(group.planId) },
                    onStats = { store.selectTaskForDetail(it.id) },
                )
            }
            items(hiddenTasks, key = { "hidden-${it.id}" }) { task ->
                    TaskListCard(
                        task = task,
                        cycleProgress = store.progressForTaskCycleTiming(task),
                        onEdit = { editTask(task) },
                        onArchive = { store.archiveTask(task.id, !task.archived) },
                        onDelete = { store.deleteTaskPermanently(task.id) },
                    onStats = { store.selectTaskForDetail(task.id) },
                )
            }
        }
    }

    if (phaseImportOpen) {
        PhaseBulkImportDialog(
            initialStartDate = store.draft.startDate,
            onDismiss = { phaseImportOpen = false },
            onApply = { phases, firstStartDate ->
                store.importPhases(phases, firstStartDate)
                phaseImportOpen = false
            },
        )
    }
}

internal fun taskEditorVisibleTasks(store: HabitTrackerUiStore, showArchived: Boolean): List<HabitTaskUi> {
    return store.visibleTasks(includeArchived = showArchived)
}

internal fun taskEditorActiveTasks(tasks: List<HabitTaskUi>): List<HabitTaskUi> {
    return tasks.filterNot { it.isOneTimeTask() }
}

internal data class TaskEditorRoutinePhase(
    val phase: HabitRoutinePhaseUi,
    val task: HabitTaskUi,
)

internal data class TaskEditorRoutineGroup(
    val planId: Int,
    val name: String,
    val phases: List<TaskEditorRoutinePhase>,
) {
    val isArchived: Boolean
        get() = phases.all { it.task.archived }

    val isActive: Boolean
        get() = phases.any {
            it.phase.status == RoutinePhaseStatus.ACTIVE &&
                it.task.isActive &&
                !it.task.archived
        }
}

internal fun taskEditorRoutineGroups(
    tasks: List<HabitTaskUi>,
    routinePhases: List<HabitRoutinePhaseUi>,
): List<TaskEditorRoutineGroup> {
    val tasksById = tasks.associateBy { it.id }
    return routinePhases
        .groupBy { it.routinePlanId }
        .mapNotNull { (planId, phases) ->
            val linkedPhases = phases
                .sortedBy { it.position }
                .mapNotNull { phase -> tasksById[phase.taskId]?.let { TaskEditorRoutinePhase(phase, it) } }
            if (linkedPhases.isEmpty()) {
                null
            } else {
                TaskEditorRoutineGroup(
                    planId = planId,
                    name = linkedPhases.first().phase.routinePlanName,
                    phases = linkedPhases,
                )
            }
        }
        .sortedWith(compareBy<TaskEditorRoutineGroup> { it.name.lowercase() }.thenBy { it.planId })
}

internal fun taskEditorSummary(activeCount: Int, hiddenCount: Int, showArchived: Boolean): String {
    return if (showArchived) {
        "${activeCount + hiddenCount} total"
    } else if (hiddenCount == 0) {
        "$activeCount active"
    } else {
        "$activeCount active, $hiddenCount hidden"
    }
}

@Composable
private fun TaskEditorCard(
    draft: HabitTaskDraft,
    existingTasks: List<HabitTaskUi>,
    onDraftChange: (HabitTaskDraft) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onBulkPhases: () -> Unit,
) {
    var sequenceImportOpen by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = if (draft.id == null) "New task" else "Edit task",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TaskTypeBadge(draft.type)
                }
            }
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("task-phase-bulk-import"),
                onClick = onBulkPhases,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Bulk phases")
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("task-name-field"),
                label = { Text("Name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { onDraftChange(draft.copy(notes = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("task-notes-field"),
                label = { Text("Notes") },
                minLines = 2,
            )

            DateControl(
                label = "Start date",
                date = draft.startDate,
                tagPrefix = "task-start-date",
                onDateSelected = { selectedDate ->
                    onDraftChange(
                        draft.copy(
                            startDate = selectedDate,
                            endDate = draft.durationDays
                                ?.let { selectedDate.plusDays((it.coerceAtLeast(1) - 1).toLong()) }
                                ?: draft.endDate?.let { maxOf(it, selectedDate) },
                            startsAfterTaskId = null,
                        ),
                    )
                },
            )
            if (draft.durationDays == null && draft.type != HabitTaskType.OneTime) {
                EndDateControl(
                    endDate = draft.endDate,
                    startDate = draft.startDate,
                    tagPrefix = "task-end-date",
                    onChange = { onDraftChange(draft.copy(endDate = it)) },
                )
            }
            if (draft.type != HabitTaskType.LongTerm && draft.type != HabitTaskType.OneTime) {
                CycleTimingControl(
                    draft = draft,
                    existingTasks = existingTasks,
                    onDraftChange = onDraftChange,
                )
            }

            ChipGroupTitle("Task type")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HabitTaskType.values().forEach { type ->
                    FilterChip(
                        modifier = Modifier.testTag("task-type-${type.name.lowercase()}"),
                        selected = draft.type == type,
                        onClick = {
                            onDraftChange(
                                draft.copy(
                                    type = type,
                                    skipBlockedDaysBehavior = defaultSkipBlockedDaysBehavior(type),
                                    pushable = when (type) {
                                        HabitTaskType.OneTime -> true
                                        HabitTaskType.Sequence -> true
                                        HabitTaskType.LongTerm -> false
                                        else -> draft.pushable
                                    },
                                    noActionBehavior = when (type) {
                                        HabitTaskType.LongTerm -> NoActionBehavior.MARK_MISSED
                                        HabitTaskType.OneTime -> NoActionBehavior.AUTO_PUSH
                                        else -> draft.noActionBehavior
                                    },
                                    durationDays = when (type) {
                                        HabitTaskType.LongTerm -> null
                                        HabitTaskType.OneTime -> 1
                                        else -> draft.durationDays
                                    },
                                    startsAfterTaskId = if (type == HabitTaskType.LongTerm) null else draft.startsAfterTaskId,
                                    endDate = if (type == HabitTaskType.OneTime) draft.startDate else draft.endDate,
                                    longTermRecurrenceUnit = draft.longTermRecurrenceUnit,
                                    intervalDays = if (type == HabitTaskType.LongTerm && draft.type != HabitTaskType.LongTerm) {
                                        6
                                    } else {
                                        draft.intervalDays
                                    },
                                ),
                            )
                        },
                        label = { Text(type.label) },
                    )
                }
            }

            when (draft.type) {
                HabitTaskType.Simple -> Text(
                    text = "Recurrence: Daily",
                    modifier = Modifier.testTag("task-recurrence-daily"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HabitTaskType.OneTime -> Text(
                    text = "Recurrence: Once",
                    modifier = Modifier.testTag("task-recurrence-one-time"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HabitTaskType.Interval -> IntervalControl(
                    title = "Interval",
                    interval = draft.intervalDays,
                    minimum = 2,
                    tagPrefix = "task-interval",
                    valueText = { "Every ${it.coerceAtLeast(2)} days" },
                    onChange = { onDraftChange(draft.copy(intervalDays = it.coerceAtLeast(2))) },
                )
                HabitTaskType.LongTerm -> {
                    IntervalControl(
                        title = "Repeat",
                        interval = draft.intervalDays.coerceAtLeast(1),
                        minimum = 1,
                        tagPrefix = "task-long-term-repeat",
                        valueText = { longTermRepeatText(it, draft.longTermRecurrenceUnit) },
                        onChange = { onDraftChange(draft.copy(intervalDays = it.coerceAtLeast(1))) },
                    )
                    ChipGroupTitle("Repeat unit")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LongTermRecurrenceUnit.values().forEach { unit ->
                            FilterChip(
                                modifier = Modifier.testTag("task-long-term-unit-${unit.name.lowercase()}"),
                                selected = draft.longTermRecurrenceUnit == unit,
                                onClick = { onDraftChange(draft.copy(longTermRecurrenceUnit = unit)) },
                                label = { Text(unit.label) },
                            )
                        }
                    }
                    ChipGroupTitle("Repeat from")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LongTermRecurrenceAnchor.values().forEach { anchor ->
                            FilterChip(
                                modifier = Modifier.testTag("task-long-term-anchor-${anchor.name.lowercase()}"),
                                selected = draft.longTermRecurrenceAnchor == anchor,
                                onClick = { onDraftChange(draft.copy(longTermRecurrenceAnchor = anchor)) },
                                label = { Text(anchor.editorLabel()) },
                            )
                        }
                    }
                }
                HabitTaskType.Weekday -> WeekdaySelector(
                    title = "Scheduled weekdays",
                    selected = draft.weekdays,
                    tagPrefix = "task-weekday",
                    onToggle = { day ->
                        onDraftChange(draft.copy(weekdays = draft.weekdays.toggle(day)))
                    },
                )
                HabitTaskType.Sequence -> {
                    OutlinedTextField(
                        value = draft.sequenceText,
                        onValueChange = { onDraftChange(draft.copy(sequenceText = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task-sequence-items-field"),
                        label = { Text("Sequence items (one per line)") },
                        minLines = 4,
                        supportingText = {
                            Text("${parseSequenceEditorText(draft.sequenceText).size} items")
                        },
                    )
                    FilledTonalButton(
                        modifier = Modifier.testTag("task-sequence-bulk-import"),
                        onClick = { sequenceImportOpen = true },
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Bulk paste")
                    }
                    IntervalControl(
                        title = "Days between sequence items",
                        interval = draft.sequenceSpacingDays,
                        minimum = 1,
                        tagPrefix = "task-sequence-spacing",
                        valueText = { spacing ->
                            if (spacing == 1) "Next item every day" else "Next item every $spacing days"
                        },
                        onChange = { onDraftChange(draft.copy(sequenceSpacingDays = it.coerceAtLeast(1))) },
                    )
                }
            }

            TaskTimeOfDaySelector(
                selected = draft.timeOfDay,
                tagPrefix = "task-time-of-day",
                onSelect = { onDraftChange(draft.copy(timeOfDay = it)) },
            )
            if (draft.type != HabitTaskType.LongTerm && draft.type != HabitTaskType.OneTime) {
                SwitchRow(
                    title = "Allow push",
                    subtitle = "Checklist shows Push along with Skip; Push moves this item to the next allowed day",
                    checked = draft.pushable,
                    tagPrefix = "task-editor-pushable",
                    onCheckedChange = { onDraftChange(draft.copy(pushable = it)) },
                )
                NoActionBehaviorSelector(
                    selected = draft.noActionBehavior,
                    pushable = draft.pushable,
                    tagPrefix = "task-no-action",
                    onSelect = { behavior ->
                        onDraftChange(
                            draft.copy(
                                noActionBehavior = behavior,
                                pushable = draft.pushable || behavior == NoActionBehavior.AUTO_PUSH,
                            ),
                        )
                    },
                )
            }

            WeekdaySelector(
                title = "Blocked days",
                selected = draft.blockedDays,
                tagPrefix = "task-blocked-day",
                onToggle = { day ->
                    onDraftChange(draft.copy(blockedDays = draft.blockedDays.toggle(day)))
                },
            )
            BlockedDayBehaviorSelector(
                selected = draft.skipBlockedDaysBehavior,
                tagPrefix = "task-blocked-behavior",
                onSelect = { behavior ->
                    onDraftChange(draft.copy(skipBlockedDaysBehavior = behavior))
                },
            )

            SwitchRow(
                title = "Reminders",
                subtitle = "Eligible for review and late-day reminders",
                checked = draft.reminderEnabled,
                tagPrefix = "task-editor-reminders",
                onCheckedChange = { onDraftChange(draft.copy(reminderEnabled = it)) },
            )
            SwitchRow(
                title = "Calendar visibility",
                subtitle = "Show generated occurrences in calendar views",
                checked = draft.calendarVisible,
                tagPrefix = "task-editor-calendar-visible",
                onCheckedChange = { onDraftChange(draft.copy(calendarVisible = it)) },
            )
            SwitchRow(
                title = "Active",
                subtitle = "Include this task in checklist, reminders, and maintenance",
                checked = draft.isActive && !draft.archived,
                tagPrefix = "task-editor-active",
                onCheckedChange = { active ->
                    onDraftChange(
                        draft.copy(
                            isActive = active,
                            archived = if (active) false else draft.archived,
                        ),
                    )
                },
            )
            if (draft.id != null) {
                SwitchRow(
                    title = "Archived",
                    subtitle = "Keep history while hiding from active views",
                    checked = draft.archived,
                    tagPrefix = "task-editor-archived",
                    onCheckedChange = { archived ->
                        onDraftChange(
                            draft.copy(
                                archived = archived,
                                isActive = if (archived) false else draft.isActive,
                            ),
                        )
                    },
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.testTag("task-save-button"),
                    onClick = onSave,
                    enabled = draft.name.trim().isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (draft.id == null) "Create task" else "Save task")
                }
                OutlinedButton(
                    modifier = Modifier.testTag("task-clear-button"),
                    onClick = onClear,
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }
    }

    if (sequenceImportOpen) {
        SequenceBulkImportDialog(
            existingItems = parseSequenceEditorText(draft.sequenceText),
            onDismiss = { sequenceImportOpen = false },
            onApply = { sequenceItems ->
                onDraftChange(draft.copy(sequenceText = formatSequenceEditorText(sequenceItems)))
                sequenceImportOpen = false
            },
        )
    }
}

private data class PhaseImportPreviewRow(
    val id: Int,
    val phase: PhaseImportPhase,
)

@Composable
private fun PhaseBulkImportDialog(
    initialStartDate: LocalDate,
    onDismiss: () -> Unit,
    onApply: (List<PhaseImportPhase>, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    var pasteText by remember { mutableStateOf("") }
    var firstStartDate by remember(initialStartDate) { mutableStateOf(initialStartDate) }
    var previewRows by remember { mutableStateOf<List<PhaseImportPreviewRow>?>(null) }
    var formattingGuideOpen by remember { mutableStateOf(false) }
    val appVersionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val normalizedPasteText = remember(pasteText) { normalizePhaseImportInput(pasteText) }
    val parsed = remember(normalizedPasteText) { parsePhaseImport(normalizedPasteText) }
    val rows = previewRows
    val timeline = remember(rows, firstStartDate) {
        buildPhaseImportTimeline(rows.orEmpty().map { it.phase }, firstStartDate)
    }
    val canApply = rows?.isNotEmpty() == true && rows.none { it.phase.name.isBlank() }

    if (formattingGuideOpen) {
        CopyableFormattingGuideDialog(
            title = "AI phase formatting",
            options = listOf(
                FormattingGuideOption(
                    label = "Phases",
                    instructions = phaseAiFormattingInstructions,
                    destinationHint = "Paste AI output into this Bulk phases importer.",
                ),
            ),
            tagPrefix = "phase-format-guide",
            onBack = { formattingGuideOpen = false },
            onCopy = { instructions ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Habit Tracker phase format", instructions),
                )
            },
        )
        return
    }

    AlertDialog(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 700.dp)
            .padding(horizontal = 12.dp)
            .testTag("phase-import-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(if (rows == null) "Bulk import phases" else "Preview phased plan") },
        text = {
            if (rows == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateControl(
                        label = "First phase starts",
                        date = firstStartDate,
                        tagPrefix = "phase-import-start-date",
                        onDateSelected = { firstStartDate = it },
                    )
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 260.dp)
                            .testTag("phase-import-paste-field"),
                        label = { Text("Paste AI phase blocks") },
                        minLines = 6,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = when {
                                    parsed.issues.isNotEmpty() -> "${parsed.issues.size} format errors"
                                    else -> "${parsed.phases.size} phases ready"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (parsed.issues.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            if (appVersionName.isNotBlank()) {
                                Text(
                                    text = "App v$appVersionName",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            modifier = Modifier.testTag("phase-import-format-guide"),
                            onClick = { formattingGuideOpen = true },
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("AI format")
                        }
                    }
                    parsed.issues.take(5).forEach { issue ->
                        val source = normalizedPasteText.lineSequence()
                            .drop(issue.lineNumber - 1)
                            .firstOrNull()
                            ?.trim()
                            .orEmpty()
                        Column(
                            modifier = Modifier.testTag("phase-import-error-${issue.lineNumber}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Line ${issue.lineNumber}: ${issue.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            if (source.isNotBlank()) {
                                Text(
                                    text = source,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (parsed.issues.size > 5) {
                        Text(
                            text = "+${parsed.issues.size - 5} more errors",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${rows.size} linked phases",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = firstStartDate.monthDayLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .testTag("phase-import-preview-list"),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                            val phaseTimeline = timeline[index]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = (index + 1).toString(),
                                        modifier = Modifier.width(28.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = row.phase.name,
                                        onValueChange = { updatedName ->
                                            previewRows = rows.map {
                                                if (it.id == row.id) {
                                                    it.copy(phase = it.phase.copy(name = updatedName))
                                                } else {
                                                    it
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("phase-import-preview-name-$index"),
                                        minLines = 1,
                                        maxLines = 2,
                                        isError = row.phase.name.isBlank(),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                    )
                                    IconButton(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("phase-import-move-up-$index"),
                                        enabled = index > 0,
                                        onClick = {
                                            previewRows = rows.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move phase up", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("phase-import-move-down-$index"),
                                        enabled = index < rows.lastIndex,
                                        onClick = {
                                            previewRows = rows.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move phase down", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("phase-import-delete-$index"),
                                        onClick = { previewRows = rows.filterNot { it.id == row.id } },
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete phase", modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(
                                    text = "${phaseTimeline.dateRangeLabel()}  |  " +
                                        "${row.phase.durationDays.dayCountLabel()}  |  ${row.phase.scheduleLabel()}",
                                    modifier = Modifier
                                        .padding(start = 28.dp)
                                        .testTag("phase-import-preview-summary-$index"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${row.phase.timeOfDay.label()}  |  ${row.phase.noActionBehavior.summaryLabel()}",
                                    modifier = Modifier.padding(start = 28.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (row.phase.structured) {
                                    val exerciseCount = row.phase.workoutDays.sumOf { it.exercises.size }
                                    Text(
                                        text = "${row.phase.workoutDays.size} numbered days  |  $exerciseCount exercises  |  " +
                                            if (row.phase.advanceMode == com.example.habittracker.data.PhaseAdvanceMode.MANUAL) {
                                                "Manual review"
                                            } else {
                                                "Automatic advancement"
                                            },
                                        modifier = Modifier.padding(start = 28.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else if (row.phase.sequenceItems.isNotEmpty()) {
                                    Text(
                                        text = row.phase.sequenceItems.joinToString(" > "),
                                        modifier = Modifier.padding(start = 28.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (row.phase.progressionNote.isNotBlank()) {
                                    Text(
                                        text = "Review: ${row.phase.progressionNote}",
                                        modifier = Modifier.padding(start = 28.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (row.phase.notes.isNotBlank()) {
                                    Text(
                                        text = "Note: ${row.phase.notes}",
                                        modifier = Modifier.padding(start = 28.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (rows == null) {
                Button(
                    modifier = Modifier.testTag("phase-import-preview-button"),
                    enabled = parsed.phases.isNotEmpty() && parsed.issues.isEmpty(),
                    onClick = {
                        previewRows = parsed.phases.mapIndexed { index, phase ->
                            PhaseImportPreviewRow(id = index, phase = phase)
                        }
                    },
                ) {
                    Text("Preview")
                }
            } else {
                Button(
                    modifier = Modifier.testTag("phase-import-apply-button"),
                    enabled = canApply,
                    onClick = { onApply(rows.map { it.phase.copy(name = it.phase.name.trim()) }, firstStartDate) },
                ) {
                    Text("Create phases")
                }
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag(
                    if (rows == null) "phase-import-cancel-button" else "phase-import-back-button",
                ),
                onClick = {
                    if (rows == null) onDismiss() else previewRows = null
                },
            ) {
                Text(if (rows == null) "Cancel" else "Back")
            }
        },
    )
}

private fun PhaseImportTimelineRow.dateRangeLabel(): String {
    return if (startDate == endDate) {
        startDate.monthDayLabel()
    } else {
        "${startDate.monthDayLabel()} - ${endDate.monthDayLabel()}"
    }
}

private data class SequenceImportPreviewRow(
    val id: Int,
    val name: String,
)

@Composable
private fun SequenceBulkImportDialog(
    existingItems: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    var pasteText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SequenceImportMode.REPLACE) }
    var previewRows by remember { mutableStateOf<List<SequenceImportPreviewRow>?>(null) }
    var formattingGuideOpen by remember { mutableStateOf(false) }
    val parsedPaste = remember(pasteText) { parseMultilineSequenceItems(pasteText) }
    val phasedFormatDetected = remember(pasteText) { containsStructuredPhaseRows(pasteText) }
    val rows = previewRows
    val canApply = rows?.isNotEmpty() == true && rows.none { it.name.isBlank() }

    if (formattingGuideOpen) {
        SequenceFormattingGuideDialog(
            onBack = { formattingGuideOpen = false },
            onCopy = { instructions ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Habit Tracker import format", instructions),
                )
            },
        )
        return
    }

    AlertDialog(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .padding(horizontal = 12.dp)
            .testTag("sequence-import-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(if (rows == null) "Bulk paste sequence" else "Preview sequence")
        },
        text = {
            if (rows == null) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SequenceImportMode.values().forEachIndexed { index, importMode ->
                            SegmentedButton(
                                modifier = Modifier.weight(1f),
                                selected = mode == importMode,
                                onClick = { mode = importMode },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = SequenceImportMode.values().size,
                                ),
                                label = {
                                    Text(
                                        if (importMode == SequenceImportMode.REPLACE) {
                                            "Replace"
                                        } else {
                                            "Append"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp)
                            .testTag("sequence-import-paste-field"),
                        label = { Text("One sequence item per line") },
                        minLines = 7,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (phasedFormatDetected) {
                                "Phased format: use Bulk phases"
                            } else {
                                "${parsedPaste.size} items ready"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (phasedFormatDetected) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.testTag("sequence-import-status"),
                        )
                        TextButton(
                            modifier = Modifier.testTag("sequence-import-format-guide"),
                            onClick = { formattingGuideOpen = true },
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("AI format")
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${rows.size} sequence items",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (mode == SequenceImportMode.REPLACE) "Replace" else "Append",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#", modifier = Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall)
                        Text("Sequence item", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(120.dp))
                    }
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .testTag("sequence-import-preview-list"),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    modifier = Modifier.width(28.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = row.name,
                                    onValueChange = { updatedName ->
                                        previewRows = rows.map {
                                            if (it.id == row.id) it.copy(name = updatedName) else it
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sequence-import-preview-name-$index"),
                                    minLines = 1,
                                    maxLines = 2,
                                    isError = row.name.isBlank(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                )
                                IconButton(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("sequence-import-move-up-$index"),
                                    enabled = index > 0,
                                    onClick = {
                                        previewRows = rows.toMutableList().apply {
                                            add(index - 1, removeAt(index))
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("sequence-import-move-down-$index"),
                                    enabled = index < rows.lastIndex,
                                    onClick = {
                                        previewRows = rows.toMutableList().apply {
                                            add(index + 1, removeAt(index))
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("sequence-import-delete-$index"),
                                    onClick = { previewRows = rows.filterNot { it.id == row.id } },
                                ) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Delete item", modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (rows == null) {
                Button(
                    modifier = Modifier.testTag("sequence-import-preview-button"),
                    enabled = parsedPaste.isNotEmpty() && !phasedFormatDetected,
                    onClick = {
                        val merged = mergeSequenceImport(existingItems, parsedPaste, mode)
                        previewRows = merged.mapIndexed { index, item ->
                            SequenceImportPreviewRow(id = index, name = item)
                        }
                    },
                ) {
                    Text("Preview")
                }
            } else {
                Button(
                    modifier = Modifier.testTag("sequence-import-apply-button"),
                    enabled = canApply,
                    onClick = { onApply(rows.map { it.name.trim() }) },
                ) {
                    Text("Apply")
                }
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag(
                    if (rows == null) "sequence-import-cancel-button" else "sequence-import-back-button",
                ),
                onClick = {
                    if (rows == null) {
                        onDismiss()
                    } else {
                        previewRows = null
                    }
                },
            ) {
                Text(if (rows == null) "Cancel" else "Back")
            }
        },
    )
}

@Composable
private fun SequenceFormattingGuideDialog(
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
) {
    CopyableFormattingGuideDialog(
        title = "AI formatting guide",
        options = listOf(
            FormattingGuideOption(
                label = "Sequence",
                instructions = sequenceAiFormattingInstructions,
                destinationHint = "Paste AI output into this sequence importer.",
            ),
            FormattingGuideOption(
                label = "Phases",
                instructions = phaseAiFormattingInstructions,
                destinationHint = "Paste AI output into Tasks > Bulk phases.",
            ),
        ),
        tagPrefix = "sequence-format-guide",
        onBack = onBack,
        onCopy = onCopy,
    )
}

private data class FormattingGuideOption(
    val label: String,
    val instructions: String,
    val destinationHint: String,
)

@Composable
private fun CopyableFormattingGuideDialog(
    title: String,
    options: List<FormattingGuideOption>,
    tagPrefix: String,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val selectedOption = options.getOrElse(selectedIndex) { options.first() }
    AlertDialog(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .padding(horizontal = 12.dp)
            .testTag("$tagPrefix-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onBack,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (options.size > 1) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("$tagPrefix-option-${option.label.lowercase()}"),
                                selected = selectedIndex == index,
                                onClick = {
                                    selectedIndex = index
                                    copied = false
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
                Text(
                    text = selectedOption.destinationHint,
                    modifier = Modifier.testTag("$tagPrefix-destination"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (options.size > 1) 250.dp else 300.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .testTag("$tagPrefix-note"),
                    ) {
                        item {
                            SelectionContainer {
                                Text(
                                    text = selectedOption.instructions,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                modifier = Modifier.testTag("$tagPrefix-copy"),
                onClick = {
                    onCopy(selectedOption.instructions)
                    copied = true
                },
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied" else "Copy instructions")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-back"),
                onClick = onBack,
            ) {
                Text("Back")
            }
        },
    )
}


@Composable
private fun DateControl(
    label: String,
    date: LocalDate,
    tagPrefix: String,
    onDateSelected: (LocalDate) -> Unit,
) {
    var showPicker by remember(tagPrefix) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = date.fullDateLabel(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedButton(
            modifier = Modifier.testTag("$tagPrefix-picker"),
            onClick = { showPicker = true },
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pick date")
        }
    }
    if (showPicker) {
        CalendarDatePickerDialog(
            date = date,
            tagPrefix = tagPrefix,
            onDismiss = { showPicker = false },
            onDateSelected = {
                onDateSelected(it)
                showPicker = false
            },
        )
    }
}

@Composable
private fun EndDateControl(
    endDate: LocalDate?,
    startDate: LocalDate,
    enabled: Boolean = true,
    tagPrefix: String,
    onChange: (LocalDate?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                modifier = Modifier.testTag("$tagPrefix-no-end"),
                selected = endDate == null,
                enabled = enabled,
                onClick = { onChange(null) },
                label = { Text("No end") },
            )
            FilterChip(
                modifier = Modifier.testTag("$tagPrefix-use-end"),
                selected = endDate != null,
                enabled = enabled,
                onClick = { onChange(endDate ?: startDate.plusDays(60)) },
                label = { Text("Set end date") },
            )
        }
        if (endDate != null && enabled) {
            DateControl(
                label = "End date",
                date = endDate,
                tagPrefix = tagPrefix,
                onDateSelected = { onChange(maxOf(startDate, it)) },
            )
        }
    }
}

@Composable
private fun CycleTimingControl(
    draft: HabitTaskDraft,
    existingTasks: List<HabitTaskUi>,
    onDraftChange: (HabitTaskDraft) -> Unit,
) {
    val durationDays = draft.durationDays
    val eligibleParents = existingTasks
        .filter { it.id != draft.id && it.durationDays != null && it.endDate != null }
        .sortedBy { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipGroupTitle("Cycle timing")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                modifier = Modifier.testTag("task-duration-off"),
                selected = durationDays == null,
                onClick = {
                    onDraftChange(
                        draft.copy(
                            durationDays = null,
                            startsAfterTaskId = null,
                            endDate = null,
                            cycleGroupId = null,
                            cycleGroupName = "",
                            cycleRestartBehavior = CycleRestartBehavior.OFF,
                        ),
                    )
                },
                label = { Text("No length") },
            )
            FilterChip(
                modifier = Modifier.testTag("task-duration-on"),
                selected = durationDays != null,
                onClick = {
                    val days = durationDays ?: draft.endDate
                        ?.let { ChronoUnit.DAYS.between(draft.startDate, it).toInt() + 1 }
                        ?.coerceAtLeast(1)
                        ?: 14
                    onDraftChange(
                        draft.copy(
                            durationDays = days,
                            endDate = draft.startDate.plusDays((days - 1).toLong()),
                            cycleDurationDays = days,
                        ),
                    )
                },
                label = { Text("Set length") },
            )
        }
        if (durationDays != null) {
            IntervalControl(
                title = "Length",
                interval = durationDays,
                minimum = 1,
                tagPrefix = "task-duration",
                valueText = { it.dayCountLabel() },
                onChange = { days ->
                    val normalizedDays = days.coerceAtLeast(1)
                    onDraftChange(
                        draft.copy(
                            durationDays = normalizedDays,
                            endDate = draft.startDate.plusDays((normalizedDays - 1).toLong()),
                            cycleDurationDays = normalizedDays,
                        ),
                    )
                },
            )
            Text(
                text = "Ends ${draft.startDate.plusDays((durationDays.coerceAtLeast(1) - 1).toLong()).fullDateLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CycleTimingRestartControl(
                draft = draft.copy(cycleDurationDays = durationDays.coerceAtLeast(1)),
                onDraftChange = onDraftChange,
            )
            if (eligibleParents.isNotEmpty()) {
                ChipGroupTitle("Start after")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 1.dp),
                ) {
                    item {
                        FilterChip(
                            modifier = Modifier.testTag("task-cycle-parent-none"),
                            selected = draft.startsAfterTaskId == null,
                            onClick = { onDraftChange(draft.copy(startsAfterTaskId = null)) },
                            label = { Text("Manual start") },
                        )
                    }
                    items(eligibleParents, key = { it.id }) { task ->
                        FilterChip(
                            modifier = Modifier.testTag("task-cycle-parent-${task.id}"),
                            selected = draft.startsAfterTaskId == task.id,
                            onClick = {
                                val nextStart = task.endDate?.plusDays(1) ?: draft.startDate
                                onDraftChange(
                                    draft.copy(
                                        startsAfterTaskId = task.id,
                                        startDate = nextStart,
                                        endDate = nextStart.plusDays((durationDays.coerceAtLeast(1) - 1).toLong()),
                                    ),
                                )
                            },
                            label = {
                                Text(
                                    text = task.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CycleTimingRestartControl(
    draft: HabitTaskDraft,
    onDraftChange: (HabitTaskDraft) -> Unit,
) {
    val enabled = draft.cycleRestartBehavior != CycleRestartBehavior.OFF
    ChipGroupTitle("Auto restart")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CycleRestartBehavior.values().forEach { behavior ->
            FilterChip(
                modifier = Modifier.testTag("task-cycle-restart-behavior-${behavior.name.lowercase()}"),
                selected = draft.cycleRestartBehavior == behavior,
                onClick = {
                    onDraftChange(
                        draft.copy(
                            cycleRestartBehavior = behavior,
                            cycleDurationDays = draft.durationDays?.coerceAtLeast(1) ?: draft.cycleDurationDays.coerceAtLeast(1),
                        ),
                    )
                },
                label = { Text(behavior.editorLabel()) },
            )
        }
    }
    if (!enabled) return
    Text(
        text = "When this length-based task gets too disrupted, restart this task's cycle timing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    IntervalControl(
        title = "Reset threshold",
        interval = draft.cycleResetThresholdPercent,
        minimum = 1,
        maximum = 100,
        repeatStep = 5,
        tagPrefix = "task-cycle-restart-threshold",
        valueText = { "${it.coerceIn(1, 100)}%" },
        onChange = { onDraftChange(draft.copy(cycleResetThresholdPercent = it.coerceIn(1, 100))) },
    )
    ChipGroupTitle("Restart timing")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CycleRestartTiming.values().forEach { timing ->
            FilterChip(
                modifier = Modifier.testTag("task-cycle-restart-timing-${timing.name.lowercase()}"),
                selected = draft.cycleRestartTiming == timing,
                onClick = { onDraftChange(draft.copy(cycleRestartTiming = timing)) },
                label = { Text(timing.editorLabel()) },
            )
        }
    }
    WeekdaySelector(
        title = "Restart blocked days",
        selected = draft.cycleBlockedDays,
        tagPrefix = "task-cycle-restart-blocked-day",
        onToggle = { day ->
            onDraftChange(draft.copy(cycleBlockedDays = draft.cycleBlockedDays.toggle(day)))
        },
    )
    Text(
        text = "Example: A ${draft.cycleDurationDays.dayCountLabel()} task at ${draft.cycleResetThresholdPercent.coerceIn(1, 100)}% restarts after ${cycleThresholdDays(draft.cycleDurationDays, draft.cycleResetThresholdPercent).dayCountLabel()} disrupted days.",
        modifier = Modifier.testTag("task-cycle-restart-example"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IntervalControl(
    title: String,
    interval: Int,
    minimum: Int = 1,
    maximum: Int? = null,
    step: Int = 1,
    repeatStep: Int = step,
    tagPrefix: String,
    valueText: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    val normalizedStep = step.coerceAtLeast(1)
    val normalizedRepeatStep = repeatStep.coerceAtLeast(1)
    fun bounded(value: Int): Int {
        val minBounded = value.coerceAtLeast(minimum)
        return maximum?.let { minBounded.coerceAtMost(it) } ?: minBounded
    }
    val normalizedInterval = bounded(interval)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipGroupTitle(title)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RepeatableOutlinedButton(
                modifier = Modifier.testTag("$tagPrefix-minus"),
                onClick = { onChange(bounded(interval - normalizedStep)) },
                onRepeat = { onChange(bounded(interval - normalizedRepeatStep)) },
                enabled = normalizedInterval > minimum,
            ) {
                Text("-")
            }
            Text(
                text = valueText(normalizedInterval),
                modifier = Modifier.testTag("$tagPrefix-value"),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            RepeatableOutlinedButton(
                modifier = Modifier.testTag("$tagPrefix-plus"),
                onClick = { onChange(bounded(interval + normalizedStep)) },
                onRepeat = { onChange(bounded(interval + normalizedRepeatStep)) },
                enabled = maximum == null || normalizedInterval < maximum,
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun RepeatableOutlinedButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onRepeat: () -> Unit = onClick,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        delay(450)
        while (pressed && enabled) {
            currentOnRepeat()
            delay(90)
        }
    }
    OutlinedButton(
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
    ) {
        content()
    }
}

@Composable
private fun CalendarDatePickerDialog(
    date: LocalDate,
    tagPrefix: String,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-confirm"),
                onClick = {
                    pickerState.selectedDateMillis
                        ?.toUtcLocalDate()
                        ?.let(onDateSelected)
                },
            ) {
                Text("Set date")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-cancel"),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    ) {
        DatePicker(
            state = pickerState,
            modifier = Modifier.testTag("$tagPrefix-calendar-picker"),
        )
    }
}

@Composable
private fun WeekdaySelector(
    title: String,
    selected: Set<DayOfWeek>,
    tagPrefix: String,
    onToggle: (DayOfWeek) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(tagPrefix),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipGroupTitle(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DayOfWeek.values().forEach { day ->
                FilterChip(
                    modifier = Modifier.testTag("$tagPrefix-${day.name.lowercase()}"),
                    selected = day in selected,
                    onClick = { onToggle(day) },
                    label = { Text(day.shortLabel()) },
                )
            }
        }
    }
}

@Composable
private fun BlockedDayBehaviorSelector(
    selected: SkipBlockedDaysBehavior,
    tagPrefix: String,
    onSelect: (SkipBlockedDaysBehavior) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(tagPrefix),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipGroupTitle("If this lands on a blocked day")
        Text(
            text = "Blocked days are days you do not want this task placed on the calendar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkipBlockedDaysBehavior.values().forEach { behavior ->
                FilterChip(
                    modifier = Modifier.testTag("$tagPrefix-${behavior.name.lowercase()}"),
                    selected = selected == behavior,
                    onClick = { onSelect(behavior) },
                    label = { Text(behavior.editorLabel()) },
                )
            }
        }
    }
}

@Composable
private fun NoActionBehaviorSelector(
    selected: NoActionBehavior,
    pushable: Boolean,
    tagPrefix: String,
    onSelect: (NoActionBehavior) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(tagPrefix),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipGroupTitle("If no action is taken")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoActionBehavior.values().forEach { behavior ->
                FilterChip(
                    modifier = Modifier.testTag("$tagPrefix-${behavior.name.lowercase()}"),
                    selected = selected == behavior,
                    onClick = { onSelect(behavior) },
                    label = { Text(behavior.editorLabel(pushable)) },
                )
            }
        }
    }
}

@Composable
private fun ThemePreferenceSelector(
    selected: String,
    tagPrefix: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(tagPrefix),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipGroupTitle("Theme")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                FilterChip(
                    modifier = Modifier.testTag("$tagPrefix-$value"),
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun TaskTimeOfDaySelector(
    selected: TaskTimeOfDay,
    tagPrefix: String,
    onSelect: (TaskTimeOfDay) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(tagPrefix),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipGroupTitle("Time of day")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                TaskTimeOfDay.GENERAL,
                TaskTimeOfDay.MORNING,
                TaskTimeOfDay.NOON,
                TaskTimeOfDay.EVENING,
            ).forEach { timeOfDay ->
                FilterChip(
                    modifier = Modifier.testTag("$tagPrefix-${timeOfDay.name.lowercase()}"),
                    selected = selected == timeOfDay,
                    onClick = { onSelect(timeOfDay) },
                    label = { Text(timeOfDay.label()) },
                )
            }
        }
    }
}

@Composable
private fun RoutinePlanCard(
    group: TaskEditorRoutineGroup,
    onEditPhase: (HabitTaskUi) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onStats: (HabitTaskUi) -> Unit,
) {
    var expanded by remember(group.planId) { mutableStateOf(false) }
    var confirmDelete by remember(group.planId) { mutableStateOf(false) }
    val currentPhase = group.phases.firstOrNull { it.phase.status == RoutinePhaseStatus.ACTIVE }
    val statusSummary = when {
        group.isArchived -> "Archived"
        currentPhase != null -> "Current: ${currentPhase.task.name}"
        group.phases.all { it.phase.status == RoutinePhaseStatus.COMPLETED } -> "Completed"
        else -> "Inactive"
    }
    Card(
        modifier = Modifier.testTag("routine-plan-card-${group.planId}"),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isArchived) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${group.phases.size} phases  |  $statusSummary",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    modifier = Modifier.testTag("routine-plan-toggle-${group.planId}"),
                    onClick = { expanded = !expanded },
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse phased routine" else "Expand phased routine",
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routine-plan-content-${group.planId}"),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    group.phases.forEachIndexed { index, phase ->
                        RoutinePlanPhaseRow(
                            item = phase,
                            onEdit = { onEditPhase(phase.task) },
                            onStats = { onStats(phase.task) },
                        )
                        if (index != group.phases.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.testTag("routine-plan-archive-${group.planId}"),
                    onClick = onArchive,
                ) {
                    Icon(
                        imageVector = if (group.isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (group.isArchived) "Restore all" else "Archive all")
                }
                if (group.isArchived) {
                    OutlinedButton(
                        modifier = Modifier.testTag("routine-plan-delete-${group.planId}"),
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete routine")
                    }
                }
            }

            if (confirmDelete) {
                Text(
                    text = "Delete every phase, calendar row, workout note, and history entry in this routine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.testTag("routine-plan-confirm-delete-${group.planId}"),
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Confirm delete")
                    }
                    TextButton(
                        modifier = Modifier.testTag("routine-plan-cancel-delete-${group.planId}"),
                        onClick = { confirmDelete = false },
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutinePlanPhaseRow(
    item: TaskEditorRoutinePhase,
    onEdit: () -> Unit,
    onStats: () -> Unit,
) {
    var expanded by remember(item.phase.id) { mutableStateOf(false) }
    val phase = item.phase
    val task = item.task
    val statusLabel = when (phase.status) {
        RoutinePhaseStatus.ACTIVE -> "Current"
        RoutinePhaseStatus.UPCOMING -> "Upcoming"
        RoutinePhaseStatus.COMPLETED -> "Completed"
    }
    val timingLabel = when (phase.advanceMode) {
        PhaseAdvanceMode.MANUAL -> "Manual review after ${phase.minimumDays.dayCountLabel()}"
        PhaseAdvanceMode.AUTOMATIC -> "Automatic after ${phase.minimumDays.dayCountLabel()}"
    }
    val scheduleLabel = when {
        task.workoutDays.isNotEmpty() -> "${task.workoutDays.size} day workout sequence"
        task.type == HabitTaskType.Sequence && task.sequenceItems.isNotEmpty() ->
            "${task.sequenceItems.size} item sequence"
        else -> task.recurrenceSummary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("routine-phase-row-${phase.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Phase ${phase.position + 1}: ${task.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (phase.status == RoutinePhaseStatus.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.testTag("routine-phase-title-${phase.id}"),
                )
                Text(
                    text = "$statusLabel  |  $timingLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (phase.status == RoutinePhaseStatus.ACTIVE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "$scheduleLabel  |  ${task.timeOfDay.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                modifier = Modifier.testTag("routine-phase-toggle-${phase.id}"),
                onClick = { expanded = !expanded },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse phase details" else "Expand phase details",
                )
            }
        }

        if (phase.progressionNote.isNotBlank()) {
            Text(
                text = "Review: ${phase.progressionNote}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("routine-phase-review-${phase.id}"),
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp)
                    .testTag("routine-phase-content-${phase.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (
                    task.notes.isNotBlank() &&
                    !task.notes.equals(phase.progressionNote, ignoreCase = true)
                ) {
                    Text(
                        text = task.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.workoutDays.isNotEmpty()) {
                    task.workoutDays.sortedBy { it.position }.forEach { day ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = day.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (day.notes.isNotBlank()) {
                                Text(
                                    text = day.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            day.exercises.sortedBy { it.position }.forEach { exercise ->
                                val conditional = if (exercise.requirement == ExerciseRequirement.CONDITIONAL) {
                                    " (conditional)"
                                } else {
                                    ""
                                }
                                Text(
                                    text = "• ${exercise.name}$conditional" +
                                        exercise.prescription.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (exercise.instructions.isNotBlank()) {
                                    Text(
                                        text = exercise.instructions,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                } else if (task.sequenceItems.isNotEmpty()) {
                    task.sequenceItems.forEachIndexed { index, sequenceItem ->
                        Text(
                            text = "${index + 1}. $sequenceItem",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        modifier = Modifier.testTag("routine-phase-edit-${phase.id}"),
                        onClick = onEdit,
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Edit phase")
                    }
                    TextButton(
                        modifier = Modifier.testTag("routine-phase-stats-${phase.id}"),
                        onClick = onStats,
                    ) {
                        Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Stats")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListCard(
    task: HabitTaskUi,
    cycleProgress: HabitCycleProgressUi?,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onStats: () -> Unit,
) {
    var confirmDelete by remember(task.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.testTag("task-card-${task.id}"),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        colors = CardDefaults.cardColors(
            containerColor = if (task.archived) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOfNotNull(
                            task.recurrenceSummary,
                            task.timeOfDay.label(),
                            task.noActionBehavior.summaryLabel(),
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TaskTypeBadge(task.type)
            }
            if (task.notes.isNotBlank()) {
                Text(
                    text = task.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            cycleProgress?.let { progress ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task-cycle-progress-${task.id}"),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        text = cycleProgressLabel(progress),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    modifier = Modifier.testTag("task-edit-${task.id}"),
                    onClick = onEdit,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Edit")
                }
                TextButton(
                    modifier = Modifier.testTag("task-stats-${task.id}"),
                    onClick = onStats,
                ) {
                    Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Stats")
                }
                OutlinedButton(
                    modifier = Modifier.testTag("task-archive-toggle-${task.id}"),
                    onClick = onArchive,
                ) {
                    Icon(
                        imageVector = if (task.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (task.archived) "Restore" else "Archive")
                }
                if (task.archived) {
                    OutlinedButton(
                        modifier = Modifier.testTag("task-delete-${task.id}"),
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete permanently")
                    }
                }
            }
            if (confirmDelete) {
                Text(
                    text = "Delete removes this task, its calendar rows, notes, and history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.testTag("task-confirm-delete-${task.id}"),
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Confirm delete")
                    }
                    TextButton(
                        modifier = Modifier.testTag("task-cancel-delete-${task.id}"),
                        onClick = { confirmDelete = false },
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsScreen(store: HabitTrackerUiStore) {
    val tasks = store.visibleTasks(includeArchived = true)
    val selectedTask = store.taskById(store.detailTaskId ?: tasks.firstOrNull()?.id ?: -1)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stats-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            LazyRow(
                modifier = Modifier.testTag("stats-task-filter-list"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    FilterChip(
                        modifier = Modifier.testTag("stats-task-filter-${task.id}"),
                        selected = selectedTask?.id == task.id,
                        onClick = { store.detailTaskId = task.id },
                        label = {
                            Text(
                                text = task.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        if (selectedTask == null) {
            item { EmptyState("Create a task to see detail and stats.") }
        } else {
            val stats = store.statsFor(selectedTask.id)
            val history = store.historyOccurrencesForTask(selectedTask.id)
            val logs = store.recentLogsForTask(selectedTask.id)
            item {
                TaskDetailHeader(selectedTask)
            }
            item {
                OutlinedButton(
                    modifier = Modifier.testTag("task-detail-calendar-${selectedTask.id}"),
                    onClick = { store.openTaskCalendar(selectedTask.id) },
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Calendar history")
                }
            }
            item {
                StatsGrid(stats, tagPrefix = "task-detail-${selectedTask.id}")
            }
            item {
                SectionTitle(
                    title = "Recent history",
                    trailing = "${history.size} rows",
                    modifier = Modifier.testTag("task-detail-history-title-${selectedTask.id}"),
                )
            }
            items(history, key = { it.id }) { occurrence ->
                CompactOccurrenceRow(
                    occurrence = occurrence,
                    task = selectedTask,
                    tagPrefix = "task-detail-history-${occurrence.id}",
                    currentOperationalDate = store.operationalDate,
                    onSaveNote = { store.updateOccurrenceNote(occurrence.id, it) },
                    onShift = { store.shiftOccurrenceForward(occurrence.id) },
                )
            }
            item {
                SectionTitle(
                    title = "Activity log",
                    trailing = "${logs.size} entries",
                    modifier = Modifier.testTag("task-detail-log-title-${selectedTask.id}"),
                )
            }
            if (logs.isEmpty()) {
                item { EmptyState("No activity has been logged for this task.") }
            } else {
                items(logs, key = { it.id }) { log ->
                    LogRow(log, tagPrefix = "task-detail-log-${log.id}")
                }
            }
        }
    }
}

@Composable
private fun TaskDetailHeader(task: HabitTaskUi) {
    Card(
        modifier = Modifier.testTag("task-detail-header-${task.id}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = task.recurrenceSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TaskTypeBadge(task.type)
            }
            if (task.sequenceItems.isNotEmpty()) {
                Text(
                    text = "Sequence: ${task.sequenceItems.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = "${task.timeOfDay.label()} task • ${if (task.pushable) "Push enabled" else "Skip enabled"} • ${task.noActionBehavior.summaryLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (task.notes.isNotBlank()) {
                Text(
                    text = task.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: HabitStatsUi, tagPrefix: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Current streak", stats.currentStreak.toString(), Modifier.weight(1f).testTag("$tagPrefix-current-streak"))
            MetricTile("Longest streak", stats.longestStreak.toString(), Modifier.weight(1f).testTag("$tagPrefix-longest-streak"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Completion", "${stats.completionPercentage}%", Modifier.weight(1f).testTag("$tagPrefix-completion"))
            MetricTile("Completed", stats.completed.toString(), Modifier.weight(1f).testTag("$tagPrefix-completed"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Skipped", "${stats.skipped} (${stats.skipRate}%)", Modifier.weight(1f).testTag("$tagPrefix-skipped"))
            MetricTile("Missed", "${stats.missed} (${stats.missRate}%)", Modifier.weight(1f).testTag("$tagPrefix-missed"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Pushed", stats.shifted.toString(), Modifier.weight(1f).testTag("$tagPrefix-shifted"))
            MetricTile("Past total", stats.pastTotal.toString(), Modifier.weight(1f).testTag("$tagPrefix-past-total"))
        }
    }
}

@Composable
private fun SettingsScreen(store: HabitTrackerUiStore) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val backupRepository = remember(appContext) { BackupRepository(appContext) }
    var exactAlarmGranted by remember { mutableStateOf(hasExactAlarmAccess(context)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationAccess(context)) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val exactAlarmSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        exactAlarmGranted = hasExactAlarmAccess(context)
        if (exactAlarmGranted) {
            store.rescheduleReminders()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted || hasNotificationAccess(context)
        if (notificationGranted) {
            store.rescheduleReminders()
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            store.cancelPreparedManualBackup()
        } else {
            store.completePreparedManualBackup(uri)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            store.setRestoreSource(null)
        } else {
            pendingRestoreUri = uri
            store.restoreStatus = "Restore file selected; confirm replacement"
        }
    }
    val autoBackupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, permissionFlags)
            }
            store.updateSettings {
                it.copy(
                    autoBackupEnabled = true,
                    autoBackupFolderUri = uri.toString(),
                )
            }
            store.backupStatus = "Auto backup folder selected"
        } else {
            store.backupStatus = "Auto backup folder not selected"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsCard("Day boundary") {
                TimePresetRow(
                    label = "A new day starts at",
                    time = store.settings.dayRolloverTime,
                    tagPrefix = "settings-rollover",
                    onPreset = { time -> store.updateSettings { it.copy(dayRolloverTime = time) } },
                )
            }
        }

        item {
            SettingsCard("Reminders") {
                SwitchRow(
                    title = "Daily review",
                    subtitle = store.settings.dailyReviewTime.clockLabel(),
                    checked = store.settings.dailyReviewEnabled,
                    tagPrefix = "settings-daily-review",
                    onCheckedChange = { checked -> store.updateSettings { it.copy(dailyReviewEnabled = checked) } },
                )
                TimePresetRow(
                    label = "Review time",
                    time = store.settings.dailyReviewTime,
                    tagPrefix = "settings-review-time",
                    onPreset = { time -> store.updateSettings { it.copy(dailyReviewTime = time) } },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Late-day unchecked reminder",
                    subtitle = store.settings.lateReminderTime.clockLabel(),
                    checked = store.settings.lateReminderEnabled,
                    tagPrefix = "settings-late-reminder",
                    onCheckedChange = { checked -> store.updateSettings { it.copy(lateReminderEnabled = checked) } },
                )
                TimePresetRow(
                    label = "Late reminder",
                    time = store.settings.lateReminderTime,
                    tagPrefix = "settings-late-time",
                    onPreset = { time -> store.updateSettings { it.copy(lateReminderTime = time) } },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Task time reminders",
                    subtitle = "Morning ${store.settings.morningTaskReminderTime.clockLabel()} / Noon ${store.settings.noonTaskReminderTime.clockLabel()} / Evening ${store.settings.eveningTaskReminderTime.clockLabel()}",
                    checked = store.settings.taskTimeReminderEnabled,
                    tagPrefix = "settings-task-time-reminders",
                    onCheckedChange = { checked -> store.updateSettings { it.copy(taskTimeReminderEnabled = checked) } },
                )
                TimePresetRow(
                    label = "Morning task time",
                    time = store.settings.morningTaskReminderTime,
                    tagPrefix = "settings-morning-task-time",
                    onPreset = { time -> store.updateSettings { it.copy(morningTaskReminderTime = time) } },
                )
                TimePresetRow(
                    label = "Noon task time",
                    time = store.settings.noonTaskReminderTime,
                    tagPrefix = "settings-noon-task-time",
                    onPreset = { time -> store.updateSettings { it.copy(noonTaskReminderTime = time) } },
                )
                TimePresetRow(
                    label = "Evening task time",
                    time = store.settings.eveningTaskReminderTime,
                    tagPrefix = "settings-evening-task-time",
                    onPreset = { time -> store.updateSettings { it.copy(eveningTaskReminderTime = time) } },
                )
            }
        }

        item {
            SettingsCard("Defaults") {
                WeekdaySelector(
                    title = "Default blocked days",
                    selected = store.settings.defaultBlockedDays,
                    tagPrefix = "settings-default-blocked-day",
                    onToggle = { day ->
                        store.updateSettings {
                            it.copy(defaultBlockedDays = it.defaultBlockedDays.toggle(day))
                        }
                    },
                )
                ThemePreferenceSelector(
                    selected = store.settings.themePreference,
                    tagPrefix = "settings-theme",
                    onSelect = { theme -> store.updateSettings { it.copy(themePreference = theme) } },
                )
            }
        }

        item {
            SettingsCard("Permissions") {
                PermissionRow(
                    title = "Exact alarms",
                    subtitle = "Required for precise review, task-time, and late-day reminders",
                    granted = exactAlarmGranted,
                    primaryLabel = "Open settings",
                    primaryEnabled = true,
                    tagPrefix = "settings-exact-alarm",
                    onPrimary = {
                        store.markExactAlarmPromptShown()
                        launchExactAlarmSettings(
                            context = context,
                            launch = { intent -> exactAlarmSettingsLauncher.launch(intent) },
                        )
                    },
                    onRefresh = {
                        exactAlarmGranted = hasExactAlarmAccess(context)
                        if (exactAlarmGranted) {
                            store.rescheduleReminders()
                        }
                    },
                )
                Text(
                    text = store.reminderScheduleStatus,
                    modifier = Modifier.testTag("settings-reminder-schedule-status"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exactAlarmGranted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = if (store.settings.exactAlarmPermissionPromptShown) {
                        "Exact alarm settings opened"
                    } else {
                        "Exact alarm settings not opened"
                    },
                    modifier = Modifier.testTag("settings-exact-alarm-prompt-status"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                PermissionRow(
                    title = "Notifications",
                    subtitle = "Required to post reminder notifications on Android 13+",
                    granted = notificationGranted,
                    primaryLabel = "Allow",
                    tagPrefix = "settings-notifications",
                    onPrimary = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationGranted = true
                        }
                    },
                    onRefresh = {
                        notificationGranted = hasNotificationAccess(context)
                        if (notificationGranted) {
                            store.rescheduleReminders()
                        }
                    },
                )
            }
        }

        item {
            SettingsCard("Backup and restore") {
                Text(
                    text = "Format: personal_scheduler_backup_v1_YYYYMMDD-HHMMSS.json",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Last exported: ${store.settings.backupLastExportedAt.ifBlank { "Never" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (store.settings.backupLastVerifiedBytes > 0L) {
                    Text(
                        text = "Last verified size: ${backupByteCountLabel(store.settings.backupLastVerifiedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Auto backup: ${autoBackupSummary(store.settings)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.testTag("settings-backup-button"),
                        onClick = {
                            store.prepareManualBackup { fileName ->
                                launchBackupDocument(
                                    fileName = fileName,
                                    launch = backupLauncher::launch,
                                    onNoDocumentPicker = {
                                        store.cancelPreparedManualBackup()
                                        store.backupStatus = "Backup unavailable: no document picker installed"
                                    },
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Back up now")
                    }
                    OutlinedButton(
                        modifier = Modifier.testTag("settings-restore-button"),
                        onClick = {
                            launchRestoreDocument(
                                launch = { mimeTypes -> restoreLauncher.launch(mimeTypes) },
                                onNoDocumentPicker = {
                                    store.restoreStatus = "Restore unavailable: no document picker installed"
                                },
                            )
                        },
                    ) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Restore backup")
                    }
                }
                HorizontalDivider()
                SwitchRow(
                    title = "Auto app backup",
                    subtitle = if (store.settings.autoBackupFolderUri.isBlank()) {
                        "Choose a folder to enable automatic backups"
                    } else {
                        "Every ${store.settings.autoBackupIntervalDays.dayCountLabel()}"
                    },
                    checked = store.settings.autoBackupEnabled,
                    tagPrefix = "settings-auto-backup",
                    onCheckedChange = { checked ->
                        if (checked && store.settings.autoBackupFolderUri.isBlank()) {
                            store.backupStatus = "Choose a folder for auto backup"
                            launchAutoBackupFolder(
                                launch = { intent -> autoBackupFolderLauncher.launch(intent) },
                                onNoDocumentPicker = {
                                    store.backupStatus = "Auto backup unavailable: no folder picker installed"
                                },
                            )
                        } else {
                            store.updateSettings { it.copy(autoBackupEnabled = checked) }
                        }
                    },
                )
                IntervalControl(
                    title = "Backup interval",
                    interval = store.settings.autoBackupIntervalDays,
                    minimum = 1,
                    tagPrefix = "settings-auto-backup-interval",
                    valueText = { "Every ${it.dayCountLabel()}" },
                    onChange = { days ->
                        store.updateSettings { it.copy(autoBackupIntervalDays = days.coerceAtLeast(1)) }
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.testTag("settings-auto-backup-folder-button"),
                        onClick = {
                            launchAutoBackupFolder(
                                launch = { intent -> autoBackupFolderLauncher.launch(intent) },
                                onNoDocumentPicker = {
                                    store.backupStatus = "Auto backup unavailable: no folder picker installed"
                                },
                            )
                        },
                    ) {
                        Text(if (store.settings.autoBackupFolderUri.isBlank()) "Choose folder" else "Change folder")
                    }
                    if (store.settings.autoBackupFolderUri.isNotBlank()) {
                        OutlinedButton(
                            modifier = Modifier.testTag("settings-auto-backup-run-now-button"),
                            onClick = { store.runAutoBackupNow() },
                        ) {
                            Text("Back up to folder now")
                        }
                    }
                }
                if (pendingRestoreUri != null) {
                    Text(
                        text = RESTORE_REPLACEMENT_WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.testTag("settings-backup-first-button"),
                            onClick = {
                                store.prepareManualBackup { fileName ->
                                    launchBackupDocument(
                                        fileName = fileName,
                                        launch = backupLauncher::launch,
                                        onNoDocumentPicker = {
                                            store.cancelPreparedManualBackup()
                                            store.backupStatus = "Backup unavailable: no document picker installed"
                                        },
                                    )
                                }
                            },
                        ) {
                            Text("Back up first")
                        }
                        Button(
                            modifier = Modifier.testTag("settings-confirm-restore-button"),
                            onClick = {
                                val restoreUri = pendingRestoreUri ?: return@Button
                                pendingRestoreUri = null
                                store.restoreStatus = "Validating restore file..."
                                scope.launch {
                                    val result = backupRepository.restoreFromUri(restoreUri)
                                    if (result.isSuccess) {
                                        store.reloadAfterRestore()
                                    }
                                    store.restoreStatus = result.fold(
                                        onSuccess = { "Restore completed from selected document" },
                                        onFailure = ::restoreFailureStatus,
                                    )
                                }
                            },
                        ) {
                            Text("Confirm restore")
                        }
                        OutlinedButton(
                            modifier = Modifier.testTag("settings-cancel-restore-button"),
                            onClick = {
                                pendingRestoreUri = null
                                store.restoreStatus = "Restore cancelled"
                            },
                        ) {
                            Text("Cancel restore")
                        }
                    }
                }
                Text(
                    text = store.backupStatus,
                    modifier = Modifier.testTag("settings-backup-status"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = store.restoreStatus,
                    modifier = Modifier.testTag("settings-restore-status"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun autoBackupSummary(settings: ReminderSettingsUi): String {
    return when {
        !settings.autoBackupEnabled -> "Off"
        settings.autoBackupFolderUri.isBlank() -> "Needs folder"
        settings.autoBackupLastFailureAt.isNotBlank() &&
            settings.autoBackupLastFailureAt >= settings.autoBackupLastRunAt ->
            "Last attempt failed: ${settings.autoBackupLastFailureReason.ifBlank { "will retry" }}"
        settings.autoBackupLastRunAt.isNotBlank() -> buildString {
            append("Every ${settings.autoBackupIntervalDays.dayCountLabel()}, last ${settings.autoBackupLastRunAt}")
            if (settings.backupLastVerifiedBytes > 0L) {
                append(" (${backupByteCountLabel(settings.backupLastVerifiedBytes)} verified)")
            }
        }
        else -> "Every ${settings.autoBackupIntervalDays.dayCountLabel()}, not run yet"
    }
}

internal const val RESTORE_REPLACEMENT_WARNING =
    "Restore replaces current tasks, history, settings, and reminder settings. Back up first if you need a current copy."

internal fun restoreFailureStatus(error: Throwable): String {
    val message = error.message.orEmpty()
    val category = when {
        message.contains("Unsupported backup schema", ignoreCase = true) -> "unsupported backup version"
        message.contains("missing schema version", ignoreCase = true) -> "invalid backup file"
        message.contains("Backup contains", ignoreCase = true) -> "corrupted backup"
        message.contains("too large", ignoreCase = true) -> "backup file is too large"
        message.contains("Could not open", ignoreCase = true) -> "could not open selected file"
        error is IllegalArgumentException -> "invalid backup file"
        else -> "restore failed"
    }
    return "Restore failed: $category"
}

internal fun launchBackupDocument(
    fileName: String = manualBackupFileName(),
    launch: (String) -> Unit,
    onNoDocumentPicker: () -> Unit,
) {
    try {
        launch(fileName)
    } catch (_: ActivityNotFoundException) {
        onNoDocumentPicker()
    }
}

internal fun launchRestoreDocument(
    launch: (Array<String>) -> Unit,
    onNoDocumentPicker: () -> Unit,
) {
    try {
        launch(arrayOf("application/json", "text/*", "*/*"))
    } catch (_: ActivityNotFoundException) {
        onNoDocumentPicker()
    }
}

internal fun launchAutoBackupFolder(
    launch: (Intent) -> Unit,
    onNoDocumentPicker: () -> Unit,
) {
    try {
        launch(autoBackupFolderIntent())
    } catch (_: ActivityNotFoundException) {
        onNoDocumentPicker()
    }
}

internal fun launchExactAlarmSettings(
    context: Context,
    launch: (Intent) -> Unit,
) {
    try {
        launch(exactAlarmSettingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        launch(appDetailsSettingsIntent(context))
    }
}

internal fun autoBackupFolderIntent(): Intent {
    return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.secondary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = settingsSectionIcon(title),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    }
}

private fun settingsSectionIcon(title: String): ImageVector {
    return when (title) {
        "Day boundary" -> Icons.Filled.Schedule
        "Reminders" -> Icons.Filled.Notifications
        "Defaults" -> Icons.Filled.Tune
        "Permissions" -> Icons.Filled.Security
        else -> Icons.Filled.SettingsBackupRestore
    }
}

@Composable
private fun TimePresetRow(
    label: String,
    time: LocalTime,
    tagPrefix: String,
    onPreset: (LocalTime) -> Unit,
) {
    var showPicker by remember(tagPrefix) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                modifier = Modifier.testTag("$tagPrefix-picker"),
                onClick = { showPicker = true },
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(time.clockLabel())
            }
        }
        if (showPicker) {
            ClockTimePickerDialog(
                time = time,
                tagPrefix = tagPrefix,
                onDismiss = { showPicker = false },
                onTimeSelected = {
                    onPreset(it)
                    showPicker = false
                },
            )
        }
    }
}

@Composable
private fun ClockTimePickerDialog(
    time: LocalTime,
    tagPrefix: String,
    onDismiss: () -> Unit,
    onTimeSelected: (LocalTime) -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-time-confirm"),
                onClick = {
                    onTimeSelected(LocalTime.of(timePickerState.hour, timePickerState.minute))
                },
            ) {
                Text("Set time")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("$tagPrefix-time-cancel"),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
        text = {
            TimePicker(
                state = timePickerState,
                modifier = Modifier.testTag("$tagPrefix-clock-picker"),
            )
        },
    )
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    primaryLabel: String,
    primaryEnabled: Boolean = !granted,
    tagPrefix: String,
    onPrimary: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(if (granted) "Allowed" else "Needed", if (granted) HabitStatus.Completed else HabitStatus.Missed)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.testTag("$tagPrefix-primary"),
                onClick = onPrimary,
                enabled = primaryEnabled,
            ) {
                Icon(
                    imageVector = if (primaryLabel == "Allow") Icons.Filled.Notifications else Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(primaryLabel)
            }
            OutlinedButton(
                modifier = Modifier.testTag("$tagPrefix-refresh"),
                onClick = onRefresh,
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun CompactOccurrenceRow(
    occurrence: HabitOccurrenceUi,
    task: HabitTaskUi,
    onOpen: (() -> Unit)? = null,
    tagPrefix: String? = null,
    currentOperationalDate: LocalDate,
    onSaveNote: ((String) -> Unit)? = null,
    onShift: (() -> Unit)? = null,
    sequenceSwapCandidates: List<HabitOccurrenceUi> = emptyList(),
    onSwitchSequenceItem: ((Int) -> Unit)? = null,
) {
    var noteOpen by remember(occurrence.id) { mutableStateOf(false) }
    val userNote = occurrence.userNote()
    var noteText by remember(occurrence.id, userNote) { mutableStateOf(userNote) }
    var switchOpen by remember(occurrence.id) { mutableStateOf(false) }
    val canShiftMissedSequence = onShift != null &&
        task.type == HabitTaskType.Sequence &&
        occurrence.status == HabitStatus.Missed
    val canSwitchSequenceItem = onSwitchSequenceItem != null &&
        task.type == HabitTaskType.Sequence &&
        occurrence.status == HabitStatus.Pending &&
        occurrence.sequenceItemName != null &&
        sequenceSwapCandidates.isNotEmpty()
    val cardModifier = (tagPrefix?.let { Modifier.testTag(it) } ?: Modifier)
        .then(onOpen?.let { Modifier.clickable(onClick = it) } ?: Modifier)

    Card(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = cardModifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = occurrence.detailSummaryText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OccurrenceStatePill(occurrence, currentOperationalDate)
                    TaskTypeBadge(task.type)
                }
            }
            if (onSaveNote != null || canShiftMissedSequence || canSwitchSequenceItem) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onSaveNote != null) {
                        TextButton(
                            modifier = tagPrefix?.let { Modifier.testTag("$it-note-toggle") } ?: Modifier,
                            onClick = { noteOpen = !noteOpen },
                        ) {
                            Text(if (noteOpen) "Close note" else "Edit note")
                        }
                    }
                    if (canShiftMissedSequence) {
                        OutlinedButton(
                            modifier = tagPrefix?.let { Modifier.testTag("$it-shift") } ?: Modifier,
                            onClick = onShift,
                        ) {
                            Text("Push future")
                        }
                    }
                    if (canSwitchSequenceItem) {
                        OutlinedButton(
                            modifier = tagPrefix?.let { Modifier.testTag("$it-sequence-switch") } ?: Modifier,
                            onClick = { switchOpen = true },
                        ) {
                            Text("Switch item")
                        }
                    }
                }
            }
            if (userNote.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(tagPrefix?.let { Modifier.testTag("$it-note-display") } ?: Modifier),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = userNote,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (noteOpen && onSaveNote != null) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(tagPrefix?.let { Modifier.testTag("$it-note-field") } ?: Modifier),
                    minLines = 2,
                    label = { Text("Occurrence note") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = tagPrefix?.let { Modifier.testTag("$it-note-save") } ?: Modifier,
                        onClick = {
                            onSaveNote(noteText)
                            noteOpen = false
                        },
                    ) {
                        Text("Save note")
                    }
                    TextButton(
                        modifier = tagPrefix?.let { Modifier.testTag("$it-note-cancel") } ?: Modifier,
                        onClick = {
                            noteText = userNote
                            noteOpen = false
                        },
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
    if (switchOpen && onSwitchSequenceItem != null) {
        SequenceItemSwapDialog(
            occurrence = occurrence,
            candidates = sequenceSwapCandidates,
            tagPrefix = tagPrefix?.let { "$it-sequence-switch" } ?: "sequence-switch-${occurrence.id}",
            onSelect = { targetOccurrenceId ->
                onSwitchSequenceItem(targetOccurrenceId)
                switchOpen = false
            },
            onDismiss = { switchOpen = false },
        )
    }
}

@Composable
private fun LogRow(log: HabitLogUi, tagPrefix: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(tagPrefix?.let { Modifier.testTag(it) } ?: Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.action.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = log.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = log.operationalDate.monthDayLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CycleLogRow(log: HabitCycleLogUi, tagPrefix: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(tagPrefix?.let { Modifier.testTag(it) } ?: Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Cycle event",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = log.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = log.operationalDate.monthDayLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChipGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tagPrefix: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            modifier = Modifier.testTag("$tagPrefix-switch"),
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusChip(status: HabitStatus) {
    StatusPill(status.label, status)
}

@Composable
private fun TimeOfDayChip(timeOfDay: TaskTimeOfDay) {
    val (containerColor, contentColor) = when (timeOfDay) {
        TaskTimeOfDay.GENERAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        TaskTimeOfDay.MORNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        TaskTimeOfDay.NOON -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        TaskTimeOfDay.EVENING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = timeOfDay.label(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun OccurrenceStatePill(occurrence: HabitOccurrenceUi, currentOperationalDate: LocalDate) {
    val label = if (
        occurrence.status == HabitStatus.Pending &&
        occurrence.operationalDate.isAfter(currentOperationalDate)
    ) {
        "Upcoming"
    } else {
        occurrence.status.label
    }
    val color = if (label == "Upcoming") themedUpcomingColor() else themedStatusColor(occurrence.status)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusPill(label: String, status: HabitStatus) {
    val color = themedStatusColor(status)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskTypeBadge(type: HabitTaskType) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = type.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun DestinationIcon(destination: AppDestination) {
    Icon(
        imageVector = destinationIcon(destination),
        contentDescription = destination.label,
        modifier = Modifier.size(24.dp),
    )
}

private fun destinationIcon(destination: AppDestination): ImageVector {
    return when (destination) {
        AppDestination.Today -> Icons.Filled.Today
        AppDestination.Calendar -> Icons.Filled.DateRange
        AppDestination.Tasks -> Icons.Filled.CheckCircle
        AppDestination.Stats -> Icons.Filled.BarChart
        AppDestination.Settings -> Icons.Filled.Settings
    }
}

@Composable
private fun EmptyState(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun statusColor(status: HabitStatus): Color {
    return when (status) {
        HabitStatus.Completed -> Color(0xFF2E7D32)
        HabitStatus.Skipped -> Color(0xFFB26A00)
        HabitStatus.Missed -> Color(0xFFC62828)
        HabitStatus.Pending -> Color(0xFF455A64)
        HabitStatus.Shifted -> Color(0xFF6A1B9A)
    }
}

@Composable
private fun themedStatusColor(status: HabitStatus): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (!dark) return statusColor(status)
    return when (status) {
        HabitStatus.Completed -> Color(0xFF78D58B)
        HabitStatus.Skipped -> Color(0xFFFFBE63)
        HabitStatus.Missed -> Color(0xFFFF8A80)
        HabitStatus.Pending -> Color(0xFFB8C4CC)
        HabitStatus.Shifted -> Color(0xFFD6A6F2)
    }
}

@Composable
private fun themedUpcomingColor(): Color {
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF8AB4F8)
    } else {
        Color(0xFF1565C0)
    }
}

@Composable
private fun themedCalendarMarkerColor(marker: CalendarMarker): Color {
    return when (marker.label) {
        "Completed" -> themedStatusColor(HabitStatus.Completed)
        "Skipped" -> themedStatusColor(HabitStatus.Skipped)
        "Missed" -> themedStatusColor(HabitStatus.Missed)
        "Pushed" -> themedStatusColor(HabitStatus.Shifted)
        "Upcoming" -> themedUpcomingColor()
        else -> themedStatusColor(HabitStatus.Pending)
    }
}

private fun screenTitle(destination: AppDestination): String {
    return when (destination) {
        AppDestination.Today -> "Daily Checklist"
        AppDestination.Calendar -> "Monthly Calendar"
        AppDestination.Tasks -> "Task Editor"
        AppDestination.Stats -> "Task Detail"
        AppDestination.Settings -> "Backup & Settings"
    }
}

private fun SkipBlockedDaysBehavior.editorLabel(): String {
    return when (this) {
        SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY -> "Skip that date"
        SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY -> "Move to next allowed day"
        SkipBlockedDaysBehavior.ASK_WHEN_NEEDED -> "Skip until changed"
    }
}

private fun NoActionBehavior.editorLabel(pushable: Boolean): String {
    return when (this) {
        NoActionBehavior.MARK_MISSED -> "Mark missed"
        NoActionBehavior.AUTO_SKIP -> "Auto-skip"
        NoActionBehavior.AUTO_PUSH -> if (pushable) "Auto-push" else "Auto-push and enable push"
    }
}

private fun NoActionBehavior.summaryLabel(): String {
    return when (this) {
        NoActionBehavior.MARK_MISSED -> "No action: missed"
        NoActionBehavior.AUTO_SKIP -> "No action: skip"
        NoActionBehavior.AUTO_PUSH -> "No action: push"
    }
}

private fun CycleRestartBehavior.editorLabel(): String {
    return when (this) {
        CycleRestartBehavior.OFF -> "Off"
        CycleRestartBehavior.SUGGEST_RESTART -> "Suggest restart"
        CycleRestartBehavior.AUTO_RESTART -> "Auto restart"
    }
}

private fun CycleRestartTiming.editorLabel(): String {
    return when (this) {
        CycleRestartTiming.TODAY -> "Today"
        CycleRestartTiming.TOMORROW -> "Tomorrow"
        CycleRestartTiming.NEXT_VALID_DAY -> "Next valid day"
    }
}

private fun cycleThresholdDays(durationDays: Int, thresholdPercent: Int): Int {
    val duration = durationDays.coerceAtLeast(1)
    val threshold = thresholdPercent.coerceIn(1, 100)
    return ((duration * threshold) + 99) / 100
}

private fun longTermRepeatText(interval: Int, unit: LongTermRecurrenceUnit): String {
    val count = interval.coerceAtLeast(1)
    val unitText = when (unit) {
        LongTermRecurrenceUnit.Days -> count.dayCountLabel()
        LongTermRecurrenceUnit.Weeks -> count.weekCountLabel()
        LongTermRecurrenceUnit.Months -> count.monthCountLabel()
        LongTermRecurrenceUnit.Years -> count.yearCountLabel()
    }
    return "Every $unitText"
}

private fun LongTermRecurrenceAnchor.editorLabel(): String {
    return when (this) {
        LongTermRecurrenceAnchor.COMPLETION_DATE -> "Completion date"
        LongTermRecurrenceAnchor.DUE_DATE -> "Due date"
    }
}

private fun TaskTimeOfDay.label(): String {
    return when (this) {
        TaskTimeOfDay.GENERAL -> "General"
        TaskTimeOfDay.MORNING -> "Morning"
        TaskTimeOfDay.NOON -> "Noon"
        TaskTimeOfDay.EVENING -> "Evening"
    }
}

private fun LocalDate.toUtcMillis(): Long {
    return atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}

private fun Long.toUtcLocalDate(): LocalDate {
    return Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
}

private fun HabitStatus.calendarDisplayOrder(): Int {
    return when (this) {
        HabitStatus.Pending -> 0
        HabitStatus.Shifted -> 1
        HabitStatus.Missed -> 2
        HabitStatus.Skipped -> 3
        HabitStatus.Completed -> 4
    }
}

internal data class CalendarMarker(
    val label: String,
    val color: Color,
    val displayOrder: Int,
)

internal fun LocalDate.calendarDayTestTag(): String = "calendar-day-$this"

internal fun CalendarMarker.calendarMarkerTestTag(date: LocalDate): String {
    return "${date.calendarDayTestTag()}-${label.lowercase().replace(" ", "-")}"
}

internal fun calendarDayContentDescription(
    date: LocalDate,
    markers: List<CalendarMarker>,
    itemCount: Int,
    isSelected: Boolean,
    isToday: Boolean,
): String {
    return buildString {
        append(date.fullDateLabel())
        if (isToday) append(", today")
        if (isSelected) append(", selected")
        append(", ")
        append(itemCount)
        append(if (itemCount == 1) " item" else " items")
        append(": ")
        if (markers.isEmpty()) {
            append("no scheduled items")
        } else {
            append(markers.joinToString { it.label })
        }
    }
}

internal fun HabitOccurrenceUi.calendarMarker(date: LocalDate, currentOperationalDate: LocalDate): CalendarMarker {
    return if (status == HabitStatus.Pending && date.isAfter(currentOperationalDate)) {
        CalendarMarker("Upcoming", Color(0xFF1565C0), 0)
    } else {
        CalendarMarker(status.label, statusColor(status), status.calendarDisplayOrder() + 1)
    }
}

internal fun HabitOccurrenceUi.detailSummaryText(): String {
    return buildString {
        append(operationalDate.monthDayLabel())
        sequenceItemName?.let { append(" - ").append(it) }
    }
}

private fun Set<DayOfWeek>.toggle(day: DayOfWeek): Set<DayOfWeek> {
    return if (day in this) this - day else this + day
}

private fun calendarCells(month: YearMonth): List<LocalDate?> {
    val firstDay = month.atDay(1)
    val leadingEmptyCells = firstDay.dayOfWeek.value % 7
    val cells = mutableListOf<LocalDate?>()
    repeat(leadingEmptyCells) { cells.add(null) }
    for (day in 1..month.lengthOfMonth()) {
        cells.add(month.atDay(day))
    }
    while (cells.size % 7 != 0) {
        cells.add(null)
    }
    while (cells.size < 42) {
        cells.add(null)
    }
    return cells
}

internal fun hasExactAlarmAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.canScheduleExactAlarms() == true
    } else {
        true
    }
}

internal fun hasNotificationAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

internal fun exactAlarmSettingsIntent(context: Context): Intent {
    val packageUri = Uri.parse("package:${context.packageName}")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
    } else {
        appDetailsSettingsIntent(context)
    }
}

internal fun appDetailsSettingsIntent(context: Context): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
}
