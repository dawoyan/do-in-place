package com.davoyans.doinplace.data.shopping

import com.davoyans.doinplace.data.db.ShoppingPlaceItemOrderDao
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.ShoppingPlaceItemOrder
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.util.DiagLog
import java.util.UUID

class ShoppingOrderEngine(private val dao: ShoppingPlaceItemOrderDao) {

    fun normalize(text: String): String =
        text.lowercase().trim()
            .replace(Regex("[^\\w\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

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
        placeKey: String,
        items: List<ShoppingListItem>
    ): List<ShoppingListItem> {
        val profile = dao.getForPlace(uid, placeKey)
        if (profile.isEmpty()) return items
        // Sort saved entries longest-first so multi-word phrases win over single words
        val sorted = profile.sortedByDescending { it.normalizedItemText.length }
        val ranked = items.mapIndexed { originalIndex, item ->
            val normItem = normalize(item.text)
            val match = bestMatch(normItem, sorted)
            MatchResult(item, match?.orderRank, match?.matchQuality ?: 0, originalIndex)
        }
        val (known, unknown) = ranked.partition { it.matchedRank != null }
        val orderedKnown = known.sortedWith(
            compareBy<MatchResult> { it.matchedRank }
                .thenByDescending { it.matchQuality }
                .thenBy { it.originalIndex }
        )
        DiagLog.d("SHOP_ORDER", "autoOrder placeKey=${placeKey.take(24)} known=${known.size} unknown=${unknown.size}")
        return (orderedKnown + unknown.sortedBy { it.originalIndex })
            .mapIndexed { i, r -> r.item.copy(orderIndex = i) }
    }

    private data class MatchResult(
        val item: ShoppingListItem,
        val matchedRank: Int?,
        val matchQuality: Int,   // 3 = exact, 2 = phrase, 1 = word-boundary
        val originalIndex: Int
    )

    private data class SavedMatch(val orderRank: Int, val matchQuality: Int)

    private fun bestMatch(
        normItem: String,
        savedSortedByLength: List<com.davoyans.doinplace.data.model.ShoppingPlaceItemOrder>
    ): SavedMatch? {
        var best: SavedMatch? = null
        for (saved in savedSortedByLength) {
            val normSaved = saved.normalizedItemText
            val quality = when {
                normItem == normSaved -> 3
                containsPhrase(normItem, normSaved) -> 2
                containsWordBoundary(normItem, normSaved) -> 1
                else -> 0
            }
            if (quality == 0) continue
            val candidate = SavedMatch(saved.orderRank, quality)
            if (best == null || quality > best.matchQuality ||
                (quality == best.matchQuality && normSaved.length > /* longer saved phrase wins */ 0)) {
                best = candidate
            }
            if (quality == 3) break // exact match — no need to search further
        }
        return best
    }

    // "milk" in "2 bottles of milk" — saved phrase contained in current item
    private fun containsPhrase(normItem: String, normSaved: String): Boolean {
        if (normSaved.length > normItem.length) return false
        return normItem.contains(normSaved, ignoreCase = true)
    }

    // word-boundary check: saved appears as a distinct word (not a substring of another word)
    private fun containsWordBoundary(normItem: String, normSaved: String): Boolean {
        if (normSaved.length > normItem.length) return false
        val escaped = Regex.escape(normSaved)
        return Regex("(^|\\s)$escaped(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(normItem)
    }

    suspend fun saveOrder(uid: String, placeKey: String, items: List<ShoppingListItem>) {
        val now = System.currentTimeMillis()
        val existing = dao.getForPlace(uid, placeKey).associateBy { it.normalizedItemText }
        items.forEachIndexed { index, item ->
            val norm = normalize(item.text)
            val rank = index * 10
            val prev = existing[norm]
            dao.upsert(
                if (prev != null)
                    prev.copy(orderRank = rank, useCount = prev.useCount + 1, lastUsedAt = now)
                else
                    ShoppingPlaceItemOrder(
                        id = UUID.randomUUID().toString(),
                        userId = uid,
                        placeKey = placeKey,
                        normalizedItemText = norm,
                        displayText = item.text,
                        orderRank = rank,
                        useCount = 1,
                        lastUsedAt = now
                    )
            )
        }
    }
}
