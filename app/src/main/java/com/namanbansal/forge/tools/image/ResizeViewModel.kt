package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.BitmapOps
import com.namanbansal.forge.core.image.CropMath
import com.namanbansal.forge.core.image.FRect
import com.namanbansal.forge.core.image.FitMode
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.ResizeMath
import com.namanbansal.forge.core.image.ViewTransform
import kotlinx.coroutines.flow.StateFlow

enum class ResizeMode(val label: String) { PIXELS("Pixels"), PERCENT("Percent"), PRESET("Presets"), CROP("Crop") }

/** What to cut from the source and how big the result is (display space = after rotate / mirror). */
class ResizePlan(val srcRect: IntArray, val outW: Int, val outH: Int)

/** I1 Resize / crop: pixel, percent, preset or crop, with rotate / mirror, on one picture or a batch. */
class ResizeViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I1"

    private val modeS = persistedString("rs.mode", ResizeMode.PIXELS.name)
    private val wS = persistedInt("rs.w", 0)
    private val hS = persistedInt("rs.h", 0)
    private val lockS = persistedBool("rs.lock", true)
    private val percentS = persistedInt("rs.pct", 50)
    private val presetS = persistedString("rs.preset", "ig-square")
    private val rotS = persistedInt("rs.rot", 0)
    private val flipS = persistedBool("rs.flip", false)
    private val ratioS = persistedInt("rs.ratio", 0)
    private val formatS = persistedString("rs.fmt", "SAME")
    private val qualityS = persistedInt("rs.q", 90)
    private val cropS = persisted<FloatArray>("rs.crop", floatArrayOf(0f, 0f, 1f, 1f), decode = { it as? FloatArray })

    val mode: StateFlow<String> = modeS.flow
    val width: StateFlow<Int> = wS.flow
    val height: StateFlow<Int> = hS.flow
    val lock: StateFlow<Boolean> = lockS.flow
    val percent: StateFlow<Int> = percentS.flow
    val presetId: StateFlow<String> = presetS.flow
    val rotation: StateFlow<Int> = rotS.flow
    val flipH: StateFlow<Boolean> = flipS.flow
    val ratioIndex: StateFlow<Int> = ratioS.flow
    val format: StateFlow<String> = formatS.flow
    val quality: StateFlow<Int> = qualityS.flow
    /** Crop rectangle as fractions (l, t, r, b) of the displayed picture. */
    val crop: StateFlow<FloatArray> = cropS.flow

    val transform: ViewTransform get() = ViewTransform(rotS.value, flipS.value)
    val isBatch: Boolean get() = inputs.value.size > 1
    fun currentMode(): ResizeMode = ResizeMode.entries.firstOrNull { it.name == modeS.value } ?: ResizeMode.PIXELS

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        if (!stateRestored || wS.value <= 0) { wS.value = info.width; hS.value = info.height }
        if (files.size > 1 && currentMode() == ResizeMode.CROP) modeS.value = ResizeMode.PIXELS.name
    }

    fun setMode(m: ResizeMode) { modeS.value = m.name }

    /** Display size of the picture after the current rotation. */
    fun displaySize(): Pair<Int, Int> = info.value?.let { transform.size(it.width, it.height) } ?: (0 to 0)

    fun setWidth(w: Int) {
        val (dw, dh) = displaySize()
        wS.value = w.coerceIn(1, MAX_SIDE)
        if (lockS.value && dw > 0) hS.value = ResizeMath.lockedHeight(dw, dh, wS.value).coerceIn(1, MAX_SIDE)
    }

    fun setHeight(h: Int) {
        val (dw, dh) = displaySize()
        hS.value = h.coerceIn(1, MAX_SIDE)
        if (lockS.value && dh > 0) wS.value = ResizeMath.lockedWidth(dw, dh, hS.value).coerceIn(1, MAX_SIDE)
    }

    fun setLock(v: Boolean) { lockS.value = v; if (v) setWidth(wS.value) }
    fun setPercent(p: Int) { percentS.value = p.coerceIn(1, 400) }
    fun setPreset(id: String) { presetS.value = id }
    fun setQuality(q: Int) { qualityS.value = q }
    fun setFormat(f: String) { formatS.value = f }

    fun rotate(right: Boolean) { rotS.value = (if (right) transform.rotatedRight() else transform.rotatedLeft()).normalizedRotation; afterTurn() }
    fun flip() { flipS.value = !flipS.value; afterTurn() }

    private fun afterTurn() {
        cropS.value = floatArrayOf(0f, 0f, 1f, 1f); applyRatio(ratioIndex.value)
        // The locked pixel size follows the turned picture.
        val (dw, dh) = displaySize()
        if (dw > 0) { wS.value = dw; hS.value = dh }
    }

    fun setCrop(l: Float, t: Float, r: Float, b: Float) { cropS.value = floatArrayOf(l, t, r, b) }

    fun setRatio(index: Int) { ratioS.value = index; applyRatio(index) }

    private fun applyRatio(index: Int) {
        val ratio = RATIOS.getOrNull(index)?.second ?: return
        val (dw, dh) = displaySize()
        if (dw == 0) return
        val r = CropMath.withRatio(
            cropS.value.let { FRect(it[0] * dw, it[1] * dh, it[2] * dw, it[3] * dh) }, ratio.takeIf { it > 0 }, dw.toFloat(), dh.toFloat(),
        )
        setCrop(r.l / dw, r.t / dh, r.r / dw, r.b / dh)
    }

    fun outputFormat(info: ImageInfo?): ImgFormat = when (formatS.value) {
        "JPG" -> ImgFormat.JPEG; "PNG" -> ImgFormat.PNG; "WEBP" -> ImgFormat.WEBP_LOSSY
        else -> when (info?.mime) { "image/png" -> ImgFormat.PNG; "image/webp" -> ImgFormat.WEBP_LOSSY; else -> ImgFormat.JPEG }
    }

    /** Pure planning (unit-tested): source rectangle and output size for one picture. */
    fun plan(srcW: Int, srcH: Int): ResizePlan {
        val t = transform
        val (dw, dh) = t.size(srcW, srcH)
        val full = intArrayOf(0, 0, srcW, srcH)
        return when (currentMode()) {
            ResizeMode.PIXELS -> {
                val tw = wS.value.coerceAtLeast(1)
                val th = if (lockS.value || isBatch) ResizeMath.lockedHeight(dw, dh, tw) else hS.value.coerceAtLeast(1)
                ResizePlan(full, tw, th)
            }
            ResizeMode.PERCENT -> ResizeMath.percent(dw, dh, percentS.value.toDouble()).let { ResizePlan(full, it.first, it.second) }
            ResizeMode.PRESET -> {
                val p = ResizeMath.preset(presetS.value) ?: ResizeMath.PRESETS.first()
                if (p.mode == FitMode.FILL) {
                    val c = ResizeMath.fillCrop(dw, dh, p.width, p.height)
                    val src = CropMath.toSourceRect(FRect(c[0].toFloat() / dw, c[1].toFloat() / dh, c[2].toFloat() / dw, c[3].toFloat() / dh), t, srcW, srcH)
                    ResizePlan(src, p.width, p.height)
                } else ResizeMath.outputFor(p, dw, dh).let { ResizePlan(full, it.first, it.second) }
            }
            ResizeMode.CROP -> {
                val c = cropS.value
                val src = CropMath.toSourceRect(FRect(c[0], c[1], c[2], c[3]), t, srcW, srcH)
                val sw = src[2] - src[0]; val sh = src[3] - src[1]
                val (ow, oh) = if (t.swaps()) sh to sw else sw to sh
                ResizePlan(src, ow, oh)
            }
        }
    }

    /** Output size shown under the preview for the first picture. */
    fun outputSize(): Pair<Int, Int>? = info.value?.let { i -> plan(storedWidth(i), storedHeight(i)).let { it.outW to it.outH } }

    // Decoded bitmaps are upright (EXIF applied), so "stored" here already means upright.
    private fun storedWidth(i: ImageInfo) = i.width
    private fun storedHeight(i: ImageInfo) = i.height

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val files = inputs.value
        val action = if (currentMode() == ResizeMode.CROP) "cropped" else "resized"
        val r = with(scope) {
            batch(files) { _, f ->
                val src = decodeFull(f)
                try {
                    val plan = plan(src.width, src.height)
                    val t = transform
                    var bmp = BitmapOps.crop(src, plan.srcRect)
                    // Resize in source orientation (cheaper), then turn.
                    val (rw, rh) = if (t.swaps()) plan.outH to plan.outW else plan.outW to plan.outH
                    if (rw * rh.toLong() > 120_000_000L) throw ToolException("${f.name}: that size is too large.")
                    bmp = BitmapOps.resize(bmp, rw, rh)
                    bmp = BitmapOps.transform(bmp, t)
                    writeImage(f, action, bmp, outputFormat(info.value.takeIf { files.size == 1 } ?: com.namanbansal.forge.core.image.ImageIO.info(app, f.uri)), qualityS.value)
                } finally { src.recycle() }
            }
        }
        val first = outputSize()
        return outcome(r, if (action == "cropped") "Cropped" else "Resized", first?.let { "${it.first} × ${it.second} px" }.takeIf { files.size == 1 })
    }

    companion object {
        const val MAX_SIDE = 20_000
        /** Aspect choices for the crop box: 0 = free (ratio 0 means unlocked). */
        val RATIOS: List<Pair<String, Float>> = listOf("Free" to 0f, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "3:2" to 1.5f, "16:9" to 16f / 9, "9:16" to 9f / 16)
    }
}
