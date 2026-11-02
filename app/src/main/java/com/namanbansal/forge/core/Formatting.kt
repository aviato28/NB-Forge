package com.namanbansal.forge.core

import java.util.Locale

/** Human-readable size ("4.2 MB"); negative means unknown. Uses 1024 steps like the system file UI. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown size"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    val text = if (v >= 100 || i == 0 && v >= 10) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    return "$text ${units[i]}"
}

fun pagesLabel(n: Int) = if (n == 1) "1 page" else "$n pages"

/** "just now", "5 min ago", "3 h ago", "2 d ago", else a date. */
fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - then).coerceAtLeast(0)
    val min = d / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        min < 60 * 24 * 7 -> "${min / (60 * 24)} d ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(java.util.Date(then))
    }
}
