package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.StateFlow

/** I3 Convert: HEIC / WebP / AVIF / PNG / JPG / SVG in, JPG / PNG / WebP / ICO (AVIF where the device can) out. Batch. */
class ConvertViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I3"
    private val formatS = persistedString("cv.fmt", ImgFormat.WEBP_LOSSY.name)
    private val qualityS = persistedInt("cv.q", 85)
    private val svgS = persistedInt("cv.svg", 1024)
    val format: StateFlow<String> = formatS.flow
    val quality: StateFlow<Int> = qualityS.flow
    val svgSize: StateFlow<Int> = svgS.flow
    fun setFormat(f: ImgFormat) { formatS.value = f.name }
    fun setQuality(q: Int) { qualityS.value = q }
    fun setSvgSize(s: Int) { svgS.value = s }
    fun selected(): ImgFormat = ImgFormat.available().firstOrNull { it.name == formatS.value } ?: ImgFormat.JPEG
    val hasSvg: Boolean get() = inputs.value.any { it.name.endsWith(".svg", true) || it.mime?.contains("svg") == true }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val files = inputs.value
        val fmt = selected()
        val r = with(scope) {
            batch(files) { _, f ->
                val i = ImageIO.info(app, f.uri)
                val bmp = if (i.isSvg) ImageIO.renderSvgBytes(readBytes(f), svgS.value) else decodeFull(f)
                try { writeImage(f, "converted", bmp, fmt, qualityS.value) } finally { bmp.recycle() }
            }
        }
        return outcome(r, "Converted to ${fmt.label}")
    }
}

@Composable
fun ConvertScreen(host: ToolHost, vm: ConvertViewModel = viewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val fmtName by vm.format.collectAsStateWithLifecycle()
    val q by vm.quality.collectAsStateWithLifecycle()
    val svg by vm.svgSize.collectAsStateWithLifecycle()
    val fmt = vm.selected()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), multiple = true, title = "Choose pictures to convert", hint = "HEIC, WebP, AVIF, PNG, JPG and SVG all work."),
        actionLabel = if (inputs.size > 1) "Convert ${inputs.size} pictures" else "Convert to ${fmt.label}",
        inputSubtitle = { it.subtitle(info) }, optionsReady = preview != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ImagePreview(preview, Modifier.fillMaxWidth().height(180.dp))
            LabelText("Convert to")
            ChipRow { ImgFormat.available().forEach { f -> OptionChip(f.label, fmt == f, { vm.setFormat(f) }) } }
            if (fmt.lossy) SliderRow("Quality", q.toFloat(), 40f..100f, "$q", { vm.setQuality(it.toInt()) })
            if (vm.hasSvg) {
                LabelText("SVG size (longest side)")
                ChipRow { listOf(512, 1024, 2048, 4096).forEach { s -> OptionChip("$s px", svg == s, { vm.setSvgSize(s) }) } }
            }
            when (fmt) {
                ImgFormat.JPEG -> InfoNote("JPG has no transparency: transparent areas become white.")
                ImgFormat.ICO -> InfoNote("The icon file contains 16, 32, 48, 64, 128 and 256 px versions. Non-square pictures are centred.")
                ImgFormat.WEBP_LOSSLESS -> InfoNote("Lossless WebP keeps every pixel and is usually smaller than PNG.")
                else -> {}
            }
            if (!com.namanbansal.forge.core.image.ImageCodec.avifEncodeSupported()) InfoNote("AVIF can be opened and converted from, but this version of Android can't write it, so it isn't offered as an output.")
        }
    }
}
