package com.namanbansal.forge.video

import com.namanbansal.forge.core.video.*
import org.junit.Assert.*
import org.junit.Test

/** The capability query runs against a fake codec list (no device needed). */
class EncoderCapsTest {
    private fun caps(vararg e: CodecEntry) = EncoderCapabilities { e.toList() }
    private val h264 = CodecEntry("c2.android.avc.encoder", listOf("video/avc"), true)
    private val aacEnc = CodecEntry("c2.android.aac.encoder", listOf("audio/mp4a-latm"), true)
    private val opusEnc = CodecEntry("c2.android.opus.encoder", listOf("audio/opus"), true)
    private val vp8Enc = CodecEntry("c2.android.vp8.encoder", listOf("video/x-vnd.on2.vp8"), true)
    private val vp9Dec = CodecEntry("c2.android.vp9.decoder", listOf("video/x-vnd.on2.vp9"), false)
    private val hevcDec = CodecEntry("c2.android.hevc.decoder", listOf("video/hevc"), false)

    @Test fun decodersDoNotCountAsEncoders() {
        val c = caps(h264, vp9Dec, hevcDec)
        assertTrue(c.h264); assertFalse(c.vp9); assertFalse(c.hevc)
    }

    @Test fun webmNeedsVideoAndOpusEncoders() {
        assertFalse(caps(h264, aacEnc).webm)
        assertFalse(caps(h264, vp8Enc).webm) // no Opus: sound couldn't be written
        assertTrue(caps(h264, vp8Enc, opusEnc).webm)
        assertEquals(EncoderCapabilities.MIME_VP8, caps(vp8Enc, opusEnc).webmVideoMime())
        assertEquals(EncoderCapabilities.MIME_VP9, caps(vp8Enc, CodecEntry("hw.vp9.enc", listOf("video/x-vnd.on2.vp9"), true), opusEnc).webmVideoMime())
        assertNull(caps(h264).webmVideoMime())
    }

    @Test fun formatListHidesMissingFormatsAndNotesWhy() {
        val none = caps(h264)
        assertEquals(listOf(VideoFormat.MP4_H264), none.videoFormats())
        assertTrue(none.missingNote()!!.contains("WebM")); assertTrue(none.missingNote()!!.contains("HEVC"))
        val all = caps(h264, CodecEntry("hevc.enc", listOf("video/hevc"), true), vp8Enc, opusEnc)
        assertEquals(listOf(VideoFormat.MP4_H264, VideoFormat.MP4_HEVC, VideoFormat.WEBM), all.videoFormats())
        assertNull(all.missingNote())
    }

    @Test fun mimeMatchIsCaseInsensitiveAndMultiMime() {
        assertTrue(caps(CodecEntry("x", listOf("audio/AMR", "VIDEO/AVC"), true)).h264)
    }

    @Test fun emptyOrBrokenListIsSafe() {
        val c = caps()
        assertFalse(c.h264); assertEquals(listOf(VideoFormat.MP4_H264), c.videoFormats()) // MP4 is always offered; the encoder error is reported at run time
    }
}
