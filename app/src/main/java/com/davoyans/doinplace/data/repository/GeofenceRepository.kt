package com.davoyans.doinplace.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.geofence.GeofenceBroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

/**
 * COST BOUNDARY – GEOFENCE REGISTRATION
 * ──────────────────────────────────────
 * This repository registers and removes Android geofences using coordinates that are
 * already saved in the local Room database (latitude + longitude + radiusMeters on Task).
 *
 * RULES:
 *  1. NEVER call Places API, Maps SDK, Geocoding API, or any HTTP endpoint from this class.
 *  2. NEVER read or write GPS history to Firestore.
 *  3. Geofence IDs are task IDs – no location data leaves the device from this class.
 *  4. All coordinates must come from the Task object already persisted in Room.
 *
 * Background monitoring costs: $0. Uses Android GeofencingClient which is part of
 * Google Play Services and is free to use.
 */
class GeofenceRepository(private val context: Context) {

    private val client: GeofencingClient = LocationServices.getGeofencingClient(context)

    fun hasRequiredPermissions(): Boolean {
        val fine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg     = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return (fine || coarse) && bg
    }

    /** Register a geofence for one active task. Uses only task.latitude/longitude/radiusMeters. */
    @SuppressLint("MissingPermission")
    suspend fun register(task: Task) {
        if (!hasRequiredPermissions()) return
        // Coordinates come exclusively from the saved Task — no API call made here.
        val geofence = buildGeofence(task.id, task.latitude, task.longitude, task.radiusMeters)
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        runCatching { client.addGeofences(request, pendingIntent()).await() }
    }

    /** Remove a geofence by task ID. No network call. */
    suspend fun remove(taskId: String) {
        runCatching { client.removeGeofences(listOf(taskId)).await() }
    }

    /** Re-register all active tasks after device reboot. No API calls. */
    @SuppressLint("MissingPermission")
    suspend fun restoreAll(tasks: List<Task>) {
        if (!hasRequiredPermissions() || tasks.isEmpty()) return
        val geofences = tasks.map {
            // Coordinates come exclusively from saved Task rows in Room.
            buildGeofence(it.id, it.latitude, it.longitude, it.radiusMeters)
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()
        runCatching { client.addGeofences(request, pendingIntent()).await() }
    }

    private fun buildGeofence(id: String, lat: Double, lng: Double, radius: Int): Geofence =
        Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(lat, lng, radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
