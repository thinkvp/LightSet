package app.lightset.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column whose items can be dragged into a new order by a handle. [content] gets
 * the modifier to put on that handle. While dragging, the layout stays put (so the
 * gesture's coordinates stay valid): the dragged item follows the finger and the ones
 * it passes slide out of its way. [onReorder] gets the new order of keys on release.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Int,
    onReorder: (List<Int>) -> Unit,
    spacing: Dp = 12.dp,
    content: @Composable (item: T, handle: Modifier) -> Unit,
) {
    val current by rememberUpdatedState(items)
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // The order just committed, shown until it comes back in [items].
    var committed by remember { mutableStateOf<List<Int>?>(null) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val gap = with(LocalDensity.current) { spacing.toPx() }
    LaunchedEffect(items) { committed = null }

    fun order(): List<Int> = committed ?: current.map(key)

    /** Where the dragged item would land if released now. */
    fun target(order: List<Int>, k: Int): Int {
        var i = order.indexOf(k)
        var rest = offset
        while (rest > 0 && i < order.lastIndex) {
            val h = (heights[order[i + 1]] ?: 0) + gap
            if (rest <= h / 2) break
            rest -= h
            i++
        }
        while (rest < 0 && i > 0) {
            val h = (heights[order[i - 1]] ?: 0) + gap
            if (-rest <= h / 2) break
            rest += h
            i--
        }
        return i
    }

    val byKey = items.associateBy(key)
    val order = order()
    val d = dragging
    val from = d?.let { order.indexOf(it) } ?: -1
    val to = d?.let { target(order, it) } ?: -1
    val draggedSpan = d?.let { (heights[it] ?: 0) + gap } ?: 0f

    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        order.forEachIndexed { index, k ->
            val item = byKey[k] ?: return@forEachIndexed
            key(k) {
                val shift = when {
                    k == d -> offset
                    from < to && index in (from + 1)..to -> -draggedSpan
                    from > to && index in to until from -> draggedSpan
                    else -> 0f
                }
                val handle = Modifier.pointerInput(k) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = k
                            offset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                        },
                        onDragEnd = {
                            val list = order()
                            val i = list.indexOf(k)
                            val j = target(list, k)
                            if (i >= 0 && j != i) {
                                val moved = list.toMutableList().apply { add(j, removeAt(i)) }
                                committed = moved
                                onReorder(moved)
                            }
                            dragging = null
                            offset = 0f
                        },
                        onDragCancel = {
                            dragging = null
                            offset = 0f
                        },
                    )
                }
                Box(
                    Modifier
                        .zIndex(if (k == d) 1f else 0f)
                        .graphicsLayer {
                            translationY = shift
                            shadowElevation = if (k == d) 8.dp.toPx() else 0f
                        }
                        .onSizeChanged { heights[k] = it.height },
                ) {
                    content(item, handle)
                }
            }
        }
    }
}
