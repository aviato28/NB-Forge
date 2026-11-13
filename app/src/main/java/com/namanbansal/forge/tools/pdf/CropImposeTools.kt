package com.namanbansal.forge.tools.pdf

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.CropPages
import com.namanbansal.forge.core.pdf.ImposeMode
import com.namanbansal.forge.core.pdf.ImposePlan
import com.namanbansal.forge.core.pdf.ImposeSpec
import com.namanbansal.forge.core.pdf.PageRasterizers
import com.namanbansal.forge.core.pdf.PdfCropper
import com.namanbansal.forge.core.pdf.PdfImposer
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.SheetSize
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.LivePreview
import com.namanbansal.forge.ui.toolkit.PagesChooser
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.namanbansal.forge.ui.toolkit.PreviewPageRow
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** P7 Crop / trim: the crop box is kept as permille of the displayed page (survives rotation and process death). */
class CropViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P7"
    override val action = "cropped"
    override val headline get() = "Cropped"

    private val lS = persistedInt("crop.l", 0); private val tS = persistedInt("crop.t", 0)
    private val rS = persistedInt("crop.r", 1000); private val bS = persistedInt("crop.b", 1000)
    private val modeS = persistedInt("crop.mode", 0); private val mediaS = persistedBool("crop.media", false)
    private val perPageS = persistedBool("crop.perPage", false); private val pageS = persistedInt("crop.page", 0)
    val box: StateFlow<List<Int>> = kotlinx.coroutines.flow.combine(lS.flow, tS.flow, rS.flow, bS.flow) { l, t, r, b -> listOf(l, t, r, b) }
        .stateIn(viewModelScope)
    val mode: StateFlow<Int> = modeS.flow; val mediaBox: StateFlow<Boolean> = mediaS.flow
    val perPage: StateFlow<Boolean> = perPageS.flow; val previewPage: StateFlow<Int> = pageS.flow

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<T> {
        val init = listOf(lS.value, tS.value, rS.value, bS.value)
        @Suppress("UNCHECKED_CAST") val out = kotlinx.coroutines.flow.MutableStateFlow(init as T)
        scope.launch { collect { out.value = it } }
        return out
    }

    fun setBox(l: Int, t: Int, r: Int, b: Int) { lS.value = l.coerceIn(0, 950); tS.value = t.coerceIn(0, 950); rS.value = r.coerceIn(50, 1000); bS.value = b.coerceIn(50, 1000) }
    fun resetBox() = setBox(0, 0, 1000, 1000)
    fun setMode(m: Int) { modeS.value = m }
    fun setMedia(v: Boolean) { mediaS.value = v }
    fun setPerPage(v: Boolean) { perPageS.value = v }
    fun setPreviewPage(p: Int) { pageS.value = p }

    override fun onPdfReady(source: PdfSource) { super.onPdfReady(source); pageS.value = pageS.value.coerceIn(0, source.pageCount - 1) }

    /** Finds the content bounds of [page] and moves the crop box to them. @return false if the page is blank */
    suspend fun autoDetect(page: Int): Boolean {
        val t = thumbnails.value ?: return false
        val bmp = t.render(page, 500) ?: return false
        val found = withContext(Dispatchers.Default) { PdfCropper.detectContent(bmp) } ?: return false
        setBox((found.l * 1000).toInt(), (found.t * 1000).toInt(), (found.r * 1000).toInt(), (found.b * 1000).toInt())
        return true
    }

    fun autoDetectAsync(page: Int) { viewModelScope.launch { if (!autoDetect(page)) notify("This page looks blank, nothing to detect.") } }

    private fun currentBox() = PdfCropper.Box(lS.value / 1000f, tS.value / 1000f, rS.value / 1000f, bS.value / 1000f)

    private fun targets() = PdfCropper.pagesFor(CropPages.entries[modeS.value.coerceIn(0, 3)], pageCount, selection.value)

    override fun validate(source: PdfSource) {
        if (targets().isEmpty()) throw ToolException("No pages to crop with this choice.")
        if (!perPageS.value && currentBox().isFull) throw ToolException("Drag the crop box in first, or use Auto-detect.")
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val pages = targets()
        val n = if (perPageS.value) {
            val r = PageRasterizers.factory(source.file)
            try {
                PdfCropper.apply(doc, pages, mediaS.value, { i ->
                    val size = r.pageSize(i) ?: return@apply null
                    val w = 500; val h = (w.toFloat() * size.second / size.first).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(android.graphics.Color.WHITE)
                    r.render(i, bmp)
                    PdfCropper.detectContent(bmp).also { bmp.recycle() }
                }, ctx)
            } finally { r.close() }
        } else PdfCropper.apply(doc, pages, mediaS.value, { currentBox() }, ctx)
        return "${pagesLabel(n)} cropped" + if (mediaS.value) " (page size changed too)" else " (nothing was deleted, the crop can be undone)"
    }
}

