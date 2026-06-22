package com.davoyans.doinplace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.davoyans.doinplace.BuildConfig
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.learning.LocalLearningEngine
import com.davoyans.doinplace.data.model.*
import com.davoyans.doinplace.data.places.TaskSuggestionEngine
import com.davoyans.doinplace.data.shopping.ShoppingOrderEngine
import com.davoyans.doinplace.engine.FoodHealthEngine
import com.davoyans.doinplace.engine.ShoppingItemCanonicalizer
import com.davoyans.doinplace.engine.UsualShoppingEngine
import com.davoyans.doinplace.notification.WalkReminderWorker
import com.davoyans.doinplace.ui.task.UsualShoppingSuggestionDialog
import com.davoyans.doinplace.ui.task.UsualShoppingSuggestionState
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.data.remote.SupabaseClient
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import com.davoyans.doinplace.data.repository.ContactRepository
import com.davoyans.doinplace.data.repository.PlaceRepository
import com.davoyans.doinplace.data.repository.PlaceSearchRepository
import com.davoyans.doinplace.data.repository.SavedCardRepository
import com.davoyans.doinplace.data.repository.SavedCardSaveResult
import com.davoyans.doinplace.data.repository.TaskRepository
import com.davoyans.doinplace.geofence.LocationReminderManager
import com.davoyans.doinplace.calendar.GoogleCalendarService
import com.davoyans.doinplace.notification.DueAlarmScheduler
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.notification.SnoozeAlarmReceiver
import com.davoyans.doinplace.notification.TimeBasedTaskScheduler
import com.davoyans.doinplace.util.RecurrenceCalculator
import java.time.LocalDate
import com.davoyans.doinplace.sync.SyncWorker
import com.davoyans.doinplace.sync.TypeTaskCheckWorker
import com.davoyans.doinplace.ui.archive.ArchivedTasksScreen
import com.davoyans.doinplace.ui.auth.AuthScreen
import com.davoyans.doinplace.ui.contacts.EditContactScreen
import com.davoyans.doinplace.ui.contacts.InviteCodeLookupResult
import com.davoyans.doinplace.ui.contacts.InviteData
import com.davoyans.doinplace.ui.contacts.TrustedContactsScreen
import com.davoyans.doinplace.ui.cards.CardDetailScreen
import com.davoyans.doinplace.ui.cards.CardImageDecoder
import com.davoyans.doinplace.ui.cards.CardsTabScreen
import com.davoyans.doinplace.ui.cards.EditCardScreen
import com.davoyans.doinplace.ui.cards.SaveCardScreen
import com.davoyans.doinplace.ui.cards.SavedCardCodeTypes
import com.davoyans.doinplace.ui.cards.SavedCardDraft
import com.davoyans.doinplace.ui.cards.ScanCardScreen
import com.davoyans.doinplace.ui.cards.ScannedCardPayload
import com.davoyans.doinplace.ui.home.HomeScreen
import com.davoyans.doinplace.ui.places.PlaceDetailScreen
import com.davoyans.doinplace.ui.places.PlacePickerScreen
import com.davoyans.doinplace.ui.places.PlacesRoutes
import com.davoyans.doinplace.ui.places.SavedPlacesScreen
import com.davoyans.doinplace.ui.settings.PermissionState
import com.davoyans.doinplace.ui.settings.PermissionsScreen
import com.davoyans.doinplace.ui.settings.SettingsScreen
import com.davoyans.doinplace.ui.task.AddRecurringTaskScreen
import com.davoyans.doinplace.ui.task.CreateTaskScreen
import com.davoyans.doinplace.ui.task.SortListScreen
import com.davoyans.doinplace.ui.task.TaskDetailScreen
import com.davoyans.doinplace.ui.theme.RemindInPlaceTheme
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.article.ArticleSummaryExtractor
import com.davoyans.doinplace.data.location.GeoapifyPlaceSearchProvider
import com.davoyans.doinplace.ocr.ScreenshotTextExtractor
import com.davoyans.doinplace.ocr.isLatinDominant
import com.davoyans.doinplace.share.ShareReceiverActivity
import com.davoyans.doinplace.share.SharedInputRoute
import com.davoyans.doinplace.share.SharedInputRouter
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.LocationShareParser
import com.davoyans.doinplace.util.ReminderItemFilter
import com.davoyans.doinplace.util.isRecurringTask
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val BACK_EXIT_TIMEOUT_MS = 2_000L
private val ROOT_BACK_EXIT_SCREENS = setOf("home", "saved_places", "cards", "contacts", "settings")

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var taskRepo: TaskRepository
    private lateinit var placeRepo: PlaceRepository
    private lateinit var savedCardRepo: SavedCardRepository
    private lateinit var contactRepo: ContactRepository
    private lateinit var contactDisplayRepo: ContactDisplayRepository
    private lateinit var locationReminderManager: LocationReminderManager
    private lateinit var placeSearchRepo: PlaceSearchRepository
    private lateinit var authClient: SupabaseAuthClient
    private lateinit var supabase: SupabaseClient
    private lateinit var learningEngine: LocalLearningEngine
    private lateinit var suggestionEngine: TaskSuggestionEngine
    private lateinit var shoppingOrderEngine: ShoppingOrderEngine
    private lateinit var usualShoppingEngine: UsualShoppingEngine
    private lateinit var foodHealthEngine: FoodHealthEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled reactively via permissionRefreshKey */ }

    private var permissionRefreshKey by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        permissionRefreshKey++
    }

    private var pendingInvite by mutableStateOf<InviteData?>(null)
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var pendingPrefillNote by mutableStateOf<String?>(null)
    private var pendingNotificationRoute by mutableStateOf<String?>(null)
    private var pendingNotificationTaskId by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInvite = parseInviteUri(intent.data)
        intent.getStringExtra(ShareReceiverActivity.EXTRA_PREFILL_NOTE)?.takeIf { it.isNotBlank() }?.let {
            pendingPrefillNote = it
        }
        extractSharedText(intent)?.let { pendingSharedText = it }
        captureNotificationIntent(intent)
    }

    private fun extractSharedText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.type != "text/plain") return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }

    private fun parseInviteUri(uri: Uri?): InviteData? {
        if (uri?.scheme != "doinplace" || uri.host != "invite") return null
        val uid = uri.getQueryParameter("uid") ?: return null
        if (uid.isBlank()) return null
        return InviteData(
            userId = uid,
            name   = uri.getQueryParameter("name") ?: "",
            email  = uri.getQueryParameter("email") ?: ""
        )
    }

    fun parseInviteFromText(text: String): InviteData? =
        runCatching { parseInviteUri(Uri.parse(text.trim())) }.getOrNull()

    private fun captureNotificationIntent(intent: Intent) {
        captureNotificationUri(intent.data)
        if (intent.getBooleanExtra("open_contacts", false)) {
            pendingNotificationRoute = NotificationHelper.ROUTE_CONTACTS
        }
        intent.getStringExtra(NotificationHelper.EXTRA_OPEN_ROUTE)
            ?.takeIf { it.isNotBlank() }
            ?.let { pendingNotificationRoute = it }
        intent.getStringExtra(NotificationHelper.EXTRA_TASK_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                pendingNotificationTaskId = it
                if (pendingNotificationRoute.isNullOrBlank()) {
                    pendingNotificationRoute = NotificationHelper.ROUTE_TASK
                }
            }
    }

    private fun captureNotificationUri(uri: Uri?) {
        if (uri?.scheme != "doinplace") return
        when (uri.host) {
            NotificationHelper.ROUTE_TASK,
            NotificationHelper.ROUTE_SHOPPING_LIST -> {
                pendingNotificationRoute = uri.host
                pendingNotificationTaskId = uri.pathSegments.firstOrNull()
            }
            NotificationHelper.ROUTE_CONTACTS,
            "connection-request",
            "contact" -> {
                pendingNotificationRoute = NotificationHelper.ROUTE_CONTACTS
            }
            "arrival" -> {
                pendingNotificationRoute = NotificationHelper.ROUTE_TASK
                pendingNotificationTaskId = uri.pathSegments.firstOrNull()
            }
        }
    }

    private fun buildArticleBody(summary: String?, url: String): String = buildString {
        if (!summary.isNullOrBlank()) { appendLine("Summary:"); appendLine(summary); appendLine() }
        appendLine("URL:"); append(url)
    }.trim()

    private fun normalizeInviteCode(raw: String): String =
        raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun maskCardCode(raw: String): String {
        val value = raw.trim()
        if (value.length <= 8) return "••••"
        return "${value.take(4)}••••••${value.takeLast(4)}"
    }

    private fun createManualCardDraft(): SavedCardDraft = SavedCardDraft(
        codeType = SavedCardCodeTypes.BARCODE,
        barcodeFormat = com.google.zxing.BarcodeFormat.CODE_128.name,
        isManualEntry = true
    )

    private fun createScannedCardDraft(payload: ScannedCardPayload): SavedCardDraft = SavedCardDraft(
        codeType = payload.codeType,
        barcodeFormat = payload.barcodeFormat ?: if (payload.codeType == SavedCardCodeTypes.QR) {
            com.google.zxing.BarcodeFormat.QR_CODE.name
        } else {
            com.google.zxing.BarcodeFormat.CODE_128.name
        },
        codeValue = payload.codeValue,
        allowTypeEditing = false,
        allowCodeEditing = false,
        isManualEntry = false
    )

    private fun addTombstone(taskId: String) {
        val p = prefs()
        val existing = p.getStringSet("deleted_task_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(taskId)
        // Cap tombstone to 500 entries to avoid unbounded growth
        val pruned = if (existing.size > 500) existing.drop(existing.size - 500).toMutableSet() else existing
        p.edit().putStringSet("deleted_task_ids", pruned).apply()
    }

    private fun prefs() = getSharedPreferences("dip_prefs", MODE_PRIVATE)

    override fun attachBaseContext(newBase: android.content.Context) {
        val lang = newBase.getSharedPreferences("dip_prefs", MODE_PRIVATE)
            .getString("app_language", "system") ?: "system"
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        db = AppDatabase.get(this)
        taskRepo = TaskRepository(db)
        placeRepo = PlaceRepository(db)
        savedCardRepo = SavedCardRepository(db.savedCardDao())
        contactRepo = ContactRepository(db)
        contactDisplayRepo = ContactDisplayRepository(db)
        locationReminderManager = LocationReminderManager(this)
        placeSearchRepo = PlaceSearchRepository(this)
        authClient = SupabaseAuthClient(this)
        supabase = SupabaseClient(this)
        learningEngine = LocalLearningEngine(db.taskLearningProfileDao())
        suggestionEngine = TaskSuggestionEngine(db.userTaskSuggestionDao())
        shoppingOrderEngine = ShoppingOrderEngine(db.shoppingPlaceItemOrderDao())
        usualShoppingEngine = UsualShoppingEngine(db)
        foodHealthEngine = FoodHealthEngine(db)

        pendingInvite = parseInviteUri(intent.data)
        intent.getStringExtra(ShareReceiverActivity.EXTRA_PREFILL_NOTE)?.takeIf { it.isNotBlank() }?.let {
            pendingPrefillNote = it
        }
        extractSharedText(intent)?.let { pendingSharedText = it }
        captureNotificationIntent(intent)
        setContent { ReminderApp() }

        val uid = authClient.getCurrentUserId()
        if (uid != null) {
            SyncWorker.syncNow(this)
            TypeTaskCheckWorker.schedule(this)
            TypeTaskCheckWorker.runNow(this)
            WalkReminderWorker.schedule(this)
            lifecycleScope.launch(Dispatchers.IO) {
                locationReminderManager.restoreOnBoot(uid)
                TimeBasedTaskScheduler.restoreOnBoot(this@MainActivity, uid)
            }
        }
    }

    @Composable
    private fun ReminderApp() {
        var screen by remember { mutableStateOf(if (authClient.isLoggedIn()) "home" else "auth") }
        var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
        var archivedTasks by remember { mutableStateOf<List<Task>>(emptyList()) }
        var places by remember { mutableStateOf<List<SavedPlace>>(emptyList()) }
        var savedCards by remember { mutableStateOf<List<SavedCardEntity>>(emptyList()) }
        var contacts by remember { mutableStateOf<List<TrustedContact>>(emptyList()) }
        var displayPrefs by remember { mutableStateOf<List<ContactDisplayPref>>(emptyList()) }
        var selectedTaskId by remember { mutableStateOf<String?>(null) }
        var selectedCardId by remember { mutableStateOf<String?>(null) }
        var pendingCardDraft by remember { mutableStateOf<SavedCardDraft?>(null) }
        var taskEvents by remember { mutableStateOf<List<TaskEvent>>(emptyList()) }
        var pickedPlace by remember { mutableStateOf<SavedPlace?>(null) }
        var selectedPlaceId by remember { mutableStateOf<String?>(null) }
        var placeEditorId by remember { mutableStateOf<String?>(null) }
        var draftTitle by remember { mutableStateOf("") }
        var draftDescription by remember { mutableStateOf("") }
        var draftShoppingItems by remember { mutableStateOf("") }
        var draftShoppingItemsNeedConfirmation by remember { mutableStateOf(false) }
        var draftTaskType by remember { mutableStateOf(TaskType.SIMPLE) }
        var draftPriority by remember { mutableStateOf(TaskPriority.NO_RUSH) }
        var draftAssigneeId by remember { mutableStateOf("") }
        var draftPlaceMode by remember { mutableStateOf(PlaceMode.EXACT) }
        var draftPlaceTypeId by remember { mutableStateOf<String?>(null) }
        var draftDueDate by remember { mutableStateOf("") }
        var draftDueTime by remember { mutableStateOf("") }
        var draftIsEverywhere by remember { mutableStateOf(false) }
        var pendingCalendarTask by remember { mutableStateOf<Task?>(null) }
        var notifyOnFriendCancelMyTask by remember {
            mutableStateOf(prefs().getBoolean("notify_on_my_task_cancelled_by_friend", true))
        }
        var smartRemindersEnabled by remember {
            mutableStateOf(prefs().getBoolean("smart_reminders_enabled", true))
        }
        var suppressAtHome by remember {
            mutableStateOf(prefs().getBoolean("smart_suppress_at_home", true))
        }
        var suppressAtNight by remember {
            mutableStateOf(prefs().getBoolean("smart_suppress_at_night", true))
        }
        var localLearningEnabled by remember {
            mutableStateOf(prefs().getBoolean("local_learning_enabled", true))
        }
        var healthyLifeEnabled by remember {
            mutableStateOf(prefs().getBoolean("healthy_life_enabled", false))
        }
        var healthyFoodEnabled by remember {
            mutableStateOf(prefs().getBoolean("healthy_food_enabled", false))
        }
        var walkReminderEnabled by remember {
            mutableStateOf(prefs().getBoolean(WalkReminderWorker.PREF_WALK_REMINDER, false))
        }
        var usualShoppingSuggestionState by remember { mutableStateOf<UsualShoppingSuggestionState?>(null) }
        var foodHealthTags by remember { mutableStateOf<Map<String, FoodHealthEngine.HealthResult>>(emptyMap()) }
        var editingContact by remember { mutableStateOf<TrustedContact?>(null) }
        var placeTypeUsages by remember { mutableStateOf<List<PlaceTypeUsage>>(emptyList()) }
        var shoppingItemCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var currentTaskShoppingItems by remember { mutableStateOf<List<ShoppingListItem>>(emptyList()) }
        var autoOrderAvailableForTask by remember { mutableStateOf(false) }
        var taskShares by remember { mutableStateOf<List<com.davoyans.doinplace.data.model.TaskShare>>(emptyList()) }
        var recentRemoteItemIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var placeNotificationRules by remember { mutableStateOf<List<com.davoyans.doinplace.data.model.TaskPlaceNotificationRule>>(emptyList()) }
        var sharedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }

        // Undo-archive state — in-memory only; cleared after 60 s or on app restart.
        var undoTaskId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(undoTaskId) {
            if (undoTaskId != null) {
                kotlinx.coroutines.delay(60_000L)
                undoTaskId = null
            }
        }

        var showFirstPermissionDialog by remember {
            mutableStateOf(!prefs().getBoolean("first_permission_warning_shown", false))
        }
        var selectedLanguage by remember {
            mutableStateOf(prefs().getString("app_language", "system") ?: "system")
        }
        var sessionExpiredDialog by remember { mutableStateOf(false) }
        var sharedLocParsed by remember { mutableStateOf<LocationShareParser.ParsedLocation?>(null) }
        var sharedLocName by remember { mutableStateOf("") }
        var sharedLocResolving by remember { mutableStateOf(false) }
        var lastBackPressedAt by remember { mutableStateOf(0L) }
        var lastBackWarningScreen by remember { mutableStateOf<String?>(null) }

        fun resetCreateTaskDraft() {
            draftTitle = ""
            draftDescription = ""
            draftShoppingItems = ""
            draftShoppingItemsNeedConfirmation = false
            draftTaskType = TaskType.SIMPLE
            draftPriority = TaskPriority.NO_RUSH
            draftAssigneeId = ""
            draftPlaceMode = PlaceMode.EXACT
            draftPlaceTypeId = null
            draftDueDate = ""
            draftDueTime = ""
            draftIsEverywhere = false
        }

        LaunchedEffect(screen) {
            if (screen !in ROOT_BACK_EXIT_SCREENS || screen != lastBackWarningScreen) {
                lastBackWarningScreen = null
                lastBackPressedAt = 0L
            }
        }


        BackHandler(enabled = screen !in setOf("auth", "create_task", "create_recurring_task", "edit_recurring_task")) {
            when {
                screen in ROOT_BACK_EXIT_SCREENS -> {
                    val now = System.currentTimeMillis()
                    if (lastBackWarningScreen == screen && now - lastBackPressedAt <= BACK_EXIT_TIMEOUT_MS) {
                        DiagLog.d("BACK_NAV", "route=$screen action=exit_app")
                        moveTaskToBack(true)
                    } else {
                        lastBackWarningScreen = screen
                        lastBackPressedAt = now
                        DiagLog.d("BACK_NAV", "route=$screen action=first_back_exit_warning")
                        Toast.makeText(
                            this@MainActivity,
                            "Press back again to exit",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                screen == PlacesRoutes.TASK_PICKER -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_place_picker")
                    screen = "create_task"
                }
                screen == PlacesRoutes.EDITOR -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_place_editor")
                    placeEditorId = null
                    screen = "saved_places"
                }
                screen == PlacesRoutes.DETAIL -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_place_detail")
                    selectedPlaceId = null
                    screen = "saved_places"
                }
                screen == "task_detail" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_task_detail")
                    screen = "home"
                }
                screen == "card_detail" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_card_detail")
                    screen = "cards"
                }
                screen == "sort_list" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_sort_list")
                    screen = "task_detail"
                }
                screen == "cards_scan" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_card_scan")
                    screen = "cards"
                }
                screen == "cards_save" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_card_save")
                    pendingCardDraft = null
                    screen = "cards"
                }
                screen == "cards_edit" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_card_edit")
                    screen = "card_detail"
                }
                screen == "archived" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_archived")
                    screen = "settings"
                }
                screen == "permissions" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_permissions")
                    screen = "home"
                }
                screen == "edit_contact" -> {
                    DiagLog.d("BACK_NAV", "route=$screen action=close_edit_contact")
                    editingContact = null
                    screen = "contacts"
                }
                else -> {
                    DiagLog.d(
                        "BACK_NAV",
                        "route=$screen action=fallback_home blockedWrongAddNavigation=true"
                    )
                    screen = "home"
                }
            }
        }

        val imagePickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            sharedLocResolving = true
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ScreenshotTextExtractor().extractText(this@MainActivity, uri)
                }
                sharedLocResolving = false
                result.fold(
                    onSuccess = { text ->
                        when {
                            text.isBlank() ->
                                Toast.makeText(this@MainActivity, "No readable text found in this image.", Toast.LENGTH_LONG).show()
                            !isLatinDominant(text) ->
                                Toast.makeText(this@MainActivity, "Only English/Latin text can be recognized for now.", Toast.LENGTH_LONG).show()
                            else -> {
                                DiagLog.d("PREFILL", "openNewReminder sourceType=SCREENSHOT_OCR")
                                if (ShoppingItemCanonicalizer.looksLikeShoppingImport(text)) {
                                    draftTitle = "Shopping list"
                                    draftDescription = ""
                                    draftShoppingItems = text
                                    draftShoppingItemsNeedConfirmation = true
                                } else {
                                    draftTitle = ""
                                    draftDescription = text
                                    draftShoppingItems = ""
                                    draftShoppingItemsNeedConfirmation = false
                                }
                                DiagLog.d("BACK_NAV", "route=create_task action=open_create")
                                screen = "create_task"
                            }
                        }
                    },
                    onFailure = {
                        Toast.makeText(this@MainActivity, "Could not extract text. Try another screenshot.", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        val uid = authClient.getCurrentUserId() ?: ""

        LaunchedEffect(selectedTaskId, healthyFoodEnabled) {
            if (!healthyFoodEnabled || selectedTaskId == null) {
                foodHealthTags = emptyMap()
                return@LaunchedEffect
            }
            val items = kotlinx.coroutines.withContext(Dispatchers.IO) {
                db.shoppingListItemDao().getForTask(selectedTaskId!!)
            }
            foodHealthTags = kotlinx.coroutines.withContext(Dispatchers.IO) {
                items.associate { item ->
                    val itemName = item.canonicalOrText
                    val result = foodHealthEngine.lookupItem(itemName, uid, selectedLanguage.takeIf { it != "system" } ?: "en")
                    itemName.lowercase().trim() to result
                }
            }
        }

        LaunchedEffect(Unit) {
            if (intent.getBooleanExtra("open_usual_shopping", false)) {
                val ptKey = intent.getStringExtra(NotificationHelper.EXTRA_PLACE_TYPE_KEY) ?: return@LaunchedEffect
                val pName = intent.getStringExtra(NotificationHelper.EXTRA_PLACE_NAME) ?: ""
                val usualItems = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    usualShoppingEngine.getUsualItems(uid, ptKey)
                }
                if (usualItems.isNotEmpty()) {
                    usualShoppingSuggestionState = UsualShoppingSuggestionState(pName, ptKey, usualItems)
                }
            }
        }

        val cardPhotoPickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch {
                val payload = withContext(Dispatchers.IO) {
                    CardImageDecoder.decodeFromUri(this@MainActivity, uri)
                }
                if (payload == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.cards_photo_decode_failed), Toast.LENGTH_LONG).show()
                } else {
                    pendingCardDraft = createScannedCardDraft(payload)
                    screen = "cards_save"
                }
            }
        }

        LaunchedEffect(Unit) {
            savedCardRepo.observeAll().collectLatest { savedCards = it }
        }

        LaunchedEffect(uid) {
            if (uid.isBlank()) return@LaunchedEffect
            launch(Dispatchers.IO) {
                runCatching { contactRepo.deleteSelfContacts(uid) }
                val myEmail = authClient.getSession()?.email ?: ""
                if (myEmail.isNotBlank()) runCatching { contactRepo.deleteSelfContactsByEmail(uid, myEmail) }
            }
            launch {
                // Combine owned/assigned tasks with tasks shared via task_shares into one list.
                // Using combine() keeps both sources in sync — no race between the two flows.
                taskRepo.observeAll(uid)
                    .combine(db.taskDao().observeSharedWithMe(uid)) { owned, shared ->
                        val ownedIds = owned.map { it.id }.toSet()
                        val onlyShared = shared.filter { it.id !in ownedIds }
                        DiagLog.d("TASK_SYNC", "refresh owned=${owned.size} shared=${onlyShared.size}")
                        (owned + onlyShared).sortedByDescending { it.createdAt }
                    }
                    .collectLatest { tasks = it }
            }
            launch { taskRepo.observeArchived(uid).collectLatest { archivedTasks = it } }
            launch { placeRepo.observeAll(uid).collectLatest { places = it } }
            launch { contactRepo.observeAll(uid).collectLatest { contacts = it } }
            launch { contactDisplayRepo.observeAll(uid).collectLatest { displayPrefs = it } }
            launch { db.placeTypeUsageDao().observeAll(uid).collectLatest { placeTypeUsages = it } }
            launch {
                db.shoppingListItemDao().observeAllForUser(uid).collectLatest { items ->
                    shoppingItemCounts = items.groupBy { it.taskId }.mapValues { it.value.size }
                }
            }
            launch {
                db.taskShareDao().observeAll(uid).collectLatest { shares ->
                    taskShares = shares
                    sharedTaskIds = shares.filter { it.status == "ACTIVE" }
                        .map { it.taskId }.toSet()
                }
            }
        }

        val invite = pendingInvite
        LaunchedEffect(invite, uid) {
            if (invite == null || uid.isBlank()) return@LaunchedEffect
            if (invite.userId == uid) { pendingInvite = null; return@LaunchedEffect }
            val alreadyExists = contacts.any { it.contactUserId == invite.userId }
            if (!alreadyExists) {
                val contact = TrustedContact(
                    id = UUID.randomUUID().toString(),
                    userId = uid,
                    contactUserId = invite.userId,
                    contactEmail = invite.email,
                    contactDisplayName = invite.name,
                    status = ContactStatus.PENDING_SENT
                )
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    contactRepo.save(contact)
                    val sess = authClient.getSession()
                    runCatching {
                        supabase.sendContactInvite(
                            contact = contact,
                            requesterEmailSnapshot = sess?.email,
                            requesterDisplayNameSnapshot = sess?.displayName
                        )
                    }
                    val myName = sess?.displayName?.takeIf { it.isNotBlank() }
                        ?: sess?.email ?: "Someone"
                    val myEmail = sess?.email ?: ""
                    DiagLog.d("QR_REQUEST", "create owner=${invite.userId.take(8)} requester=${uid.take(8)} requesterEmail=${myEmail.ifBlank { "missing" }}")
                    runCatching { supabase.notifyConnectionRequest(invite.userId, uid, myName, myEmail) }
                        .onFailure { e -> DiagLog.e("CONTACTS", "notifyConnectionRequest failed: ${e.message?.take(60)}") }
                }
            }
            pendingInvite = null
            screen = "contacts"
        }

        LaunchedEffect(pendingNotificationRoute, uid) {
            if (uid.isBlank()) return@LaunchedEffect
            if (pendingNotificationRoute == NotificationHelper.ROUTE_CONTACTS) {
                pendingNotificationRoute = null
                screen = "contacts"
                DiagLog.d("NOTIFY_CLICK", "deepLink=doinplace://contacts opened=true")
                DiagLog.d("NAV_DEEPLINK", "route=contacts")
            }
        }

        // Open task detail when a task notification is tapped
        LaunchedEffect(pendingNotificationTaskId, pendingNotificationRoute, tasks) {
            if (pendingNotificationRoute != null &&
                pendingNotificationRoute !in setOf(NotificationHelper.ROUTE_TASK, NotificationHelper.ROUTE_SHOPPING_LIST)
            ) return@LaunchedEffect
            val taskId = pendingNotificationTaskId ?: return@LaunchedEffect
            if (uid.isBlank() || tasks.isEmpty()) return@LaunchedEffect
            val task = tasks.find { it.id == taskId } ?: return@LaunchedEffect
            pendingNotificationTaskId = null
            pendingNotificationRoute = null
            selectedTaskId = taskId
            screen = "task_detail"
            val route = if (task.taskType == TaskType.SHOPPING_LIST) {
                NotificationHelper.ROUTE_SHOPPING_LIST
            } else {
                NotificationHelper.ROUTE_TASK
            }
            DiagLog.d("NOTIFY_CLICK", "deepLink=doinplace://$route/$taskId opened=true")
            DiagLog.d("NAV_DEEPLINK", "route=$route taskId=${taskId.take(8)}")
        }

        // Handle prefill from ShareReceiverActivity (image OCR) and ProcessTextActivity (selected text)
        LaunchedEffect(pendingPrefillNote, uid) {
            val note = pendingPrefillNote ?: return@LaunchedEffect
            if (uid.isBlank()) return@LaunchedEffect
            pendingPrefillNote = null
            if (!isLatinDominant(note)) {
                Toast.makeText(this@MainActivity, "Only English/Latin text can be recognized for now.", Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
            DiagLog.d("PREFILL", "openNewReminder from pendingPrefillNote")
            if (ShoppingItemCanonicalizer.looksLikeShoppingImport(note)) {
                draftTitle = "Shopping list"
                draftDescription = ""
                draftShoppingItems = note
                draftShoppingItemsNeedConfirmation = true
            } else {
                draftTitle = ""
                draftDescription = note
                draftShoppingItems = ""
                draftShoppingItemsNeedConfirmation = false
            }
            DiagLog.d("BACK_NAV", "route=create_task action=open_create")
            screen = "create_task"
        }

        // Process shared text — routes via SharedInputRouter to place resolver, article summary, or note prefill
        LaunchedEffect(pendingSharedText, uid) {
            val text = pendingSharedText ?: return@LaunchedEffect
            if (uid.isBlank()) return@LaunchedEffect
            val route = SharedInputRouter.routeText(text)
            DiagLog.d("SHARE_ROUTER", "action=ACTION_SEND mime=text/plain route=${route::class.simpleName}")

            when (route) {
                is SharedInputRoute.ArticleUrl -> {
                    sharedLocResolving = true
                    try {
                        val summary = withContext(Dispatchers.IO) {
                            ArticleSummaryExtractor().summarize(route.url)
                        }
                        DiagLog.d("PREFILL", "openNewReminder sourceType=ARTICLE_URL")
                        draftTitle = summary.title?.trim() ?: ""
                        draftDescription = buildArticleBody(summary.summary, summary.url)
                        draftShoppingItems = ""
                        draftShoppingItemsNeedConfirmation = false
                        DiagLog.d("BACK_NAV", "route=create_task action=open_create")
                        screen = "create_task"
                    } finally {
                        sharedLocResolving = false
                        pendingSharedText = null
                    }
                }
                is SharedInputRoute.PlainTextNote -> {
                    DiagLog.d("PREFILL", "openNewReminder sourceType=PLAIN_TEXT_SHARE")
                    pendingSharedText = null
                    draftTitle = ""
                    draftDescription = route.text
                    draftShoppingItems = ""
                    draftShoppingItemsNeedConfirmation = false
                    DiagLog.d("BACK_NAV", "route=create_task action=open_create")
                    screen = "create_task"
                }
                else -> {
                    // PlaceLink or Unsupported — existing location resolver
                    // NOTE: pendingSharedText is nulled in finally — do NOT null it earlier
                    sharedLocResolving = true
                    var parsed: LocationShareParser.ParsedLocation? = null
                    try {
                        val resolved = withContext(Dispatchers.IO) {
                            kotlinx.coroutines.withTimeout(10_000L) { LocationShareParser.resolve(text) }
                        }
                        if (resolved != null && !resolved.nameOnly) {
                            parsed = resolved
                        } else {
                            val placeName = if (resolved?.nameOnly == true && resolved.name.isNotBlank())
                                resolved.name
                            else
                                LocationShareParser.extractPlaceNameForGeocoding(text)
                            DiagLog.d("LOCATION", "resolve null → place name fallback='$placeName'")
                            if (!placeName.isNullOrBlank()) {
                                parsed = withContext(Dispatchers.IO) {
                                    val userLoc = placeSearchRepo.getLastKnownLocation()
                                    val results = GeoapifyPlaceSearchProvider().search(
                                        placeName,
                                        userLat = userLoc?.first,
                                        userLng = userLoc?.second
                                    )
                                    results.getOrNull()?.firstOrNull()?.let { r ->
                                        DiagLog.d("LOCATION", "geocode result '${r.title}' lat=${r.latitude} lng=${r.longitude}")
                                        LocationShareParser.ParsedLocation(r.latitude, r.longitude, r.title)
                                    }
                                }
                                if (parsed == null) DiagLog.d("LOCATION", "geocode returned no results for '$placeName'")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        DiagLog.e("LOCATION", "resolve timeout")
                        parsed = null
                    } catch (e: CancellationException) {
                        DiagLog.d("LOCATION", "resolve cancelled")
                        throw e
                    } catch (e: Exception) {
                        DiagLog.e("LOCATION", "resolve+geocode exception", e)
                        parsed = null
                    } finally {
                        sharedLocResolving = false
                        pendingSharedText = null
                    }
                    if (parsed != null) {
                        sharedLocParsed = parsed
                        sharedLocName = parsed.name
                    } else if (route is SharedInputRoute.PlaceLink) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.share_place_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Monitor auth state — if session is cleared (both tokens expired), prompt re-login.
        LaunchedEffect(uid, screen) {
            if (uid.isBlank() || screen == "auth") return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(4_000)
                if (authClient.getAccessToken() == null) {
                    sessionExpiredDialog = true
                    break
                }
            }
        }

        RemindInPlaceTheme {

            // Compute permission state (recomputed on every permissionRefreshKey change)
            val hasFineLocation = remember(permissionRefreshKey) {
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            val hasCoarseLocation = remember(permissionRefreshKey) {
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            val hasRawBgLocation = remember(permissionRefreshKey) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                else true
            }
            val hasNotification = remember(permissionRefreshKey) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                else true
            }
            val hasActivityRecognition = remember(permissionRefreshKey) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                else true
            }
            val hasForegroundLocation = hasFineLocation || hasCoarseLocation
            val hasUsableBgLocation = hasForegroundLocation && hasRawBgLocation
            val permissionState = PermissionState(
                hasForegroundLocation = hasForegroundLocation,
                hasBackgroundLocation = hasUsableBgLocation,
                hasNotifications = hasNotification,
                hasActivityRecognition = hasActivityRecognition
            )
            val rootBottomBar: @Composable () -> Unit = {
                RootBottomBar(
                    currentScreen = screen,
                    onNavigate = { destination -> screen = destination }
                )
            }

            // First-install permission dialog (shown once if any permission is missing and user is logged in)
            if (showFirstPermissionDialog && uid.isNotBlank() && permissionState.anyMissing) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        showFirstPermissionDialog = false
                        prefs().edit().putBoolean("first_permission_warning_shown", true).apply()
                    },
                    title = { androidx.compose.material3.Text("Set up permissions for place reminders") },
                    text = {
                        androidx.compose.material3.Text(
                            "Do In Place needs notifications and location permissions to remind you when you arrive near saved places.\n\n" +
                            "Please enable the required settings now. You can change them later in Settings."
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showFirstPermissionDialog = false
                            prefs().edit().putBoolean("first_permission_warning_shown", true).apply()
                            screen = "permissions"
                        }) { androidx.compose.material3.Text("Open settings") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showFirstPermissionDialog = false
                            prefs().edit().putBoolean("first_permission_warning_shown", true).apply()
                        }) { androidx.compose.material3.Text("Later") }
                    }
                )
            }

            when (screen) {

                "auth" -> AuthScreen(
                    authClient = authClient,
                    onAuthenticated = {
                        val newUid = authClient.getCurrentUserId() ?: return@AuthScreen
                        val session = authClient.getSession() ?: return@AuthScreen
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                supabase.upsertUserProfile(newUid, session.email, session.displayName)
                            }
                        }
                        SyncWorker.syncNow(this@MainActivity)
                        TypeTaskCheckWorker.schedule(this@MainActivity)
                        screen = "home"
                    }
                )

                "home" -> HomeScreen(
                    tasks = tasks,
                    currentUserId = uid,
                    contacts = contacts,
                    displayPrefs = displayPrefs,
                    shoppingItemCounts = shoppingItemCounts,
                    sharedTaskIds = sharedTaskIds,
                    permissionState = permissionState,
                    onTaskClick = { id -> selectedTaskId = id; screen = "task_detail" },
                    onArchive = { task ->
                        DiagLog.d("TASK_ARCHIVE", "archive start taskId=${task.id.take(8)}")
                        undoTaskId = task.id
                        lifecycleScope.launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            taskRepo.archiveTask(task.id)
                            DiagLog.d("TASK_ARCHIVE", "archived local taskId=${task.id.take(8)}")
                            DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                            TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                            runCatching { supabase.archiveTaskRemote(task.id, true, now) }
                        }
                    },
                    onCancelTask = { task ->
                        DiagLog.d("TASK_SWIPE", "confirmArchive taskId=${task.id.take(8)}")
                        if (task.status == TaskStatus.PENDING_ACCEPTANCE) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                locationReminderManager.onTaskDeactivated(task.id)
                                NotificationHelper.cancel(this@MainActivity, task.id)
                                SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                taskRepo.updateStatus(task.id, TaskStatus.REJECTED, uid)
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.REJECTED.name) }
                            }
                        } else {
                            undoTaskId = task.id
                            lifecycleScope.launch(Dispatchers.IO) {
                                locationReminderManager.onTaskDeactivated(task.id)
                                NotificationHelper.cancel(this@MainActivity, task.id)
                                SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                DiagLog.d("TASK_ARCHIVE", "archive start taskId=${task.id.take(8)}")
                                val archiveNow = System.currentTimeMillis()
                                taskRepo.archiveTask(task.id)
                                DiagLog.d("TASK_ARCHIVE", "archived local taskId=${task.id.take(8)}")
                                runCatching { supabase.archiveTaskRemote(task.id, true, archiveNow) }
                            }
                        }
                    },
                    onUndo = {
                        val taskToRestore = undoTaskId
                        if (taskToRestore != null) {
                            undoTaskId = null
                            lifecycleScope.launch(Dispatchers.IO) {
                                val target = db.taskDao().getById(taskToRestore) ?: return@launch
                                taskRepo.restoreToActive(target, uid)
                                DiagLog.d("TASK_RESTORE", "restored taskId=${taskToRestore.take(8)}")
                                val restored = target.copy(status = TaskStatus.ACTIVE, archived = false)
                                if (restored.isEverywhere) {
                                    TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, restored)
                                } else {
                                    locationReminderManager.onTaskActivated(restored)
                                    DueAlarmScheduler.scheduleForTask(this@MainActivity, restored)
                                }
                                runCatching { supabase.updateTaskStatus(target.id, TaskStatus.ACTIVE.name) }
                            }
                        }
                    },
                    showUndo = undoTaskId != null,
                    onRefresh = { SyncWorker.syncNow(this@MainActivity) },
                    onCreateTask = {
                        DiagLog.d("BACK_NAV", "route=create_task action=open_create")
                        screen = "create_task"
                    },
                    onCreateRecurringTask = {
                        selectedTaskId = null
                        screen = "create_recurring_task"
                    },
                    onFromScreenshot = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOpenPlaces = { screen = "saved_places" },
                    onOpenContacts = { screen = "contacts" },
                    onOpenSettings = { screen = "settings" },
                    onOpenPermissions = { screen = "permissions" },
                    bottomBar = rootBottomBar
                )

                "create_task" -> CreateTaskScreen(
                    savedPlaces = places,
                    trustedContacts = contacts.filter { it.status == ContactStatus.ACCEPTED && it.contactUserId != uid },
                    currentUserId = uid,
                    displayPrefs = displayPrefs,
                    initialTitle = draftTitle,
                    initialDescription = draftDescription,
                    initialShoppingItems = draftShoppingItems,
                    initialPriority = draftPriority,
                    initialAssigneeId = draftAssigneeId.ifBlank { null },
                    initialPlaceMode = draftPlaceMode,
                    initialPlaceTypeId = draftPlaceTypeId,
                    initialDueDate = draftDueDate,
                    initialDueTime = draftDueTime,
                    initialIsEverywhere = draftIsEverywhere,
                    confirmLowConfidenceShoppingItems = draftShoppingItemsNeedConfirmation,
                    placeTypeUsages = placeTypeUsages,
                    onPlaceTypeUsed = { typeId ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dao = db.placeTypeUsageDao()
                            val existing = dao.get(uid, typeId)
                            val now = System.currentTimeMillis()
                            if (existing != null) {
                                dao.upsert(existing.copy(useCount = existing.useCount + 1, lastUsedAt = now))
                            } else {
                                dao.upsert(PlaceTypeUsage(
                                    id = UUID.randomUUID().toString(),
                                    userId = uid,
                                    placeTypeId = typeId,
                                    useCount = 1,
                                    lastUsedAt = now
                                ))
                            }
                        }
                    },
                    onGetSuggestions = { placeKey, placeTypeId, placeName ->
                        suggestionEngine.getSuggestions(uid, placeKey, placeTypeId, placeName)
                    },
                    onSuggestionAccepted = { placeKey, placeTypeId, text ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { suggestionEngine.recordAccepted(uid, placeKey, placeTypeId, text) }
                        }
                    },
                    onGetShoppingSuggestions = { placeKey, placeTypeId ->
                        suggestionEngine.getShoppingSuggestions(uid, placeKey, placeTypeId)
                    },
                    onShoppingItemAdded = { placeKey, placeTypeId, text ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { suggestionEngine.recordShoppingItemAdded(uid, placeKey, placeTypeId, text) }
                        }
                    },
                    onSave = { task, shoppingItems ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            taskRepo.save(task)
                            if (shoppingItems.isNotEmpty()) {
                                db.shoppingListItemDao().upsertAll(shoppingItems)
                                // Learn from each saved shopping item
                                if (localLearningEnabled) {
                                    val placeKey = when (task.placeMode) {
                                        PlaceMode.EXACT -> task.placeId ?: ""
                                        PlaceMode.TYPE  -> ""
                                    }
                                    for (item in shoppingItems) {
                                        runCatching {
                                            suggestionEngine.recordShoppingItemAdded(
                                                uid, placeKey, task.placeTypeId, item.canonicalOrText
                                            )
                                        }
                                    }
                                }
                            }
                            if (task.status == TaskStatus.ACTIVE) {
                                if (task.isEverywhere) {
                                    DiagLog.d("EVERYWHERE", "scheduleForTask taskId=${task.id.take(8)}")
                                    TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, task)
                                } else {
                                    locationReminderManager.onTaskActivated(task)
                                    DueAlarmScheduler.scheduleForTask(this@MainActivity, task)
                                }
                            }
                            if (localLearningEnabled) {
                                runCatching { learningEngine.recordTaskCreated(uid, task) }
                                runCatching {
                                    val placeKey = when (task.placeMode) {
                                        PlaceMode.EXACT -> learningEngine.buildPlaceKey(task)
                                        PlaceMode.TYPE  -> ""
                                    }
                                    suggestionEngine.recordTaskCreated(uid, placeKey, task.placeTypeId, task.title)
                                }
                            }
                            // Record place type usage on save
                            if (task.placeTypeId != null) {
                                val dao = db.placeTypeUsageDao()
                                val existing = dao.get(uid, task.placeTypeId)
                                val now = System.currentTimeMillis()
                                if (existing != null) {
                                    dao.upsert(existing.copy(useCount = existing.useCount + 1, lastUsedAt = now))
                                } else {
                                    dao.upsert(PlaceTypeUsage(
                                        id = UUID.randomUUID().toString(),
                                        userId = uid,
                                        placeTypeId = task.placeTypeId,
                                        useCount = 1,
                                        lastUsedAt = now
                                    ))
                                }
                            }
                                if (task.pendingSync) {
                                    runCatching {
                                        supabase.pushTask(task)
                                        if (shoppingItems.isNotEmpty()) {
                                            supabase.pushShoppingItems(task.id, shoppingItems)
                                        }
                                    }.onSuccess {
                                        DiagLog.d(
                                            "ASSIGN_SEND_DB",
                                            "taskId=${task.id.take(8)} createdBy=${task.createdByUserId.take(8)} assignedTo=${task.assignedToUserId.take(8)} status=${task.status}"
                                        )
                                        taskRepo.markSynced(task.id)
                                    }
                                // Notify the assignee via FCM (not the actor)
                                val assigneeId = task.assignedToUserId
                                if (assigneeId.isNotBlank() && assigneeId != uid) {
                                    val actorName = authClient.getSession()?.let { s ->
                                        s.displayName?.takeIf { it.isNotBlank() } ?: s.email
                                    } ?: "Someone"
                                    if (task.taskType == TaskType.SHOPPING_LIST) {
                                        DiagLog.d(
                                            "ASSIGN_SEND",
                                            "taskId=${task.id.take(8)} type=${task.taskType} assignedTo=${assigneeId.take(8)} itemCount=${shoppingItems.size}"
                                        )
                                    }
                                    DiagLog.d("NOTIFY_ROUTE", "event=TASK_ASSIGNED actor=${uid.take(8)} recipient=${assigneeId.take(8)} taskId=${task.id.take(8)}")
                                    runCatching {
                                        supabase.notifyTaskEvent(
                                            eventType = "new_task",
                                            taskId = task.id,
                                            taskTitle = task.title,
                                            actorUserId = uid,
                                            actorName = actorName,
                                            actorEmail = authClient.getSession()?.email,
                                            creatorUserId = uid,
                                            targetUserId = assigneeId,
                                            taskType = task.taskType
                                        )
                                    }.onFailure { e -> DiagLog.e("NOTIFY_ROUTE", "new_task notify failed: ${e.message?.take(60)}") }
                                } else {
                                    DiagLog.d("NOTIFY_ROUTE", "skip self-notification actor=${uid.take(8)} recipient=${assigneeId.take(8)} event=TASK_ASSIGNED")
                                }
                            } else if (shoppingItems.isNotEmpty()) {
                                runCatching { supabase.pushShoppingItems(task.id, shoppingItems) }
                            }
                        }
                        // If a TYPE task just became active, check immediately in case we're already near a match
                        if (task.placeMode == PlaceMode.TYPE && task.status == TaskStatus.ACTIVE) {
                            TypeTaskCheckWorker.runNow(this@MainActivity)
                        }
                        resetCreateTaskDraft()
                        screen = "home"
                    },
                    onPickPlace = { t, d, si, tt, pr, aid, pm, ptid, dd, dt ->
                        draftTitle = t; draftDescription = d
                        draftShoppingItems = si; draftTaskType = tt
                        draftPriority = pr; draftAssigneeId = aid
                        draftPlaceMode = pm; draftPlaceTypeId = ptid
                        draftDueDate = dd; draftDueTime = dt
                        screen = PlacesRoutes.pickForTaskRoute()
                    },
                    onBack = {
                        DiagLog.d("BACK_NAV", "route=create_task action=close_add_screen")
                        resetCreateTaskDraft()
                        screen = "home"
                    },
                    pendingPickedPlace = pickedPlace.also { pickedPlace = null },
                    onSuggestPriority = if (localLearningEnabled) { placeId, placeName, lat, lng, title ->
                        val fakeTask = Task(
                            id = "", title = title, createdByUserId = uid, assignedToUserId = uid,
                            placeId = placeId, placeName = placeName,
                            latitude = lat, longitude = lng, radiusMeters = 100
                        )
                        learningEngine.suggestPriority(uid, fakeTask)
                    } else null
                )

                "create_recurring_task" -> AddRecurringTaskScreen(
                    currentUserId = uid,
                    onSave = { task ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            taskRepo.save(task)
                            TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, task)
                            DiagLog.d("RECURRING", "schedule next id=${task.id.take(8)} date=${task.activeFromDate}")
                        }
                        screen = "home"
                    },
                    onBack = { screen = "home" }
                )

                PlacesRoutes.TASK_PICKER -> PlacePickerScreen(
                    savedPlaces = places,
                    userId = uid,
                    onPlacePicked = { place ->
                        lifecycleScope.launch(Dispatchers.IO) { placeRepo.save(place) }
                        pickedPlace = place
                        screen = "create_task"
                    },
                    onDeletePlace = { place ->
                        lifecycleScope.launch(Dispatchers.IO) { placeRepo.softDelete(place.id) }
                    },
                    onBack = { screen = "create_task" },
                    placeSearchRepository = placeSearchRepo
                )

                "saved_places" -> SavedPlacesScreen(
                    places = places,
                    onOpenPlace = { place ->
                        selectedPlaceId = place.id
                        screen = PlacesRoutes.DETAIL
                    },
                    onDeletePlace = { place ->
                        lifecycleScope.launch(Dispatchers.IO) { placeRepo.softDelete(place.id) }
                    },
                    onAddPlace = {
                        placeEditorId = null
                        screen = PlacesRoutes.addFromPlacesRoute()
                    },
                    onRefresh = { SyncWorker.syncNow(this@MainActivity) },
                    onBack = { screen = "home" },
                    bottomBar = rootBottomBar
                )

                PlacesRoutes.DETAIL -> {
                    val place = places.find { it.id == selectedPlaceId }
                    if (place == null) {
                        screen = "saved_places"
                    } else {
                        PlaceDetailScreen(
                            place = place,
                            onEdit = {
                                placeEditorId = place.id
                                screen = PlacesRoutes.EDITOR
                            },
                            onDelete = { target ->
                                lifecycleScope.launch(Dispatchers.IO) { placeRepo.softDelete(target.id) }
                                selectedPlaceId = null
                                screen = "saved_places"
                            },
                            onBack = { screen = "saved_places" }
                        )
                    }
                }

                PlacesRoutes.EDITOR -> {
                    val editingPlace = places.find { it.id == placeEditorId }
                    PlacePickerScreen(
                        savedPlaces = places,
                        userId = uid,
                        onPlacePicked = { place ->
                            lifecycleScope.launch(Dispatchers.IO) { placeRepo.save(place) }
                            placeEditorId = null
                            selectedPlaceId = place.id
                            screen = PlacesRoutes.DETAIL
                        },
                        onBack = {
                            placeEditorId = null
                            screen = "saved_places"
                        },
                        placeSearchRepository = placeSearchRepo,
                        initialPlace = editingPlace,
                        titleText = if (editingPlace == null) getString(R.string.add_place) else getString(R.string.edit_place),
                        showSavedPlacesSection = false
                    )
                }

                "cards" -> CardsTabScreen(
                    cards = savedCards,
                    onOpenCard = { card ->
                        selectedCardId = card.id
                        screen = "card_detail"
                    },
                    onEditCard = { card ->
                        selectedCardId = card.id
                        screen = "cards_edit"
                    },
                    onScanCard = { screen = "cards_scan" },
                    onAddFromPhoto = {
                        cardPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onAddManual = {
                        pendingCardDraft = createManualCardDraft()
                        screen = "cards_save"
                    },
                    bottomBar = rootBottomBar
                )

                "cards_scan" -> ScanCardScreen(
                    onDetected = { payload ->
                        DiagLog.d(
                            "CARDS_SCAN",
                            "detected type=${payload.codeType} format=${payload.barcodeFormat ?: "unknown"} value=${maskCardCode(payload.codeValue)}"
                        )
                        pendingCardDraft = createScannedCardDraft(payload)
                        screen = "cards_save"
                    },
                    onAddManual = {
                        pendingCardDraft = createManualCardDraft()
                        screen = "cards_save"
                    },
                    onBack = { screen = "cards" }
                )

                "cards_save" -> {
                    val draft = pendingCardDraft ?: createManualCardDraft().also { pendingCardDraft = it }
                    SaveCardScreen(
                        initialDraft = draft,
                        onSaveCard = { updatedDraft, allowDuplicate ->
                            val entity = updatedDraft.toEntity()
                            DiagLog.d(
                                "CARDS_SAVE",
                                "attempt id=${entity.id.take(8)} type=${entity.codeType} format=${entity.barcodeFormat ?: "unknown"} value=${maskCardCode(entity.codeValue)} allowDuplicate=$allowDuplicate"
                            )
                            withContext(Dispatchers.IO) {
                                savedCardRepo.save(entity, allowDuplicate)
                            }
                        },
                        onOpenExisting = { existing ->
                            pendingCardDraft = draft
                            selectedCardId = existing.id
                            screen = "card_detail"
                        },
                        onSaved = { cardId ->
                            pendingCardDraft = null
                            selectedCardId = cardId
                            screen = "card_detail"
                        },
                        onBack = {
                            pendingCardDraft = null
                            screen = "cards"
                        }
                    )
                }

                "card_detail" -> {
                    val card = savedCards.find { it.id == selectedCardId }
                    if (card == null) { screen = "cards"; return@RemindInPlaceTheme }
                    CardDetailScreen(
                        card = card,
                        onEdit = {
                            selectedCardId = card.id
                            screen = "cards_edit"
                        },
                        onBack = { screen = "cards" }
                    )
                }

                "cards_edit" -> {
                    val card = savedCards.find { it.id == selectedCardId }
                    if (card == null) { screen = "cards"; return@RemindInPlaceTheme }
                    EditCardScreen(
                        card = card,
                        onSave = { draft ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                savedCardRepo.save(
                                    draft.toEntity().copy(
                                        id = card.id,
                                        createdAt = card.createdAt,
                                        updatedAt = System.currentTimeMillis()
                                    ),
                                    allowDuplicate = true
                                )
                            }
                            screen = "card_detail"
                        },
                        onDelete = { target ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                savedCardRepo.deleteById(target.id)
                            }
                            selectedCardId = null
                            screen = "cards"
                        },
                        onBack = { screen = "card_detail" }
                    )
                }

                "task_detail" -> {
                    val task = tasks.find { it.id == selectedTaskId }
                    if (task == null) { screen = "home"; return@RemindInPlaceTheme }
                    LaunchedEffect(task.id) {
                        db.taskEventDao().observeForTask(task.id).collectLatest { taskEvents = it }
                    }
                    LaunchedEffect(task.id) {
                        db.shoppingListItemDao().observeForTask(task.id).collectLatest { items ->
                            currentTaskShoppingItems = items
                            if (task.taskType == TaskType.SHOPPING_LIST && task.placeId != null) {
                                val placeKey = shoppingOrderEngine.buildPlaceKey(task)
                                autoOrderAvailableForTask = shoppingOrderEngine.hasLearnedOrder(uid, placeKey)
                            }
                        }
                    }
                            LaunchedEffect(task.id, task.taskType, task.status, currentTaskShoppingItems.size) {
                        if (task.taskType == TaskType.SHOPPING_LIST) {
                            DiagLog.d(
                                "SHOP_UI",
                                "open taskId=${task.id.take(8)} type=${task.taskType} source=task_detail status=${task.status} visibleItems=${currentTaskShoppingItems.size}"
                            )
                            if (task.assignedToUserId == uid) {
                                DiagLog.d(
                                    "SHOP_ASSIGN_TEST",
                                    "user=${uid.take(8)} taskId=${task.id.take(8)} localTaskId=${task.id.take(8)} remoteTaskId=${task.id.take(8)} idsMatch=true"
                                )
                            }
                        }
                    }
                    LaunchedEffect(task.id) {
                        db.taskPlaceNotificationRuleDao().observeForTask(task.id).collectLatest {
                            placeNotificationRules = it.filter { r -> r.active }
                        }
                    }
                    // Poll for remote shopping item updates while shared list is open
                    val isSharedList = task.taskType == TaskType.SHOPPING_LIST &&
                        taskShares.any { it.taskId == task.id && it.status == "ACTIVE" }
                    LaunchedEffect(task.id, isSharedList) {
                        if (!isSharedList) return@LaunchedEffect
                        DiagLog.d("SHOP_REALTIME", "subscribed taskId=${task.id.take(8)}")
                        while (true) {
                            kotlinx.coroutines.delay(5_000L)
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                runCatching {
                                    val remoteItems = supabase.fetchShoppingItemsForTask(task.id)
                                    val pending = db.shoppingListItemDao().getPendingSync()
                                        .map { it.id }.toSet()
                                    val localIds = currentTaskShoppingItems.map { it.id }.toSet()
                                    val newFromRemote = mutableSetOf<String>()
                                    val toUpdate = remoteItems.mapIndexedNotNull { i, rI ->
                                        val iId = rI["id"] as? String ?: return@mapIndexedNotNull null
                                        if (iId in pending) return@mapIndexedNotNull null // preserve local changes
                                        val localItem = currentTaskShoppingItems.find { it.id == iId }
                                        val remoteUpd = (rI["updated_at"] as? Long) ?: 0L
                                        if (localItem != null && localItem.updatedAt >= remoteUpd) return@mapIndexedNotNull null
                                        val addedBy = rI["added_by_user_id"] as? String
                                        if (iId !in localIds && addedBy != null && addedBy != uid) {
                                            newFromRemote.add(iId)
                                        }
                                        val remoteCanonical = rI["canonical_name"] as? String
                                        val remoteText = rI["text"] as? String ?: ""
                                        val canonicalText = remoteCanonical?.takeIf { it.isNotBlank() } ?: remoteText
                                        ShoppingListItem(
                                            id = iId,
                                            taskId = task.id,
                                            text = canonicalText,
                                            normalizedText = (rI["normalized_text"] as? String)
                                                ?.takeIf { it.isNotBlank() }
                                                ?: ShoppingItemCanonicalizer.normalize(canonicalText),
                                            rawText = rI["raw_text"] as? String ?: remoteText,
                                            canonicalName = canonicalText,
                                            orderIndex = (rI["order_index"] as? Long)?.toInt() ?: i,
                                            checked = rI["checked"] as? Boolean ?: false,
                                            checkedByUserId = rI["checked_by_user_id"] as? String,
                                            checkedAt = rI["checked_at"] as? Long,
                                            updatedByUserId = rI["updated_by_user_id"] as? String,
                                            syncStatus = "SYNCED",
                                            createdAt = (rI["created_at"] as? Long) ?: System.currentTimeMillis(),
                                            updatedAt = (rI["updated_at"] as? Long) ?: System.currentTimeMillis(),
                                            addedByUserId = addedBy,
                                            addedByDisplayName = rI["added_by_display_name"] as? String,
                                            addedAt = rI["added_at"] as? Long,
                                            originColorKey = rI["origin_color_key"] as? String
                                        )
                                    }
                                    if (toUpdate.isNotEmpty()) {
                                        db.shoppingListItemDao().upsertAll(toUpdate)
                                        DiagLog.d("SHOP_REALTIME", "remote update itemCount=${toUpdate.size} taskId=${task.id.take(8)}")
                                    }
                                    if (newFromRemote.isNotEmpty()) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            recentRemoteItemIds = recentRemoteItemIds + newFromRemote
                                        }
                                        kotlinx.coroutines.delay(4_000L)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            recentRemoteItemIds = recentRemoteItemIds - newFromRemote
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TaskDetailScreen(
                        task = task,
                        events = taskEvents,
                        currentUserId = uid,
                        contacts = contacts.filter { it.status == ContactStatus.ACCEPTED && it.contactUserId != uid },
                        displayPrefs = displayPrefs,
                        shoppingItems = currentTaskShoppingItems,
                        taskShares = taskShares,
                        onShoppingItemChecked = { itemId, checked ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                val displayName = run {
                                    val me = contacts.find { it.contactUserId == uid }
                                    me?.contactDisplayName?.ifBlank { null }
                                        ?: authClient.getSession()?.displayName
                                        ?: "Me"
                                }
                                db.shoppingListItemDao().updateCheckedWithUser(
                                    id = itemId,
                                    checked = checked,
                                    checkedByUserId = if (checked) uid else null,
                                    checkedByDisplayName = if (checked) displayName else null,
                                    checkedAt = if (checked) now else null,
                                    updatedByUserId = uid,
                                    now = now
                                )
                                DiagLog.d("SHOP_SYNC", "item checked itemId=${itemId.take(8)} by=${uid.take(8)}")
                                // Trigger immediate background sync for shared list updates
                                val item = db.shoppingListItemDao().getForTask(
                                    currentTaskShoppingItems.find { it.id == itemId }?.taskId ?: ""
                                ).firstOrNull()
                                if (item != null) {
                                    runCatching {
                                        supabase.updateShoppingItemChecked(
                                            itemId = itemId,
                                            taskId = item.taskId,
                                            checked = checked,
                                            checkedByUserId = if (checked) uid else null,
                                            checkedAt = if (checked) now else null,
                                            updatedByUserId = uid,
                                            updatedAt = now
                                        )
                                        db.shoppingListItemDao().markSynced(itemId)
                                        // Notify the other participant(s) via FCM so their device syncs immediately
                                        val taskForNotify = db.taskDao().getById(item.taskId)
                                        if (taskForNotify != null) {
                                            val targetUserId = if (taskForNotify.createdByUserId != uid) {
                                                taskForNotify.createdByUserId
                                            } else {
                                                // We are the creator — notify the first active shared user
                                                db.taskShareDao().getForTask(item.taskId)
                                                    .firstOrNull { it.sharedWithUserId != uid && it.status == "ACTIVE" }
                                                    ?.sharedWithUserId
                                            }
                                            if (targetUserId != null) {
                                                runCatching {
                                                    supabase.notifyTaskEvent(
                                                        eventType = "shopping_item_updated",
                                                        taskId = item.taskId,
                                                        taskTitle = taskForNotify.title,
                                                        actorUserId = uid,
                                                        actorName = displayName,
                                                        creatorUserId = uid,
                                                        targetUserId = targetUserId,
                                                        taskType = taskForNotify.taskType
                                                    )
                                                }
                                            }
                                        }
                                    }.onFailure {
                                        DiagLog.e("SHOP_SYNC", "immediate sync failed, will retry: ${it.message?.take(60)}")
                                    }
                                }
                            }
                        },
                        onShareList = { selectedContacts ->
                            if (selectedContacts.isNotEmpty()) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    DiagLog.d("SHOP_SHARE", "share start taskId=${task.id.take(8)} selectedCount=${selectedContacts.size}")
                                    // Push parent task first so FK constraint on task_shares is satisfied
                                    runCatching { supabase.pushTask(task) }
                                        .onFailure { e -> DiagLog.e("SHOP_SHARE", "pushTask failed: ${e.message?.take(60)}") }
                                    // Push shopping items so recipient sees them immediately
                                    if (task.taskType == TaskType.SHOPPING_LIST) {
                                        runCatching {
                                            val items = db.shoppingListItemDao().getForTask(task.id)
                                            if (items.isNotEmpty()) supabase.pushShoppingItems(task.id, items)
                                        }.onFailure { e -> DiagLog.e("SHOP_SHARE", "pushShoppingItems failed: ${e.message?.take(60)}") }
                                    }
                                    val actorName = authClient.getSession()?.displayName?.takeIf { it.isNotBlank() }
                                        ?: authClient.getSession()?.email
                                        ?: "Someone"
                                    for (contact in selectedContacts) {
                                        val shareId = UUID.randomUUID().toString()
                                        val now = System.currentTimeMillis()
                                        val share = com.davoyans.doinplace.data.model.TaskShare(
                                            id = shareId,
                                            taskId = task.id,
                                            ownerUserId = uid,
                                            sharedWithUserId = contact.contactUserId,
                                            sharedWithDisplayName = contact.contactDisplayName.ifBlank { contact.contactEmail },
                                            status = "ACTIVE",
                                            createdAt = now,
                                            updatedAt = now
                                        )
                                        db.taskShareDao().upsert(share)
                                        runCatching {
                                            supabase.createTaskShare(shareId, task.id, uid, contact.contactUserId, now)
                                            DiagLog.d("TASK_SHARE", "created taskId=${task.id.take(8)} sharedWith=${contact.contactUserId.take(8)} permission=EDIT")
                                        }.onFailure { e ->
                                            DiagLog.e("SHOP_SHARE", "share failed sharedWith=${contact.contactUserId.take(8)}: ${e.message?.take(60)}")
                                        }
                                        // Notify UserB via FCM so they sync immediately and see the shared task
                                        runCatching {
                                            supabase.notifyTaskEvent(
                                                eventType = "task_shared",
                                                taskId = task.id,
                                                taskTitle = task.title,
                                                actorUserId = uid,
                                                actorName = actorName,
                                                creatorUserId = uid,
                                                targetUserId = contact.contactUserId,
                                                taskType = task.taskType
                                            )
                                            DiagLog.d("TASK_SHARE", "FCM sent sharedWith=${contact.contactUserId.take(8)}")
                                        }.onFailure { e ->
                                            DiagLog.e("TASK_SHARE", "FCM notify failed: ${e.message?.take(60)}")
                                        }
                                    }
                                }
                            }
                        },
                        onSortList = { screen = "sort_list" },
                        onAutoOrder = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val reordered = shoppingOrderEngine.autoOrder(uid, task, currentTaskShoppingItems)
                                db.shoppingListItemDao().upsertAll(reordered)
                            }
                        },
                        autoOrderAvailable = autoOrderAvailableForTask,
                        foodHealthTags = foodHealthTags,
                        onChecklistItemToggle = { newJson ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                taskRepo.updateChecklist(task.id, newJson)
                            }
                        },
                        onDone = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val doneAt = System.currentTimeMillis()
                                if (task.taskType == TaskType.SHOPPING_LIST && !task.placeId.isNullOrBlank()) {
                                    val items = db.shoppingListItemDao().getForTask(task.id)
                                    if (items.any { it.checked }) {
                                        val placeKey = shoppingOrderEngine.buildPlaceKey(task)
                                        DiagLog.d("SHOP_ORDER", "learn start taskId=${task.id.take(8)} placeKey=${placeKey.take(24)}")
                                        val checkedByOrder = items.filter { it.checked }.sortedBy { it.updatedAt }
                                        val unchecked = items.filter { !it.checked }.sortedBy { it.orderIndex }
                                        runCatching { shoppingOrderEngine.saveOrder(uid, task, checkedByOrder + unchecked) }
                                    }
                                    // Learn usual shopping habits
                                    val placeTypeKey = task.placeTypeId
                                        ?: if (!task.placeTypeName.isNullOrBlank()) task.placeTypeName!!.lowercase().replace(" ", "_") else null
                                    if (placeTypeKey != null) {
                                        runCatching {
                                            usualShoppingEngine.recordCompletedSession(
                                                uid, task.id, placeTypeKey,
                                                task.placeName, db.shoppingListItemDao().getForTask(task.id)
                                            )
                                        }
                                    }
                                } else if (task.taskType == TaskType.SHOPPING_LIST) {
                                    DiagLog.d("SHOP_ORDER", "skip reason=no exact place taskId=${task.id.take(8)}")
                                }
                                if (task.isRecurringTask()) {
                                    TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                    val nextDate = RecurrenceCalculator.nextOccurrenceDate(
                                        task, LocalDate.now()
                                    )
                                    if (nextDate != null) {
                                        db.taskEventDao().insert(
                                            TaskEvent(
                                                id = UUID.randomUUID().toString(),
                                                taskId = task.id,
                                                type = TaskEventType.DONE,
                                                actorUserId = uid,
                                                createdAt = doneAt,
                                                synced = false
                                            )
                                        )
                                        val nextTask = task.copy(
                                            status = TaskStatus.ACTIVE,
                                            activeFromDate = nextDate.toString(),
                                            lastCompletedAt = doneAt,
                                            updatedAt = doneAt,
                                            archived = false,
                                            pendingSync = false
                                        )
                                        taskRepo.save(nextTask)
                                        TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, nextTask)
                                        DiagLog.d("RECURRING", "complete id=${task.id.take(8)} nextDueDate=$nextDate")
                                        DiagLog.d("RECURRING", "schedule next id=${task.id.take(8)} date=$nextDate")
                                    }
                                } else {
                                    taskRepo.updateStatus(task.id, TaskStatus.DONE, uid)
                                    if (task.isEverywhere) {
                                        TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                    } else {
                                        locationReminderManager.onTaskDeactivated(task.id)
                                    }
                                    runCatching { supabase.updateTaskStatus(task.id, TaskStatus.DONE.name) }
                                }
                                if (!task.isRecurringTask()) {
                                    NotificationHelper.cancel(this@MainActivity, task.id)
                                    SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                } else {
                                    NotificationHelper.cancel(this@MainActivity, task.id)
                                    SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                if (localLearningEnabled) {
                                    runCatching { learningEngine.recordTaskCompleted(uid, task) }
                                }
                            }
                            if (task.isEverywhere) {
                                pendingCalendarTask = task
                            }
                            screen = "home"
                        },
                        onForceDone = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val uncheckedCount = currentTaskShoppingItems.count { !it.checked }
                                taskRepo.markForceDone(task.id, uid, uncheckedCount)
                                if (task.isEverywhere) {
                                    TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                } else {
                                    locationReminderManager.onTaskDeactivated(task.id)
                                }
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.DONE.name) }
                                NotificationHelper.cancel(this@MainActivity, task.id)
                                SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                if (localLearningEnabled) {
                                    runCatching { learningEngine.recordTaskCompleted(uid, task) }
                                }
                            }
                            screen = "home"
                        },
                        onCancel = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                taskRepo.updateStatus(task.id, TaskStatus.CANCELLED, uid)
                                locationReminderManager.onTaskDeactivated(task.id)
                                NotificationHelper.cancel(this@MainActivity, task.id)
                                SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.CANCELLED.name) }
                                if (localLearningEnabled) {
                                    runCatching { learningEngine.recordTaskCancelled(uid, task) }
                                }
                            }
                            screen = "home"
                        },
                        onReassign = { newAssigneeId ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val newStatus = if (newAssigneeId == uid) TaskStatus.ACTIVE
                                               else TaskStatus.PENDING_ACCEPTANCE
                                val updated = task.copy(
                                    assignedToUserId = newAssigneeId,
                                    status = newStatus,
                                    updatedAt = System.currentTimeMillis(),
                                    pendingSync = newAssigneeId != uid
                                )
                                taskRepo.save(updated)
                                if (newAssigneeId == uid) {
                                    if (updated.isEverywhere) {
                                        TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, updated)
                                    } else {
                                        locationReminderManager.onTaskActivated(updated)
                                        DueAlarmScheduler.scheduleForTask(this@MainActivity, updated)
                                    }
                                } else {
                                    locationReminderManager.onTaskDeactivated(task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                    TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                val shoppingItems = if (updated.taskType == TaskType.SHOPPING_LIST) {
                                    db.shoppingListItemDao().getForTask(updated.id)
                                } else {
                                    emptyList()
                                }
                                runCatching {
                                    supabase.pushTask(updated)
                                    if (shoppingItems.isNotEmpty()) {
                                        supabase.pushShoppingItems(updated.id, shoppingItems)
                                    }
                                }
                                if (newAssigneeId != uid) {
                                    val actorName = authClient.getSession()?.let { s ->
                                        s.displayName?.takeIf { it.isNotBlank() } ?: s.email
                                    } ?: "Someone"
                                    DiagLog.d(
                                        "ASSIGN_SEND",
                                        "taskId=${updated.id.take(8)} type=${updated.taskType} assignedTo=${newAssigneeId.take(8)} itemCount=${shoppingItems.size}"
                                    )
                                    DiagLog.d("NOTIFY_ROUTE", "event=TASK_ASSIGNED actor=${uid.take(8)} recipient=${newAssigneeId.take(8)} taskId=${updated.id.take(8)}")
                                    runCatching {
                                        supabase.notifyTaskEvent(
                                            eventType = "new_task",
                                            taskId = updated.id,
                                            taskTitle = updated.title,
                                            actorUserId = uid,
                                            actorName = actorName,
                                            actorEmail = authClient.getSession()?.email,
                                            creatorUserId = uid,
                                            targetUserId = newAssigneeId,
                                            taskType = updated.taskType
                                        )
                                    }
                                }
                            }
                            screen = "home"
                        },
                        onEditRecurring = if (task.isRecurringTask()) {
                            {
                                selectedTaskId = task.id
                                screen = "edit_recurring_task"
                            }
                        } else null,
                        onAccept = { arrivalShare ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                DiagLog.d("ASSIGN_ACCEPT", "taskId=${task.id.take(8)} status=ACCEPTED")
                                val updated = task.copy(
                                    status = TaskStatus.ACTIVE,
                                    arrivalShareAllowed = arrivalShare,
                                    pendingSync = true
                                )
                                taskRepo.save(updated)
                                if (updated.isEverywhere) {
                                    TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, updated)
                                } else {
                                    locationReminderManager.onTaskActivated(updated)
                                    DueAlarmScheduler.scheduleForTask(this@MainActivity, updated)
                                }
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.ACTIVE.name) }
                                db.taskEventDao().insert(TaskEvent(
                                    id = UUID.randomUUID().toString(),
                                    taskId = task.id,
                                    type = TaskEventType.ACCEPTED,
                                    actorUserId = uid,
                                    synced = false
                                ))
                                SyncWorker.syncNow(this@MainActivity)
                            }
                            screen = "home"
                        },
                        onReject = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                taskRepo.updateStatus(task.id, TaskStatus.REJECTED, uid)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.REJECTED.name) }
                            }
                            screen = "home"
                        },
                        recentRemoteItemIds = recentRemoteItemIds,
                        placeNotificationRules = placeNotificationRules,
                        onRemovePlaceRule = { ruleId ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.taskPlaceNotificationRuleDao().deactivateRule(ruleId)
                            }
                        },
                        onBack = { screen = "home" }
                    )
                }

                "edit_recurring_task" -> {
                    val task = tasks.find { it.id == selectedTaskId && it.isRecurringTask() }
                    if (task == null) { screen = "home"; return@RemindInPlaceTheme }
                    AddRecurringTaskScreen(
                        currentUserId = uid,
                        editingTask = task,
                        onSave = { updatedTask ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                taskRepo.save(updatedTask)
                                TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, updatedTask)
                                DiagLog.d("RECURRING", "schedule next id=${updatedTask.id.take(8)} date=${updatedTask.activeFromDate}")
                            }
                            screen = "task_detail"
                        },
                        onBack = { screen = "task_detail" }
                    )
                }

                "contacts" -> {
                    val session = authClient.getSession()
                    TrustedContactsScreen(
                        contacts = contacts,
                        displayPrefs = displayPrefs,
                        currentUserId = uid,
                        currentUserName = session?.displayName ?: "",
                        currentUserEmail = session?.email ?: "",
                        onInviteByEmail = { email ->
                            val myEmail = authClient.getSession()?.email ?: ""
                            val isSelfByEmail = myEmail.isNotBlank() && email.lowercase() == myEmail.lowercase()
                            if (!isSelfByEmail) lifecycleScope.launch(Dispatchers.IO) {
                                val found = runCatching { supabase.lookupUserByEmail(email) }.getOrNull()
                                if (found?.first == uid) return@launch
                                val contact = TrustedContact(
                                    id = UUID.randomUUID().toString(),
                                    userId = uid,
                                    contactUserId = found?.first ?: email,
                                    contactEmail = email,
                                    contactDisplayName = found?.second ?: "",
                                    status = ContactStatus.PENDING
                                )
                                contactRepo.save(contact)
                                val sess = authClient.getSession()
                                runCatching {
                                    supabase.sendContactInvite(
                                        contact = contact,
                                        requesterEmailSnapshot = sess?.email,
                                        requesterDisplayNameSnapshot = sess?.displayName
                                    )
                                }
                            }
                        },
                        onPasteInvite = { text ->
                            val inv = parseInviteFromText(text)
                            if (inv != null) pendingInvite = inv
                        },
                        onAcceptInvite = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                contactRepo.updateStatus(contact.id, ContactStatus.ACCEPTED)
                                runCatching { supabase.updateContactStatus(contact.id, "ACCEPTED") }
                                    .onFailure { e -> DiagLog.e("CONTACTS", "updateContactStatus failed: ${e.message?.take(60)}") }
                                DiagLog.d("CONNECTION_ACCEPT", "backend status=ACCEPTED connectionId=${contact.id.take(8)}")
                                val sess = authClient.getSession()
                                val myName = sess?.displayName?.takeIf { it.isNotBlank() }
                                    ?: sess?.email ?: "Someone"
                                runCatching {
                                    supabase.notifyConnectionAccepted(contact.contactUserId, myName, uid)
                                }.onFailure { e -> DiagLog.e("CONTACTS", "notifyConnectionAccepted failed: ${e.message?.take(60)}") }
                            }
                        },
                        onAcceptWithNickname = { contact, nickname ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                contactRepo.updateStatus(contact.id, ContactStatus.ACCEPTED)
                                runCatching { supabase.updateContactStatus(contact.id, "ACCEPTED") }
                                    .onFailure { e -> DiagLog.e("CONTACTS", "updateContactStatus failed: ${e.message?.take(60)}") }
                                DiagLog.d("CONNECTION_ACCEPT", "backend status=ACCEPTED connectionId=${contact.id.take(8)}")
                                if (nickname.isNotBlank()) {
                                    val pref = ContactDisplayPref(
                                        id = ContactDisplayRepository.makeId(uid, contact.contactUserId),
                                        ownerUserId = uid,
                                        contactUserId = contact.contactUserId,
                                        nickname = nickname,
                                        iconId = "person",
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    contactDisplayRepo.save(pref)
                                    DiagLog.d("CONNECTION_ACCEPT", "nickname=$nickname contactId=${contact.contactUserId.take(8)}")
                                }
                                val sess = authClient.getSession()
                                val myName = sess?.displayName?.takeIf { it.isNotBlank() }
                                    ?: sess?.email ?: "Someone"
                                runCatching {
                                    supabase.notifyConnectionAccepted(contact.contactUserId, myName, uid)
                                }.onFailure { e -> DiagLog.e("CONTACTS", "notifyConnectionAccepted failed: ${e.message?.take(60)}") }
                            }
                        },
                        onRejectInvite = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                DiagLog.d("CONTACTS", "reject invite id=${contact.id.take(8)} contactUserId=${contact.contactUserId.take(8)}")
                                contactRepo.deleteById(contact.id)
                                runCatching { supabase.updateContactStatus(contact.id, "REMOVED") }
                            }
                        },
                        onCancelInvite = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                DiagLog.d("CONNECTION_CANCEL", "requester=${uid.take(8)} connectionId=${contact.id.take(8)} status=CANCELLED")
                                contactRepo.deleteById(contact.id)
                                runCatching { supabase.cancelContactInvite(contact.id) }
                                    .onFailure { e -> DiagLog.e("CONTACTS", "cancelContactInvite failed: ${e.message?.take(60)}") }
                            }
                        },
                        onResendInvite = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                DiagLog.d("CONNECTION_RESEND", "oldStatus=PENDING_SENT connectionId=${contact.id.take(8)}")
                                runCatching { supabase.resendContactInvite(contact.id) }
                                    .onSuccess { DiagLog.d("CONNECTION_RESEND", "newStatus=PENDING connectionId=${contact.id.take(8)}") }
                                    .onFailure { e -> DiagLog.e("CONTACTS", "resendContactInvite failed: ${e.message?.take(60)}") }
                            }
                        },
                        onRemoveContact = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                DiagLog.d("CONTACTS", "remove contact id=${contact.id.take(8)} contactUserId=${contact.contactUserId.take(8)}")
                                contactRepo.deleteById(contact.id)
                                runCatching { supabase.updateContactStatus(contact.id, "REMOVED") }
                            }
                        },
                        onEditContact = { contact ->
                            editingContact = contact
                            screen = "edit_contact"
                        },
                        onCreateInviteCode = {
                            DiagLog.d("INVITE", "createConnectionInvite start uid=${uid.take(8)}")
                            val code = withContext(Dispatchers.IO) {
                                runCatching { supabase.createConnectionInvite(uid) }
                                    .onFailure { e -> DiagLog.e("INVITE", "createConnectionInvite failed", e) }
                                    .getOrThrow()  // propagate so TrustedContactsScreen can show error
                            }
                            DiagLog.d("INVITE", "createConnectionInvite result='$code'")
                            code
                        },
                        onLookupInviteCode = { normalizedCode ->
                            DiagLog.d("INVITE", "lookup normalizedCode=$normalizedCode")
                            val result = withContext(Dispatchers.IO) {
                                runCatching { supabase.getConnectionInviteByNormalizedCode(normalizedCode) }
                                    .onFailure { e -> DiagLog.e("INVITE", "lookup network", e) }
                                    .getOrNull()
                            }
                            DiagLog.d("INVITE", "lookup raw result keys=${result?.keys?.joinToString()}")
                            when {
                                result == null ->
                                    InviteCodeLookupResult.NotFound(getString(R.string.invalid_invite_code))
                                        .also { DiagLog.d("INVITE", "→ null result") }
                                result["from_user_id"] as? String == uid ->
                                    InviteCodeLookupResult.NotFound(getString(R.string.invite_code_self))
                                        .also { DiagLog.d("INVITE", "→ self-code") }
                                (result["expires_at"] as? Number)?.toLong()
                                    ?.let { it < System.currentTimeMillis() } == true ->
                                    InviteCodeLookupResult.NotFound(getString(R.string.invite_code_expired))
                                        .also { DiagLog.d("INVITE", "→ expired") }
                                result["status"] as? String == "USED" ->
                                    InviteCodeLookupResult.NotFound(getString(R.string.invite_code_used))
                                        .also { DiagLog.d("INVITE", "→ used") }
                                result["status"] as? String != "ACTIVE" ->
                                    InviteCodeLookupResult.NotFound(getString(R.string.invalid_invite_code))
                                        .also { DiagLog.d("INVITE", "→ status=${result["status"]}") }
                                else -> {
                                    val inviterUserId = result["from_user_id"] as? String ?: ""
                                    if (inviterUserId.isBlank()) {
                                        DiagLog.d("INVITE", "invalid invite: from_user_id missing")
                                        InviteCodeLookupResult.NotFound(getString(R.string.invalid_invite_code))
                                    } else {
                                        DiagLog.d("INVITE", "→ found inviterUserId=${inviterUserId.take(8)}")
                                        val inviterInfo = withContext(Dispatchers.IO) {
                                            runCatching { supabase.lookupUserById(inviterUserId) }
                                                .onFailure { e -> DiagLog.e("INVITE", "lookupUserById", e) }
                                                .getOrNull()
                                        }
                                        val rawName = inviterInfo?.second ?: ""
                                        val rawEmail = inviterInfo?.first ?: ""
                                        val resolvedName = when {
                                            rawName.isNotBlank() -> rawName
                                            rawEmail.isNotBlank() -> rawEmail
                                            else -> ""
                                        }
                                        val nameSource = when {
                                            rawName.isNotBlank() -> "profile"
                                            rawEmail.isNotBlank() -> "email"
                                            else -> "fallback"
                                        }
                                        DiagLog.d("INVITE_NAME", "ownerUserId=${inviterUserId.take(8)} source=$nameSource name=$resolvedName")
                                        InviteCodeLookupResult.Found(
                                            inviteId = result["id"] as? String ?: "",
                                            inviterUserId = inviterUserId,
                                            inviterName = resolvedName,
                                            inviterEmail = rawEmail
                                        )
                                    }
                                }
                            }
                        },
                        onRedeemInviteCode = { inviteId, inviterUserId, inviterName, inviterEmail ->
                            DiagLog.d("INVITE", "redeem inviteId=$inviteId inviterUserId=${inviterUserId.take(8)}")
                            if (inviterUserId == uid) {
                                DiagLog.d("INVITE", "redeem skipped — self")
                            } else {
                                val existingContact = contacts.find { it.contactUserId == inviterUserId }
                                DiagLog.d("INVITE", "existing contact status=${existingContact?.status}")
                                when (existingContact?.status) {
                                    ContactStatus.ACCEPTED -> {
                                        DiagLog.d("INVITE", "redeem skipped — already accepted")
                                    }
                                    ContactStatus.PENDING -> {
                                        DiagLog.d("INVITE", "redeem skipped — already pending")
                                    }
                                    ContactStatus.PENDING_SENT -> {
                                        DiagLog.d("INVITE", "redeem skipped — request already sent, waiting for acceptance")
                                    }
                                    ContactStatus.BLOCKED -> {
                                        // BLOCKED was used for removes before this fix. Treat as re-addable.
                                        DiagLog.d("INVITE", "redeem reactivating — was blocked/removed oldStatus=BLOCKED")
                                        withContext(Dispatchers.IO) {
                                            contactRepo.deleteById(existingContact.id)
                                            val contact = TrustedContact(
                                                id = UUID.randomUUID().toString(),
                                                userId = uid,
                                                contactUserId = inviterUserId,
                                                contactEmail = inviterEmail,
                                                contactDisplayName = inviterName,
                                                status = ContactStatus.PENDING_SENT
                                            )
                                            contactRepo.save(contact)
                                            val sess = authClient.getSession()
                                            runCatching {
                                                supabase.sendContactInvite(
                                                    contact = contact,
                                                    requesterEmailSnapshot = sess?.email,
                                                    requesterDisplayNameSnapshot = sess?.displayName
                                                )
                                            }
                                                .onFailure { e -> DiagLog.e("INVITE", "sendContactInvite", e) }
                                            runCatching { supabase.acceptConnectionInvite(inviteId, uid) }
                                                .onFailure { e -> DiagLog.e("INVITE", "acceptConnectionInvite", e) }
                                        }
                                    }
                                    null -> {
                                        DiagLog.d("INVITE", "redeem creating new contact as PENDING_SENT")
                                        val contact = TrustedContact(
                                            id = UUID.randomUUID().toString(),
                                            userId = uid,
                                            contactUserId = inviterUserId,
                                            contactEmail = inviterEmail,
                                            contactDisplayName = inviterName,
                                            status = ContactStatus.PENDING_SENT
                                        )
                                        withContext(Dispatchers.IO) {
                                            contactRepo.save(contact)
                                            val sess = authClient.getSession()
                                            runCatching {
                                                supabase.sendContactInvite(
                                                    contact = contact,
                                                    requesterEmailSnapshot = sess?.email,
                                                    requesterDisplayNameSnapshot = sess?.displayName
                                                )
                                            }
                                                .onFailure { e -> DiagLog.e("INVITE", "sendContactInvite", e) }
                                            runCatching { supabase.acceptConnectionInvite(inviteId, uid) }
                                                .onFailure { e -> DiagLog.e("INVITE", "acceptConnectionInvite", e) }
                                        }
                                    }
                                }
                            }
                        },
                        onRefresh = { SyncWorker.syncNow(this@MainActivity) },
                        onBack = { screen = "home" },
                        bottomBar = rootBottomBar
                    )
                }

                "edit_contact" -> {
                    val contact = editingContact
                    if (contact == null) { screen = "contacts"; return@RemindInPlaceTheme }
                    val existingPref = displayPrefs.find { it.contactUserId == contact.contactUserId }
                    EditContactScreen(
                        contact = contact,
                        existingPref = existingPref,
                        onSave = { nickname, iconId ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val pref = ContactDisplayPref(
                                    id = ContactDisplayRepository.makeId(uid, contact.contactUserId),
                                    ownerUserId = uid,
                                    contactUserId = contact.contactUserId,
                                    nickname = nickname,
                                    iconId = iconId,
                                    updatedAt = System.currentTimeMillis()
                                )
                                contactDisplayRepo.save(pref)
                            }
                            editingContact = null
                            screen = "contacts"
                        },
                        onBack = { editingContact = null; screen = "contacts" }
                    )
                }

                "settings" -> {
                    val debugInfo = buildString {
                        appendLine("Do In Place — Debug Info")
                        appendLine("========================")
                        appendLine("User: ${uid.take(8)}…")
                        appendLine("Contacts: ${contacts.size} (${contacts.count { it.status == ContactStatus.ACCEPTED }} accepted, ${contacts.count { it.status == ContactStatus.PENDING }} pending, ${contacts.count { it.status == ContactStatus.BLOCKED }} blocked)")
                        appendLine("Tasks: ${tasks.size} active / ${archivedTasks.size} archived")
                        appendLine("Places: ${places.size}")
                        appendLine("Cards: ${savedCards.size}")
                        appendLine("Location: ${if (permissionState.hasForegroundLocation) "✓" else "✗"} (fine:${if (hasFineLocation) "✓" else "✗"} coarse:${if (hasCoarseLocation) "✓" else "✗"})")
                        appendLine("Bg location: ${if (permissionState.hasBackgroundLocation) "✓" else "✗"}")
                        appendLine("Notifications: ${if (permissionState.hasNotifications) "✓" else "✗"}")
                        appendLine("Activity: ${if (permissionState.hasActivityRecognition) "✓" else "✗"}")
                        appendLine("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        val diagDump = DiagLog.dump()
                        if (diagDump.isNotBlank()) {
                            appendLine()
                            appendLine("─── Diagnostic Log ───")
                            append(diagDump)
                        }
                    }
                    SettingsScreen(
                        locationGranted = permissionState.hasForegroundLocation,
                        backgroundLocationGranted = permissionState.hasBackgroundLocation,
                        notificationGranted = permissionState.hasNotifications,
                        activityRecognitionGranted = permissionState.hasActivityRecognition,
                        onRequestForegroundLocation = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        onRequestActivityRecognition = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                            }
                        },
                        activeTaskCount = tasks.count { it.status == TaskStatus.ACTIVE },
                        notifyOnFriendCancelMyTask = notifyOnFriendCancelMyTask,
                        onNotifyOnFriendCancelMyTaskChanged = { enabled ->
                            notifyOnFriendCancelMyTask = enabled
                            prefs().edit().putBoolean("notify_on_my_task_cancelled_by_friend", enabled).apply()
                            lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { supabase.updateNotifyOnTaskCancelledPref(uid, enabled) }
                            }
                        },
                        smartRemindersEnabled = smartRemindersEnabled,
                        onSmartRemindersEnabledChanged = { enabled ->
                            smartRemindersEnabled = enabled
                            prefs().edit().putBoolean("smart_reminders_enabled", enabled).apply()
                        },
                        suppressAtHome = suppressAtHome,
                        onSuppressAtHomeChanged = { v ->
                            suppressAtHome = v
                            prefs().edit().putBoolean("smart_suppress_at_home", v).apply()
                        },
                        suppressAtNight = suppressAtNight,
                        onSuppressAtNightChanged = { v ->
                            suppressAtNight = v
                            prefs().edit().putBoolean("smart_suppress_at_night", v).apply()
                        },
                        onClearReminderOutcomes = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { db.reminderOutcomeDao().clearAll(uid) }
                            }
                        },
                        localLearningEnabled = localLearningEnabled,
                        onLocalLearningEnabledChanged = { enabled ->
                            localLearningEnabled = enabled
                            prefs().edit().putBoolean("local_learning_enabled", enabled).apply()
                        },
                        onClearLocalLearning = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { learningEngine.clearAll(uid) }
                            }
                        },
                        lastSyncInfo = "Sync runs every 15 min when connected · Supabase storage",
                        archivedCount = archivedTasks.size,
                        debugInfo = debugInfo,
                        selectedLanguage = selectedLanguage,
                        onLanguageChanged = { lang ->
                            selectedLanguage = lang
                            prefs().edit().putString("app_language", lang).apply()
                            AppLocaleManager.applyLanguage(lang)
                            recreate()
                        },
                        healthyLifeEnabled = healthyLifeEnabled,
                        onHealthyLifeEnabledChanged = { v ->
                            healthyLifeEnabled = v
                            prefs().edit().putBoolean("healthy_life_enabled", v).apply()
                            if (!v) {
                                healthyFoodEnabled = false
                                walkReminderEnabled = false
                                prefs().edit()
                                    .putBoolean("healthy_food_enabled", false)
                                    .putBoolean(WalkReminderWorker.PREF_WALK_REMINDER, false)
                                    .apply()
                                WalkReminderWorker.cancel(this@MainActivity)
                            }
                        },
                        healthyFoodEnabled = healthyFoodEnabled,
                        onHealthyFoodEnabledChanged = { v ->
                            healthyFoodEnabled = v
                            prefs().edit().putBoolean("healthy_food_enabled", v).apply()
                        },
                        walkReminderEnabled = walkReminderEnabled,
                        onWalkReminderEnabledChanged = { v ->
                            walkReminderEnabled = v
                            prefs().edit().putBoolean(WalkReminderWorker.PREF_WALK_REMINDER, v).apply()
                            if (v) WalkReminderWorker.schedule(this@MainActivity)
                            else WalkReminderWorker.cancel(this@MainActivity)
                        },
                        onOpenArchive = { screen = "archived" },
                        onDeleteLocalData = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                tasks.forEach { task ->
                                    runCatching { locationReminderManager.onTaskDeactivated(task.id) }
                                    NotificationHelper.cancel(this@MainActivity, task.id)
                                    SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                    TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                db.clearAllTables()
                            }
                            screen = "home"
                        },
                        onDeleteAccount = { onError ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                tasks.forEach { task ->
                                    runCatching { locationReminderManager.onTaskDeactivated(task.id) }
                                    NotificationHelper.cancel(this@MainActivity, task.id)
                                    SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                    TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                supabase.deleteCurrentUserData().fold(
                                    onSuccess = {
                                        db.clearAllTables()
                                        authClient.signOut()
                                        withContext(Dispatchers.Main) { screen = "auth" }
                                    },
                                    onFailure = { e ->
                                        withContext(Dispatchers.Main) {
                                            onError(e.message ?: "Deletion failed. Please try again.")
                                        }
                                    }
                                )
                            }
                        },
                        onLogout = {
                            lifecycleScope.launch(Dispatchers.IO) { authClient.signOut() }
                            screen = "auth"
                        },
                        onBack = { screen = "home" },
                        bottomBar = rootBottomBar
                    )
                }

                "sort_list" -> {
                    val task = tasks.find { it.id == selectedTaskId }
                    if (task == null) { screen = "home"; return@RemindInPlaceTheme }
                    // Keep items live while the edit screen is open
                    LaunchedEffect(task.id) {
                        db.shoppingListItemDao().observeForTask(task.id).collectLatest { items ->
                            currentTaskShoppingItems = items
                            DiagLog.d("SHOP_EDIT", "observed items taskId=${task.id.take(8)} count=${items.size}")
                        }
                    }
                    val placeKey = shoppingOrderEngine.buildPlaceKey(task)
                    SortListScreen(
                        items = currentTaskShoppingItems,
                        taskPlaceName = task.placeName,
                        hasLearnedOrder = autoOrderAvailableForTask,
                        currentUserId = uid,
                        onAddItem = { text ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                val displayName = authClient.getSession()?.displayName ?: "Me"
                                val canonicalized = ShoppingItemCanonicalizer.canonicalize(text)
                                val canonicalText = canonicalized.canonicalName.ifBlank { text.trim() }
                                val newItem = ShoppingListItem(
                                    id = UUID.randomUUID().toString(),
                                    taskId = task.id,
                                    text = canonicalText,
                                    normalizedText = ShoppingItemCanonicalizer.normalize(canonicalText),
                                    rawText = text.trim(),
                                    canonicalName = canonicalText,
                                    orderIndex = currentTaskShoppingItems.size,
                                    syncStatus = "PENDING_UPDATE",
                                    createdAt = now,
                                    updatedAt = now,
                                    addedByUserId = uid,
                                    addedByDisplayName = displayName,
                                    addedAt = now
                                )
                                db.shoppingListItemDao().upsertAll(listOf(newItem))
                                runCatching { supabase.pushShoppingItems(task.id, listOf(newItem)) }
                                    .onSuccess { db.shoppingListItemDao().markSynced(newItem.id) }
                                DiagLog.d("SHOP_EDIT", "add item taskId=${task.id.take(8)} itemId=${newItem.id.take(8)}")
                            }
                        },
                        onDeleteItem = { itemId ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                val removedItem = currentTaskShoppingItems.find { it.id == itemId }
                                db.shoppingListItemDao().softDelete(itemId, uid, now)
                                runCatching { supabase.softDeleteShoppingItem(itemId, uid, now) }
                                val placeTypeKey = task.placeTypeId
                                val normalized = removedItem?.normalizedText?.takeIf { it.isNotBlank() }
                                if (!placeTypeKey.isNullOrBlank() && !normalized.isNullOrBlank()) {
                                    runCatching { usualShoppingEngine.recordItemDismissed(uid, placeTypeKey, normalized) }
                                }
                                val remaining = ReminderItemFilter.activeItems(task.id, db.shoppingListItemDao().getForTaskIncludingDeleted(task.id))
                                if (remaining.isEmpty()) {
                                    NotificationHelper.cancel(this@MainActivity, task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                    TimeBasedTaskScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                DiagLog.d("SHOP_EDIT", "delete confirm itemId=${itemId.take(8)}")
                            }
                        },
                        onSaveOrder = { sorted ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.shoppingListItemDao().upsertAll(sorted)
                                runCatching { supabase.pushShoppingItems(task.id, sorted) }
                                DiagLog.d("SHOP_ORDER", "saved order taskId=${task.id.take(8)} count=${sorted.size}")
                            }
                            screen = "task_detail"
                        },
                        onSaveAsDefault = { sorted ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.shoppingListItemDao().upsertAll(sorted)
                                runCatching { supabase.pushShoppingItems(task.id, sorted) }
                                shoppingOrderEngine.saveOrder(uid, task, sorted)
                                autoOrderAvailableForTask = true
                                DiagLog.d("SHOP_ORDER", "saved order taskId=${task.id.take(8)} count=${sorted.size}")
                            }
                            screen = "task_detail"
                        },
                        onBack = { screen = "task_detail" }
                    )
                }

                "archived" -> ArchivedTasksScreen(
                    archivedTasks = archivedTasks,
                    currentUserId = uid,
                    contacts = contacts,
                    onRestore = { task ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            taskRepo.restoreToActive(task, uid)
                            DiagLog.d("TASK_RESTORE", "restored taskId=${task.id.take(8)}")
                            val restored = task.copy(status = TaskStatus.ACTIVE, archived = false)
                            if (restored.isEverywhere) {
                                TimeBasedTaskScheduler.scheduleForTask(this@MainActivity, restored)
                            } else {
                                locationReminderManager.onTaskActivated(restored)
                            }
                            runCatching { supabase.updateTaskStatus(task.id, TaskStatus.ACTIVE.name) }
                        }
                    },
                    onDeletePermanently = { task ->
                        DiagLog.d("TASK_DELETE", "permanent delete confirmed taskId=${task.id.take(8)}")
                        lifecycleScope.launch(Dispatchers.IO) {
                            taskRepo.deleteTaskPermanently(task.id)
                            addTombstone(task.id)
                            runCatching { supabase.deleteShoppingItemsForTask(task.id) }
                            runCatching { supabase.deleteTask(task.id) }
                        }
                    },
                    onClearAll = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val toDelete = db.taskDao().getArchivedIds(uid)
                            taskRepo.clearArchivedTasks(uid)
                            toDelete.forEach { id ->
                                addTombstone(id)
                                runCatching { supabase.deleteTask(id) }
                            }
                        }
                    },
                    onBack = { screen = "settings" }
                )

                "permissions" -> PermissionsScreen(
                    permissionState = permissionState,
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    },
                    onRequestForegroundLocation = {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    },
                    onRequestActivityRecognition = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                        }
                    },
                    onBack = { screen = "home" }
                )
            }

            // Usual shopping suggestion dialog
            usualShoppingSuggestionState?.let { state ->
                UsualShoppingSuggestionDialog(
                    state = state,
                    onCreateList = { selectedNorms ->
                        usualShoppingSuggestionState = null
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { usualShoppingEngine.recordItemsAccepted(uid, state.placeTypeKey, selectedNorms) }
                            state.items
                                .map { it.normalizedItem }
                                .filterNot { it in selectedNorms }
                                .forEach { normalized ->
                                    runCatching { usualShoppingEngine.recordItemDismissed(uid, state.placeTypeKey, normalized) }
                                }
                        }
                        val selectedItems = state.items.filter { it.normalizedItem in selectedNorms }
                        resetCreateTaskDraft()
                        draftTitle = "Shopping list"
                        draftTaskType = TaskType.SHOPPING_LIST
                        draftShoppingItems = selectedItems.joinToString("\n") { it.displayItem }
                        screen = "create_task"
                    },
                    onDismiss = {
                        usualShoppingSuggestionState = null
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { usualShoppingEngine.suppressForToday(uid, state.placeTypeKey) }
                            state.items.forEach { item ->
                                runCatching { usualShoppingEngine.recordItemDismissed(uid, state.placeTypeKey, item.normalizedItem) }
                            }
                        }
                    }
                )
            }

            // Google Calendar "add done event" prompt for Everywhere tasks
            pendingCalendarTask?.let { calTask ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingCalendarTask = null },
                    title = { androidx.compose.material3.Text(stringResource(R.string.calendar_add_dialog_title)) },
                    text = {
                        androidx.compose.material3.Text(
                            stringResource(R.string.calendar_add_dialog_text, calTask.title)
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            pendingCalendarTask = null
                            lifecycleScope.launch(Dispatchers.IO) {
                                val ok = GoogleCalendarService.insertDoneEvent(
                                    this@MainActivity, calTask, System.currentTimeMillis()
                                )
                                DiagLog.d("CALENDAR_DONE", "insert result=$ok taskId=${calTask.id.take(8)}")
                                if (!ok) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.calendar_done_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }) { androidx.compose.material3.Text(stringResource(R.string.calendar_yes_add)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { pendingCalendarTask = null }) {
                            androidx.compose.material3.Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            if (sessionExpiredDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { androidx.compose.material3.Text("Session expired") },
                    text = { androidx.compose.material3.Text("Session expired. Please log in again.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            sessionExpiredDialog = false
                            lifecycleScope.launch(Dispatchers.IO) { authClient.signOut() }
                            screen = "auth"
                        }) { androidx.compose.material3.Text("Log in") }
                    }
                )
            }

            // Shared location: resolving spinner (dismissable — never infinite)
            if (sharedLocResolving) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { sharedLocResolving = false },
                    title = { androidx.compose.material3.Text(stringResource(R.string.share_place_resolving)) },
                    text = {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {},
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { sharedLocResolving = false }
                        ) { androidx.compose.material3.Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            // Shared location: save confirmation dialog
            val loc = sharedLocParsed
            if (loc != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { sharedLocParsed = null },
                    title = { androidx.compose.material3.Text(stringResource(R.string.share_place_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            androidx.compose.material3.OutlinedTextField(
                                value = sharedLocName,
                                onValueChange = { sharedLocName = it },
                                label = { androidx.compose.material3.Text(stringResource(R.string.share_place_hint)) },
                                singleLine = true,
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                            )
                            androidx.compose.material3.Text(
                                "%.5f, %.5f".format(loc.lat, loc.lng),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            enabled = sharedLocName.isNotBlank(),
                            onClick = {
                                val place = SavedPlace(
                                    id = UUID.randomUUID().toString(),
                                    userId = uid,
                                    name = sharedLocName.trim(),
                                    latitude = loc.lat,
                                    longitude = loc.lng,
                                    radiusMeters = 100
                                )
                                sharedLocParsed = null
                                lifecycleScope.launch(Dispatchers.IO) { placeRepo.save(place) }
                                screen = "saved_places"
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.share_place_saved),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) { androidx.compose.material3.Text(stringResource(R.string.share_place_save)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { sharedLocParsed = null }) {
                            androidx.compose.material3.Text(stringResource(R.string.share_place_discard))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RootBottomBar(
    currentScreen: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        val items = listOf(
            Triple("home", Icons.Default.Home, R.string.tab_home),
            Triple("saved_places", Icons.Default.Place, R.string.saved_places),
            Triple("cards", Icons.Default.CreditCard, R.string.tab_cards),
            Triple("contacts", Icons.Default.People, R.string.contacts),
            Triple("settings", Icons.Default.Settings, R.string.settings)
        )
        items.forEach { (route, icon, labelRes) ->
            NavigationBarItem(
                selected = currentScreen == route,
                onClick = { onNavigate(route) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}
