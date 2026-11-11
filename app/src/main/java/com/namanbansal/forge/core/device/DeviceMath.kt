package com.namanbansal.forge.core.device

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

const val GRAVITY = 9.80665f

/** Exponential low-pass over a 3-axis vector (gravity from a raw accelerometer, or smoothing TYPE_GRAVITY). */
class LowPass3(private val alpha: Float) {
    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set
    private var primed = false

    fun push(nx: Float, ny: Float, nz: Float) {
        if (!primed) { x = nx; y = ny; z = nz; primed = true; return }
        x += alpha * (nx - x); y += alpha * (ny - y); z += alpha * (nz - z)
    }
    fun reset() { primed = false }
}

/** Smooths an angle in degrees along the shortest way round, so 359 -> 1 does not swing through 180. */
class AngleSmoother(private val alpha: Float) {
    var value = 0f; private set
    private var primed = false

    fun push(deg: Float): Float {
        if (!primed) { value = norm360(deg); primed = true; return value }
        value = norm360(value + alpha * shortestDelta(value, deg))
        return value
    }
    fun reset() { primed = false }
}

fun norm360(deg: Float): Float { val r = deg % 360f; return if (r < 0) r + 360f else r }

/** Signed shortest rotation from [from] to [to], in (-180, 180]. */
fun shortestDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** Tilt of a phone lying roughly flat. [x] positive = right edge low, [y] positive = top edge high, [total] = angle from level. */
data class Tilt(val x: Float, val y: Float, val total: Float)

object LevelMath {
    /** From a gravity-sensor vector (reads +9.8 on Z when the phone lies face up). */
    fun surfaceTilt(gx: Float, gy: Float, gz: Float): Tilt {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 0.1f) return Tilt(0f, 0f, 0f)
        val tx = Math.toDegrees(atan2(-gx, sqrt(gy * gy + gz * gz)).toDouble()).toFloat()
        val ty = Math.toDegrees(atan2(gy, sqrt(gx * gx + gz * gz)).toDouble()).toFloat()
        val total = Math.toDegrees(kotlin.math.acos((abs(gz) / g).coerceIn(0f, 1f)).toDouble()).toFloat()
        return Tilt(tx, ty, if (gz < 0) 180f - total else total)
    }

    /**
     * Phone standing on an edge (screen vertical): deviation in degrees of the phone's own axes from the nearest
     * horizontal or vertical, in [-45, 45]. 0 = perfectly level (or plumb).
     */
    fun edgeAngle(gx: Float, gy: Float): Float {
        if (abs(gx) < 0.5f && abs(gy) < 0.5f) return 0f
        val deg = Math.toDegrees(atan2(gy, gx).toDouble()).toFloat()
        var r = deg % 90f
        if (r > 45f) r -= 90f
        if (r < -45f) r += 90f
        return r
    }

    /** Offset of a bubble from the centre, as a unit vector scaled so [maxDeg] reaches the rim. Bubble rises to the high side. */
    fun bubbleOffset(t: Tilt, maxDeg: Float = 10f): Pair<Float, Float> {
        // Right edge low means the bubble goes left; top edge high means it goes up the screen (negative y).
        var bx = -t.x / maxDeg
        var by = -t.y / maxDeg
        val len = sqrt(bx * bx + by * by)
        if (len > 1f) { bx /= len; by /= len }
        return bx to by
    }

    fun tenths(deg: Float): String {
        val r = round(abs(deg) * 10f) / 10f
        val sign = if (deg < 0 && r != 0f) "-" else ""
        return "$sign${"%.1f".format(java.util.Locale.US, r)}°"
    }
}

/** Fires once each time the value comes within [enter] of zero, and re-arms after it leaves [exit] (hysteresis). */
class ZeroCrossDetector(private val enter: Float = 0.5f, private val exit: Float = 1.2f) {
    private var inside = false
    fun update(deg: Float): Boolean {
        val a = abs(deg)
        if (!inside && a <= enter) { inside = true; return true }
        if (inside && a >= exit) inside = false
        return false
    }
}

