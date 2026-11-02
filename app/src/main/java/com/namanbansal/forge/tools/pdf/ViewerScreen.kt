package com.namanbansal.forge.tools.pdf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.TextHit
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.PickView
import com.namanbansal.forge.ui.toolkit.rememberFilePicker
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f
private val PICK = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to read")

/** P40 Viewer: smooth vertical scrolling, pinch / double-tap zoom, page indicator + jump, text search, dark pages, quick actions. */
@Composable
fun ViewerScreen(host: ToolHost, vm: ViewerViewModel = viewModel()) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val snack = rememberSnack()
    val pick = rememberFilePicker(PICK.mimes, multiple = false) { vm.onUrisPicked(it) }
    androidx.compose.runtime.LaunchedEffect(vm) { vm.messages.collect { snack(it) } }

    Column(Modifier.fillMaxSize()) {
        val t = thumbs
        if (phase == ToolPhase.Options && source != null && t != null) {
            ViewerContent(host, vm, t, title = inputs.firstOrNull()?.name ?: host.tool.name, pageCount = t.pageCount, onChange = pick)
        } else {
            TopBar(host.tool.name, onBack = host.onBack)
            if (phase == ToolPhase.Options && prompt == null) {
                // Opened but still preparing, or restoring: a spinner via the shared pick view's loading state.
                PickView(PICK, loading = true, onChoose = pick)
            } else {
                PickView(PICK, loading = loading, onChoose = pick)
            }
        }
    }
    prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) }
}

