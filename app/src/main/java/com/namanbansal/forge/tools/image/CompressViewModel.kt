package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.BitmapOps
import com.namanbansal.forge.core.image.ImageCodec
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.MetadataStripper
import com.namanbansal.forge.core.image.StripMode
import com.namanbansal.forge.core.image.TargetSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the live preview currently shows. */
data class CompressPreview(val after: Bitmap, val estimatedBytes: Long, val quality: Int)

/** I2 Compress: quality slider with split before/after preview, a target file size mode (binary search on quality) and batch. */
class CompressViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I2"
    override val previewDim: Int get() = 1200

    private val targetMode = persistedBool("cp.target", false)
    private val qualityS = persistedInt("cp.q", 75)
    private val targetKbS = persistedInt("cp.kb", 500)
    private val formatS = persistedString("cp.fmt", "SAME")
    private val shrinkS = persistedBool("cp.shrink", true)

    val target: StateFlow<Boolean> = targetMode.flow
    val quality: StateFlow<Int> = qualityS.flow
    val targetKb: StateFlow<Int> = targetKbS.flow
    val format: StateFlow<String> = formatS.flow
    val allowShrink: StateFlow<Boolean> = shrinkS.flow

    private val _after = MutableStateFlow<CompressPreview?>(null)
    val after: StateFlow<CompressPreview?> = _after.asStateFlow()
    private var previewJob: Job? = null

    fun setTarget(v: Boolean) { targetMode.value = v; refreshPreview() }
    fun setQuality(q: Int) { qualityS.value = q.coerceIn(1, 100); refreshPreview() }
    fun setTargetKb(kb: Int) { targetKbS.value = kb.coerceIn(5, 200_000); refreshPreview() }
    fun setFormat(f: String) { formatS.value = f; refreshPreview() }
    fun setShrink(v: Boolean) { shrinkS.value = v }

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) { refreshPreview() }

    fun outputFormat(info: ImageInfo?): ImgFormat = when (formatS.value) {
        "JPG" -> ImgFormat.JPEG; "WEBP" -> ImgFormat.WEBP_LOSSY; "PNG" -> ImgFormat.PNG
        else -> when (info?.mime) { "image/png" -> ImgFormat.PNG; "image/webp" -> ImgFormat.WEBP_LOSSY; else -> ImgFormat.JPEG }
    }

    /** Re-encodes the preview picture (debounced) so the split slider and size estimate follow the controls. */
    private fun refreshPreview() {
        val base = preview.value ?: return
        val inf = info.value ?: return
        val fmt = outputFormat(inf)
        val q = qualityS.value
        val byTarget = targetMode.value && fmt.lossy
        val kb = targetKbS.value
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            delay(120)
            val ratio = base.width.toDouble() * base.height / inf.pixels.coerceAtLeast(1)
            val usedQ = if (byTarget) TargetSize.search((kb * 1024L * ratio).toLong().coerceAtLeast(500)) { ImageCodec.encodedSize(base, fmt, it) }.quality else q
            val bytes = ImageCodec.encodeToBytes(base, fmt, usedQ)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
            _after.value = CompressPreview(decoded, (bytes.size / ratio.coerceAtLeast(1e-6)).toLong(), usedQ)
        }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val files = inputs.value
        var before = 0L
        var after = 0L
        val r = with(scope) {
            batch(files) { _, f ->
                val finfo = ImageIO.info(app, f.uri)
                val fmt = outputFormat(finfo)
                val orig = readBytes(f)
                before += orig.size
                if (fmt == ImgFormat.PNG) {
                    // Lossless: drop metadata chunks and re-encode, keep whichever is smaller (or the original bytes if neither helps).
                    val stripped = if (finfo.mime == "image/png") runCatching { MetadataStripper.strip(orig, StripMode.ALL, app.cacheDir) }.getOrNull() else null
                    val bmp = decodeFull(f)
                    val re = try { ImageCodec.encodeToBytes(bmp, ImgFormat.PNG, 100) } finally { bmp.recycle() }
                    val best = listOfNotNull(stripped, re, orig.takeIf { finfo.mime == "image/png" }).minBy { it.size }
                    after += best.size
                    saveAs(f.name, "compressed", "png", "image/png", images = true) { it.write(best) }
                } else {
                    var bmp = decodeFull(f)
                    try {
                        val q: Int
                        if (targetMode.value && fmt.lossy) {
                            val goal = targetKbS.value * 1024L
                            var res = TargetSize.search(goal, checkCancelled = { }, sizeAt = { ImageCodec.encodedSize(bmp, fmt, it) })
                            var tries = 0
                            while (!res.fits && shrinkS.value && tries < 5 && bmp.width > 64) {
                                val smaller = BitmapOps.resize(bmp, (bmp.width * 0.8).toInt().coerceAtLeast(1), (bmp.height * 0.8).toInt().coerceAtLeast(1))
                                bmp.recycle(); bmp = smaller; tries++
                                res = TargetSize.search(goal, sizeAt = { ImageCodec.encodedSize(bmp, fmt, it) })
                            }
                            q = res.quality
                        } else q = qualityS.value
                        val saved = writeImage(f, "compressed", bmp, fmt, q)
                        after += saved.size
                        saved
                    } finally { bmp.recycle() }
                }
            }
        }
        val pct = if (before > 0) ((before - after) * 100 / before).toInt() else 0
        return outcome(r, "Compressed", if (pct > 0) "$pct% smaller" else "Already compact")
    }
}
