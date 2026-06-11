package com.davoyans.doinplace.engine

import android.location.Location
import com.davoyans.doinplace.data.model.SavedPlace

object HomeDetector {
    private const val HOME_RADIUS_METERS = 200f
    private val HOME_KEYWORDS = setOf("home", "house", "дом", "тун", "տուն", "dom")

    fun isAtHome(location: Location?, savedPlaces: List<SavedPlace>): Boolean {
        if (location == null) return false
        val homes = savedPlaces.filter { !it.deleted && isHomeName(it.name) }
        if (homes.isEmpty()) return false
        return homes.any { home ->
            val result = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                home.latitude, home.longitude, result
            )
            result[0] <= HOME_RADIUS_METERS
        }
    }

    fun isHomeName(name: String): Boolean {
        val lower = name.lowercase().trim()
        return HOME_KEYWORDS.any { lower.contains(it) }
    }
}
