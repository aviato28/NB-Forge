package com.namanbansal.forge.util

import com.namanbansal.forge.core.util.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class UnitsTest {
    @Test fun roundTripEveryPairInEveryCategory() {
        for (cat in Units.categories) for (a in cat.units) for (b in cat.units) {
            for (v in listOf(1.0, 123.456, 0.0042, 98765.4321)) {
                val there = cat.convert(v, a, b)
                val back = cat.convert(there, b, a)
                assertEquals("${cat.id} ${a.id}->${b.id} of $v", v, back, Math.abs(v) * 1e-9)
            }
        }
    }

    private fun c(cat: UnitCategory, v: Double, from: String, to: String) = cat.convert(v, cat.unit(from), cat.unit(to))

    @Test fun knownValues() {
        assertEquals(1609.344, c(Units.length, 1.0, "mi", "m"), 1e-9)
        assertEquals(2.54, c(Units.length, 1.0, "in", "cm"), 1e-12)
        assertEquals(32.0, c(Units.temperature, 0.0, "c", "f"), 1e-9)
        assertEquals(212.0, c(Units.temperature, 100.0, "c", "f"), 1e-9)
        assertEquals(-40.0, c(Units.temperature, -40.0, "f", "c"), 1e-9)
        assertEquals(0.0, c(Units.temperature, 273.15, "k", "c"), 1e-9)
        assertEquals(-459.67, c(Units.temperature, 0.0, "k", "f"), 1e-9)
        assertEquals(3.785411784, c(Units.volume, 1.0, "gal", "l"), 1e-9)
        assertEquals(1024.0, c(Units.data, 1.0, "KiB", "B"), 1e-9)
        assertEquals(1000.0, c(Units.data, 1.0, "KB", "B"), 1e-9)
        assertEquals(1.0, c(Units.data, 8.0, "bit", "B"), 1e-9)
        assertEquals(0.44704, c(Units.speed, 1.0, "mph", "ms"), 1e-9)
        assertEquals(3.92, c(Units.fuel, 60.0, "mpgus", "l100"), 0.01)
        assertEquals(10.0, c(Units.fuel, 10.0, "kml", "l100"), 1e-9)
        assertEquals(101325.0, c(Units.pressure, 1.0, "atm", "pa"), 1e-6)
        assertEquals(4184.0, c(Units.energy, 1.0, "kcal", "j"), 1e-9)
        assertEquals(4046.8564224, c(Units.area, 1.0, "ac", "m2"), 1e-6)
        assertEquals(24.0, c(Units.time, 1.0, "d", "h"), 1e-12)
        assertEquals(14.0, c(Units.weight, 1.0, "st", "lb"), 0.001)
    }

    @Test fun fuelZeroIsNotACrash() { assertTrue(c(Units.fuel, 0.0, "mpgus", "l100").isNaN()) }

    @Test fun everyCategoryHasItsDefaults() {
        assertEquals(11, Units.categories.size)
        for (cat in Units.categories) { assertNotNull(cat.units.firstOrNull { it.id == cat.defaultFrom }); assertNotNull(cat.units.firstOrNull { it.id == cat.defaultTo }) }
        assertEquals(Units.length, Units.category("nonsense"))
    }

    @Test fun formatting() {
        assertEquals("0", NumberText.format(0.0))
        assertEquals("1,234.5", NumberText.format(1234.5))
        assertEquals("0.3", NumberText.format(0.1 + 0.2))
        assertEquals("1,609.344", NumberText.format(1609.344))
        assertEquals("-1,000", NumberText.format(-1000.0))
        assertEquals("1.5e+20", NumberText.format(1.5e20))
        assertEquals("2.5e-8", NumberText.format(2.5e-8))
        assertEquals("—", NumberText.format(Double.NaN))
        assertEquals("∞", NumberText.format(Double.POSITIVE_INFINITY))
        assertEquals("3.333333333", NumberText.format(10.0 / 3))
        assertEquals("1234.5", NumberText.format(1234.5, group = false))
    }

    @Test fun parsing() {
        assertEquals(1234.5, NumberText.parse("1,234.5")!!, 0.0)
        assertEquals(1.5, NumberText.parse("1,5")!!, 0.0)
        assertEquals(-2.0, NumberText.parse(" -2 ")!!, 0.0)
        assertNull(NumberText.parse("")); assertNull(NumberText.parse("-")); assertNull(NumberText.parse("abc")); assertNull(NumberText.parse("."))
    }
}

