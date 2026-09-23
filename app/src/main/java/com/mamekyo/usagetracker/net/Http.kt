package com.mamekyo.usagetracker.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, val body: String) : IOException("HTTP $code") {
    /** Best-effort human readable server message. */
    val serverMessage: String?
        get() = runCatching {
            val o = Http.json.parseToJsonElement(body).jsonObject
            val error = o["error"]
            (error as? JsonObject)?.str("message")
                ?: (error as? JsonPrimitive)?.contentOrNull?.let { e -> o.str("error_description")?.let { "$e: $it" } ?: e }
                ?: o.str("detail")
                ?: o.str("message")
        }.getOrNull() ?: body.take(160).takeIf { it.isNotBlank() }
}

object Http {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Executes the request; non-2xx responses throw [HttpException]. */
    suspend fun call(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw HttpException(it.code, body)
                        body
                    }
                }
                result.fold(cont::resume, cont::resumeWithException)
            }
        })
    }

    suspend fun callJson(request: Request): JsonObject {
        val text = call(request)
        return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
    }

    fun jsonBody(vararg fields: Pair<String, String>): RequestBody =
        JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }).toString().toRequestBody(JSON_MEDIA)

    fun formBody(vararg fields: Pair<String, String>): RequestBody =
        FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let {
    it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.trim()?.toLongOrNull()
}

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** Parses an ISO-8601 timestamp or an epoch value (seconds or millis) into epoch millis. */
fun parseInstant(element: JsonElement?): Long? {
    val p = element as? JsonPrimitive ?: return null
    p.longOrNull?.let { return if (it < 100_000_000_000L) it * 1000 else it }
    p.doubleOrNull?.let { return (if (it < 1e11) it * 1000 else it).toLong() }
    val text = p.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
