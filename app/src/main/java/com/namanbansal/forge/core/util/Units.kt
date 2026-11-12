package com.namanbansal.forge.core.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** One unit: [toBase] / [fromBase] convert to and from the category's base unit (so non-linear ones like temperature fit). */
class UnitDef(val id: String, val name: String, val symbol: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double)

class UnitCategory(val id: String, val name: String, val units: List<UnitDef>, val defaultFrom: String, val defaultTo: String) {
    fun unit(id: String): UnitDef = units.firstOrNull { it.id == id } ?: units.first()
    fun convert(value: Double, from: UnitDef, to: UnitDef): Double = to.fromBase(from.toBase(value))
}

private fun lin(id: String, name: String, symbol: String, factor: Double) = UnitDef(id, name, symbol, { it * factor }, { it / factor })
private fun inv(id: String, name: String, symbol: String, k: Double) = UnitDef(id, name, symbol, { if (it == 0.0) Double.NaN else k / it }, { if (it == 0.0) Double.NaN else k / it })

object Units {
    // Each factor is "how many base units in one of this unit" (m, kg, L, m2, m/s, s, byte, Pa, J).
    val length = UnitCategory("length", "Length", listOf(
        lin("um", "Micrometre", "µm", 1e-6), lin("mm", "Millimetre", "mm", 1e-3), lin("cm", "Centimetre", "cm", 1e-2), lin("m", "Metre", "m", 1.0), lin("km", "Kilometre", "km", 1e3),
        lin("in", "Inch", "in", 0.0254), lin("ft", "Foot", "ft", 0.3048), lin("yd", "Yard", "yd", 0.9144), lin("mi", "Mile", "mi", 1609.344), lin("nmi", "Nautical mile", "nmi", 1852.0),
    ), "km", "mi")

    val weight = UnitCategory("weight", "Weight", listOf(
        lin("mg", "Milligram", "mg", 1e-6), lin("g", "Gram", "g", 1e-3), lin("kg", "Kilogram", "kg", 1.0), lin("t", "Tonne", "t", 1000.0),
        lin("oz", "Ounce", "oz", 0.028349523125), lin("lb", "Pound", "lb", 0.45359237), lin("st", "Stone", "st", 6.35029318),
    ), "kg", "lb")

    val temperature = UnitCategory("temp", "Temperature", listOf(
        UnitDef("c", "Celsius", "°C", { it }, { it }),
        UnitDef("f", "Fahrenheit", "°F", { (it - 32.0) * 5.0 / 9.0 }, { it * 9.0 / 5.0 + 32.0 }),
        UnitDef("k", "Kelvin", "K", { it - 273.15 }, { it + 273.15 }),
        UnitDef("r", "Rankine", "°R", { (it - 491.67) * 5.0 / 9.0 }, { (it + 273.15) * 9.0 / 5.0 }),
    ), "c", "f")

    val volume = UnitCategory("volume", "Volume", listOf(
        lin("ml", "Millilitre", "mL", 1e-3), lin("l", "Litre", "L", 1.0), lin("m3", "Cubic metre", "m³", 1000.0),
        lin("tsp", "Teaspoon (US)", "tsp", 0.00492892159375), lin("tbsp", "Tablespoon (US)", "tbsp", 0.01478676478125),
        lin("floz", "Fluid ounce (US)", "fl oz", 0.0295735295625), lin("cup", "Cup (US)", "cup", 0.2365882365),
        lin("pt", "Pint (US)", "pt", 0.473176473), lin("qt", "Quart (US)", "qt", 0.946352946), lin("gal", "Gallon (US)", "gal", 3.785411784),
        lin("galuk", "Gallon (UK)", "gal UK", 4.54609),
    ), "l", "gal")

    val area = UnitCategory("area", "Area", listOf(
        lin("cm2", "Square centimetre", "cm²", 1e-4), lin("m2", "Square metre", "m²", 1.0), lin("ha", "Hectare", "ha", 1e4), lin("km2", "Square kilometre", "km²", 1e6),
        lin("in2", "Square inch", "in²", 0.00064516), lin("ft2", "Square foot", "ft²", 0.09290304), lin("yd2", "Square yard", "yd²", 0.83612736),
        lin("ac", "Acre", "ac", 4046.8564224), lin("mi2", "Square mile", "mi²", 2589988.110336),
    ), "m2", "ft2")

    val speed = UnitCategory("speed", "Speed", listOf(
        lin("ms", "Metres per second", "m/s", 1.0), lin("kmh", "Kilometres per hour", "km/h", 1.0 / 3.6), lin("mph", "Miles per hour", "mph", 0.44704),
        lin("kn", "Knot", "kn", 1852.0 / 3600.0), lin("fts", "Feet per second", "ft/s", 0.3048),
    ), "kmh", "mph")

