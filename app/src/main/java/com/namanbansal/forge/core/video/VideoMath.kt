package com.namanbansal.forge.core.video

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A time range in milliseconds, `start < end`. */
data class TrimRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/** Keep one range ("cut the ends") or remove one range ("cut out the middle"). */
enum class TrimMode { KEEP, REMOVE }

/** Pure range maths behind the trim timeline (handles, minimum clip, what gets exported). */
object TrimMath {
    const val MIN_CLIP_MS = 200L

    /** Clamps a requested range into `0..duration`, keeping at least [MIN_CLIP_MS] (or the whole clip if it is shorter). */
    fun clamp(startMs: Long, endMs: Long, durationMs: Long): TrimRange {
        val d = max(durationMs, 0L)
        val minLen = min(MIN_CLIP_MS, d)
        val s = startMs.coerceIn(0L, max(d - minLen, 0L))
        val e = endMs.coerceIn(s + minLen, d)
        return TrimRange(s, e)
    }

    fun moveStart(r: TrimRange, newStart: Long, durationMs: Long): TrimRange {
        val minLen = min(MIN_CLIP_MS, durationMs)
        return TrimRange(newStart.coerceIn(0L, max(r.endMs - minLen, 0L)), r.endMs)
    }

    fun moveEnd(r: TrimRange, newEnd: Long, durationMs: Long): TrimRange {
        val minLen = min(MIN_CLIP_MS, durationMs)
        return TrimRange(r.startMs, newEnd.coerceIn(min(r.startMs + minLen, durationMs), durationMs))
    }

    /** The pieces that end up in the file, in order. KEEP -> the range itself; REMOVE -> before and after it (empty bits dropped). */
    fun keptSegments(mode: TrimMode, r: TrimRange, durationMs: Long): List<TrimRange> = when (mode) {
        TrimMode.KEEP -> listOf(r)
        TrimMode.REMOVE -> listOfNotNull(
            TrimRange(0L, r.startMs).takeIf { it.lengthMs >= MIN_CLIP_MS },
            TrimRange(r.endMs, durationMs).takeIf { it.lengthMs >= MIN_CLIP_MS },
        )
    }

    fun keptLengthMs(segments: List<TrimRange>): Long = segments.sumOf { it.lengthMs }

    fun fractionToMs(f: Float, durationMs: Long): Long = (f.coerceIn(0f, 1f) * durationMs).roundToLong()

    fun msToFraction(ms: Long, durationMs: Long): Float = if (durationMs <= 0) 0f else (ms.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Snaps [ms] to the nearest frame boundary of a [fps] video (frame-accurate handles). */
    fun snapToFrame(ms: Long, fps: Float): Long {
        if (fps <= 0f) return ms
        val frame = 1000.0 / fps
        return ((ms / frame).roundToLong() * frame).roundToLong()
    }

    /** "1:05.3" style clock. */
    fun clock(ms: Long, tenths: Boolean = true): String {
        val total = max(ms, 0L)
        val h = total / 3_600_000
        val m = (total / 60_000) % 60
        val s = (total / 1000) % 60
        val t = (total % 1000) / 100
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        return if (tenths) "$base.$t" else base
    }
}

/** Compression: preset bitrates, estimated sizes and the target-size bitrate. */
object CompressMath {
    const val AUDIO_BPS = 128_000
    private const val CONTAINER_OVERHEAD = 1.02
    const val MIN_VIDEO_BPS = 80_000

    enum class Preset(val label: String, val shortSide: Int, val baseBps: Int) {
        SMALL("Small 480p", 480, 1_000_000),
        MEDIUM("Medium 720p", 720, 2_500_000),
        HIGH("High 1080p", 1080, 5_000_000),
    }

    /** Bytes of an MP4 with the given stream bitrates and duration (includes a little container overhead). */
    fun estimateBytes(videoBps: Long, audioBps: Long, durationMs: Long): Long =
        (((videoBps + audioBps) / 8.0) * (durationMs / 1000.0) * CONTAINER_OVERHEAD).roundToLong()

