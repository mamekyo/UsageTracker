package com.mamekyo.usagetracker.net

import com.mamekyo.usagetracker.data.UsageWindow
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/**
 * Claude subscription (Pro/Max) access through the same OAuth client Claude Code uses.
 * Usage comes from `GET /api/oauth/usage`, which powers Claude Code's `/usage` command.
 */
object ClaudeApi {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    // Values match Claude Code 2.1.280: loopback redirect on any port, or the copy/paste code page.
    private const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    const val CALLBACK_PATH = "/callback"
    private const val MANUAL_REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
    private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
    private const val SCOPES = "user:profile user:inference"
    private const val BETA = "oauth-2025-04-20"
    private const val API_VERSION = "2023-06-01"

    // Anthropic's edge answers unknown clients with HTTP 429 on both the token and usage endpoints.
    private const val USER_AGENT = "claude-cli/2.1.280 (external, cli)"
    private const val DEFAULT_EXPIRES_IN_SECONDS = 8L * 3600

    class LoginRequest(val url: String, val verifier: String, val state: String, val redirectUri: String)

    data class Profile(val email: String?, val plan: String?, val accountUuid: String?)

    data class Login(val tokens: TokenSet, val email: String?, val accountUuid: String?)

    /**
     * With [loopbackPort] the browser is redirected to `http://localhost:<port>/callback` so the app receives
     * the code automatically; without it Claude shows the code for the user to paste.
     */
    fun newLogin(loopbackPort: Int?): LoginRequest {
        val verifier = Pkce.verifier()
        val state = Pkce.randomToken(32)
        val redirectUri = loopbackPort?.let { "http://localhost:$it$CALLBACK_PATH" } ?: MANUAL_REDIRECT_URI
        val url = AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("code", "true")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()
            .toString()
        return LoginRequest(url, verifier, state, redirectUri)
    }

    /** Accepts the "code#state" string shown by the callback page, a bare code, or the full callback URL. */
    fun parseAuthorizationInput(input: String): Pair<String, String?> {
        val text = input.trim()
        text.toHttpUrlOrNull()?.let { url ->
            url.queryParameter("code")?.let { return it to url.queryParameter("state") }
        }
        val hash = text.indexOf('#')
        return if (hash >= 0) text.substring(0, hash).trim() to text.substring(hash + 1).trim() else text to null
    }

    suspend fun exchangeCode(login: LoginRequest, input: String): Login {
        val (code, state) = parseAuthorizationInput(input)
        if (code.isBlank()) throw IOException("請貼上授權碼")
        if (state != null && state != login.state) {
            throw IOException("授權碼不屬於這次登入，請重新按「開啟登入頁面」再試一次")
        }
        val response = try {
            Http.callJson(
                tokenRequest(
                    Http.jsonBody(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "state" to login.state,
                        "client_id" to CLIENT_ID,
                        "redirect_uri" to login.redirectUri,
                        "code_verifier" to login.verifier,
                    ),
                ),
            )
        } catch (e: HttpException) {
            if (e.code == 400) throw IOException("授權碼無效或已過期，請重新開啟登入頁面取得新的授權碼（${e.serverMessage}）")
            throw e
        }
        val account = response.obj("account")
        return Login(
            tokens = tokenSet(response),
            email = account?.str("email_address") ?: account?.str("email"),
            accountUuid = account?.str("uuid"),
        )
    }

    suspend fun refresh(refreshToken: String): TokenSet = tokenSet(
        Http.callJson(
            tokenRequest(
                Http.jsonBody(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                ),
            ),
        ),
    )

    suspend fun fetchUsage(accessToken: String): JsonObject = Http.callJson(apiRequest(USAGE_URL, accessToken))

    suspend fun fetchProfile(accessToken: String): Profile {
        val o = Http.callJson(apiRequest(PROFILE_URL, accessToken))
        val account = o.obj("account")
        val org = o.obj("organization")
        val base = org?.str("organization_type")?.removePrefix("claude_")?.takeIf { it.isNotBlank() }?.let(Windows::humanize)
            ?: when {
                account?.bool("has_claude_max") == true -> "Max"
                account?.bool("has_claude_pro") == true -> "Pro"
                else -> null
            }
        val multiplier = org?.str("rate_limit_tier")?.let { Regex("(\\d+x)").find(it)?.value }
        return Profile(
            email = account?.str("email_address") ?: account?.str("email"),
            plan = base?.let { if (multiplier != null && !it.contains(multiplier)) "$it $multiplier" else it },
            accountUuid = account?.str("uuid"),
        )
    }