    val time = UnitCategory("time", "Time", listOf(
        lin("us", "Microsecond", "µs", 1e-6), lin("msec", "Millisecond", "ms", 1e-3), lin("s", "Second", "s", 1.0), lin("min", "Minute", "min", 60.0),
        lin("h", "Hour", "h", 3600.0), lin("d", "Day", "d", 86400.0), lin("wk", "Week", "wk", 604800.0),
        lin("mo", "Month (average)", "mo", 2629800.0), lin("yr", "Year (365.25 d)", "yr", 31557600.0),
    ), "h", "min")

    val data = UnitCategory("data", "Data", listOf(
        lin("bit", "Bit", "bit", 0.125), lin("B", "Byte", "B", 1.0),
        lin("KB", "Kilobyte", "KB", 1e3), lin("MB", "Megabyte", "MB", 1e6), lin("GB", "Gigabyte", "GB", 1e9), lin("TB", "Terabyte", "TB", 1e12),
        lin("KiB", "Kibibyte", "KiB", 1024.0), lin("MiB", "Mebibyte", "MiB", 1048576.0), lin("GiB", "Gibibyte", "GiB", 1073741824.0), lin("TiB", "Tebibyte", "TiB", 1099511627776.0),
    ), "GB", "MB")

    val pressure = UnitCategory("pressure", "Pressure", listOf(
        lin("pa", "Pascal", "Pa", 1.0), lin("hpa", "Hectopascal / millibar", "hPa", 100.0), lin("kpa", "Kilopascal", "kPa", 1000.0), lin("bar", "Bar", "bar", 1e5),
        lin("atm", "Atmosphere", "atm", 101325.0), lin("psi", "Pound per sq inch", "psi", 6894.757293168), lin("mmhg", "Millimetre of mercury", "mmHg", 133.322387415),
        lin("inhg", "Inch of mercury", "inHg", 3386.389),
    ), "bar", "psi")

    val energy = UnitCategory("energy", "Energy", listOf(
        lin("j", "Joule", "J", 1.0), lin("kj", "Kilojoule", "kJ", 1000.0), lin("cal", "Calorie", "cal", 4.184), lin("kcal", "Kilocalorie (food Calorie)", "kcal", 4184.0),
        lin("wh", "Watt-hour", "Wh", 3600.0), lin("kwh", "Kilowatt-hour", "kWh", 3.6e6), lin("btu", "British thermal unit", "BTU", 1055.05585262), lin("ftlb", "Foot-pound", "ft·lbf", 1.3558179483314004),
    ), "kcal", "kj")

    /** Fuel economy is reciprocal, so the base is litres per 100 km. */
    val fuel = UnitCategory("fuel", "Fuel economy", listOf(
        UnitDef("l100", "Litres per 100 km", "L/100km", { it }, { it }),
        inv("kml", "Kilometres per litre", "km/L", 100.0),
        inv("mpgus", "Miles per gallon (US)", "mpg US", 235.214583333),
        inv("mpguk", "Miles per gallon (UK)", "mpg UK", 282.480936331),
    ), "mpgus", "l100")

    val categories: List<UnitCategory> = listOf(length, weight, temperature, volume, area, speed, time, data, pressure, energy, fuel)

    fun category(id: String): UnitCategory = categories.firstOrNull { it.id == id } ?: length
}

/** Number display and input helpers shared by the converters and the calculator. */
object NumberText {
    /** Rounds to [sig] significant digits and drops trailing zeros; very large / small values use scientific notation. */
    fun format(v: Double, sig: Int = 10, group: Boolean = true): String {
        if (v.isNaN()) return "—"
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        if (v == 0.0) return "0"
        val a = abs(v)
        val bd = BigDecimal(v).round(MathContext(sig, RoundingMode.HALF_EVEN))
        if (a >= 1e15 || a < 1e-6) {
            val s = bd.stripTrailingZeros()
            val unscaled = s.unscaledValue().abs().toString()
            val exp = unscaled.length - 1 - s.scale()
            val mant = if (unscaled.length == 1) unscaled else unscaled[0] + "." + unscaled.substring(1)
            return (if (v < 0) "-" else "") + mant + "e" + (if (exp >= 0) "+" else "-") + abs(exp)
        }
        var plain = bd.stripTrailingZeros().toPlainString()
        if (group) {
            val neg = plain.startsWith("-")
            val body = plain.removePrefix("-")
            val ip = body.substringBefore('.')
            val fp = if (body.contains('.')) "." + body.substringAfter('.') else ""
            plain = (if (neg) "-" else "") + ip.reversed().chunked(3).joinToString(",").reversed() + fp
        }
        return plain
    }

    /** Parses what a person types: accepts a decimal comma when there is no point, ignores spaces and grouping commas. */
    fun parse(text: String): Double? {
        var t = text.trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty() || t == "-" || t == ".") return null
        t = if (t.contains('.')) t.replace(",", "") else if (t.count { it == ',' } == 1) t.replace(',', '.') else t.replace(",", "")
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
