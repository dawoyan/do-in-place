package com.davoyans.doinplace.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.location.GeoapifyPlaceSearchProvider
import com.davoyans.doinplace.data.location.PlaceSearchResult
import com.davoyans.doinplace.data.model.PlaceType
import com.davoyans.doinplace.data.model.SavedPlace
import com.davoyans.doinplace.data.repository.PlaceSearchRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerScreen(
    savedPlaces: List<SavedPlace>,
    userId: String,
    onPlacePicked: (SavedPlace) -> Unit,
    onDeletePlace: (SavedPlace) -> Unit = {},
    onBack: () -> Unit,
    placeSearchRepository: PlaceSearchRepository,
    initialPlace: SavedPlace? = null,
    titleText: String? = null,
    showSavedPlacesSection: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val geoapify = remember { GeoapifyPlaceSearchProvider() }

    // ── Search state ─────────────────────────────────────────────────────────
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Last known location for proximity bias + result sorting (passive, no button)
    var userLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) {
        runCatching { userLocation = placeSearchRepository.getLastKnownLocation() }
    }

    // Debounced Geoapify search — LaunchedEffect cancels previous when query changes
    LaunchedEffect(query, userLocation) {
        suggestions = emptyList()
        searchError = null
        if (query.length < 3) { isSearching = false; return@LaunchedEffect }
        delay(700)
        isSearching = true
        geoapify.search(query, userLocation?.first, userLocation?.second)
            .onSuccess { suggestions = it }
            .onFailure { e ->
                searchError = if (e is IllegalStateException) e.message
                else "Place search is temporarily unavailable"
            }
        isSearching = false
    }

    // ── GPS / manual state ───────────────────────────────────────────────────
    var customName by remember(initialPlace?.id) { mutableStateOf(initialPlace?.name.orEmpty()) }
    var customAddress by remember(initialPlace?.id) { mutableStateOf(initialPlace?.address.orEmpty()) }
    var selectedType by remember(initialPlace?.id) { mutableStateOf(initialPlace?.placeType ?: PlaceType.CUSTOM) }
    var radius by remember(initialPlace?.id) { mutableStateOf(initialPlace?.radiusMeters ?: 100) }
    var resolvedLat by remember(initialPlace?.id) { mutableStateOf(initialPlace?.latitude) }
    var resolvedLng by remember(initialPlace?.id) { mutableStateOf(initialPlace?.longitude) }
    var locating by remember { mutableStateOf(false) }
    var locatingError by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    var radiusExpanded by remember { mutableStateOf(false) }

    val radiusDefaults = mapOf(
        PlaceType.EXACT to 100, PlaceType.MALL to 300,
        PlaceType.DISTRICT to 800, PlaceType.CUSTOM to 100
    )
    val radiusOptions = listOf(100, 200, 300, 500, 1000, 1500)
    val typeLabels = mapOf(
        PlaceType.EXACT    to "Exact address / building",
        PlaceType.MALL     to "Mall / Shopping center",
        PlaceType.DISTRICT to "District / Large area",
        PlaceType.CUSTOM   to "Custom"
    )

    val hasCoords = resolvedLat != null && resolvedLng != null
    val canSaveManual = customName.isNotBlank() && hasCoords

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText ?: stringResource(R.string.add_place)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }
            )
        }
    ) { padding ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            // ── Place chooser header ──────────────────────────────────────────
            item {
                Row(
                    Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Choose a location for your task",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 1. Search field ──────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search place or address") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        when {
                            isSearching -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                            )
                            query.isNotEmpty() -> IconButton(onClick = {
                                query = ""; suggestions = emptyList(); searchError = null
                            }) { Icon(Icons.Default.Clear, "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ── 2. Search error ──────────────────────────────────────────────
            searchError?.let { err ->
                item {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // ── 3. Search suggestions ────────────────────────────────────────
            if (suggestions.isNotEmpty()) {
                item {
                    Text(
                        "Suggestions",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(suggestions, key = { it.id }) { result ->
                    SuggestionRow(result) {
                        val place = SavedPlace(
                            id = initialPlace?.id ?: UUID.randomUUID().toString(),
                            userId = userId,
                            name = result.title,
                            address = result.formattedAddress,
                            latitude = result.latitude,
                            longitude = result.longitude,
                            radiusMeters = radiusForCategory(result.rawCategory),
                            placeType = placeTypeForCategory(result.rawCategory),
                            provider = result.provider,
                            createdAt = initialPlace?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        onPlacePicked(place)
                    }
                }
            } else if (query.length >= 3 && !isSearching && searchError == null) {
                item {
                    Text(
                        "No places found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // ── 4. Saved places ──────────────────────────────────────────────
            if (showSavedPlacesSection && savedPlaces.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item {
                    Text(
                        stringResource(R.string.places),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(savedPlaces, key = { it.id }) { place ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Place, null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onPlacePicked(place) }
                            ) {
                                Text(place.name, fontWeight = FontWeight.Medium)
                                if (!place.address.isNullOrBlank()) {
                                    Text(
                                        place.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Text(
                                    "${place.radiusMeters} m · ${typeLabels[place.placeType] ?: place.placeType.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { onDeletePlace(place) }) {
                                Icon(
                                    Icons.Default.Close, "Remove saved place",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── 5. GPS location ──────────────────────────────────────────────
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item {
                Text(
                    "Use GPS location",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        locating = true; locatingError = ""
                        scope.launch {
                            val loc = placeSearchRepository.getCurrentLocation()
                            locating = false
                            if (loc == null) {
                                locatingError = "Could not get location. Check GPS / permission."
                            } else {
                                resolvedLat = loc.latitude
                                resolvedLng = loc.longitude
                                if (customName.isBlank()) customName = loc.suggestedName
                                if (customAddress.isBlank() && loc.address != null) customAddress = loc.address
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !locating
                ) {
                    if (locating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.getting_location))
                    } else {
                        Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.use_current_location))
                    }
                }
            }

            if (locatingError.isNotBlank()) {
                item {
                    Text(locatingError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (hasCoords) {
                item {
                    Text(
                        "GPS: ${"%.5f".format(resolvedLat)}, ${"%.5f".format(resolvedLng)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // ── 6. Manual name / address ─────────────────────────────────────
            item {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text(stringResource(R.string.place_name_hint)) },
                    placeholder = { Text("e.g. Home, Work, Yerevan Mall") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = customAddress,
                    onValueChange = { customAddress = it },
                    label = { Text("Address (optional note)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 7. Place type & radius ───────────────────────────────────────
            item { Text(stringResource(R.string.place_type_label), style = MaterialTheme.typography.labelLarge) }
            item {
                Box {
                    OutlinedButton(onClick = { typeExpanded = true }, Modifier.fillMaxWidth()) {
                        Text(typeLabels[selectedType] ?: selectedType.name)
                    }
                    DropdownMenu(typeExpanded, { typeExpanded = false }) {
                        PlaceType.values().forEach { type ->
                            DropdownMenuItem(text = { Text(typeLabels[type] ?: type.name) }, onClick = {
                                selectedType = type
                                radius = radiusDefaults[type] ?: 100
                                typeExpanded = false
                            })
                        }
                    }
                }
            }

            item { Text(stringResource(R.string.detection_radius), style = MaterialTheme.typography.labelLarge) }
            item {
                Box {
                    OutlinedButton(onClick = { radiusExpanded = true }, Modifier.fillMaxWidth()) {
                        Text("$radius m")
                    }
                    DropdownMenu(radiusExpanded, { radiusExpanded = false }) {
                        radiusOptions.forEach { r ->
                            DropdownMenuItem(text = { Text("$r m") }, onClick = {
                                radius = r; radiusExpanded = false
                            })
                        }
                    }
                }
            }

            item {
                Text(
                    "100 m is the default. Increase for large venues like malls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // ── 8. Save GPS place ────────────────────────────────────────────
            item {
                Button(
                    onClick = {
                        val place = SavedPlace(
                            id = initialPlace?.id ?: UUID.randomUUID().toString(),
                            userId = userId,
                            name = customName.trim(),
                            address = customAddress.trim().takeIf { it.isNotBlank() },
                            latitude = resolvedLat ?: 0.0,
                            longitude = resolvedLng ?: 0.0,
                            radiusMeters = radius,
                            placeType = selectedType,
                            provider = initialPlace?.provider?.ifBlank { "gps" } ?: "gps",
                            createdAt = initialPlace?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        onPlacePicked(place)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSaveManual
                ) { Text(stringResource(R.string.save_place)) }

                if (!hasCoords) {
                    Text(
                        "Tap \"Use my current location\" to set coordinates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        } // end Surface
    }
}

@Composable
private fun SuggestionRow(result: PlaceSearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(result.title, fontWeight = FontWeight.Medium)
                if (result.formattedAddress != result.title) {
                    Text(
                        result.formattedAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            result.distanceMeters?.let { dist ->
                Text(
                    formatDistance(dist),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

private fun formatDistance(meters: Float): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    else -> "${"%.1f".format(meters / 1000)} km"
}

private fun radiusForCategory(category: String?): Int = when {
    category == null -> 100
    "mall" in category || "shopping" in category -> 300
    "district" in category || "city" in category -> 800
    else -> 100
}

private fun placeTypeForCategory(category: String?): PlaceType = when {
    category == null -> PlaceType.CUSTOM
    "mall" in category || "shopping" in category -> PlaceType.MALL
    "district" in category || "city" in category -> PlaceType.DISTRICT
    else -> PlaceType.CUSTOM
}
