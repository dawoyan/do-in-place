package com.davoyans.doinplace.ui.task

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.ChecklistItem
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.PlaceTypeUsage
import com.davoyans.doinplace.data.model.SavedPlace
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TrustedContact
import com.davoyans.doinplace.data.model.parseChecklistItems
import com.davoyans.doinplace.data.model.toChecklistJson
import com.davoyans.doinplace.data.places.PlaceTypeEngine
import com.davoyans.doinplace.data.places.PlaceTypeInfo
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import com.davoyans.doinplace.ui.contacts.iconIdToVector
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

// Place types that imply shopping list mode
private val SHOPPING_PLACE_TYPE_IDS = setOf(
    "supermarket", "household", "bazaar", "building_materials", "hardware"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateTaskScreen(
    savedPlaces: List<SavedPlace>,
    trustedContacts: List<TrustedContact>,
    currentUserId: String,
    displayPrefs: List<ContactDisplayPref> = emptyList(),
    initialTitle: String = "",
    initialDescription: String = "",
    initialShoppingItems: String = "",
    initialPriority: TaskPriority = TaskPriority.NO_RUSH,
    initialAssigneeId: String? = null,
    initialPlaceMode: PlaceMode = PlaceMode.EXACT,
    initialPlaceTypeId: String? = null,
    initialDueDate: String = "",
    initialDueTime: String = "",
    placeTypeUsages: List<PlaceTypeUsage> = emptyList(),
    onSave: (Task, List<ShoppingListItem>) -> Unit,
    onPickPlace: (title: String, description: String, shoppingItems: String, taskType: TaskType,
                  priority: TaskPriority, assigneeId: String, placeMode: PlaceMode,
                  placeTypeId: String?, dueDate: String, dueTime: String) -> Unit,
    onBack: () -> Unit,
    pendingPickedPlace: SavedPlace? = null,
    onSuggestPriority: (suspend (placeId: String?, placeName: String, lat: Double, lng: Double, title: String) -> Pair<TaskPriority, String>?)? = null,
    onGetSuggestions: (suspend (placeKey: String, placeTypeId: String?, placeName: String) -> List<String>)? = null,
    onSuggestionAccepted: ((placeKey: String, placeTypeId: String?, text: String) -> Unit)? = null,
    onGetShoppingSuggestions: (suspend (placeKey: String, placeTypeId: String?) -> List<String>)? = null,
    onShoppingItemAdded: ((placeKey: String, placeTypeId: String?, text: String) -> Unit)? = null,
    onPlaceTypeUsed: (placeTypeId: String) -> Unit = {}
) {
    val context = LocalContext.current

    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    var shoppingItemsText by remember { mutableStateOf(initialShoppingItems) }
    var placeMode by remember { mutableStateOf(initialPlaceMode) }
    var selectedPlace by remember { mutableStateOf(pendingPickedPlace) }
    var selectedPlaceType by remember {
        mutableStateOf(
            if (initialPlaceTypeId != null)
                PlaceTypeEngine.DEFAULT_PLACE_TYPES.find { it.id == initialPlaceTypeId }
            else null
        )
    }
    var selectedAssigneeId by remember { mutableStateOf(initialAssigneeId ?: currentUserId) }
    var radius by remember { mutableStateOf(selectedPlace?.radiusMeters ?: 100) }
    var placesExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var assigneeExpanded by remember { mutableStateOf(false) }
    var radiusExpanded by remember { mutableStateOf(false) }
    var pendingTaskToCreate by remember { mutableStateOf<Task?>(null) }

    // User-overridable task type (null = auto)
    var userOverrideType by remember { mutableStateOf<TaskType?>(null) }

    // Priority
    var selectedPriority by remember { mutableStateOf(initialPriority) }
    var userChangedPriority by remember { mutableStateOf(false) }
    var learningSuggestion by remember { mutableStateOf<Pair<TaskPriority, String>?>(null) }

    // Due date
    var dueDate by remember { mutableStateOf(initialDueDate) }
    var dueTime by remember { mutableStateOf(initialDueTime) }

    // Task-title suggestions
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    // Shopping-item suggestions (learned from user history)
    var shoppingSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    // Track which suggestions were tapped this session (to hide them immediately)
    var tappedSuggestions by remember { mutableStateOf<Set<String>>(emptySet()) }

    val radiusOptions = listOf(100, 200, 300, 500, 1000, 1500)

    // Place types ordered by recent/frequent usage
    val orderedPlaceTypes = remember(placeTypeUsages) {
        val usageMap = placeTypeUsages.associateBy { it.placeTypeId }
        PlaceTypeEngine.DEFAULT_PLACE_TYPES.sortedWith(
            compareByDescending<PlaceTypeInfo> { usageMap[it.id]?.lastUsedAt ?: 0L }
                .thenByDescending { usageMap[it.id]?.useCount ?: 0 }
        )
    }

    // Effective suggestion context
    val effectivePlaceKey = when (placeMode) {
        PlaceMode.EXACT -> selectedPlace?.id ?: ""
        PlaceMode.TYPE  -> ""
    }
    val effectivePlaceTypeId = when (placeMode) {
        PlaceMode.EXACT -> selectedPlace?.let { PlaceTypeEngine.inferPlaceType(it.name)?.id }
        PlaceMode.TYPE  -> selectedPlaceType?.id
    }
    val effectivePlaceName = when (placeMode) {
        PlaceMode.EXACT -> selectedPlace?.name ?: ""
        PlaceMode.TYPE  -> selectedPlaceType?.displayName ?: ""
    }

    // Auto-infer task type from content + place
    val inferredTaskType by remember(shoppingItemsText, description, effectivePlaceTypeId) {
        derivedStateOf {
            val isShoppingPlace = effectivePlaceTypeId in SHOPPING_PLACE_TYPE_IDS
            val text = if (shoppingItemsText.isNotBlank()) shoppingItemsText else description
            val lines = text.lines().filter { it.isNotBlank() }
            val isMultilineShort = lines.size >= 2 && lines.all { it.length < 40 }
            when {
                isShoppingPlace || isMultilineShort -> TaskType.SHOPPING_LIST
                else -> TaskType.SIMPLE
            }
        }
    }
    val effectiveTaskType = userOverrideType ?: inferredTaskType

    // Fetch task-title suggestions when place changes
    if (onGetSuggestions != null) {
        LaunchedEffect(effectivePlaceKey, effectivePlaceTypeId, effectivePlaceName) {
            suggestions = if (effectivePlaceName.isBlank() && effectivePlaceTypeId == null) emptyList()
            else onGetSuggestions(effectivePlaceKey, effectivePlaceTypeId, effectivePlaceName)
        }
    }

    // Fetch shopping suggestions when place changes; reset tapped set on place change
    if (onGetShoppingSuggestions != null) {
        LaunchedEffect(effectivePlaceKey, effectivePlaceTypeId) {
            tappedSuggestions = emptySet()
            shoppingSuggestions = onGetShoppingSuggestions(effectivePlaceKey, effectivePlaceTypeId)
        }
    }

    // Fetch priority learning suggestion
    if (onSuggestPriority != null) {
        val place = selectedPlace
        LaunchedEffect(title, place, placeMode) {
            if (placeMode == PlaceMode.TYPE || place == null || title.isBlank()) {
                learningSuggestion = null; return@LaunchedEffect
            }
            kotlinx.coroutines.delay(600)
            learningSuggestion = onSuggestPriority(place.id, place.name, place.latitude, place.longitude, title)
        }
    }

    fun openDateTimePicker() {
        val cal = Calendar.getInstance()
        if (dueDate.isNotBlank()) {
            runCatching {
                val parts = dueDate.split("-")
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                dueDate = "%04d-%02d-%02d".format(year, month + 1, day)
                val timeCal = Calendar.getInstance()
                if (dueTime.isNotBlank()) {
                    runCatching {
                        val tp = dueTime.split(":")
                        timeCal.set(Calendar.HOUR_OF_DAY, tp[0].toInt())
                        timeCal.set(Calendar.MINUTE, tp[1].toInt())
                    }
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute -> dueTime = "%02d:%02d".format(hour, minute) },
                    timeCal.get(Calendar.HOUR_OF_DAY),
                    timeCal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val dueSummary = remember(dueDate, dueTime) {
        if (dueDate.isBlank()) ""
        else {
            val display = runCatching {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val d = sdf.parse(dueDate)
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d!!)
            }.getOrElse { dueDate }
            if (dueTime.isNotBlank()) "$display at $dueTime" else display
        }
    }

    // Confirmation dialog
    pendingTaskToCreate?.let { task ->
        val isSelfTask = task.assignedToUserId == currentUserId
        val assigneePref = displayPrefs.find { it.contactUserId == task.assignedToUserId }
        val assigneeContact = trustedContacts.find { it.contactUserId == task.assignedToUserId }
        val assigneeDisplayName = if (isSelfTask) "Me"
        else ContactDisplayRepository.resolveDisplayName(task.assignedToUserId, assigneeContact, assigneePref)

        var blinkDone by remember { mutableStateOf(false) }
        var blinkVisible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            repeat(6) {
                blinkVisible = false
                delay(260)
                blinkVisible = true
                delay(260)
            }
            blinkDone = true
        }

        val assigneeColor = if (isSelfTask) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error

        AlertDialog(
            onDismissRequest = { pendingTaskToCreate = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Assign to")
                    Text(assigneeDisplayName, fontWeight = FontWeight.Bold, color = assigneeColor)
                }
            },
            text = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (title.isNotBlank()) DialogRow("Title", task.title)
                        DialogRow("Priority", if (task.priority == TaskPriority.URGENT) context.getString(R.string.urgent) else context.getString(R.string.no_rush))
                        DialogRow("Place", task.placeName)
                        if (task.placeMode == PlaceMode.TYPE) {
                            DialogRow("Mode", "Any matching place")
                        } else {
                            if (!task.address.isNullOrBlank() && task.address != task.placeName)
                                DialogRow("Address", task.address)
                            DialogRow("Radius", "${task.radiusMeters} m")
                        }
                        if (task.taskType == TaskType.SHOPPING_LIST) {
                            val count = shoppingItemsText.lines().count { it.isNotBlank() }
                            DialogRow("Type", "Shopping List ($count items)")
                        }
                        if (dueSummary.isNotBlank()) DialogRow("Due", dueSummary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!blinkDone) return@TextButton
                    val activeText = if (effectiveTaskType == TaskType.SHOPPING_LIST) shoppingItemsText else description
                    val shoppingItems = if (task.taskType == TaskType.SHOPPING_LIST) {
                        activeText.lines()
                            .filter { it.isNotBlank() }
                            .mapIndexed { idx, line ->
                                ShoppingListItem(
                                    id = UUID.randomUUID().toString(),
                                    taskId = task.id,
                                    text = line.trim(),
                                    normalizedText = line.trim().lowercase()
                                        .replace(Regex("[^\\w\\s]"), "")
                                        .replace(Regex("\\s+"), " ").trim(),
                                    orderIndex = idx
                                )
                            }
                    } else emptyList()
                    onSave(task, shoppingItems)
                    pendingTaskToCreate = null
                }) {
                    Text(
                        "Assign to $assigneeDisplayName",
                        color = assigneeColor.copy(alpha = if (blinkVisible) 1f else 0.12f)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTaskToCreate = null }) { Text("Cancel") }
            }
        )
    }

    LaunchedEffect(pendingPickedPlace) {
        if (pendingPickedPlace != null) {
            selectedPlace = pendingPickedPlace
            radius = pendingPickedPlace.radiusMeters
            placeMode = PlaceMode.EXACT
        }
    }

    val activeText = if (effectiveTaskType == TaskType.SHOPPING_LIST) shoppingItemsText else description
    val canCreate = (
        (placeMode == PlaceMode.EXACT && selectedPlace != null) ||
        (placeMode == PlaceMode.TYPE && selectedPlaceType != null)
    ) && (effectiveTaskType == TaskType.SIMPLE || activeText.lines().any { it.isNotBlank() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_reminder)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── 1. Assignee (compact one-line) ─────────────────────────────
            if (trustedContacts.isNotEmpty()) {
                val assigneePref = displayPrefs.find { it.contactUserId == selectedAssigneeId }
                val assigneeContact = trustedContacts.find { it.contactUserId == selectedAssigneeId }
                val assigneeName = if (selectedAssigneeId == currentUserId) "Me"
                    else ContactDisplayRepository.resolveDisplayName(selectedAssigneeId, assigneeContact, assigneePref)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "For:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { assigneeExpanded = true },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (selectedAssigneeId != currentUserId && assigneePref != null) {
                                        Icon(iconIdToVector(assigneePref.iconId), null,
                                            Modifier.size(14.dp))
                                    }
                                    Text(assigneeName, style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                                }
                            }
                        )
                        DropdownMenu(assigneeExpanded, { assigneeExpanded = false }) {
                            DropdownMenuItem(text = { Text("Me") }, onClick = {
                                selectedAssigneeId = currentUserId; assigneeExpanded = false
                            })
                            trustedContacts.forEach { contact ->
                                val pref = displayPrefs.find { it.contactUserId == contact.contactUserId }
                                val name = ContactDisplayRepository.resolveDisplayName(contact.contactUserId, contact, pref)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(iconIdToVector(pref?.iconId ?: "person"), null,
                                                Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                            Text(name)
                                        }
                                    },
                                    onClick = { selectedAssigneeId = contact.contactUserId; assigneeExpanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // ── 2. Place ────────────────────────────────────────────────────
            Text("Place *", style = MaterialTheme.typography.labelLarge)
            if (placeMode == PlaceMode.EXACT) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { placesExpanded = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Place, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                selectedPlace?.name ?: "Select saved place",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                        }
                        DropdownMenu(placesExpanded, { placesExpanded = false }) {
                            savedPlaces.forEach { place ->
                                DropdownMenuItem(text = { Text(place.name) }, onClick = {
                                    selectedPlace = place; radius = place.radiusMeters
                                    placesExpanded = false
                                })
                            }
                            if (savedPlaces.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No saved places — use Search", color = MaterialTheme.colorScheme.onSurface.copy(0.5f)) },
                                    onClick = { placesExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        onPickPlace(title, description, shoppingItemsText, effectiveTaskType,
                            selectedPriority, selectedAssigneeId, placeMode,
                            selectedPlaceType?.id, dueDate, dueTime)
                    }) { Text("Search") }
                }

                if (selectedPlace != null) {
                    Box {
                        OutlinedButton(onClick = { radiusExpanded = true }, Modifier.fillMaxWidth()) {
                            Text("Radius: $radius m")
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

                FilledTonalButton(
                    onClick = { placeMode = PlaceMode.TYPE; selectedPlace = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Category, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.use_place_type_instead),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // TYPE mode — single button that opens the type list directly
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { typeExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Category, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            selectedPlaceType?.displayName ?: "Select place type",
                            Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                    }
                    DropdownMenu(typeExpanded, { typeExpanded = false }) {
                        orderedPlaceTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = {
                                    selectedPlaceType = type
                                    onPlaceTypeUsed(type.id)
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Activates near any matching place",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    TextButton(
                        onClick = { placeMode = PlaceMode.EXACT; selectedPlaceType = null },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("exact place", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ── 3. Inferred place type badge ────────────────────────────────
            effectivePlaceTypeId?.let { typeId ->
                val typeName = PlaceTypeEngine.DEFAULT_PLACE_TYPES.find { it.id == typeId }?.displayName
                    ?: selectedPlaceType?.displayName
                if (typeName != null && placeMode == PlaceMode.EXACT) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Place, null,
                            Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        Text(
                            "Inferred: $typeName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── 4. Title (optional) ────────────────────────────────────────
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ── 5. Detected-as chip + content area ─────────────────────────
            // "Detected as:" chip (subtle, always visible once place is picked)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Detected as:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                SuggestionChip(
                    onClick = {
                        userOverrideType = if (effectiveTaskType == TaskType.SHOPPING_LIST)
                            TaskType.SIMPLE else TaskType.SHOPPING_LIST
                    },
                    label = {
                        Text(
                            if (effectiveTaskType == TaskType.SHOPPING_LIST) "Shopping list" else "Simple task",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                Text(
                    "· Change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }

            if (effectiveTaskType == TaskType.SHOPPING_LIST) {
                // Shopping suggestions ABOVE the text area
                val normCurrentItems = shoppingItemsText.lines()
                    .map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
                    .filter { it.isNotBlank() }
                    .toSet()
                val visibleSuggestions = shoppingSuggestions
                    .filter { s ->
                        val norm = s.trim().lowercase().replace(Regex("\\s+"), " ")
                        norm.isNotBlank() && norm !in normCurrentItems && norm !in tappedSuggestions
                    }
                    .take(8)

                if (visibleSuggestions.isNotEmpty()) {
                    Text(
                        "From your history:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        visibleSuggestions.forEach { suggestion ->
                            SuggestionChip(
                                onClick = {
                                    val normSugg = suggestion.trim().lowercase()
                                        .replace(Regex("\\s+"), " ")
                                    if (normSugg !in normCurrentItems) {
                                        val existing = shoppingItemsText.trimEnd()
                                        shoppingItemsText = if (existing.isBlank()) suggestion
                                            else "$existing\n$suggestion"
                                        tappedSuggestions = tappedSuggestions + normSugg
                                        onShoppingItemAdded?.invoke(effectivePlaceKey, effectivePlaceTypeId, suggestion)
                                        android.util.Log.d("ShoppingSuggest", "suggestion tapped item=$suggestion")
                                    } else {
                                        android.util.Log.d("ShoppingSuggest", "duplicate skipped item=$suggestion")
                                    }
                                },
                                label = {
                                    Text(
                                        "+ $suggestion",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                } else if (onGetShoppingSuggestions != null && shoppingSuggestions.isEmpty()
                    && (effectivePlaceKey.isNotBlank() || effectivePlaceTypeId != null)) {
                    Text(
                        "Add items once, and they will appear here next time.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                val itemCount = shoppingItemsText.lines().count { it.isNotBlank() }
                OutlinedTextField(
                    value = shoppingItemsText,
                    onValueChange = { shoppingItemsText = it },
                    label = { Text("Shopping items") },
                    placeholder = {
                        Text(
                            "milk\nbread\neggs\nwater",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                if (itemCount > 0) {
                    Text(
                        "✓ $itemCount item${if (itemCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            } else {
                val checklistPreview = description.parseChecklistItems()
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                if (checklistPreview != null) {
                    Text(
                        "✓ ${checklistPreview.size} checklist items detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // ── 6. Priority (compact 2-option row) ────────────────────────
            learningSuggestion?.let { (suggestedPriority, reason) ->
                if (!userChangedPriority || suggestedPriority != selectedPriority) {
                    AssistChip(
                        onClick = { selectedPriority = suggestedPriority },
                        label = {
                            Text(
                                "${stringResource(R.string.suggested)}: ${if (suggestedPriority == TaskPriority.URGENT) stringResource(R.string.urgent) else stringResource(R.string.no_rush)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(14.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Priority:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = selectedPriority == TaskPriority.NO_RUSH,
                        onClick = { selectedPriority = TaskPriority.NO_RUSH; userChangedPriority = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text(stringResource(R.string.no_rush)) }
                    SegmentedButton(
                        selected = selectedPriority == TaskPriority.URGENT,
                        onClick = { selectedPriority = TaskPriority.URGENT; userChangedPriority = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text(stringResource(R.string.urgent)) }
                }
            }

            // ── 7. Due date ────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { openDateTimePicker() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (dueSummary.isNotBlank()) dueSummary else "Due date (optional)")
                }
                if (dueDate.isNotBlank()) {
                    IconButton(onClick = { dueDate = ""; dueTime = "" }) {
                        Icon(Icons.Default.Close, "Clear due date",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── 8. Create button ───────────────────────────────────────────
            Button(
                onClick = {
                    val descTrimmed = description.trim().takeIf { it.isNotBlank() }
                    val parsedItems = descTrimmed?.parseChecklistItems()
                    val checklistJson = if (effectiveTaskType == TaskType.SIMPLE)
                        parsedItems?.map { ChecklistItem(it, false) }?.toChecklistJson()
                    else null

                    val taskId = UUID.randomUUID().toString()
                    val taskTitle = title.trim().ifBlank {
                        // Auto-generate title from place if blank
                        when (placeMode) {
                            PlaceMode.EXACT -> "Task at ${selectedPlace?.name ?: "place"}"
                            PlaceMode.TYPE  -> "Task at ${selectedPlaceType?.displayName ?: "place"}"
                        }
                    }

                    val task = when (placeMode) {
                        PlaceMode.EXACT -> {
                            val place = selectedPlace ?: return@Button
                            val inferredType = PlaceTypeEngine.inferPlaceType(place.name)
                            Task(
                                id = taskId,
                                title = taskTitle,
                                description = descTrimmed,
                                createdByUserId = currentUserId,
                                assignedToUserId = selectedAssigneeId,
                                placeId = place.id,
                                placeName = place.name,
                                address = place.address,
                                latitude = place.latitude,
                                longitude = place.longitude,
                                radiusMeters = radius,
                                status = if (selectedAssigneeId == currentUserId) TaskStatus.ACTIVE
                                         else TaskStatus.PENDING_ACCEPTANCE,
                                priority = selectedPriority,
                                activeFromDate = dueDate.takeIf { it.isNotBlank() },
                                activeStartTime = dueTime.takeIf { it.isNotBlank() },
                                checklistJson = checklistJson,
                                pendingSync = selectedAssigneeId != currentUserId,
                                placeMode = PlaceMode.EXACT,
                                placeTypeId = inferredType?.id,
                                placeTypeName = inferredType?.displayName,
                                taskType = effectiveTaskType
                            )
                        }
                        PlaceMode.TYPE -> {
                            val type = selectedPlaceType ?: return@Button
                            Task(
                                id = taskId,
                                title = taskTitle,
                                description = descTrimmed,
                                createdByUserId = currentUserId,
                                assignedToUserId = selectedAssigneeId,
                                placeId = null,
                                placeName = type.displayName,
                                address = null,
                                latitude = 0.0,
                                longitude = 0.0,
                                radiusMeters = PlaceTypeEngine.DEFAULT_TYPE_RADIUS[type.id] ?: 250,
                                status = if (selectedAssigneeId == currentUserId) TaskStatus.ACTIVE
                                         else TaskStatus.PENDING_ACCEPTANCE,
                                priority = selectedPriority,
                                activeFromDate = dueDate.takeIf { it.isNotBlank() },
                                activeStartTime = dueTime.takeIf { it.isNotBlank() },
                                checklistJson = checklistJson,
                                pendingSync = selectedAssigneeId != currentUserId,
                                placeMode = PlaceMode.TYPE,
                                placeTypeId = type.id,
                                placeTypeName = type.displayName,
                                taskType = effectiveTaskType
                            )
                        }
                    }
                    pendingTaskToCreate = task
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canCreate
            ) { Text(stringResource(R.string.create_reminder_btn)) }
        }
    }
}

@Composable
private fun DialogRow(label: String, value: String) {
    Row {
        Text("$label:", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(56.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun priorityColor(priority: TaskPriority) = when (priority) {
    TaskPriority.URGENT -> MaterialTheme.colorScheme.error
    TaskPriority.NO_RUSH   -> MaterialTheme.colorScheme.primary
}
