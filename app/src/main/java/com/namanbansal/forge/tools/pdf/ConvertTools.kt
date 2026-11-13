package com.namanbansal.forge.tools.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.FileResolver
import com.namanbansal.forge.core.OutputNaming
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.ZipWriter
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.ImageOrientation
import com.namanbansal.forge.core.pdf.ImagePageSize
import com.namanbansal.forge.core.pdf.ImagesPdfSpec
import com.namanbansal.forge.core.pdf.ImagesToPdf
import com.namanbansal.forge.core.pdf.PageRasterizers
import com.namanbansal.forge.core.pdf.PdfPageExporter
import com.namanbansal.forge.core.pdf.PdfSelectionViewModel
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.LivePreview
import com.namanbansal.forge.ui.toolkit.PagesChooser
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.PreviewPageRow
import com.namanbansal.forge.ui.toolkit.ReorderableColumn
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import com.namanbansal.forge.ui.toolkit.rememberFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** P31 PDF to images: pages to PNG / JPG / WebP in Pictures/Forge/<name>/, optionally also as a ZIP. */
class PdfToImagesViewModel(application: Application, saved: SavedStateHandle) : PdfSelectionViewModel(application, saved) {
    override val toolCode = "P31"
    private val fmtS = persistedInt("p2i.fmt", 0); private val dpiS = persistedInt("p2i.dpi", 150)
    private val qualityS = persistedInt("p2i.q", 90); private val zipS = persistedBool("p2i.zip", false); private val pageS = persistedInt("p2i.page", 0)
    val format: StateFlow<Int> = fmtS.flow; val dpi: StateFlow<Int> = dpiS.flow; val quality: StateFlow<Int> = qualityS.flow
    val zip: StateFlow<Boolean> = zipS.flow; val previewPage: StateFlow<Int> = pageS.flow

    fun setFormat(v: Int) { fmtS.value = v }; fun setDpi(v: Int) { dpiS.value = v }; fun setQuality(v: Int) { qualityS.value = v }
    fun setZip(v: Boolean) { zipS.value = v }; fun setPreviewPage(v: Int) { pageS.value = v }
    fun fmt(): ImgFormat = PdfPageExporter.formats[fmtS.value.coerceIn(0, PdfPageExporter.formats.size - 1)]

    override fun onPdfReady(source: PdfSource) { super.onPdfReady(source); pageS.value = pageS.value.coerceIn(0, source.pageCount - 1) }

    /** "2480 x 3508 px, about 1.4 MB" for the previewed page, or why it can't be exported. */
    suspend fun describe(page: Int): String {
        val t = thumbnails.value ?: return ""
        val (wp, hp) = t.pageSize(page) ?: return ""
        val dpi = dpiS.value
        val (w, h) = PdfPageExporter.pixelSize(wp, hp, dpi)
        if (!PdfPageExporter.fits(wp, hp, dpi)) return "$w × $h px is too large for this device. Choose a lower resolution."
        val small = t.render(page, 320) ?: return "$w × $h px"
        val f = fmt()
        val bytes = withContext(Dispatchers.Default) { PdfPageExporter.estimateBytes(small, w, h, f, qualityS.value) }
        return "$w × $h px" + (bytes?.let { " · about ${formatBytes(it)} per page" } ?: "")
    }

