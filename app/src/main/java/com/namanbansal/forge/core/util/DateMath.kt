package com.namanbansal.forge.core.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

data class DateDiff(val years: Int, val months: Int, val days: Int, val totalDays: Long, val weeks: Long, val extraDays: Int, val workingDays: Long, val reversed: Boolean)
data class AgeInfo(val years: Int, val months: Int, val days: Int, val totalDays: Long, val nextBirthday: LocalDate, val daysToBirthday: Long)

object DateMath {
    /** Difference between two dates as years / months / days (earlier to later) plus total and working days. */
    fun diff(a: LocalDate, b: LocalDate, includeEnd: Boolean = false): DateDiff {
        val reversed = b.isBefore(a)
        val from = if (reversed) b else a
        val to = if (reversed) a else b
        val p = Period.between(from, to)
        val total = ChronoUnit.DAYS.between(from, to)
        val end = if (includeEnd) to.plusDays(1) else to
        return DateDiff(p.years, p.months, p.days, total, total / 7, (total % 7).toInt(), workingDays(from, end), reversed)
    }

    /** Monday-to-Friday days in [from, toExclusive). */
    fun workingDays(from: LocalDate, toExclusive: LocalDate): Long {
        if (!toExclusive.isAfter(from)) return 0
        val total = ChronoUnit.DAYS.between(from, toExclusive)
        var count = (total / 7) * 5
        var d = from.plusDays((total / 7) * 7)
        while (d.isBefore(toExclusive)) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) count++
            d = d.plusDays(1)
        }
        return count
    }

    /** Adds (or subtracts) years, months, then days. Month ends clamp: 31 Jan + 1 month = 28/29 Feb. */
    fun add(date: LocalDate, years: Int, months: Int, days: Int, subtract: Boolean = false): LocalDate {
        val s = if (subtract) -1 else 1
        return date.plusYears((s * years).toLong()).plusMonths((s * months).toLong()).plusDays((s * days).toLong())
    }

    /** Moves [n] working days forward (or back when negative), skipping weekends. */
    fun addWorkingDays(date: LocalDate, n: Int): LocalDate {
        var d = date
        var left = kotlin.math.abs(n)
        val step = if (n < 0) -1L else 1L
        while (left > 0) {
            d = d.plusDays(step)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) left--
        }
        return d
    }

    /** Age on [today]; 29 Feb birthdays are celebrated on 28 Feb in common years. A birthday today gives 0 days to go. */
    fun age(birth: LocalDate, today: LocalDate): AgeInfo {
        val p = Period.between(birth, today)
        var next = birth.withYear(today.year)
        if (next.isBefore(today)) next = birth.withYear(today.year + 1)
        return AgeInfo(p.years, p.months, p.days, ChronoUnit.DAYS.between(birth, today), next, ChronoUnit.DAYS.between(today, next))
    }

    fun isLeap(year: Int) = java.time.Year.isLeap(year.toLong())
}