@Composable
private fun ViewerContent(host: ToolHost, vm: ViewerViewModel, thumbs: PdfThumbnails, title: String, pageCount: Int, onChange: () -> Unit) {
    val c = ForgeTheme.colors
    val scope = rememberCoroutineScope()
    val invert by vm.invert.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = vm.lastPage.value.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    var searchOpen by remember { mutableStateOf(query.isNotEmpty()) }
    var showHits by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }

    // Which page is "current": the one at the middle of the screen.
    val currentPage by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.firstOrNull { mid >= it.offset && mid < it.offset + it.size }?.index
                ?: listState.firstVisibleItemIndex
        }
    }
    LaunchedEffect(listState) { snapshotFlow { currentPage }.collect { vm.rememberPage(it) } }
    // Scroll to the current search hit whenever next / previous / a list tap asks for it.
    LaunchedEffect(jump) {
        hits.getOrNull(current)?.let { listState.animateScrollToItem(it.page) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack = host.onBack) {
            RoundIconButton(ForgeIcons.Search, "Search in document", { searchOpen = !searchOpen; if (!searchOpen) vm.clearSearch() }, active = searchOpen)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(ForgeIcons.Moon, if (invert) "Show pages normally" else "Dark pages", vm::toggleInvert, active = invert)
            Spacer(Modifier.width(8.dp))
            Box {
                RoundIconButton(ForgeIcons.More, "More actions", { menu = true })
                ToolsMenu(menu, onDismiss = { menu = false }) { code -> menu = false; host.onOpenTool(code, inputs) }
            }
            Spacer(Modifier.width(8.dp))
        }
        AnimatedVisibility(searchOpen) {
            SearchBar(
                query = query, searching = searching, count = hits.size, current = current,
                onSubmit = vm::search, onPrev = vm::previousHit, onNext = vm::nextHit,
                onClose = { searchOpen = false; showHits = false; vm.clearSearch() },
                listOpen = showHits, onToggleList = { showHits = !showHits },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ZoomablePages(vm, thumbs, aspects, hits, current, invert, listState, pageCount)
            PagePill(currentPage + 1, pageCount, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { jumpDialog = true }
        }
        AnimatedVisibility(searchOpen && showHits && hits.isNotEmpty()) {
            HitList(hits, current, Modifier.heightIn(max = 240.dp)) { i -> vm.openHit(i); }
        }
    }
    if (jumpDialog) JumpDialog(pageCount, currentPage + 1, onDismiss = { jumpDialog = false }) { p ->
        jumpDialog = false
        scope.launch { listState.scrollToItem((p - 1).coerceIn(0, pageCount - 1)) }
    }
}

// ---- pages + zoom -------------------------------------------------------------------------------------------------

@Composable
private fun ZoomablePages(
    vm: ViewerViewModel, thumbs: PdfThumbnails, aspects: FloatArray?, hits: List<TextHit>, current: Int, invert: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState, pageCount: Int,
) {
    val c = ForgeTheme.colors
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    var zoom by remember { mutableFloatStateOf(vm.zoom.value.coerceIn(1f, MAX_ZOOM)) }
    var live by remember { mutableFloatStateOf(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pendingScrollX by remember { mutableStateOf<Int?>(null) }
    var pendingList by remember { mutableStateOf<IntArray?>(null) }
    val byPage = remember(hits) { hits.withIndex().groupBy({ it.value.page }, { it }) }

    BoxWithConstraints(Modifier.fillMaxSize().background(if (invert) Color(0xFF0E0E10) else c.surface2)) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        val pageWidthPx = (viewportW * zoom).roundToInt().coerceAtLeast(1)
        val pageWidthDp = with(density) { pageWidthPx.toDp() }

        fun commit(newZoom: Float, centroid: Offset) {
            val target = newZoom.coerceIn(1f, MAX_ZOOM)
            val ratio = target / zoom
            if (ratio != 1f) {
                pendingScrollX = ((hScroll.value + centroid.x) * ratio - centroid.x).roundToInt().coerceAtLeast(0)
                // Page heights scale with the zoom, so scale the offset inside the first page and keep the touch point steady.
                pendingList = intArrayOf(listState.firstVisibleItemIndex, ((listState.firstVisibleItemScrollOffset + centroid.y) * ratio - centroid.y).roundToInt().coerceAtLeast(0))
                zoom = target
                vm.rememberZoom(target)
            }
            live = 1f
        }
        val commitState = remember { mutableStateOf<(Float, Offset) -> Unit>({ _, _ -> }) }
        commitState.value = ::commit
        LaunchedEffect(zoom) {
            // After the wider layout exists, restore the scroll position that keeps the pinch point under the fingers.
            val x = pendingScrollX ?: return@LaunchedEffect
            withTimeoutOrNull(600) { snapshotFlow { hScroll.maxValue }.first { it >= x || zoom <= 1f } }
            hScroll.scrollTo(x)
            pendingScrollX = null
        }
        LaunchedEffect(pendingList) {
            pendingList?.let { listState.scrollToItem(it[0], it[1]); pendingList = null }
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(viewportW) {
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
                                if (z != 1f) {
                                    live = (live * z).coerceIn(1f / zoom, MAX_ZOOM / zoom)
                                    origin = centroid
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) commitState.value(zoom * live, origin)
                    }
                }
                .pointerInput(viewportW) {
                    detectTapGestures(onDoubleTap = { p -> commitState.value(if (zoom > 1.2f) 1f else 2.5f, p) })
                }
                .graphicsLayer {
                    scaleX = live; scaleY = live
                    transformOrigin = TransformOrigin(origin.x / viewportW.coerceAtLeast(1), origin.y / viewportH.coerceAtLeast(1))
                },
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                LazyColumn(Modifier.width(pageWidthDp).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed((0 until pageCount).toList(), key = { _, it -> it }) { _, i ->
                        val aspect = aspects?.getOrNull(i) ?: 0.707f
                        PageView(i, thumbs, pageWidthPx, viewportW, aspect, invert, byPage[i].orEmpty(), current)
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

/** One page: shown right away from the fit-width image, then re-rendered sharper when zoomed (capped for memory). */
@Composable
private fun PageView(
    index: Int, thumbs: PdfThumbnails, widthPx: Int, fitWidthPx: Int, aspect: Float, invert: Boolean,
    hits: List<IndexedValue<TextHit>>, currentHit: Int,
) {
    val c = ForgeTheme.colors
    val density = LocalDensity.current
    val renderW = widthPx.coerceAtMost(MAX_RENDER_WIDTH)
    var shown by remember(index) { mutableStateOf(thumbs.peek(index, renderW) ?: thumbs.peek(index, fitWidthPx)) }
    LaunchedEffect(index, renderW) {
        thumbs.peek(index, renderW)?.let { shown = it; return@LaunchedEffect }
        if (shown == null) thumbs.render(index, fitWidthPx.coerceAtMost(MAX_RENDER_WIDTH))?.let { shown = it }
        if (renderW > fitWidthPx) {
            delay(150) // zooming settles before spending memory on a bigger bitmap
            thumbs.render(index, renderW)?.let { shown = it }
        }
    }
    val heightDp = with(density) { (widthPx / aspect).toDp() }
    val filter = if (invert) ColorFilter.colorMatrix(INVERT) else null
    Box(
        Modifier.fillMaxWidth().height(heightDp).background(if (invert) Color(0xFF1A1A1D) else Color.White)
            .semantics { contentDescription = "Page ${index + 1}" },
    ) {
        val bmp = shown
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = filter)
        }
        if (hits.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for ((hitIndex, hit) in hits) {
                    val active = hitIndex == currentHit
                    for (r in hit.rects) {
                        val tl = Offset(r.left * size.width, r.top * size.height)
                        val sz = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height)
                        drawRect(if (active) Color(0x99FF6A1A) else Color(0x66FFD400), tl, sz)
                    }
                }
            }
        }
    }
}

private const val MAX_RENDER_WIDTH = 2400

private val INVERT = ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
))

