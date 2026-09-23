package com.mamekyo.usagetracker.domain

import com.mamekyo.usagetracker.data.DisplayMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class Level { GOOD, WARN, CRITICAL }

object Format {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

    /** Percentage shown to the user for the chosen mode, 0..100. */
    fun shownPercent(usedPercent: Double, mode: DisplayMode): Int {
        val value = if (mode == DisplayMode.REMAINING) 100.0 - usedPercent else usedPercent
        return value.roundToInt().coerceIn(0, 100)
    }

    fun modeWord(mode: DisplayMode) = if (mode == DisplayMode.REMAINING) "剩餘" else "已使用"

    /** Severity is always judged on what is left, whatever the display mode. */
    fun level(usedPercent: Double): Level {
        val remaining = 100.0 - usedPercent
        return when {
            remaining <= 20.0 -> Level.CRITICAL
            remaining <= 50.0 -> Level.WARN
            else -> Level.GOOD
        }
    }

    fun resetText(resetsAt: Long?, now: Long): String = when {
        resetsAt == null -> "尚未開始計算"
        resetsAt <= now -> "即將重置"
        else -> "${duration(resetsAt - now)}後重置"
    }

    fun duration(millis: Long): String {
        val totalMinutes = (millis + 59_999) / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}天${hours}小時" else "${days}天"
            hours > 0 -> if (minutes > 0) "${hours}小時${minutes}分" else "${hours}小時"
            else -> "${minutes.coerceAtLeast(1)}分鐘"
        }
    }

    /** "14:30" today, "9/24 14:30" otherwise. */
    fun clock(epochMillis: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (time.toLocalDate() == today) timeFormat.format(time) else dateTimeFormat.format(time)
    }

    fun updatedText(fetchedAt: Long, now: Long): String =
        if (fetchedAt <= 0) "尚未更新" else "更新於 ${clock(fetchedAt, now)}"
}
