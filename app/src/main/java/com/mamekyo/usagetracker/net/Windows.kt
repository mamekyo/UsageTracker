package com.mamekyo.usagetracker.net

/** Shared window keys/labels so windows from different accounts of a provider line up for merging. */
object Windows {
    const val FIVE_HOUR = "five_hour"
    const val WEEKLY = "seven_day"
    const val FIVE_HOUR_SECONDS = 5L * 3600
    const val WEEK_SECONDS = 7L * 24 * 3600

    const val FIVE_HOUR_LABEL = "5 小時"
    const val WEEKLY_LABEL = "每週"

    /** Maps a window length to its canonical key, tolerating small server-side deviations. */
    fun keyForSeconds(seconds: Long?): String = when {
        seconds == null -> "window"
        seconds in 4 * 3600L..6 * 3600L -> FIVE_HOUR
        seconds in 6 * 24 * 3600L..8 * 24 * 3600L -> WEEKLY
        else -> "window_$seconds"
    }

    fun labelForSeconds(seconds: Long?): String = when (keyForSeconds(seconds)) {
        FIVE_HOUR -> FIVE_HOUR_LABEL
        WEEKLY -> WEEKLY_LABEL
        else -> when {
            seconds == null -> "用量"
            seconds % 86_400 == 0L -> "${seconds / 86_400} 天"
            seconds % 3600 == 0L -> "${seconds / 3600} 小時"
            else -> "${seconds / 60} 分鐘"
        }
    }

    fun orderForKey(key: String): Int = when (key) {
        FIVE_HOUR -> 0
        WEEKLY -> 1
        else -> 2
    }

    /** "weekly_scoped" / "oauth_apps" -> "Weekly Scoped" / "Oauth Apps". */
    fun humanize(token: String): String =
        token.split('_', ' ', '-').filter { it.isNotBlank() }.joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }
}
