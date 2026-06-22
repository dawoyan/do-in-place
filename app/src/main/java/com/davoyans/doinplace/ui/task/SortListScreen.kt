package com.davoyans.doinplace.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.util.DiagLog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortListScreen(
    items: List<ShoppingListItem>,
    taskPlaceName: String,
    hasLearnedOrder: Boolean,
    currentUserId: String,
    onAddItem: (text: String) -> Unit = {},
    onDeleteItem: (id: String) -> Unit = {},
    onSaveOrder: (List<ShoppingListItem>) -> Unit,
    onSaveAsDefault: (List<ShoppingListItem>) -> Unit,
    onBack: () -> Unit
) {
    var sortedItems by remember(items) { mutableStateOf(items) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val itemHeightDp = 60.dp
    val itemHeightPx = with(density) { itemHeightDp.toPx() }
    var snackMsg by remember { mutableStateOf<String?>(null) }
    val snackState = remember { SnackbarHostState() }
    val savedDefaultMsg = stringResource(R.string.saved_default_for, taskPlaceName)
    var newItemText by remember { mutableStateOf("") }
    var pendingDeleteItem by remember { mutableStateOf<ShoppingListItem?>(null) }

    LaunchedEffect(snackMsg) {
        snackMsg?.let { snackState.showSnackbar(it); snackMsg = null }
    }

    fun reorder(from: Int, to: Int): List<ShoppingListItem> {
        val m = sortedItems.toMutableList()
        m.add(to, m.removeAt(from))
        return m.mapIndexed { i, it -> it.copy(orderIndex = i) }
    }

    // Delete confirmation dialog
    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text(stringResource(R.string.shopping_delete_item_title)) },
            text = { Text(stringResource(R.string.shopping_delete_item_message, item.canonicalOrText)) },
            confirmButton = {
                TextButton(onClick = {
                    DiagLog.d("SHOP_EDIT", "delete confirm itemId=${item.id.take(8)}")
                    onDeleteItem(item.id)
                    sortedItems = sortedItems.filter { it.id != item.id }
                        .mapIndexed { i, it -> it.copy(orderIndex = i) }
                    pendingDeleteItem = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shopping_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Add item row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newItemText,
                    onValueChange = { newItemText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add item…") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        val t = newItemText.trim()
                        if (t.isNotBlank()) {
                            DiagLog.d("SHOP_EDIT", "add item '${t.take(20)}'")
                            onAddItem(t)
                            newItemText = ""
                        }
                    },
                    enabled = newItemText.isNotBlank()
                ) { Text("+") }
            }

            Text(
                stringResource(R.string.shopping_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // Reorderable item list
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                sortedItems.forEachIndexed { index, item ->
                    val isDragging = draggingIndex == index
                    val target = if (draggingIndex >= 0) {
                        (draggingIndex + (dragOffset / itemHeightPx).roundToInt())
                            .coerceIn(0, sortedItems.lastIndex)
                    } else -1

                    val shift = when {
                        isDragging -> dragOffset
                        draggingIndex >= 0 -> {
                            val d = draggingIndex
                            val t = target
                            when {
                                d < t && index in (d + 1)..t -> -itemHeightPx
                                d > t && index in t until d -> itemHeightPx
                                else -> 0f
                            }
                        }
                        else -> 0f
                    }

                    Row(
                        Modifier
                            .height(itemHeightDp)
                            .fillMaxWidth()
                            .offset { IntOffset(0, shift.roundToInt()) }
                            .zIndex(if (isDragging) 1f else 0f)
                            .background(
                                if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Drag handle
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder",
                            modifier = Modifier
                                .size(32.dp)
                                .pointerInput(index) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffset = 0f
                                            DiagLog.d("SHOP_EDIT", "drag reorder taskId=? itemId=${item.id.take(8)}")
                                        },
                                        onDrag = { _, delta -> dragOffset += delta.y },
                                        onDragEnd = {
                                            val d = draggingIndex
                                            if (d >= 0) {
                                                val t = (d + (dragOffset / itemHeightPx).roundToInt())
                                                    .coerceIn(0, sortedItems.lastIndex)
                                                if (d != t) sortedItems = reorder(d, t)
                                            }
                                            draggingIndex = -1; dragOffset = 0f
                                        },
                                        onDragCancel = { draggingIndex = -1; dragOffset = 0f }
                                    )
                                },
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.canonicalOrText,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        // Arrow buttons (accessibility fallback)
                        IconButton(
                            onClick = { if (index > 0) sortedItems = reorder(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "Move up", Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                if (index < sortedItems.lastIndex)
                                    sortedItems = reorder(index, index + 1)
                            },
                            enabled = index < sortedItems.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "Move down", Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { pendingDeleteItem = item },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }

                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }

            // Action buttons
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSaveOrder(sortedItems) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.save_order)) }

                OutlinedButton(
                    onClick = {
                        onSaveAsDefault(sortedItems)
                        snackMsg = savedDefaultMsg
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.save_as_default_for_place)) }

                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
