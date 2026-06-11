package com.davoyans.doinplace.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.ShoppingListItem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortListScreen(
    items: List<ShoppingListItem>,
    taskPlaceName: String,
    hasLearnedOrder: Boolean,
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

    LaunchedEffect(snackMsg) {
        snackMsg?.let { snackState.showSnackbar(it); snackMsg = null }
    }

    fun reorder(from: Int, to: Int): List<ShoppingListItem> {
        val m = sortedItems.toMutableList()
        m.add(to, m.removeAt(from))
        return m.mapIndexed { i, it -> it.copy(orderIndex = i) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sort_shopping_list)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }
            )
        },
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Text(
                "Drag the handle or use arrows to reorder items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Scrollable item list
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
                                        onDragStart = { draggingIndex = index; dragOffset = 0f },
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
                        Spacer(Modifier.width(12.dp))
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        // Up / Down fallback buttons
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
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }

            // Action buttons
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSaveOrder(sortedItems) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Order") }

                OutlinedButton(
                    onClick = {
                        onSaveAsDefault(sortedItems)
                        snackMsg = "Saved as default order for $taskPlaceName"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save as Default for This Place")
                }

                if (!hasLearnedOrder) {
                    Text(
                        "No saved order for $taskPlaceName yet — sort and tap above to learn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
            }
        }
    }
}