private enum class Grab { NONE, MOVE, L, R, T, B, TL, TR, BL, BR }

/** The page with a draggable crop rectangle: corners and edges resize it, dragging inside moves it. */
@Composable
fun CropCanvas(bitmap: Bitmap, box: List<Int>, onChange: (Int, Int, Int, Int) -> Unit, modifier: Modifier = Modifier, dim: Boolean = true) {
    val c = ForgeTheme.colors
    val accent = c.accent
    val current = rememberUpdatedState(box)
    val change = rememberUpdatedState(onChange)
    val slop = with(LocalDensity.current) { 36.dp.toPx() }
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    val grab = remember { arrayOf(Grab.NONE) }
    Box(modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).semantics { contentDescription = "Crop box. Drag the corners or edges." }) {
        Image(img, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.matchParentSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        val b = current.value
                        val l = b[0] / 1000f * size.width; val t = b[1] / 1000f * size.height; val r = b[2] / 1000f * size.width; val bt = b[3] / 1000f * size.height
                        val nl = Math.abs(p.x - l) < slop; val nr = Math.abs(p.x - r) < slop; val nt = Math.abs(p.y - t) < slop; val nb = Math.abs(p.y - bt) < slop
                        val inX = p.x > l - slop && p.x < r + slop; val inY = p.y > t - slop && p.y < bt + slop
                        grab[0] = when {
                            nl && nt -> Grab.TL; nr && nt -> Grab.TR; nl && nb -> Grab.BL; nr && nb -> Grab.BR
                            nl && inY -> Grab.L; nr && inY -> Grab.R; nt && inX -> Grab.T; nb && inX -> Grab.B
                            p.x in l..r && p.y in t..bt -> Grab.MOVE
                            else -> Grab.NONE
                        }
                    },
                    onDrag = { ptr, amount ->
                        ptr.consume()
                        val dx = (amount.x / size.width * 1000).toInt(); val dy = (amount.y / size.height * 1000).toInt()
                        var (l, t, r, b) = current.value
                        when (grab[0]) {
                            Grab.MOVE -> { val w = r - l; val h = b - t; l = (l + dx).coerceIn(0, 1000 - w); t = (t + dy).coerceIn(0, 1000 - h); r = l + w; b = t + h }
                            Grab.L -> l = minOf(l + dx, r - 50); Grab.R -> r = maxOf(r + dx, l + 50)
                            Grab.T -> t = minOf(t + dy, b - 50); Grab.B -> b = maxOf(b + dy, t + 50)
                            Grab.TL -> { l = minOf(l + dx, r - 50); t = minOf(t + dy, b - 50) }
                            Grab.TR -> { r = maxOf(r + dx, l + 50); t = minOf(t + dy, b - 50) }
                            Grab.BL -> { l = minOf(l + dx, r - 50); b = maxOf(b + dy, t + 50) }
                            Grab.BR -> { r = maxOf(r + dx, l + 50); b = maxOf(b + dy, t + 50) }
                            Grab.NONE -> {}
                        }
                        change.value(l, t, r, b)
                    },
                )
            },
        ) {
            val b = box
            val l = b[0] / 1000f * size.width; val t = b[1] / 1000f * size.height; val r = b[2] / 1000f * size.width; val bt = b[3] / 1000f * size.height
            val dimColor = Color.Black.copy(alpha = if (dim) 0.45f else 0f)
            drawRect(dimColor, Offset.Zero, Size(size.width, t))
            drawRect(dimColor, Offset(0f, bt), Size(size.width, size.height - bt))
            drawRect(dimColor, Offset(0f, t), Size(l, bt - t))
            drawRect(dimColor, Offset(r, t), Size(size.width - r, bt - t))
            drawRect(accent, Offset(l, t), Size(r - l, bt - t), style = Stroke(width = 3f))
            for ((hx, hy) in listOf(l to t, r to t, l to bt, r to bt, (l + r) / 2 to t, (l + r) / 2 to bt, l to (t + bt) / 2, r to (t + bt) / 2)) {
                drawCircle(Color.White, 11f, Offset(hx, hy)); drawCircle(accent, 8f, Offset(hx, hy))
            }
        }
    }
}