class CurrencyTest {
    private val body = """{"amount":1.0,"base":"EUR","date":"2026-11-10","rates":{"USD":1.08,"GBP":0.85,"JPY":160.0}}"""
    private val dir = java.nio.file.Files.createTempDirectory("fx").toFile()

    @Test fun parseAddsBaseAndConverts() {
        val r = CurrencyParse.parse(body, 1000L)
        assertEquals("EUR", r.base); assertEquals("2026-11-10", r.date); assertEquals(1.0, r.rates["EUR"]!!, 0.0)
        assertEquals(108.0, r.convert(100.0, "EUR", "USD")!!, 1e-9)
        assertEquals(100.0, r.convert(108.0, "USD", "EUR")!!, 1e-9)
        assertEquals(0.85 / 1.08 * 50, r.convert(50.0, "USD", "GBP")!!, 1e-9)
        assertNull(r.convert(1.0, "USD", "XXX"))
        assertEquals(listOf("EUR", "GBP", "JPY", "USD"), r.codes)
    }

    @Test(expected = CurrencyException::class) fun parseRejectsGarbage() { CurrencyParse.parse("<html>", 0) }
    @Test(expected = CurrencyException::class) fun parseRejectsEmptyRates() { CurrencyParse.parse("""{"base":"EUR","date":"x","rates":{}}""", 0) }

    @Test fun cacheRoundTripAndFreshness() = kotlinx.coroutines.runBlocking {
        var now = 1_000_000L
        var calls = 0
        val repo = CurrencyRepository(java.io.File(dir, "c1.json"), { calls++; body }, { now })
        val first = repo.load()
        assertFalse(first.offline); assertEquals(1, calls)
        now += 3600_000
        repo.load(); assertEquals("fresh cache is reused", 1, calls)
        now += 7 * 3600_000
        repo.load(); assertEquals("stale cache refetches", 2, calls)
        repo.load(forceRefresh = true); assertEquals(3, calls)
    }

    @Test fun offlineFallsBackToCacheWithFlag() = kotlinx.coroutines.runBlocking {
        var now = 5_000_000L
        var online = true
        val repo = CurrencyRepository(java.io.File(dir, "c2.json"), { if (online) body else throw java.io.IOException("offline") }, { now })
        repo.load()
        online = false; now += 30 * 3600_000
        val r = repo.load()
        assertTrue(r.offline); assertEquals(5_000_000L, r.rates.fetchedAt); assertNotNull(r.error)
        assertTrue(CurrencyRepository.asOf(r.rates, now).contains("2026-11-10"))
    }

    @Test fun offlineWithNoCacheThrows() {
        val repo = CurrencyRepository(java.io.File(dir, "c3.json"), { throw java.io.IOException("offline") })
        try { kotlinx.coroutines.runBlocking { repo.load() }; fail() } catch (e: CurrencyException) { assertTrue(e.message!!.contains("No internet")) }
    }

    @Test fun corruptCacheIsIgnored() = kotlinx.coroutines.runBlocking {
        val f = java.io.File(dir, "c4.json"); f.writeText("garbage")
        val r = CurrencyRepository(f, { body }, { 1L }).load()
        assertFalse(r.offline); assertEquals(1.08, r.rates.rates["USD"]!!, 0.0)
    }

    @Test fun searchMatchesCodeOrName() {
        assertTrue(CurrencyInfo.matches("USD", "usd")); assertTrue(CurrencyInfo.matches("USD", "dollar")); assertFalse(CurrencyInfo.matches("USD", "yen")); assertTrue(CurrencyInfo.matches("JPY", ""))
    }
}

class CalcTest {
    private fun e(s: String, deg: Boolean = true) = Calc.evaluate(s, deg)
    private fun err(s: String): String = try { Calc.evaluate(s); "no error" } catch (x: CalcError) { x.message!! }

