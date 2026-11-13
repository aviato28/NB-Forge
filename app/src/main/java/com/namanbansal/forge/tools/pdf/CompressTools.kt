package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pdf.CompressOptions
import com.namanbansal.forge.core.pdf.CompressPreset
import com.namanbansal.forge.core.pdf.PdfCompressor
import com.namanbansal.forge.core.pdf.PdfGrayscale
import com.namanbansal.forge.core.pdf.PdfImagePass
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.LivePreview
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.namanbansal.forge.ui.toolkit.PreviewPageRow
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** P9 Compress. Estimates the output by sampling pictures, never returns a bigger file than the original. */
class CompressViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {
    override val toolCode = "P9"

    private val presetState = persistedInt("compress.preset", 1)
    private val dpiState = persistedInt("compress.dpi", 150)
    private val qualityState = persistedInt("compress.quality", 75)
    private val stripState = persistedBool("compress.strip", false)
    private val grayState = persistedBool("compress.gray", false)
    private val previewPageState = persistedInt("compress.page", 0)
    val preset: StateFlow<Int> = presetState.flow
    val dpi: StateFlow<Int> = dpiState.flow
    val quality: StateFlow<Int> = qualityState.flow
    val strip: StateFlow<Boolean> = stripState.flow
    val gray: StateFlow<Boolean> = grayState.flow
    val previewPage: StateFlow<Int> = previewPageState.flow

    sealed interface Est { data object Working : Est; class Ready(val e: PdfCompressor.Estimate) : Est; data object Failed : Est }
    private val _estimate = MutableStateFlow<Est>(Est.Working)
    val estimate: StateFlow<Est> = _estimate.asStateFlow()
    private var estJob: Job? = null

    fun options(): CompressOptions {
        val p = CompressPreset.entries[presetState.value.coerceIn(0, 3)]
        return if (p == CompressPreset.CUSTOM) CompressOptions(dpiState.value, qualityState.value, stripState.value, grayState.value)
        else CompressOptions(p.dpi, p.quality, stripState.value, grayState.value)
    }

    fun setPreset(i: Int) { presetState.value = i; refresh() }
    fun setDpi(v: Int) { dpiState.value = v; refresh() }
    fun setQuality(v: Int) { qualityState.value = v; refresh() }
    fun setStrip(v: Boolean) { stripState.value = v }
    fun setGray(v: Boolean) { grayState.value = v; refresh() }
    fun setPreviewPage(p: Int) { previewPageState.value = p }

    override fun onPdfReady(source: PdfSource) { refresh() }

    private fun refresh() {
        val src = source.value ?: return
        val o = options()
        val original = inputs.value.firstOrNull()?.size?.takeIf { it > 0 } ?: src.file.length()
        estJob?.cancel()
        _estimate.value = Est.Working
        estJob = viewModelScope.launch {
            delay(350)
            _estimate.value = withContext(Dispatchers.IO) { runCatching { Est.Ready(PdfCompressor.estimate(src.file, original, o)) }.getOrDefault(Est.Failed) }
        }
    }

    suspend fun preview(page: Int, widthPx: Int): android.graphics.Bitmap? {
        val o = options()
        return previewPage(page, widthPx) { doc -> PdfImagePass.run(doc, o.images(), pages = setOf(page)) }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val o = options()
        val original = inputs.value.firstOrNull()?.size?.takeIf { it > 0 } ?: src.file.length()
        val tmp = File(File(app.cacheDir, PdfLoader.WORK_DIR).apply { mkdirs() }, "cmp-${UUID.randomUUID()}.pdf")
        try {
            val ctx = currentCoroutineContext()
            var images = 0
            var merged = 0
            withContext(Dispatchers.IO) {
                PdfOps.load(src.file).use { doc ->
                    val stats = PdfCompressor.compress(doc, o, TransformCtx({ ctx.ensureActive() }, { scope.progress(it * 0.9f) }))
                    images = stats.recompressed; merged = stats.deduped
                    src.protection?.applyTo(doc)
                    tmp.outputStream().use { doc.save(it) }
                }
            }
            val keepOriginal = tmp.length() >= original
            val saved = scope.save("compressed", "pdf", "application/pdf") { out ->
                withContext(Dispatchers.IO) {
                    if (!keepOriginal) tmp.inputStream().use { it.copyTo(out) }
                    else if (src.protection == null) src.file.inputStream().use { it.copyTo(out) }
                    else PdfOps.load(src.file).use { d -> src.protection.applyTo(d); d.save(out) }
                }
            }
            scope.progress(1f)
            return if (keepOriginal) ToolOutcome(saved, "Already compact", "Compressing would have made this file larger, so the original was kept unchanged.")
            else ToolOutcome(saved, "Compressed", buildString {
                append("${formatBytes(original)} to ${formatBytes(tmp.length())}")
                if (images > 0) append(" · $images ${if (images == 1) "picture" else "pictures"} recompressed")
                if (merged > 0) append(" · $merged duplicate ${if (merged == 1) "picture" else "pictures"} merged")
            })
        } finally { tmp.delete() }
    }
}

