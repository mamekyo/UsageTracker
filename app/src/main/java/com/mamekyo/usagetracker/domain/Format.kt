package com.mamekyo.usagetracker.domain

import com.mamekyo.usagetracker.data.DisplayMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class Level { GOOD, WARN, CRITICAL }

/** Language-independent formatting; wording lives in [com.mamekyo.usagetracker.i18n.Texts]. */
object Format {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

    /** Percentage shown to the user for the chosen mode, 0..100. */
    fun shownPercent(usedPercent: Double, mode: DisplayMode): Int {
        val value = if (mode == DisplayMode.REMAINING) 100.0 - usedPercent else usedPercent
        return value.roundToInt().coerceIn(0, 100)
    }

    /** Severity is always judged on what is left, whatever the display mode. */
    fun level(usedPercent: Double): Level {
        val remaining = 100.0 - usedPercent
        return when {
            remaining <= 20.0 -> Level.CRITICAL
            remaining <= 50.0 -> Level.WARN
            else -> Level.GOOD
        }
    }

    data class DurationParts(val days: Long, val hours: Long, val minutes: Long)

    /** Splits a countdown into days/hours/minutes, rounding up to the next whole minute. */
    fun durationParts(millis: Long): DurationParts {
        val totalMinutes = (millis.coerceAtLeast(0) + 59_999) / 60_000
        return DurationParts(totalMinutes / (24 * 60), (totalMinutes % (24 * 60)) / 60, totalMinutes % 60)
    }

    /** "14:30" today, "9/24 14:30" otherwise. */
    fun clock(epochMillis: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (time.toLocalDate() == today) timeFormat.format(time) else dateTimeFormat.format(time)
    }
}
