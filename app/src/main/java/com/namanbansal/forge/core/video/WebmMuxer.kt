package com.namanbansal.forge.core.video

import android.media.MediaCodec
import android.media.MediaMuxer
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Metadata
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * Transformer's built-in muxers only write MP4. This one hands the samples to the framework `MediaMuxer` in WebM mode
 * (VP8 / VP9 video, Opus / Vorbis audio), so "Convert to WebM" can reuse Transformer for decoding and encoding.
 */
@UnstableApi
class WebmMuxer private constructor(path: String) : Muxer {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
    private var started = false
    private var closed = false

    override fun addTrack(format: Format): Int {
        try { return muxer.addTrack(MediaFormatUtil.createMediaFormatFromFormat(format)) } catch (e: Exception) { throw MuxerException("Couldn't add a track to the WebM file", e) }
    }

    override fun writeSampleData(trackIndex: Int, data: ByteBuffer, bufferInfo: BufferInfo) {
        try {
            if (!started) { muxer.start(); started = true }
            val info = MediaCodec.BufferInfo()
            info.set(data.position(), bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
            muxer.writeSampleData(trackIndex, data, info)
        } catch (e: Exception) {
            throw MuxerException("Couldn't write to the WebM file", e)
        }
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) { /* WebM output carries no extra metadata here. */ }

    override fun close() {
        if (closed) return
        closed = true
        try { if (started) muxer.stop() } catch (e: Exception) { throw MuxerException("Couldn't finish the WebM file", e) } finally { runCatching { muxer.release() } }
    }

    class Factory : Muxer.Factory {
        override fun create(path: String): Muxer = try { WebmMuxer(path) } catch (e: Exception) { throw MuxerException("Couldn't create the WebM file", e) }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9)
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
            else -> ImmutableList.of()
        }
    }
}