    @Test fun precedence() {
        assertEquals(14.0, e("2+3*4"), 0.0); assertEquals(20.0, e("(2+3)*4"), 0.0); assertEquals(-4.0, e("-2^2"), 0.0)
        assertEquals(512.0, e("2^3^2"), 0.0); assertEquals(0.125, e("2^-3"), 0.0); assertEquals(1.0, e("10-5-4"), 0.0)
        assertEquals(2.0, e("16/4/2"), 0.0); assertEquals(7.0, e("1+2*3"), 0.0); assertEquals(13.0, e("5--8"), 0.0)
    }

    @Test fun operatorsSymbols() {
        assertEquals(6.0, e("2×3"), 0.0); assertEquals(2.5, e("5÷2"), 0.0); assertEquals(1.0, e("3−2"), 0.0)
    }

    @Test fun implicitMultiplication() {
        assertEquals(6.0, e("2(3)"), 0.0); assertEquals(Math.PI * 2, e("2π"), 1e-12); assertEquals(10.0, e("(1+1)(2+3)"), 0.0); assertEquals(6.0, e("2 3"), 0.0)
    }

    @Test fun floatingPointIsTidied() { assertEquals("0.3", Calc.format(e("0.1+0.2"))); assertEquals("3.14159265359", Calc.format(Math.PI)) }

    @Test fun percent() {
        assertEquals(0.5, e("50%"), 0.0); assertEquals(220.0, e("200+10%"), 1e-9); assertEquals(180.0, e("200-10%"), 1e-9)
        assertEquals(0.5, e("10%*5"), 1e-12); assertEquals(20.0, e("200*10%"), 1e-9)
    }

    @Test fun functionsDegrees() {
        assertEquals(0.5, e("sin(30)"), 1e-12); assertEquals(0.0, e("sin(180)"), 0.0); assertEquals(0.0, e("cos(90)"), 0.0)
        assertEquals(1.0, e("tan(45)"), 1e-12); assertEquals(30.0, e("asin(0.5)"), 1e-9); assertEquals(45.0, e("atan(1)"), 1e-9)
        assertEquals(60.0, e("acos(0.5)"), 1e-9)
    }

    @Test fun functionsRadians() {
        assertEquals(1.0, e("sin(pi/2)", false), 1e-12); assertEquals(-1.0, e("cos(pi)", false), 1e-12); assertEquals(Math.PI / 4, e("atan(1)", false), 1e-12)
    }

    @Test fun otherFunctions() {
        assertEquals(1.0, e("ln(e)"), 1e-12); assertEquals(3.0, e("log(1000)"), 1e-12); assertEquals(3.0, e("log2(8)"), 1e-12)
        assertEquals(4.0, e("sqrt(16)"), 0.0); assertEquals(4.0, e("√16"), 0.0); assertEquals(3.0, e("cbrt(27)"), 1e-12); assertEquals(5.0, e("abs(-5)"), 0.0)
        assertEquals(120.0, e("5!"), 0.0); assertEquals(1.0, e("0!"), 0.0); assertEquals(6.0, e("3!"), 0.0); assertEquals(24.0, e("fact(4)"), 0.0)
        assertEquals(Math.E, e("exp(1)"), 1e-12); assertEquals(3.0, e("round(2.6)"), 0.0); assertEquals(2.0, e("floor(2.9)"), 0.0); assertEquals(3.0, e("ceil(2.1)"), 0.0)
        assertEquals(0.5, e("sin 30"), 1e-12); assertEquals(2.0, e("sqrt(2)^2"), 1e-12)
    }

    @Test fun autoClosesBrackets() { assertEquals(14.0, e("2*(3+4"), 0.0); assertEquals(5.0, e("sqrt(25"), 0.0) }

    @Test fun errors() {
        assertEquals("Can't divide by zero", err("1/0")); assertEquals("Can't divide by zero", err("5/(3-3)"))
        assertEquals("Check your brackets", err("(1+2))")); assertEquals("Incomplete expression", err("2+"))
        assertEquals("Nothing to calculate", err("")); assertEquals("Unknown function \"foo\"", err("foo(2)"))
        assertEquals("Square root of a negative number", err("sqrt(-4)")); assertEquals("ln needs a positive number", err("ln(0)"))
        assertEquals("Factorial needs a whole number, 0 or more", err("2.5!")); assertEquals("Factorial needs a whole number, 0 or more", err("(-1)!"))
        assertEquals("Result too large", err("171!")); assertEquals("Result too large", err("10^999"))
        assertEquals("Too many decimal points", err("1.2.3")); assertEquals("Unexpected \"$\"", err("2\$3"))
        assertEquals("Tangent is undefined here", err("tan(90)")); assertEquals("Input must be between -1 and 1", err("asin(2)"))
        assertEquals("Unexpected \"*\"", err("*3"))
    }

