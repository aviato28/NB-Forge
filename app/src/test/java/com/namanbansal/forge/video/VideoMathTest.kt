package com.namanbansal.forge.video

import com.namanbansal.forge.core.video.*
import org.junit.Assert.*
import org.junit.Test

class VideoMathTest {
    // ---- trim range maths ----
    @Test fun clampKeepsRangeInsideAndAtLeastMinimum() {
        assertEquals(TrimRange(0, 10_000), TrimMath.clamp(-5, 99_999, 10_000))
        val r = TrimMath.clamp(9_990, 9_995, 10_000)
        assertTrue(r.lengthMs >= TrimMath.MIN_CLIP_MS); assertTrue(r.endMs <= 10_000)
        assertEquals(TrimRange(0, 100), TrimMath.clamp(50, 20, 100)) // shorter than the minimum: whole clip
    }

    @Test fun handlesCannotCross() {
        val r = TrimRange(2_000, 6_000)
        assertEquals(6_000 - TrimMath.MIN_CLIP_MS, TrimMath.moveStart(r, 9_000, 10_000).startMs)
        assertEquals(2_000 + TrimMath.MIN_CLIP_MS, TrimMath.moveEnd(r, 0, 10_000).endMs)
        assertEquals(10_000, TrimMath.moveEnd(r, 50_000, 10_000).endMs)
        assertEquals(0, TrimMath.moveStart(r, -3, 10_000).startMs)
    }

    @Test fun keepAndRemoveSegments() {
        val r = TrimRange(2_000, 6_000)
        assertEquals(listOf(r), TrimMath.keptSegments(TrimMode.KEEP, r, 10_000))
        assertEquals(listOf(TrimRange(0, 2_000), TrimRange(6_000, 10_000)), TrimMath.keptSegments(TrimMode.REMOVE, r, 10_000))
        assertEquals(listOf(TrimRange(6_000, 10_000)), TrimMath.keptSegments(TrimMode.REMOVE, TrimRange(0, 6_000), 10_000))
        assertTrue(TrimMath.keptSegments(TrimMode.REMOVE, TrimRange(0, 10_000), 10_000).isEmpty())
        assertEquals(6_000, TrimMath.keptLengthMs(TrimMath.keptSegments(TrimMode.REMOVE, r, 10_000)))
    }

    @Test fun frameSnapAndClock() {
        assertEquals(1000, TrimMath.snapToFrame(1010, 30f)) // 33.3 ms frames: 30 frames = 1000 ms
        assertEquals(33, TrimMath.snapToFrame(40, 30f))
        assertEquals("1:05.3", TrimMath.clock(65_340))
        assertEquals("1:02:03", TrimMath.clock(3_723_000, tenths = false))
        assertEquals(0.5f, TrimMath.msToFraction(5_000, 10_000), 1e-6f)
        assertEquals(2_500L, TrimMath.fractionToMs(0.25f, 10_000))
    }

    // ---- compress / target size ----
    @Test fun targetSizeBitrateRoundTripsToTargetSize() {
        val dur = 60_000L; val target = 8L * 1024 * 1024
        val v = CompressMath.bitrateForTarget(target, dur, CompressMath.AUDIO_BPS.toLong())
        val est = CompressMath.estimateBytes(v.toLong(), CompressMath.AUDIO_BPS.toLong(), dur)
        assertEquals(target.toDouble(), est.toDouble(), target * 0.01)
    }

    @Test fun impossibleTargetsAreClampedToMinimumBitrate() {
        assertEquals(CompressMath.MIN_VIDEO_BPS, CompressMath.bitrateForTarget(1_000, 600_000, CompressMath.AUDIO_BPS.toLong()))
        assertEquals(CompressMath.MIN_VIDEO_BPS, CompressMath.bitrateForTarget(1_000, 0, 0))
    }

    @Test fun scaledSizeKeepsAspectEvenAndNeverUpscales() {
        assertEquals(852 to 480, CompressMath.scaledSize(1920, 1080, 480).let { it.first to it.second })
        assertEquals(480 to 852, CompressMath.scaledSize(1080, 1920, 480)) // portrait: the short side is the width
        assertEquals(640 to 360, CompressMath.scaledSize(640, 360, 1080))
        val (w, h) = CompressMath.scaledSize(1001, 563, 360)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test fun presetBitrateScalesWithFpsCodecAndNeverExceedsSource() {
        val p = CompressMath.Preset.MEDIUM
        assertEquals(2_500_000, CompressMath.presetBitrate(p, 30f, false, null))
        assertTrue(CompressMath.presetBitrate(p, 60f, false, null) > 2_500_000)
        assertTrue(CompressMath.presetBitrate(p, 30f, true, null) < 2_500_000)
        assertEquals(900_000, CompressMath.presetBitrate(p, 30f, false, 1_000_000))
    }

    @Test fun lowBitrateShrinksPictureInsteadOfMud() {
        assertEquals(1080, CompressMath.suggestShortSide(8_000_000, 1920, 1080, 30f))
        assertTrue(CompressMath.suggestShortSide(400_000, 1920, 1080, 30f) <= 360)
        assertEquals(1080, CompressMath.suggestShortSide(8_000_000, 1080, 1920, 30f)) // portrait uses the short side
    }

    // ---- speed / duration ----
    @Test fun speedDurationMaths() {
        assertEquals(10_000, SpeedMath.outputDurationMs(20_000, 2f))
        assertEquals(80_000, SpeedMath.outputDurationMs(20_000, 0.25f))
        assertEquals(5_000, SpeedMath.outputDurationMs(20_000, 4f))
        assertEquals(SpeedMath.outputDurationMs(20_000, 4f), SpeedMath.outputDurationMs(20_000, 9f)) // clamped
    }

    @Test fun audioIsKeptOnlyUpToTwoTimes() {
        assertTrue(SpeedMath.audioKept(0.25f, true)); assertTrue(SpeedMath.audioKept(2f, true))
        assertFalse(SpeedMath.audioKept(2.5f, true)); assertFalse(SpeedMath.audioKept(1f, false))
    }

    @Test fun speedSnapAndLabel() {
        assertEquals(0.75f, SpeedMath.snap(0.76f), 1e-4f)
        assertEquals(1.5f, SpeedMath.snap(1.52f), 1e-4f)
        assertEquals(2.75f, SpeedMath.snap(2.8f), 1e-4f)
        assertEquals("2x", SpeedMath.label(2f)); assertEquals("0.25x", SpeedMath.label(0.25f)); assertEquals("1.5x", SpeedMath.label(1.5f))
    }

    // ---- gif maths ----
    @Test fun gifFrameMaths() {
        assertEquals(60, GifMath.frameCount(5_000, 12))
        assertEquals(8, GifMath.frameDelayCs(12)); assertEquals(5, GifMath.frameDelayCs(20)); assertEquals(20, GifMath.frameDelayCs(5))
        assertEquals(480 to 270, GifMath.outputSize(1920, 1080, 480))
        assertEquals(240 to 427, GifMath.outputSize(1080, 1920, 240))
        assertEquals(320 to 180, GifMath.outputSize(320, 180, 720)) // never upscaled
        val t = GifMath.frameTimesUs(1_000, 1_000, 10)
        assertEquals(10, t.size); assertEquals(1_000_000L, t[0]); assertEquals(1_900_000L, t[9])
        assertEquals(3_000L, GifMath.extrapolate(500, 10, 60) / 1) // 50 bytes/frame * 60
    }
}
