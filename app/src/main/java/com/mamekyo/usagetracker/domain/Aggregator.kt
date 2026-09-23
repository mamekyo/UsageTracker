package com.mamekyo.usagetracker.domain

import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AccountUsage
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.data.UsageWindow

/** A window ready for display; text is produced by the UI layer from these fields. */
data class WindowView(
    val key: String,
    val usedPercent: Double,
    val resetsAt: Long?,
    val order: Int,
    /** Accounts contributing to this window (1 for a single account). */
    val accountCount: Int = 1,
    val windowSeconds: Long? = null,
    val scope: String? = null,
    val fallbackLabel: String = key,
) {
    val remainingPercent: Double get() = 100.0 - usedPercent
}

data class UsageView(
    val provider: Provider,
    /** The account shown when the view covers exactly one account. */
    val account: Account?,
    /** Built as a provider merge rather than for a specific account. */
    val merged: Boolean,
    val accountCount: Int,
    val windows: List<WindowView>,
    /** Oldest successful fetch among contributing accounts; 0 when never fetched. */
    val fetchedAt: Long,
    /** Last fetch error of the single account shown. */
    val error: String?,
    /** Accounts whose last update failed or that need a new login. */
    val failingCount: Int,
    val needsReauth: Boolean,
)

object Aggregator {
    /** A window whose reset time has passed is treated as fresh (0% used) until the next fetch. */
    fun effective(window: UsageWindow, now: Long): UsageWindow =
        if (window.resetsAt != null && window.resetsAt <= now) window.copy(usedPercent = 0.0, resetsAt = null) else window

    private fun UsageWindow.toView(usedPercent: Double = this.usedPercent, resetsAt: Long? = this.resetsAt, accountCount: Int = 1) =
        WindowView(key, usedPercent, resetsAt, order, accountCount, windowSeconds, scope, label)

    private val windowOrder = compareBy<WindowView> { it.order }.thenBy { it.key }

    fun accountView(account: Account, usage: AccountUsage?, now: Long, merged: Boolean = false): UsageView = UsageView(
        provider = account.provider,
        account = account,
        merged = merged,
        accountCount = 1,
        windows = usage?.windows.orEmpty().map { effective(it, now).toView() }.sortedWith(windowOrder),
        fetchedAt = usage?.fetchedAt ?: 0,
        error = usage?.error,
        failingCount = if (account.needsReauth || usage?.error != null) 1 else 0,
        needsReauth = account.needsReauth,
    )

    /**
     * Merges every account of [provider] that is included in merging. Each window is the average of the
     * accounts reporting it (one account exhausted + one untouched = 50%), and the reset shown is the
     * soonest upcoming reset among them.
     */
    fun mergedView(provider: Provider, state: AppState, now: Long): UsageView? {
        val accounts = state.accounts.filter { it.provider == provider && it.includeInMerge }
        if (accounts.isEmpty()) return null
        if (accounts.size == 1) {
            val single = accounts.first()
            return accountView(single, state.usage[single.id], now, merged = true)
        }
        val reporting = accounts.mapNotNull { account ->
            state.usage[account.id]?.takeIf { it.windows.isNotEmpty() }
        }
        val grouped = LinkedHashMap<String, MutableList<UsageWindow>>()
        reporting.forEach { usage ->
            usage.windows.forEach { grouped.getOrPut(it.key) { mutableListOf() } += effective(it, now) }
        }
        val windows = grouped.values.map { list ->
            list.first().toView(
                usedPercent = list.sumOf { it.usedPercent } / list.size,
                resetsAt = list.mapNotNull { it.resetsAt }.minOrNull(),
                accountCount = list.size,
            )
        }.sortedWith(windowOrder)

        return UsageView(
            provider = provider,
            account = null,
            merged = true,
            accountCount = accounts.size,
            windows = windows,
            fetchedAt = reporting.minOfOrNull { it.fetchedAt } ?: 0,
            error = null,
            failingCount = accounts.count { it.needsReauth || state.usage[it.id]?.error != null },
            needsReauth = accounts.all { it.needsReauth },
        )
    }

    fun views(source: Source, state: AppState, now: Long): List<UsageView> = when (source) {
        Source.All -> Provider.entries.mapNotNull { mergedView(it, state, now) }
        is Source.Merged -> listOfNotNull(mergedView(source.provider, state, now))
        is Source.Single -> state.accounts.firstOrNull { it.id == source.accountId }
            ?.let { listOf(accountView(it, state.usage[it.id], now)) }
            .orEmpty()
    }
}