    @Test fun tryEvaluate() { assertNull(Calc.tryEvaluate("2+")); assertNull(Calc.tryEvaluate("  ")); assertEquals(4.0, Calc.tryEvaluate("2+2")!!, 0.0) }

    @Test fun history() {
        val items = Calc.History.add(Calc.History.add(emptyList(), "1+1", "2"), "2*3", "6")
        assertEquals(listOf("2*3" to "6", "1+1" to "2"), items)
        assertEquals(items, Calc.History.decode(Calc.History.encode(items)))
        assertEquals(1, Calc.History.add(items.take(1), "2*3", "6").size)
        assertEquals(Calc.History.MAX, (1..80).fold(emptyList<Pair<String, String>>()) { l, i -> Calc.History.add(l, "$i+0", "$i") }.size)
        assertTrue(Calc.History.decode("garbage").isEmpty())
    }
}

class PercentMathTest {
    @Test fun basics() {
        assertEquals(15.0, PercentMath.percentOf(15.0, 100.0), 1e-12); assertEquals(25.0, PercentMath.whatPercent(5.0, 20.0)!!, 1e-12)
        assertNull(PercentMath.whatPercent(5.0, 0.0)); assertEquals(25.0, PercentMath.change(80.0, 100.0)!!, 1e-12); assertEquals(-20.0, PercentMath.change(100.0, 80.0)!!, 1e-12)
        assertNull(PercentMath.change(0.0, 5.0)); assertEquals(110.0, PercentMath.plusPercent(100.0, 10.0), 1e-12); assertEquals(90.0, PercentMath.minusPercent(100.0, 10.0), 1e-12)
    }

    @Test fun tipAndSplit() {
        val r = PercentMath.tip(80.0, 15.0, 4)
        assertEquals(12.0, r.tip, 1e-9); assertEquals(92.0, r.total, 1e-9); assertEquals(23.0, r.perPerson, 1e-9); assertEquals(3.0, r.tipPerPerson, 1e-9)
    }

    @Test fun roundUpMovesExtraIntoTip() {
        val r = PercentMath.tip(100.0, 10.0, 3, roundUp = true)   // 110 / 3 = 36.67 -> 37 each
        assertEquals(37.0, r.perPerson, 1e-9); assertEquals(111.0, r.total, 1e-9); assertEquals(11.0, r.tip, 1e-9)
    }

    @Test fun zeroPeopleIsTreatedAsOne() { assertEquals(110.0, PercentMath.tip(100.0, 10.0, 0).perPerson, 1e-9) }
}

class DateMathTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun leapYears() {
        assertTrue(DateMath.isLeap(2024)); assertFalse(DateMath.isLeap(1900)); assertTrue(DateMath.isLeap(2000)); assertFalse(DateMath.isLeap(2023))
        assertEquals(2, DateMath.diff(d("2024-02-28"), d("2024-03-01")).totalDays); assertEquals(1, DateMath.diff(d("2023-02-28"), d("2023-03-01")).totalDays)
        assertEquals(366, DateMath.diff(d("2024-01-01"), d("2025-01-01")).totalDays)
    }

    @Test fun differenceBreakdown() {
        val x = DateMath.diff(d("2020-01-15"), d("2024-03-20"))
        assertEquals(4, x.years); assertEquals(2, x.months); assertEquals(5, x.days); assertFalse(x.reversed)
        assertEquals(x.totalDays / 7, x.weeks); assertEquals((x.totalDays % 7).toInt(), x.extraDays)
        val r = DateMath.diff(d("2024-03-20"), d("2020-01-15")); assertTrue(r.reversed); assertEquals(4, r.years)
        assertEquals(0, DateMath.diff(d("2024-05-05"), d("2024-05-05")).totalDays)
    }

    @Test fun monthEnds() {
        assertEquals(d("2024-02-29"), DateMath.add(d("2024-01-31"), 0, 1, 0)); assertEquals(d("2023-02-28"), DateMath.add(d("2023-01-31"), 0, 1, 0))
        assertEquals(d("2024-02-29"), DateMath.add(d("2023-02-28"), 1, 0, 0).let { DateMath.add(it, 0, 0, 1) })
        assertEquals(d("2023-12-31"), DateMath.add(d("2024-01-31"), 0, 1, 0, subtract = true))
        assertEquals(d("2024-02-29"), DateMath.add(d("2020-02-29"), 4, 0, 0)); assertEquals(d("2021-02-28"), DateMath.add(d("2020-02-29"), 1, 0, 0))
        assertEquals(d("2024-03-01"), DateMath.add(d("2024-02-28"), 0, 0, 2))
    }

    @Test fun workingDays() {
        assertEquals(5, DateMath.workingDays(d("2024-01-01"), d("2024-01-08")))   // Mon to next Mon
        assertEquals(0, DateMath.workingDays(d("2024-01-06"), d("2024-01-08")))   // Sat, Sun
        assertEquals(10, DateMath.workingDays(d("2024-01-01"), d("2024-01-15")))
        assertEquals(3, DateMath.workingDays(d("2024-01-03"), d("2024-01-08")))   // Wed, Thu, Fri
        assertEquals(0, DateMath.workingDays(d("2024-01-08"), d("2024-01-01")))
        assertEquals(6, DateMath.diff(d("2024-01-01"), d("2024-01-08"), includeEnd = true).workingDays)
        assertEquals(d("2024-01-08"), DateMath.addWorkingDays(d("2024-01-05"), 1)); assertEquals(d("2024-01-05"), DateMath.addWorkingDays(d("2024-01-08"), -1))
        assertEquals(d("2024-01-15"), DateMath.addWorkingDays(d("2024-01-01"), 10)); assertEquals(d("2024-01-01"), DateMath.addWorkingDays(d("2024-01-01"), 0))
    }

    @Test fun age() {
        val a = DateMath.age(d("1990-06-15"), d("2024-03-10"))
        assertEquals(33, a.years); assertEquals(8, a.months); assertEquals(24, a.days); assertEquals(d("2024-06-15"), a.nextBirthday); assertEquals(97, a.daysToBirthday)
        assertEquals(0, DateMath.age(d("1990-06-15"), d("2024-06-15")).daysToBirthday)
        assertEquals(d("2025-06-15"), DateMath.age(d("1990-06-15"), d("2024-06-16")).nextBirthday)
        val leap = DateMath.age(d("2000-02-29"), d("2023-02-27"))
        assertEquals(d("2023-02-28"), leap.nextBirthday); assertEquals(1, leap.daysToBirthday)
        assertEquals(d("2024-02-29"), DateMath.age(d("2000-02-29"), d("2023-03-01")).nextBirthday)
    }
}

class ZonesTest {
    private val ny = ZoneId.of("America/New_York"); private val tokyo = ZoneId.of("Asia/Tokyo"); private val delhi = ZoneId.of("Asia/Kolkata")

    @Test fun convertsAcrossDst() {
        val summer = Zones.convert(LocalDateTime.of(2024, 7, 1, 12, 0), ny, tokyo)
        assertEquals(LocalDateTime.of(2024, 7, 2, 1, 0), summer.toLocalDateTime())
        val winter = Zones.convert(LocalDateTime.of(2024, 1, 1, 12, 0), ny, tokyo)
        assertEquals(LocalDateTime.of(2024, 1, 2, 2, 0), winter.toLocalDateTime())
        assertEquals(LocalDateTime.of(2024, 7, 1, 21, 30), Zones.convert(LocalDateTime.of(2024, 7, 1, 12, 0), ny, delhi).toLocalDateTime())
    }

    @Test fun springForwardGapMovesForward() {
        // 2:30 does not exist in New York on 2024-03-10; java moves it to 3:30.
        val z = Zones.convert(LocalDateTime.of(2024, 3, 10, 2, 30), ny, ZoneId.of("UTC"))
        assertEquals(LocalDateTime.of(2024, 3, 10, 7, 30), z.toLocalDateTime())
    }

