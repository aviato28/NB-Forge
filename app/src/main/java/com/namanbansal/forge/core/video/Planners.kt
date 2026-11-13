package com.namanbansal.forge.core.video

import kotlin.math.max
import kotlin.math.min

/** What a compress run will do, worked out from the file and the chosen options (shown as an estimate before running). */
data class CompressPlan(
    val width: Int,
    val height: Int,
    val videoBps: Int,
    val audioBps: Int?,
    val estimateBytes: Long,
    val hevc: Boolean,
    /** Shown under the estimate: "Not smaller than the original", "Target too small", HDR notes... */
    val warning: String?,
    val toneMap: Boolean,
)

enum class CompressMode { SMALL, MEDIUM, HIGH, CUSTOM, TARGET }

object CompressPlanner {
    fun plan(
        info: VideoInfo,
        mode: CompressMode,
        hevc: Boolean,
        customShortSide: Int = 0,
        customKbps: Int = 2000,
        targetBytes: Long = 0,
    ): CompressPlan {
        val audio = if (info.hasAudio) CompressMath.AUDIO_BPS else 0
        var warning: String? = null
        val fps = info.frameRate
        val sourceBps = info.videoBitrate ?: info.overallBps
        val side: Int
        val vbps: Int
        when (mode) {
            CompressMode.SMALL, CompressMode.MEDIUM, CompressMode.HIGH -> {
                val p = CompressMath.Preset.entries[mode.ordinal]
                side = p.shortSide
                vbps = CompressMath.presetBitrate(p, fps, hevc, sourceBps)
            }
            CompressMode.CUSTOM -> {
                side = customShortSide
                vbps = (customKbps * 1000).coerceAtLeast(CompressMath.MIN_VIDEO_BPS)
            }
            CompressMode.TARGET -> {
                val min = CompressMath.minimumBytes(info.durationMs, audio.toLong())
                if (targetBytes < min) warning = "That's smaller than this clip can reasonably get (about ${com.namanbansal.forge.core.formatBytes(min)} at the lowest quality)."
                vbps = CompressMath.bitrateForTarget(targetBytes, info.durationMs, audio.toLong())
                side = CompressMath.suggestShortSide(vbps, info.width, info.height, fps)
            }
        }
        val (w, h) = if (side <= 0) CompressMath.scaledSize(info.width, info.height, Int.MAX_VALUE) else CompressMath.scaledSize(info.width, info.height, side)
        val est = CompressMath.estimateBytes(vbps.toLong(), audio.toLong(), info.durationMs)
        if (warning == null && info.sizeBytes > 0 && est >= info.sizeBytes * 0.95) warning = "This wouldn't make the file noticeably smaller. Pick a lower preset."
        val toneMap = info.hdr && !hevc
        if (info.hdr && warning == null) warning = if (hevc) "HDR clip: HDR is kept (HEVC)." else "HDR clip: converted to standard colours for H.264, so it looks right on every screen."
        return CompressPlan(w, h, vbps, if (info.hasAudio) audio else null, est, hevc, warning, toneMap)
    }

    fun request(info: VideoInfo, input: android.net.Uri, plan: CompressPlan): ExportRequest = ExportRequest(
        input = input,
        videoMime = if (plan.hevc) EncoderCapabilities.MIME_HEVC else EncoderCapabilities.MIME_H264,
        audioMime = if (info.hasAudio) EncoderCapabilities.MIME_AAC else null,
        videoBitrate = plan.videoBps,
        audioBitrate = plan.audioBps,
        width = plan.width.takeIf { it != info.width || plan.height != info.height },
        height = plan.height.takeIf { it != info.height || plan.width != info.width },
        toneMapToSdr = plan.toneMap,
    )
}

object ConvertPlanner {
    /** True when the picture and sound already match [format], so Transformer can copy them without re-encoding. */
    fun canRemux(info: VideoInfo, format: VideoFormat): Boolean = when (format) {
        VideoFormat.MP4_H264 -> info.isH264 && (!info.hasAudio || info.isAac) && !info.hdr
        VideoFormat.MP4_HEVC -> info.isHevc && (!info.hasAudio || info.isAac)
        VideoFormat.WEBM -> (info.videoMime.equals(EncoderCapabilities.MIME_VP9, true) || info.videoMime.equals(EncoderCapabilities.MIME_VP8, true)) &&
            (!info.hasAudio || info.audioMime.equals(EncoderCapabilities.MIME_OPUS, true) || info.audioMime.equals("audio/vorbis", true))
    }

    /** Bitrate to re-encode at: about the source's own (so quality holds), clamped; HEVC needs less. */
    fun reencodeBps(info: VideoInfo, format: VideoFormat): Int {
        val src = info.videoBitrate ?: info.overallBps ?: 4_000_000L
        val f = if (format == VideoFormat.MP4_HEVC) 0.75 else if (format == VideoFormat.WEBM) 0.9 else 1.0
        return (src * f).toLong().coerceIn(400_000L, 40_000_000L).toInt()
    }

    fun request(info: VideoInfo, input: android.net.Uri, format: VideoFormat, caps: EncoderCapabilities): ExportRequest {
        val remux = canRemux(info, format)
        val vmime = format.videoMime ?: caps.webmVideoMime()
        return ExportRequest(
            input = input,
            videoMime = vmime,
            audioMime = if (!info.hasAudio) null else if (format == VideoFormat.WEBM) EncoderCapabilities.MIME_OPUS else EncoderCapabilities.MIME_AAC,
            videoBitrate = if (remux) null else reencodeBps(info, format),
            webm = format == VideoFormat.WEBM,
            toneMapToSdr = info.hdr && format == VideoFormat.MP4_H264,
        )
    }
}

object SpeedPlanner {
    fun request(info: VideoInfo, input: android.net.Uri, speed: Float): ExportRequest = ExportRequest(
        input = input, speed = speed, removeAudio = !SpeedMath.audioKept(speed, info.hasAudio),
        toneMapToSdr = false,
    )

    fun resultNote(info: VideoInfo, speed: Float): String {
        val outMs = SpeedMath.outputDurationMs(info.durationMs, speed)
        val audio = when {
            !info.hasAudio -> ""
            SpeedMath.audioKept(speed, true) -> " Sound is pitch-corrected."
            else -> " Sound was removed (above ${SpeedMath.label(SpeedMath.MAX_AUDIO_SPEED)} it isn't intelligible)."
        }
        return "${TrimMath.clock(info.durationMs, false)} → ${TrimMath.clock(outMs, false)}.$audio"
    }
}

object TrimPlanner {
    fun request(info: VideoInfo, input: android.net.Uri, segments: List<TrimRange>): ExportRequest {
        val whole = segments.size == 1 && segments[0].startMs <= 0 && segments[0].endMs >= info.durationMs
        return ExportRequest(input = input, segments = if (whole) null else segments)
    }
}

object AudioPlanner {
    fun m4aRequest(input: android.net.Uri, range: TrimRange?): ExportRequest =
        ExportRequest(input = input, segments = range?.let { listOf(it) }, removeVideo = true, audioMime = EncoderCapabilities.MIME_AAC)

    /** M4A copies the sound untouched when it is already AAC and no trim is asked for. */
    fun m4aIsLossless(info: VideoInfo, range: TrimRange?): Boolean = info.isAac && (range == null || (range.startMs <= 0 && range.endMs >= info.durationMs))

    fun wavBytes(durationMs: Long, sampleRate: Int, channels: Int): Long = WavWriter.HEADER_SIZE + durationMs * sampleRate / 1000 * channels * 2
}
