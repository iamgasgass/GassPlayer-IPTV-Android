package com.iamgasgass.gassplayer.services

import com.iamgasgass.gassplayer.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TraktService(
    @Suppress("unused") private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
) {
    data class DeviceCode(
        val code: String,
        val userCode: String,
        val verificationUrl: String,
        val expires: Int,
        val interval: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun post(
        path: String,
        body: String,
        token: String = "",
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url("https://api.trakt.tv$path")
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)

        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val request = requestBuilder
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Trakt HTTP ${response.code}: $responseBody")
            }
            responseBody
        }
    }

    suspend fun deviceCode(): DeviceCode {
        val body = buildJsonObject {
            put("client_id", clientId)
        }.toString()

        val result = json.parseToJsonElement(
            post("/oauth/device/code", body),
        ).jsonObject

        return DeviceCode(
            code = result.string("device_code"),
            userCode = result.string("user_code"),
            verificationUrl = result.string("verification_url"),
            expires = result.integer("expires_in"),
            interval = result.integer("interval"),
        )
    }

    suspend fun exchange(code: String): String {
        val body = buildJsonObject {
            put("code", code)
            put("client_id", clientId)
            put("client_secret", clientSecret)
        }.toString()

        return json.parseToJsonElement(
            post("/oauth/device/token", body),
        ).jsonObject.string("access_token")
    }

    suspend fun scrobble(
        token: String,
        title: String,
        progress: Double,
        state: String = "start",
    ) {
        val body = buildJsonObject {
            putJsonObject("movie") {
                put("title", title)
            }
            put("progress", progress.coerceIn(0.0, 100.0))
        }.toString()

        post("/scrobble/$state", body, token)
    }
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.integer(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: 0