    suspend fun fitsAll(): Boolean {
        val t = thumbnails.value ?: return true
        return selection.value.take(50).all { p -> t.pageSize(p)?.let { PdfPageExporter.fits(it.first, it.second, dpiS.value) } ?: true }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val pages = selection.value.filter { it in 0 until src.pageCount }.sorted()
        if (pages.isEmpty()) throw ToolException("Choose at least one page.")
        val f = fmt(); val dpi = dpiS.value; val q = qualityS.value
        val name = primaryName()
        val base = OutputNaming.baseName(name)
        val outs = ArrayList<SavedOutput>()
        val r = PageRasterizers.factory(src.file)
        try {
            for ((n, p) in pages.withIndex()) {
                scope.checkCancelled()
                scope.progress(n.toFloat() / pages.size * (if (zipS.value) 0.9f else 1f))
                val bmp = withContext(Dispatchers.IO) { PdfPageExporter.render(r, p, dpi) }
                try {
                    outs += scope.saveAs(name, "", f.ext, f.mime, images = true, subDir = "$base/", rawName = PdfPageExporter.fileName(base, p, src.pageCount, f)) { out ->
                        withContext(Dispatchers.IO) { PdfPageExporter.encode(bmp, f, q, out) }
                    }
                } finally { bmp.recycle() }
            }
        } finally { r.close() }
        var archive: SavedOutput? = null
        if (zipS.value) {
            archive = scope.save("images", "zip", "application/zip") { out ->
                withContext(Dispatchers.IO) { ZipWriter.write(out, outs.map { o -> o.displayName to { app.contentResolver.openInputStream(o.uri) } }) { } }
            }
        }
        scope.progress(1f)
        val folder = outs.first().folder
        return ToolOutcome(outs.first(), "${if (pages.size == 1) "1 image" else "${pages.size} images"} saved", "$folder · ${f.label} at $dpi dpi" + if (archive != null) " · ZIP included" else "", outs, archive)
    }

    private fun primaryName() = inputs.value.firstOrNull()?.name ?: "document.pdf"
}

private val DPI_CHOICES = listOf(72, 100, 150, 200, 300, 450, 600)