object Inclinometer {
    /** Elevation of the phone's long axis above the horizon (lay the long edge on a slope). */
    fun edgeSlope(gy: Float, gx: Float, gz: Float): Float {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 0.1f) return 0f
        return Math.toDegrees(asin((gy / g).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    /** Elevation of the back camera's axis above the horizon (sight along the top edge / through the camera). */
    fun sight(gx: Float, gy: Float, gz: Float): Float {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 0.1f) return 0f
        return Math.toDegrees(asin((-gz / g).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    /** Slope as a percentage grade (100% = 45 degrees); null near vertical. */
    fun grade(deg: Float): Float? = if (abs(deg) >= 89.5f) null else Math.tan(Math.toRadians(deg.toDouble())).toFloat() * 100f
}

/** Heading maths for the compass. */
object CompassMath {
    /**
     * Heading in degrees clockwise from north (0..360) from a 3x3 rotation matrix (SensorManager layout, row-major).
     * Flat phones report where the top edge points; upright phones where the back camera points.
     */
    fun heading(r: FloatArray): Float {
        val flat = abs(r[8]) > 0.7071f
        val hx = if (flat) r[1] else -r[2]
        val hy = if (flat) r[4] else -r[5]
        return norm360(Math.toDegrees(atan2(hx, hy).toDouble()).toFloat())
    }

    fun trueHeading(magnetic: Float, declination: Float): Float = norm360(magnetic + declination)

    val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun cardinal(deg: Float): String = POINTS[(((norm360(deg) + 11.25f) / 22.5f).toInt()) % 16]
}

/** Accuracy bucket for the compass calibration prompt (SensorManager.SENSOR_STATUS_*: 0 unreliable .. 3 high). */
fun needsCalibration(accuracy: Int): Boolean = accuracy <= 1

/** Sound level maths: dBFS from 16-bit PCM and the SPL estimate. */
object SoundMath {
    const val DEFAULT_OFFSET_DB = 90f

    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return -120f
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / count)
        if (rms < 1.0) return -120f
        return (20.0 * log10(rms / 32768.0)).toFloat()
    }

    /** Estimated dB SPL: phone microphones are not calibrated, so this is only a ballpark (typically within +/-10 dB). */
    fun estimateSpl(dbfs: Float, offset: Float = DEFAULT_OFFSET_DB): Float = (dbfs + offset).coerceIn(0f, 140f)

    data class Reference(val db: Int, val label: String)
    val REFERENCES = listOf(
        Reference(10, "Rustling leaves"), Reference(30, "Whisper"), Reference(40, "Quiet library"),
        Reference(50, "Light rain"), Reference(60, "Conversation"), Reference(70, "Busy traffic / vacuum"),
        Reference(85, "Heavy city traffic"), Reference(100, "Motorbike / club"), Reference(110, "Rock concert"),
        Reference(130, "Jet take-off, pain threshold"),
    )
    fun describe(db: Float): String = REFERENCES.lastOrNull { it.db <= db }?.label ?: "Very quiet"
}

/** Running min / average / max. */
class RunningStats {
    var min = Float.NaN; private set
    var max = Float.NaN; private set
    private var sum = 0.0
    var count = 0; private set
    val avg: Float get() = if (count == 0) Float.NaN else (sum / count).toFloat()
    fun push(v: Float) {
        min = if (min.isNaN()) v else min(min, v)
        max = if (max.isNaN()) v else max(max, v)
        sum += v; count++
    }
    fun reset() { min = Float.NaN; max = Float.NaN; sum = 0.0; count = 0 }
}

object LightRef {
    data class Ref(val lux: Float, val label: String)
    val REFERENCES = listOf(
        Ref(0.1f, "Moonless night"), Ref(1f, "Candle at 1 m"), Ref(10f, "Dim hallway"), Ref(50f, "Living room"),
        Ref(300f, "Office desk (target 300 to 500)"), Ref(1000f, "Overcast day"), Ref(10000f, "Full daylight"), Ref(50000f, "Direct sunlight"),
    )
    fun describe(lux: Float): String = REFERENCES.lastOrNull { it.lux <= lux }?.label ?: "Near darkness"
    fun format(lux: Float): String = when {
        lux >= 100f -> "%.0f".format(java.util.Locale.US, lux)
        lux >= 10f -> "%.1f".format(java.util.Locale.US, lux)
        else -> "%.2f".format(java.util.Locale.US, lux)
    }
}

/** Seismograph helpers: acceleration magnitude minus gravity and a Modified Mercalli style label. */
object VibrationMath {
    /** |a| - g in m/s^2; the sign carries direction of the change. */
    fun dynamic(ax: Float, ay: Float, az: Float): Float = sqrt(ax * ax + ay * ay + az * az) - GRAVITY

    /** Roman numeral intensity from peak acceleration in g (rough USGS peak-ground-acceleration correlation). */
    fun mercalli(peakG: Float): String = when {
        peakG < 0.0017f -> "I"
        peakG < 0.014f -> "II-III"
        peakG < 0.039f -> "IV"
        peakG < 0.092f -> "V"
        peakG < 0.18f -> "VI"
        peakG < 0.34f -> "VII"
        peakG < 0.65f -> "VIII"
        peakG < 1.24f -> "IX"
        else -> "X+"
    }

    fun describe(numeral: String): String = when (numeral) {
        "I" -> "Not felt"
        "II-III" -> "Weak, felt by few"
        "IV" -> "Light, like a passing truck"
        "V" -> "Moderate, dishes rattle"
        "VI" -> "Strong, things move"
        "VII" -> "Very strong"
        "VIII" -> "Severe"
        else -> "Violent: this is a shake, not a tremor"
    }
}

/** Display geometry for the ruler. */
object RulerMath {
    const val CARD_MM = 85.6f
    const val CARD_HEIGHT_MM = 53.98f
    const val MM_PER_INCH = 25.4f

    fun pxPerMm(dpi: Float): Float = dpi / MM_PER_INCH

    /** New pixels-per-mm so a card drawn [cardPx] long matches the real 85.6 mm. */
    fun calibrate(cardPx: Float): Float = cardPx / CARD_MM

    enum class Kind { MM, HALF, CM }
    data class Tick(val mm: Float, val kind: Kind, val label: String?)

    /** Metric ticks every millimetre for [lengthMm]: every 10th is a labelled cm tick, every 5th a half. */
    fun metricTicks(lengthMm: Int): List<Tick> = (0..lengthMm).map { i ->
        when {
            i % 10 == 0 -> Tick(i.toFloat(), Kind.CM, (i / 10).toString())
            i % 5 == 0 -> Tick(i.toFloat(), Kind.HALF, null)
            else -> Tick(i.toFloat(), Kind.MM, null)
        }
    }

    /** Inch ticks every 1/16 in; whole inches labelled, halves and quarters get longer ticks. */
    fun inchTicks(lengthInches: Int): List<Tick> = (0..lengthInches * 16).map { i ->
        val mm = i * MM_PER_INCH / 16f
        when {
            i % 16 == 0 -> Tick(mm, Kind.CM, (i / 16).toString())
            i % 8 == 0 -> Tick(mm, Kind.HALF, null)
            else -> Tick(mm, Kind.MM, null)
        }
    }
}

/** Angle between two arms that meet at [cx],[cy] (screen coordinates, y down), in degrees 0..180. */
object AngleMath {
    fun between(cx: Float, cy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val a1 = atan2((ay - cy).toDouble(), (ax - cx).toDouble())
        val a2 = atan2((by - cy).toDouble(), (bx - cx).toDouble())
        var d = Math.toDegrees(abs(a1 - a2))
        if (d > 180.0) d = 360.0 - d
        return d.toFloat()
    }
}

data class Vec3(val x: Float, val y: Float, val z: Float)

/** Fixed-size ring of recent values for a live graph; [snapshot] returns oldest first. */
class Ring(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    var size = 0; private set
    fun push(v: Float) { data[head] = v; head = (head + 1) % capacity; if (size < capacity) size++ }
    fun clear() { head = 0; size = 0 }
    fun snapshot(): FloatArray = FloatArray(size) { i -> data[(head - size + i + capacity * 2) % capacity] }
}
