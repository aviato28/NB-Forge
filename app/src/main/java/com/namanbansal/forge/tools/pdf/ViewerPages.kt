package com.namanbansal.forge.tools.pdf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.TextHit
import com.namanbansal.forge.core.pdf.viewer.LinkGeometry
import com.namanbansal.forge.core.pdf.viewer.PageBox
import com.namanbansal.forge.core.pdf.viewer.PageLink
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.namanbansal.forge.core.pdf.viewer.SlotGeometry
import com.namanbansal.forge.core.pdf.viewer.SlotLayout
import com.namanbansal.forge.core.pdf.viewer.TextSelection
import com.namanbansal.forge.core.pdf.viewer.TileMath
import com.namanbansal.forge.core.pdf.viewer.TileRenderer
import com.namanbansal.forge.data.ViewerPrefs
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

const val MAX_ZOOM = 8f
private val TOP_BAR_GUTTER = 60.dp
private const val DOUBLE_TAP_ZOOM = 2.5f

/** "Go to this page" asked by the chrome (page jump, outline, search hit); [nonce] makes repeated jumps to one page count. */
data class JumpRequest(val page: Int, val nonce: Int)

/** A brief highlight where a link was tapped. */
data class LinkFlash(val page: Int, val rect: com.namanbansal.forge.core.pdf.NormRect, val id: Int)

/** What the selection toolbar can do. */
class SelectionActions(val copy: () -> Unit, val selectAll: () -> Unit, val search: () -> Unit, val share: () -> Unit)

/**
 * Layering, bottom to top, for every page: base bitmap -> sharp tiles -> search highlights -> selection + link flash ->
 * (Round 2: [extraLayer], where annotation / editing overlays attach) -> selection handles and toolbar.
 * The gesture layer above all pages routes taps, long-presses, pinches and double-taps; modes added later (annotate, sign)
 * plug in through [extraLayer] and by intercepting [onTap] in the caller.
 */
@Composable
fun ViewerPages(
    vm: ViewerViewModel, thumbs: PdfThumbnails, tiles: TileRenderer?, aspects: FloatArray?, pageCount: Int,
    hits: List<TextHit>, currentHit: Int, invert: Boolean, prefs: ViewerPrefs,
    jump: JumpRequest?, flash: LinkFlash?, selection: TextSelection?, actions: SelectionActions,
    onPage: (Int) -> Unit, onTap: () -> Unit, onUri: (String) -> Unit, onScrolling: () -> Unit,
    modifier: Modifier = Modifier,
    input: PageInput? = null,
    extraLayer: @Composable (page: Int, widthPx: Int, heightPx: Int) -> Unit = { _, _, _ -> },
    topExtra: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val c = ForgeTheme.colors
    BoxWithConstraints(modifier.background(if (invert) Color(0xFF050506) else c.surface2)) {
        val vw = constraints.maxWidth.coerceAtLeast(1)
        val vh = constraints.maxHeight.coerceAtLeast(1)
        val twoPage = prefs.twoPageLandscape && vw > vh
        val paged = prefs.pageByPage
        val fitPage = prefs.fitPage || paged
        key(twoPage, paged, fitPage) {
            PagesInner(
                vm, thumbs, tiles, aspects, pageCount, hits, currentHit, invert, vw, vh, twoPage, paged, fitPage,
                jump, flash, selection, actions, onPage, onTap, onUri, onScrolling, input, extraLayer, topExtra,
            )
        }
    }
}

