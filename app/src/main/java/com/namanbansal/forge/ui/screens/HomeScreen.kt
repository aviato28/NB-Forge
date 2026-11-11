package com.namanbansal.forge.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.namanbansal.forge.core.RecentItem
import com.namanbansal.forge.data.Pins
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.tools.Category
import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.AnvilMark
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.SectionHeader
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

fun Category.icon(): ImageVector = when (this) {
    Category.PDF -> ForgeIcons.FileText
    Category.IMAGE -> ForgeIcons.Image
    Category.VIDEO -> ForgeIcons.Video
    Category.DEVICE -> ForgeIcons.Phone
    Category.UTILITIES -> ForgeIcons.Calc
    Category.FILES -> ForgeIcons.Folder
}

/** Short label used on the home grid (mockup uses "Video"). */
fun Category.shortTitle(): String = if (this == Category.VIDEO) "Video" else title

fun toolCount(n: Int) = if (n == 1) "1 tool" else "$n tools"

@Composable
fun HomeScreen(
    pins: List<String>,
    recents: List<RecentItem>,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onTool: (String) -> Unit,
    onCategory: (Category) -> Unit,
    onTogglePin: (String) -> Unit,
    onReorderPins: (List<String>) -> Unit,
    onSeeAllRecents: () -> Unit,
    onRemoveRecent: (com.namanbansal.forge.core.RecentEntry) -> Unit,
    recentActions: RecentActions? = null,
) {
    val c = ForgeTheme.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    val pinned = pins.mapNotNull { ToolRegistry.get(it) }

    // First frame after the system splash: content eases in (220 ms, once per process).
    val intro = remember { Animatable(if (introPlayed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!introPlayed) {
            introPlayed = true
            intro.animateTo(1f, tween(220))
        }
    }
    val slide = with(LocalDensity.current) { 16.dp.toPx() }

    LazyColumn(
        Modifier.fillMaxSize().graphicsLayer { alpha = intro.value; translationY = (1f - intro.value) * slide },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                AnvilMark(36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("FORGE", style = ForgeType.Brand, color = c.text)
                    Text("by Naman Bansal", style = ForgeType.Tiny.copy(fontWeight = FontWeight.Medium), color = c.text2)
                }
                RoundIconButton(ForgeIcons.Settings, "Settings", onSettings)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(ForgeShapes.Search).background(c.surface)
                    .border(1.dp, c.border, ForgeShapes.Search).clickable(onClick = onSearch).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(ForgeIcons.Search, null, tint = c.text2, modifier = Modifier.size(20.dp))
                Text("Search ${ToolRegistry.all.size} tools…", style = ForgeType.Body, color = c.text2)
            }
        }
        item {
            SectionHeader(
                "Pinned",
                action = if (pinned.isEmpty()) null else if (editing) "Done" else "Edit",
                onAction = { editing = !editing },
            )
            if (pinned.isEmpty()) {
                Text("Long-press any tool to pin it here.", style = ForgeType.Small, color = c.text2)
            } else {
                if (editing) {
                    PinEditGrid(pins, onCommit = onReorderPins, onUnpin = onTogglePin)
                    Text(
                        "Drag to reorder. Up to ${ToolRegistry.MAX_PINS} pins.",
                        style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(pinned, key = { it.code }) { tool ->
                            PinTile(tool, false, Modifier.size(76.dp), onClick = { onTool(tool.code) }, onLongClick = { editing = true })
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Recent", action = if (recents.isEmpty()) null else "See all", onAction = onSeeAllRecents)
            ForgeCard(Modifier.fillMaxWidth()) {
                if (recents.isEmpty()) {
                    EmptyState(ForgeIcons.Clock, "Nothing here yet", "Files you process show up here")
                } else {
                    val shown = recents.take(3)
                    shown.forEachIndexed { i, item -> RecentRow(item, showDivider = i < shown.lastIndex, onRemove = { onRemoveRecent(item.entry) }, actions = recentActions) }
                }
            }
        }
        item { SectionHeader("All tools", Modifier.padding(top = 0.dp)) }
        val cats = Category.entries.chunked(2)
        items(cats.size) { i ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cats[i].forEach { cat -> CategoryCard(cat, Modifier.weight(1f)) { onCategory(cat) } }
                if (cats[i].size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Set after the first intro animation so rotating/returning to Home does not replay it. */
private var introPlayed = false

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinTile(tool: ToolDef, editing: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit, onUnpin: (() -> Unit)? = null) {
    val c = ForgeTheme.colors
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().clip(ForgeShapes.Card).background(c.surface).border(1.dp, if (editing) c.accent else c.border, ForgeShapes.Card)
                .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
                .padding(top = 12.dp, start = 4.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(tool.icon, null, tint = c.text, modifier = Modifier.size(24.dp))
            Text(
                tool.shortLabel, style = ForgeType.Tiny.copy(fontSize = 10.5.sp, lineHeight = 12.sp), color = c.text,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing && onUnpin != null) {
            // 48dp touch target (kept inside the tile so it stays hittable); the visible badge stays a small corner dot.
            Box(
                Modifier.align(Alignment.TopEnd).size(48.dp)
                    .combinedClickable(onClick = onUnpin).semantics { contentDescription = "Unpin ${tool.shortLabel}" },
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(Modifier.padding(top = 2.dp, end = 2.dp).size(20.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(ForgeIcons.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/**
 * Edit-mode pins: a fixed 4-column grid (max 8 pins = 2 rows) where each tile can be dragged to a new slot.
 * The order is committed once, when the finger lifts.
 */
@Composable
private fun PinEditGrid(codes: List<String>, onCommit: (List<String>) -> Unit, onUnpin: (String) -> Unit) {
    val density = LocalDensity.current
    var order by remember(codes) { mutableStateOf(codes) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val cols = 4
    val gap = 10.dp
    val cellH = 76.dp
    val commit = rememberUpdatedState(onCommit)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellW: Dp = (maxWidth - gap * (cols - 1)) / cols
        val wPx = with(density) { cellW.toPx() }
        val hPx = with(density) { cellH.toPx() }
        val gPx = with(density) { gap.toPx() }
        val rows = ceil(order.size / cols.toFloat()).toInt().coerceAtLeast(1)
        fun slot(i: Int) = Offset((i % cols) * (wPx + gPx), (i / cols) * (hPx + gPx))

        Box(Modifier.fillMaxWidth().size(width = maxWidth, height = cellH * rows + gap * (rows - 1))) {
            order.mapNotNull { ToolRegistry.get(it) }.forEach { tool ->
                val code = tool.code
                key(code) {
                    val index = order.indexOf(code)
                    val isDragged = dragging == code
                    val slotDp = DpOffsetOf(slot(index), density)
                    val x by animateDpAsState(slotDp.first, tween(160), label = "pinX")
                    val y by animateDpAsState(slotDp.second, tween(160), label = "pinY")
                    val pos = if (isDragged) dragPos else with(density) { Offset(x.toPx(), y.toPx()) }
                    PinTile(
                        tool, editing = true,
                        modifier = Modifier
                            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                            .size(cellW, cellH)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { if (isDragged) { scaleX = 1.06f; scaleY = 1.06f } }
                            .pointerInput(code) {
                                detectDragGestures(
                                    onDragStart = { dragging = code; dragPos = slot(order.indexOf(code)) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPos += amount
                                        val cx = dragPos.x + wPx / 2
                                        val cy = dragPos.y + hPx / 2
                                        val col = (cx / (wPx + gPx)).toInt().coerceIn(0, cols - 1)
                                        val row = (cy / (hPx + gPx)).toInt().coerceIn(0, rows - 1)
                                        val target = (row * cols + col).coerceAtMost(order.lastIndex)
                                        val from = order.indexOf(code)
                                        if (target != from) order = Pins.move(order, from, target)
                                    },
                                    onDragEnd = { dragging = null; commit.value(order) },
                                    onDragCancel = { dragging = null; order = codes },
                                )
                            },
                        onClick = {}, onLongClick = {}, onUnpin = { onUnpin(code) },
                    )
                }
            }
        }
    }
}

private fun DpOffsetOf(o: Offset, density: androidx.compose.ui.unit.Density): Pair<Dp, Dp> =
    with(density) { o.x.toDp() to o.y.toDp() }

@Composable
private fun CategoryCard(cat: Category, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    ForgeCard(modifier.height(56.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBox(cat.icon(), size = 36.dp, shape = ForgeShapes.IconBoxSmall)
            Column {
                Text(cat.shortTitle(), style = ForgeType.Item, color = c.text)
                Text(toolCount(ToolRegistry.count(cat)), style = ForgeType.Small.copy(fontWeight = FontWeight.Normal), color = c.text2)
            }
        }
    }
}
