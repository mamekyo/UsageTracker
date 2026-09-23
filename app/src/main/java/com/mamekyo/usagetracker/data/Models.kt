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
    /** User nickname, else email; null when neither is known (the UI then shows a generic name). */
    val customName: String?
        get() = nickname?.takeIf { it.isNotBlank() } ?: email
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
 * Display text is built from [windowSeconds] and [scope] in the UI language; [label] is a
 * language-neutral fallback for windows whose length is unknown.
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
    /** Model or limit name the window is restricted to, e.g. "Fable". */
    val scope: String? = null,
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
    /** BCP-47 tag chosen in the app; empty follows the system. Used before Android 13 only. */
    val language: String = "",
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
enum class WidgetStyle {
    /** One row per window: label, reset time, percentage and a progress bar. */
    @SerialName("bars") BARS,

    /** Ring gauges side by side, one row per provider. */
    @SerialName("rings") RINGS,
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
    /** appWidgetId -> visual style; widgets without an entry use bars. Kept separate so older state files still load. */
    val widgetStyles: Map<Int, WidgetStyle> = emptyMap(),
    val lastRefreshAt: Long = 0,
)