private fun Modifier.matchParentSize(): Modifier = this.fillMaxWidth().then(Modifier.aspectRatioFill())
private fun Modifier.aspectRatioFill(): Modifier = this

@Composable
fun CropScreen(host: ToolHost, vm: CropViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val box by vm.box.collectAsStateWithLifecycle(); val mode by vm.mode.collectAsStateWithLifecycle()
    val media by vm.mediaBox.collectAsStateWithLifecycle(); val perPage by vm.perPage.collectAsStateWithLifecycle()
    val page by vm.previewPage.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle(); val rangeText by vm.selectionText.collectAsStateWithLifecycle()
    val full = box[0] <= 0 && box[1] <= 0 && box[2] >= 1000 && box[3] >= 1000
    PdfToolScreen(host, vm, "Choose a PDF to crop", "Crop", actionEnabled = (!full || perPage) && (mode != 3 || selected.isNotEmpty()), actionHint = if (full && !perPage) "Drag the box in, or tap Auto-detect" else null) { s, thumbs ->
        val pg = page.coerceIn(0, s.pageCount - 1)
        PreviewPageRow(pg, s.pageCount, vm::setPreviewPage)
        val bmp by produceState<Bitmap?>(null, pg, thumbs) { value = thumbs.render(pg, 900) }
        val b = bmp
        if (b == null) Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = c.accent) }
        else CropCanvas(b, box, vm::setBox)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            ForgeButton("Auto-detect", { vm.autoDetectAsync(pg) }, Modifier.weight(1f), secondary = true)
            ForgeButton("Reset", { vm.resetBox() }, Modifier.weight(1f), secondary = true, enabled = !full)
        }
        GroupLabel("Apply to")
        Segmented(CropPages.entries.map { it.label }, mode, vm::setMode)
        if (mode == 3) PagesChooser(s.pageCount, thumbs, selected, rangeText, vm::setSelection, vm::setSelectionText, Modifier.padding(top = 8.dp))
        ToggleRow("Detect whitespace on every page", perPage, vm::setPerPage, Modifier.padding(top = 12.dp), subtitle = "Each page is trimmed to its own content instead of using this box.")
        ToggleRow("Also change the page size", media, vm::setMedia, Modifier.padding(top = 8.dp), subtitle = "Off keeps the file lossless: the crop box is just a window onto the page.")
    }
}

/** P8 N-up / booklet. */
class ImposeViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {
    override val toolCode = "P8"
    private val modeS = persistedInt("imp.mode", 0); private val sheetS = persistedInt("imp.sheet", 0)
    private val marginS = persistedInt("imp.margin", 18); private val gutterS = persistedInt("imp.gutter", 12)
    private val bordersS = persistedBool("imp.borders", false); private val rtlS = persistedBool("imp.rtl", false)
    private val colFirstS = persistedBool("imp.colFirst", false); private val sideS = persistedInt("imp.side", 0)
    val mode: StateFlow<Int> = modeS.flow; val sheet: StateFlow<Int> = sheetS.flow; val margin: StateFlow<Int> = marginS.flow
    val gutter: StateFlow<Int> = gutterS.flow; val borders: StateFlow<Boolean> = bordersS.flow; val rtl: StateFlow<Boolean> = rtlS.flow
    val columnFirst: StateFlow<Boolean> = colFirstS.flow; val side: StateFlow<Int> = sideS.flow
    private var count = 0

    fun setMode(v: Int) { modeS.value = v; sideS.value = 0 }; fun setSheet(v: Int) { sheetS.value = v }
    fun setMargin(v: Int) { marginS.value = v }; fun setGutter(v: Int) { gutterS.value = v }
    fun setBorders(v: Boolean) { bordersS.value = v }; fun setRtl(v: Boolean) { rtlS.value = v }
    fun setColumnFirst(v: Boolean) { colFirstS.value = v }; fun setSide(v: Int) { sideS.value = v }

    fun spec() = ImposeSpec(
        ImposeMode.entries[modeS.value.coerceIn(0, 2)], SheetSize.entries[sheetS.value.coerceIn(0, 2)], marginS.value.toFloat(), gutterS.value.toFloat(),
        bordersS.value, rtlS.value, colFirstS.value,
    )
    fun sides(): Int = ImposePlan.plan(count.coerceAtLeast(1), spec()).size

