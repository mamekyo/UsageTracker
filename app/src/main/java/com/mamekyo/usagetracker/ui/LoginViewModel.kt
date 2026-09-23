package com.mamekyo.usagetracker.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.Credentials
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.domain.UsageRepository
import com.mamekyo.usagetracker.i18n.Locales
import com.mamekyo.usagetracker.net.ClaudeApi
import com.mamekyo.usagetracker.net.LoginException
import com.mamekyo.usagetracker.net.LoopbackServer
import com.mamekyo.usagetracker.net.OpenAiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/** Drives one add-account flow at a time. Text is resolved by the UI so it follows the current language. */
class LoginViewModel(app: Application) : AndroidViewModel(app) {
    sealed interface Status {
        data object Idle : Status
        data class Waiting(@param:StringRes val message: Int) : Status
        data class Working(@param:StringRes val message: Int) : Status
        data class DeviceCode(val code: String, val url: String) : Status
        data class Success(val account: Account) : Status
        data class Failed(val error: Throwable) : Status
    }

    private val store = Store.get(app)
    private val repository = UsageRepository.get(app)
    private val _status = MutableStateFlow<Status>(Status.Idle)
    private val _authUrl = MutableStateFlow<String?>(null)
    private var claudeLogin: ClaudeApi.LoginRequest? = null
    private var openAiLogin: OpenAiApi.LoginRequest? = null
    private var server: LoopbackServer? = null
    private var job: Job? = null

    val status: StateFlow<Status> = _status.asStateFlow()

    /** Authorization URL of the flow in progress, for copying into another browser. */
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    fun reset() {
        job?.cancel()
        job = null
        server?.close()
        server = null
        claudeLogin = null
        openAiLogin = null
        _authUrl.value = null
        _status.value = Status.Idle
    }

    /**
     * Starts a loopback listener on a free port so Claude's redirect adds the account automatically.
     * If no port can be bound, falls back to Claude's copy/paste code page.
     */
    fun startClaude(): String {
        reset()
        val listener = LoopbackServer(0, ClaudeApi.CALLBACK_PATH, pages())
        val port = try {
            listener.start()
        } catch (e: IOException) {
            null
        }
        val login = ClaudeApi.newLogin(port)
        claudeLogin = login
        _authUrl.value = login.url
        if (port == null) {
            _status.value = Status.Waiting(R.string.login_waiting_manual_claude)
        } else {
            server = listener
            launchFlow(Status.Waiting(R.string.login_waiting_auto_claude)) {
                finishClaudeLogin(login, listener.awaitCode(login.state))
            }
        }
        return login.url
    }

    /** Fallback: the user pastes the callback URL from the address bar, or a code shown by Claude. */
    fun submitClaudeCode(input: String) {
        val login = claudeLogin ?: return notStarted()
        server?.close()
        launchFlow(Status.Working(R.string.login_verifying)) { finishClaudeLogin(login, input) }
    }

    private suspend fun finishClaudeLogin(login: ClaudeApi.LoginRequest, codeOrUrl: String): Account {
        _status.value = Status.Working(R.string.login_finishing)
        val result = ClaudeApi.exchangeCode(login, codeOrUrl)
        val profile = runCatching { ClaudeApi.fetchProfile(result.tokens.accessToken) }.getOrNull()
        val email = profile?.email ?: result.email
        return saveAccount(
            provider = Provider.CLAUDE,
            email = email,
            plan = profile?.plan,
            externalId = profile?.accountUuid ?: result.accountUuid ?: email,
            credentials = Credentials(result.tokens.accessToken, result.tokens.refreshToken, result.tokens.expiresAt),
        )
    }

