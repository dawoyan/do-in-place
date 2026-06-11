package com.davoyans.doinplace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.davoyans.doinplace.BuildConfig
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.learning.LocalLearningEngine
import com.davoyans.doinplace.data.model.*
import com.davoyans.doinplace.data.places.TaskSuggestionEngine
import com.davoyans.doinplace.data.shopping.ShoppingOrderEngine
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.data.remote.SupabaseClient
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import com.davoyans.doinplace.data.repository.ContactRepository
import com.davoyans.doinplace.data.repository.PlaceRepository
import com.davoyans.doinplace.data.repository.PlaceSearchRepository
import com.davoyans.doinplace.data.repository.TaskRepository
import com.davoyans.doinplace.geofence.LocationReminderManager
import com.davoyans.doinplace.notification.DueAlarmScheduler
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.notification.SnoozeAlarmReceiver
import com.davoyans.doinplace.sync.SyncWorker
import com.davoyans.doinplace.sync.TypeTaskCheckWorker
import com.davoyans.doinplace.ui.archive.ArchivedTasksScreen
import com.davoyans.doinplace.ui.auth.AuthScreen
import com.davoyans.doinplace.ui.contacts.EditContactScreen
import com.davoyans.doinplace.ui.contacts.InviteCodeLookupResult
import com.davoyans.doinplace.ui.contacts.InviteData
import com.davoyans.doinplace.ui.contacts.TrustedContactsScreen
import com.davoyans.doinplace.ui.home.HomeScreen
import com.davoyans.doinplace.ui.places.PlacePickerScreen
import com.davoyans.doinplace.ui.places.SavedPlacesScreen
import com.davoyans.doinplace.ui.settings.PermissionState
import com.davoyans.doinplace.ui.settings.PermissionsScreen
import com.davoyans.doinplace.ui.settings.SettingsScreen
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
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var taskRepo: TaskRepository
    private lateinit var placeRepo: PlaceRepository
    private lateinit var contactRepo: ContactRepository
    private lateinit var contactDisplayRepo: ContactDisplayRepository
    private lateinit var locationReminderManager: LocationReminderManager
    private lateinit var placeSearchRepo: PlaceSearchRepository
    private lateinit var authClient: SupabaseAuthClient
    private lateinit var supabase: SupabaseClient
    private lateinit var learningEngine: LocalLearningEngine
    private lateinit var suggestionEngine: TaskSuggestionEngine
    private lateinit var shoppingOrderEngine: ShoppingOrderEngine

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInvite = parseInviteUri(intent.data)
        intent.getStringExtra(ShareReceiverActivity.EXTRA_PREFILL_NOTE)?.takeIf { it.isNotBlank() }?.let {
            pendingPrefillNote = it
        }
        extractSharedText(intent)?.let { pendingSharedText = it }
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

    private fun buildArticleBody(summary: String?, url: String): String = buildString {
        if (!summary.isNullOrBlank()) { appendLine("Summary:"); appendLine(summary); appendLine() }
        appendLine("URL:"); append(url)
    }.trim()

    private fun normalizeInviteCode(raw: String): String =
        raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

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
        contactRepo = ContactRepository(db)
        contactDisplayRepo = ContactDisplayRepository(db)
        locationReminderManager = LocationReminderManager(this)
        placeSearchRepo = PlaceSearchRepository(this)
        authClient = SupabaseAuthClient(this)
        supabase = SupabaseClient(this)
        learningEngine = LocalLearningEngine(db.taskLearningProfileDao())
        suggestionEngine = TaskSuggestionEngine(db.userTaskSuggestionDao())
        shoppingOrderEngine = ShoppingOrderEngine(db.shoppingPlaceItemOrderDao())

        pendingInvite = parseInviteUri(intent.data)
        intent.getStringExtra(ShareReceiverActivity.EXTRA_PREFILL_NOTE)?.takeIf { it.isNotBlank() }?.let {
            pendingPrefillNote = it
        }
        extractSharedText(intent)?.let { pendingSharedText = it }
        setContent { ReminderApp() }

        val uid = authClient.getCurrentUserId()
        if (uid != null) {
            SyncWorker.syncNow(this)
            TypeTaskCheckWorker.schedule(this)
            TypeTaskCheckWorker.runNow(this)
            lifecycleScope.launch(Dispatchers.IO) {
                locationReminderManager.restoreOnBoot(uid)
            }
        }
    }

    @Composable
    private fun ReminderApp() {
        var screen by remember { mutableStateOf(if (authClient.isLoggedIn()) "home" else "auth") }
        var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
        var archivedTasks by remember { mutableStateOf<List<Task>>(emptyList()) }
        var places by remember { mutableStateOf<List<SavedPlace>>(emptyList()) }
        var contacts by remember { mutableStateOf<List<TrustedContact>>(emptyList()) }
        var displayPrefs by remember { mutableStateOf<List<ContactDisplayPref>>(emptyList()) }
        var selectedTaskId by remember { mutableStateOf<String?>(null) }
        var taskEvents by remember { mutableStateOf<List<TaskEvent>>(emptyList()) }
        var pickedPlace by remember { mutableStateOf<SavedPlace?>(null) }
        var draftTitle by remember { mutableStateOf("") }
        var draftDescription by remember { mutableStateOf("") }
        var draftShoppingItems by remember { mutableStateOf("") }
        var draftTaskType by remember { mutableStateOf(TaskType.SIMPLE) }
        var draftPriority by remember { mutableStateOf(TaskPriority.NO_RUSH) }
        var draftAssigneeId by remember { mutableStateOf("") }
        var draftPlaceMode by remember { mutableStateOf(PlaceMode.EXACT) }
        var draftPlaceTypeId by remember { mutableStateOf<String?>(null) }
        var draftDueDate by remember { mutableStateOf("") }
        var draftDueTime by remember { mutableStateOf("") }
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
        var editingContact by remember { mutableStateOf<TrustedContact?>(null) }
        var placeTypeUsages by remember { mutableStateOf<List<PlaceTypeUsage>>(emptyList()) }
        var shoppingItemCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var currentTaskShoppingItems by remember { mutableStateOf<List<ShoppingListItem>>(emptyList()) }
        var autoOrderAvailableForTask by remember { mutableStateOf(false) }

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
                                draftDescription = text
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

        LaunchedEffect(uid) {
            if (uid.isBlank()) return@LaunchedEffect
            launch(Dispatchers.IO) {
                runCatching { contactRepo.deleteSelfContacts(uid) }
                val myEmail = authClient.getSession()?.email ?: ""
                if (myEmail.isNotBlank()) runCatching { contactRepo.deleteSelfContactsByEmail(uid, myEmail) }
            }
            launch { taskRepo.observeAll(uid).collectLatest { tasks = it } }
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
                    status = ContactStatus.PENDING
                )
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    contactRepo.save(contact)
                    runCatching { supabase.sendContactInvite(contact) }
                }
            }
            pendingInvite = null
            screen = "contacts"
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
            draftDescription = note
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
                        screen = "create_task"
                    } finally {
                        sharedLocResolving = false
                        pendingSharedText = null
                    }
                }
                is SharedInputRoute.PlainTextNote -> {
                    DiagLog.d("PREFILL", "openNewReminder sourceType=PLAIN_TEXT_SHARE")
                    pendingSharedText = null
                    draftDescription = route.text
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
                    permissionState = permissionState,
                    onTaskClick = { id -> selectedTaskId = id; screen = "task_detail" },
                    onArchive = { task ->
                        DiagLog.d("TASK_ARCHIVE", "archive start taskId=${task.id.take(8)}")
                        undoTaskId = task.id
                        lifecycleScope.launch(Dispatchers.IO) {
                            taskRepo.archiveTask(task.id)
                            DiagLog.d("TASK_ARCHIVE", "archived local taskId=${task.id.take(8)}")
                            DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
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
                                DiagLog.d("TASK_ARCHIVE", "archive start taskId=${task.id.take(8)}")
                                taskRepo.archiveTask(task.id)
                                DiagLog.d("TASK_ARCHIVE", "archived local taskId=${task.id.take(8)}")
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
                                locationReminderManager.onTaskActivated(restored)
                                DueAlarmScheduler.scheduleForTask(this@MainActivity, restored)
                                runCatching { supabase.updateTaskStatus(target.id, TaskStatus.ACTIVE.name) }
                            }
                        }
                    },
                    showUndo = undoTaskId != null,
                    onRefresh = { SyncWorker.syncNow(this@MainActivity) },
                    onCreateTask = { screen = "create_task" },
                    onFromScreenshot = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOpenPlaces = { screen = "saved_places" },
                    onOpenContacts = { screen = "contacts" },
                    onOpenSettings = { screen = "settings" },
                    onOpenPermissions = { screen = "permissions" }
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
                                runCatching { supabase.pushShoppingItems(task.id, shoppingItems) }
                                // Learn from each saved shopping item
                                if (localLearningEnabled) {
                                    val placeKey = when (task.placeMode) {
                                        PlaceMode.EXACT -> task.placeId ?: ""
                                        PlaceMode.TYPE  -> ""
                                    }
                                    for (item in shoppingItems) {
                                        runCatching {
                                            suggestionEngine.recordShoppingItemAdded(
                                                uid, placeKey, task.placeTypeId, item.text
                                            )
                                        }
                                    }
                                }
                            }
                            if (task.status == TaskStatus.ACTIVE) {
                                locationReminderManager.onTaskActivated(task)
                                DueAlarmScheduler.scheduleForTask(this@MainActivity, task)
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
                                val assigneeName = contacts
                                    .find { it.contactUserId == task.assignedToUserId }
                                    ?.let { c ->
                                        val pref = displayPrefs.find { it.contactUserId == c.contactUserId }
                                        ContactDisplayRepository.resolveDisplayName(c.contactUserId, c, pref)
                                    }
                                    ?: "your contact"
                                NotificationHelper.showTaskUpdateNotification(
                                    this@MainActivity,
                                    "Task \"${task.title}\" sent to $assigneeName — waiting for acceptance"
                                )
                                runCatching { supabase.pushTask(task) }
                                    .onSuccess { taskRepo.markSynced(task.id) }
                            }
                        }
                        // If a TYPE task just became active, check immediately in case we're already near a match
                        if (task.placeMode == PlaceMode.TYPE && task.status == TaskStatus.ACTIVE) {
                            TypeTaskCheckWorker.runNow(this@MainActivity)
                        }
                        draftTitle = ""; draftDescription = ""
                        draftShoppingItems = ""; draftTaskType = TaskType.SIMPLE
                        draftPriority = TaskPriority.NO_RUSH; draftAssigneeId = ""
                        draftPlaceMode = PlaceMode.EXACT; draftPlaceTypeId = null
                        draftDueDate = ""; draftDueTime = ""
                        screen = "home"
                    },
                    onPickPlace = { t, d, si, tt, pr, aid, pm, ptid, dd, dt ->
                        draftTitle = t; draftDescription = d
                        draftShoppingItems = si; draftTaskType = tt
                        draftPriority = pr; draftAssigneeId = aid
                        draftPlaceMode = pm; draftPlaceTypeId = ptid
                        draftDueDate = dd; draftDueTime = dt
                        screen = "place_picker"
                    },
                    onBack = {
                        draftTitle = ""; draftDescription = ""
                        draftShoppingItems = ""; draftTaskType = TaskType.SIMPLE
                        draftPriority = TaskPriority.NO_RUSH; draftAssigneeId = ""
                        draftPlaceMode = PlaceMode.EXACT; draftPlaceTypeId = null
                        draftDueDate = ""; draftDueTime = ""
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

                "place_picker" -> PlacePickerScreen(
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
                    onDeletePlace = { place ->
                        lifecycleScope.launch(Dispatchers.IO) { placeRepo.softDelete(place.id) }
                    },
                    onAddPlace = { screen = "place_picker" },
                    onRefresh = { SyncWorker.syncNow(this@MainActivity) },
                    onBack = { screen = "home" }
                )

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
                    TaskDetailScreen(
                        task = task,
                        events = taskEvents,
                        currentUserId = uid,
                        contacts = contacts.filter { it.status == ContactStatus.ACCEPTED && it.contactUserId != uid },
                        displayPrefs = displayPrefs,
                        shoppingItems = currentTaskShoppingItems,
                        onShoppingItemChecked = { itemId, checked ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.shoppingListItemDao().updateChecked(itemId, checked)
                            }
                        },
                        onSortList = { screen = "sort_list" },
                        onAutoOrder = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val placeKey = shoppingOrderEngine.buildPlaceKey(task)
                                val reordered = shoppingOrderEngine.autoOrder(uid, placeKey, currentTaskShoppingItems)
                                db.shoppingListItemDao().upsertAll(reordered)
                            }
                        },
                        autoOrderAvailable = autoOrderAvailableForTask,
                        onChecklistItemToggle = { newJson ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                taskRepo.updateChecklist(task.id, newJson)
                            }
                        },
                        onDone = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                taskRepo.updateStatus(task.id, TaskStatus.DONE, uid)
                                if (task.taskType == TaskType.SHOPPING_LIST && !task.placeId.isNullOrBlank()) {
                                    val items = db.shoppingListItemDao().getForTask(task.id)
                                    if (items.any { it.checked }) {
                                        val placeKey = shoppingOrderEngine.buildPlaceKey(task)
                                        DiagLog.d("SHOP_ORDER", "learn start taskId=${task.id.take(8)} placeKey=${placeKey.take(24)}")
                                        val checkedByOrder = items.filter { it.checked }.sortedBy { it.updatedAt }
                                        val unchecked = items.filter { !it.checked }.sortedBy { it.orderIndex }
                                        runCatching { shoppingOrderEngine.saveOrder(uid, placeKey, checkedByOrder + unchecked) }
                                    }
                                } else if (task.taskType == TaskType.SHOPPING_LIST) {
                                    DiagLog.d("SHOP_ORDER", "skip reason=no exact place taskId=${task.id.take(8)}")
                                }
                                locationReminderManager.onTaskDeactivated(task.id)
                                NotificationHelper.cancel(this@MainActivity, task.id)
                                SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.DONE.name) }
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
                                    locationReminderManager.onTaskActivated(updated)
                                    DueAlarmScheduler.scheduleForTask(this@MainActivity, updated)
                                } else {
                                    locationReminderManager.onTaskDeactivated(task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                runCatching { supabase.updateTaskStatus(task.id, newStatus.name) }
                            }
                            screen = "home"
                        },
                        onAccept = { arrivalShare ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val updated = task.copy(
                                    status = TaskStatus.ACTIVE,
                                    arrivalShareAllowed = arrivalShare,
                                    pendingSync = true
                                )
                                taskRepo.save(updated)
                                locationReminderManager.onTaskActivated(updated)
                                DueAlarmScheduler.scheduleForTask(this@MainActivity, updated)
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.ACTIVE.name) }
                                db.taskEventDao().insert(TaskEvent(
                                    id = UUID.randomUUID().toString(),
                                    taskId = task.id,
                                    type = TaskEventType.ACCEPTED,
                                    actorUserId = uid,
                                    synced = false
                                ))
                            }
                            screen = "home"
                        },
                        onReject = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                taskRepo.updateStatus(task.id, TaskStatus.REJECTED, uid)
                                DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                runCatching { supabase.updateTaskStatus(task.id, TaskStatus.REJECTED.name) }
                            }
                            screen = "home"
                        },
                        onBack = { screen = "home" }
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
                                runCatching { supabase.sendContactInvite(contact) }
                            }
                        },
                        onPasteInvite = { text ->
                            val inv = parseInviteFromText(text)
                            if (inv != null) pendingInvite = inv
                        },
                        onAcceptInvite = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                contactRepo.updateStatus(contact.id, ContactStatus.ACCEPTED)
                                val sess = authClient.getSession()
                                if (sess != null) {
                                    val reverse = TrustedContact(
                                        id = UUID.randomUUID().toString(),
                                        userId = uid,
                                        contactUserId = contact.contactUserId,
                                        contactEmail = sess.email,
                                        contactDisplayName = sess.displayName,
                                        status = ContactStatus.PENDING
                                    )
                                    runCatching { supabase.sendContactInvite(reverse) }
                                }
                            }
                        },
                        onRejectInvite = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                contactRepo.updateStatus(contact.id, ContactStatus.BLOCKED)
                                runCatching { supabase.updateContactStatus(contact.id, ContactStatus.BLOCKED.name) }
                            }
                        },
                        onRemoveContact = { contact ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                contactRepo.updateStatus(contact.id, ContactStatus.BLOCKED)
                                runCatching { supabase.updateContactStatus(contact.id, ContactStatus.BLOCKED.name) }
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
                                        InviteCodeLookupResult.Found(
                                            inviteId = result["id"] as? String ?: "",
                                            inviterUserId = inviterUserId,
                                            inviterName = inviterInfo?.second ?: "",
                                            inviterEmail = inviterInfo?.first ?: ""
                                        )
                                    }
                                }
                            }
                        },
                        onRedeemInviteCode = { inviteId, inviterUserId, inviterName, inviterEmail ->
                            DiagLog.d("INVITE", "redeem inviteId=$inviteId inviterUserId=${inviterUserId.take(8)}")
                            if (inviterUserId != uid) {
                                val alreadyExists = contacts.any { it.contactUserId == inviterUserId }
                                if (!alreadyExists) {
                                    val contact = TrustedContact(
                                        id = UUID.randomUUID().toString(),
                                        userId = uid,
                                        contactUserId = inviterUserId,
                                        contactEmail = inviterEmail,
                                        contactDisplayName = inviterName,
                                        status = ContactStatus.PENDING
                                    )
                                    withContext(Dispatchers.IO) {
                                        contactRepo.save(contact)
                                        runCatching { supabase.sendContactInvite(contact) }
                                            .onFailure { e -> DiagLog.e("INVITE", "sendContactInvite", e) }
                                        runCatching { supabase.acceptConnectionInvite(inviteId, uid) }
                                            .onFailure { e -> DiagLog.e("INVITE", "acceptConnectionInvite", e) }
                                    }
                                } else {
                                    DiagLog.d("INVITE", "redeem skipped — contact already exists")
                                }
                            } else {
                                DiagLog.d("INVITE", "redeem skipped — self")
                            }
                        },
                        onRefresh = { SyncWorker.syncNow(this@MainActivity) },
                        onBack = { screen = "home" }
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
                        appendLine("Contacts: ${contacts.size} (${contacts.count { it.status == ContactStatus.ACCEPTED }} accepted, ${contacts.count { it.status == ContactStatus.PENDING }} pending)")
                        appendLine("Tasks: ${tasks.size} active / ${archivedTasks.size} archived")
                        appendLine("Places: ${places.size}")
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
                        onOpenArchive = { screen = "archived" },
                        onDeleteLocalData = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                tasks.forEach { task ->
                                    runCatching { locationReminderManager.onTaskDeactivated(task.id) }
                                    NotificationHelper.cancel(this@MainActivity, task.id)
                                    SnoozeAlarmReceiver.cancelRepeat(this@MainActivity, task.id)
                                    DueAlarmScheduler.cancelForTask(this@MainActivity, task.id)
                                }
                                db.clearAllTables()
                            }
                            screen = "home"
                        },
                        onLogout = {
                            lifecycleScope.launch(Dispatchers.IO) { authClient.signOut() }
                            screen = "auth"
                        },
                        onBack = { screen = "home" }
                    )
                }

                "sort_list" -> {
                    val task = tasks.find { it.id == selectedTaskId }
                    if (task == null) { screen = "home"; return@RemindInPlaceTheme }
                    val placeKey = shoppingOrderEngine.buildPlaceKey(task)
                    SortListScreen(
                        items = currentTaskShoppingItems,
                        taskPlaceName = task.placeName,
                        hasLearnedOrder = autoOrderAvailableForTask,
                        onSaveOrder = { sorted ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.shoppingListItemDao().upsertAll(sorted)
                                runCatching { supabase.pushShoppingItems(task.id, sorted) }
                            }
                            screen = "task_detail"
                        },
                        onSaveAsDefault = { sorted ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.shoppingListItemDao().upsertAll(sorted)
                                runCatching { supabase.pushShoppingItems(task.id, sorted) }
                                shoppingOrderEngine.saveOrder(uid, placeKey, sorted)
                                autoOrderAvailableForTask = true
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
                            locationReminderManager.onTaskActivated(
                                task.copy(status = TaskStatus.ACTIVE, archived = false)
                            )
                            runCatching { supabase.updateTaskStatus(task.id, TaskStatus.ACTIVE.name) }
                        }
                    },
                    onDeletePermanently = { task ->
                        DiagLog.d("TASK_DELETE", "permanent delete confirmed taskId=${task.id.take(8)}")
                        lifecycleScope.launch(Dispatchers.IO) {
                            taskRepo.deleteTaskPermanently(task.id)
                            if (task.createdByUserId != task.assignedToUserId) {
                                runCatching { supabase.deleteShoppingItemsForTask(task.id) }
                                runCatching { supabase.deleteTask(task.id) }
                            }
                        }
                    },
                    onClearAll = {
                        lifecycleScope.launch(Dispatchers.IO) { taskRepo.clearArchivedTasks(uid) }
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
