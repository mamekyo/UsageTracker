package com.mamekyo.usagetracker.domain

import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AccountUsage
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.data.UsageWindow

data class WindowView(
    val key: String,
    val label: String,
    val usedPercent: Double,
    val resetsAt: Long?,
    val order: Int,
    /** Accounts contributing to this window (1 for a single account). */
    val accountCount: Int = 1,
) {
    val remainingPercent: Double get() = 100.0 - usedPercent
}

data class UsageView(
    val title: String,
    val subtitle: String?,
    val provider: Provider,
    val windows: List<WindowView>,
    /** Oldest successful fetch among contributing accounts; 0 when never fetched. */
    val fetchedAt: Long,
    val error: String?,
    val needsReauth: Boolean,
    val accountCount: Int,
)

object Aggregator {
    /** A window whose reset time has passed is treated as fresh (0% used) until the next fetch. */
    fun effective(window: UsageWindow, now: Long): UsageWindow =
        if (window.resetsAt != null && window.resetsAt <= now) window.copy(usedPercent = 0.0, resetsAt = null) else window

    fun accountView(account: Account, usage: AccountUsage?, now: Long): UsageView = UsageView(
        title = account.displayName,
        subtitle = account.plan?.let { "${account.provider.displayName} $it" } ?: account.provider.displayName,
        provider = account.provider,
        windows = usage?.windows.orEmpty().map { effective(it, now) }
            .map { WindowView(it.key, it.label, it.usedPercent, it.resetsAt, it.order) },
        fetchedAt = usage?.fetchedAt ?: 0,
        error = if (account.needsReauth) "登入已失效，請重新登入" else usage?.error,
        needsReauth = account.needsReauth,
        accountCount = 1,
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
            return accountView(single, state.usage[single.id], now).copy(title = provider.displayName, subtitle = single.displayName)
        }
        val reporting = accounts.mapNotNull { account ->
            state.usage[account.id]?.takeIf { it.windows.isNotEmpty() }?.let { account to it }
        }
        val grouped = LinkedHashMap<String, MutableList<UsageWindow>>()
        reporting.forEach { (_, usage) ->
            usage.windows.forEach { grouped.getOrPut(it.key) { mutableListOf() } += effective(it, now) }
        }
        val windows = grouped.map { (key, list) ->
            WindowView(
                key = key,
                label = list.first().label,
                usedPercent = list.sumOf { it.usedPercent } / list.size,
                resetsAt = list.mapNotNull { it.resetsAt }.minOrNull(),
                order = list.first().order,
                accountCount = list.size,
            )
        }.sortedWith(compareBy<WindowView> { it.order }.thenBy { it.label })

        val failing = accounts.count { it.needsReauth || state.usage[it.id]?.error != null }
        return UsageView(
            title = "${provider.displayName}（合併）",
            subtitle = "${accounts.size} 個帳號",
            provider = provider,
            windows = windows,
            fetchedAt = reporting.minOfOrNull { it.second.fetchedAt } ?: 0,
            error = if (failing > 0) "$failing 個帳號更新失敗" else null,
            needsReauth = accounts.all { it.needsReauth },
            accountCount = accounts.size,
        )
    }

    fun views(source: Source, state: AppState, now: Long): List<UsageView> = when (source) {
        Source.All -> Provider.entries.mapNotNull { mergedView(it, state, now) }
        is Source.Merged -> listOfNotNull(mergedView(source.provider, state, now))
        is Source.Single -> state.accounts.firstOrNull { it.id == source.accountId }
            ?.let { listOf(accountView(it, state.usage[it.id], now)) }
            .orEmpty()
    }

    fun sourceLabel(source: Source, state: AppState): String = when (source) {
        Source.All -> "全部（各家合併）"
        is Source.Merged -> "${source.provider.displayName}（合併）"
        is Source.Single -> state.accounts.firstOrNull { it.id == source.accountId }
            ?.let { "${it.provider.displayName} · ${it.displayName}" } ?: "已刪除的帳號"
    }
}
