package com.mamekyo.usagetracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Provider(val displayName: String) {
    @SerialName("openai") OPENAI("OpenAI"),
    @SerialName("claude") CLAUDE("Claude"),
}

@Serializable
data class Account(
    val id: String,
    val provider: Provider,
    val email: String? = null,
    val nickname: String? = null,
    val plan: String? = null,
    /** Provider-side identity used to detect re-login of the same account. */
    val externalId: String? = null,
    val includeInMerge: Boolean = true,
    val needsReauth: Boolean = false,
    val createdAt: Long = 0,
) {
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: email ?: "${provider.displayName} 帳號"
}

/** Secrets; persisted only inside the Keystore-encrypted credentials file. */
@Serializable
data class Credentials(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch millis; null when unknown. */
    val expiresAt: Long? = null,
    /** OpenAI only: value for the ChatGPT-Account-Id header. */
    val chatgptAccountId: String? = null,
)

/**
 * One rate-limit window as reported by a provider.
 * [key] is stable across accounts of the same provider so windows can be merged.
 */
@Serializable
data class UsageWindow(
    val key: String,
    val label: String,
    val usedPercent: Double,
    /** Epoch millis of the next reset, when known. */
    val resetsAt: Long? = null,
    val windowSeconds: Long? = null,
    val order: Int = 0,
)

@Serializable
data class AccountUsage(
    val windows: List<UsageWindow> = emptyList(),
    val fetchedAt: Long = 0,
    val error: String? = null,
)

@Serializable
enum class DisplayMode {
    @SerialName("remaining") REMAINING,
    @SerialName("used") USED,
}

@Serializable
data class Settings(
    val displayMode: DisplayMode = DisplayMode.REMAINING,
    val refreshMinutes: Int = 15,
)

/** What a widget or an alert rule looks at. */
@Serializable
sealed interface Source {
    /** Every provider, each merged across its accounts. Widgets only. */
    @Serializable
    @SerialName("all")
    data object All : Source

    @Serializable
    @SerialName("merged")
    data class Merged(val provider: Provider) : Source

    @Serializable
    @SerialName("account")
    data class Single(val accountId: String) : Source
}

@Serializable
data class AlertRule(
    val id: String,
    val source: Source,
    /** Null means every window of the source. */
    val windowKey: String? = null,
    /** Notify when the remaining percentage drops to this value or below. */
    val thresholdRemaining: Int,
    val enabled: Boolean = true,
)

@Serializable
data class AppState(
    val accounts: List<Account> = emptyList(),
    val usage: Map<String, AccountUsage> = emptyMap(),
    val settings: Settings = Settings(),
    val rules: List<AlertRule> = emptyList(),
    /** "ruleId|windowKey" entries already notified; re-armed once usage recovers above the threshold. */
    val firedAlerts: Set<String> = emptySet(),
    /** appWidgetId -> source shown by that widget. */
    val widgets: Map<Int, Source> = emptyMap(),
    val lastRefreshAt: Long = 0,
)
