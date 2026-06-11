package com.davoyans.doinplace.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R

data class PermissionState(
    val hasForegroundLocation: Boolean,
    val hasBackgroundLocation: Boolean,
    val hasNotifications: Boolean,
    val hasActivityRecognition: Boolean
) {
    val allGranted get() = hasForegroundLocation && hasBackgroundLocation && hasNotifications
    val anyMissing get() = !allGranted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    permissionState: PermissionState,
    onRequestNotification: () -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    fun openAppSettings() {
        context.startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
                }
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
            Text(
                "Do In Place uses location only for your accepted place reminders. " +
                "It does not show your live location to friends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            PermissionCard(
                title = "Notifications",
                description = "Required to show reminders when you arrive near a saved place.",
                granted = permissionState.hasNotifications,
                actionLabel = if (Build.VERSION.SDK_INT >= 33) "Enable notifications" else "Open notification settings",
                actionEnabled = !permissionState.hasNotifications,
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33) onRequestNotification()
                    else openAppSettings()
                }
            )

            PermissionCard(
                title = "Location (foreground)",
                description = "Needed to detect when you arrive near a place.",
                granted = permissionState.hasForegroundLocation,
                actionLabel = "Enable location",
                actionEnabled = !permissionState.hasForegroundLocation,
                onAction = onRequestForegroundLocation,
                permanentlyDeniedAction = "Open app settings",
                onPermanentlyDenied = ::openAppSettings
            )

            PermissionCard(
                title = "Background location",
                description = "Required for reminders when the app is closed. " +
                    "Android may require you to set \"Allow all the time\" in system settings.",
                granted = permissionState.hasBackgroundLocation,
                actionLabel = "Open location settings",
                actionEnabled = permissionState.hasForegroundLocation && !permissionState.hasBackgroundLocation,
                disabledReason = if (!permissionState.hasForegroundLocation) "Enable foreground location first." else null,
                onAction = ::openAppSettings
            )

            if (!permissionState.hasBackgroundLocation) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "For place-based reminders to work when the app is closed, enable Always allow location access in Android settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            "How to enable background location:\n" +
                                "1. Open Android Settings.\n" +
                                "2. Open Apps.\n" +
                                "3. Select Do In Place.\n" +
                                "4. Open Permissions.\n" +
                                "5. Open Location.\n" +
                                "6. Choose Allow all the time / Always allow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        OutlinedButton(onClick = ::openAppSettings) {
                            Text("Open app settings")
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PermissionCard(
                    title = "Activity recognition",
                    description = "Activity permission helps Do In Place understand movement context, such as whether you are walking, driving, or staying near a place. This improves background place-based task behavior and reduces unnecessary reminders.",
                    granted = permissionState.hasActivityRecognition,
                    actionLabel = "Enable activity permission",
                    actionEnabled = !permissionState.hasActivityRecognition,
                    onAction = onRequestActivityRecognition,
                    permanentlyDeniedAction = "Open app settings",
                    onPermanentlyDenied = ::openAppSettings
                )
            }

            if (permissionState.allGranted &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || permissionState.hasActivityRecognition)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "All permissions granted. Place reminders are fully set up.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            } else if (permissionState.allGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Required permissions are granted. Activity recognition is still recommended for smarter movement detection.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    disabledReason: String? = null,
    permanentlyDeniedAction: String? = null,
    onPermanentlyDenied: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    if (granted) "Enabled" else "Not enabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (!granted) {
                if (disabledReason != null) {
                    Text(
                        disabledReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actionEnabled) {
                        OutlinedButton(onClick = onAction) { Text(actionLabel) }
                    }
                    if (permanentlyDeniedAction != null && onPermanentlyDenied != null) {
                        TextButton(onClick = onPermanentlyDenied) { Text(permanentlyDeniedAction) }
                    }
                }
            }
        }
    }
}
