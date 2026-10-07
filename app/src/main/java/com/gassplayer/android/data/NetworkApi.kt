package com.gassplayer.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

class NetworkApi {
    @Volatile var userAgent: String = "GassPlayer/Android/1.0"
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(normalizeUrl(url)).header("User-Agent", userAgent)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}"); response.body.string() }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement = JsonStore.json.parseToJsonElement(getText(url, headers))

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(normalizeUrl(url)).post(requestBody).header("User-Agent", userAgent)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}"); JsonStore.json.parseToJsonElement(response.body.string()) }
    }

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val request = Request.Builder().url(normalizeUrl(url)).post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType())).header("User-Agent", userAgent).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}"); JsonStore.json.parseToJsonElement(response.body.string()) }
    }

    private fun normalizeUrl(raw: String): String = runCatching { val uri = URI(raw.trim()); val scheme = if (uri.scheme.isNullOrBlank()) "http" else uri.scheme; URI(scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString() }.getOrDefault(raw.trim())
}
