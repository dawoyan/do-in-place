package com.davoyans.doinplace.geofence

import android.content.Context
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.repository.GeofenceRepository

/**
 * COST BOUNDARY – LOCATION REMINDER ORCHESTRATOR
 * ────────────────────────────────────────────────
 * Manages the full lifecycle of geofence-based reminders.
 * All geofences are built from Task.latitude / Task.longitude / Task.radiusMeters
 * which are already stored in the local Room database.
 *
 * This class MUST NOT:
 *  - Call Places API, Maps SDK, or any geocoding endpoint
 *  - Write GPS coordinates or location history to Firestore
 *  - Perform any HTTP request
 *
 * Geofencing via Android GeofencingClient is free (part of Google Play Services).
 */
class LocationReminderManager(context: Context) {

    private val geofenceRepo = GeofenceRepository(context)
    private val db = AppDatabase.get(context)

    /** Call when a task becomes active (created by self or accepted shared task). */
    suspend fun onTaskActivated(task: Task) {
        if (task.status != TaskStatus.ACTIVE) return
        // Everywhere tasks use time-based scheduling only — no geofence
        if (task.isEverywhere) return
        // TYPE tasks have no fixed geofence in v1 — type-based detection is handled separately
        if (task.placeMode == PlaceMode.TYPE) return
        if (task.latitude == 0.0 && task.longitude == 0.0) return
        geofenceRepo.register(task)
    }

    /** Call when a task is done, cancelled, rejected, or expired. */
    suspend fun onTaskDeactivated(taskId: String) {
        geofenceRepo.remove(taskId)
    }

    /**
     * Called on device boot or app launch to restore geofences that were cleared by the OS.
     * Reads from Room only — no network call.
     */
    suspend fun restoreOnBoot(uid: String) {
        val activeTasks = db.taskDao().getActiveTasks(uid)
            .filter { !it.isEverywhere && it.placeMode == PlaceMode.EXACT }
        geofenceRepo.restoreAll(activeTasks)
    }

    fun hasLocationPermission(): Boolean = geofenceRepo.hasRequiredPermissions()
}
