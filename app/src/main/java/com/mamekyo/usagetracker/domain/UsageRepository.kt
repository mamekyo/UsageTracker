package com.mamekyo.usagetracker.domain

import android.content.Context
import android.util.Log
import com.mamekyo.usagetracker.UsageTrackerApp
import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AccountUsage
import com.mamekyo.usagetracker.data.Credentials
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.data.UsageWindow
import com.mamekyo.usagetracker.i18n.Locales
import com.mamekyo.usagetracker.i18n.Texts
import com.mamekyo.usagetracker.net.ClaudeApi
import com.mamekyo.usagetracker.net.HttpException
import com.mamekyo.usagetracker.net.OpenAiApi
import com.mamekyo.usagetracker.widget.UsageWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ReauthRequiredException : IOException("reauthentication required")

/** Fetches usage for every account, keeping tokens fresh, then republishes widgets and alerts. */
class UsageRepository private constructor(private val context: Context) {
    private val store = Store.get(context)
    private val accountLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshMutex = Mutex()
    private val _refreshing = MutableStateFlow(false)

    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Shows the refreshing state right away, before the queued background refresh starts. If that refresh
     * never starts (e.g. the system defers it), the state is dropped after a timeout so widgets don't spin forever.
     */
    fun markRefreshRequested() {
        _refreshing.value = true
        UsageTrackerApp.scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (_refreshing.value && !refreshMutex.isLocked) {
                _refreshing.value = false
                runCatching { UsageWidget.updateAll(context) }
            }
        }
    }

    /** Refreshes all accounts. Concurrent callers wait for the in-flight refresh instead of starting another. */
    suspend fun refreshAll() {
        if (!refreshMutex.tryLock()) {
            refreshMutex.withLock { }
            return
        }
        try {
            _refreshing.value = true
            val accounts = store.state.value.accounts
            coroutineScope { accounts.forEach { launch { refreshAccount(it) } } }
            store.update { it.copy(lastRefreshAt = System.currentTimeMillis()) }
            // Clear the flag before republishing so widgets render the finished state.
            _refreshing.value = false
            publish()
        } finally {
            _refreshing.value = false
            refreshMutex.unlock()
        }
    }

    /** Refreshes one account (e.g. right after login) and republishes. */
    suspend fun refreshOne(account: Account) {
        refreshAccount(account)
        publish()
    }

    /** Pushes the current state to widgets and evaluates alert rules. */
    suspend fun publish() {
        runCatching { UsageWidget.updateAll(context) }.onFailure { Log.w(TAG, "Widget update failed", it) }
        runCatching { Alerts.evaluate(context) }.onFailure { Log.w(TAG, "Alert evaluation failed", it) }
    }

    private suspend fun refreshAccount(account: Account) {
        val now = System.currentTimeMillis()
        try {
            val (windows, plan) = authorized(account) { credentials -> fetch(account, credentials) }
            store.update { state ->
                if (state.accounts.none { it.id == account.id }) return@update state
                state.copy(
                    usage = state.usage + (account.id to AccountUsage(windows = windows, fetchedAt = now)),
                    accounts = state.accounts.map {
                        if (it.id == account.id) it.copy(plan = plan ?: it.plan, needsReauth = false) else it
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed for ${account.provider}", e)
            val reauth = e is ReauthRequiredException
            val message = Texts.error(Locales.wrap(context), e)
            store.update { state ->
                if (state.accounts.none { it.id == account.id }) return@update state
                val previous = state.usage[account.id] ?: AccountUsage()
                state.copy(
                    usage = state.usage + (account.id to previous.copy(error = message)),
                    accounts = if (reauth) {
                        state.accounts.map { if (it.id == account.id) it.copy(needsReauth = true) else it }
                    } else {
                        state.accounts
                    },
                )
            }
        }
    }

    private suspend fun fetch(account: Account, credentials: Credentials): Pair<List<UsageWindow>, String?> =
        when (account.provider) {
            Provider.CLAUDE -> {
                val windows = ClaudeApi.parseUsage(ClaudeApi.fetchUsage(credentials.accessToken))
                val plan = if (account.plan == null) {
                    runCatching { ClaudeApi.fetchProfile(credentials.accessToken).plan }.getOrNull()
                } else {
                    null
                }
                windows to plan
            }
            Provider.OPENAI -> OpenAiApi.parseUsage(OpenAiApi.fetchUsage(credentials.accessToken, credentials.chatgptAccountId))
        }

    /** Runs [block] with a valid access token: refreshes ahead of expiry and once more on HTTP 401. */
    private suspend fun <T> authorized(account: Account, block: suspend (Credentials) -> T): T {
        val lock = accountLocks.getOrPut(account.id) { Mutex() }
        val credentials = lock.withLock {
            val current = store.credentials(account.id) ?: throw ReauthRequiredException()
            val expiresAt = current.expiresAt
            if (expiresAt != null && expiresAt - System.currentTimeMillis() < REFRESH_SKEW_MS) {
                renew(account, current)
            } else {
                current
            }
        }
        return try {
            block(credentials)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            val renewed = lock.withLock {
                val latest = store.credentials(account.id) ?: throw ReauthRequiredException()
                // Another caller may have renewed while we were waiting.
                if (latest.accessToken != credentials.accessToken) latest else renew(account, latest)
            }
            try {
                block(renewed)
            } catch (retry: HttpException) {
                if (retry.code == 401) throw ReauthRequiredException() else throw retry
            }
        }
    }

    private suspend fun renew(account: Account, current: Credentials): Credentials {
        val refreshToken = current.refreshToken ?: throw ReauthRequiredException()
        val tokens = try {
            when (account.provider) {
                Provider.CLAUDE -> ClaudeApi.refresh(refreshToken)
                Provider.OPENAI -> OpenAiApi.refresh(refreshToken)
            }
        } catch (e: HttpException) {
            // 400 invalid_grant / 401: the refresh token is expired, revoked or already used.
            if (e.code == 400 || e.code == 401 || e.code == 403) throw ReauthRequiredException() else throw e
        }
        val accountId = if (account.provider == Provider.OPENAI) {
            OpenAiApi.identity(tokens.idToken, tokens.accessToken).accountId
        } else {
            null
        }
        val updated = current.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: refreshToken,
            expiresAt = tokens.expiresAt,
            chatgptAccountId = accountId ?: current.chatgptAccountId,
        )
        store.saveCredentials(account.id, updated)
        return updated
    }

    companion object {
        private const val TAG = "UsageRepository"
        private const val REFRESH_SKEW_MS = 5 * 60 * 1000L
        private const val REQUEST_TIMEOUT_MS = 2 * 60 * 1000L

        @Volatile
        private var instance: UsageRepository? = null

        fun get(context: Context): UsageRepository =
            instance ?: synchronized(this) {
                instance ?: UsageRepository(context.applicationContext).also { instance = it }
            }
    }
}
