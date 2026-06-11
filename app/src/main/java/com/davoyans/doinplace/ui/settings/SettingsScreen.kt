package com.davoyans.doinplace.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.davoyans.doinplace.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.davoyans.doinplace.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val LANGUAGE_OPTIONS = listOf(
    "system" to null,   // label resolved via stringResource at call site
    "hy" to "Հայերեն",
    "ru" to "Русский",
    "en" to "English"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    locationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    notificationGranted: Boolean,
    activityRecognitionGranted: Boolean,
    onRequestForegroundLocation: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    activeTaskCount: Int,
    notifyOnFriendCancelMyTask: Boolean,
    onNotifyOnFriendCancelMyTaskChanged: (Boolean) -> Unit,
    smartRemindersEnabled: Boolean,
    onSmartRemindersEnabledChanged: (Boolean) -> Unit,
    suppressAtHome: Boolean,
    onSuppressAtHomeChanged: (Boolean) -> Unit,
    suppressAtNight: Boolean,
    onSuppressAtNightChanged: (Boolean) -> Unit,
    onClearReminderOutcomes: () -> Unit,
    localLearningEnabled: Boolean,
    onLocalLearningEnabledChanged: (Boolean) -> Unit,
    onClearLocalLearning: () -> Unit,
    lastSyncInfo: String,
    archivedCount: Int,
    debugInfo: String,
    selectedLanguage: String = "",
    onLanguageChanged: (String) -> Unit = {},
    onOpenArchive: () -> Unit,
    onDeleteLocalData: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showClearLearningDialog by remember { mutableStateOf(false) }
    var showDeleteLocalDataDialog by remember { mutableStateOf(false) }

    if (showDeleteLocalDataDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteLocalDataDialog = false },
            title = { Text("Delete local data?") },
            text = { Text("All tasks, places, contacts and learning data saved on this device will be removed. You will stay logged in. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteLocalDataDialog = false; onDeleteLocalData() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLocalDataDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    fun copyDebugInfo() {
        val clip = android.content.ClipData.newPlainText("Do In Place Debug", debugInfo)
        val mgr = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        mgr.setPrimaryClip(clip)
    }

    if (showClearLearningDialog) {
        AlertDialog(
            onDismissRequest = { showClearLearningDialog = false },
            title = { Text(stringResource(R.string.clear_task_learning_title)) },
            text = { Text("The app will forget your local task priority suggestions. Your tasks will not be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = { onClearLocalLearning(); showClearLearningDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearLearningDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Permissions ───────────────────────────────────────────────────
            SectionTitle(stringResource(R.string.permissions))
            Text(
                "Tap any row to open system settings where you can toggle the permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            PermissionRow(
                name = "Notifications",
                granted = notificationGranted,
                note = "Required to show reminders",
                actionLabel = "Open settings",
                onClick = ::openNotificationSettings
            )
            PermissionRow(
                name = "Location (foreground)",
                granted = locationGranted,
                note = "Needed to detect when you arrive near a place",
                actionLabel = "Request permission",
                onClick = onRequestForegroundLocation
            )

            val bgNote = when {
                !locationGranted ->
                    "Grant foreground location first, then enable background location"
                activeTaskCount == 0 ->
                    "No active reminders — background location is currently idle (no battery cost). " +
                    "You can safely set this to \"While using the app\" until you create tasks."
                else ->
                    "Monitoring $activeTaskCount active place${if (activeTaskCount == 1) "" else "s"}. " +
                    "Uses cell towers / Wi-Fi — not GPS — so battery impact is low."
            }
            PermissionRow(
                name = "Background location",
                granted = backgroundLocationGranted,
                note = if (backgroundLocationGranted && activeTaskCount == 0)
                    "Granted — but idle right now (no active tasks)"
                else
                    bgNote,
                grantedNote = if (activeTaskCount == 0)
                    "Idle — no active reminders"
                else
                    "Active — monitoring $activeTaskCount place${if (activeTaskCount == 1) "" else "s"}",
                actionLabel = "Open settings",
                onClick = ::openAppSettings
            )
            if (!backgroundLocationGranted) {
                Text(
                    "For place-based reminders to work when the app is closed, enable Always allow location access in Android settings.\n\n" +
                        "How to enable background location:\n" +
                        "1. Open Android Settings.\n" +
                        "2. Open Apps.\n" +
                        "3. Select Do In Place.\n" +
                        "4. Open Permissions.\n" +
                        "5. Open Location.\n" +
                        "6. Choose Allow all the time / Always allow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 4.dp)
                )
            }
            if (backgroundLocationGranted) {
                Text(
                    "⚡ Battery tip: background location uses cell towers, not GPS. " +
                    if (activeTaskCount == 0)
                        "With no active tasks it consumes nothing."
                    else
                        "Battery drain is low. It increases slightly with more active reminders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 4.dp)
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PermissionRow(
                    name = "Activity recognition",
                    granted = activityRecognitionGranted,
                    note = "Helps Do In Place understand whether you are walking, driving, or staying near a place so smart reminders can be more accurate.",
                    grantedNote = "Granted - smarter movement detection enabled",
                    actionLabel = if (activityRecognitionGranted) "Open settings" else "Request permission",
                    onClick = if (activityRecognitionGranted) ::openAppSettings else onRequestActivityRecognition
                )
            }

            // ── Notification preferences ─────────────────────────────────────
            HorizontalDivider()
            SectionTitle(stringResource(R.string.notifications))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Notifications, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Notify me when a friend cancels or rejects my task",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Get a notification when someone cancels or rejects a task you created for them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = notifyOnFriendCancelMyTask,
                        onCheckedChange = onNotifyOnFriendCancelMyTaskChanged
                    )
                }
            }

            // ── Smart reminders ───────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle("Smart Reminders")
            Text(
                "Suppress unhelpful reminders based on your activity, time of day, and location context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            SmartToggleRow(
                label    = "Smart reminders",
                detail   = "Route notifications through context-aware engine",
                checked  = smartRemindersEnabled,
                onChange = onSmartRemindersEnabledChanged
            )
            if (smartRemindersEnabled) {
                SmartToggleRow(
                    label    = "Suppress Easy reminders at home",
                    detail   = "Non-urgent outside-place reminders are silenced when you're home",
                    checked  = suppressAtHome,
                    onChange = onSuppressAtHomeChanged
                )
                SmartToggleRow(
                    label    = "Suppress Easy reminders at night",
                    detail   = "Non-urgent reminders are silenced 22:00–08:00 unless due soon",
                    checked  = suppressAtNight,
                    onChange = onSuppressAtNightChanged
                )
                OutlinedButton(
                    onClick  = onClearReminderOutcomes,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear learned reminder behavior")
                }
            }

            // ── Local learning ────────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle("Task Learning")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Use local task learning",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Suggest task priority from your own past tasks on this phone. This learning stays on your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = localLearningEnabled,
                        onCheckedChange = onLocalLearningEnabledChanged
                    )
                }
            }
            OutlinedButton(
                onClick = { showClearLearningDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clear_task_learning_btn))
            }

            // ── Sync ─────────────────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle("Sync")
            Text(
                lastSyncInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            // ── Language ──────────────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle(stringResource(R.string.language))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Language, null,
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    val systemLabel = stringResource(R.string.system_default)
                    LANGUAGE_OPTIONS.forEach { (tag, staticLabel) ->
                        val label = staticLabel ?: systemLabel
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == tag,
                                onClick = { onLanguageChanged(tag) }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            // ── Archive ───────────────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle("Archive")
            Card(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Archive, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.archived_tasks), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Text(
                            if (archivedCount == 0) "No archived tasks"
                            else "$archivedCount task${if (archivedCount == 1) "" else "s"} archived",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // ── Debug ─────────────────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle("Debug")
            OutlinedButton(onClick = ::copyDebugInfo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.copy_debug_info))
            }
            Text(
                debugInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )

            // ── Privacy ───────────────────────────────────────────────────────
            HorizontalDivider()
            SectionTitle("Privacy")
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.local_learning_desc), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.no_live_location), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.local_learning_device), style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = { showDeleteLocalDataDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.delete_local_data), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onLogout, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text(stringResource(R.string.log_out)) }

            // ── App version ───────────────────────────────────────────────────
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  ·  Built ${BuildConfig.BUILD_TIME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SmartToggleRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    note: String,
    onClick: () -> Unit,
    grantedNote: String = "Granted ✓",
    actionLabel: String = "Open settings"
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        )
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    if (granted) grantedNote else note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error
                )
            }
            if (!granted) {
                TextButton(onClick = onClick) { Text(actionLabel) }
            }
        }
    }
}
