package com.davoyans.doinplace.data.shopping

import com.davoyans.doinplace.data.db.ShoppingPlaceItemOrderDao
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.ShoppingPlaceItemOrder
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.engine.ShoppingItemCanonicalizer
import com.davoyans.doinplace.util.DiagLog
import java.util.UUID

class ShoppingOrderEngine(private val dao: ShoppingPlaceItemOrderDao) {
    private val autoSortUseCase = ShoppingAutoSortUseCase(::normalize)

    fun normalize(text: String): String =
        ShoppingItemCanonicalizer.normalize(text)

    fun buildPlaceKey(task: Task): String {
        if (!task.placeId.isNullOrBlank()) return task.placeId
        val norm = task.placeName.lowercase().trim().replace(Regex("\\s+"), "_")
        val addr = (task.address ?: "").lowercase().trim().replace(Regex("\\s+"), "_")
        val lat = Math.round(task.latitude * 100000)
        val lng = Math.round(task.longitude * 100000)
        return "manual:${norm}|${addr}|${lat},${lng}"
    }

    suspend fun hasLearnedOrder(uid: String, placeKey: String): Boolean =
        dao.countForPlace(uid, placeKey) > 0

    suspend fun autoOrder(
        uid: String,
        task: Task,
        items: List<ShoppingListItem>
    ): List<ShoppingListItem> {
        val placeKey = buildPlaceKey(task)
        val typeKey = task.placeTypeId?.takeIf { it.isNotBlank() }?.let { "type:$it" }.orEmpty()
        val exactPlaceProfile = dao.getForPlace(uid, placeKey)
        val sameTypeProfile = if (typeKey.isBlank()) emptyList() else dao.getForPlace(uid, typeKey)
        return autoSortUseCase.sort(items, exactPlaceProfile, sameTypeProfile).items
    }

    suspend fun saveOrder(uid: String, task: Task, items: List<ShoppingListItem>) {
        val now = System.currentTimeMillis()
        val exactPlaceKey = buildPlaceKey(task)
        saveProfile(uid, exactPlaceKey, items, now)
        task.placeTypeId?.takeIf { it.isNotBlank() }?.let { typeId ->
            saveProfile(uid, "type:$typeId", items, now)
        }
        DiagLog.d("SHOP_AUTO_SORT", "source=save finalOrder=${items.joinToString(" | ") { it.canonicalOrText }}")
    }

    private suspend fun saveProfile(uid: String, placeKey: String, items: List<ShoppingListItem>, now: Long) {
        val existing = dao.getForPlace(uid, placeKey).associateBy { it.normalizedItemText }
        items.forEachIndexed { index, item ->
            val displayText = item.canonicalOrText
            val normalized = normalize(displayText)
            val rank = index * 10
            val previous = existing[normalized]
            dao.upsert(
                previous?.copy(
                    displayText = displayText,
                    orderRank = rank,
                    useCount = previous.useCount + 1,
                    lastUsedAt = now
                ) ?: ShoppingPlaceItemOrder(
                    id = UUID.randomUUID().toString(),
                    userId = uid,
                    placeKey = placeKey,
                    normalizedItemText = normalized,
                    displayText = displayText,
                    orderRank = rank,
                    useCount = 1,
                    lastUsedAt = now
                )
            )
        }
    }
}
