package com.namanbansal.forge.ui.toolkit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.PageSelectionSync
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlin.math.abs

/**
 * One cell of a [PagePickerGrid]. [key] must be unique and an Int, Long or String. [thumbs] null = a blank white page.
 * [gapBefore]/[gapAfter] are ids of the tappable gaps drawn on the left/right edge of the cell (see [GridGaps]).
 */
@Immutable
class GridPage(
    val key: Any,
    val thumbs: PdfThumbnails?,
    val page: Int,
    val label: String,
    val selected: Boolean = false,
    val tint: Color? = null,
    val badge: String? = null,
    val dimmed: Boolean = false,
    val rotation: Int = 0,
    val gapBefore: Int? = null,
    val gapAfter: Int? = null,
    val description: String = "Page $label",
)

/** Tappable gaps between pages (Split's cut markers, Insert's insertion point). Gap ids are chosen by the caller. */
@Immutable
class GridGaps(
    val isActive: (Int) -> Boolean,
    val onToggle: (Int) -> Unit,
    val describe: (id: Int, active: Boolean) -> String,
    val icon: ImageVector,
    val activeColor: Color,
)

private val Strip = 16.dp      // visible width of a gap, on each side of a cell
private val GapHit = 26.dp     // how far from the cell edge a touch still counts as the gap (touch target ~52dp wide)

/** Number of grid columns for a given width: 3 on phones, more on tablets / landscape. */
fun pickerColumns(widthDp: Float): Int = when {
    widthDp < 600f -> 3
    widthDp < 840f -> 5
    else -> 7
}

private class Hit(val index: Int, val gap: Int?)

/**
 * Lazy thumbnail grid with the shared gestures: tap, drag horizontally across thumbnails to select a run ([onDragSelect]),
 * long-press for the full-screen peek, and optional tappable [gaps]. [header]/[footer] add full-width items around the pages.
 * [onTap] null = tapping a page opens the peek.
 */
@Composable
fun PagePickerGrid(
    pages: List<GridPage>,
    modifier: Modifier = Modifier,
    onTap: ((Int) -> Unit)? = null,
    onDragSelect: ((anchor: Int, current: Int, done: Boolean) -> Unit)? = null,
    gaps: GridGaps? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    var peek by rememberSaveable { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pagesNow = rememberUpdatedState(pages)
    val gapsNow = rememberUpdatedState(gaps)
    val tapNow = rememberUpdatedState(onTap)
    val dragNow = rememberUpdatedState(onDragSelect)
    val keyIndex = remember(pages) { HashMap<Any, Int>(pages.size * 2).also { m -> pages.forEachIndexed { i, p -> m[p.key] = i } } }
    val keyIndexNow = rememberUpdatedState(keyIndex)
    val gapHitPx = with(density) { GapHit.toPx() }

    fun hit(pos: Offset): Hit? {
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width && pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
        } ?: return null
        val index = keyIndexNow.value[info.key] ?: return null
        val page = pagesNow.value.getOrNull(index) ?: return null
        var gap: Int? = null
        if (gapsNow.value != null) {
            val dx = pos.x - info.offset.x
            if (dx < gapHitPx && page.gapBefore != null) gap = page.gapBefore
            else if (dx > info.size.width - gapHitPx && page.gapAfter != null) gap = page.gapAfter
        }
        return Hit(index, gap)
    }

    BoxWithConstraints(modifier) {
        val columns = pickerColumns(maxWidth.value)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), state = gridState,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = hit(down.position) ?: return@awaitEachGesture
                    // 1 = tap, 2 = horizontal drag, 3 = vertical (the grid scrolls), null = long press
                    val outcome: Int? = withTimeoutOrNull(longPressMs) {
                        var res = 0
                        while (res == 0) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            res = when {
                                ch == null || ch.isConsumed -> 3
                                ch.changedToUp() -> 1
                                else -> {
                                    val d = ch.position - down.position
                                    if (abs(d.x) > slop || abs(d.y) > slop) { if (abs(d.x) > abs(d.y) && dragNow.value != null) 2 else 3 } else 0
                                }
                            }
                        }
                        res
                    }
                    when (outcome) {
                        1 -> {
                            val g = start.gap
                            if (g != null) gapsNow.value?.onToggle?.invoke(g) else (tapNow.value ?: { i: Int -> peek = i }).invoke(start.index)
                        }
                        2 -> {
                            val drag = dragNow.value ?: return@awaitEachGesture
                            var last = start.index
                            drag(start.index, last, false)
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                ev.changes.forEach { it.consume() }
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUp()) break
                                hit(ch.position)?.let { if (it.index != last) { last = it.index; drag(start.index, last, false) } }
                            } while (ev.changes.any { it.pressed })
                            drag(start.index, last, true)
                        }
                        null -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            peek = start.index
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                        }
                        else -> Unit
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(if (gaps == null) 10.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            header?.invoke(this)
            itemsIndexed(pages, key = { _, p -> p.key }) { index, page ->
                GridCell(page, index, gaps, onTap, onDragSelect != null) { peek = index }
            }
            footer?.invoke(this)
        }
    }
    if (peek in pages.indices) PagePeek(pages, peek, onIndexChange = { peek = it }, onClose = { peek = -1 })
}

