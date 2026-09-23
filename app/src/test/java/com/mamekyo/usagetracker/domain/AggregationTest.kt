package com.mamekyo.usagetracker.domain

import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AccountUsage
import com.mamekyo.usagetracker.data.AlertRule
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.data.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregationTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L

    private fun window(key: String, used: Double, resetsAt: Long?) = UsageWindow(key, key, used, resetsAt)

    private fun state(vararg accounts: Pair<Account, List<UsageWindow>>, rules: List<AlertRule> = emptyList()) = AppState(
        accounts = accounts.map { it.first },
        usage = accounts.associate { (account, windows) -> account.id to AccountUsage(windows, fetchedAt = now) },
        rules = rules,
    )

    private val a = Account("a", Provider.CLAUDE, email = "a@x")
    private val b = Account("b", Provider.CLAUDE, email = "b@x")

    @Test
    fun mergeAveragesAccountsAndShowsSoonestReset() {
        val s = state(
            a to listOf(window("five_hour", 100.0, now + 2 * hour)),
            b to listOf(window("five_hour", 0.0, now + 4 * hour)),
        )

        val merged = Aggregator.mergedView(Provider.CLAUDE, s, now)!!.windows.single()

        // One account exhausted, the other untouched: 50% remaining.
        assertEquals(50.0, merged.usedPercent, 0.0)
        assertEquals(50, Format.shownPercent(merged.usedPercent, DisplayMode.REMAINING))
        assertEquals(now + 2 * hour, merged.resetsAt)
        assertEquals(2, merged.accountCount)
    }

    @Test
    fun excludedAccountsDoNotCountTowardsMerge() {
        val s = state(
            a to listOf(window("five_hour", 80.0, null)),
            b.copy(includeInMerge = false) to listOf(window("five_hour", 0.0, null)),
        )

        assertEquals(80.0, Aggregator.mergedView(Provider.CLAUDE, s, now)!!.windows.single().usedPercent, 0.0)
    }

    @Test
    fun windowWhoseResetPassedCountsAsUnused() {
        val s = state(
            a to listOf(window("five_hour", 100.0, now - 1)),
            b to listOf(window("five_hour", 50.0, now + hour)),
        )

        val merged = Aggregator.mergedView(Provider.CLAUDE, s, now)!!.windows.single()

        assertEquals(25.0, merged.usedPercent, 0.0)
        assertEquals(now + hour, merged.resetsAt)
    }

    @Test
    fun modelScopedWindowAveragesOnlyAccountsReportingIt() {
        val s = state(
            a to listOf(window("five_hour", 10.0, null), window("seven_day:fable", 100.0, null)),
            b to listOf(window("five_hour", 30.0, null)),
        )

        val windows = Aggregator.mergedView(Provider.CLAUDE, s, now)!!.windows.associateBy { it.key }

        assertEquals(20.0, windows.getValue("five_hour").usedPercent, 0.0)
        assertEquals(100.0, windows.getValue("seven_day:fable").usedPercent, 0.0)
        assertEquals(1, windows.getValue("seven_day:fable").accountCount)
    }

    @Test
    fun alertFiresOnceAndRearmsAfterRecovery() {
        val rule = AlertRule("r", Source.Merged(Provider.CLAUDE), windowKey = "five_hour", thresholdRemaining = 20)
        val low = state(a to listOf(window("five_hour", 85.0, now + hour)), rules = listOf(rule))

        val (first, belowFirst) = Alerts.evaluate(low, now)
        assertEquals(listOf("r|five_hour"), first.map { it.firedKey })

        // Still low on the next check: no duplicate notification.
        val (second, belowSecond) = Alerts.evaluate(low.copy(firedAlerts = belowFirst), now)
        assertTrue(second.isEmpty())
        assertEquals(setOf("r|five_hour"), belowSecond)

        // After the window resets the key is dropped, so the next dip notifies again.
        val (_, afterReset) = Alerts.evaluate(low.copy(firedAlerts = belowSecond), now + 2 * hour)
        assertTrue(afterReset.isEmpty())
    }

    @Test
    fun disabledOrOtherWindowRulesDoNotFire() {
        val disabled = AlertRule("d", Source.Single("a"), thresholdRemaining = 50, enabled = false)
        val otherWindow = AlertRule("o", Source.Single("a"), windowKey = "seven_day", thresholdRemaining = 50)
        val s = state(a to listOf(window("five_hour", 90.0, null)), rules = listOf(disabled, otherWindow))

        assertTrue(Alerts.evaluate(s, now).first.isEmpty())
    }

    @Test
    fun durationFormatting() {
        assertEquals("2小時15分", Format.duration(2 * hour + 15 * 60_000))
        assertEquals("3天4小時", Format.duration(76 * hour))
        assertEquals("1分鐘", Format.duration(10_000))
        assertEquals("3天19時後重置", Format.resetText(now + 91 * hour, now, short = true))
        assertNull(Aggregator.mergedView(Provider.OPENAI, AppState(), now))
    }
}
