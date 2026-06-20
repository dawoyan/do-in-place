package com.davoyans.doinplace.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.SavedPlace
import com.davoyans.doinplace.util.MapIntentHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(
    place: SavedPlace,
    onEdit: () -> Unit,
    onDelete: (SavedPlace) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.remove_place)) },
            text = { Text(stringResource(R.string.delete_place_confirm, place.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(place)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(place.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.places), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(place.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (!place.address.isNullOrBlank()) {
                Text("${stringResource(R.string.address)}: ${place.address}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("${stringResource(R.string.place_type_label)}: ${place.placeType.name}", style = MaterialTheme.typography.bodyMedium)
            Text("${stringResource(R.string.detection_radius)}: ${place.radiusMeters} m", style = MaterialTheme.typography.bodyMedium)
            Text("Lat: ${place.latitude}", style = MaterialTheme.typography.bodySmall)
            Text("Lng: ${place.longitude}", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { MapIntentHelper.open(context, place.latitude, place.longitude, place.name, place.address) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Map, contentDescription = null)
                Text(" ${stringResource(R.string.open_in_maps)}")
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Text(" ${stringResource(R.string.edit)}")
            }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" ${stringResource(R.string.delete)}")
            }
        }
    }
}
