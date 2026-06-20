package com.davoyans.doinplace.geofence

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskEvent
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.engine.ContextAwareReminderEngine
import com.davoyans.doinplace.engine.UsualShoppingEngine
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.notification.SnoozeAlarmReceiver
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.PlaceLabelResolver
import com.davoyans.doinplace.util.ReminderItemFilter
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * COST BOUNDARY – GEOFENCE TRIGGER HANDLER
 * ─────────────────────────────────────────
 * Receives geofence ENTER/EXIT events from Android OS (free, no API charges).
 *
 * THIS CLASS MUST NEVER:
 *  - Call Places API, Geocoding API, Maps SDK, or any HTTP endpoint
 *  - Write GPS history or coordinates to Firestore
 *  - Start any network request
 *
 * Allowed: show/cancel local notification, write TaskEvent to Room (local DB only),
 * read last known location from FusedLocationProviderClient (no GPS fix, no network).
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT) return

        val db = AppDatabase.get(context)
        val triggeredIds = event.triggeringGeofences?.map { it.requestId } ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val currentLocation = try {
                LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            } catch (_: Exception) { null }

            if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                handleExit(context, db, triggeredIds, currentLocation)
            } else {
                handleEnter(context, db, triggeredIds, currentLocation)
            }
        }
    }

    private suspend fun handleEnter(
        context: Context,
        db: AppDatabase,
        triggeredIds: List<String>,
        currentLocation: android.location.Location?
    ) {
        val engine  = ContextAwareReminderEngine(context, db)
        val snapshot = engine.buildSnapshot(currentLocation)
        val uid     = SupabaseAuthClient(context).getCurrentUserId() ?: ""

        // Check usual shopping suggestion for grocery place types
        val usualShoppingEngine = UsualShoppingEngine(db)
        for (taskId in triggeredIds) {
            val task = db.taskDao().getById(taskId) ?: continue
            val placeTypeKey = task.placeTypeId ?: continue
            if (!usualShoppingEngine.isGroceryType(placeTypeKey)) continue
            val prefs = context.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("usual_shopping_enabled", true)) continue
            runCatching {
                if (usualShoppingEngine.shouldSuggest(uid, placeTypeKey)) {
                    val placeName = task.placeName.ifBlank { "the store" }
                    NotificationHelper.showUsualShoppingNotification(context, placeTypeKey, placeName)
                    usualShoppingEngine.suppressForToday(uid, placeTypeKey)
                }
            }
        }

        for (taskId in triggeredIds) {
            val task = db.taskDao().getById(taskId) ?: continue
            if (task.status != TaskStatus.ACTIVE) continue
            if (!isInTimeWindow(task.activeStartTime, task.activeEndTime)) continue

            val distanceMeters = computeDistance(currentLocation, task)
            if (!shouldShowReminder(task, currentLocation)) continue

            if (task.taskType == TaskType.SHOPPING_LIST) {
                val reminderItems = ReminderItemFilter.activeItems(task.id, db.shoppingListItemDao().getForTaskIncludingDeleted(task.id))
                if (reminderItems.isEmpty()) continue
            }
            val resolvedPlace = PlaceLabelResolver.resolve(
                exactPlaceName = task.placeName,
                exactPlaceAddress = task.address,
                savedPlaceName = task.placeName
            )

            // Check exact-place notification rules (MUTE_HERE / SNOOZE_HERE)
            val exactPlaceKey = task.placeId ?: "${task.id}"
            val now = System.currentTimeMillis()
            val muteRule = db.taskPlaceNotificationRuleDao().getRule(task.id, exactPlaceKey, "MUTE_HERE")
            if (muteRule != null && muteRule.active) {
                DiagLog.d("PLACE_NOTIFY", "suppressed rule=MUTE_HERE taskId=${task.id.take(8)} key=$exactPlaceKey")
                continue
            }
            val snoozeRule = db.taskPlaceNotificationRuleDao().getRule(task.id, exactPlaceKey, "SNOOZE_HERE")
            if (snoozeRule != null && snoozeRule.active && (snoozeRule.snoozedUntil ?: 0L) > now) {
                DiagLog.d("PLACE_NOTIFY", "suppressed rule=SNOOZE_HERE until=${snoozeRule.snoozedUntil} taskId=${task.id.take(8)}")
                continue
            }

            // Context-aware gate
            val decision = engine.evaluate(
                task, snapshot,
                matchedPlaceName    = task.placeName.takeIf { it.isNotBlank() },
                matchedPlaceAddress = task.address,
                distanceMeters      = distanceMeters
            )
            engine.recordOutcome(task, snapshot, engine.computeDueUrgency(task, snapshot.nowMillis), decision, uid)
            if (!decision.shouldNotify) continue

            DiagLog.d("PLACE_NOTIFY", "shown taskId=${task.id.take(8)} exactPlaceKey=$exactPlaceKey")
            val hasCoords = task.latitude != 0.0 || task.longitude != 0.0
            NotificationHelper.showPlaceReminderNotification(
                context = context,
                taskId = task.id,
                taskTitle = task.title,
                exactPlaceName = resolvedPlace.primaryName,
                exactPlaceAddress = resolvedPlace.address,
                savedPlaceName = task.placeName,
                priority = task.priority,
                placeLat = if (hasCoords) task.latitude else null,
                placeLng = if (hasCoords) task.longitude else null,
                exactPlaceKey = exactPlaceKey
            )

            SnoozeAlarmReceiver.scheduleFirstRepeat(context, task.id, task.priority)

            val placeAddr = task.address?.takeIf { it.isNotBlank() && it != task.placeName }
            db.taskEventDao().insert(TaskEvent(
                id = UUID.randomUUID().toString(),
                taskId = task.id,
                type = TaskEventType.REMINDED,
                actorUserId = uid,
                placeName = task.placeName.takeIf { it.isNotBlank() },
                placeAddress = placeAddr,
                synced = false
            ))

            if (task.arrivalShareAllowed) {
                db.taskEventDao().insert(TaskEvent(
                    id = UUID.randomUUID().toString(),
                    taskId = task.id,
                    type = TaskEventType.ARRIVED_NEAR_PLACE,
                    actorUserId = uid,
                    synced = false
                ))
            }

            db.taskDao().touchReminderShown(task.id)
        }
    }

    private suspend fun handleExit(
        context: Context,
        db: AppDatabase,
        triggeredIds: List<String>,
        currentLocation: android.location.Location?
    ) {
        val uid = SupabaseAuthClient(context).getCurrentUserId() ?: ""
        for (taskId in triggeredIds) {
            // Only act if a place notification is still outstanding
            if (!NotificationHelper.isPlaceNotifActive(context, taskId)) continue

            val task = db.taskDao().getById(taskId) ?: continue
            if (task.status != TaskStatus.ACTIVE) {
                // Task no longer active — clean up silently
                NotificationHelper.cancelPlaceNotif(context, taskId)
                continue
            }

            // Do not dismiss based on stale/missing location
            if (!isLocationFreshEnough(currentLocation)) {
                DiagLog.d("GEOFENCE_EXIT", "taskId=${taskId.take(8)} skip: stale/null location")
                continue
            }

            // Confirm user is actually outside radius + buffer before dismissing
            if (!isOutsideRadius(task, currentLocation!!)) {
                DiagLog.d("GEOFENCE_EXIT", "taskId=${taskId.take(8)} skip: still inside radius")
                continue
            }

            // Auto-dismiss the notification
            NotificationHelper.cancelPlaceNotif(context, taskId)
            SnoozeAlarmReceiver.cancelRepeat(context, taskId)

            val placeAddr = task.address?.takeIf { it.isNotBlank() && it != task.placeName }
            db.taskEventDao().insert(TaskEvent(
                id = UUID.randomUUID().toString(),
                taskId = task.id,
                type = TaskEventType.PLACE_REMINDER_AUTO_DISMISSED,
                actorUserId = uid,
                placeName = task.placeName.takeIf { it.isNotBlank() },
                placeAddress = placeAddr,
                reason = "left place",
                synced = false
            ))

            DiagLog.d("GEOFENCE_EXIT", "taskId=${taskId.take(8)} notification auto-dismissed after leaving")
        }
    }

    private fun shouldShowReminder(task: Task, location: android.location.Location?): Boolean {
        val taskId = task.id.take(8)
        if (task.latitude == 0.0 && task.longitude == 0.0) {
            DiagLog.d("REMINDER", "validate taskId=$taskId coords=0,0 notify=false")
            return false
        }
        if (location == null) {
            DiagLog.d("REMINDER", "validate taskId=$taskId location=null notify=false")
            return false
        }
        val ageMs = System.currentTimeMillis() - location.time
        val isStale = when {
            ageMs <= 5 * 60_000L  -> false
            ageMs <= 15 * 60_000L -> location.accuracy > 100f
            else                  -> true
        }
        if (isStale) {
            DiagLog.d("REMINDER", "validate taskId=$taskId stale ageMs=$ageMs accuracy=${location.accuracy} notify=false")
            return false
        }
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            location.latitude, location.longitude,
            task.latitude, task.longitude,
            results
        )
        val distanceMeters = results[0]
        val accuracyBuffer = location.accuracy.coerceIn(50f, 200f)
        val shouldNotify = distanceMeters <= task.radiusMeters + accuracyBuffer
        DiagLog.d("REMINDER",
            "validate taskId=$taskId dist=${distanceMeters.toInt()} " +
            "radius=${task.radiusMeters} buf=${accuracyBuffer.toInt()} notify=$shouldNotify")
        return shouldNotify
    }

    /**
     * Returns true if the location is fresh enough to trust for auto-dismiss decisions.
     * Age > 15 min → never auto-dismiss. Age 5–15 min → only if accuracy < 100 m.
     */
    private fun isLocationFreshEnough(location: android.location.Location?): Boolean {
        if (location == null) return false
        val ageMs = System.currentTimeMillis() - location.time
        return when {
            ageMs > 15 * 60_000L  -> false
            ageMs > 5  * 60_000L  -> location.accuracy <= 100f
            else                   -> true
        }
    }

    /**
     * Returns true if user is confirmed outside task radius + accuracy buffer.
     * This guards against false-positive EXIT events.
     */
    private fun isOutsideRadius(task: Task, location: android.location.Location): Boolean {
        if (task.latitude == 0.0 && task.longitude == 0.0) return false
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            location.latitude, location.longitude,
            task.latitude, task.longitude,
            results
        )
        val distanceMeters = results[0]
        val buffer = location.accuracy.coerceIn(50f, 200f)
        return distanceMeters > task.radiusMeters + buffer
    }

    private fun computeDistance(location: android.location.Location?, task: Task): Float? {
        if (location == null || (task.latitude == 0.0 && task.longitude == 0.0)) return null
        val result = FloatArray(1)
        android.location.Location.distanceBetween(
            location.latitude, location.longitude, task.latitude, task.longitude, result
        )
        return result[0]
    }

    private fun isInTimeWindow(start: String?, end: String?): Boolean {
        if (start == null || end == null) return true
        return try {
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            val now = LocalTime.now()
            val s = LocalTime.parse(start, fmt)
            val e = LocalTime.parse(end, fmt)
            now.isAfter(s) && now.isBefore(e)
        } catch (_: Exception) { true }
    }
}
