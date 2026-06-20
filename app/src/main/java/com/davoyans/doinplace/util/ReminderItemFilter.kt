package com.davoyans.doinplace.util

import com.davoyans.doinplace.data.model.ShoppingListItem

object ReminderItemFilter {
    fun activeItems(taskId: String, items: List<ShoppingListItem>): List<ShoppingListItem> {
        var removedCount = 0
        val active = items.filter { item ->
            val keep = item.deletedAt == null && item.text.isNotBlank()
            if (!keep) {
                removedCount += 1
                DiagLog.d("REMINDER_SKIP_ITEM", "taskId=${taskId.take(8)} item=${item.text} reason=deleted")
            }
            keep
        }
        DiagLog.d(
            "REMINDER_BUILD",
            "taskId=${taskId.take(8)} activeItems=${active.size} removedItemsFiltered=$removedCount"
        )
        return active
    }
}
