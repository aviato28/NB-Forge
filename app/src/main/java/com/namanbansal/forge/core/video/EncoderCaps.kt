package com.namanbansal.forge.core.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** One codec as the system reports it (a plain value so the query logic can be tested without a device). */
data class CodecEntry(val name: String, val mimes: List<String>, val isEncoder: Boolean, val hardware: Boolean = true)

/** The system's codec list, behind an interface so tests can supply a fake `MediaCodecList`. */
fun interface CodecList {
    fun entries(): List<CodecEntry>
}

/** Reads the real `MediaCodecList`; any failure reads as "no codecs" so callers fall back to the safe option. */
object AndroidCodecList : CodecList {
    override fun entries(): List<CodecEntry> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map { info ->
            CodecEntry(info.name, info.supportedTypes.toList(), info.isEncoder, hardware = info.isHardwareAcceleratedCompat())
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean = try { isHardwareAccelerated } catch (_: Throwable) { true }
}

/** Which formats this device can *write*. WebM is only offered when a VP8/VP9 encoder (and Opus for the sound) exists. */
class EncoderCapabilities(private val list: CodecList = AndroidCodecList) {
    private val entries: List<CodecEntry> by lazy { list.entries() }

    fun hasEncoder(mime: String): Boolean = entries.any { it.isEncoder && it.mimes.any { m -> m.equals(mime, ignoreCase = true) } }

    val h264: Boolean get() = hasEncoder(MIME_H264)
    val hevc: Boolean get() = hasEncoder(MIME_HEVC)
    val vp9: Boolean get() = hasEncoder(MIME_VP9)
    val vp8: Boolean get() = hasEncoder(MIME_VP8)
    val aac: Boolean get() = hasEncoder(MIME_AAC)
    val opus: Boolean get() = hasEncoder(MIME_OPUS)

    /** WebM needs a VP8 or VP9 encoder for the picture and an Opus encoder for the sound. */
    val webm: Boolean get() = (vp9 || vp8) && opus

    /** VP9 when available (better quality per bit), else VP8; null if neither exists. */
    fun webmVideoMime(): String? = when {
        vp9 -> MIME_VP9
        vp8 -> MIME_VP8
        else -> null
    }

    fun videoFormats(): List<VideoFormat> = buildList {
        add(VideoFormat.MP4_H264)
        if (hevc) add(VideoFormat.MP4_HEVC)
        if (webm) add(VideoFormat.WEBM)
    }

    /** Why a format is missing, for the note under the chips; null when everything is on offer. */
    fun missingNote(): String? {
        val gaps = buildList {
            if (!hevc) add("HEVC (H.265)")
            if (!webm) add("WebM")
        }
        return if (gaps.isEmpty()) null else "${gaps.joinToString(" and ")} can't be written on this device (no encoder), so ${if (gaps.size > 1) "they aren't" else "it isn't"} offered."
    }

    companion object {
        const val MIME_H264 = "video/avc"
        const val MIME_HEVC = "video/hevc"
        const val MIME_VP8 = "video/x-vnd.on2.vp8"
        const val MIME_VP9 = "video/x-vnd.on2.vp9"
        const val MIME_AAC = "audio/mp4a-latm"
        const val MIME_OPUS = "audio/opus"
    }
}

/** What the Convert and Compress tools can write. */
enum class VideoFormat(val label: String, val ext: String, val mime: String, val videoMime: String?) {
    MP4_H264("MP4 · H.264", "mp4", "video/mp4", EncoderCapabilities.MIME_H264),
    MP4_HEVC("MP4 · HEVC", "mp4", "video/mp4", EncoderCapabilities.MIME_HEVC),
    WEBM("WebM", "webm", "video/webm", null),
}
