package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.db.SavedCardDao
import com.davoyans.doinplace.data.model.SavedCardEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SavedCardRepositoryTest {
    @Test
    fun saveUsesLocalDaoAndDetectsDuplicate() = runTest {
        val dao = FakeSavedCardDao()
        val repo = SavedCardRepository(dao)
        val first = SavedCardEntity(id = "1", name = "Shop", codeType = "QR", codeValue = "abc")

        val firstResult = repo.save(first)
        val duplicateResult = repo.save(first.copy(id = "2"))

        assertIs<SavedCardSaveResult.Saved>(firstResult)
        assertIs<SavedCardSaveResult.Duplicate>(duplicateResult)
        assertEquals(1, dao.items.size)
    }

    private class FakeSavedCardDao : SavedCardDao {
        val items = linkedMapOf<String, SavedCardEntity>()
        private val flow = MutableStateFlow<List<SavedCardEntity>>(emptyList())

        override fun observeAll(): Flow<List<SavedCardEntity>> = flow

        override fun observeById(id: String): Flow<SavedCardEntity?> = MutableStateFlow(items[id])

        override suspend fun getById(id: String): SavedCardEntity? = items[id]

        override suspend fun findByCodeValue(codeValue: String): SavedCardEntity? =
            items.values.firstOrNull { it.codeValue == codeValue }

        override suspend fun upsert(card: SavedCardEntity) {
            items[card.id] = card
            flow.value = items.values.toList()
        }

        override suspend fun deleteById(id: String) {
            items.remove(id)
            flow.value = items.values.toList()
        }
    }
}
