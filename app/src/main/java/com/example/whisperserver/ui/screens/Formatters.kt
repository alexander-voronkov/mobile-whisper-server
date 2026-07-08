package com.example.whisperserver.ui.screens

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)
private val clockSecondsFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
private val fullFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
private val dayFormat = SimpleDateFormat("MMM d", Locale.US)

/** "12:41" */
fun formatClock(millis: Long): String = if (millis <= 0) "—" else clockFormat.format(Date(millis))

/** "Jul 8, 12:41:07" */
fun formatFullTimestamp(millis: Long): String = if (millis <= 0) "—" else fullFormat.format(Date(millis))

/** "Jul 8" */
fun formatDay(millis: Long): String = if (millis <= 0) "—" else dayFormat.format(Date(millis))

/** "Today" / "Yesterday" / "Jul 8" — relative day used for Journal grouping. */
fun dayLabel(millis: Long): String {
    if (millis <= 0) return "—"
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayFormat.format(Date(millis))
    }
}

/** "12:41:07" */
fun formatClockSeconds(millis: Long): String = if (millis <= 0) "—" else clockSecondsFormat.format(Date(millis))

/**
 * Compact human duration, e.g. 940 -> "940 ms", 7200 -> "7.2 s", 92000 -> "1m 32s".
 * Returns "—" for non-positive values (unknown).
 */
fun formatDuration(millis: Long): String = when {
    millis <= 0 -> "—"
    millis < 1000 -> "$millis ms"
    millis < 60_000 -> String.format(Locale.US, "%.1f s", millis / 1000.0)
    else -> {
        val m = TimeUnit.MILLISECONDS.toMinutes(millis)
        val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        String.format(Locale.US, "%dm %02ds", m, s)
    }
}

/** "0:07" style mm:ss clock for the audio player. */
fun formatPlayerClock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/**
 * Processing cost per second of audio as a "×realtime" figure, e.g. 3.96 ->
 * "3.96×" (higher = slower = worse). Returns "—" for null / non-positive.
 */
fun formatRate(rate: Double?, decimals: Int = 2): String {
    if (rate == null || rate <= 0) return "—"
    return String.format(Locale.US, "%.${decimals}f×", rate)
}
