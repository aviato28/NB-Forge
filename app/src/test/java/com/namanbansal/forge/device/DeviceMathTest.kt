package com.namanbansal.forge.device

import com.namanbansal.forge.core.device.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMathTest {
    private val g = GRAVITY

    @Test fun flatPhoneIsLevel() {
        val t = LevelMath.surfaceTilt(0f, 0f, g)
        assertEquals(0f, t.total, 0.01f); assertEquals(0f, t.x, 0.01f); assertEquals(0f, t.y, 0.01f)
    }

    @Test fun rightEdgeLowGivesPositiveXAndBubbleGoesLeft() {
        // Right edge low: the device X axis points slightly down, so the sensor reads negative X.
        val s = Math.sin(Math.toRadians(5.0)).toFloat() * g
        val c = Math.cos(Math.toRadians(5.0)).toFloat() * g
        val t = LevelMath.surfaceTilt(-s, 0f, c)
        assertEquals(5f, t.x, 0.05f); assertEquals(5f, t.total, 0.05f)
        val (bx, by) = LevelMath.bubbleOffset(t)
        assertTrue(bx < 0); assertEquals(0f, by, 0.01f)
    }

    @Test fun topEdgeHighMovesBubbleUp() {
        val t = LevelMath.surfaceTilt(0f, 0.5f, g)
        assertTrue(t.y > 0)
        assertTrue(LevelMath.bubbleOffset(t).second < 0)
    }

    @Test fun bubbleStaysInsideRim() {
        val (bx, by) = LevelMath.bubbleOffset(Tilt(80f, 80f, 90f))
        assertTrue(Math.hypot(bx.toDouble(), by.toDouble()) <= 1.0001)
    }

    @Test fun faceDownReportsOver90() {
        assertEquals(180f, LevelMath.surfaceTilt(0f, 0f, -g).total, 0.01f)
    }

    @Test fun edgeAngleNearestAxis() {
        assertEquals(0f, LevelMath.edgeAngle(g, 0f), 0.01f)       // landscape on its long edge, level
        assertEquals(0f, LevelMath.edgeAngle(0f, g), 0.01f)       // portrait, plumb
        val a = Math.toRadians(3.0)
        assertEquals(3f, LevelMath.edgeAngle((g * Math.cos(a)).toFloat(), (g * Math.sin(a)).toFloat()), 0.05f)
        assertEquals(-3f, LevelMath.edgeAngle((g * Math.cos(-a)).toFloat(), (g * Math.sin(-a)).toFloat()), 0.05f)
        assertEquals(0f, LevelMath.edgeAngle(0.1f, 0.1f), 0f)    // phone flat: no edge reading
    }

    @Test fun tenthsFormatting() {
        assertEquals("0.0°", LevelMath.tenths(0.04f))
        assertEquals("1.3°", LevelMath.tenths(1.26f))
        assertEquals("-2.5°", LevelMath.tenths(-2.5f))
        assertEquals("0.0°", LevelMath.tenths(-0.02f))
    }

    @Test fun zeroCrossFiresOnceWithHysteresis() {
        val d = ZeroCrossDetector(0.5f, 1.2f)
        assertFalse(d.update(3f))
        assertTrue(d.update(0.3f))
        assertFalse(d.update(0.1f)); assertFalse(d.update(0.8f))
        assertFalse(d.update(0.2f))   // never left the exit band
        assertFalse(d.update(2f))
        assertTrue(d.update(0.0f))
    }

    @Test fun lowPassConvergesAndFirstSampleIsExact() {
        val f = LowPass3(0.2f)
        f.push(1f, 2f, 3f); assertEquals(1f, f.x, 0f)
        repeat(60) { f.push(5f, 5f, 5f) }
        assertEquals(5f, f.x, 0.01f); assertEquals(5f, f.z, 0.01f)
    }

    @Test fun angleSmootherWrapsAround() {
        val s = AngleSmoother(0.5f)
        s.push(359f)
        val v = s.push(1f)
        assertEquals(0f, v, 0.01f)         // halfway between 359 and 1 is 0, not 180
        assertEquals(10f, shortestDelta(350f, 0f), 0.01f)
        assertEquals(-10f, shortestDelta(10f, 0f), 0.01f)
        assertEquals(350f, norm360(-10f), 0.01f)
    }

    @Test fun inclinometer() {
        assertEquals(0f, Inclinometer.edgeSlope(0f, 0f, g), 0.01f)
        assertEquals(30f, Inclinometer.edgeSlope(g * 0.5f, 0f, g * 0.866f), 0.1f)
        assertEquals(0f, Inclinometer.sight(0f, g, 0f), 0.01f)       // upright, camera at the horizon
        assertEquals(45f, Inclinometer.sight(0f, g * 0.7071f, -g * 0.7071f), 0.1f)
        assertEquals(100f, Inclinometer.grade(45f)!!, 0.5f)
        assertNull(Inclinometer.grade(90f))
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test fun compassHeadingFlat() {
        assertEquals(0f, CompassMath.heading(identity()), 0.01f)
        // Phone turned 90 degrees clockwise (top edge points east).
        val r = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(90f, CompassMath.heading(r), 0.01f)
    }

    @Test fun compassHeadingUpright() {
        // Upright phone, back camera pointing north: device -Z maps to world +Y, device Y to world +Z.
        val r = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0f, CompassMath.heading(r), 0.01f)
    }

    @Test fun trueNorthAndCardinals() {
        assertEquals(10f, CompassMath.trueHeading(5f, 5f), 0.01f)
        assertEquals(355f, CompassMath.trueHeading(5f, -10f), 0.01f)
        assertEquals("N", CompassMath.cardinal(359f)); assertEquals("NE", CompassMath.cardinal(45f)); assertEquals("W", CompassMath.cardinal(270f))
        assertTrue(needsCalibration(1)); assertFalse(needsCalibration(2))
    }

    @Test fun dbMaths() {
        val full = ShortArray(100) { if (it % 2 == 0) 32767 else -32767 }
        assertEquals(0f, SoundMath.rmsDbfs(full), 0.01f)
        val half = ShortArray(100) { 16384 }
        assertEquals(-6.02f, SoundMath.rmsDbfs(half), 0.05f)
        assertEquals(-120f, SoundMath.rmsDbfs(ShortArray(10)), 0f)
        assertEquals(-120f, SoundMath.rmsDbfs(ShortArray(0)), 0f)
        assertEquals(84f, SoundMath.estimateSpl(-6f), 0f)
        assertEquals(140f, SoundMath.estimateSpl(100f), 0f)
        assertEquals(0f, SoundMath.estimateSpl(-120f), 0f)
        assertEquals("Conversation", SoundMath.describe(62f))
    }

    @Test fun runningStats() {
        val s = RunningStats()
        assertTrue(s.avg.isNaN())
        listOf(40f, 60f, 50f).forEach(s::push)
        assertEquals(40f, s.min, 0f); assertEquals(60f, s.max, 0f); assertEquals(50f, s.avg, 0.001f)
        s.reset(); assertEquals(0, s.count)
    }

    @Test fun mercalli() {
        assertEquals("I", VibrationMath.mercalli(0.001f))
        assertEquals("IV", VibrationMath.mercalli(0.02f))
        assertEquals("VII", VibrationMath.mercalli(0.2f))
        assertEquals("X+", VibrationMath.mercalli(2f))
        assertEquals(0f, VibrationMath.dynamic(0f, 0f, GRAVITY), 0.001f)
    }

    @Test fun lightFormat() {
        assertEquals("350", LightRef.format(350.4f)); assertEquals("12.5", LightRef.format(12.5f)); assertEquals("0.50", LightRef.format(0.5f))
        assertEquals("Full daylight", LightRef.describe(20000f))
    }

    @Test fun rulerTicksAndCalibration() {
        val m = RulerMath.metricTicks(30)
        assertEquals(31, m.size)
        assertEquals(RulerMath.Kind.CM, m[10].kind); assertEquals("1", m[10].label)
        assertEquals(RulerMath.Kind.HALF, m[5].kind); assertEquals(RulerMath.Kind.MM, m[3].kind)
        val i = RulerMath.inchTicks(2)
        assertEquals(33, i.size); assertEquals(25.4f, i[16].mm, 0.001f); assertEquals("2", i[32].label)
        assertEquals(10f, RulerMath.calibrate(856f), 0.001f)
        assertEquals(160f / 25.4f, RulerMath.pxPerMm(160f), 0.001f)
    }

    @Test fun protractorAngle() {
        assertEquals(90f, AngleMath.between(0f, 0f, 10f, 0f, 0f, 10f), 0.01f)
        assertEquals(180f, AngleMath.between(0f, 0f, 10f, 0f, -10f, 0f), 0.01f)
        assertEquals(30f, AngleMath.between(5f, 5f, 15f, 5f, 5f + 10f * Math.cos(Math.toRadians(30.0)).toFloat(), 5f + 10f * Math.sin(Math.toRadians(30.0)).toFloat()), 0.01f)
        assertEquals(90f, AngleMath.between(0f, 0f, 0f, -10f, -10f, 0f), 0.01f)  // across the +/-180 seam
    }
}