    override fun onPdfReady(source: PdfSource) { count = source.pageCount; sideS.value = sideS.value.coerceIn(0, sides() - 1) }

    suspend fun preview(side: Int, widthPx: Int): Bitmap? {
        val src = source.value ?: return null
        val sp = spec()
        return withContext(Dispatchers.IO) { PdfImposer.previewSide(src.file, sp, side, widthPx, File(app.cacheDir, PdfLoader.WORK_DIR)) }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val sp = spec()
        var sheets = 0
        val saved = scope.save(if (sp.mode == ImposeMode.BOOKLET) "booklet" else if (sp.mode == ImposeMode.NUP2) "2up" else "4up", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                PdfOps.load(src.file).use { doc ->
                    PdfImposer.build(doc, sp, null, TransformCtx({ ctx.ensureActive() }, { scope.progress(it) })).use { dest ->
                        sheets = dest.numberOfPages
                        src.protection?.applyTo(dest)
                        dest.save(out)
                    }
                }
            }
        }
        val detail = if (sp.mode == ImposeMode.BOOKLET) "${pagesLabel(sheets)} for printing double-sided (flip on the short edge), then fold and staple"
        else "${pagesLabel(count)} on ${pagesLabel(sheets)}"
        return ToolOutcome(saved, if (sp.mode == ImposeMode.BOOKLET) "Booklet ready" else "Pages combined", detail)
    }
}

@Composable
fun ImposeScreen(host: ToolHost, vm: ImposeViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val mode by vm.mode.collectAsStateWithLifecycle(); val sheet by vm.sheet.collectAsStateWithLifecycle()
    val margin by vm.margin.collectAsStateWithLifecycle(); val gutter by vm.gutter.collectAsStateWithLifecycle()
    val borders by vm.borders.collectAsStateWithLifecycle(); val rtl by vm.rtl.collectAsStateWithLifecycle()
    val colFirst by vm.columnFirst.collectAsStateWithLifecycle(); val side by vm.side.collectAsStateWithLifecycle()
    PdfToolScreen(host, vm, "Choose a PDF to arrange", if (mode == 2) "Make booklet" else "Combine pages") { s, _ ->
        val total = ImposePlan.plan(s.pageCount, vm.spec()).size
        val sd = side.coerceIn(0, total - 1)
        Segmented(ImposeMode.entries.map { it.label }, mode, vm::setMode)
        LivePreview(key = listOf(mode, sheet, margin, gutter, borders, rtl, colFirst, sd), description = "Sheet preview", modifier = Modifier.padding(top = 12.dp)) { vm.preview(sd, 700) }
        PreviewPageRow(sd, total, vm::setSide, Modifier.padding(top = 8.dp), label = if (mode == 2) "Preview side" else "Preview sheet")
        Text(
            if (mode == 2) "${ImposePlan.paddedCount(s.pageCount)} page slots on $total sides" + if (ImposePlan.paddedCount(s.pageCount) > s.pageCount) " (${ImposePlan.paddedCount(s.pageCount) - s.pageCount} blank added)" else ""
            else "${s.pageCount} pages on $total sheets",
            style = ForgeType.Small, color = c.text2,
        )
        GroupLabel("Sheet size")
        ChipRow { SheetSize.entries.forEachIndexed { i, z -> OptionChip(z.label, sheet == i, { vm.setSheet(i) }) } }
        SliderRow("Margin", margin.toFloat(), 0f..60f, "$margin pt", { vm.setMargin(it.toInt()) }, Modifier.padding(top = 8.dp))
        SliderRow("Gap between pages", gutter.toFloat(), 0f..40f, "$gutter pt", { vm.setGutter(it.toInt()) })
        ToggleRow("Borders around pages", borders, vm::setBorders, Modifier.padding(top = 4.dp))
        ToggleRow("Right-to-left binding", rtl, vm::setRtl, Modifier.padding(top = 8.dp), subtitle = "For Arabic, Hebrew or Japanese vertical text.")
        if (mode == 1) ToggleRow("Fill down the columns first", colFirst, vm::setColumnFirst, Modifier.padding(top = 8.dp), subtitle = "Page 2 under page 1, instead of beside it.")
        if (mode == 2) InfoNote("Print double-sided, flipping on the short edge. Fold the stack in half and staple the spine. The page count is padded with blanks to a multiple of 4.", Modifier.padding(top = 8.dp))
    }
}
