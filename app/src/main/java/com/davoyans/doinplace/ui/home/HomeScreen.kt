package com.davoyans.doinplace.ui.home

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.davoyans.doinplace.R
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TrustedContact
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import com.davoyans.doinplace.ui.contacts.iconIdToVector
import com.davoyans.doinplace.ui.settings.PermissionState
import com.davoyans.doinplace.ui.task.formatDue
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.RECURRING_DUE_SOON_DAYS
import com.davoyans.doinplace.util.isRecurringTask
import com.davoyans.doinplace.util.isStandaloneEverywhereTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<Task>,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref> = emptyList(),
    shoppingItemCounts: Map<String, Int> = emptyMap(),
    sharedTaskIds: Set<String> = emptySet(),
    permissionState: PermissionState? = null,
    onTaskClick: (String) -> Unit,
    onArchive: (Task) -> Unit,
    onCancelTask: (Task) -> Unit,
    onUndo: () -> Unit = {},
    showUndo: Boolean = false,
    onRefresh: () -> Unit,
    onCreateTask: () -> Unit,
    onCreateRecurringTask: () -> Unit,
    onFromScreenshot: () -> Unit = {},
    onOpenPlaces: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {}
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    val pending = tasks.filter { it.status == TaskStatus.PENDING_ACCEPTANCE }
    val allActive = tasks.filter { it.status == TaskStatus.ACTIVE }
    val today = LocalDate.now()
    val soonThreshold = today.plusDays(RECURRING_DUE_SOON_DAYS)
    val recurring = allActive
        .filter { it.isRecurringTask() }
        .sortedWith(compareBy<Task>(
            {
                val due = it.activeFromDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
                when {
                    due == null -> 3
                    due.isBefore(today) -> 0
                    !due.isAfter(soonThreshold) -> 1
                    else -> 2
                }
            },
            { it.activeFromDate ?: "9999-99-99" },
            { it.title.lowercase(Locale.getDefault()) }
        ))
    val placeBased = allActive.filter { !it.isEverywhere }
        .sortedWith(compareBy(
            { it.priority.ordinal },
            { it.activeFromDate ?: "9999-99-99" },
            { it.activeStartTime ?: "99:99" },
            { it.createdAt }
        ))
    val everywhere = allActive.filter { it.isStandaloneEverywhereTask() }
        .sortedWith(compareBy(
            { it.activeFromDate ?: "9999-99-99" },
            { it.priority.ordinal }
        ))
    val other = tasks.filter { it.status !in setOf(TaskStatus.ACTIVE, TaskStatus.PENDING_ACCEPTANCE) }
    val anyEverywhereSoon = everywhere.any { task ->
        val d = task.activeFromDate ?: return@any false
        runCatching {
            val ld = LocalDate.parse(d)
            !ld.isAfter(soonThreshold)
        }.getOrElse { false }
    }
    val anyRecurringSoon = recurring.any { task ->
        val d = task.activeFromDate ?: return@any false
        runCatching {
            val ld = LocalDate.parse(d)
            !ld.isAfter(soonThreshold)
        }.getOrElse { false }
    }
    var pendingExpanded by remember { mutableStateOf(pending.isNotEmpty()) }
    var everywhereExpanded by remember { mutableStateOf(anyEverywhereSoon) }
    var recurringExpanded by remember { mutableStateOf(anyRecurringSoon) }

    LaunchedEffect(pending.isNotEmpty()) {
        if (pending.isNotEmpty()) pendingExpanded = true
    }
    LaunchedEffect(anyEverywhereSoon) {
        if (anyEverywhereSoon) everywhereExpanded = true
    }
    LaunchedEffect(anyRecurringSoon) {
        if (anyRecurringSoon) recurringExpanded = true
    }
    LaunchedEffect(recurring.size) {
        DiagLog.d("RECURRING", "group total=${recurring.size}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    if (showUndo) {
                        IconButton(onClick = onUndo) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo last archive",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onOpenPlaces)   { Icon(Icons.Default.Place, stringResource(R.string.places)) }
                    IconButton(onClick = onOpenContacts) { Icon(Icons.Default.People,   "Contacts") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            Column {
                Surface(shadowElevation = 4.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onCreateTask, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.new_reminder_fab), maxLines = 1)
                        }
                        OutlinedButton(onClick = onFromScreenshot, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Image, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Screenshot text", maxLines = 1)
                        }
                    }
                }
                bottomBar()
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    onRefresh()
                    delay(1500)
                    isRefreshing = false
                }
            },
            state = pullState,
            modifier = Modifier.padding(padding)
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (permissionState != null && permissionState.anyMissing) {
                    item {
                        PermissionBanner(permissionState = permissionState, onOpenPermissions = onOpenPermissions)
                    }
                }
                if (tasks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp, bottom = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                                Text(
                                    stringResource(R.string.no_reminders_yet),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    stringResource(R.string.tap_to_create),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
                if (pending.isNotEmpty()) {
                    item {
                        PendingAcceptanceSectionHeader(
                            count = pending.size,
                            expanded = pendingExpanded,
                            onToggle = { pendingExpanded = !pendingExpanded }
                        )
                    }
                    if (pendingExpanded) {
                        items(pending, key = { it.id }) { task ->
                            SwipeableTaskCard(task, currentUserId, contacts, displayPrefs, shoppingItemCounts, sharedTaskIds, onTaskClick, onArchive, onCancelTask)
                        }
                    }
                }
                if (placeBased.isNotEmpty()) {
                    item {
                        Text(
                            "Active reminders (${placeBased.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(placeBased, key = { it.id }) { task ->
                        SwipeableTaskCard(task, currentUserId, contacts, displayPrefs, shoppingItemCounts, sharedTaskIds, onTaskClick, onArchive, onCancelTask)
                    }
                }
                if (other.isNotEmpty()) {
                    item { SectionHeader("Completed / Cancelled") }
                    items(other, key = { it.id }) { task ->
                        SwipeableTaskCard(task, currentUserId, contacts, displayPrefs, shoppingItemCounts, sharedTaskIds, onTaskClick, onArchive, onCancelTask)
                    }
                }
                if (everywhere.isNotEmpty()) {
                    item {
                        EverywhereTaskSectionHeader(
                            count = everywhere.size,
                            expanded = everywhereExpanded,
                            hasSoon = anyEverywhereSoon,
                            onToggle = { everywhereExpanded = !everywhereExpanded }
                        )
                    }
                    if (everywhereExpanded) {
                        items(everywhere, key = { it.id }) { task ->
                            EverywhereTaskCard(task, today, onTaskClick)
                        }
                    }
                }
                item {
                    RecurringTaskSectionHeader(
                        count = recurring.size,
                        expanded = recurringExpanded,
                        hasSoon = anyRecurringSoon,
                        onToggle = { recurringExpanded = !recurringExpanded }
                    )
                }
                if (recurringExpanded) {
                    item {
                        TextButton(
                            onClick = {
                                DiagLog.d("RECURRING", "add start")
                                onCreateRecurringTask()
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                        ) {
                            Text("+ ${stringResource(R.string.add_recurring_task)}")
                        }
                    }
                    if (recurring.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.recurring_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    } else {
                        items(recurring, key = { it.id }) { task ->
                            RecurringTaskCard(task = task, today = today, onClick = onTaskClick)
                        }
                    }
                }
                item {
                    Text(
                        "← Swipe left to archive",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskCard(
    task: Task,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref>,
    shoppingItemCounts: Map<String, Int>,
    sharedTaskIds: Set<String> = emptySet(),
    onTaskClick: (String) -> Unit,
    onArchive: (Task) -> Unit,
    onCancelTask: (Task) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingArchiveTask by remember { mutableStateOf<Task?>(null) }
    val isFinished = task.status in setOf(
        TaskStatus.DONE, TaskStatus.CANCELLED, TaskStatus.REJECTED, TaskStatus.EXPIRED
    )

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { newValue ->
            if (newValue != SwipeToDismissBoxValue.EndToStart) return@rememberSwipeToDismissBoxState true
            if (isFinished) return@rememberSwipeToDismissBoxState true
            pendingArchiveTask = task
            false
        }
    )

    LaunchedEffect(dismissState.currentValue, isFinished, task) {
        if (isFinished && dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onArchive(task)
        }
    }

    // Vibrate when confirm dialog opens for an active task
    LaunchedEffect(pendingArchiveTask) {
        if (pendingArchiveTask != null) {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(220, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(220)
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bgColor by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> Color.Transparent
                },
                label = "swipeBg"
            )
            val iconTint = MaterialTheme.colorScheme.onTertiaryContainer
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bgColor, shape = MaterialTheme.shapes.medium)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Archive, stringResource(R.string.archive), tint = iconTint, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.archive), style = MaterialTheme.typography.labelSmall, color = iconTint)
                }
            }
        }
    ) {
        TaskCard(task, currentUserId, contacts, displayPrefs, onTaskClick,
            shoppingItemCount = shoppingItemCounts[task.id] ?: 0,
            isShared = task.id in sharedTaskIds)
    }

    pendingArchiveTask?.let { archiveTask ->
        ArchiveActiveTaskConfirmDialog(
            taskTitle = archiveTask.title,
            onKeep = {
                pendingArchiveTask = null
                scope.launch { dismissState.reset() }
            },
            onConfirmArchive = {
                pendingArchiveTask = null
                scope.launch { dismissState.reset() }
                onCancelTask(archiveTask)
            }
        )
    }
}

