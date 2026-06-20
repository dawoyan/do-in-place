package com.davoyans.doinplace.ui.task

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.data.model.UsualShoppingItemStats

data class UsualShoppingSuggestionState(
    val placeName: String,
    val placeTypeKey: String,
    val items: List<UsualShoppingItemStats>
)

@Composable
fun UsualShoppingSuggestionDialog(
    state: UsualShoppingSuggestionState,
    onCreateList: (selectedNormalizedItems: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val checked = remember(state.items) {
        mutableStateMapOf<String, Boolean>().also { map ->
            // Pre-select top items (those bought more often)
            state.items.forEachIndexed { idx, it ->
                map[it.normalizedItem] = idx < 6
            }
        }
    }
    val allChecked = state.items.all { checked[it.normalizedItem] == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Usual shopping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "You often buy these near ${state.placeName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                state.items.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = checked[item.normalizedItem] == true,
                            onCheckedChange = { v -> checked[item.normalizedItem] = v }
                        )
                        Text(
                            item.displayItem,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val all = state.items.map { it.normalizedItem }
                    all.forEach { checked[it] = !allChecked }
                }) {
                    Text(if (allChecked) "Deselect all" else "Select all")
                }
                Button(
                    onClick = {
                        val selected = state.items
                            .filter { checked[it.normalizedItem] == true }
                            .map { it.normalizedItem }
                        onCreateList(selected)
                    },
                    enabled = checked.values.any { it }
                ) {
                    Text("Create list")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    )
}
