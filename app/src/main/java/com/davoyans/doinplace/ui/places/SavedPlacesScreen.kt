package com.davoyans.doinplace.ui.places

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.SavedPlace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesScreen(
    places: List<SavedPlace>,
    onDeletePlace: (SavedPlace) -> Unit,
    onAddPlace: () -> Unit,
    onRefresh: () -> Unit = {},
    onBack: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SavedPlace?>(null) }
    var isRefreshing  by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    pendingDelete?.let { place ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.remove_place)) },
            text = {
                Text("\"${place.name}\" may be used by active reminders.\n\nWhat would you like to do?")
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDeletePlace(place); pendingDelete = null }) {
                        Text("Remove from saved places only")
                    }
                    TextButton(onClick = { onDeletePlace(place); pendingDelete = null }) {
                        Text("Cancel related reminders too", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep place") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.saved_places)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
                actions = { TextButton(onClick = onAddPlace) { Text("+ Add") } }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    onRefresh()
                    delay(1000)
                    isRefreshing = false
                }
            },
            state = rememberPullToRefreshState(),
            modifier = Modifier.padding(padding)
        ) {
            if (places.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Place, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.no_saved_places), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(places, key = { it.id }) { place ->
                        PlaceRow(place, onDelete = { pendingDelete = place })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: SavedPlace, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(place.name, fontWeight = FontWeight.Medium)
                if (!place.address.isNullOrBlank()) {
                    Text(place.address, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Text("${place.radiusMeters} m · ${place.placeType.name.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