    /**
     * Normalises both response shapes: the legacy flat keys (`five_hour`, `seven_day`, `seven_day_<model>`)
     * and the newer self-describing `limits[]` array that carries model-scoped windows such as Fable.
     * `limits[]` wins for windows it describes; legacy keys fill gaps (notably missing `resets_at`).
     */
    fun parseUsage(o: JsonObject): List<UsageWindow> {
        val byKey = LinkedHashMap<String, UsageWindow>()

        fun legacy(key: String, label: String, seconds: Long, order: Int, source: JsonObject?) {
            val used = source?.double("utilization") ?: return
            byKey[key] = UsageWindow(key, label, used.coerceIn(0.0, 100.0), parseInstant(source["resets_at"]), seconds, order)
        }

        legacy(Windows.FIVE_HOUR, Windows.FIVE_HOUR_LABEL, Windows.FIVE_HOUR_SECONDS, 0, o.obj("five_hour"))
        legacy(Windows.WEEKLY, Windows.WEEKLY_LABEL, Windows.WEEK_SECONDS, 1, o.obj("seven_day"))
        for ((name, value) in o) {
            if (!name.startsWith("seven_day_") || value !is JsonObject) continue
            val model = modelName(name.removePrefix("seven_day_"))
            legacy(scopedKey(model), scopedLabel(model), Windows.WEEK_SECONDS, 2, value)
        }

        o.arr("limits")?.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val percent = entry.double("percent") ?: return@forEach
            val kind = entry.str("kind")
            val group = entry.str("group")
            val scope = entry.obj("scope")
            val model = scope?.obj("model")?.let { it.str("display_name") ?: it.str("id") }
            val surface = scope?.str("surface")

            var key: String
            var label: String
            val seconds: Long?
            val order: Int
            when {
                kind == "session" || (kind == null && group == "session" && model == null) -> {
                    key = Windows.FIVE_HOUR; label = Windows.FIVE_HOUR_LABEL; seconds = Windows.FIVE_HOUR_SECONDS; order = 0
                }
                kind == "weekly_all" || (kind == null && group == "weekly" && model == null) -> {
                    key = Windows.WEEKLY; label = Windows.WEEKLY_LABEL; seconds = Windows.WEEK_SECONDS; order = 1
                }
                kind == "weekly_scoped" || (group == "weekly" && model != null) -> {
                    val name = model ?: "特定模型"
                    key = scopedKey(name); label = scopedLabel(name); seconds = Windows.WEEK_SECONDS; order = 2
                }
                else -> {
                    val base = kind ?: group ?: "limit"
                    key = listOfNotNull(base, model?.lowercase()).joinToString(":")
                    label = listOfNotNull(Windows.humanize(base), model).joinToString(" · ")
                    seconds = when (group) {
                        "session" -> Windows.FIVE_HOUR_SECONDS
                        "weekly" -> Windows.WEEK_SECONDS
                        else -> null
                    }
                    order = 3
                }
            }
            if (!surface.isNullOrBlank()) {
                key += "@$surface"
                label += " · ${Windows.humanize(surface)}"
            }
            val resetsAt = parseInstant(entry["resets_at"]) ?: byKey[key]?.resetsAt
            byKey[key] = UsageWindow(key, label, percent.coerceIn(0.0, 100.0), resetsAt, seconds, order)
        }

        return byKey.values.sortedWith(compareBy<UsageWindow> { it.order }.thenBy { it.label })
    }

    private fun modelName(token: String): String = when (token.lowercase()) {
        "opus" -> "Opus"
        "sonnet" -> "Sonnet"
        "haiku" -> "Haiku"
        "fable" -> "Fable"
        "oauth_apps" -> "OAuth Apps"
        else -> Windows.humanize(token)
    }

    private fun scopedKey(model: String) = "${Windows.WEEKLY}:${model.lowercase()}"

    private fun scopedLabel(model: String) = "每週 · $model"

    private fun tokenRequest(body: okhttp3.RequestBody) = Request.Builder()
        .url(TOKEN_URL)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .post(body)
        .build()

    private fun apiRequest(url: String, accessToken: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .header("anthropic-beta", BETA)
        .header("anthropic-version", API_VERSION)
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    private fun tokenSet(o: JsonObject): TokenSet {
        val access = o.str("access_token") ?: throw IOException("登入回應缺少 access_token")
        val expiresIn = o.long("expires_in") ?: DEFAULT_EXPIRES_IN_SECONDS
        return TokenSet(
            accessToken = access,
            refreshToken = o.str("refresh_token"),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            raw = o,
        )
    }
}