    @Test fun offsetLabels() {
        val i = java.time.Instant.parse("2024-07-01T00:00:00Z")
        assertEquals("UTC+5:30", Zones.offsetLabel(delhi, i)); assertEquals("UTC-4", Zones.offsetLabel(ny, i)); assertEquals("UTC", Zones.offsetLabel(ZoneId.of("UTC"), i))
        assertEquals("UTC-5", Zones.offsetLabel(ny, java.time.Instant.parse("2024-01-01T00:00:00Z"))); assertEquals(13.0, Zones.hoursAhead(tokyo, ny, i), 0.0)
    }

    @Test fun plannerStrip() {
        val cells = Zones.planner(LocalDate.of(2024, 7, 1), ny, tokyo)
        assertEquals(24, cells.size)
        assertEquals(13, cells[0].localHour); assertEquals(0, cells[0].dayShift)   // 00:00 NY = 13:00 Tokyo, same day
        assertEquals(HourKind.WORK, cells[0].kind)
        assertEquals(1, cells[12].dayShift); assertEquals(1, cells[12].localHour); assertEquals(HourKind.NIGHT, cells[12].kind)
        val common = Zones.commonWorkingHours(LocalDate.of(2024, 7, 1), ny, listOf(ny, ZoneId.of("Europe/London")))
        assertEquals(listOf(9, 10, 11), common)   // London is 5 h ahead of New York in July
    }

    @Test fun cityCatalogue() {
        assertTrue(Zones.cities.all { runCatching { ZoneId.of(it.zone) }.isSuccess })
        assertEquals("New York", Zones.find("America/New_York")!!.name); assertTrue(Zones.matches(Zones.cities.first(), "lon")); assertTrue(Zones.matches(Zones.cities.first(), ""))
    }
}

class TimersTest {
    @Test fun stopwatchRunsPausesAndLaps() {
        var s = StopwatchState().start(1000)
        assertEquals(500, s.elapsed(1500)); s = s.lap(1500); s = s.lap(2200)
        assertEquals(listOf(700L, 500L), s.laps.map { it.lapMs }); assertEquals(listOf(1200L, 500L), s.laps.map { it.totalMs }); assertEquals(2, s.laps.first().number)
        s = s.stop(3000); assertEquals(2000, s.elapsed(9999)); assertFalse(s.running)
        s = s.start(10_000); assertEquals(2500, s.elapsed(10_500)); assertNull(s.copy(laps = s.laps.take(2)).bestWorst())
        assertEquals(StopwatchState(), s.reset()); assertEquals(StopwatchState(), StopwatchState().lap(5))
    }

    @Test fun bestAndWorstLap() {
        var s = StopwatchState().start(0)
        s = s.lap(1000).lap(1500).lap(3500)    // laps 1000, 500, 2000
        val (best, worst) = s.bestWorst()!!
        assertEquals(500L, s.laps[best].lapMs); assertEquals(2000L, s.laps[worst].lapMs)
    }

    @Test fun stopwatchFormat() {
        assertEquals("00:00.00", StopwatchState.format(0)); assertEquals("01:05.50", StopwatchState.format(65_500)); assertEquals("1:01:01.01", StopwatchState.format(3_661_010)); assertEquals("01:05", StopwatchState.format(65_999, false))
    }

    @Test fun planClockWalksPhases() {
        val plan = listOf(Phase("a", 10), Phase("b", 5))
        assertEquals(PlanPosition(0, 10_000, false), PlanClock.at(plan, 1000, 1000)); assertEquals(PlanPosition(0, 4_000, false), PlanClock.at(plan, 1000, 7000))
        assertEquals(PlanPosition(1, 5_000, false), PlanClock.at(plan, 1000, 11_000)); assertEquals(PlanPosition(1, 1_000, false), PlanClock.at(plan, 1000, 15_000))
        assertEquals(PlanPosition(1, 0, true), PlanClock.at(plan, 1000, 16_000)); assertEquals(PlanPosition(1, 0, true), PlanClock.at(plan, 1000, 99_000))
        assertEquals(PlanPosition(0, 10_000, false), PlanClock.at(plan, 1000, 0)); assertEquals(15_000L, PlanClock.totalMs(plan)); assertEquals(11_000L, PlanClock.phaseEnd(plan, 1000, 0))
    }

    @Test fun clockAndDescribe() {
        assertEquals("00:01", PlanClock.clock(1)); assertEquals("00:00", PlanClock.clock(0)); assertEquals("05:00", PlanClock.clock(300_000)); assertEquals("1:00:00", PlanClock.clock(3_600_000))
        assertEquals("1 h 5 min", PlanClock.describe(3900)); assertEquals("30 s", PlanClock.describe(30)); assertEquals("5 min", PlanClock.describe(300)); assertEquals("0 s", PlanClock.describe(0))
    }

