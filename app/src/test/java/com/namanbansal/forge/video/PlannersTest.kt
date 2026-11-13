package com.namanbansal.forge.video

import android.net.Uri
import com.namanbansal.forge.core.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlannersTest {
    private val uri = Uri.parse("content://x/1")
    private val caps = EncoderCapabilities { listOf(CodecEntry("a", listOf("video/avc", "video/hevc"), true)) }

    @Test fun compressPresetEstimateIsBelowSourceAndScalesDown() {
        val info = sampleInfo(durationMs = 60_000, size = 100_000_000)
        val p = CompressPlanner.plan(info, CompressMode.SMALL, hevc = false)
        assertEquals(852 to 480, p.width to p.height)
        assertTrue(p.estimateBytes < info.sizeBytes / 5)
        assertEquals(CompressMath.AUDIO_BPS, p.audioBps)
        val r = CompressPlanner.request(info, uri, p)
        assertEquals("video/avc", r.videoMime); assertEquals(852, r.width); assertEquals(480, r.height); assertFalse(r.toneMapToSdr)
    }

    @Test fun compressTargetModeHitsTargetAndWarnsWhenImpossible() {
        val info = sampleInfo(durationMs = 120_000, size = 200_000_000)
        val ok = CompressPlanner.plan(info, CompressMode.TARGET, false, targetBytes = 20L * 1024 * 1024)
        assertEquals(20.0 * 1024 * 1024, ok.estimateBytes.toDouble(), 20.0 * 1024 * 1024 * 0.02)
        val tiny = CompressPlanner.plan(info, CompressMode.TARGET, false, targetBytes = 100_000)
        assertNotNull(tiny.warning); assertEquals(CompressMath.MIN_VIDEO_BPS, tiny.videoBps)
    }

    @Test fun compressWarnsWhenNotSmaller() {
        val info = sampleInfo(durationMs = 10_000, size = 500_000, bps = 400_000)
        assertTrue(CompressPlanner.plan(info, CompressMode.HIGH, false).warning!!.contains("noticeably smaller"))
    }

    @Test fun hdrIsToneMappedForH264AndKeptForHevc() {
        val hdr = sampleInfo(hdr = true)
        assertTrue(CompressPlanner.plan(hdr, CompressMode.MEDIUM, hevc = false).toneMap)
        assertFalse(CompressPlanner.plan(hdr, CompressMode.MEDIUM, hevc = true).toneMap)
        assertTrue(ConvertPlanner.request(hdr, uri, VideoFormat.MP4_H264, caps).toneMapToSdr)
        assertFalse(ConvertPlanner.request(hdr, uri, VideoFormat.MP4_HEVC, caps).toneMapToSdr)
    }

    @Test fun noAudioMeansNoAudioBitrate() {
        val p = CompressPlanner.plan(sampleInfo(audio = null), CompressMode.MEDIUM, false)
        assertNull(p.audioBps); assertNull(CompressPlanner.request(sampleInfo(audio = null), uri, p).audioMime)
    }

    @Test fun convertRemuxesWhenCodecsAlreadyMatch() {
        assertTrue(ConvertPlanner.canRemux(sampleInfo(), VideoFormat.MP4_H264))
        assertFalse(ConvertPlanner.canRemux(sampleInfo(audio = "audio/mpeg"), VideoFormat.MP4_H264)) // MP3 -> AAC needs re-encoding
        assertFalse(ConvertPlanner.canRemux(sampleInfo(video = "video/hevc"), VideoFormat.MP4_H264))
        assertTrue(ConvertPlanner.canRemux(sampleInfo(video = "video/hevc"), VideoFormat.MP4_HEVC))
        assertTrue(ConvertPlanner.canRemux(sampleInfo(video = "video/avc", audio = null), VideoFormat.MP4_H264))
        assertFalse(ConvertPlanner.canRemux(sampleInfo(), VideoFormat.WEBM))
        assertTrue(ConvertPlanner.canRemux(sampleInfo(video = "video/x-vnd.on2.vp9", audio = "audio/opus"), VideoFormat.WEBM))
        assertNull(ConvertPlanner.request(sampleInfo(), uri, VideoFormat.MP4_H264, caps).videoBitrate) // remux: no bitrate forced
        assertNotNull(ConvertPlanner.request(sampleInfo(video = "video/hevc"), uri, VideoFormat.MP4_H264, caps).videoBitrate)
    }

    @Test fun reencodeBitrateIsClampedAndHevcIsLower() {
        val i = sampleInfo(bps = 8_000_000)
        assertTrue(ConvertPlanner.reencodeBps(i, VideoFormat.MP4_HEVC) < ConvertPlanner.reencodeBps(i, VideoFormat.MP4_H264))
        assertEquals(400_000, ConvertPlanner.reencodeBps(sampleInfo(bps = 1000), VideoFormat.MP4_H264))
    }

    @Test fun speedRequestDropsAudioAboveTwoTimes() {
        val i = sampleInfo()
        assertFalse(SpeedPlanner.request(i, uri, 2f).removeAudio)
        assertTrue(SpeedPlanner.request(i, uri, 3f).removeAudio)
        assertTrue(SpeedPlanner.request(sampleInfo(audio = null), uri, 1.5f).removeAudio) // nothing to keep
        assertTrue(SpeedPlanner.resultNote(i, 4f).contains("removed")); assertTrue(SpeedPlanner.resultNote(i, 1.5f).contains("pitch-corrected"))
    }

    @Test fun trimRequestCoversWholeFileAsNoClip() {
        val i = sampleInfo(durationMs = 10_000)
        assertNull(TrimPlanner.request(i, uri, listOf(TrimRange(0, 10_000))).segments)
        assertEquals(2, TrimPlanner.request(i, uri, listOf(TrimRange(0, 2_000), TrimRange(5_000, 10_000))).segments!!.size)
    }

    @Test fun audioRemuxOnlyForUntrimmedAac() {
        val i = sampleInfo(durationMs = 10_000)
        assertTrue(AudioPlanner.m4aIsLossless(i, null)); assertTrue(AudioPlanner.m4aIsLossless(i, TrimRange(0, 10_000)))
        assertFalse(AudioPlanner.m4aIsLossless(i, TrimRange(1_000, 5_000)))
        assertFalse(AudioPlanner.m4aIsLossless(sampleInfo(audio = "audio/opus"), null))
        val r = AudioPlanner.m4aRequest(uri, null); assertTrue(r.removeVideo); assertEquals("audio/mp4a-latm", r.audioMime)
    }

    @Test fun exportErrorsAreFriendly() {
        assertTrue(ExportErrors.message(ExportErrors.ENCODER_INIT_FAILED).contains("can't encode"))
        assertTrue(ExportErrors.message(ExportErrors.DECODING_FORMAT_UNSUPPORTED).contains("unsupported codec"))
        assertTrue(ExportErrors.message(ExportErrors.IO_FILE_NOT_FOUND).contains("Pick it again"))
        assertTrue(ExportErrors.message(99999).contains("99999"))
        assertTrue(ExportErrors.message(ExportErrors.ENCODING_FORMAT_UNSUPPORTED, hdr = true).contains("HEVC"))
    }

    @Test fun wavHeaderLayout() {
        val h = WavWriter.header(1000, 44100, 2)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4)); assertEquals("data", String(h, 36, 4))
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1036, bb.getInt(4)); assertEquals(44100, bb.getInt(24)); assertEquals(176400, bb.getInt(28)); assertEquals(1000, bb.getInt(40))
        assertEquals(2, bb.getShort(22).toInt()); assertEquals(16, bb.getShort(34).toInt())
    }

    @Test fun floatPcmToSixteenBit() {
        val src = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        src.asFloatBuffer().put(floatArrayOf(1f, -1f, 2f)); src.rewind()
        val dst = ByteArray(6)
        WavWriter.floatToPcm16(src, 3, dst, 0)
        val s = java.nio.ByteBuffer.wrap(dst).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(32767, s.get(0).toInt()); assertEquals(-32767, s.get(1).toInt()); assertEquals(32767, s.get(2).toInt()) // clipped
    }
}
