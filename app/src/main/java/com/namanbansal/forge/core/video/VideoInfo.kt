package com.namanbansal.forge.core.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.namanbansal.forge.core.ToolException

/** What the tools need to know about a picked video / audio file. Width and height are as displayed (rotation already applied). */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Float,
    val videoMime: String?,
    val audioMime: String?,
    val videoBitrate: Long?,
    val sizeBytes: Long,
    val hdr: Boolean = false,
    val containerHint: String? = null,
) {
    val hasVideo: Boolean get() = videoMime != null
    val hasAudio: Boolean get() = audioMime != null
    val isPortrait: Boolean get() = height > width
    val isAac: Boolean get() = audioMime.equals(EncoderCapabilities.MIME_AAC, true)
    val isH264: Boolean get() = videoMime.equals(EncoderCapabilities.MIME_H264, true)
    val isHevc: Boolean get() = videoMime.equals(EncoderCapabilities.MIME_HEVC, true)

    /** Overall bits per second of the whole file, or null for unknown length. */
    val overallBps: Long? get() = if (durationMs > 0 && sizeBytes > 0) sizeBytes * 8000L / durationMs else null
}

/** Reads [VideoInfo]; an interface so view-model tests can fake it. */
interface MediaProbe {
    fun probe(uri: Uri, sizeBytes: Long): VideoInfo
}

/** MediaExtractor-based probe: streams only the headers, never loads the file. */
class ExtractorProbe(private val context: Context) : MediaProbe {
    override fun probe(uri: Uri, sizeBytes: Long): VideoInfo {
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(context, uri, null) } catch (e: Exception) { throw ToolException("Forge couldn't open this file. It may be damaged or not a video.") }
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video == null && m.startsWith("video/")) video = f
                if (audio == null && m.startsWith("audio/")) audio = f
            }
            if (video == null && audio == null) throw ToolException("No video or audio was found in this file.")
            return buildInfo(video, audio, sizeBytes)
        } finally {
            runCatching { ex.release() }
        }
    }

    companion object {
        fun buildInfo(video: MediaFormat?, audio: MediaFormat?, sizeBytes: Long): VideoInfo {
            fun int(f: MediaFormat?, k: String, d: Int) = if (f != null && f.containsKey(k)) runCatching { f.getInteger(k) }.getOrDefault(d) else d
            fun long(f: MediaFormat?, k: String) = if (f != null && f.containsKey(k)) runCatching { f.getLong(k) }.getOrNull() else null
            val rot = int(video, MediaFormat.KEY_ROTATION, 0).let { ((it % 360) + 360) % 360 }
            val w = int(video, MediaFormat.KEY_WIDTH, 0)
            val h = int(video, MediaFormat.KEY_HEIGHT, 0)
            val swap = rot == 90 || rot == 270
            val durUs = maxOf(long(video, MediaFormat.KEY_DURATION) ?: 0L, long(audio, MediaFormat.KEY_DURATION) ?: 0L)
            val transfer = int(video, MediaFormat.KEY_COLOR_TRANSFER, -1)
            val fps = if (video != null && video.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { video.getFloat(MediaFormat.KEY_FRAME_RATE) }.recoverCatching { video.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f) else 0f
            val bps = int(video, MediaFormat.KEY_BIT_RATE, 0).toLong().takeIf { it > 0 }
            return VideoInfo(
                durationMs = durUs / 1000L,
                width = if (swap) h else w,
                height = if (swap) w else h,
                rotation = rot,
                frameRate = fps,
                videoMime = video?.getString(MediaFormat.KEY_MIME),
                audioMime = audio?.getString(MediaFormat.KEY_MIME),
                videoBitrate = bps,
                sizeBytes = sizeBytes,
                // 6 = ST2084 (PQ), 7 = HLG
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
            )
        }
    }
}
