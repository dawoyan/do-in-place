package com.davoyans.doinplace.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.RecurrenceType
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.canSaveRecurringTask
import com.davoyans.doinplace.util.isRecurringTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecurringTaskScreen(
    currentUserId: String,
    editingTask: Task? = null,
    onSave: (Task) -> Unit,
    onBack: () -> Unit
) {
    val isEditing = editingTask != null
    val context = LocalContext.current
    val initialTitle = editingTask?.title.orEmpty()
    val initialNote = editingTask?.description.orEmpty()
    val initialRecurrence = editingTask?.recurrenceType?.takeIf { it != RecurrenceType.NONE }
        ?: RecurrenceType.MONTHLY
    val initialDayOfMonth = editingTask?.recurrenceDayOfMonth?.toString().orEmpty()
    val initialMonth = editingTask?.recurrenceMonth?.toString().orEmpty()
    val initialDay = editingTask?.recurrenceDayOfMonth?.toString().orEmpty()
    val anchorDate = remember(editingTask?.activeFromDate) {
        editingTask?.activeFromDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
    }

    var title by remember { mutableStateOf(initialTitle) }
    var note by remember { mutableStateOf(initialNote) }
    var recurrenceType by remember { mutableStateOf(initialRecurrence) }
    var dayOfMonthText by remember { mutableStateOf(initialDayOfMonth) }
    var monthText by remember { mutableStateOf(initialMonth) }
    var dayText by remember { mutableStateOf(initialDay) }
    var showDiscardChangesDialog by remember { mutableStateOf(false) }

    val dayOfMonth = dayOfMonthText.toIntOrNull()
    val month = monthText.toIntOrNull()
    val day = dayText.toIntOrNull()

    val nextReminder = remember(recurrenceType, dayOfMonth, month, day, anchorDate) {
        when (recurrenceType) {
            RecurrenceType.MONTHLY ->
                dayOfMonth?.let { com.davoyans.doinplace.util.RecurrenceCalculator.firstMonthlyOccurrence(it, anchorDate) }
            RecurrenceType.YEARLY ->
                if (month != null && day != null) {
                    com.davoyans.doinplace.util.RecurrenceCalculator.firstYearlyOccurrence(month, day, anchorDate)
                } else null
            RecurrenceType.NONE -> null
        }
    }

    val canSave = canSaveRecurringTask(
        title = title,
        note = note,
        recurrenceType = recurrenceType,
        dayOfMonth = dayOfMonth,
        month = month,
        day = day
    ) && nextReminder != null

    val hasUnsavedChanges by remember(
        title,
        note,
        recurrenceType,
        dayOfMonthText,
        monthText,
        dayText
    ) {
        derivedStateOf {
            title != initialTitle ||
                note != initialNote ||
                recurrenceType != initialRecurrence ||
                dayOfMonthText != initialDayOfMonth ||
                monthText != initialMonth ||
                dayText != initialDay
        }
    }

    fun requestBack() {
        if (hasUnsavedChanges) {
            showDiscardChangesDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(onBack = ::requestBack)

    LaunchedEffect(isEditing, editingTask) {
        if (isEditing && editingTask?.isRecurringTask() == true) {
            DiagLog.d("RECURRING", "edit id=${editingTask.id.take(8)}")
        }
    }

    if (showDiscardChangesDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardChangesDialog = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardChangesDialog = false
                    onBack()
                }) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardChangesDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.edit_recurring_task_title
                            else R.string.add_recurring_task_title
                        )
                    )
                },
                navigationIcon = {
                    TextButton(onClick = ::requestBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.title_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Text(
                stringResource(R.string.recurrence_type),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = recurrenceType == RecurrenceType.MONTHLY,
                    onClick = { recurrenceType = RecurrenceType.MONTHLY },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) {
                    Text(stringResource(R.string.recurrence_monthly))
                }
                SegmentedButton(
                    selected = recurrenceType == RecurrenceType.YEARLY,
                    onClick = { recurrenceType = RecurrenceType.YEARLY },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) {
                    Text(stringResource(R.string.recurrence_yearly))
                }
            }

            if (recurrenceType == RecurrenceType.MONTHLY) {
                OutlinedTextField(
                    value = dayOfMonthText,
                    onValueChange = { dayOfMonthText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.day_of_month)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = monthText,
                        onValueChange = { monthText = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.month)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.day)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }

            nextReminder?.let { nextDate ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "${stringResource(R.string.next_reminder)}: ${formatRecurringDate(nextDate)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Text(
                            stringResource(R.string.recurring_task_note),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.time_based_tasks)) },
                        colors = AssistChipDefaults.assistChipColors()
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val normalizedTitle = title.trim().ifBlank {
                        note.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: ""
                    }
                    val dueDate = nextReminder ?: return@Button
                    val recurringPlaceName = context.getString(R.string.recurring_tasks)
                    val updatedTask = editingTask?.copy(
                        title = normalizedTitle,
                        description = note.trim().ifBlank { null },
                        placeId = null,
                        placeName = recurringPlaceName,
                        address = null,
                        latitude = 0.0,
                        longitude = 0.0,
                        radiusMeters = 0,
                        status = TaskStatus.ACTIVE,
                        activeFromDate = dueDate.toString(),
                        activeStartTime = null,
                        archived = false,
                        placeMode = PlaceMode.EXACT,
                        placeTypeId = null,
                        placeTypeName = null,
                        taskType = TaskType.SIMPLE,
                        isEverywhere = true,
                        recurrenceType = recurrenceType,
                        recurrenceDayOfMonth = if (recurrenceType == RecurrenceType.MONTHLY) dayOfMonth else day,
                        recurrenceMonth = if (recurrenceType == RecurrenceType.YEARLY) month else null,
                        updatedAt = now,
                        pendingSync = false
                    ) ?: Task(
                        id = UUID.randomUUID().toString(),
                        title = normalizedTitle,
                        description = note.trim().ifBlank { null },
                        createdByUserId = currentUserId,
                        assignedToUserId = currentUserId,
                        placeId = null,
                        placeName = recurringPlaceName,
                        address = null,
                        latitude = 0.0,
                        longitude = 0.0,
                        radiusMeters = 0,
                        status = TaskStatus.ACTIVE,
                        priority = TaskPriority.NO_RUSH,
                        activeFromDate = dueDate.toString(),
                        activeStartTime = null,
                        archived = false,
                        placeMode = PlaceMode.EXACT,
                        placeTypeId = null,
                        placeTypeName = null,
                        taskType = TaskType.SIMPLE,
                        isEverywhere = true,
                        recurrenceType = recurrenceType,
                        recurrenceDayOfMonth = if (recurrenceType == RecurrenceType.MONTHLY) dayOfMonth else day,
                        recurrenceMonth = if (recurrenceType == RecurrenceType.YEARLY) month else null
                    )

                    if (updatedTask.recurrenceType == RecurrenceType.MONTHLY) {
                        DiagLog.d(
                            "RECURRING",
                            "save id=${updatedTask.id.take(8)} type=MONTHLY day=${updatedTask.recurrenceDayOfMonth}"
                        )
                    } else {
                        DiagLog.d(
                            "RECURRING",
                            "save id=${updatedTask.id.take(8)} type=YEARLY month=${updatedTask.recurrenceMonth} day=${updatedTask.recurrenceDayOfMonth}"
                        )
                    }
                    DiagLog.d("RECURRING", "nextDueDate id=${updatedTask.id.take(8)} date=${dueDate}")
                    DiagLog.d("RECURRING", "no location used id=${updatedTask.id.take(8)}")
                    onSave(updatedTask)
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

private fun formatRecurringDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
