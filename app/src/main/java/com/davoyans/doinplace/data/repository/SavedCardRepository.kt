package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.db.SavedCardDao
import com.davoyans.doinplace.data.model.SavedCardEntity

sealed interface SavedCardSaveResult {
    data class Saved(val cardId: String) : SavedCardSaveResult
    data class Duplicate(val existing: SavedCardEntity) : SavedCardSaveResult
}

class SavedCardRepository(private val dao: SavedCardDao) {
    fun observeAll() = dao.observeAll()

    suspend fun getById(id: String) = dao.getById(id)

    suspend fun save(card: SavedCardEntity, allowDuplicate: Boolean = false): SavedCardSaveResult {
        val duplicate = dao.findByCodeValue(card.codeValue)
        if (!allowDuplicate && duplicate != null && duplicate.id != card.id) {
            return SavedCardSaveResult.Duplicate(duplicate)
        }
        dao.upsert(card)
        return SavedCardSaveResult.Saved(card.id)
    }

    suspend fun deleteById(id: String) = dao.deleteById(id)
}
