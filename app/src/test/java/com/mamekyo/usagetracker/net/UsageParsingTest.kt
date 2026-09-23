package com.mamekyo.usagetracker.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class UsageParsingTest {
    private fun json(text: String): JsonObject = Http.json.parseToJsonElement(text).jsonObject

    private fun millis(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun claudeLimitsArrayAddsFableAndKeepsFlatResetTimes() {
        // Shape observed in September 2026: model-scoped weekly bucket only inside limits[].
        val usage = json(
            """
            {
              "five_hour":  { "utilization": 9.0,  "resets_at": "2026-09-14T02:10:00Z" },
              "seven_day":  { "utilization": 68.0, "resets_at": "2026-09-19T09:00:00.951713+00:00" },
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "limits": [
                { "kind": "session", "group": "session", "percent": 9, "severity": "normal", "is_active": false },
                { "kind": "weekly_all", "group": "weekly", "percent": 68, "severity": "normal", "is_active": false },
                { "kind": "weekly_scoped", "group": "weekly", "percent": 100, "severity": "critical", "is_active": true,
                  "resets_at": "2026-09-19T09:00:00Z", "scope": { "model": { "id": null, "display_name": "Fable" } } }
              ]
            }
            """,
        )

        val windows = ClaudeApi.parseUsage(usage)

        assertEquals(listOf("five_hour", "seven_day", "seven_day:fable"), windows.map { it.key })
        assertEquals(listOf(9.0, 68.0, 100.0), windows.map { it.usedPercent })
        assertEquals("每週 · Fable", windows[2].label)
        // limits[] entries without resets_at inherit the flat keys' reset time.
        assertEquals(millis("2026-09-14T02:10:00Z"), windows[0].resetsAt)
        assertEquals(millis("2026-09-19T09:00:00.951713+00:00"), windows[1].resetsAt)
        assertEquals(millis("2026-09-19T09:00:00Z"), windows[2].resetsAt)
    }

    @Test
    fun claudeLegacyShapeStillParses() {
        val usage = json(
            """
            {
              "five_hour": { "utilization": 33.0, "resets_at": "2026-04-11T07:00:00.528743+00:00" },
              "seven_day": { "utilization": 13.0, "resets_at": "2026-04-17T00:59:59.951713+00:00" },
              "seven_day_sonnet": { "utilization": 1.0, "resets_at": "2026-04-16T03:00:00.951719+00:00" },
              "seven_day_opus": null,
              "extra_usage": { "is_enabled": false }
            }
            """,
        )

        val windows = ClaudeApi.parseUsage(usage)

        assertEquals(listOf("five_hour", "seven_day", "seven_day:sonnet"), windows.map { it.key })
        // utilization is already a percentage: 1.0 means 1%, not 100%.
        assertEquals(1.0, windows[2].usedPercent, 0.0)
    }

    @Test
    fun claudeFreeTierReturnsNoWindows() {
        assertEquals(emptyList<Any>(), ClaudeApi.parseUsage(json("{}")))
    }

    @Test
    fun openAiPrimaryAndSecondaryWindowsMapToFiveHourAndWeekly() {
        val now = 1_700_000_000_000L
        val usage = json(
            """
            {
              "plan_type": "plus",
              "rate_limit": {
                "allowed": true, "limit_reached": false,
                "primary_window":   { "used_percent": 42, "limit_window_seconds": 18000,  "reset_after_seconds": 3600,   "reset_at": 1700003600 },
                "secondary_window": { "used_percent": 7,  "limit_window_seconds": 604800, "reset_after_seconds": 259200, "reset_at": 0 }
              },
              "additional_rate_limits": [
                { "limit_name": "GPT-5-Codex-Spark", "metered_feature": "codex_spark",
                  "rate_limit": { "allowed": true, "limit_reached": false,
                    "primary_window": { "used_percent": 100, "limit_window_seconds": 18000, "reset_after_seconds": 60, "reset_at": 1700000060 } } }
              ]
            }
            """,
        )

        val (windows, plan) = OpenAiApi.parseUsage(usage, now)

        assertEquals("Plus", plan)
        assertEquals(listOf("five_hour", "seven_day", "GPT-5-Codex-Spark|five_hour"), windows.map { it.key })
        assertEquals(42.0, windows[0].usedPercent, 0.0)
        assertEquals(1_700_003_600_000L, windows[0].resetsAt)
        // reset_at of 0 falls back to reset_after_seconds.
        assertEquals(now + 259_200_000L, windows[1].resetsAt)
        assertEquals("GPT-5-Codex-Spark · 5 小時", windows[2].label)
    }

    @Test
    fun claudeAuthorizationInputAcceptsCodeHashStateAndUrls() {
        assertEquals("abc" to "xyz", ClaudeApi.parseAuthorizationInput("  abc#xyz \n"))
        assertEquals("abc" to null, ClaudeApi.parseAuthorizationInput("abc"))
        assertEquals(
            "abc" to "xyz",
            ClaudeApi.parseAuthorizationInput("https://console.anthropic.com/oauth/code/callback?code=abc&state=xyz"),
        )
    }

    @Test(expected = java.io.IOException::class)
    fun openAiCallbackWithWrongStateIsRejected() {
        OpenAiApi.parseCallbackUrl("http://localhost:1455/auth/callback?code=abc&state=other", "expected")
    }

    @Test
    fun parseInstantHandlesEpochSecondsAndMillis() {
        assertEquals(1_700_000_000_000L, parseInstant(json("""{"t":1700000000}""")["t"]))
        assertEquals(1_700_000_000_000L, parseInstant(json("""{"t":1700000000000}""")["t"]))
        assertNull(parseInstant(json("""{"t":null}""")["t"]))
    }
}
