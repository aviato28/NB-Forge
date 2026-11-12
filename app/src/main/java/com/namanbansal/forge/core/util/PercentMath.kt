package com.namanbansal.forge.core.util

import kotlin.math.ceil
import kotlin.math.round

data class TipResult(val tip: Double, val total: Double, val perPerson: Double, val tipPerPerson: Double, val rounding: Double)

/** Quick money maths. All functions are pure; money is rounded to cents only when shown. */
object PercentMath {
    fun percentOf(percent: Double, value: Double) = value * percent / 100.0

    /** "a is what percent of b" (null when b is 0). */
    fun whatPercent(a: Double, b: Double): Double? = if (b == 0.0) null else a / b * 100.0

    /** Percent change from [from] to [to] (null when from is 0). */
    fun change(from: Double, to: Double): Double? = if (from == 0.0) null else (to - from) / kotlin.math.abs(from) * 100.0

    fun plusPercent(value: Double, percent: Double) = value * (1 + percent / 100.0)
    fun minusPercent(value: Double, percent: Double) = value * (1 - percent / 100.0)

    /**
     * Bill + tip split between [people]. With [roundUp] each person's share goes up to the next whole unit,
     * and [TipResult.rounding] is the extra that costs (it ends up in the tip).
     */
    fun tip(bill: Double, tipPercent: Double, people: Int, roundUp: Boolean = false): TipResult {
        val n = people.coerceAtLeast(1)
        val tip = bill * tipPercent / 100.0
        val exactTotal = bill + tip
        var per = exactTotal / n
        var rounding = 0.0
        if (roundUp) {
            val up = ceil(round(per * 100) / 100.0)
            rounding = (up - per) * n
            per = up
        }
        val total = per * n
        return TipResult(tip + rounding, total, per, (tip + rounding) / n, rounding)
    }
}
