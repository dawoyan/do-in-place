package com.davoyans.doinplace.ui.task

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.davoyans.doinplace.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.data.model.ChecklistItem
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.ContactStatus
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskEvent
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TrustedContact
import com.davoyans.doinplace.data.model.parseChecklistJson
import com.davoyans.doinplace.data.model.toChecklistJson
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    task: Task,
    events: List<TaskEvent>,
    currentUserId: String,
    contacts: List<TrustedContact> = emptyList(),
    displayPrefs: List<ContactDisplayPref> = emptyList(),
    shoppingItems: List<ShoppingListItem> = emptyList(),
    onShoppingItemChecked: (id: String, checked: Boolean) -> Unit = { _, _ -> },
    onSortList: () -> Unit = {},
    onAutoOrder: () -> Unit = {},
    autoOrderAvailable: Boolean = false,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onReassign: ((newAssigneeId: String) -> Unit)? = null,
    onAccept: (arrivalShare: Boolean) -> Unit,
    onReject: () -> Unit,
    onChecklistItemToggle: (newJson: String) -> Unit = {},
    onBack: () -> Unit
) {
    var showArrivalDialog  by remember { mutableStateOf(false) }
    var showMapFallback    by remember { mutableStateOf(false) }
    var showReassignDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isAssignee = task.assignedToUserId == currentUserId
    val isPending = task.status == TaskStatus.PENDING_ACCEPTANCE
    val isShoppingList = task.taskType == TaskType.SHOPPING_LIST
    val canInteractWithItems = task.status == TaskStatus.ACTIVE || task.status == TaskStatus.PENDING_ACCEPTANCE

    // Derive checklist from stored JSON; re-derive whenever task changes (for SIMPLE tasks)
    val checklistItems: List<ChecklistItem>? = remember(task.checklistJson) {
        if (isShoppingList) null
        else task.checklistJson?.parseChecklistJson()?.takeIf { it.isNotEmpty() }
    }
    val allShoppingDone = isShoppingList && shoppingItems.isNotEmpty() && shoppingItems.all { it.checked }
    val allDone = if (isShoppingList) allShoppingDone
                  else checklistItems?.all { it.done } == true

    if (showArrivalDialog) {
        ArrivalShareDialog(
            creatorName = task.createdByUserId,
            onConfirm = { share -> showArrivalDialog = false; onAccept(share) },
            onDismiss = { showArrivalDialog = false }
        )
    }

    if (showReassignDialog && onReassign != null) {
        ReassignDialog(
            task = task,
            contacts = contacts,
            displayPrefs = displayPrefs,
            currentUserId = currentUserId,
            onDismiss = { showReassignDialog = false },
            onReassign = { newId -> showReassignDialog = false; onReassign(newId) }
        )
    }

    if (showMapFallback) {
        val lat = task.latitude
        val lng = task.longitude
        AlertDialog(
            onDismissRequest = { showMapFallback = false },
            title = { Text(stringResource(R.string.could_not_open_map)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val coords = "$lat, $lng"
                            val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            mgr.setPrimaryClip(ClipData.newPlainText("coordinates", coords))
                            showMapFallback = false
                        }
                    ) { Text(stringResource(R.string.copy_coordinates)) }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val url = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {}
                            showMapFallback = false
                        }
                    ) { Text(stringResource(R.string.open_in_browser)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMapFallback = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Photo
            task.photoUri?.let { path ->
                if (File(path).exists()) {
                    val bitmap = remember(path) {
                        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
                    }
                    bitmap?.let {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            androidx.compose.foundation.Image(
                                bitmap = it,
                                contentDescription = "Task photo",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                    .clip(MaterialTheme.shapes.medium),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // ── Task info card ───────────────────────────────────────────────
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

                    // Shopping list items (SHOPPING_LIST tasks)
                    if (isShoppingList && shoppingItems.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.shopping_list) + " (${shoppingItems.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (canInteractWithItems && !allShoppingDone) {
                                    FilledTonalButton(
                                        onClick = { shoppingItems.forEach { onShoppingItemChecked(it.id, true) } },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(stringResource(R.string.check_all), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (autoOrderAvailable) {
                                    FilledTonalButton(
                                        onClick = onAutoOrder,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(stringResource(R.string.auto_order), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                OutlinedButton(
                                    onClick = onSortList,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.sort_list), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        shoppingItems.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = item.checked,
                                    onCheckedChange = { checked ->
                                        onShoppingItemChecked(item.id, checked)
                                    },
                                    enabled = canInteractWithItems
                                )
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDecoration = if (item.checked) TextDecoration.LineThrough
                                                         else TextDecoration.None
                                    ),
                                    color = if (item.checked)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (allShoppingDone) {
                            Text(
                                "All items checked!",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    } else if (!isShoppingList && checklistItems != null) {
                        Text(
                            "Shopping / To-do list",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        checklistItems.forEachIndexed { index, item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = item.done,
                                    onCheckedChange = { checked ->
                                        val updated = checklistItems.toMutableList()
                                        updated[index] = item.copy(done = checked)
                                        onChecklistItemToggle(updated.toChecklistJson())
                                    },
                                    enabled = canInteractWithItems
                                )
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDecoration = if (item.done) TextDecoration.LineThrough
                                                         else TextDecoration.None
                                    ),
                                    color = if (item.done)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (allDone) {
                            Text(
                                "All items checked!",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    } else if (!task.description.isNullOrBlank()) {
                        Text(task.description, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    }

                    val remindedCount = events.count { it.type == TaskEventType.REMINDED }

                    if (task.placeMode == PlaceMode.TYPE) {
                        DetailRow("Place type", "${task.placeTypeName ?: task.placeName} — any nearby")
                        DetailRow("Search area", "${task.placeName}, ${task.radiusMeters} m radius")
                        if (remindedCount > 0)
                            DetailRow("Notified", "$remindedCount time${if (remindedCount != 1) "s" else ""}")
                        if (task.latitude != 0.0 || task.longitude != 0.0) {
                            val searchQuery = task.placeTypeName?.ifBlank { null } ?: task.placeName
                            TextButton(
                                onClick = {
                                    val url = "https://www.google.com/maps/search/" +
                                        Uri.encode(searchQuery) +
                                        "/@${task.latitude},${task.longitude},15z"
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Map, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Find nearby ${task.placeTypeName ?: "places"}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        DetailRow("Place", task.placeName)
                        DetailRow("Radius", "${task.radiusMeters} m")
                        if (!task.address.isNullOrBlank() && task.address != task.placeName)
                            DetailRow("Address", task.address)
                        if (remindedCount > 0)
                            DetailRow("Notified", "$remindedCount time${if (remindedCount != 1) "s" else ""}")
                        // Open in maps
                        if (task.latitude != 0.0 || task.longitude != 0.0) {
                            TextButton(
                                onClick = {
                                    val lat = task.latitude
                                    val lng = task.longitude
                                    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${task.placeName})")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    val canOpen = try {
                                        intent.resolveActivity(context.packageManager) != null
                                    } catch (_: Exception) { false }
                                    if (canOpen) {
                                        try { context.startActivity(intent) }
                                        catch (_: Exception) { showMapFallback = true }
                                    } else {
                                        showMapFallback = true
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Map, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.open_in_maps), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    DetailRow("Status", task.status.name.replace("_", " ").lowercase()
                        .replaceFirstChar { it.uppercase() })
                    DetailRow("Priority", task.priority.name.lowercase().replaceFirstChar { it.uppercase() })
                    formatDue(task.activeFromDate, task.activeStartTime)?.let { due ->
                        DetailRow("Due", due)
                    }
                    if (task.arrivalShareAllowed) {
                        DetailRow("Arrival sharing", "Enabled")
                    }
                }
            }

            // ── Accept / reject (pending shared tasks assigned to me) ────────
            if (isPending && isAssignee) {
                Text(stringResource(R.string.respond_to_task), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showArrivalDialog = true }, Modifier.weight(1f)) { Text(stringResource(R.string.accept)) }
                    OutlinedButton(onClick = onReject, Modifier.weight(1f)) { Text(stringResource(R.string.reject)) }
                }
            }

            // ── Done / Close / Cancel ────────────────────────────────────────
            val canAct = (task.status == TaskStatus.ACTIVE || (isPending && isAssignee)) &&
                         (isAssignee || task.createdByUserId == currentUserId)
            val isCreator = task.createdByUserId == currentUserId
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    enabled = canAct,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allDone)
                            MaterialTheme.colorScheme.tertiary
                        else
                            androidx.compose.ui.graphics.Color(0xFF2E7D32),
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(if (allDone && canAct) "${stringResource(R.string.done)} ✓" else stringResource(R.string.done))
                }
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.close))
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = canAct,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (canAct) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }

            // ── Reassign (creator only) ──────────────────────────────────────
            if (isCreator && onReassign != null && (task.status == TaskStatus.ACTIVE || isPending)) {
                OutlinedButton(
                    onClick = { showReassignDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reassign task")
                }
            }

            // ── Share completion details ──────────────────────────────────────
            if (task.status == TaskStatus.DONE) {
                OutlinedButton(
                    onClick = {
                        val shareText = buildTaskShareText(
                            task = task,
                            currentUserId = currentUserId,
                            contacts = contacts,
                            displayPrefs = displayPrefs,
                            shoppingItems = shoppingItems,
                            checklistItems = checklistItems,
                            events = events
                        )
                        try {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share task details")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No app available to share.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share details")
                }
            }

            // ── Event history ────────────────────────────────────────────────
            if (events.isNotEmpty()) {
                Text(stringResource(R.string.history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                events.forEach { event ->
                    val (label, detail) = eventDisplayText(event)
                    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            Text(formatTimestamp(event.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        if (detail != null) {
                            Text(detail, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ArrivalShareDialog(
    creatorName: String,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var share by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.arrival_sharing)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Do you want to notify $creatorName when you arrive near this task's place?")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = share, onCheckedChange = { share = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Tell them when I arrive near this place")
                }
                Text("Default: your arrival is private.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(share) }) { Text("Accept task") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReassignDialog(
    task: Task,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onReassign: (String) -> Unit
) {
    val isSelfAssigned = task.assignedToUserId == currentUserId
    val acceptedContacts = contacts.filter {
        it.status == ContactStatus.ACCEPTED && it.contactUserId != task.assignedToUserId
    }

    fun nameFor(c: TrustedContact): String {
        val pref = displayPrefs.find { it.contactUserId == c.contactUserId }
        return ContactDisplayRepository.resolveDisplayName(c.contactUserId, c, pref)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reassign task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Currently assigned to: ${if (isSelfAssigned) "Myself" else task.assignedToUserId.take(6)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (!isSelfAssigned) {
                    OutlinedButton(
                        onClick = { onReassign(currentUserId) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Assign to Myself") }
                }
                if (acceptedContacts.isEmpty() && isSelfAssigned) {
                    Text(
                        "No contacts available. Add trusted contacts first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    acceptedContacts.forEach { contact ->
                        OutlinedButton(
                            onClick = { onReassign(contact.contactUserId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(nameFor(contact)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun formatTimestamp(ts: Long): String {
    val d = java.util.Date(ts)
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(d)
}

/** Returns (primary label, optional detail line) for an event row. */
private fun eventDisplayText(event: TaskEvent): Pair<String, String?> {
    val placeDetail = buildString {
        val name = event.placeName?.takeIf { it.isNotBlank() }
        val addr = event.placeAddress?.takeIf { it.isNotBlank() }
        if (name != null) {
            append(name)
            if (addr != null) append(", $addr")
        }
    }.takeIf { it.isNotBlank() }

    return when (event.type) {
        TaskEventType.REMINDED ->
            "Reminded" to placeDetail?.let { "near $it" }

        TaskEventType.PLACE_REMINDER_AUTO_DISMISSED ->
            "Notification removed" to (placeDetail?.let { "left $it" } ?: "Left the place")

        TaskEventType.DUE_REMINDER_SHOWN -> {
            val label = "Due reminder" + (event.reason?.let { " — $it" } ?: "")
            label to placeDetail
        }

        TaskEventType.NOTIFICATION_OPENED ->
            "Notification opened" to null

        else -> {
            val label = event.type.name.lowercase().replace("_", " ")
                .replaceFirstChar { it.uppercase() }
            label to null
        }
    }
}

fun formatDue(date: String?, time: String?): String? {
    if (date.isNullOrBlank()) return null
    val displayDate = runCatching {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val d = sdf.parse(date)!!
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(d)
    }.getOrElse { date }
    return if (!time.isNullOrBlank()) "$displayDate at $time" else displayDate
}

fun buildTaskShareText(
    task: Task,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref>,
    shoppingItems: List<ShoppingListItem>,
    checklistItems: List<ChecklistItem>?,
    events: List<TaskEvent>
): String {
    val sb = StringBuilder()
    sb.appendLine("Task: ${task.title}")
    sb.appendLine("Status: Done")
    val doneEvent = events.firstOrNull { it.type == TaskEventType.DONE }
    val completedAt = doneEvent?.createdAt ?: task.updatedAt
    sb.appendLine("Completed: ${formatShareTimestamp(completedAt)}")
    buildSharePlaceText(task, events)?.let { (label, value) ->
        sb.appendLine("$label: $value")
    }
    if (task.assignedToUserId != task.createdByUserId) {
        resolveNameForShare(task.assignedToUserId, currentUserId, contacts, displayPrefs)
            ?.let { sb.appendLine("Assigned to: $it") }
        resolveNameForShare(task.createdByUserId, currentUserId, contacts, displayPrefs)
            ?.let { sb.appendLine("Created by: $it") }
    }
    if (!task.description.isNullOrBlank() && checklistItems == null && task.taskType == TaskType.SIMPLE) {
        sb.appendLine()
        sb.appendLine("Details:")
        sb.append(task.description.trim())
    }
    if (task.taskType == TaskType.SHOPPING_LIST && shoppingItems.isNotEmpty()) {
        val bought = shoppingItems.filter { it.checked }
        val remaining = shoppingItems.filter { !it.checked }
        if (bought.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("*կատարված*")
            bought.forEach { sb.appendLine("✅ ${it.text}") }
        }
        if (remaining.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("*անկատար*")
            remaining.forEach { sb.appendLine("◻ ${it.text}") }
        }
    }
    if (checklistItems != null && checklistItems.isNotEmpty()) {
        val done = checklistItems.filter { it.done }
        val remaining = checklistItems.filter { !it.done }
        if (done.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("*կատարված*")
            done.forEach { sb.appendLine("✅ ${it.text}") }
        }
        if (remaining.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("*անկատար*")
            remaining.forEach { sb.appendLine("◻ ${it.text}") }
        }
    }
    sb.appendLine()
    sb.append("Shared from Do In Place")
    return sb.toString().trim()
}

private fun buildSharePlaceText(task: Task, events: List<TaskEvent>): Pair<String, String>? {
    return when (task.placeMode) {
        PlaceMode.EXACT -> {
            val name = task.placeName.takeIf { it.isNotBlank() } ?: return null
            val addr = task.address?.takeIf { it.isNotBlank() && it != name }
            "Place" to if (addr != null) "$name, $addr" else name
        }
        PlaceMode.TYPE -> {
            val arrivedEvent = events.firstOrNull {
                it.type == TaskEventType.ARRIVED_NEAR_PLACE && !it.placeName.isNullOrBlank()
            }
            if (arrivedEvent != null) {
                val typeName = task.placeTypeName?.takeIf { it.isNotBlank() }
                val matchedName = arrivedEvent.placeName!!
                val matchedAddr = arrivedEvent.placeAddress?.takeIf { it.isNotBlank() && it != matchedName }
                val matchedText = if (matchedAddr != null) "$matchedName, $matchedAddr" else matchedName
                val placeValue = if (typeName != null && typeName != matchedName)
                    "$typeName - $matchedText" else matchedText
                "Place" to placeValue
            } else {
                val typeName = task.placeTypeName?.takeIf { it.isNotBlank() }
                    ?: task.placeName.takeIf { it.isNotBlank() }
                    ?: return null
                "Place type" to typeName
            }
        }
    }
}

private fun resolveNameForShare(
    userId: String,
    currentUserId: String,
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref>
): String? {
    if (userId.isBlank()) return null
    if (userId == currentUserId) return "Me"
    val contact = contacts.find { it.contactUserId == userId } ?: return null
    val pref = displayPrefs.find { it.contactUserId == userId }
    return ContactDisplayRepository.resolveDisplayName(userId, contact, pref).takeIf { it.isNotBlank() }
}

private fun formatShareTimestamp(ts: Long): String {
    val date = java.util.Date(ts)
    return java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(date)
}
