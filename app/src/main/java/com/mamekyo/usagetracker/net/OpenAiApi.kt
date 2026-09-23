package com.mamekyo.usagetracker.net

import com.mamekyo.usagetracker.data.UsageWindow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * ChatGPT subscription (Plus/Pro/...) access through the Codex CLI OAuth client.
 * Usage comes from `GET https://chatgpt.com/backend-api/wham/usage`, the endpoint behind Codex's `/status`.
 */
object OpenAiApi {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val ISSUER = "https://auth.openai.com"
    private const val TOKEN_URL = "$ISSUER/oauth/token"
    const val CALLBACK_PORT = 1455
    const val CALLBACK_PATH = "/auth/callback"
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
    private const val DEVICE_REDIRECT_URI = "$ISSUER/deviceauth/callback"
    private const val DEVICE_API = "$ISSUER/api/accounts/deviceauth"
    const val DEVICE_VERIFY_URL = "$ISSUER/codex/device"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    private const val ORIGINATOR = "codex_cli_rs"
    private const val USER_AGENT = "codex_cli_rs/0.156.1 (Android; aarch64) UsageTracker"
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private const val PROFILE_CLAIM = "https://api.openai.com/profile"

    class LoginRequest(val url: String, val verifier: String, val state: String)

    class DeviceCode(val userCode: String, val deviceAuthId: String, val intervalSeconds: Long, val verificationUrl: String)

    data class Identity(val email: String?, val plan: String?, val accountId: String?, val userId: String?)

    data class Login(val tokens: TokenSet, val identity: Identity)

    fun newBrowserLogin(): LoginRequest {
        val verifier = Pkce.randomToken(64)
        val state = Pkce.randomToken(32)
        val url = "$ISSUER/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("originator", ORIGINATOR)
            .build()
            .toString()
        return LoginRequest(url, verifier, state)
    }

    /** Extracts `code` from a pasted callback URL (`http://localhost:1455/auth/callback?code=...&state=...`). */
    fun parseCallbackUrl(input: String, expectedState: String): String {
        val url = input.trim().toHttpUrlOrNull() ?: throw LoginException(LoginException.Reason.CALLBACK_URL_INVALID)
        url.queryParameter("error")?.let { throw LoginException(LoginException.Reason.DENIED, url.queryParameter("error_description") ?: it) }
        val code = url.queryParameter("code") ?: throw LoginException(LoginException.Reason.CALLBACK_NO_CODE)
        if (url.queryParameter("state") != expectedState) throw LoginException(LoginException.Reason.STATE_MISMATCH)
        return code
    }

    suspend fun exchangeBrowserCode(code: String, login: LoginRequest): Login =
        exchangeCode(code, login.verifier, REDIRECT_URI)

    suspend fun requestDeviceCode(): DeviceCode {
        val o = try {
            Http.callJson(
                Request.Builder().url("$DEVICE_API/usercode")
                    .header("User-Agent", USER_AGENT)
                    .post(Http.jsonBody("client_id" to CLIENT_ID))
                    .build(),
            )
        } catch (e: HttpException) {
            if (e.code == 404) throw LoginException(LoginException.Reason.DEVICE_DISABLED) else throw e
        }
        return DeviceCode(
            userCode = o.str("user_code") ?: o.str("usercode") ?: throw LoginException(LoginException.Reason.BAD_RESPONSE, "user_code"),
            deviceAuthId = o.str("device_auth_id") ?: throw LoginException(LoginException.Reason.BAD_RESPONSE, "device_auth_id"),
            intervalSeconds = (o.long("interval") ?: 5L).coerceIn(2L, 30L),
            verificationUrl = DEVICE_VERIFY_URL,
        )
    }

    /** Polls until the user approves the code (up to 15 minutes), then exchanges it for tokens. */
    suspend fun completeDeviceLogin(device: DeviceCode): Login {
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
        while (true) {
            try {
                val o = Http.callJson(
                    Request.Builder().url("$DEVICE_API/token")
                        .header("User-Agent", USER_AGENT)
                        .post(Http.jsonBody("device_auth_id" to device.deviceAuthId, "user_code" to device.userCode))
                        .build(),
                )
                val code = o.str("authorization_code") ?: throw LoginException(LoginException.Reason.BAD_RESPONSE, "authorization_code")
                val verifier = o.str("code_verifier") ?: throw LoginException(LoginException.Reason.BAD_RESPONSE, "code_verifier")
                return exchangeCode(code, verifier, DEVICE_REDIRECT_URI)
            } catch (e: HttpException) {
                if (e.code != 403 && e.code != 404) throw e
            }
            if (System.currentTimeMillis() > deadline) throw LoginException(LoginException.Reason.DEVICE_EXPIRED)
            delay(device.intervalSeconds * 1000)
        }
    }

