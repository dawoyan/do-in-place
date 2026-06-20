package com.davoyans.doinplace.util

import com.davoyans.doinplace.data.model.ShoppingListItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderItemFilterTest {
    @Test
    fun deletedItemsAreExcluded() {
        val items = listOf(
            ShoppingListItem(id = "1", taskId = "task", text = "bread", normalizedText = "bread", orderIndex = 0),
            ShoppingListItem(id = "2", taskId = "task", text = "milk", normalizedText = "milk", orderIndex = 1, deletedAt = 10L)
        )

        val filtered = ReminderItemFilter.activeItems("task", items)

        assertEquals(listOf("bread"), filtered.map { it.text })
    }
}