    @Test fun pomodoroPlan() {
        val p = Pomodoro.plan()
        assertEquals(8, p.size); assertEquals(listOf(25, 5, 25, 5, 25, 5, 25, 15), p.map { it.seconds / 60 }); assertEquals("Long break", p.last().label); assertEquals("Focus 1 of 4", p.first().label)
        assertEquals(2, Pomodoro.plan(rounds = 1).size)
    }
}

class RandomiserTest {
    private fun r(seed: Int = 1) = Randomiser(Random(seed))

    @Test fun numbersStayInRange() { val n = r().numbers(5, 8, 500); assertTrue(n.all { it in 5..8 }); assertEquals(setOf(5L, 6L, 7L, 8L), n.toSet()) }
    @Test fun uniqueNumbersAreDistinct() { val n = r().numbers(1, 10, 10, unique = true); assertEquals((1L..10L).toSet(), n.toSet()); assertEquals(10, n.size) }
    @Test fun numberErrors() {
        for (bad in listOf({ r().numbers(5, 1) }, { r().numbers(1, 3, 4, unique = true) }, { r().numbers(1, 3, 0) }, { r().numbers(1, 3, 2000) })) {
            try { bad(); fail() } catch (_: RandomError) { }
        }
        assertEquals(3L, r().numbers(3, 3).single())
    }
    @Test fun hugeUniqueRange() { val n = r().numbers(0, Long.MAX_VALUE / 2, 5, unique = true); assertEquals(5, n.toSet().size) }
    @Test fun diceAndCoin() {
        assertTrue(r().dice(6, 100).all { it in 1..6 }); assertEquals(6, r().dice(6, 100).toSet().size)
        val flips = List(200) { r(it).coin() }; assertTrue(flips.any { it } && flips.any { !it })
        try { r().dice(1); fail() } catch (_: RandomError) { }; try { r().dice(6, 0); fail() } catch (_: RandomError) { }
    }
    @Test fun listPicker() {
        val items = Randomiser.parseItems("a\n\n  b  \nc\n")
        assertEquals(listOf("a", "b", "c"), items); assertTrue(r().pick(items) in items); assertEquals(items.toSet(), r().shuffle(items).toSet())
        try { r().pick(listOf(" ", "")); fail() } catch (_: RandomError) { }
        assertEquals(r(7).pick(items), r(7).pick(items))
    }
}

class TallyTest {
    private val dir = java.nio.file.Files.createTempDirectory("tally").toFile()

    @Test fun persistsAcrossStoreInstances() {
        val f = java.io.File(dir, "t.json")
        var list = TallyStore.add(emptyList(), "Laps"); list = TallyStore.add(list, "  "); list = TallyStore.bump(list, 1, +1); list = TallyStore.bump(list, 1, +1)
        TallyStore(f).save(list)
        val loaded = TallyStore(f).load()
        assertEquals(listOf("Laps", "Counter 2"), loaded.map { it.name }); assertEquals(2, loaded[0].count); assertEquals(list, loaded)
    }

    @Test fun operations() {
        var l = TallyStore.add(TallyStore.add(emptyList(), "a"), "b")
        assertEquals(listOf(1L, 2L), l.map { it.id })
        l = TallyStore.setStep(l, 1, 5); l = TallyStore.bump(l, 1, 1); assertEquals(5, l[0].count)
        l = TallyStore.bump(l, 1, -1); l = TallyStore.bump(l, 1, -1); assertEquals("never below zero", 0, l[0].count)
        l = TallyStore.rename(l, 2, "  renamed "); assertEquals("renamed", l[1].name); assertEquals("blank rename ignored", "renamed", TallyStore.rename(l, 2, " ")[1].name)
        l = TallyStore.reset(TallyStore.bump(l, 2, 1), 2); assertEquals(0, l[1].count)
        l = TallyStore.remove(l, 1); assertEquals(1, l.size); assertEquals(3L, TallyStore.add(l, "c").last().id)
        assertEquals(1000, TallyStore.setStep(l, 2, 5000)[0].step)
    }

