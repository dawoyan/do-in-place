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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<Task>,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref> = emptyList(),
    shoppingItemCounts: Map<String, Int> = emptyMap(),
    permissionState: PermissionState? = null,
    onTaskClick: (String) -> Unit,
    onArchive: (Task) -> Unit,
    onCancelTask: (Task) -> Unit,
    onUndo: () -> Unit = {},
    showUndo: Boolean = false,
    onRefresh: () -> Unit,
    onCreateTask: () -> Unit,
    onFromScreenshot: () -> Unit = {},
    onOpenPlaces: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit = {}
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

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
                    IconButton(onClick = onOpenPlaces)   { Icon(Icons.Default.Place,    "Saved places") }
                    IconButton(onClick = onOpenContacts) { Icon(Icons.Default.People,   "Contacts") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
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
        if (tasks.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                if (permissionState != null && permissionState.anyMissing) {
                    PermissionBanner(
                        permissionState = permissionState,
                        onOpenPermissions = onOpenPermissions,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Place, null, modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        Text(stringResource(R.string.no_reminders_yet), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text(stringResource(R.string.tap_to_create),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            }
        } else {
            val pending = tasks.filter { it.status == TaskStatus.PENDING_ACCEPTANCE }
            val active  = tasks
                .filter { it.status == TaskStatus.ACTIVE }
                .sortedWith(compareBy(
                    { it.priority.ordinal },
                    { it.activeFromDate ?: "9999-99-99" },
                    { it.activeStartTime ?: "99:99" },
                    { it.createdAt }
                ))
            val other   = tasks.filter { it.status !in setOf(TaskStatus.ACTIVE, TaskStatus.PENDING_ACCEPTANCE) }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (permissionState != null && permissionState.anyMissing) {
                    item {
                        PermissionBanner(permissionState = permissionState, onOpenPermissions = onOpenPermissions)
                    }
                }
                if (pending.isNotEmpty()) {
                    item { SectionHeader("Pending acceptance (${pending.size})") }
                    items(pending, key = { it.id }) { task ->
                        SwipeableTaskCard(task, currentUserId, contacts, displayPrefs, shoppingItemCounts, onTaskClick, onArchive, onCancelTask)
                    }
                }
                if (active.isNotEmpty()) {
                    item {
                        Text(
                            "Active reminders (${active.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(active, key = { it.id }) { task ->
                        SwipeableTaskCard(task, currentUserId, contacts, displayPrefs, shoppingItemCounts, onTaskClick, onArchive, onCancelTask)
                    }
                }
                if (other.isNotEmpty()) {
                    item { SectionHeader("Completed / Cancelled") }
                    items(other, key = { it.id }) { task ->
                        SwipeableTaskCard(task, currentUserId, contacts, displayPrefs, shoppingItemCounts, onTaskClick, onArchive, onCancelTask)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskCard(
    task: Task,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref>,
    shoppingItemCounts: Map<String, Int>,
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
            shoppingItemCount = shoppingItemCounts[task.id] ?: 0)
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
    shoppingItemCount: Int = 0
) {
    fun resolvedName(userId: String): String {
        if (userId == currentUserId) return "You"
        val pref = displayPrefs.find { it.contactUserId == userId }
        val contact = contacts.find { it.contactUserId == userId }
        return ContactDisplayRepository.resolveDisplayName(userId, contact, pref)
    }

    val isSelf = task.createdByUserId == currentUserId && task.assignedToUserId == currentUserId
    val isForFriend = task.createdByUserId == currentUserId && task.assignedToUserId != currentUserId
    val isFromFriend = task.createdByUserId != currentUserId && task.assignedToUserId == currentUserId

    val (typeIcon, typeLabel) = when {
        isSelf      -> Icons.Default.Person  to "Self Task"
        isForFriend -> Icons.Default.Send    to "For ${resolvedName(task.assignedToUserId)}"
        isFromFriend -> Icons.Default.Download to "From ${resolvedName(task.createdByUserId)}"
        else        -> Icons.Default.Person  to "Self Task"
    }
    val typeColor = when {
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

            // Place row
            Text(
                placeDisplay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 22.dp)
            )

            // Due date (if set)
            formatDue(task.activeFromDate, task.activeStartTime)?.let { due ->
                Text(
                    "Due: $due",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
        }
        } // end Box
    }
}

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
