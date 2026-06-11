package com.davoyans.doinplace.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * COST BOUNDARY
 * ─────────────
 * This repository is the ONLY place allowed to resolve human-readable addresses.
 * It uses Android's built-in Geocoder (billed to device, not to this developer account)
 * and FusedLocationProviderClient (free, no API key).
 *
 * NEVER import or call:
 *   - com.google.android.libraries.places.*   (Places SDK – pay-per-request)
 *   - com.google.maps.*                        (Maps SDK)
 *   - Any Geocoding/Directions/Distance API endpoint
 *
 * This repository is called ONLY from the Add/Edit Place UI screen, never from background services.
 */
class PlaceSearchRepository(private val context: Context) {

    data class ResolvedLocation(
        val latitude: Double,
        val longitude: Double,
        val suggestedName: String,
        val address: String?
    )

    /**
     * Returns the last known location passively (no GPS activation, instant).
     * Used to bias/sort search results without a button press.
     * Returns null if no cached fix is available or permission is not granted.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { loc ->
                    cont.resume(if (loc != null) loc.latitude to loc.longitude else null)
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    /**
     * Returns the device's current GPS fix.
     * Uses FusedLocationProviderClient – free, no API key, no billing.
     * Called only when the user taps "Use my current location" on the UI.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): ResolvedLocation? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = suspendCancellableCoroutine<android.location.Location?> { cont ->
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc -> cont.resume(loc) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return null

        // Android Geocoder – uses device's own service, not billed to this developer account.
        val address = reverseGeocodeOnDevice(location.latitude, location.longitude)
        val name = address ?: "${fmtCoord(location.latitude)}, ${fmtCoord(location.longitude)}"
        return ResolvedLocation(location.latitude, location.longitude, name, address)
    }

    /**
     * Reverse-geocode using the on-device Android Geocoder.
     * Returns null silently if the device has no network or geocoder service.
     * NEVER calls a paid API endpoint.
     */
    private suspend fun reverseGeocodeOnDevice(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(context)
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1) { addresses ->
                        cont.resume(addresses.firstOrNull()?.let(::formatAddress))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.let(::formatAddress)
            }
        } catch (_: Exception) { null }
    }

    private fun formatAddress(addr: android.location.Address): String {
        return listOfNotNull(
            addr.thoroughfare,
            addr.subLocality ?: addr.locality,
            addr.countryName
        ).joinToString(", ").ifBlank { addr.getAddressLine(0) }
    }

    private fun fmtCoord(v: Double) = "%.4f".format(v)
}
