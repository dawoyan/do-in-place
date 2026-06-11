package com.davoyans.doinplace.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TrustedContact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedTasksScreen(
    archivedTasks: List<Task>,
    currentUserId: String,
    contacts: List<TrustedContact>,
    onRestore: (Task) -> Unit = {},
    onDeletePermanently: (Task) -> Unit = {},
    onClearAll: () -> Unit = {},
    onBack: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingPermDelete by remember { mutableStateOf<Task?>(null) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear archive?") },
            text = { Text("This will permanently delete all archived tasks. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; onClearAll() }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    pendingPermDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingPermDelete = null },
            title = { Text("Delete permanently?") },
            text = { Text("\"${task.title}\" will be deleted permanently. This cannot be restored.") },
            confirmButton = {
                TextButton(onClick = { pendingPermDelete = null; onDeletePermanently(task) }) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archived_tasks)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (archivedTasks.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear all archived")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (archivedTasks.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Archive, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Text(
                        stringResource(R.string.no_archived_tasks),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        stringResource(R.string.swipe_to_archive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(archivedTasks, key = { it.id }) { task ->
                    ArchivedTaskCard(
                        task = task,
                        currentUserId = currentUserId,
                        contacts = contacts,
                        onRestore = { onRestore(task) },
                        onDeletePermanently = { pendingPermDelete = task }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedTaskCard(
    task: Task,
    currentUserId: String,
    contacts: List<TrustedContact>,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit = {}
) {
    val creatorLabel = when {
        task.createdByUserId == currentUserId -> "You"
        else -> contacts.find { it.contactUserId == task.createdByUserId }
            ?.let { it.contactDisplayName.ifBlank { it.contactEmail } }
            ?: "Unknown"
    }
    val assigneeLabel = when {
        task.assignedToUserId == currentUserId -> "You"
        else -> contacts.find { it.contactUserId == task.assignedToUserId }
            ?.let { it.contactDisplayName.ifBlank { it.contactEmail } }
            ?: "Unknown"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        task.status.name.lowercase().replace("_", " ")
                            .replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place, null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (task.placeMode == PlaceMode.TYPE) "${task.placeName} - any matching place"
                    else task.placeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            HorizontalDivider(thickness = 0.5.dp)

            MetaRow("Created by", creatorLabel)
            if (task.assignedToUserId != task.createdByUserId) {
                MetaRow("Assigned to", assigneeLabel)
            }
            MetaRow("Created", formatDate(task.createdAt))
            task.archivedAt?.let { MetaRow("Archived", formatDate(it)) }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Restore, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restore as active")
            }
            TextButton(
                onClick = onDeletePermanently,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Default.Delete, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Delete permanently",
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(ts))
