package com.davoyans.doinplace.ui.task

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.davoyans.doinplace.util.DiagLog
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
    initialIsEverywhere: Boolean = false,
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
    var isEverywhere by remember { mutableStateOf(initialIsEverywhere) }
    var selectedPlace by remember { mutableStateOf(pendingPickedPlace) }
    var selectedPlaceType by remember {
        mutableStateOf(
            if (initialPlaceTypeId != null)
                PlaceTypeEngine.DEFAULT_PLACE_TYPES.find { it.id == initialPlaceTypeId }
            else null
        )
    }
    // Place type for shopping tasks in Everywhere mode
    var everywhereShoppingPlaceType by remember { mutableStateOf<PlaceTypeInfo?>(null) }
    var everywhereShoppingTypeExpanded by remember { mutableStateOf(false) }

    var selectedAssigneeId by remember(initialAssigneeId) { mutableStateOf(initialAssigneeId ?: currentUserId) }
    var radius by remember { mutableStateOf(selectedPlace?.radiusMeters ?: 100) }
    var placesExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var assigneeExpanded by remember { mutableStateOf(false) }
    var radiusExpanded by remember { mutableStateOf(false) }
    var pendingTaskToCreate by remember { mutableStateOf<Task?>(null) }
    var showDiscardChangesDialog by remember { mutableStateOf(false) }

    var userOverrideType by remember { mutableStateOf<TaskType?>(null) }

    var selectedPriority by remember { mutableStateOf(initialPriority) }
    var userChangedPriority by remember { mutableStateOf(false) }
    var learningSuggestion by remember { mutableStateOf<Pair<TaskPriority, String>?>(null) }

    var dueDate by remember { mutableStateOf(initialDueDate) }
    var dueTime by remember { mutableStateOf(initialDueTime) }

    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var shoppingSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var tappedSuggestions by remember { mutableStateOf<Set<String>>(emptySet()) }

    val radiusOptions = listOf(100, 200, 300, 500, 1000, 1500)

    val orderedPlaceTypes = remember(placeTypeUsages) {
        val usageMap = placeTypeUsages.associateBy { it.placeTypeId }
        PlaceTypeEngine.DEFAULT_PLACE_TYPES.sortedWith(
            compareByDescending<PlaceTypeInfo> { usageMap[it.id]?.lastUsedAt ?: 0L }
                .thenByDescending { usageMap[it.id]?.useCount ?: 0 }
        )
    }

    val effectivePlaceKey = when {
        isEverywhere -> ""
        placeMode == PlaceMode.EXACT -> selectedPlace?.id ?: ""
        else -> ""
    }
    val effectivePlaceTypeId = when {
        isEverywhere -> everywhereShoppingPlaceType?.id
        placeMode == PlaceMode.EXACT -> selectedPlace?.let { PlaceTypeEngine.inferPlaceType(it.name)?.id }
        else -> selectedPlaceType?.id
    }
    val effectivePlaceName = when {
        isEverywhere -> everywhereShoppingPlaceType?.displayName ?: "Everywhere"
        placeMode == PlaceMode.EXACT -> selectedPlace?.name ?: ""
        else -> selectedPlaceType?.displayName ?: ""
    }

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

    if (onGetSuggestions != null) {
        LaunchedEffect(effectivePlaceKey, effectivePlaceTypeId, effectivePlaceName) {
            suggestions = if (effectivePlaceName.isBlank() && effectivePlaceTypeId == null) emptyList()
            else onGetSuggestions(effectivePlaceKey, effectivePlaceTypeId, effectivePlaceName)
        }
    }

    if (onGetShoppingSuggestions != null) {
        LaunchedEffect(effectivePlaceKey, effectivePlaceTypeId) {
            tappedSuggestions = emptySet()
            shoppingSuggestions = onGetShoppingSuggestions(effectivePlaceKey, effectivePlaceTypeId)
        }
    }

    if (onSuggestPriority != null) {
        val place = selectedPlace
        LaunchedEffect(title, place, placeMode, isEverywhere) {
            if (isEverywhere || placeMode == PlaceMode.TYPE || place == null || title.isBlank()) {
                learningSuggestion = null; return@LaunchedEffect
            }
            delay(600)
            learningSuggestion = onSuggestPriority(place.id, place.name, place.latitude, place.longitude, title)
        }
    }

    fun openDateTimePicker(clearTimeOnCancel: Boolean = true) {
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
                val timeDialog = TimePickerDialog(
                    context,
                    { _, hour, minute -> dueTime = "%02d:%02d".format(hour, minute) },
                    timeCal.get(Calendar.HOUR_OF_DAY),
                    timeCal.get(Calendar.MINUTE),
                    true
                )
                if (clearTimeOnCancel) {
                    timeDialog.setButton(
                        DialogInterface.BUTTON_NEGATIVE,
                        context.getString(android.R.string.cancel)
                    ) { _, _ ->
                        dueTime = ""
                    }
                    timeDialog.setOnCancelListener {
                        dueTime = ""
                    }
                }
                timeDialog.show()
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

    val canCreate = when {
        isEverywhere -> {
            val hasContent = effectiveTaskType == TaskType.SIMPLE || activeText(effectiveTaskType, shoppingItemsText, description).lines().any { it.isNotBlank() }
            val hasDue = dueDate.isNotBlank()
            val hasShoppingType = effectiveTaskType != TaskType.SHOPPING_LIST || everywhereShoppingPlaceType != null
            hasContent && hasDue && hasShoppingType
        }
        placeMode == PlaceMode.EXACT -> selectedPlace != null &&
            (effectiveTaskType == TaskType.SIMPLE || activeText(effectiveTaskType, shoppingItemsText, description).lines().any { it.isNotBlank() })
        placeMode == PlaceMode.TYPE -> selectedPlaceType != null &&
            (effectiveTaskType == TaskType.SIMPLE || activeText(effectiveTaskType, shoppingItemsText, description).lines().any { it.isNotBlank() })
        else -> false
    }

    val hasUnsavedChanges by remember(
        title,
        description,
        shoppingItemsText,
        isEverywhere,
        selectedPlace,
        selectedPlaceType,
        everywhereShoppingPlaceType,
        selectedAssigneeId,
        selectedPriority,
        placeMode,
        dueDate,
        dueTime
    ) {
        derivedStateOf {
            title.isNotBlank() ||
                description.isNotBlank() ||
                shoppingItemsText.lines().any { it.isNotBlank() } ||
                selectedPlace != null ||
                selectedPlaceType != null ||
                everywhereShoppingPlaceType != null ||
                isEverywhere ||
                selectedAssigneeId != currentUserId ||
                selectedPriority != TaskPriority.NO_RUSH ||
                placeMode != PlaceMode.EXACT ||
                dueDate.isNotBlank() ||
                dueTime.isNotBlank()
        }
    }

    fun requestBack() {
        if (hasUnsavedChanges) {
            DiagLog.d("BACK_NAV", "route=create_task action=show_discard_dialog")
            showDiscardChangesDialog = true
        } else {
            onBack()
        }
    }

    BackHandler {
        when {
            pendingTaskToCreate != null -> pendingTaskToCreate = null
            showDiscardChangesDialog -> showDiscardChangesDialog = false
            else -> requestBack()
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Assign to")
                    Text(assigneeDisplayName, fontWeight = FontWeight.Bold, color = assigneeColor)
                }
            },
            text = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (title.isNotBlank()) DialogRow("Title", task.title)
                        DialogRow("Priority", if (task.priority == TaskPriority.URGENT) context.getString(R.string.urgent) else context.getString(R.string.no_rush))
                        if (task.isEverywhere) {
                            DialogRow("Place", stringResource(R.string.everywhere))
                            if (!task.activeFromDate.isNullOrBlank()) DialogRow("Due", dueSummary)
                        } else {
                            DialogRow("Place", task.placeName)
                            if (task.placeMode == PlaceMode.TYPE) DialogRow("Mode", "Any matching place")
                            else {
                                if (!task.address.isNullOrBlank() && task.address != task.placeName)
                                    DialogRow("Address", task.address)
                                DialogRow("Radius", "${task.radiusMeters} m")
                            }
                            if (dueSummary.isNotBlank()) DialogRow("Due", dueSummary)
                        }
                        if (task.taskType == TaskType.SHOPPING_LIST) {
                            val count = shoppingItemsText.lines().count { it.isNotBlank() }
                            DialogRow("Type", "Shopping List ($count items)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!blinkDone) return@TextButton
                    val activeTextVal = if (effectiveTaskType == TaskType.SHOPPING_LIST) shoppingItemsText else description
                    val shoppingItems = if (task.taskType == TaskType.SHOPPING_LIST) {
                        activeTextVal.lines().filter { it.isNotBlank() }
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

    if (showDiscardChangesDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardChangesDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your new reminder has unsaved changes.") },
            confirmButton = {
                TextButton(onClick = {
                    DiagLog.d("BACK_NAV", "route=create_task action=discard_changes")
                    showDiscardChangesDialog = false
                    onBack()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardChangesDialog = false }) {
                    Text("Keep editing")
                }
            }
        )
    }

    LaunchedEffect(pendingPickedPlace) {
        if (pendingPickedPlace != null) {
            selectedPlace = pendingPickedPlace
            radius = pendingPickedPlace.radiusMeters
            placeMode = PlaceMode.EXACT
            isEverywhere = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_reminder)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (pendingTaskToCreate != null) {
                            pendingTaskToCreate = null
                        } else {
                            requestBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
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

            // ── 1. Assignee ────────────────────────────────────────────────
            if (trustedContacts.isNotEmpty()) {
                val assigneePref = displayPrefs.find { it.contactUserId == selectedAssigneeId }
                val assigneeContact = trustedContacts.find { it.contactUserId == selectedAssigneeId }
                val assigneeName = if (selectedAssigneeId == currentUserId) "Me"
                    else ContactDisplayRepository.resolveDisplayName(selectedAssigneeId, assigneeContact, assigneePref)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("For:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { assigneeExpanded = true },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (selectedAssigneeId != currentUserId && assigneePref != null) {
                                        Icon(iconIdToVector(assigneePref.iconId), null, Modifier.size(14.dp))
                                    }
                                    Text(assigneeName, style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                                }
                            }
                        )
                        DropdownMenu(assigneeExpanded, { assigneeExpanded = false }) {
                            DropdownMenuItem(text = { Text("Me") }, onClick = { selectedAssigneeId = currentUserId; assigneeExpanded = false })
                            trustedContacts.forEach { contact ->
                                val pref = displayPrefs.find { it.contactUserId == contact.contactUserId }
                                val name = ContactDisplayRepository.resolveDisplayName(contact.contactUserId, contact, pref)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(iconIdToVector(pref?.iconId ?: "person"), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
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

            // ── 2. Place mode selector ─────────────────────────────────────
            Text(if (isEverywhere) stringResource(R.string.due_date_required) else "Place *",
                style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isEverywhere && placeMode == PlaceMode.EXACT,
                    onClick = { isEverywhere = false; placeMode = PlaceMode.EXACT },
                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                ) {
                    Text(
                        stringResource(R.string.exact_label),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SegmentedButton(
                    selected = !isEverywhere && placeMode == PlaceMode.TYPE,
                    onClick = { isEverywhere = false; placeMode = PlaceMode.TYPE; selectedPlace = null },
                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                ) {
                    Text(
                        stringResource(R.string.type_label),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SegmentedButton(
                    selected = isEverywhere,
                    onClick = { isEverywhere = true; selectedPlace = null; selectedPlaceType = null },
                    shape = SegmentedButtonDefaults.itemShape(2, 3)
                ) {
                    Text(
                        stringResource(R.string.everywhere),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ── 2b. Place content based on mode ───────────────────────────
            when {
                isEverywhere -> {
                    // Everywhere info
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Public, null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary)
                            Text(
                                stringResource(R.string.everywhere_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    // Shopping exception: require place type when shopping + everywhere
                    if (effectiveTaskType == TaskType.SHOPPING_LIST) {
                        Text(
                            "Place type for shopping *",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (everywhereShoppingPlaceType == null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { everywhereShoppingTypeExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Category, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                SelectorButtonText(
                                    text = everywhereShoppingPlaceType?.displayName ?: stringResource(R.string.select_place_type),
                                    placeholder = everywhereShoppingPlaceType == null
                                )
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                            }
                            DropdownMenu(everywhereShoppingTypeExpanded, { everywhereShoppingTypeExpanded = false }) {
                                orderedPlaceTypes.filter { it.id in SHOPPING_PLACE_TYPE_IDS }.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.displayName) },
                                        onClick = {
                                            everywhereShoppingPlaceType = type
                                            everywhereShoppingTypeExpanded = false
                                        }
                                    )
                                }
                                orderedPlaceTypes.filter { it.id !in SHOPPING_PLACE_TYPE_IDS }.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.displayName) },
                                        onClick = {
                                            everywhereShoppingPlaceType = type
                                            everywhereShoppingTypeExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        if (everywhereShoppingPlaceType == null) {
                            Text(
                                stringResource(R.string.everywhere_shopping_needs_type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                placeMode == PlaceMode.EXACT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { placesExpanded = true }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Place, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                SelectorButtonText(
                                    text = selectedPlace?.name ?: stringResource(R.string.select_saved_place),
                                    placeholder = selectedPlace == null
                                )
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                            }
                            DropdownMenu(placesExpanded, { placesExpanded = false }) {
                                savedPlaces.forEach { place ->
                                    DropdownMenuItem(text = { Text(place.name) }, onClick = {
                                        selectedPlace = place; radius = place.radiusMeters; placesExpanded = false
                                    })
                                }
                                if (savedPlaces.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.no_saved_places_hint), color = MaterialTheme.colorScheme.onSurface.copy(0.5f)) },
                                        onClick = { placesExpanded = false }
                                    )
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            onPickPlace(title, description, shoppingItemsText, effectiveTaskType,
                                selectedPriority, selectedAssigneeId, placeMode,
                                selectedPlaceType?.id, dueDate, dueTime)
                        }) { Text(stringResource(R.string.search)) }
                    }
                    if (selectedPlace != null) {
                        Box {
                            OutlinedButton(onClick = { radiusExpanded = true }, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.radius_m, radius))
                            }
                            DropdownMenu(radiusExpanded, { radiusExpanded = false }) {
                                radiusOptions.forEach { r ->
                                    DropdownMenuItem(text = { Text("$r m") }, onClick = { radius = r; radiusExpanded = false })
                                }
                            }
                        }
                    }
                }

                else -> { // TYPE mode
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Category, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            SelectorButtonText(
                                text = selectedPlaceType?.displayName ?: stringResource(R.string.select_place_type),
                                placeholder = selectedPlaceType == null
                            )
                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                        }
                        DropdownMenu(typeExpanded, { typeExpanded = false }) {
                            orderedPlaceTypes.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName) },
                                    onClick = { selectedPlaceType = type; onPlaceTypeUsed(type.id); typeExpanded = false }
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.activates_near_matching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // ── 3. Inferred place type badge (non-everywhere only) ─────────
            if (!isEverywhere) {
                effectivePlaceTypeId?.let { typeId ->
                    val typeName = PlaceTypeEngine.DEFAULT_PLACE_TYPES.find { it.id == typeId }?.displayName
                        ?: selectedPlaceType?.displayName
                    if (typeName != null && placeMode == PlaceMode.EXACT) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Place, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            Text(stringResource(R.string.inferred_type, typeName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            // ── 4. Title ──────────────────────────────────────────────────
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.title_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ── 5. Task type + content ────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.detected_as), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                SuggestionChip(
                    onClick = {
                        userOverrideType = if (effectiveTaskType == TaskType.SHOPPING_LIST)
                            TaskType.SIMPLE else TaskType.SHOPPING_LIST
                    },
                    label = { Text(if (effectiveTaskType == TaskType.SHOPPING_LIST) stringResource(R.string.shopping_list_type) else stringResource(R.string.simple_task_type), style = MaterialTheme.typography.labelSmall) }
                )
                Text(stringResource(R.string.change), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            }

            if (effectiveTaskType == TaskType.SHOPPING_LIST) {
                val normCurrentItems = shoppingItemsText.lines().map { it.trim().lowercase().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }.toSet()
                val visibleSuggestions = shoppingSuggestions.filter { s ->
                    val norm = s.trim().lowercase().replace(Regex("\\s+"), " ")
                    norm.isNotBlank() && norm !in normCurrentItems && norm !in tappedSuggestions
                }.take(8)

                if (visibleSuggestions.isNotEmpty()) {
                    Text("From your history:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        visibleSuggestions.forEach { suggestion ->
                            SuggestionChip(
                                onClick = {
                                    val normSugg = suggestion.trim().lowercase().replace(Regex("\\s+"), " ")
                                    if (normSugg !in normCurrentItems) {
                                        val existing = shoppingItemsText.trimEnd()
                                        shoppingItemsText = if (existing.isBlank()) suggestion else "$existing\n$suggestion"
                                        tappedSuggestions = tappedSuggestions + normSugg
                                        onShoppingItemAdded?.invoke(effectivePlaceKey, effectivePlaceTypeId, suggestion)
                                    }
                                },
                                label = { Text("+ $suggestion", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                val itemCount = shoppingItemsText.lines().count { it.isNotBlank() }
                OutlinedTextField(
                    value = shoppingItemsText,
                    onValueChange = { shoppingItemsText = it },
                    label = { Text(stringResource(R.string.shopping_items)) },
                    placeholder = { Text("milk\nbread\neggs\nwater", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                if (itemCount > 0) {
                    Text("✓ $itemCount item${if (itemCount != 1) "s" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                val checklistPreview = description.parseChecklistItems()
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.notes_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                if (checklistPreview != null) {
                    Text("✓ ${checklistPreview.size} checklist items detected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
                }
            }

            // ── 6. Priority ───────────────────────────────────────────────
            learningSuggestion?.let { (suggestedPriority, _) ->
                if (!userChangedPriority || suggestedPriority != selectedPriority) {
                    AssistChip(
                        onClick = { selectedPriority = suggestedPriority },
                        label = { Text("${stringResource(R.string.suggested)}: ${if (suggestedPriority == TaskPriority.URGENT) stringResource(R.string.urgent) else stringResource(R.string.no_rush)}", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(14.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.priority_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(selected = selectedPriority == TaskPriority.NO_RUSH, onClick = { selectedPriority = TaskPriority.NO_RUSH; userChangedPriority = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.no_rush)) }
                    SegmentedButton(selected = selectedPriority == TaskPriority.URGENT, onClick = { selectedPriority = TaskPriority.URGENT; userChangedPriority = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.urgent)) }
                }
            }

            // ── 7. Due date ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { openDateTimePicker() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (dueSummary.isNotBlank()) dueSummary else if (isEverywhere) stringResource(R.string.due_date_required) else stringResource(R.string.due_date))
                }
                if (dueDate.isNotBlank()) {
                    IconButton(onClick = { dueDate = ""; dueTime = "" }) {
                        Icon(Icons.Default.Close, "Clear due date", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
            if (isEverywhere && dueDate.isBlank()) {
                Text(
                    stringResource(R.string.everywhere_due_date_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
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
                        when {
                            isEverywhere -> "Everywhere task"
                            placeMode == PlaceMode.EXACT -> "Task at ${selectedPlace?.name ?: "place"}"
                            else -> "Task at ${selectedPlaceType?.displayName ?: "place"}"
                        }
                    }

                    val task = if (isEverywhere) {
                        Task(
                            id = taskId,
                            title = taskTitle,
                            description = descTrimmed,
                            createdByUserId = currentUserId,
                            assignedToUserId = selectedAssigneeId,
                            placeId = null,
                            placeName = "Everywhere",
                            address = null,
                            latitude = 0.0,
                            longitude = 0.0,
                            radiusMeters = 0,
                            status = if (selectedAssigneeId == currentUserId) TaskStatus.ACTIVE
                                     else TaskStatus.PENDING_ACCEPTANCE,
                            priority = selectedPriority,
                            activeFromDate = dueDate.takeIf { it.isNotBlank() },
                            activeStartTime = dueTime.takeIf { it.isNotBlank() },
                            checklistJson = checklistJson,
                            pendingSync = selectedAssigneeId != currentUserId,
                            placeMode = if (everywhereShoppingPlaceType != null) PlaceMode.TYPE else PlaceMode.EXACT,
                            placeTypeId = everywhereShoppingPlaceType?.id,
                            placeTypeName = everywhereShoppingPlaceType?.displayName,
                            taskType = effectiveTaskType,
                            isEverywhere = true
                        )
                    } else when (placeMode) {
                        PlaceMode.EXACT -> {
                            val place = selectedPlace ?: return@Button
                            val inferredType = PlaceTypeEngine.inferPlaceType(place.name)
                            Task(
                                id = taskId, title = taskTitle, description = descTrimmed,
                                createdByUserId = currentUserId, assignedToUserId = selectedAssigneeId,
                                placeId = place.id, placeName = place.name, address = place.address,
                                latitude = place.latitude, longitude = place.longitude, radiusMeters = radius,
                                status = if (selectedAssigneeId == currentUserId) TaskStatus.ACTIVE else TaskStatus.PENDING_ACCEPTANCE,
                                priority = selectedPriority,
                                activeFromDate = dueDate.takeIf { it.isNotBlank() },
                                activeStartTime = dueTime.takeIf { it.isNotBlank() },
                                checklistJson = checklistJson,
                                pendingSync = selectedAssigneeId != currentUserId,
                                placeMode = PlaceMode.EXACT,
                                placeTypeId = inferredType?.id, placeTypeName = inferredType?.displayName,
                                taskType = effectiveTaskType
                            )
                        }
                        PlaceMode.TYPE -> {
                            val type = selectedPlaceType ?: return@Button
                            Task(
                                id = taskId, title = taskTitle, description = descTrimmed,
                                createdByUserId = currentUserId, assignedToUserId = selectedAssigneeId,
                                placeId = null, placeName = type.displayName, address = null,
                                latitude = 0.0, longitude = 0.0,
                                radiusMeters = PlaceTypeEngine.DEFAULT_TYPE_RADIUS[type.id] ?: 250,
                                status = if (selectedAssigneeId == currentUserId) TaskStatus.ACTIVE else TaskStatus.PENDING_ACCEPTANCE,
                                priority = selectedPriority,
                                activeFromDate = dueDate.takeIf { it.isNotBlank() },
                                activeStartTime = dueTime.takeIf { it.isNotBlank() },
                                checklistJson = checklistJson,
                                pendingSync = selectedAssigneeId != currentUserId,
                                placeMode = PlaceMode.TYPE,
                                placeTypeId = type.id, placeTypeName = type.displayName,
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

private fun activeText(type: TaskType, shoppingItems: String, description: String) =
    if (type == TaskType.SHOPPING_LIST) shoppingItems else description

@Composable
private fun RowScope.SelectorButtonText(text: String, placeholder: Boolean) {
    Text(
        text = text,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
    )
}

@Composable
private fun DialogRow(label: String, value: String) {
    Row {
        Text("$label:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun priorityColor(priority: TaskPriority) = when (priority) {
    TaskPriority.URGENT -> MaterialTheme.colorScheme.error
    TaskPriority.NO_RUSH -> MaterialTheme.colorScheme.primary
}
