package com.namanbansal.forge.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

data class City(val name: String, val country: String, val zone: String)

enum class HourKind { WORK, AWAKE, NIGHT }
data class HourCell(val localHour: Int, val dayShift: Int, val kind: HourKind)

object Zones {
    val cities: List<City> = listOf(
        City("London", "United Kingdom", "Europe/London"), City("Dublin", "Ireland", "Europe/Dublin"), City("Lisbon", "Portugal", "Europe/Lisbon"),
        City("Paris", "France", "Europe/Paris"), City("Berlin", "Germany", "Europe/Berlin"), City("Madrid", "Spain", "Europe/Madrid"), City("Rome", "Italy", "Europe/Rome"),
        City("Amsterdam", "Netherlands", "Europe/Amsterdam"), City("Zurich", "Switzerland", "Europe/Zurich"), City("Stockholm", "Sweden", "Europe/Stockholm"),
        City("Athens", "Greece", "Europe/Athens"), City("Helsinki", "Finland", "Europe/Helsinki"), City("Istanbul", "Türkiye", "Europe/Istanbul"), City("Moscow", "Russia", "Europe/Moscow"),
        City("Cairo", "Egypt", "Africa/Cairo"), City("Lagos", "Nigeria", "Africa/Lagos"), City("Nairobi", "Kenya", "Africa/Nairobi"), City("Johannesburg", "South Africa", "Africa/Johannesburg"),
        City("Dubai", "United Arab Emirates", "Asia/Dubai"), City("Riyadh", "Saudi Arabia", "Asia/Riyadh"), City("Tehran", "Iran", "Asia/Tehran"), City("Karachi", "Pakistan", "Asia/Karachi"),
        City("Delhi", "India", "Asia/Kolkata"), City("Mumbai", "India", "Asia/Kolkata"), City("Kathmandu", "Nepal", "Asia/Kathmandu"), City("Dhaka", "Bangladesh", "Asia/Dhaka"),
        City("Bangkok", "Thailand", "Asia/Bangkok"), City("Jakarta", "Indonesia", "Asia/Jakarta"), City("Singapore", "Singapore", "Asia/Singapore"), City("Hong Kong", "China", "Asia/Hong_Kong"),
        City("Shanghai", "China", "Asia/Shanghai"), City("Manila", "Philippines", "Asia/Manila"), City("Seoul", "South Korea", "Asia/Seoul"), City("Tokyo", "Japan", "Asia/Tokyo"),
        City("Perth", "Australia", "Australia/Perth"), City("Sydney", "Australia", "Australia/Sydney"), City("Melbourne", "Australia", "Australia/Melbourne"), City("Auckland", "New Zealand", "Pacific/Auckland"),
        City("Honolulu", "United States", "Pacific/Honolulu"), City("Anchorage", "United States", "America/Anchorage"), City("Los Angeles", "United States", "America/Los_Angeles"),
        City("Vancouver", "Canada", "America/Vancouver"), City("Denver", "United States", "America/Denver"), City("Mexico City", "Mexico", "America/Mexico_City"),
        City("Chicago", "United States", "America/Chicago"), City("Toronto", "Canada", "America/Toronto"), City("New York", "United States", "America/New_York"),
        City("Bogotá", "Colombia", "America/Bogota"), City("Lima", "Peru", "America/Lima"), City("Santiago", "Chile", "America/Santiago"),
        City("São Paulo", "Brazil", "America/Sao_Paulo"), City("Buenos Aires", "Argentina", "America/Argentina/Buenos_Aires"), City("Reykjavik", "Iceland", "Atlantic/Reykjavik"),
        City("UTC", "Coordinated Universal Time", "UTC"),
    )

    fun find(zone: String): City? = cities.firstOrNull { it.zone == zone }
    fun matches(c: City, q: String) = q.isBlank() || c.name.contains(q.trim(), true) || c.country.contains(q.trim(), true) || c.zone.contains(q.trim(), true)

    /** Wall-clock time [local] in zone [from], shown in zone [to]. Gaps (spring forward) move forward, overlaps take the earlier offset. */
    fun convert(local: LocalDateTime, from: ZoneId, to: ZoneId): ZonedDateTime = ZonedDateTime.of(local, from).withZoneSameInstant(to)

    /** "UTC+5:30", "UTC-4", "UTC". */
    fun offsetLabel(zone: ZoneId, at: Instant): String {
        val secs = zone.rules.getOffset(at).totalSeconds
        if (secs == 0) return "UTC"
        val sign = if (secs < 0) "-" else "+"
        val a = kotlin.math.abs(secs)
        val h = a / 3600; val m = (a % 3600) / 60
        return "UTC$sign$h" + if (m != 0) ":%02d".format(m) else ""
    }

    fun abbreviation(zone: ZoneId, at: Instant): String = zone.getDisplayName(TextStyle.SHORT, Locale.getDefault()).let { _ ->
        java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH).format(at.atZone(zone))
    }

    /** Hour offset of [zone] relative to [other] at [at], in hours (can be fractional). */
    fun hoursAhead(zone: ZoneId, other: ZoneId, at: Instant): Double =
        (zone.rules.getOffset(at).totalSeconds - other.rules.getOffset(at).totalSeconds) / 3600.0

    /**
     * The meeting-planner strip: for each of the 24 hours of [date] in [ref], the matching local hour in [zone],
     * whether it is on another day, and whether it is working time (9 to 17), awake time (7 to 22) or night.
     */
    fun planner(date: LocalDate, ref: ZoneId, zone: ZoneId): List<HourCell> = (0 until 24).map { h ->
        val z = ZonedDateTime.of(date, java.time.LocalTime.of(h, 0), ref).withZoneSameInstant(zone)
        val hour = z.hour
        HourCell(hour, java.time.temporal.ChronoUnit.DAYS.between(date, z.toLocalDate()).toInt(), when (hour) { in 9..16 -> HourKind.WORK; in 7..21 -> HourKind.AWAKE; else -> HourKind.NIGHT })
    }

    /** Hours of [date] (in [ref]) where every zone is within working hours; handy to highlight the best slots. */
    fun commonWorkingHours(date: LocalDate, ref: ZoneId, zones: List<ZoneId>): List<Int> =
        (0 until 24).filter { h -> zones.all { z -> planner(date, ref, z)[h].kind == HourKind.WORK } }
}
