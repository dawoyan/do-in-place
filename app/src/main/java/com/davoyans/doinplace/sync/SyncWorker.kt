package com.davoyans.doinplace.sync

import android.content.Context
import androidx.work.*
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.repository.ContactDisplayNameResolver
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import java.util.UUID
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.data.remote.SupabaseClient
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.util.DiagLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val authClient = SupabaseAuthClient(applicationContext)
        val uid = authClient.getCurrentUserId() ?: return Result.success()
        val db = AppDatabase.get(applicationContext)
        val supabase = SupabaseClient(applicationContext)

        // Push pending tasks — self-assigned tasks are local-only; shared tasks use UPSERT
        // so a task that never reached Supabase (e.g. offline at creation) gets created here.
        for (task in db.taskDao().getPendingSync()) {
            if (task.createdByUserId == task.assignedToUserId) {
                db.taskDao().markSynced(task.id)
                continue
            }
            runCatching {
                supabase.pushTask(task)
                if (task.taskType == TaskType.SHOPPING_LIST) {
                    val localItems = db.shoppingListItemDao().getForTask(task.id)
                    if (localItems.isNotEmpty()) {
                        supabase.pushShoppingItems(task.id, localItems)
                    }
                }
            }
                .onSuccess { db.taskDao().markSynced(task.id) }
        }

        // Push unsynced task events — only for shared tasks that exist remotely.
        // Guarded by a singleton mutex so periodic sync and syncNow() cannot race.
        taskEventsSyncMutex.withLock {
            val unsyncedEvents = db.taskEventDao().getUnsynced()
            DiagLog.d("TASK_EVENTS_SYNC", "start pendingCount=${unsyncedEvents.size}")
            var synced = 0; var failed = 0; var pending = 0
            for (event in unsyncedEvents) {
                val task = db.taskDao().getById(event.taskId)
                when {
                    task == null -> {
                        // Parent task not found locally — orphan, stop retrying.
                        DiagLog.d("TASK_EVENTS_SYNC", "skipped orphan eventId=${event.id} taskId=${event.taskId.take(8)}")
                        db.taskEventDao().markSynced(event.id)
                        synced++
                    }
                    task.createdByUserId == task.assignedToUserId -> {
                        // Self-assigned (local-only) task — never pushed to Supabase.
                        db.taskEventDao().markSynced(event.id)
                        synced++
                    }
                    task.pendingSync -> {
                        // Shared task not yet uploaded; skip and retry next cycle.
                        pending++
                    }
                    else -> {
                        // Drop stale reminder events for tasks that are no longer active.
                        if (event.type in REMINDER_ONLY_TYPES && task.status != TaskStatus.ACTIVE) {
                            DiagLog.d("TASK_EVENTS_SYNC", "skipped stale reminder eventId=${event.id} taskStatus=${task.status}")
                            db.taskEventDao().markSynced(event.id)
                            synced++
                            continue
                        }
                        // RLS requires actor_user_id = auth.uid() — skip events that cannot pass.
                        if (event.actorUserId.isBlank() || event.actorUserId != uid) {
                            DiagLog.d("TASK_EVENTS_SYNC", "skipped actorUserId mismatch eventId=${event.id} actor=${event.actorUserId.take(8)} uid=${uid.take(8)}")
                            db.taskEventDao().markSynced(event.id)
                            synced++
                            continue
                        }
                        DiagLog.d("TASK_EVENTS_SYNC", "upload eventId=${event.id} taskId=${event.taskId.take(8)} type=${event.type}")
                        runCatching { supabase.pushTaskEvent(event) }
                            .onSuccess {
                                DiagLog.d("TASK_EVENTS_SYNC", "upload success eventId=${event.id}")
                                db.taskEventDao().markSynced(event.id)
                                synced++
                            }
                            .onFailure { e ->
                                when {
                                    isDuplicateTaskEventKeyError(e) -> {
                                        DiagLog.d("TASK_EVENTS_SYNC", "duplicate remote event treated as synced eventId=${event.id}")
                                        db.taskEventDao().markSynced(event.id)
                                        synced++
                                    }
                                    isRlsDeniedError(e) -> {
                                        DiagLog.e("TASK_EVENTS_SYNC", "RLS_DENIED eventId=${event.id} taskId=${event.taskId.take(8)} type=${event.type} actor=${event.actorUserId.take(8)} taskStatus=${task.status}")
                                        db.taskEventDao().markSynced(event.id)
                                        synced++
                                    }
                                    else -> {
                                        DiagLog.d("TASK_EVENTS_SYNC", "retry later eventId=${event.id} reason=${e.message?.take(80)}")
                                        failed++
                                    }
                                }
                            }
                    }
                }
            }
            DiagLog.d("TASK_EVENTS_SYNC", "finish synced=$synced failed=$failed pending=$pending")
        }

        // Pull remote tasks and merge into local Room DB
        val deletedTaskIds = applicationContext
            .getSharedPreferences("dip_prefs", android.content.Context.MODE_PRIVATE)
            .getStringSet("deleted_task_ids", emptySet()) ?: emptySet()

        runCatching {
            val remoteTasks = supabase.fetchTasksForUser(uid)
            for (raw in remoteTasks) {
                val taskId = raw["id"] as? String ?: continue
                // Skip tasks that were permanently deleted on this device
                if (taskId in deletedTaskIds) {
                    runCatching { supabase.deleteTask(taskId) }
                    continue
                }
                val existing = db.taskDao().getById(taskId)
                val remoteUpdated = (raw["updated_at"] as? Long) ?: 0L
                if (existing == null || remoteUpdated > existing.updatedAt) {
                    val merged = mapToTask(raw) ?: continue

                    // Preserve local archived state — remote may not have this column yet
                    val finalTask = if (existing?.archived == true && !merged.archived) {
                        merged.copy(archived = true, archivedAt = existing.archivedAt)
                    } else merged

                    // ── Notify: new task assigned to me (pending acceptance)
                    if (existing == null &&
                        finalTask.status == TaskStatus.PENDING_ACCEPTANCE &&
                        finalTask.assignedToUserId == uid) {
                        DiagLog.d("ASSIGN_RECEIVE", "taskId=${finalTask.id.take(8)} type=${finalTask.taskType} assignedToMe=true status=${finalTask.status}")
                        val fromName = resolveContactName(uid, finalTask.createdByUserId, db, supabase)
                        NotificationHelper.showTaskInviteNotification(
                            applicationContext, finalTask.id, fromName
                        )
                    }

                    // ── Notify: task I created was accepted (PENDING → ACTIVE)
                    if (existing?.status == TaskStatus.PENDING_ACCEPTANCE &&
                        finalTask.status == TaskStatus.ACTIVE &&
                        finalTask.createdByUserId == uid) {
                        val acceptorName = resolveContactName(uid, finalTask.assignedToUserId, db, supabase)
                        NotificationHelper.showTaskUpdateNotification(
                            applicationContext,
                            "$acceptorName accepted \"${finalTask.title}\"",
                            finalTask.id,
                            "TASK_ACCEPTED"
                        )
                    }

                    db.taskDao().upsert(finalTask)
                    if (finalTask.assignedToUserId == uid) {
                        DiagLog.d("TASK_SYNC", "save assigned parent remoteTaskId=${finalTask.id.take(8)} localTaskId=${finalTask.id.take(8)}")
                    }

                    // Sync shopping items for shopping list tasks
                    if (merged.taskType == TaskType.SHOPPING_LIST) {
                        runCatching {
                            val remoteItems = supabase.fetchShoppingItemsForTask(taskId)
                            if (remoteItems.isNotEmpty()) {
                                val items = remoteItems.mapIndexedNotNull { i, raw ->
                                    val itemId = raw["id"] as? String ?: return@mapIndexedNotNull null
                                    ShoppingListItem(
                                        id = itemId,
                                        taskId = taskId,
                                        text = raw["text"] as? String ?: "",
                                        normalizedText = raw["normalized_text"] as? String ?: "",
                                        orderIndex = (raw["order_index"] as? Long)?.toInt() ?: i,
                                        checked = raw["checked"] as? Boolean ?: false,
                                        checkedByUserId = raw["checked_by_user_id"] as? String,
                                        checkedAt = (raw["checked_at"] as? Long),
                                        updatedByUserId = raw["updated_by_user_id"] as? String,
                                        syncStatus = "SYNCED",
                                        createdAt = (raw["created_at"] as? Long) ?: System.currentTimeMillis(),
                                        updatedAt = (raw["updated_at"] as? Long) ?: System.currentTimeMillis()
                                    )
                                }
                                db.shoppingListItemDao().upsertAll(items)
                            }
                        }
                    }
                }
            }
        }.onFailure { e ->
            val msg = e.message ?: ""
            if ("is_everywhere" in msg || "PGRST204" in msg) {
                DiagLog.e("SUPABASE_SCHEMA", "tasks.is_everywhere missing — run migration 20260617000000")
            } else {
                DiagLog.e("TASK_SYNC", "fetchTasksForUser failed reason=${msg.take(80)}")
            }
        }

        // Push pending shopping item check updates (shared list sync)
        runCatching {
            val pending = db.shoppingListItemDao().getPendingSync()
            DiagLog.d("SHOP_SYNC", "pendingItemsToSync=${pending.size}")
            for (item in pending) {
                runCatching {
                    supabase.updateShoppingItemChecked(
                        itemId = item.id,
                        taskId = item.taskId,
                        checked = item.checked,
                        checkedByUserId = item.checkedByUserId,
                        checkedAt = item.checkedAt,
                        updatedByUserId = item.updatedByUserId,
                        updatedAt = item.updatedAt
                    )
                }.onSuccess {
                    db.shoppingListItemDao().markSynced(item.id)
                    DiagLog.d("SHOP_SYNC", "synced itemId=${item.id.take(8)}")
                }.onFailure { e ->
                    DiagLog.e("SHOP_SYNC", "sync failed itemId=${item.id.take(8)} reason=${e.message?.take(80)}")
                }
            }
        }

        // Pull task_shares for current user and sync shared tasks
        runCatching {
            val shares = supabase.fetchTaskSharesForUser(uid)
            val nowMs = System.currentTimeMillis()
            val localShares = mutableListOf<com.davoyans.doinplace.data.model.TaskShare>()
            for (raw in shares) {
                val shareId = raw["id"] as? String ?: continue
                val taskId  = raw["task_id"] as? String ?: continue
                val ownerUid = raw["owner_user_id"] as? String ?: continue
                val sharedWith = raw["shared_with_user_id"] as? String ?: continue
                val status  = raw["status"] as? String ?: "ACTIVE"
                val displayName = runCatching {
                    if (sharedWith == uid) {
                        supabase.lookupUserById(ownerUid)?.second ?: ""
                    } else {
                        supabase.lookupUserById(sharedWith)?.second ?: ""
                    }
                }.getOrDefault("")
                localShares.add(com.davoyans.doinplace.data.model.TaskShare(
                    id = shareId,
                    taskId = taskId,
                    ownerUserId = ownerUid,
                    sharedWithUserId = sharedWith,
                    sharedWithDisplayName = displayName,
                    status = status,
                    createdAt = (raw["created_at"] as? Long) ?: nowMs,
                    updatedAt = (raw["updated_at"] as? Long) ?: nowMs
                ))
            }
            if (localShares.isNotEmpty()) db.taskShareDao().upsertAll(localShares)

            // Pull the actual shared tasks using explicit task IDs (PostgREST subqueries are not supported)
            val sharedWithMeIds = localShares
                .filter { it.sharedWithUserId == uid && it.status == "ACTIVE" }
                .map { it.taskId }
            DiagLog.d("TASK_SYNC", "sharedWithMeIds=${sharedWithMeIds.size}")
            val sharedTasksRaw = supabase.fetchTasksByIds(sharedWithMeIds)
            DiagLog.d("TASK_SYNC", "sharedTasksRaw=${sharedTasksRaw.size}")
            for (rawTask in sharedTasksRaw) {
                val merged = mapToTask(rawTask) ?: continue
                if (merged.id in deletedTaskIds) continue
                val existing = db.taskDao().getById(merged.id)
                val remoteUpdated = (rawTask["updated_at"] as? Long) ?: 0L
                if (existing == null || remoteUpdated > existing.updatedAt) {
                    val finalTask = if (existing?.archived == true && !merged.archived) {
                        merged.copy(archived = true, archivedAt = existing.archivedAt)
                    } else merged
                    // Notify receiver about newly shared task
                    if (existing == null) {
                        val ownerShare = localShares.find { it.taskId == finalTask.id && it.status == "ACTIVE" }
                        if (ownerShare != null) {
                            val fromName = resolveContactName(uid, ownerShare.ownerUserId, db, supabase)
                            NotificationHelper.showSharedTaskNotification(applicationContext, finalTask.id, finalTask.title, fromName)
                            DiagLog.d("NOTIFY", "shared task notification shown taskId=${finalTask.id.take(8)}")
                        }
                    }
                    db.taskDao().upsert(finalTask)
                    if (merged.taskType == TaskType.SHOPPING_LIST) {
                        runCatching {
                            val remoteItems = supabase.fetchShoppingItemsForTask(merged.id)
                            val items = remoteItems.mapIndexedNotNull { i, rI ->
                                val iId = rI["id"] as? String ?: return@mapIndexedNotNull null
                                val localItem = db.shoppingListItemDao().getForTask(merged.id)
                                    .find { it.id == iId }
                                val remoteUpd = (rI["updated_at"] as? Long) ?: 0L
                                if (localItem?.syncStatus == "PENDING_UPDATE" && localItem.updatedAt > remoteUpd) {
                                    DiagLog.d("SHOP_SYNC", "conflict winner=local itemId=${iId.take(8)}")
                                    return@mapIndexedNotNull null // keep local pending
                                }
                                ShoppingListItem(
                                    id = iId,
                                    taskId = merged.id,
                                    text = rI["text"] as? String ?: "",
                                    normalizedText = rI["normalized_text"] as? String ?: "",
                                    orderIndex = (rI["order_index"] as? Long)?.toInt() ?: i,
                                    checked = rI["checked"] as? Boolean ?: false,
                                    checkedByUserId = rI["checked_by_user_id"] as? String,
                                    checkedAt = rI["checked_at"] as? Long,
                                    updatedByUserId = rI["updated_by_user_id"] as? String,
                                    syncStatus = "SYNCED",
                                    createdAt = (rI["created_at"] as? Long) ?: System.currentTimeMillis(),
                                    updatedAt = (rI["updated_at"] as? Long) ?: System.currentTimeMillis()
                                )
                            }
                            if (items.isNotEmpty()) db.shoppingListItemDao().upsertAll(items)
                        }
                    }
                }
            }
        }

        // Pull pending contact invites addressed to this user
        runCatching {
            val invites = supabase.fetchPendingInvitesForUser(uid)
            for (raw in invites) {
                val id     = raw["id"] as? String ?: continue
                val fromId = raw["from_user_id"] as? String ?: continue
                if (fromId == uid) continue  // skip self-invites that shouldn't exist
                val requesterEmail = raw["requester_email_snapshot"] as? String
                val requesterName = raw["requester_display_name_snapshot"] as? String
                if (db.trustedContactDao().findContact(uid, fromId) == null) {
                    val fromProfile = runCatching { supabase.lookupUserById(fromId) }.getOrNull()
                    val resolvedEmail = requesterEmail?.takeIf { it.isNotBlank() }
                        ?: fromProfile?.first?.takeIf { it.isNotBlank() }
                        ?: ""
                    val resolvedName = requesterName?.takeIf { it.isNotBlank() }
                        ?: fromProfile?.second?.takeIf { it.isNotBlank() }
                        ?: ""
                    db.trustedContactDao().upsert(
                        com.davoyans.doinplace.data.model.TrustedContact(
                            id = id,
                            userId = uid,
                            contactUserId = fromId,
                            contactEmail = resolvedEmail,
                            contactDisplayName = resolvedName,
                            status = com.davoyans.doinplace.data.model.ContactStatus.PENDING
                        )
                    )
                    DiagLog.d("QR_OWNER_UI", "incoming request requesterEmail=${resolvedEmail.ifBlank { "missing" }}")
                }
            }
        }

        // Repair contacts that have blank or fallback "User xxx" display names
        runCatching {
            val all = db.trustedContactDao().getAllForUser(uid)
            fun isBadName(name: String) = name.isBlank() || name.matches(Regex("User [a-f0-9A-F]{4,8}"))
            val needsRepair = all.filter { isBadName(it.contactDisplayName) }
            var repaired = 0
            for (contact in needsRepair) {
                val profile = runCatching { supabase.lookupUserById(contact.contactUserId) }.getOrNull()
                val name = profile?.first?.takeIf { it.isNotBlank() }
                    ?: profile?.second?.takeIf { it.isNotBlank() }
                    ?: contact.contactEmail.takeIf { it.isNotBlank() }
                    ?: ""
                if (name != contact.contactDisplayName) {
                    DiagLog.d("CONTACTS_REPAIR", "old=\"${contact.contactDisplayName}\" new=\"$name\" userId=${contact.contactUserId.take(8)}")
                    db.trustedContactDao().upsert(contact.copy(contactDisplayName = name))
                    repaired++
                }
            }
            val accepted = all.filter { it.status == com.davoyans.doinplace.data.model.ContactStatus.ACCEPTED }
            val blankNamesAfter = db.trustedContactDao().getAllForUser(uid).count { isBadName(it.contactDisplayName) }
            DiagLog.d("CONTACTS", "accepted=${accepted.size} blankNamesBefore=${needsRepair.size} repaired=$repaired blankNamesAfter=$blankNamesAfter")
        }

        // Sync food health tags from Supabase (read-only global table)
        runCatching {
            val tags = supabase.fetchFoodHealthTags()
            if (tags.isNotEmpty()) {
                val entities = tags.mapNotNull { raw ->
                    val id   = raw["id"] as? String ?: return@mapNotNull null
                    val name = raw["normalized_name"] as? String ?: return@mapNotNull null
                    val lang = raw["language"] as? String ?: "en"
                    val tag  = raw["health_tag"] as? String ?: return@mapNotNull null
                    val sug  = raw["suggestion"] as? String
                    val sub  = raw["subcategory"] as? String
                    com.davoyans.doinplace.data.model.FoodHealthTag(id, name, lang, tag, sug, sub)
                }
                db.foodHealthDao().upsertAll(entities)
                DiagLog.d("FOOD_HEALTH", "synced ${entities.size} food health tags")
            }
        }.onFailure { e -> DiagLog.e("FOOD_HEALTH", "sync failed: ${e.message?.take(60)}") }

        // Ensure shopping items are fetched for all shopping tasks the user can access
        runCatching {
            val ownedShoppingTasks = db.taskDao().getOwnedShoppingTasks(uid)
            val assignedShoppingTasks = db.taskDao().getAssignedShoppingTasks(uid)
            val sharedShoppingTaskIds = db.taskShareDao().getSharedWithMe(uid).map { it.taskId }.toSet()
            val sharedShoppingTasks = sharedShoppingTaskIds.mapNotNull { db.taskDao().getById(it) }
                .filter { it.taskType == TaskType.SHOPPING_LIST }
            val assignedIds = assignedShoppingTasks.map { it.id }.toSet()
            val ownedIds = ownedShoppingTasks.map { it.id }.toSet()
            val sharedIds = sharedShoppingTasks.map { it.id }.toSet()
            val accessibleTasks = (ownedShoppingTasks + assignedShoppingTasks + sharedShoppingTasks).associateBy { it.id }.values.toList()
            DiagLog.d("SHOP_SYNC", "accessible ids owned=${ownedIds.size} assigned=${assignedIds.size} shared=${sharedIds.size} total=${accessibleTasks.size}")

            for (task in accessibleTasks) {
                runCatching {
                    val remoteItems = supabase.fetchShoppingItemsForTask(task.id)
                    DiagLog.d("SHOP_SYNC", "fetch assigned taskId=${task.id.take(8)} itemCount=${remoteItems.size}")
                    if (remoteItems.isEmpty()) {
                        DiagLog.d("SHOP_SYNC", "assigned items empty reason=no_remote_items taskId=${task.id.take(8)}")
                        return@runCatching
                    }
                    val existingLocal = db.shoppingListItemDao().getForTask(task.id)
                    val pendingIds = existingLocal.filter { it.syncStatus == "PENDING_UPDATE" }.map { it.id }.toSet()
                    val items = remoteItems.mapIndexedNotNull { i, rI ->
                        val iId = rI["id"] as? String ?: return@mapIndexedNotNull null
                        if (iId in pendingIds) return@mapIndexedNotNull null
                        val localItem = existingLocal.find { it.id == iId }
                        val remoteUpd = (rI["updated_at"] as? Long) ?: 0L
                        if (localItem != null && localItem.updatedAt >= remoteUpd) return@mapIndexedNotNull null
                        ShoppingListItem(
                            id = iId,
                            taskId = task.id,
                            text = rI["text"] as? String ?: "",
                            normalizedText = rI["normalized_text"] as? String ?: "",
                            orderIndex = (rI["order_index"] as? Long)?.toInt() ?: i,
                            checked = rI["checked"] as? Boolean ?: false,
                            checkedByUserId = rI["checked_by_user_id"] as? String,
                            checkedAt = rI["checked_at"] as? Long,
                            updatedByUserId = rI["updated_by_user_id"] as? String,
                            addedByUserId = rI["added_by_user_id"] as? String,
                            addedByDisplayName = rI["added_by_display_name"] as? String,
                            syncStatus = "SYNCED",
                            createdAt = (rI["created_at"] as? Long) ?: System.currentTimeMillis(),
                            updatedAt = remoteUpd.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                    }
                    if (items.isNotEmpty()) {
                        db.shoppingListItemDao().upsertAll(items)
                        DiagLog.d("SHOP_SYNC", "save assigned taskId=${task.id.take(8)} saved=${items.size}")
                    }
                }.onFailure { e ->
                    DiagLog.e("SHOP_SYNC", "assigned fetch failed taskId=${task.id.take(8)} reason=${e.message?.take(60)}")
                }
            }
        }.onFailure { e -> DiagLog.e("SHOP_SYNC", "assigned sync block failed: ${e.message?.take(60)}") }

        // Upgrade PENDING_SENT contacts whose invite was accepted by the recipient
        runCatching {
            val acceptedOutgoing = supabase.fetchAcceptedOutgoingInvites(uid)
            for (raw in acceptedOutgoing) {
                val toUserId = raw["to_user_id"] as? String ?: continue
                val existing = db.trustedContactDao().findContact(uid, toUserId) ?: continue
                if (existing.status == com.davoyans.doinplace.data.model.ContactStatus.PENDING_SENT) {
                    db.trustedContactDao().updateStatus(existing.id, "ACCEPTED")
                    DiagLog.d("CONTACTS", "PENDING_SENT → ACCEPTED contactUserId=${toUserId.take(8)}")
                }
            }
        }.onFailure { e -> DiagLog.e("CONTACTS", "fetchAcceptedOutgoingInvites failed: ${e.message?.take(60)}") }

        return Result.success()
    }

    private suspend fun resolveContactName(
        uid: String,
        contactUserId: String,
        db: com.davoyans.doinplace.data.db.AppDatabase,
        supabase: SupabaseClient
    ): String {
        val prefId = ContactDisplayRepository.makeId(uid, contactUserId)
        val pref = runCatching { db.contactDisplayPrefDao().getById(prefId) }.getOrNull()
        val contact = runCatching { db.trustedContactDao().findContact(uid, contactUserId) }.getOrNull()
        val profile = runCatching { supabase.lookupUserById(contactUserId) }.getOrNull()
        val resolved = ContactDisplayNameResolver.resolveForNotification(
            viewerUserId = uid,
            otherUserId = contactUserId,
            contact = contact,
            pref = pref,
            emailSnapshot = profile?.first,
            nameSnapshot = profile?.second
        )
        val resolvedName = resolved.primary.ifBlank {
            profile?.first?.takeIf { it.isNotBlank() }
                ?: profile?.second?.takeIf { it.isNotBlank() }
                ?: "Someone"
        }
        val source = if (resolved.source == com.davoyans.doinplace.data.repository.DisplayNameSource.UNKNOWN) {
            when {
                !profile?.first.isNullOrBlank() -> "profile_email"
                !profile?.second.isNullOrBlank() -> "profile_name"
                else -> "fallback"
            }
        } else {
            resolved.source.name.lowercase()
        }
        DiagLog.d("NAME_RESOLVER", "context=notification viewer=${uid.take(8)} other=${contactUserId.take(8)} source=$source result=$resolvedName")
        return resolvedName
    }

    private fun mapToTask(raw: Map<String, Any?>): com.davoyans.doinplace.data.model.Task? {
        val id = raw["id"] as? String ?: return null
        return com.davoyans.doinplace.data.model.Task(
            id = id,
            title = raw["title"] as? String ?: "",
            description = raw["description"] as? String,
            createdByUserId  = raw["created_by_user_id"]   as? String ?: "",
            assignedToUserId = raw["assigned_to_user_id"]  as? String ?: "",
            placeName     = raw["place_name"]     as? String ?: "",
            address       = raw["address"]        as? String,
            latitude      = (raw["latitude"]      as? Double)  ?: 0.0,
            longitude     = (raw["longitude"]     as? Double)  ?: 0.0,
            radiusMeters  = (raw["radius_meters"] as? Long)?.toInt() ?: 100,
            status = runCatching {
                TaskStatus.valueOf(raw["status"] as? String ?: "ACTIVE")
            }.getOrDefault(TaskStatus.ACTIVE),
            arrivalShareAllowed = raw["arrival_share_allowed"] as? Boolean ?: false,
            activeFromDate    = raw["active_from_date"]    as? String,
            activeToDate      = raw["active_to_date"]      as? String,
            activeDaysOfWeek  = raw["active_days_of_week"] as? String,
            activeStartTime   = raw["active_start_time"]   as? String,
            activeEndTime     = raw["active_end_time"]     as? String,
            remindUntilDone   = raw["remind_until_done"]   as? Boolean ?: true,
            priority = run {
                val raw2 = raw["priority"] as? String ?: "NO_RUSH"
                when (raw2) {
                    "HIGH"               -> TaskPriority.URGENT
                    "NORMAL","LOW","EASY" -> TaskPriority.NO_RUSH
                    else -> runCatching { TaskPriority.valueOf(raw2) }.getOrDefault(TaskPriority.NO_RUSH)
                }
            },
            placeMode = runCatching {
                PlaceMode.valueOf(raw["place_mode"] as? String ?: "EXACT")
            }.getOrDefault(PlaceMode.EXACT),
            placeTypeId = raw["place_type_id"] as? String,
            placeTypeName = raw["place_type_name"] as? String,
            taskType = runCatching {
                TaskType.valueOf(raw["task_type"] as? String ?: "SIMPLE")
            }.getOrDefault(TaskType.SIMPLE),
            isEverywhere = raw["is_everywhere"] as? Boolean ?: false,
            pendingSync = false,
            createdAt = (raw["created_at"] as? Long) ?: System.currentTimeMillis(),
            updatedAt = (raw["updated_at"] as? Long) ?: System.currentTimeMillis()
        )
    }

    private fun isDuplicateTaskEventKeyError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return "23505" in msg && "task_events_pkey" in msg
    }

    private fun isRlsDeniedError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return "403" in msg || "row-level security" in msg
    }

    companion object {
        private const val WORK_NAME = "do_in_place_sync"
        // Singleton mutex shared across all SyncWorker instances (periodic + one-time).
        val taskEventsSyncMutex = Mutex()

        // Event types that only make sense while a task is still ACTIVE.
        private val REMINDER_ONLY_TYPES = setOf(
            TaskEventType.REMINDED,
            TaskEventType.DUE_REMINDER_SHOWN,
            TaskEventType.PLACE_REMINDER_AUTO_DISMISSED,
            TaskEventType.ARRIVED_NEAR_PLACE
        )

        fun schedulePeriodicSync(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