@Composable
fun PdfToImagesScreen(host: ToolHost, vm: PdfToImagesViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val fmt by vm.format.collectAsStateWithLifecycle(); val dpi by vm.dpi.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle(); val zip by vm.zip.collectAsStateWithLifecycle()
    val page by vm.previewPage.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle(); val rangeText by vm.selectionText.collectAsStateWithLifecycle()
    val fits by produceState(true, dpi, selected) { value = vm.fitsAll() }
    PdfToolScreen(
        host, vm, "Choose a PDF to convert", if (selected.size == 1) "Save 1 image" else "Save ${selected.size} images",
        actionEnabled = selected.isNotEmpty() && fits, actionHint = if (!fits) "Too large at this resolution. Choose a lower dpi." else null, showProtectionNote = false,
    ) { s, thumbs ->
        val pg = page.coerceIn(0, s.pageCount - 1)
        LivePreview(key = listOf(pg), description = "Page preview") { thumbs.render(pg, 700) }
        PreviewPageRow(pg, s.pageCount, vm::setPreviewPage, Modifier.padding(top = 8.dp))
        val info by produceState("", pg, dpi, fmt, quality) { value = vm.describe(pg) }
        Text(info, style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(bottom = 8.dp).semantics { contentDescription = "Export size" })
        Segmented(PdfPageExporter.formats.map { it.label.substringBefore(' ') }, fmt, vm::setFormat)
        Text("Resolution", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        ChipRow { DPI_CHOICES.forEach { OptionChip("$it dpi", dpi == it, { vm.setDpi(it) }) } }
        if (vm.fmt().lossy) SliderRow("Quality", quality.toFloat(), 30f..100f, "$quality", { vm.setQuality(it.toInt()) }, Modifier.padding(top = 8.dp))
        PagesChooser(s.pageCount, thumbs, selected, rangeText, vm::setSelection, vm::setSelectionText, Modifier.padding(top = 12.dp))
        ToggleRow("Also make a ZIP", zip, vm::setZip, Modifier.padding(top = 12.dp), subtitle = "One file with every image, handy for sharing.")
        InfoNote("Pictures are saved in Pictures/Forge/${OutputNaming.baseName(vm.inputs.value.firstOrNull()?.name ?: "document")}/ so they appear in your gallery.", Modifier.padding(top = 8.dp))
    }
}

/** P32 Images to PDF: pick pictures, drag to order, choose the page. EXIF orientation is applied; HEIC works where the phone can decode it. */
class ImagesToPdfViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {
    override val toolCode = "P32"
    private val sizeS = persistedInt("i2p.size", 1); private val orientS = persistedInt("i2p.orient", 0)
    private val marginS = persistedInt("i2p.margin", 18); private val qualityS = persistedInt("i2p.q", 85)
    private val wS = persistedInt("i2p.w", 210); private val hS = persistedInt("i2p.h", 297); private val tabS = persistedInt("i2p.tab", 0)
    val pageSize: StateFlow<Int> = sizeS.flow; val orientation: StateFlow<Int> = orientS.flow; val margin: StateFlow<Int> = marginS.flow
    val quality: StateFlow<Int> = qualityS.flow; val customW: StateFlow<Int> = wS.flow; val customH: StateFlow<Int> = hS.flow; val tab: StateFlow<Int> = tabS.flow
    private val _items = MutableStateFlow<List<PickedFile>>(emptyList())
    val items: StateFlow<List<PickedFile>> = _items.asStateFlow()

    fun setPageSize(v: Int) { sizeS.value = v }; fun setOrientation(v: Int) { orientS.value = v }; fun setMargin(v: Int) { marginS.value = v }
    fun setQuality(v: Int) { qualityS.value = v }; fun setCustomW(v: Int) { wS.value = v }; fun setCustomH(v: Int) { hS.value = v }; fun setTab(v: Int) { tabS.value = v }

    fun spec() = ImagesPdfSpec(
        ImagePageSize.entries[sizeS.value.coerceIn(0, 3)], ImageOrientation.entries[orientS.value.coerceIn(0, 2)],
        marginS.value.toFloat(), qualityS.value, wS.value.toFloat(), hS.value.toFloat(),
    )

    override suspend fun onInputsReady(files: List<PickedFile>) { _items.value = files }
    override fun onInputsReset() { _items.value = emptyList(); super.onInputsReset() }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val files = uris.map { FileResolver.resolve(app, it) }
            val all = _items.value + files
            _items.value = all; replaceInputs(all)
        }
    }

    fun remove(index: Int) {
        val list = _items.value.toMutableList(); if (index !in list.indices) return
        list.removeAt(index)
        if (list.isEmpty()) changeFiles() else { _items.value = list; replaceInputs(list) }
    }

    fun move(from: Int, to: Int) {
        val list = _items.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from)); _items.value = list
    }

    fun dragFinished() { replaceInputs(_items.value) }

    suspend fun previewFor(widthPx: Int): Bitmap? {
        val first = _items.value.firstOrNull() ?: return null
        val sp = spec()
        return withContext(Dispatchers.IO) { runCatching { ImagesToPdf.previewPage(sp, ImageIO.decode(app, first.uri, maxDim = 900), widthPx) }.getOrNull() }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val files = _items.value
        if (files.isEmpty()) throw ToolException("Add at least one picture.")
        val sp = spec()
        val saved = scope.save("images", "pdf", "application/pdf") { out ->
            withContext(Dispatchers.IO) {
                val ctx = kotlin.coroutines.coroutineContext
                ImagesToPdf.build(files.size, sp, out, null, TransformCtx({ ctx[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() } }, { scope.progress(it) })) { i ->
                    try { ImageIO.decode(app, files[i].uri, maxDim = 5000, maxPixels = 20_000_000L) }
                    catch (e: ToolException) { throw ToolException("${files[i].name}: ${e.message}") }
                }
            }
        }
        return ToolOutcome(saved, "PDF created", "${pagesLabel(files.size)} · ${sp.size.label}")
    }
}