@Composable
fun CompressScreen(host: ToolHost, vm: CompressViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val preset by vm.preset.collectAsStateWithLifecycle(); val dpi by vm.dpi.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle(); val strip by vm.strip.collectAsStateWithLifecycle()
    val gray by vm.gray.collectAsStateWithLifecycle(); val est by vm.estimate.collectAsStateWithLifecycle()
    val page by vm.previewPage.collectAsStateWithLifecycle()
    PdfToolScreen(host, vm, "Choose a PDF to compress", "Compress") { s, _ ->
        Segmented(CompressPreset.entries.map { it.label }, preset, vm::setPreset)
        val p = CompressPreset.entries[preset.coerceIn(0, 3)]
        Text(p.blurb + if (p != CompressPreset.CUSTOM) "  ·  ${p.dpi} dpi, quality ${p.quality}" else "", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(vertical = 8.dp))
        if (p == CompressPreset.CUSTOM) {
            Text("Image resolution", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            ChipRow { listOf(50, 72, 96, 150, 200, 300, 400).forEach { OptionChip("$it dpi", dpi == it, { vm.setDpi(it) }) } }
            SliderRow("JPEG quality", quality.toFloat(), 20f..95f, "$quality", { vm.setQuality(it.toInt()) }, modifier = Modifier.padding(top = 8.dp))
        }
        ToggleRow("Convert to grayscale", gray, vm::setGray, Modifier.padding(top = 8.dp), subtitle = "Black and white pictures are smaller.")
        ToggleRow("Remove metadata", strip, vm::setStrip, Modifier.padding(top = 8.dp), subtitle = "Title, author and the hidden XMP packet.")
        GroupLabel("Estimated result")
        ForgeCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                when (val e = est) {
                    CompressViewModel.Est.Working -> Text("Sampling the pictures…", style = ForgeType.Body, color = c.text2)
                    CompressViewModel.Est.Failed -> Text("Couldn't estimate. You can still compress.", style = ForgeType.Body, color = c.text2)
                    is CompressViewModel.Est.Ready -> {
                        val x = e.e
                        val pct = if (x.originalBytes > 0) ((1 - x.estimatedBytes.toDouble() / x.originalBytes) * 100).toInt() else 0
                        Row { Text("${formatBytes(x.originalBytes)}  →  ", style = ForgeType.Title, color = c.text2); Text("about ${formatBytes(x.estimatedBytes)}", style = ForgeType.Title, color = c.text) }
                        Text(
                            if (x.images == 0) "No pictures found, so savings come only from tidying the file."
                            else if (pct <= 0) "Pictures here are already compact. If it can't shrink, your original is kept."
                            else "About $pct% smaller · from ${x.sampled} of ${x.images} pictures sampled",
                            style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        GroupLabel("Preview")
        PreviewPageRow(page.coerceIn(0, s.pageCount - 1), s.pageCount, vm::setPreviewPage)
        LivePreview(key = listOf(page, preset, dpi, quality, gray), description = "Compressed page preview") { vm.preview(page.coerceIn(0, s.pageCount - 1), 700) }
        InfoNote("Pictures already below the target resolution are left alone, identical pictures are stored once, and unused objects are dropped.", Modifier.padding(top = 8.dp))
    }
}

/** P10 Grayscale. */
class GrayscaleViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P10"
    override val action = "grayscale"
    override val headline get() = "Converted to grayscale"
    private val pageState = persistedInt("gray.page", 0)
    val page: StateFlow<Int> = pageState.flow
    fun setPage(p: Int) { pageState.value = p }
    suspend fun preview(widthPx: Int, p: Int) = previewPage(p, widthPx) { PdfGrayscale.convert(it, pages = setOf(p)) }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val (stats, ops) = PdfGrayscale.convert(doc, ctx = ctx)
        return "${stats.recompressed} ${if (stats.recompressed == 1) "picture" else "pictures"} and $ops colour ${if (ops == 1) "setting" else "settings"} converted"
    }
}

@Composable
fun GrayscaleScreen(host: ToolHost, vm: GrayscaleViewModel = viewModel()) {
    val page by vm.page.collectAsStateWithLifecycle()
    PdfToolScreen(host, vm, "Choose a PDF to convert", "Convert to grayscale") { s, _ ->
        PreviewPageRow(page.coerceIn(0, s.pageCount - 1), s.pageCount, vm::setPage)
        LivePreview(key = page, description = "Grayscale preview") { vm.preview(700, page.coerceIn(0, s.pageCount - 1)) }
        InfoNote("Pictures and colours in text, lines and fills become gray. Patterns and gradients keep their colour.", Modifier.padding(top = 12.dp))
    }
}

/** P14 Optimise: honest "optimise for web" is not possible with PDFBox 2.0 (no linearisation, no object streams): compress and tidy. */
class OptimiseViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P14"
    override val action = "optimised"
    override val headline get() = "Optimised"
    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val n = PdfCompressor.compressStreams(doc)
        return "$n ${if (n == 1) "stream" else "streams"} compressed, unused objects dropped, structure rewritten"
    }
}

@Composable
fun OptimiseScreen(host: ToolHost, vm: OptimiseViewModel = viewModel()) {
    PdfToolScreen(host, vm, "Choose a PDF to optimise", "Optimise") { _, _ ->
        InfoNote("Compresses uncompressed streams, drops objects nothing uses and rewrites the file cleanly. Pictures are not changed (use Compress for that).")
        InfoNote("This is not \"fast web view\": linearisation and object streams are not available in Forge's PDF engine, so the first page won't load earlier over slow networks.", Modifier.padding(top = 8.dp))
    }
}
