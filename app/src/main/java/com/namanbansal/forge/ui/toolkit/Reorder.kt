package com.namanbansal.forge.ui.toolkit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.android.awaitFrame

/** One laid-out item of a lazy list/grid, in the container's coordinates. */
class ReorderItem(val key: Any, val index: Int, val offset: IntOffset, val size: IntSize)

/**
 * Drag-to-reorder for lazy lists and grids. The dragged item follows the finger (translation computed from the finger's
 * movement, so it stays under the finger even while others re-flow or the list auto-scrolls) and swaps places with the
 * item it is dragged over by calling [onMove]. [onStart]/[onEnd] bracket one drag (one undo step for the caller).
 */
@Stable
class ReorderState internal constructor(
    private val visible: () -> List<ReorderItem>,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onStart: () -> Unit,
    private val onEnd: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var startPos = Offset.Zero
    private var delta by mutableStateOf(Offset.Zero)
    internal var viewport = IntSize.Zero

    fun begin(key: Any) {
        val info = visible().firstOrNull { it.key == key } ?: return
        draggingKey = key
        startPos = Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
        delta = Offset.Zero
        onStart()
    }

    fun drag(by: Offset) {
        if (draggingKey == null) return
        delta += by
        recheck()
    }

    fun end() {
        if (draggingKey == null) return
        draggingKey = null
        delta = Offset.Zero
        onEnd()
    }

    /** Where the dragged item currently is, relative to where the lazy layout placed it (zero for other items). */
    fun translationOf(key: Any): Offset {
        if (key != draggingKey) return Offset.Zero
        val cur = visible().firstOrNull { it.key == key } ?: return Offset.Zero
        return startPos + delta - Offset(cur.offset.x.toFloat(), cur.offset.y.toFloat())
    }

    /** Swap with whichever item the dragged one's centre is over. Also called after auto-scroll moved the content. */
    internal fun recheck() {
        val items = visible()
        val dragged = items.firstOrNull { it.key == draggingKey } ?: return
        val cx = startPos.x + delta.x + dragged.size.width / 2f
        val cy = startPos.y + delta.y + dragged.size.height / 2f
        val target = items.firstOrNull {
            it.key != draggingKey && cx >= it.offset.x && cx < it.offset.x + it.size.width && cy >= it.offset.y && cy < it.offset.y + it.size.height
        } ?: return
        onMove(dragged.index, target.index)
    }

    /** Pixels to scroll this frame (negative = up) while the dragged item sits near the top/bottom edge. */
    internal fun edgeScroll(edgePx: Float): Float {
        val dragged = visible().firstOrNull { it.key == draggingKey } ?: return 0f
        val top = startPos.y + delta.y
        val bottom = top + dragged.size.height
        return when {
            top < edgePx -> -((edgePx - top) / edgePx).coerceIn(0f, 1f) * 28f
            bottom > viewport.height - edgePx -> ((bottom - (viewport.height - edgePx)) / edgePx).coerceIn(0f, 1f) * 28f
            else -> 0f
        }
    }
}

@Composable
private fun AutoScroll(state: ReorderState, scrollBy: suspend (Float) -> Unit) {
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val by = rememberUpdatedState(scrollBy)
    LaunchedEffect(state.draggingKey != null) {
        while (state.draggingKey != null) {
            val d = state.edgeScroll(edge)
            if (d != 0f) { by.value(d); state.recheck() }
            awaitFrame()
        }
    }
}

private fun Modifier.dragVisual(state: ReorderState, key: Any): Modifier = this
    .zIndex(if (state.draggingKey == key) 1f else 0f)
    .graphicsLayer {
        if (state.draggingKey == key) {
            val t = state.translationOf(key)
            translationX = t.x; translationY = t.y
            scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 12f
        }
    }

/**
 * Lazy grid whose items can be dragged (long-press, then move) to new positions. [content] draws one item; tap handling
 * stays with the content. Other items slide aside; accessibility gets "Move earlier/later" actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T : Any> ReorderableGrid(
    items: List<T>,
    key: (T) -> Any,
    columns: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(bottom = 8.dp),
    gridState: LazyGridState = rememberLazyGridState(),
    content: @Composable (item: T, index: Int, dragging: Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnMove = rememberUpdatedState(onMove)
    val currentStart = rememberUpdatedState(onDragStart)
    val currentEnd = rememberUpdatedState(onDragEnd)
    val state = remember(gridState) {
        ReorderState(
            visible = { gridState.layoutInfo.visibleItemsInfo.map { ReorderItem(it.key, it.index, it.offset, it.size) } },
            onMove = { f, t -> currentOnMove.value(f, t) }, onStart = { currentStart.value() }, onEnd = { currentEnd.value() },
        )
    }
    AutoScroll(state) { gridState.scrollBy(it) }
    val step = { from: Int, to: Int -> currentStart.value(); currentOnMove.value(from, to); currentEnd.value() } // one undo step
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns), state = gridState,
        modifier = modifier.onSizeChanged { state.viewport = it },
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
            val k = key(item)
            val dragging = state.draggingKey == k
            Box(
                Modifier
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .dragVisual(state, k)
                    .pointerInput(k) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); state.begin(k) },
                            onDrag = { change, amount -> change.consume(); state.drag(amount) },
                            onDragEnd = { state.end() }, onDragCancel = { state.end() },
                        )
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("Move earlier") { step(index, index - 1); true } else null,
                            if (index < items.lastIndex) CustomAccessibilityAction("Move later") { step(index, index + 1); true } else null,
                        )
                    },
            ) { content(item, index, dragging) }
        }
    }
}

/**
 * Lazy column with drag handles: [content] receives a modifier for its grip icon that starts a drag immediately.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T : Any> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(bottom = 8.dp),
    listState: LazyListState = rememberLazyListState(),
    content: @Composable (item: T, index: Int, dragging: Boolean, handle: Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnMove = rememberUpdatedState(onMove)
    val currentStart = rememberUpdatedState(onDragStart)
    val currentEnd = rememberUpdatedState(onDragEnd)
    val state = remember(listState) {
        ReorderState(
            visible = { listState.layoutInfo.visibleItemsInfo.map { ReorderItem(it.key, it.index, IntOffset(0, it.offset), IntSize(listState.layoutInfo.viewportSize.width, it.size)) } },
            onMove = { f, t -> currentOnMove.value(f, t) }, onStart = { currentStart.value() }, onEnd = { currentEnd.value() },
        )
    }
    AutoScroll(state) { listState.scrollBy(it) }
    val step = { from: Int, to: Int -> currentStart.value(); currentOnMove.value(from, to); currentEnd.value() } // one undo step
    LazyColumn(
        state = listState, modifier = modifier.onSizeChanged { state.viewport = it },
        verticalArrangement = Arrangement.spacedBy(spacing), contentPadding = contentPadding,
    ) {
        itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
            val k = key(item)
            val dragging = state.draggingKey == k
            val handle = Modifier.pointerInput(k) {
                detectDragGestures(
                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); state.begin(k) },
                    onDrag = { change, amount -> change.consume(); state.drag(amount) },
                    onDragEnd = { state.end() }, onDragCancel = { state.end() },
                )
            }
            Box(
                Modifier
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .dragVisual(state, k)
                    .semantics {
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("Move up") { step(index, index - 1); true } else null,
                            if (index < items.lastIndex) CustomAccessibilityAction("Move down") { step(index, index + 1); true } else null,
                        )
                    },
            ) { content(item, index, dragging, handle) }
        }
    }
}