@Composable
fun ImagesToPdfScreen(host: ToolHost, vm: ImagesToPdfViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val items by vm.items.collectAsStateWithLifecycle(); val tab by vm.tab.collectAsStateWithLifecycle()
    val size by vm.pageSize.collectAsStateWithLifecycle(); val orient by vm.orientation.collectAsStateWithLifecycle()
    val margin by vm.margin.collectAsStateWithLifecycle(); val quality by vm.quality.collectAsStateWithLifecycle()
    val w by vm.customW.collectAsStateWithLifecycle(); val h by vm.customH.collectAsStateWithLifecycle()
    val add = rememberFilePicker(FileKind.pickerMimes(FileKind.IMAGE), true) { vm.addUris(it) }
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), multiple = true, title = "Choose pictures to turn into a PDF", hint = "Photos, scans or screenshots, including HEIC. You can add more and reorder them next."),
        actionLabel = "Create PDF", actionEnabled = items.isNotEmpty(), showInputCard = false,
    ) {
        Segmented(listOf("Pictures (${items.size})", "Page"), tab, vm::setTab, Modifier.padding(bottom = 12.dp))
        if (tab == 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Drag the handle to set the page order", style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(1f))
                TextAction("Add pictures", add, icon = ForgeIcons.Plus)
            }
            ReorderableColumn(items = items, key = { it.uri.toString() + "#" + it.name }, modifier = Modifier.weight(1f).fillMaxWidth(), onMove = vm::move, onDragStart = {}, onDragEnd = vm::dragFinished) { file, index, dragging, handle ->
                PictureRow(file, index + 1, dragging, handle, onRemove = { vm.remove(index) })
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                LivePreview(key = listOf(size, orient, margin, w, h, items.firstOrNull()?.uri), description = "Page preview") { vm.previewFor(600) }
                Text("Page size", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow { ImagePageSize.entries.forEachIndexed { i, z -> OptionChip(z.label, size == i, { vm.setPageSize(i) }) } }
                if (size == 3) {
                    SliderRow("Width", w.toFloat(), 50f..500f, "$w mm", { vm.setCustomW(it.toInt()) }, Modifier.padding(top = 8.dp))
                    SliderRow("Height", h.toFloat(), 50f..700f, "$h mm", { vm.setCustomH(it.toInt()) })
                }
                if (size != 0) {
                    Text("Orientation", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Segmented(ImageOrientation.entries.map { it.label }, orient, vm::setOrientation)
                    SliderRow("Margin", margin.toFloat(), 0f..72f, "$margin pt", { vm.setMargin(it.toInt()) }, Modifier.padding(top = 8.dp))
                }
                SliderRow("Picture quality", quality.toFloat(), 40f..100f, "$quality", { vm.setQuality(it.toInt()) }, Modifier.padding(top = 4.dp))
                InfoNote("Photos are turned upright using their orientation data. Pictures with transparency get a white background.", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun PictureRow(file: PickedFile, number: Int, dragging: Boolean, handle: Modifier, onRemove: () -> Unit) {
    val c = ForgeTheme.colors
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, file.uri) { value = withContext(Dispatchers.IO) { runCatching { ImageIO.decode(ctx, file.uri, maxDim = 160) }.getOrNull() } }
    ForgeCard(Modifier.fillMaxWidth().let { if (dragging) it.border(1.5.dp, c.accent, com.namanbansal.forge.ui.theme.ForgeShapes.Card) else it }) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(48.dp).semantics { contentDescription = "Drag to reorder ${file.name}" }, contentAlignment = Alignment.Center) { Icon(ForgeIcons.Grip, null, tint = c.text2, modifier = Modifier.size(22.dp)) }
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.size(54.dp).clip(shape).background(c.surface2).border(1.dp, c.border, shape), contentAlignment = Alignment.Center) {
                thumb?.let { b -> Image(remember(b) { b.asImageBitmap() }, null, Modifier.size(54.dp), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$number · ${file.name}", style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(file.size), style = ForgeType.Small, color = c.text2)
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onRemove).semantics { contentDescription = "Remove ${file.name}" }, contentAlignment = Alignment.Center) {
                Icon(ForgeIcons.Close, null, tint = c.text2, modifier = Modifier.size(18.dp))
            }
        }
    }
}