    suspend fun refresh(refreshToken: String): TokenSet = tokenSet(
        Http.callJson(
            Request.Builder().url(TOKEN_URL)
                .header("User-Agent", USER_AGENT)
                .post(
                    Http.jsonBody(
                        "client_id" to CLIENT_ID,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                    ),
                )
                .build(),
        ),
    )

    fun identity(idToken: String?, accessToken: String?): Identity {
        val id = Jwt.payload(idToken)
        val access = Jwt.payload(accessToken)
        val auth = id?.obj(AUTH_CLAIM) ?: access?.obj(AUTH_CLAIM)
        return Identity(
            email = id?.str("email") ?: id?.obj(PROFILE_CLAIM)?.str("email") ?: access?.obj(PROFILE_CLAIM)?.str("email"),
            plan = auth?.str("chatgpt_plan_type")?.let(::planLabel),
            accountId = auth?.str("chatgpt_account_id") ?: access?.obj(AUTH_CLAIM)?.str("chatgpt_account_id"),
            userId = auth?.str("chatgpt_user_id") ?: auth?.str("user_id"),
        )
    }

    suspend fun fetchUsage(accessToken: String, accountId: String?): JsonObject {
        val builder = Request.Builder().url(USAGE_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("originator", ORIGINATOR)
        if (!accountId.isNullOrBlank()) builder.header("ChatGPT-Account-Id", accountId)
        return Http.callJson(builder.get().build())
    }

    /** Returns the windows plus the plan label reported by the usage endpoint. */
    fun parseUsage(o: JsonObject, now: Long = System.currentTimeMillis()): Pair<List<UsageWindow>, String?> {
        val windows = mutableListOf<UsageWindow>()

        fun addWindows(limit: JsonObject?, name: String?) {
            listOfNotNull(limit?.obj("primary_window"), limit?.obj("secondary_window")).forEach { w ->
                val used = w.double("used_percent") ?: return@forEach
                val seconds = w.long("limit_window_seconds")?.takeIf { it > 0 }
                val resetsAt = w.long("reset_at")?.takeIf { it > 0 }?.times(1000)
                    ?: w.long("reset_after_seconds")?.let { now + it * 1000 }
                val baseKey = Windows.keyForSeconds(seconds)
                windows += UsageWindow(
                    key = if (name == null) baseKey else "$name|$baseKey",
                    label = listOfNotNull(Windows.fallbackLabel(seconds), name).joinToString(" · "),
                    usedPercent = used.coerceIn(0.0, 100.0),
                    resetsAt = resetsAt,
                    windowSeconds = seconds,
                    order = if (name == null) Windows.orderForKey(baseKey) else 3,
                    scope = name,
                )
            }
        }

        addWindows(o.obj("rate_limit"), null)
        o.arr("additional_rate_limits")?.forEach { element ->
            val extra = element as? JsonObject ?: return@forEach
            val name = extra.str("limit_name") ?: extra.str("metered_feature") ?: "extra"
            addWindows(extra.obj("rate_limit"), name)
        }
        val sorted = windows.distinctBy { it.key }.sortedWith(compareBy<UsageWindow> { it.order }.thenBy { it.key })
        return sorted to o.str("plan_type")?.let(::planLabel)
    }

    fun planLabel(raw: String): String = when (raw.lowercase()) {
        "prolite" -> "Pro Lite"
        "k12" -> "K-12"
        else -> Windows.humanize(raw)
    }

    private suspend fun exchangeCode(code: String, verifier: String, redirectUri: String): Login {
        val tokens = tokenSet(
            Http.callJson(
                Request.Builder().url(TOKEN_URL)
                    .header("User-Agent", USER_AGENT)
                    .post(
                        Http.formBody(
                            "grant_type" to "authorization_code",
                            "client_id" to CLIENT_ID,
                            "code" to code,
                            "redirect_uri" to redirectUri,
                            "code_verifier" to verifier,
                        ),
                    )
                    .build(),
            ),
        )
        return Login(tokens, identity(tokens.idToken, tokens.accessToken))
    }

    private fun tokenSet(o: JsonObject): TokenSet {
        val access = o.str("access_token") ?: throw LoginException(LoginException.Reason.BAD_RESPONSE, "access_token")
        val expiresAt = o.long("expires_in")?.let { System.currentTimeMillis() + it * 1000 } ?: Jwt.expiresAt(access)
        return TokenSet(
            accessToken = access,
            refreshToken = o.str("refresh_token"),
            expiresAt = expiresAt,
            idToken = o.str("id_token"),
            raw = o,
        )
    }
}
