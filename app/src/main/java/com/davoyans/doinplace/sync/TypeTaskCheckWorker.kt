package com.davoyans.doinplace.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.location.GeoapifyPlaceSearchProvider
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.TaskEvent
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.places.PlaceTypeEngine
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.engine.ContextAwareReminderEngine
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.notification.SnoozeAlarmReceiver
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.PlaceLabelResolver
import com.davoyans.doinplace.util.ReminderItemFilter
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Periodically checks whether the user is near a place matching any active TYPE-mode task.
 *
 * TYPE tasks use "any matching pharmacy / supermarket / etc." instead of a fixed coordinate,
 * so they cannot use Android geofences. This worker fills that gap:
 *   1. Gets the device's last known location (no new GPS fix — free, fast, battery-safe).
 *   2. For each active TYPE task, queries Geoapify for places of the matching category.
 *   3. If any result is within the task's radius → shows a reminder notification.
 *
 * Runs every 15 minutes when the device has a network connection.
 * A 30-minute per-task cooldown prevents repeated notifications.
 */
class TypeTaskCheckWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val WORK_NAME = "type_task_check"
        private const val COOLDOWN_MS = 30 * 60 * 1000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TypeTaskCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }

        /** Run immediately — call when a new TYPE task is saved so it triggers at once. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<TypeTaskCheckWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val hasLocation = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return Result.success()

        val uid = SupabaseAuthClient(ctx).getCurrentUserId() ?: return Result.success()
        val db = AppDatabase.get(ctx)

        val activeTasks = db.taskDao().getActiveTasks(uid)
            .filter { it.placeMode == PlaceMode.TYPE && it.status == TaskStatus.ACTIVE }
        if (activeTasks.isEmpty()) return Result.success()

        val location = getLastKnownLocation() ?: return Result.success()
        val now = System.currentTimeMillis()

        // Reject stale location: >5 min requires good accuracy; >15 min always skip
        val locationAgeMs = now - location.time
        val locationStale = when {
            locationAgeMs <= 5 * 60_000L  -> false
            locationAgeMs <= 15 * 60_000L -> location.accuracy > 100f
            else                          -> true
        }
        if (locationStale) {
            DiagLog.d("TYPETASK", "location stale ageMs=$locationAgeMs accuracy=${location.accuracy} — skipping")
            return Result.success()
        }

        val geoapify = GeoapifyPlaceSearchProvider()
        val engine   = ContextAwareReminderEngine(ctx, db)
        val snapshot = engine.buildSnapshot(location)

        // Cache per category so we don't call Geoapify twice for the same type in one pass
        val categoryResultCache = mutableMapOf<String, List<com.davoyans.doinplace.data.location.PlaceSearchResult>>()

        for (task in activeTasks) {
            // Respect per-task cooldown
            val lastShown = task.lastReminderShownAt
            if (lastShown != null && now - lastShown < COOLDOWN_MS) continue

            // Respect task's active time window
            if (!isInTimeWindow(task.activeStartTime, task.activeEndTime)) continue

            val category = PlaceTypeEngine.GEOAPIFY_CATEGORIES[task.placeTypeId] ?: continue

            // Use the larger of the task radius or 500 m as the Geoapify search radius.
            // The task's own radius is then used for the final distance filter.
            val searchRadius = maxOf(task.radiusMeters, 500)
            val cacheKey = "$category:$searchRadius"

            val nearby = categoryResultCache.getOrPut(cacheKey) {
                geoapify.searchNearby(
                    location.latitude, location.longitude,
                    searchRadius, category
                ).getOrElse { emptyList() }
            }

            val triggeringPlace = nearby.firstOrNull { place ->
                distanceMeters(
                    location.latitude, location.longitude,
                    place.latitude, place.longitude
                ) <= task.radiusMeters
            }

            if (triggeringPlace != null) {
                val dist = distanceMeters(
                    location.latitude, location.longitude,
                    triggeringPlace.latitude, triggeringPlace.longitude
                )
                val decision = engine.evaluate(
                    task, snapshot,
                    matchedPlaceName    = triggeringPlace.title,
                    matchedPlaceAddress = triggeringPlace.formattedAddress.takeIf { it.isNotBlank() },
                    distanceMeters      = dist
                )
                engine.recordOutcome(task, snapshot, engine.computeDueUrgency(task, snapshot.nowMillis), decision, uid)
                if (!decision.shouldNotify) continue

                if (task.taskType == TaskType.SHOPPING_LIST) {
                    val reminderItems = ReminderItemFilter.activeItems(task.id, db.shoppingListItemDao().getForTaskIncludingDeleted(task.id))
                    if (reminderItems.isEmpty()) continue
                }
                val resolvedPlace = PlaceLabelResolver.resolve(
                    exactPlaceName = triggeringPlace.title,
                    exactPlaceAddress = triggeringPlace.formattedAddress,
                    savedPlaceName = task.placeName,
                    providerPlaceName = triggeringPlace.title,
                    placeTypeName = task.placeTypeName
                )
                NotificationHelper.showPlaceReminderNotification(
                    context = ctx,
                    taskId = task.id,
                    taskTitle = task.title,
                    exactPlaceName = resolvedPlace.primaryName,
                    exactPlaceAddress = resolvedPlace.address,
                    savedPlaceName = task.placeName,
                    providerPlaceName = triggeringPlace.title,
                    placeTypeName = task.placeTypeName,
                    priority = task.priority,
                    placeLat = triggeringPlace.latitude,
                    placeLng = triggeringPlace.longitude
                )
                SnoozeAlarmReceiver.scheduleFirstRepeat(ctx, task.id, task.priority)
                db.taskEventDao().insert(
                    TaskEvent(
                        id = UUID.randomUUID().toString(),
                        taskId = task.id,
                        type = TaskEventType.REMINDED,
                        actorUserId = uid,
                        synced = false
                    )
                )
                db.taskDao().touchReminderShown(task.id)
            }
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): android.location.Location? =
        try {
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation.await()
        } catch (_: Exception) {
            null
        }

    private fun distanceMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Float {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, result)
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
