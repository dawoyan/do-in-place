package com.davoyans.doinplace.engine

import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.UsualShoppingItemStats
import com.davoyans.doinplace.data.model.UsualShoppingSession
import com.davoyans.doinplace.data.model.UsualShoppingSuppression
import java.util.UUID

class UsualShoppingEngine(private val db: AppDatabase) {

    companion object {
        val GROCERY_PLACE_TYPES = setOf("supermarket", "food_shop", "bazaar")
        private const val HABIT_THRESHOLD = 3
        private const val MIN_SUGGEST_ITEMS = 3
        private const val SUPPRESS_DAY_MS = 24 * 60 * 60 * 1000L
        private const val DISMISS_SUPPRESS_MS = 30L * 24 * 60 * 60 * 1000L
        private val EARLY_WINDOW_MS = 3L * 24 * 60 * 60 * 1000L
    }

    fun isGroceryType(placeTypeKey: String?) = placeTypeKey in GROCERY_PLACE_TYPES

    suspend fun recordCompletedSession(
        userId: String,
        taskId: String,
        placeTypeKey: String,
        placeName: String,
        items: List<ShoppingListItem>
    ) {
        if (placeTypeKey !in GROCERY_PLACE_TYPES) return
        val now = System.currentTimeMillis()
        db.usualShoppingDao().insertSession(
            UsualShoppingSession(
                id = UUID.randomUUID().toString(),
                userId = userId,
                taskId = taskId,
                placeTypeKey = placeTypeKey,
                placeName = placeName,
                completedAt = now
            )
        )
        for (item in items.filter { it.checked }) {
            val norm = item.text.lowercase().trim()
            val existing = db.usualShoppingDao().getItemStat(userId, placeTypeKey, norm)
            val updated = existing?.copy(
                buyCount = existing.buyCount + 1,
                displayItem = item.text,
                lastBoughtAt = now,
                suppressedUntil = 0
            ) ?: UsualShoppingItemStats(
                id = UUID.randomUUID().toString(),
                userId = userId,
                placeTypeKey = placeTypeKey,
                normalizedItem = norm,
                displayItem = item.text,
                buyCount = 1,
                lastBoughtAt = now
            )
            db.usualShoppingDao().upsertItemStats(updated)
        }
    }

    suspend fun getUsualItems(userId: String, placeTypeKey: String): List<UsualShoppingItemStats> {
        val now = System.currentTimeMillis()
        return db.usualShoppingDao().getItemStats(userId, placeTypeKey)
            .filter { it.buyCount >= HABIT_THRESHOLD && it.suppressedUntil < now }
            .sortedByDescending { it.buyCount }
    }

    suspend fun shouldSuggest(userId: String, placeTypeKey: String): Boolean {
        val items = getUsualItems(userId, placeTypeKey)
        if (items.size < MIN_SUGGEST_ITEMS) return false

        val sup = db.usualShoppingDao().getSuppression(userId, placeTypeKey)
        if (sup != null && sup.suppressedUntil > System.currentTimeMillis()) return false

        val sessions = db.usualShoppingDao().getSessionsForPlaceType(userId, placeTypeKey)
        if (sessions.isEmpty()) return false
        if (sessions.size < 3) return true

        val sorted = sessions.sortedByDescending { it.completedAt }
        val i1 = sorted[1].completedAt - sorted[2].completedAt
        val i2 = sorted[0].completedAt - sorted[1].completedAt
        val avg = (i1 + i2) / 2
        val nextEligible = sorted[0].completedAt + avg - EARLY_WINDOW_MS
        return System.currentTimeMillis() >= nextEligible
    }

    suspend fun suppressForToday(userId: String, placeTypeKey: String) {
        val now = System.currentTimeMillis()
        db.usualShoppingDao().upsertSuppression(
            UsualShoppingSuppression(
                id = "${userId}_${placeTypeKey}",
                userId = userId,
                placeTypeKey = placeTypeKey,
                suppressedUntil = now + SUPPRESS_DAY_MS,
                lastShownAt = now
            )
        )
    }

    suspend fun recordItemDismissed(userId: String, placeTypeKey: String, normalizedItem: String) {
        val now = System.currentTimeMillis()
        val existing = db.usualShoppingDao().getItemStat(userId, placeTypeKey, normalizedItem) ?: return
        val newDismiss = existing.dismissCount + 1
        db.usualShoppingDao().upsertItemStats(
            existing.copy(
                dismissCount = newDismiss,
                lastDismissedAt = now,
                suppressedUntil = if (newDismiss >= 3) now + DISMISS_SUPPRESS_MS else 0
            )
        )
    }

    suspend fun recordItemsAccepted(userId: String, placeTypeKey: String, items: List<String>) {
        val now = System.currentTimeMillis()
        for (norm in items) {
            val existing = db.usualShoppingDao().getItemStat(userId, placeTypeKey, norm) ?: continue
            db.usualShoppingDao().upsertItemStats(
                existing.copy(acceptedCount = existing.acceptedCount + 1, lastBoughtAt = now)
            )
        }
    }
}