    /** Video bitrate that makes the whole file about [targetBytes]; never below [MIN_VIDEO_BPS]. */
    fun bitrateForTarget(targetBytes: Long, durationMs: Long, audioBps: Long): Int {
        if (durationMs <= 0) return MIN_VIDEO_BPS
        val totalBps = targetBytes * 8.0 / CONTAINER_OVERHEAD / (durationMs / 1000.0)
        return (totalBps - audioBps).roundToLong().coerceIn(MIN_VIDEO_BPS.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** Smallest file the target mode can promise at [MIN_VIDEO_BPS]; asking for less than this is impossible. */
    fun minimumBytes(durationMs: Long, audioBps: Long): Long = estimateBytes(MIN_VIDEO_BPS.toLong(), audioBps, durationMs)

    /** Preset bitrate for [fps] (30 fps is the reference) and codec (HEVC needs about 30% fewer bits), never above the source's own. */
    fun presetBitrate(p: Preset, fps: Float, hevc: Boolean, sourceBps: Long?): Int {
        val fpsFactor = (if (fps > 0f) fps / 30f else 1f).coerceIn(0.6f, 2f)
        var bps = p.baseBps * fpsFactor * (if (hevc) 0.7f else 1f)
        if (sourceBps != null && sourceBps > 0) bps = min(bps, sourceBps * 0.9f)
        return max(bps.roundToInt(), MIN_VIDEO_BPS)
    }

    /** [w] x [h] scaled so the short side is [shortSide] (even numbers, aspect kept, never upscaled). */
    fun scaledSize(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val short = min(w, h)
        if (w <= 0 || h <= 0 || shortSide <= 0 || shortSide >= short) return even(w) to even(h)
        val f = shortSide.toDouble() / short
        return even((w * f).roundToInt()) to even((h * f).roundToInt())
    }

    private fun even(v: Int): Int = max(2, v and 1.inv())

    /**
     * Picks the largest short side (of 1080/720/480/360/240) that still gets at least [minBitsPerPixel] per pixel per frame at [videoBps]:
     * squeezing a 1080p picture into 200 kbps looks like mud, so target-size mode shrinks the picture instead.
     */
    fun suggestShortSide(videoBps: Int, w: Int, h: Int, fps: Float, minBitsPerPixel: Double = 0.06): Int {
        val short = min(w, h)
        val long = max(w, h)
        val f = if (fps > 0f) fps else 30f
        for (side in listOf(1080, 720, 480, 360, 240)) {
            if (side > short) continue
            val px = side.toDouble() * (long.toDouble() * side / short)
            if (videoBps / (px * f) >= minBitsPerPixel) return side
        }
        return min(240, short)
    }
}

/** Speed change maths. */
object SpeedMath {
    const val MIN = 0.25f
    const val MAX = 4f
    /** Above this the audio is dropped: stretched speech past 2x is unintelligible and Sonic starts to artefact. */
    const val MAX_AUDIO_SPEED = 2f

    fun clamp(speed: Float): Float = speed.coerceIn(MIN, MAX)

    fun outputDurationMs(inputMs: Long, speed: Float): Long = (inputMs / clamp(speed).toDouble()).roundToLong()

    fun audioKept(speed: Float, hasAudio: Boolean): Boolean = hasAudio && speed <= MAX_AUDIO_SPEED + 1e-4f

    /** Rounds a slider value to a friendly step (0.05 below 1x, 0.1 up to 2x, 0.25 above). */
    fun snap(speed: Float): Float {
        val s = clamp(speed)
        val step = when { s < 1f -> 0.05f; s <= 2f -> 0.1f; else -> 0.25f }
        return clamp((s / step).roundToInt() * step)
    }

    fun label(speed: Float): String {
        val r = (speed * 100).roundToInt() / 100f
        return (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0')) + "x"
    }
}

/** GIF size / frame maths. */
object GifMath {
    const val MAX_SECONDS = 30
    val FPS_RANGE = 5..20
    val WIDTH_RANGE = 240..720

    fun frameCount(rangeMs: Long, fps: Int): Int = max(1, (rangeMs / 1000.0 * fps).roundToInt())

    fun frameDelayCs(fps: Int): Int = max(2, (100.0 / fps).roundToInt())

    /** Output size for a source of [srcW] x [srcH] (already rotated) at [width], height rounded, at least 2. */
    fun outputSize(srcW: Int, srcH: Int, width: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return width to width
        val w = min(width, srcW).coerceAtLeast(2)
        return w to max(2, (srcH.toDouble() * w / srcW).roundToInt())
    }

    /** Rough size before anything is encoded (about 0.35 bytes per pixel per frame for dithered photographic video). */
    fun roughBytes(frames: Int, w: Int, h: Int): Long = (frames.toDouble() * w * h * 0.35).roundToLong() + 800

    /** Scale the byte size of the encoded first second up to the whole clip. */
    fun extrapolate(sampleBytes: Long, sampleFrames: Int, totalFrames: Int): Long =
        if (sampleFrames <= 0) 0L else (sampleBytes.toDouble() / sampleFrames * totalFrames).roundToLong()

    /** Timestamps (microseconds) of each frame to grab. */
    fun frameTimesUs(startMs: Long, rangeMs: Long, fps: Int): LongArray {
        val n = frameCount(rangeMs, fps)
        return LongArray(n) { i -> (startMs * 1000L) + (i * 1_000_000L / fps) }
    }
}