@Composable
private fun PagesInner(
    vm: ViewerViewModel, thumbs: PdfThumbnails, tiles: TileRenderer?, aspects: FloatArray?, pageCount: Int,
    hits: List<TextHit>, currentHit: Int, invert: Boolean, vw: Int, vh: Int, twoPage: Boolean, paged: Boolean, fitPage: Boolean,
    jump: JumpRequest?, flash: LinkFlash?, selection: TextSelection?, actions: SelectionActions,
    onPage: (Int) -> Unit, onTap: () -> Unit, onUri: (String) -> Unit, onScrolling: () -> Unit,
    input: PageInput?, extraLayer: @Composable (Int, Int, Int) -> Unit, topExtra: androidx.compose.ui.unit.Dp,
) {
    val density = LocalDensity.current
    val inputRef = rememberUpdatedState(input)
    val scope = rememberCoroutineScope()
    val slots = remember(pageCount, twoPage) { SlotLayout.slots(pageCount, twoPage) }
    val startSlot = SlotLayout.slotOf(vm.startPage().coerceIn(0, (pageCount - 1).coerceAtLeast(0)), twoPage)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startSlot)
    val pager = rememberPagerState(initialPage = startSlot) { slots.size }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var zoom by remember { mutableFloatStateOf(vm.startZoom().coerceIn(1f, MAX_ZOOM)) }
    var live by remember { mutableFloatStateOf(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pendingX by remember { mutableStateOf<Int?>(null) }
    var pendingY by remember { mutableStateOf<Int?>(null) }
    var pendingList by remember { mutableStateOf<IntArray?>(null) }
    val byPage = remember(hits) { hits.withIndex().groupBy({ it.value.page }, { it }) }
    val linkCache = remember { HashMap<Int, List<PageLink>>() }

    val aspectsRef = rememberUpdatedState(aspects)
    // The first page of a continuous document starts below the top bar, so its first lines are readable with the bars shown.
    // (plus the height of the "not saved" banner while it shows, animated so dismissing it glides the page up)
    val topInsetDp by androidx.compose.animation.core.animateDpAsState(TOP_BAR_GUTTER + topExtra, label = "topInset")
    val topInsetPx = with(density) { topInsetDp.roundToPx() }
    fun geo(slot: Int, z: Float): SlotGeometry =
        SlotLayout.compute(slots[slot], aspectsRef.value, vw, vh, z, fitPage, minHeight = if (paged) vh else 0, topInset = if (!paged && slot == 0) topInsetPx else 0)

    // ---- current page, scrolling activity, jumps ------------------------------------------------------------------
    val currentSlot by remember {
        derivedStateOf {
            if (paged) pager.currentPage else {
                val info = listState.layoutInfo
                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.firstOrNull { mid >= it.offset && mid < it.offset + it.size }?.index ?: listState.firstVisibleItemIndex
            }
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { currentSlot }.collect { s -> slots.getOrNull(s)?.let { onPage(it.first) } } }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress || pager.isScrollInProgress || hScroll.isScrollInProgress || vScroll.isScrollInProgress }
            .collect { if (it) onScrolling() }
    }
    suspend fun goTo(page: Int) {
        val s = SlotLayout.slotOf(page.coerceIn(0, pageCount - 1), twoPage)
        if (paged) { vScroll.scrollTo(0); pager.scrollToPage(s) } else listState.scrollToItem(s)
    }
    LaunchedEffect(jump) { jump?.let { goTo(it.page) } }

    // ---- zoom -----------------------------------------------------------------------------------------------------
    fun commit(newZoom: Float, centroid: Offset) {
        val target = newZoom.coerceIn(1f, MAX_ZOOM)
        val ratio = target / zoom
        if (ratio != 1f) {
            pendingX = ((hScroll.value + centroid.x) * ratio - centroid.x).roundToInt().coerceAtLeast(0)
            if (paged) pendingY = ((vScroll.value + centroid.y) * ratio - centroid.y).roundToInt().coerceAtLeast(0)
            else pendingList = intArrayOf(listState.firstVisibleItemIndex, ((listState.firstVisibleItemScrollOffset + centroid.y) * ratio - centroid.y).roundToInt().coerceAtLeast(0))
            zoom = target
            vm.rememberZoom(target)
        }
        live = 1f
    }
    val commitRef = rememberUpdatedState<(Float, Offset) -> Unit>(::commit)
    LaunchedEffect(zoom) {
        val x = pendingX; val y = pendingY
        if (x != null) { withTimeoutOrNull(600) { snapshotFlow { hScroll.maxValue }.first { it >= x || zoom <= 1f } }; hScroll.scrollTo(x); pendingX = null }
        if (y != null) { withTimeoutOrNull(600) { snapshotFlow { vScroll.maxValue }.first { it >= y || zoom <= 1f } }; vScroll.scrollTo(y); pendingY = null }
    }
    LaunchedEffect(pendingList) { pendingList?.let { listState.scrollToItem(it[0], it[1]); pendingList = null } }

    // ---- where is a finger? ---------------------------------------------------------------------------------------
    /** Page, and the touch point as fractions of it, under viewport point [p]; null on a gap. */
    fun pageAt(p: Offset): Triple<Int, Float, Float>? {
        val slot: Int; val cy: Float
        if (paged) { slot = pager.currentPage; cy = p.y + vScroll.value } else {
            val it = listState.layoutInfo.visibleItemsInfo.firstOrNull { p.y >= it.offset && p.y < it.offset + it.size } ?: return null
            slot = it.index; cy = p.y - it.offset
        }
        if (slot !in slots.indices) return null
        val (box, frac) = SlotLayout.hit(geo(slot, zoom), p.x + hScroll.value, cy) ?: return null
        return Triple(box.page, frac.first, frac.second)
    }

    /** The page under [p] together with where its slot sits, so a gesture can keep measuring against that page after leaving it. */
    class Anchor(val page: Int, val box: PageBox, val itemTop: Int)
    fun anchorAt(p: Offset): Anchor? {
        val slot: Int; val top: Int
        if (paged) { slot = pager.currentPage; top = -vScroll.value } else {
            val it = listState.layoutInfo.visibleItemsInfo.firstOrNull { p.y >= it.offset && p.y < it.offset + it.size } ?: return null
            slot = it.index; top = it.offset
        }
        if (slot !in slots.indices) return null
        val (box, _) = SlotLayout.hit(geo(slot, zoom), p.x + hScroll.value, p.y - top) ?: return null
        return Anchor(box.page, box, top)
    }
    fun fracOf(a: Anchor, p: Offset): Pair<Float, Float> =
        (((p.x + hScroll.value) - a.box.x) / a.box.w).coerceIn(0f, 1f) to (((p.y - a.itemTop) - a.box.y) / a.box.h).coerceIn(0f, 1f)

    val tapHandler = rememberUpdatedState<(Offset) -> Unit> { p ->
        if (selection != null) vm.clearSelection() else {
            val hit = pageAt(p)
            if (hit != null && inputRef.value?.onTap(PageHit(hit.first, hit.second, hit.third, aspectsRef.value?.getOrNull(hit.first) ?: 0.707f)) == true) return@rememberUpdatedState
            val link = hit?.let { (pg, nx, ny) -> linkCache[pg]?.let { LinkGeometry.hit(it, nx, ny) } }
            val target = link?.page; val uri = link?.uri
            if (hit != null && link != null) vm.flashLink(hit.first, link)
            when {
                target != null -> scope.launch { goTo(target) }
                uri != null -> onUri(uri)
                else -> onTap()
            }
        }
    }
    val longPressHandler = rememberUpdatedState<(Offset) -> Unit> { p ->
        pageAt(p)?.let { (pg, nx, ny) -> scope.launch { vm.selectWordAt(pg, nx, ny) } }
    }

    val contentWidthDp = with(density) { (vw * zoom).roundToInt().toDp() }
    val gestures = Modifier
        .pointerInput(vw) {
            // A mode that owns one-finger drags (drawing, snapping to text, erasing) claims the gesture here, before the scrollers
            // see it. A second finger drops the claim and goes to the scrollers / pinch below: two fingers always navigate.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val inp = inputRef.value ?: return@awaitEachGesture
                if (!inp.claimsDrag || !inp.accepts(down.type == PointerType.Stylus)) return@awaitEachGesture
                val a = anchorAt(down.position) ?: return@awaitEachGesture
                val aspect = a.box.w.toFloat() / a.box.h.coerceAtLeast(1)
                val (x0, y0) = fracOf(a, down.position)
                val startHit = PageHit(a.page, x0, y0, aspect)
                if (!inp.claimsAt(startHit)) return@awaitEachGesture
                down.consume()
                inp.onStart(startHit)
                var travelled = 0f
                var lastPos = down.position
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) { inp.onCancel(); return@awaitEachGesture }
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: run { inp.onCancel(); return@awaitEachGesture }
                    if (ch.changedToUp()) {
                        ch.consume()
                        if (travelled < viewConfiguration.touchSlop && !inp.tapIsStroke) { inp.onCancel(); tapHandler.value(down.position) } else inp.onEnd()
                        return@awaitEachGesture
                    }
                    if (ch.positionChanged()) {
                        ch.consume()
                        travelled += (ch.position - lastPos).getDistance(); lastPos = ch.position
                        val (fx, fy) = fracOf(a, ch.position)
                        inp.onMove(fx, fy)
                    }
                }
            }
        }
        .pointerInput(vw) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var pinching = false
                var centroid = Offset.Zero
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        pinching = true
                        val z = event.calculateZoom()
                        centroid = event.calculateCentroid(useCurrent = true)
                        if (z != 1f) { live = (live * z).coerceIn(1f / zoom, MAX_ZOOM / zoom); origin = centroid }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (pinching) commitRef.value(zoom * live, origin)
            }
        }
        .pointerInput(vw) {
            detectTapGestures(
                onTap = { tapHandler.value(it) },
                onLongPress = { longPressHandler.value(it) },
                onDoubleTap = { p -> commitRef.value(if (zoom > 1.2f) 1f else DOUBLE_TAP_ZOOM, p) },
            )
        }
        .graphicsLayer {
            scaleX = live; scaleY = live
            transformOrigin = TransformOrigin(origin.x / vw, origin.y / vh)
        }

    @Composable
    fun Slot(slot: Int, slotTop: () -> Int) {
        val g = geo(slot, zoom)
        val wDp = with(density) { (vw * zoom).roundToInt().toDp() }
        val hDp = with(density) { g.height.toDp() }
        Box(Modifier.width(wDp).height(hDp)) {
            for (b in g.boxes) {
                key(b.page) {
                    PageView(
                        vm, thumbs, tiles, b, (b.w / zoom).roundToInt(), zoom > 1.02f, vw, vh, hScroll, slotTop, invert,
                        byPage[b.page].orEmpty(), currentHit, if (selection?.page == b.page) selection else null,
                        if (flash?.page == b.page) flash else null, actions, linkCache, extraLayer,
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().then(gestures)) {
        if (paged) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = zoom <= 1.01f && selection == null) { slot ->
                Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) { Slot(slot) { -vScroll.value } }
            }
        } else {
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                LazyColumn(Modifier.width(contentWidthDp).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(slots.size, key = { it }) { slot ->
                        Slot(slot) { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == slot }?.offset ?: Int.MIN_VALUE / 2 }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

/** One page with all its layers. [baseWidthPx] is its width at zoom 1: the size of the always-available base bitmap. */
@Composable
private fun PageView(
    vm: ViewerViewModel, thumbs: PdfThumbnails, tiles: TileRenderer?, box: PageBox, baseWidthPx: Int, zoomed: Boolean,
    vw: Int, vh: Int, hScroll: ScrollState, slotTop: () -> Int, invert: Boolean,
    hits: List<IndexedValue<TextHit>>, currentHit: Int, selection: TextSelection?, flash: LinkFlash?,
    actions: SelectionActions, linkCache: HashMap<Int, List<PageLink>>, extraLayer: @Composable (Int, Int, Int) -> Unit,
) {
    val c = ForgeTheme.colors
    val density = LocalDensity.current
    val page = box.page
    var base by remember(page) { mutableStateOf(thumbs.peek(page, baseWidthPx)) }
    LaunchedEffect(page, baseWidthPx) {
        if (thumbs.peek(page, baseWidthPx) == null) base = thumbs.render(page, baseWidthPx.coerceAtMost(MAX_BASE_WIDTH)) ?: base
        else base = thumbs.peek(page, baseWidthPx)
    }
    LaunchedEffect(page) { linkCache[page] = vm.pageLinks(page) }

    // Tiles of the visible region only; recomputed on scroll but only invalidates when the set changes.
    val wanted by remember(box, zoomed, tiles) {
        derivedStateOf {
            if (tiles == null || !zoomed) emptyList() else {
                val ox = box.x - hScroll.value; val oy = slotTop() + box.y
                TileMath.visibleTiles(page, box.w, box.h, -ox.toFloat(), -oy.toFloat(), (vw - ox).toFloat(), (vh - oy).toFloat())
            }
        }
    }
    LaunchedEffect(wanted, tiles) { tiles?.setWanted(page, wanted) }
    DisposableEffect(page, tiles) { onDispose { tiles?.setWanted(page, emptyList()) } }

    val text by produceState<PageText?>(null, page, selection != null) { if (selection != null) value = vm.pageText(page) }
    val filter = if (invert) ColorFilter.colorMatrix(INVERT) else null
    val wDp = with(density) { box.w.toDp() }; val hDp = with(density) { box.h.toDp() }
    Box(
        Modifier.offset { IntOffset(box.x, box.y) }.size(wDp, hDp).background(if (invert) Color(0xFF16161A) else Color.White)
            .semantics { contentDescription = "Page ${page + 1}" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            tiles?.revision?.intValue // redraw when a tile lands (no recomposition)
            base?.let { b -> drawImage(b.asImageBitmap(), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium, colorFilter = filter) }
            if (tiles != null) for (k in wanted) {
                val t = tiles.peek(k) ?: continue
                val r = TileMath.tileRect(k, box.w, box.h)
                drawImage(t.image, srcSize = IntSize(t.width, t.height), dstOffset = IntOffset(r.left, r.top), dstSize = IntSize(r.width, r.height), filterQuality = FilterQuality.Low, colorFilter = filter)
            }
        }
        extraLayer(page, box.w, box.h)
        Canvas(Modifier.fillMaxSize()) {
            for ((hitIndex, hit) in hits) for (r in hit.rects) {
                drawRect(if (hitIndex == currentHit) Color(0x99FF6A1A) else Color(0x66FFD400), Offset(r.left * size.width, r.top * size.height), Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height))
            }
            val pt = text
            if (selection != null && pt != null) for (r in pt.lineRects(selection.start, selection.end)) {
                drawRect(c.accent.copy(alpha = 0.32f), Offset(r.left * size.width, r.top * size.height), Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height))
            }
        }
        if (flash != null) LinkRipple(flash)
        val pt = text
        if (selection != null && pt != null) SelectionHandles(vm, selection, pt, box, actions)
    }
}

private const val MAX_BASE_WIDTH = 2400

@Composable
private fun LinkRipple(flash: LinkFlash) {
    val c = ForgeTheme.colors
    val alpha = remember(flash.id) { Animatable(0.35f) }
    LaunchedEffect(flash.id) { alpha.animateTo(0f, tween(380)) }
    Canvas(Modifier.fillMaxSize()) {
        val r = flash.rect
        drawRoundRect(c.accent.copy(alpha = alpha.value), Offset(r.left * size.width, r.top * size.height), Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    }
}

/** The two draggable handles and the floating toolbar of a text selection. */
@Composable
private fun SelectionHandles(vm: ViewerViewModel, sel: TextSelection, text: PageText, box: PageBox, actions: SelectionActions) {
    val c = ForgeTheme.colors
    val density = LocalDensity.current
    val rects = remember(sel, text) { text.lineRects(sel.start, sel.end) }
    if (rects.isEmpty()) return
    val first = rects.first(); val last = rects.last()
    val handle = 22.dp
    val handlePx = with(density) { handle.toPx() }
    val selRef = rememberUpdatedState(sel)

    @Composable
    fun Handle(anchor: Offset, isStart: Boolean) {
        var pos by remember(anchor) { mutableStateOf(anchor) }
        Box(
            Modifier.offset { IntOffset((anchor.x - handlePx / 2).roundToInt(), anchor.y.roundToInt()) }.size(handle)
                .pointerInput(isStart, box) {
                    detectDragGestures(onDragStart = { pos = anchor }) { change, drag ->
                        change.consume()
                        pos += drag
                        val idx = text.charAt(pos.x / box.w, (pos.y - handlePx * 0.9f) / box.h, slop = 0.03f)
                        val s = selRef.value
                        if (idx >= 0) vm.setSelection(if (isStart) s.copy(start = idx.coerceAtMost(s.end - 1)) else s.copy(end = (idx + 1).coerceAtLeast(s.start + 1)))
                    }
                }
                .semantics { contentDescription = if (isStart) "Selection start handle" else "Selection end handle" },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(c.accent, radius = size.minDimension / 2 * 0.62f, center = Offset(size.width / 2, size.height / 2 + 2.dp.toPx()))
                drawRect(c.accent, Offset(size.width / 2 - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height / 2))
            }
        }
    }
    Handle(Offset(first.left * box.w, first.bottom * box.h), isStart = true)
    Handle(Offset(last.right * box.w, last.bottom * box.h), isStart = false)

    // Toolbar above the selection (below it when there is no room).
    val barW = with(density) { 296.dp.toPx() }; val barH = with(density) { 48.dp.toPx() }
    val top = rects.minOf { it.top } * box.h
    val centreX = (rects.minOf { it.left } + rects.maxOf { it.right }) / 2 * box.w
    val y = if (top - barH - 12f > 0f) top - barH - 12f else rects.maxOf { it.bottom } * box.h + handlePx + 8f
    Row(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
            .offset { IntOffset((centreX - barW / 2).roundToInt().coerceAtLeast(0), y.roundToInt()) }
            .size(296.dp, 48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp))
            .semantics { contentDescription = "Text selection actions" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(ForgeIcons.Copy, "Copy", Modifier.weight(1f), actions.copy)
        ToolbarButton(ForgeIcons.Grid, "Select all", Modifier.weight(1f), actions.selectAll)
        ToolbarButton(ForgeIcons.Search, "Search", Modifier.weight(1f), actions.search)
        ToolbarButton(ForgeIcons.Share, "Share", Modifier.weight(1f), actions.share)
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxHeight().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(18.dp))
        Text(label, style = ForgeType.Tiny, color = c.text, maxLines = 1)
    }
}

/** Inverts colours but keeps a soft range (white page -> dark grey, black text -> off-white) so pages stay distinct from the background. */
internal val INVERT = ColorMatrix(floatArrayOf(
    -0.835f, 0f, 0f, 0f, 235f,
    0f, -0.835f, 0f, 0f, 235f,
    0f, 0f, -0.835f, 0f, 235f,
    0f, 0f, 0f, 1f, 0f,
))
