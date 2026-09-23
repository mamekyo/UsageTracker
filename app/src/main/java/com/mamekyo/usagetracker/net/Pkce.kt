package com.mamekyo.usagetracker.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun randomToken(bytes: Int = 32): String = encoder.encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun verifier(): String = randomToken(32)

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

object Jwt {
    /** Decodes the (unverified) payload of a JWT; tokens come straight from the issuer over TLS. */
    fun payload(token: String?): JsonObject? {
        val part = token?.split('.')?.getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(part.trimEnd('='))
            Http.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }.getOrNull()
    }

    /** `exp` claim in epoch millis. */
    fun expiresAt(token: String?): Long? = payload(token)?.long("exp")?.times(1000)
}

/** Token endpoint result shared by both providers. */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val idToken: String? = null,
    val raw: JsonObject,
)
