package com.davoyans.doinplace.data.shopping

import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.ShoppingPlaceItemOrder
import com.davoyans.doinplace.engine.ShoppingItemCategory
import com.davoyans.doinplace.engine.ShoppingItemClassifier
import com.davoyans.doinplace.util.DiagLog

class ShoppingAutoSortUseCase(
    private val normalize: (String) -> String
) {
    fun sort(
        items: List<ShoppingListItem>,
        exactPlaceProfile: List<ShoppingPlaceItemOrder>,
        sameTypeProfile: List<ShoppingPlaceItemOrder>
    ): AutoSortResult {
        if (exactPlaceProfile.isEmpty() && sameTypeProfile.isEmpty()) {
            return AutoSortResult(
                items = orderByCategory(items, source = "category_fallback"),
                source = "category_fallback"
            )
        }

        val activeProfile = if (exactPlaceProfile.isNotEmpty()) exactPlaceProfile else sameTypeProfile
        val exactIndex = exactPlaceProfile.associate { it.normalizedItemText to it.orderRank }
        val sameTypeIndex = if (exactPlaceProfile.isEmpty()) sameTypeProfile.associate { it.normalizedItemText to it.orderRank } else emptyMap()
        val categoryAnchors = activeProfile
            .sortedBy { it.orderRank }
            .mapNotNull { order ->
                val category = ShoppingItemClassifier.classify(order.displayText)
                category.takeIf { it != ShoppingItemCategory.UNKNOWN }?.let { it to order.orderRank }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ranks) -> ranks.minOrNull() ?: Int.MAX_VALUE }

        val ranked = items.mapIndexed { originalIndex, item ->
            val displayText = item.canonicalOrText
            val normalized = normalize(displayText)
            val exactRank = exactIndex[normalized]
            val typeRank = sameTypeIndex[normalized]
            val category = ShoppingItemClassifier.classify(displayText)
            val categoryAnchor = categoryAnchors[category]
            SortKey(
                item = item,
                sourceRank = when {
                    exactRank != null -> exactRank
                    typeRank != null -> typeRank
                    categoryAnchor != null -> categoryAnchor
                    category != ShoppingItemCategory.UNKNOWN -> Int.MAX_VALUE - 1
                    else -> Int.MAX_VALUE
                },
                learnedRank = when {
                    exactRank != null -> 0
                    typeRank != null -> 0
                    categoryAnchor != null -> 1
                    category != ShoppingItemCategory.UNKNOWN -> category.order
                    else -> Int.MAX_VALUE
                },
                categoryOrder = when {
                    categoryAnchor != null -> 0
                    else -> category.order
                },
                originalIndex = originalIndex
            )
        }

        val sorted = ranked.sortedWith(
            compareBy<SortKey> { it.sourceRank }
                .thenBy { it.learnedRank }
                .thenBy { it.categoryOrder }
                .thenBy { it.originalIndex }
        ).mapIndexed { index, key -> key.item.copy(orderIndex = index) }

        DiagLog.d(
            "SHOP_AUTO_SORT",
            "source=${if (exactPlaceProfile.isNotEmpty()) "saved_exact_place" else "learned_same_place_type"} finalOrder=${sorted.joinToString(" | ") { it.canonicalOrText }}"
        )

        return AutoSortResult(
            items = sorted,
            source = if (exactPlaceProfile.isNotEmpty()) "saved_exact_place" else "learned_same_place_type"
        )
    }

    private fun orderByCategory(items: List<ShoppingListItem>, source: String): List<ShoppingListItem> {
        val sorted = items.mapIndexed { originalIndex, item ->
            val category = ShoppingItemClassifier.classify(item.canonicalOrText)
            SortKey(
                item = item,
                sourceRank = if (category == ShoppingItemCategory.UNKNOWN) 5 else 4,
                learnedRank = Int.MAX_VALUE,
                categoryOrder = category.order,
                originalIndex = originalIndex
            )
        }.sortedWith(
            compareBy<SortKey> { it.sourceRank }
                .thenBy { it.categoryOrder }
                .thenBy { it.originalIndex }
        ).mapIndexed { index, key -> key.item.copy(orderIndex = index) }

        DiagLog.d("SHOP_AUTO_SORT", "source=$source finalOrder=${sorted.joinToString(" | ") { it.canonicalOrText }}")
        return sorted
    }

    data class AutoSortResult(
        val items: List<ShoppingListItem>,
        val source: String
    )

    private data class SortKey(
        val item: ShoppingListItem,
        val sourceRank: Int,
        val learnedRank: Int,
        val categoryOrder: Int,
        val originalIndex: Int
    )
}