    /** Starts the loopback listener and returns the URL to open, or null when the port is unavailable. */
    fun startOpenAiBrowser(): String? {
        reset()
        val login = OpenAiApi.newBrowserLogin()
        val listener = LoopbackServer(OpenAiApi.CALLBACK_PORT, OpenAiApi.CALLBACK_PATH, pages())
        try {
            listener.start()
        } catch (e: IOException) {
            _status.value = Status.Failed(e)
            return null
        }
        server = listener
        openAiLogin = login
        _authUrl.value = login.url
        launchFlow(Status.Waiting(R.string.login_waiting_openai)) {
            val code = listener.awaitCode(login.state)
            _status.value = Status.Working(R.string.login_finishing)
            finishOpenAiLogin(OpenAiApi.exchangeBrowserCode(code, login))
        }
        return login.url
    }

    /** Fallback when the browser could not reach the loopback listener: the user pastes the callback URL. */
    fun submitOpenAiCallback(input: String) {
        val login = openAiLogin ?: return notStarted()
        val code = try {
            OpenAiApi.parseCallbackUrl(input, login.state)
        } catch (e: IOException) {
            _status.value = Status.Failed(e)
            return
        }
        server?.close()
        launchFlow(Status.Working(R.string.login_finishing)) { finishOpenAiLogin(OpenAiApi.exchangeBrowserCode(code, login)) }
    }

    fun startOpenAiDevice() {
        reset()
        launchFlow(Status.Working(R.string.login_fetching_device_code)) {
            val device = OpenAiApi.requestDeviceCode()
            _status.value = Status.DeviceCode(device.userCode, device.verificationUrl)
            val login = OpenAiApi.completeDeviceLogin(device)
            _status.value = Status.Working(R.string.login_finishing)
            finishOpenAiLogin(login)
        }
    }

    private fun notStarted() {
        _status.value = Status.Failed(LoginException(LoginException.Reason.NOT_STARTED))
    }

    private suspend fun finishOpenAiLogin(login: OpenAiApi.Login): Account {
        val identity = login.identity
        return saveAccount(
            provider = Provider.OPENAI,
            email = identity.email,
            plan = identity.plan,
            externalId = listOfNotNull(identity.accountId, identity.userId ?: identity.email).joinToString("|").ifBlank { null },
            credentials = Credentials(
                accessToken = login.tokens.accessToken,
                refreshToken = login.tokens.refreshToken,
                expiresAt = login.tokens.expiresAt,
                chatgptAccountId = identity.accountId,
            ),
        )
    }

    private fun launchFlow(initial: Status, block: suspend () -> Account) {
        job?.cancel()
        job = viewModelScope.launch {
            _status.value = initial
            try {
                _status.value = Status.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status.Failed(e)
            }
        }
    }

    private suspend fun saveAccount(
        provider: Provider,
        email: String?,
        plan: String?,
        externalId: String?,
        credentials: Credentials,
    ): Account {
        val stored = store.upsertAccount(
            Account(
                id = UUID.randomUUID().toString(),
                provider = provider,
                email = email,
                plan = plan,
                externalId = externalId,
                createdAt = System.currentTimeMillis(),
            ),
            credentials,
        )
        _status.value = Status.Working(R.string.login_loading_usage)
        repository.refreshOne(stored)
        return store.state.value.accounts.firstOrNull { it.id == stored.id } ?: stored
    }

    /** Pages the browser shows after the redirect, in the app language. */
    private fun pages(): LoopbackServer.Pages {
        val c = Locales.wrap(getApplication())
        return LoopbackServer.Pages(
            successTitle = c.getString(R.string.page_success_title),
            successBody = c.getString(R.string.page_success_body),
            failedTitle = c.getString(R.string.page_failed_title),
            mismatchTitle = c.getString(R.string.page_mismatch_title),
            mismatchBody = c.getString(R.string.page_mismatch_body),
            missingCode = c.getString(R.string.page_missing_code),
            notFound = c.getString(R.string.page_not_found),
            returnLabel = c.getString(R.string.page_return),
        )
    }

    override fun onCleared() {
        job?.cancel()
        server?.close()
    }
}
