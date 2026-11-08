package com.staninna.yourtube.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Drag to reorder in a LazyColumn whose items are all reorderable, with item indexes as the list's.
 * The dragged item follows the finger and trades places with a neighbor once its middle crosses into
 * it: [onMove] reorders the shown list right away, and [onDrop] gets the final index when the finger
 * lifts. A cancelled drag moves the item back where it started and drops nothing. While the item is
 * near an edge of the list, the list scrolls along every frame, also when the finger rests there.
 */
@Stable
class ReorderState internal constructor(
    private val list: LazyListState,
    private val onMove: State<(from: Int, to: Int) -> Unit>,
    private val onDrop: State<(key: Any, to: Int) -> Unit>,
) {
    /** The key of the item being dragged, if any. */
    var dragging: Any? by mutableStateOf(null)
        private set

    /** How far the dragged item is drawn from where the list places it. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var startIndex = -1
    private var index = -1

    /** Set after a move until the list has laid the item out at its new index, so it cannot move back first. */
    private var pending: Int? = null

    /** Starts dragging [key]; false when the list does not show it. */
    fun start(key: Any): Boolean {
        index = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index ?: return false
        startIndex = index
        dragging = key
        offset = 0f
        pending = null
        return true
    }

    fun drag(dy: Float) {
        if (dragging == null) return
        offset += dy
        settle()
    }

    fun end() {
        val key = dragging ?: return
        reset()
        onDrop.value(key, index)
    }

    /** The gesture was taken away (another pointer, the screen leaving): the order goes back as it was. */
    fun cancel() {
        if (dragging == null) return
        if (index != startIndex) move(index, startIndex)
        reset()
    }

    /** Scrolls the list once a frame while the dragged item is near one of its edges, until the drag ends. */
    suspend fun scrollWhileDragging() {
        while (dragging != null) {
            withFrameNanos {}
            scrollAtEdge()
        }
    }

    /** Moves the item when its middle lies over a neighbor. */
    private fun settle() {
        val key = dragging ?: return
        val items = list.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == key } ?: return
        if (pending != null && current.index != pending) return
        pending = null
        val middle = (current.offset + offset + current.size / 2f).toInt()
        val target = items.firstOrNull { it.key != key && middle in it.offset until it.offset + it.size } ?: return
        // Where the list will put the item: level with the target's top going up, its bottom going down.
        val newTop = if (target.index < current.index) target.offset else target.offset + target.size - current.size
        offset -= newTop - current.offset
        move(current.index, target.index)
        index = target.index
        pending = target.index
    }

    /**
     * Scrolls by up to [EDGE_STEP] when the dragged item comes within its own height of an edge, faster
     * the closer it gets, and keeps it under the finger. Waits for a move to be laid out first,
     * since until then the list still has the item at its old place.
     */
    private fun scrollAtEdge() {
        val key = dragging ?: return
        val info = list.layoutInfo
        val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        if (pending != null && current.index != pending) return
        pending = null
        val zone = current.size.toFloat()
        val top = current.offset + offset
        val pastEnd = top + current.size - (info.viewportEndOffset - zone)
        val pastStart = (info.viewportStartOffset + zone) - top
        val scroll = when {
            pastEnd > 0 -> EDGE_STEP * (pastEnd / zone).coerceAtMost(1f)
            pastStart > 0 -> -EDGE_STEP * (pastStart / zone).coerceAtMost(1f)
            else -> return
        }
        offset += list.dispatchRawDelta(scroll)
        settle()
    }

    private fun move(from: Int, to: Int) {
        // A lazy list keeps its first visible item in place by key; pin it by index so a move
        // involving that item does not scroll the list.
        if (from == list.firstVisibleItemIndex || to == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        onMove.value(from, to)
    }

    private fun reset() {
        dragging = null
        offset = 0f
        pending = null
    }

    private companion object {
        /** The most the list scrolls in one frame at an edge, in pixels. */
        const val EDGE_STEP = 20f
    }
}

@Composable
fun rememberReorderState(list: LazyListState, onMove: (from: Int, to: Int) -> Unit, onDrop: (key: Any, to: Int) -> Unit): ReorderState {
    val move = rememberUpdatedState(onMove)
    val drop = rememberUpdatedState(onDrop)
    return remember(list) { ReorderState(list, move, drop) }
}

/**
 * Draws the item with [key] where the finger has it while it is dragged: above the others, a little
 * larger and with a shadow in [shape], so it reads as lifted off the list.
 */
fun Modifier.reorderItem(state: ReorderState, key: Any, shape: Shape): Modifier =
    if (state.dragging != key) {
        this
    } else {
        zIndex(1f).graphicsLayer {
            translationY = state.offset
            scaleX = 1.02f
            scaleY = 1.02f
            shadowElevation = 8.dp.toPx()
            this.shape = shape
        }
    }

/** Makes this the handle that drags the item with [key]. */
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    coroutineScope {
        detectDragGestures(
            onDragStart = { if (state.start(key)) launch { state.scrollWhileDragging() } },
            onDragEnd = state::end,
            onDragCancel = state::cancel,
        ) { change, amount ->
            change.consume()
            state.drag(amount.y)
        }
    }
}