/** Full-width item for [PagePickerGrid]'s header/footer. */
fun LazyGridScope.fullWidthItem(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun GridCell(page: GridPage, index: Int, gaps: GridGaps?, onTap: ((Int) -> Unit)?, canSelect: Boolean, onPeek: () -> Unit) {
    val strip = if (gaps != null) Strip else 0.dp
    Box(Modifier.fillMaxWidth()) {
        PageThumb(
            sourcePage = page.page, label = page.label, thumbnails = page.thumbs,
            modifier = Modifier.padding(horizontal = strip).semantics {
                // Touches are handled by the grid's gesture detector; these are the accessibility equivalents.
                onClick(label = if (canSelect) "Select page" else "Preview page") { (onTap ?: { _ -> onPeek() }).invoke(index); true }
                onLongClick(label = "Preview page") { onPeek(); true }
            },
            selected = page.selected, rotation = page.rotation, description = page.description,
            tint = page.tint, badge = page.badge, dimmed = page.dimmed,
        )
        if (gaps != null) {
            Row(Modifier.matchParentSize()) {
                GapStrip(page.gapBefore, gaps)
                Spacer(Modifier.weight(1f))
                GapStrip(page.gapAfter, gaps)
            }
        }
    }
}

@Composable
private fun GapStrip(id: Int?, gaps: GridGaps) {
    if (id == null) { Spacer(Modifier.width(Strip)); return }
    val c = ForgeTheme.colors
    val active = gaps.isActive(id)
    val color = if (active) gaps.activeColor else c.text2.copy(alpha = 0.55f)
    Box(
        Modifier.width(Strip).fillMaxHeight().semantics {
            role = Role.Button
            contentDescription = gaps.describe(id, active)
            onClick { gaps.onToggle(id); true }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxHeight().width(2.dp)) {
            drawLine(
                color, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = size.width,
                pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
            )
        }
        if (active) {
            Box(Modifier.requiredSize(26.dp).clip(CircleShape).background(gaps.activeColor), contentAlignment = Alignment.Center) {
                Icon(gaps.icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            Box(Modifier.requiredSize(18.dp).clip(CircleShape).background(c.bg), contentAlignment = Alignment.Center) {
                Icon(gaps.icon, null, tint = c.text2, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** "3 of 12 selected" with All / None / Invert. Shared by the picker and the Page manager. */
@Composable
fun SelectionBar(count: Int, total: Int, onAll: () -> Unit, onNone: () -> Unit, onInvert: () -> Unit, modifier: Modifier = Modifier, noun: String = "selected") {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$count of $total $noun", style = ForgeType.Small, color = ForgeTheme.colors.text2, modifier = Modifier.weight(1f))
        TextAction("All", onAll, enabled = count < total)
        TextAction("None", onNone, enabled = count > 0)
        TextAction("Invert", onInvert)
    }
}

/**
 * Pick pages of one PDF: thumbnail grid (tap, drag across to select a run, long-press to peek), All / None / Invert and an
 * optional range field kept in two-way sync with the grid. The caller owns [selected] and [rangeText] (so they can live in a
 * ViewModel); [onRangeTextChange] should apply [PageSelectionSync.fromText] and [onSelectionChange] should rewrite the text.
 */
@Composable
fun PagePicker(
    pageCount: Int,
    thumbnails: PdfThumbnails,
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
    rangeText: String? = null,
    onRangeTextChange: ((String) -> Unit)? = null,
    rangePlaceholder: String = "Type pages, like 1-3, 5, 8-10",
    selectedNoun: String = "selected",
    header: (@Composable () -> Unit)? = null,
) {
    val pages = remember(pageCount, thumbnails, selected) {
        List(pageCount) { GridPage("p$it", thumbnails, it, "${it + 1}", selected = it in selected) }
    }
    val selectedNow = rememberUpdatedState(selected)
    val changeNow = rememberUpdatedState(onSelectionChange)
    val drag = remember { DragRun() }
    Column(modifier) {
        header?.invoke()
        SelectionBar(
            selected.size, pageCount,
            onAll = { onSelectionChange(PageSelectionSync.all(pageCount)) },
            onNone = { onSelectionChange(emptySet()) },
            onInvert = { onSelectionChange(PageSelectionSync.invert(selected, pageCount)) },
            noun = selectedNoun,
        )
        if (rangeText != null && onRangeTextChange != null) {
            ForgeTextField(
                rangeText, onRangeTextChange, placeholder = rangePlaceholder,
                error = PageSelectionSync.error(rangeText, pageCount), modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        PagePickerGrid(
            pages, Modifier.weight(1f),
            onTap = { i -> val cur = selectedNow.value; changeNow.value(if (i in cur) cur - i else cur + i) },
            onDragSelect = { anchor, current, done ->
                if (!drag.active) { drag.active = true; drag.base = selectedNow.value; drag.makeSelected = anchor !in drag.base }
                changeNow.value(PageSelectionSync.dragRun(drag.base, anchor, current, drag.makeSelected))
                if (done) drag.active = false
            },
        )
    }
}

/** Bookkeeping for one drag-select: the selection before it started and whether it switches pages on or off. */
private class DragRun {
    var active = false
    var base: Set<Int> = emptySet()
    var makeSelected = true
}