@Composable
private fun EverywhereTaskSectionHeader(
    count: Int,
    expanded: Boolean,
    hasSoon: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.AccessTime,
            contentDescription = null,
            Modifier.size(16.dp),
            tint = if (hasSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
        )
        Text(
            "Time-based tasks ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = if (hasSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
        )
        if (hasSoon) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    "Due soon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun EverywhereTaskCard(
    task: Task,
    today: LocalDate,
    onClick: (String) -> Unit
) {
    val dueDate = task.activeFromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val daysDiff = dueDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }

    val (badgeText, badgeColor) = when {
        dueDate == null -> null to MaterialTheme.colorScheme.outline
        daysDiff != null && daysDiff < 0 -> "Overdue" to MaterialTheme.colorScheme.error
        daysDiff == 0L -> "Today" to MaterialTheme.colorScheme.tertiary
        daysDiff != null && daysDiff <= 7 -> "Due soon" to MaterialTheme.colorScheme.secondary
        else -> null to MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(task.id) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (daysDiff != null && daysDiff < 0)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.AccessTime,
                contentDescription = null,
                Modifier.size(18.dp),
                tint = if (daysDiff != null && daysDiff < 0)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (dueDate != null) {
                    val dueText = formatDue(task.activeFromDate, task.activeStartTime) ?: task.activeFromDate
                    Text(
                        "Due: $dueText",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysDiff != null && daysDiff < 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (task.recurrenceType.name != "NONE") {
                    Text(
                        when (task.recurrenceType.name) {
                            "MONTHLY" -> "Repeats monthly"
                            "YEARLY" -> "Repeats yearly"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                    )
                }
            }
            if (badgeText != null) {
                Surface(
                    color = badgeColor.copy(alpha = 0.18f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecurringTaskSectionHeader(
    count: Int,
    expanded: Boolean,
    hasSoon: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.Autorenew,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (hasSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
        )
        Text(
            "${stringResource(R.string.recurring_tasks)} ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = if (hasSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
        )
        if (hasSoon) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    stringResource(R.string.due_soon),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun RecurringTaskCard(
    task: Task,
    today: LocalDate,
    onClick: (String) -> Unit
) {
    val dueDate = task.activeFromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val daysDiff = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
    val recurrenceSummary = remember(task.recurrenceType, task.recurrenceDayOfMonth, task.recurrenceMonth) {
        when (task.recurrenceType.name) {
            "MONTHLY" -> {
                val day = task.recurrenceDayOfMonth ?: 1
                "Monthly, ${day}${ordinalSuffix(day)}"
            }
            "YEARLY" -> {
                val month = task.recurrenceMonth ?: 1
                val day = task.recurrenceDayOfMonth ?: 1
                val label = runCatching {
                    LocalDate.of(today.year, month, minOf(day, 28))
                        .format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
                }.getOrElse { month.toString() }
                "Yearly, $label $day"
            }
            else -> ""
        }
    }

    val (badgeText, badgeColor) = when {
        dueDate == null -> null to MaterialTheme.colorScheme.outline
        daysDiff != null && daysDiff < 0 -> stringResource(R.string.overdue) to MaterialTheme.colorScheme.error
        daysDiff == 0L -> stringResource(R.string.due_today) to MaterialTheme.colorScheme.tertiary
        daysDiff != null && daysDiff <= RECURRING_DUE_SOON_DAYS -> stringResource(R.string.due_soon) to MaterialTheme.colorScheme.secondary
        else -> null to MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(task.id) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (daysDiff != null && daysDiff < 0) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
            Icons.Default.Autorenew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (daysDiff != null && daysDiff < 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    recurrenceSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                dueDate?.let {
                    Text(
                        "${stringResource(R.string.next_reminder)}: ${formatDue(task.activeFromDate, null) ?: task.activeFromDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysDiff != null && daysDiff < 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            if (badgeText != null) {
                Surface(
                    color = badgeColor.copy(alpha = 0.18f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun ordinalSuffix(day: Int): String = when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

@Composable
private fun PendingAcceptanceSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.Notifications,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Text(
            "Pending assignments ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun TaskCard(
    task: Task,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref>,
    onClick: (String) -> Unit,
    shoppingItemCount: Int = 0,
    isShared: Boolean = false
) {
    fun resolvedName(userId: String): String {
        if (userId == currentUserId) return "You"
        val pref = displayPrefs.find { it.contactUserId == userId }
        val contact = contacts.find { it.contactUserId == userId }
        return ContactDisplayRepository.resolveDisplay(userId, contact, pref).primary
    }

    val isCreator = task.createdByUserId == currentUserId
    val isAssignee = task.assignedToUserId == currentUserId
    val isSelf = isCreator && isAssignee
    val isForFriend = isCreator && !isAssignee
    val isFromFriend = !isCreator && isAssignee
    val creatorName = resolvedName(task.createdByUserId)
    val assigneeName = resolvedName(task.assignedToUserId)

    val (typeIcon, typeLabel) = when {
        isShared && !isCreator -> Icons.Default.Share to "Shared"
        isSelf -> Icons.Default.Person to "Personal"
        isForFriend -> Icons.Default.Send to "Assigned"
        isFromFriend -> Icons.Default.Download to "Received"
        else -> Icons.Default.Person to "Personal"
    }
    val typeColor = when {
        isShared && !isCreator -> MaterialTheme.colorScheme.tertiary
        isForFriend  -> MaterialTheme.colorScheme.primary
        isFromFriend -> MaterialTheme.colorScheme.secondary
        else         -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }

    val contactUserId = when {
        isForFriend  -> task.assignedToUserId
        isFromFriend -> task.createdByUserId
        else         -> null
    }
    val contactPref = contactUserId?.let { uid -> displayPrefs.find { it.contactUserId == uid } }

    val placeDisplay = if (task.placeMode == PlaceMode.TYPE) {
        "${task.placeName} - any matching place"
    } else {
        task.placeName
    }
    val relationshipPrimary = when {
        isShared && !isCreator -> "Shared by $creatorName"
        isPendingTask(task) && isCreator && task.assignedToUserId != currentUserId -> "Assigned to $assigneeName"
        isFromFriend -> if (task.taskType == TaskType.SHOPPING_LIST) {
            "Shopping list from $creatorName"
        } else {
            "Task from $creatorName"
        }
        isForFriend -> "Assigned to $assigneeName"
        else -> null
    }
    val relationshipSecondary = when {
        isPendingTask(task) && isCreator && task.assignedToUserId != currentUserId -> "Waiting for $assigneeName to accept"
        isPendingTask(task) && isAssignee && task.createdByUserId != currentUserId -> "Needs your response"
        else -> null
    }
    val relationshipTertiary = when {
        isPendingTask(task) && isCreator && task.assignedToUserId != currentUserId -> "Needs ${assigneeName}'s response"
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(task.id) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Box(Modifier.fillMaxWidth()) {
            // Subtle background watermark — bottom-right corner, 7% alpha, behind card content
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(68.dp)
                    .align(Alignment.BottomEnd)
                    .alpha(0.07f)
            )
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {

            // Top row: status chip + priority chip + shopping badge + spacer + type chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatusChip(task.status)
                PriorityChip(task.priority)
                if (task.taskType == TaskType.SHOPPING_LIST) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            if (shoppingItemCount > 0) "Shopping · $shoppingItemCount items"
                            else "Shopping List",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Task type chip
                Surface(
                    color = typeColor.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (contactPref != null) {
                            Icon(iconIdToVector(contactPref.iconId), null,
                                Modifier.size(11.dp), tint = typeColor)
                        } else {
                            Icon(typeIcon, null, Modifier.size(11.dp), tint = typeColor)
                        }
                        Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = typeColor)
                    }
                }
            }

            // Title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place, null,
                    tint = taskStatusColor(task.status),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    task.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Place row — shared badge sits inline to avoid overflowing the chip row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 22.dp)
            ) {
                Text(
                    placeDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isShared) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "🔗 Shared",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Due date (if set)
            formatDue(task.activeFromDate, task.activeStartTime)?.let { due ->
                Text(
                    "Due: $due",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
            relationshipPrimary?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
            relationshipSecondary?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
            relationshipTertiary?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
        }
        } // end Box
    }
}

private fun isPendingTask(task: Task): Boolean = task.status == TaskStatus.PENDING_ACCEPTANCE

@Composable
private fun PriorityChip(priority: TaskPriority) {
    val (label, containerColor, contentColor) = when (priority) {
        TaskPriority.URGENT  -> Triple(stringResource(R.string.urgent),  MaterialTheme.colorScheme.errorContainer,   MaterialTheme.colorScheme.onErrorContainer)
        TaskPriority.NO_RUSH -> Triple(stringResource(R.string.no_rush), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Surface(color = containerColor, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusChip(status: TaskStatus) {
    val (label, color) = when (status) {
        TaskStatus.ACTIVE             -> "Active"    to MaterialTheme.colorScheme.secondary
        TaskStatus.PENDING_ACCEPTANCE -> "Pending"   to MaterialTheme.colorScheme.tertiary
        TaskStatus.DONE               -> "Done"      to MaterialTheme.colorScheme.outline
        TaskStatus.CANCELLED          -> "Cancelled" to MaterialTheme.colorScheme.error
        TaskStatus.REJECTED           -> "Rejected"  to MaterialTheme.colorScheme.error
        TaskStatus.EXPIRED            -> "Expired"   to MaterialTheme.colorScheme.outline
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun taskStatusColor(status: TaskStatus) = when (status) {
    TaskStatus.ACTIVE             -> MaterialTheme.colorScheme.primary
    TaskStatus.PENDING_ACCEPTANCE -> MaterialTheme.colorScheme.tertiary
    else                          -> MaterialTheme.colorScheme.outline
}

@Composable
private fun ArchiveActiveTaskConfirmDialog(
    taskTitle: String,
    onKeep: () -> Unit,
    onConfirmArchive: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Archive this task?") },
        text = { Text("\"$taskTitle\" will be moved to archive. You can restore it later.") },
        confirmButton = {
            TextButton(onClick = onConfirmArchive) {
                Text("Archive")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text("Keep") }
        }
    )
}

@Composable
fun PermissionBanner(
    permissionState: PermissionState,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = when {
        !permissionState.hasForegroundLocation ->
            "Location is required for place reminders."
        !permissionState.hasBackgroundLocation ->
            "Background location is not enabled. Reminders may not work when the app is closed."
        !permissionState.hasNotifications ->
            "Notifications are required to show reminders."
        else -> return
    }
    Card(
        onClick = onOpenPermissions,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning, null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Enable permissions →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}