    @Test fun missingOrCorruptFileIsEmpty() {
        val f = java.io.File(dir, "none.json"); assertTrue(TallyStore(f).load().isEmpty())
        f.writeText("{not json"); assertTrue(TallyStore(f).load().isEmpty())
    }
}

class FinanceTest {
    @Test fun emiKnownValue() {
        assertEquals(8884.88, EmiMath.emi(100_000.0, 12.0, 12), 0.01)
        assertEquals(1000.0, EmiMath.emi(12000.0, 0.0, 12), 1e-9)
        assertEquals(899.33, EmiMath.emi(150_000.0, 6.0, 360), 0.01)
    }

    @Test fun scheduleBalancesToZero() {
        val r = EmiMath.schedule(100_000.0, 12.0, 12)
        assertEquals(12, r.rows.size); assertEquals(0.0, r.rows.last().balance, 0.0)
        assertEquals(100_000.0, r.rows.sumOf { it.principal }, 0.005)
        assertEquals(r.totalInterest, r.rows.sumOf { it.interest }, 0.01); assertEquals(1000.0, r.rows[0].interest, 0.0)
        assertEquals(r.totalPaid - 100_000.0, r.totalInterest, 0.01)
        for (i in 1 until r.rows.size) assertTrue(r.rows[i].interest <= r.rows[i - 1].interest)
        assertTrue(Math.abs(r.rows.last().payment - r.emi) < 1.0)
    }

    @Test fun zeroRateAndTinyLoans() {
        val z = EmiMath.schedule(1000.0, 0.0, 3); assertEquals(0.0, z.totalInterest, 0.0); assertEquals(0.0, z.rows.last().balance, 0.0); assertEquals(1000.0, z.rows.sumOf { it.principal }, 0.005)
        val one = EmiMath.schedule(500.0, 10.0, 1); assertEquals(1, one.rows.size); assertEquals(0.0, one.rows[0].balance, 0.0)
        val long = EmiMath.schedule(250_000.0, 7.5, 360); assertEquals(0.0, long.rows.last().balance, 0.0); assertEquals(360, long.rows.size)
    }

    @Test fun csv() {
        val csv = EmiMath.toCsv(EmiMath.schedule(1200.0, 0.0, 2), "USD")
        val lines = csv.trim().split("\r\n")
        assertEquals("Month,Payment (USD),Interest (USD),Principal (USD),Balance (USD)", lines[0]); assertEquals("1,600.00,0.00,600.00,600.00", lines[1]); assertEquals("2,600.00,0.00,600.00,0.00", lines[2])
        assertEquals("Month,Payment,Interest,Principal,Balance", EmiMath.toCsv(EmiMath.schedule(10.0, 0.0, 1)).lines()[0])
    }

    @Test fun bmiAndBmr() {
        assertEquals(22.86, Health.bmi(70.0, 175.0), 0.01); assertEquals("Healthy weight", Health.bmiCategory(22.86)); assertEquals("Underweight", Health.bmiCategory(17.0))
        assertEquals("Overweight", Health.bmiCategory(27.0)); assertEquals("Obese (class 1)", Health.bmiCategory(31.0)); assertEquals("Obese (class 3)", Health.bmiCategory(45.0))
        assertEquals(1648.75, Health.bmr(Sex.MALE, 70.0, 175.0, 30), 1e-9); assertEquals(1482.75, Health.bmr(Sex.FEMALE, 70.0, 175.0, 30), 1e-9)
        assertEquals(1648.75 * 1.55, Health.tdee(1648.75, Activity.MODERATE), 1e-9)
        val (lo, hi) = Health.healthyRangeKg(175.0); assertEquals(56.7, lo, 0.1); assertEquals(76.3, hi, 0.1)
    }

    @Test fun imperialConversions() {
        assertEquals(177.8, Health.feetInchesToCm(5.0, 10.0), 1e-9); assertEquals(5 to 10.0, Health.cmToFeetInches(177.8).let { it.first to Math.round(it.second).toDouble() })
        assertEquals(70.0, Health.lbToKg(Health.kgToLb(70.0)), 1e-9); assertEquals(0.45359237, Health.lbToKg(1.0), 1e-12)
        assertEquals(6 to 0.0, Health.cmToFeetInches(182.87))
    }
}
