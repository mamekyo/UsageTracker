package com.mamekyo.usagetracker.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.domain.Format
import com.mamekyo.usagetracker.domain.ReauthRequiredException
import com.mamekyo.usagetracker.domain.UsageView
import com.mamekyo.usagetracker.domain.WindowView
import com.mamekyo.usagetracker.net.HttpException
import com.mamekyo.usagetracker.net.LoginException
import com.mamekyo.usagetracker.net.Windows
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Builds user-facing text from domain data in the context's language. */
object Texts {
    private fun Context.plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        resources.getQuantityString(id, count, *args)

    fun accountName(c: Context, account: Account): String =
        account.customName ?: c.getString(R.string.account_default_name, account.provider.displayName)

    /** "5-hour", "Weekly · Fable", or the provider's own wording for windows of unknown length. */
    fun window(c: Context, window: WindowView): String {
        val seconds = window.windowSeconds ?: return window.fallbackLabel
        val canonical = Windows.keyForSeconds(seconds)
        val base = when (canonical) {
            Windows.FIVE_HOUR -> c.getString(R.string.window_five_hour)
            Windows.WEEKLY -> c.getString(R.string.window_weekly)
            else -> when {
                seconds % 86_400 == 0L -> c.plural(R.plurals.window_days, (seconds / 86_400).toInt(), seconds / 86_400)
                seconds % 3600 == 0L -> c.plural(R.plurals.window_hours, (seconds / 3600).toInt(), seconds / 3600)
                else -> c.plural(R.plurals.window_minutes, (seconds / 60).toInt(), seconds / 60)
            }
        }
        return when {
            window.scope != null -> "$base · ${window.scope}"
            window.key == canonical -> base
            // Scoped windows stored before scopes existed keep their saved label until the next refresh.
            else -> window.fallbackLabel
        }
    }

    fun viewTitle(c: Context, view: UsageView): String = when {
        view.merged && view.accountCount > 1 -> c.getString(R.string.merged_title, view.provider.displayName)
        view.merged -> view.provider.displayName
        else -> view.account?.let { accountName(c, it) } ?: view.provider.displayName
    }

    fun viewSubtitle(c: Context, view: UsageView): String? = when {
        view.merged && view.accountCount > 1 -> c.plural(R.plurals.account_count, view.accountCount, view.accountCount)
        view.merged -> view.account?.let { accountName(c, it) }
        else -> listOfNotNull(view.provider.displayName, view.account?.plan).joinToString(" ")
    }

    /** One-line title for tight spaces: "Claude · 2 accounts merged" or "Claude · me@example.com". */
    fun compactTitle(c: Context, view: UsageView): String =
        if (view.accountCount > 1) {
            c.plural(R.plurals.merged_short, view.accountCount, view.provider.displayName, view.accountCount)
        } else {
            listOfNotNull(view.provider.displayName, view.account?.let { accountName(c, it) }).joinToString(" · ")
        }

    fun viewError(c: Context, view: UsageView): String? = when {
        view.accountCount > 1 -> view.failingCount.takeIf { it > 0 }?.let { c.plural(R.plurals.accounts_failed, it, it) }
        view.needsReauth -> c.getString(R.string.error_reauth)
        else -> view.error
    }

    fun source(c: Context, source: Source, state: AppState): String = when (source) {
        Source.All -> c.getString(R.string.source_all)
        is Source.Merged -> c.getString(R.string.merged_title, source.provider.displayName)
        is Source.Single -> state.accounts.firstOrNull { it.id == source.accountId }
            ?.let { "${it.provider.displayName} · ${accountName(c, it)}" }
            ?: c.getString(R.string.source_deleted)
    }

    fun modeWord(c: Context, mode: DisplayMode): String =
        c.getString(if (mode == DisplayMode.REMAINING) R.string.mode_remaining else R.string.mode_used)

    /** "64% used" / "已使用 64%" for the value already converted by [Format.shownPercent]. */
    fun percent(c: Context, percent: Int, mode: DisplayMode): String =
        c.getString(if (mode == DisplayMode.REMAINING) R.string.percent_remaining else R.string.percent_used, percent)

    fun resetText(c: Context, resetsAt: Long?, now: Long, short: Boolean = false): String = when {
        resetsAt == null -> c.getString(if (short) R.string.reset_not_started_short else R.string.reset_not_started)
        resetsAt <= now -> c.getString(R.string.reset_soon)
        else -> c.getString(R.string.reset_in, duration(c, resetsAt - now, short))
    }

    fun duration(c: Context, millis: Long, short: Boolean = false): String {
        val (days, hours, minutes) = Format.durationParts(millis)
        return when {
            days > 0 && hours > 0 -> c.getString(if (short) R.string.duration_days_hours_short else R.string.duration_days_hours, days, hours)
            days > 0 -> c.getString(R.string.duration_days, days)
            hours > 0 && minutes > 0 ->
                c.getString(if (short) R.string.duration_hours_minutes_short else R.string.duration_hours_minutes, hours, minutes)
            hours > 0 -> c.getString(if (short) R.string.duration_hours_short else R.string.duration_hours, hours)
            else -> c.getString(if (short) R.string.duration_minutes_short else R.string.duration_minutes, minutes.coerceAtLeast(1))
        }
    }

    fun updated(c: Context, fetchedAt: Long, now: Long): String =
        if (fetchedAt <= 0) c.getString(R.string.not_updated) else c.getString(R.string.updated_at, Format.clock(fetchedAt, now))

    fun error(c: Context, e: Throwable): String = when (e) {
        is ReauthRequiredException -> c.getString(R.string.error_reauth)
        is LoginException -> loginError(c, e)
        is HttpException -> when (e.code) {
            429 -> c.getString(R.string.error_rate_limited)
            in 500..599 -> c.getString(R.string.error_server, e.code)
            else -> c.getString(R.string.error_http, e.code, e.serverMessage ?: c.getString(R.string.error_unknown))
        }
        is UnknownHostException, is ConnectException -> c.getString(R.string.error_offline)
        is SocketTimeoutException -> c.getString(R.string.error_timeout)
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: c.getString(R.string.error_network)
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun loginError(c: Context, e: LoginException): String {
        val detail = e.detail.orEmpty()
        return when (e.reason) {
            LoginException.Reason.CODE_MISSING -> c.getString(R.string.login_error_code_missing)
            LoginException.Reason.STATE_MISMATCH -> c.getString(R.string.login_error_state_mismatch)
            LoginException.Reason.CODE_INVALID -> c.getString(R.string.login_error_code_invalid, detail)
            LoginException.Reason.BAD_RESPONSE -> c.getString(R.string.login_error_bad_response, detail)
            LoginException.Reason.PORT_UNAVAILABLE -> c.getString(R.string.login_error_port, detail)
            LoginException.Reason.DENIED -> c.getString(R.string.login_error_denied, detail)
            LoginException.Reason.CALLBACK_URL_INVALID -> c.getString(R.string.login_error_callback_url)
            LoginException.Reason.CALLBACK_NO_CODE -> c.getString(R.string.login_error_callback_no_code)
            LoginException.Reason.DEVICE_DISABLED -> c.getString(R.string.login_error_device_disabled)
            LoginException.Reason.DEVICE_EXPIRED -> c.getString(R.string.login_error_device_expired)
            LoginException.Reason.NOT_STARTED -> c.getString(R.string.login_error_start_first)
        }
    }
}
