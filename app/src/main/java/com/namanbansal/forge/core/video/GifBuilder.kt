package com.namanbansal.forge.core.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.namanbansal.forge.core.ToolException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream

/** Where GIF frames come from (decoded upright, at the requested size). Behind an interface so tests feed synthetic frames. */
interface FrameSource : AutoCloseable {
    /** ARGB pixels of the frame at [timeUs] scaled to [w] x [h], or null when the decoder can't produce it. */
    fun frame(timeUs: Long, w: Int, h: Int): IntArray?
    override fun close() {}
}

/** MediaMetadataRetriever frames: exact (OPTION_CLOSEST), upright (rotation applied), streamed from the file. */
class RetrieverFrameSource(context: Context, uri: Uri) : FrameSource {
    private val r = MediaMetadataRetriever().also { it.setDataSource(context, uri) }
    private val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

    override fun frame(timeUs: Long, w: Int, h: Int): IntArray? {
        val raw = try { r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h) } catch (_: Throwable) { null } ?: return null
        val bmp = if (raw.width == w && raw.height == h) raw else Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    override fun close() { runCatching { r.release() } }
}

class GifResult(val frames: Int, val bytes: Long)

/** A quantised first-second sample for the live preview, plus its size so the whole GIF's size can be extrapolated. */
class GifPreview(val frames: List<IndexedFrame>, val delayCs: Int, val bytes: Long)

object GifBuilder {
    /** The most frames one GIF may hold (30 s at 20 fps). */
    const val MAX_FRAMES = GifMath.MAX_SECONDS * 20

    private class CountingStream : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }

    /** Writes the GIF for [startMs, startMs + rangeMs) to [out]; frames are decoded and quantised one by one. */
    suspend fun build(
        source: FrameSource, srcW: Int, srcH: Int, startMs: Long, rangeMs: Long, durationMs: Long, fps: Int, width: Int,
        out: OutputStream, dither: Boolean = true, onProgress: (Float) -> Unit = {},
    ): GifResult {
        if (rangeMs <= 0) throw ToolException("Pick a range of at least a fraction of a second.")
        val (w, h) = GifMath.outputSize(srcW, srcH, width)
        val times = GifMath.frameTimesUs(startMs, rangeMs, fps)
        val writer = GifWriter(out, w, h)
        val delay = GifMath.frameDelayCs(fps)
        var last: IndexedFrame? = null
        var missing = 0
        for ((i, t) in times.withIndex()) {
            currentCoroutineContext().ensureActive()
            val px = source.frame(t.coerceAtMost(maxOf(durationMs * 1000L - 1000L, 0L)), w, h)
            val frame = if (px != null) Quantizer.quantize(px, w, h, 256, dither) else { missing++; last }
            if (frame != null) { writer.addFrame(frame, delay); last = frame }
            onProgress((i + 1f) / times.size)
        }
        if (writer.frames == 0 || missing > times.size / 2) throw ToolException("Forge couldn't read frames from this video, so no GIF was made.")
        writer.finish()
        return GifResult(writer.frames, writer.bytesWritten)
    }

    /** Encodes the first [seconds] second(s) in memory-light fashion for the preview and the size estimate. */
    suspend fun preview(source: FrameSource, srcW: Int, srcH: Int, startMs: Long, rangeMs: Long, durationMs: Long, fps: Int, width: Int, seconds: Int = 1, dither: Boolean = true): GifPreview {
        val (w, h) = GifMath.outputSize(srcW, srcH, width)
        val sample = minOf(rangeMs, seconds * 1000L)
        val times = GifMath.frameTimesUs(startMs, sample, fps)
        val counter = CountingStream()
        val writer = GifWriter(counter, w, h)
        val delay = GifMath.frameDelayCs(fps)
        val frames = ArrayList<IndexedFrame>()
        for (t in times) {
            currentCoroutineContext().ensureActive()
            val px = source.frame(t.coerceAtMost(maxOf(durationMs * 1000L - 1000L, 0L)), w, h) ?: continue
            val f = Quantizer.quantize(px, w, h, 256, dither)
            writer.addFrame(f, delay); frames += f
        }
        if (frames.isEmpty()) throw ToolException("Forge couldn't read frames from this video.")
        writer.finish()
        return GifPreview(frames, delay, counter.count)
    }
}