// ---- chrome ------------------------------------------------------------------------------------------------------------

@Composable
private fun PagePill(page: Int, total: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Row(
        modifier.heightIn(min = 48.dp).clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp)
            .semantics { contentDescription = "Page $page of $total. Tap to jump to a page" },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text("$page / $total", style = ForgeType.Label, color = c.text) }
}

@Composable
private fun SearchBar(
    query: String, searching: Boolean, count: Int, current: Int,
    onSubmit: (String) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
    listOpen: Boolean, onToggleList: () -> Unit,
) {
    val c = ForgeTheme.colors
    var text by remember { mutableStateOf(query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(text) { // search while typing, after a short pause
        if (text != query) { delay(350); onSubmit(text) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp))
            .background(c.surface).border(1.5.dp, c.accent, RoundedCornerShape(18.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(ForgeIcons.Search, null, tint = c.text2, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
            modifier = Modifier.weight(1f).focusRequester(focus).padding(horizontal = 6.dp),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (text.isEmpty()) Text("Find in document", style = ForgeType.Body, color = c.text2); inner() } },
        )
        when {
            searching -> CircularProgressIndicator(color = c.accent, trackColor = c.surface2, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            query.isNotBlank() -> Text(
                if (count == 0) "No results" else "${current + 1} of $count", style = ForgeType.Small, color = c.text2,
                modifier = Modifier.clip(CircleShape).clickable(enabled = count > 0, onClick = onToggleList).padding(horizontal = 8.dp, vertical = 12.dp)
                    .semantics { stateDescription = if (listOpen) "List shown" else "List hidden" },
            )
        }
        BarIcon(ForgeIcons.ChevronUp, "Previous result", count > 0, onPrev)
        BarIcon(ForgeIcons.ChevronDown, "Next result", count > 0, onNext)
        BarIcon(ForgeIcons.Close, "Close search", true, onClose)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
}

@Composable
private fun HitList(hits: List<TextHit>, current: Int, modifier: Modifier, onOpen: (Int) -> Unit) {
    val c = ForgeTheme.colors
    LazyColumn(modifier.fillMaxWidth().background(c.surface).border(1.dp, c.border)) {
        itemsIndexed(hits, key = { i, _ -> i }) { i, h ->
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(i) }.background(if (i == current) c.tint else Color.Transparent).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("Page ${h.page + 1}", style = ForgeType.Tiny, color = c.accent)
                Text(snippet(h, c.accent), style = ForgeType.Small, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = c.border)
        }
    }
}

private fun snippet(h: TextHit, accent: Color): AnnotatedString = buildAnnotatedString {
    append("…" + h.before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(h.match) }
    append(h.after + "…")
}

@Composable
private fun ToolsMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = ForgeTheme.colors
    val tools = remember { ToolRegistry.availableFor(setOf(FileKind.PDF)).filter { it.code != "P40" } }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = c.surface) {
        Text("Open this PDF in…", style = ForgeType.Tiny, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        tools.forEach { t ->
            DropdownMenuItem(
                text = { Text(t.shortLabel) },
                leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) },
                onClick = { onPick(t.code) },
            )
        }
    }
}

@Composable
private fun JumpDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val c = ForgeTheme.colors
    var text by remember { mutableStateOf(current.toString()) }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..pageCount
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Go to page", style = ForgeType.Section, color = c.text) },
        text = {
            ForgeTextField(
                text, { text = it.filter(Char::isDigit).take(6) }, label = "Page (1-$pageCount)", keyboardType = KeyboardType.Number,
                error = if (!valid && text.isNotEmpty()) "Enter a page from 1 to ${pagesLabel(pageCount).removeSuffix(" pages").removeSuffix(" page")}" else null,
                onDone = { if (valid) onGo(n!!) },
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { if (valid) onGo(n!!) }, enabled = valid) { Text("Go", color = c.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}
