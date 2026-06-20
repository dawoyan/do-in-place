package com.davoyans.doinplace.data.shopping

import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.ShoppingPlaceItemOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class ShoppingAutoSortUseCaseTest {
    private val useCase = ShoppingAutoSortUseCase { text ->
        text.lowercase().trim().replace(Regex("\\s+"), " ")
    }

    @Test
    fun categoryFallbackGroupsSimilarItemsAndKeepsUnknownStable() {
        val items = listOf(
            shoppingItem("juice", 0),
            shoppingItem("bread", 1),
            shoppingItem("soap", 2),
            shoppingItem("mystery", 3)
        )

        val result = useCase.sort(items, emptyList(), emptyList()).items

        assertEquals(listOf("bread", "juice", "soap", "mystery"), result.map { it.text })
    }

    @Test
    fun exactPlaceProfileOrdersKnownItemsBeforeCategoryFallback() {
        val profile = listOf(
            savedOrder("bread", 0),
            savedOrder("milk", 10),
            savedOrder("water", 20),
            savedOrder("soap", 30)
        )
        val items = listOf(
            shoppingItem("juice", 0),
            shoppingItem("lavash", 1),
            shoppingItem("yogurt", 2),
            shoppingItem("detergent", 3),
            shoppingItem("milk", 4),
            shoppingItem("bread", 5)
        )

        val result = useCase.sort(items, profile, emptyList()).items

        assertEquals(
            listOf("bread", "lavash", "milk", "yogurt", "juice", "detergent"),
            result.map { it.text }
        )
    }

    private fun shoppingItem(text: String, orderIndex: Int) = ShoppingListItem(
        id = text,
        taskId = "task",
        text = text,
        normalizedText = text,
        orderIndex = orderIndex
    )

    private fun savedOrder(text: String, rank: Int) = ShoppingPlaceItemOrder(
        id = text,
        userId = "user",
        placeKey = "place",
        normalizedItemText = text,
        displayText = text,
        orderRank = rank,
        useCount = 1,
        lastUsedAt = 1L
    )
}
