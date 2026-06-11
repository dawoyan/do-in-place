package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.SavedPlace

class PlaceRepository(private val db: AppDatabase) {
    fun observeAll(uid: String) = db.savedPlaceDao().observeAll(uid)

    suspend fun getAll(uid: String) = db.savedPlaceDao().getAll(uid)

    suspend fun getById(id: String) = db.savedPlaceDao().getById(id)

    suspend fun save(place: SavedPlace) {
        val existing = db.savedPlaceDao().getAll(place.userId)
        val duplicate = existing.any { p ->
            p.id != place.id &&
            p.name.trim().lowercase() == place.name.trim().lowercase() &&
            kotlin.math.abs(p.latitude  - place.latitude)  < 0.0005 &&
            kotlin.math.abs(p.longitude - place.longitude) < 0.0005
        }
        if (!duplicate) db.savedPlaceDao().upsert(place)
    }

    suspend fun softDelete(id: String) = db.savedPlaceDao().softDelete(id)
}
