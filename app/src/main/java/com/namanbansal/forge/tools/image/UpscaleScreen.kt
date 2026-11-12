package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.Lanczos
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/**
 * I7 "Enhance (2x/4x)": Lanczos-3 resampling plus an optional unsharp mask. It is classic image processing, not machine learning,
 * and is labelled that way on purpose (see docs/DECISIONS.md).
 */
class UpscaleViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I7"
    override val previewDim: Int get() = 900
    private val factorS = persistedInt("up.f", 2)
    private val sharpS = persistedInt("up.s", 1)
    val factor: StateFlow<Int> = factorS.flow
    val sharpen: StateFlow<Int> = sharpS.flow
    fun setFactor(f: Int) { factorS.value = f }
    fun setSharpen(s: Int) { sharpS.value = s }

    fun fits(factor: Int): Boolean = info.value?.let { it.pixels * factor * factor <= Lanczos.MAX_OUTPUT_PIXELS } ?: true
    fun outputSize(): Pair<Int, Int>? = info.value?.let { Lanczos.outputSize(it.width, it.height, factorS.value) }
    val canRun: Boolean get() = fits(factorS.value)

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        if (!stateRestored && !fits(factorS.value)) factorS.value = 2
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val f = factorS.value
        val file = inputs.value.first()
        if (!fits(f)) throw ToolException("That would be over ${Lanczos.MAX_OUTPUT_PIXELS / 1_000_000} megapixels. Choose 2x, or start from a smaller picture.")
        val ctx = kotlinx.coroutines.currentCoroutineContext()
        val src = decodeFull(file)
        val big = try {
            Lanczos.resize(src, src.width * f, src.height * f, { ctx.ensureActive() }) { scope.progress(it * 0.9f) }
        } finally { src.recycle() }
        Lanczos.unsharp(big, SHARP_AMOUNT[sharpS.value.coerceIn(0, 3)], if (f == 2) 2 else 3, checkCancelled = { ctx.ensureActive() })
        val fmt = if (info.value?.mime == "image/jpeg") ImgFormat.JPEG else ImgFormat.PNG
        val saved = scope.writeImage(file, "enhanced${f}x", big, fmt, 95)
        return ToolOutcome(saved, "Enhanced ${f}x", "${big.width} × ${big.height} px")
    }

    companion object { val SHARP_AMOUNT = floatArrayOf(0f, 0.4f, 0.8f, 1.3f) }
}

@Composable
fun UpscaleScreen(host: ToolHost, vm: UpscaleViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val f by vm.factor.collectAsStateWithLifecycle()
    val s by vm.sharpen.collectAsStateWithLifecycle()
    val c = ForgeTheme.colors
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), title = "Choose a picture to enlarge", hint = "Enlarges 2x or 4x with sharp, clean resampling."),
        actionLabel = "Enhance ${f}x", actionEnabled = vm.fits(f),
        actionHint = if (!vm.fits(f)) "Too large: choose 2x or a smaller picture." else null,
        inputSubtitle = { it.subtitle(info) }, optionsReady = preview != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ImagePreview(preview, Modifier.fillMaxWidth().height(220.dp))
            LabelText("Enlarge by")
            ChipRow { listOf(2, 4).forEach { n -> OptionChip("${n}x", f == n, { vm.setFactor(n) }) } }
            vm.outputSize()?.let { (w, h) -> Text("Result: $w × $h px" + if (!vm.fits(f)) " (too large)" else "", style = ForgeType.Label, color = if (vm.fits(f)) c.accent else c.text2) }
            LabelText("Sharpening")
            ChipRow { listOf("Off", "Light", "Medium", "Strong").forEachIndexed { i, label -> OptionChip(label, s == i, { vm.setSharpen(i) }) } }
            InfoNote("This is classic high-quality resampling (Lanczos) with sharpening. It makes pictures larger and crisper, but it can't invent detail that isn't there, and it isn't AI.")
        }
    }
}
