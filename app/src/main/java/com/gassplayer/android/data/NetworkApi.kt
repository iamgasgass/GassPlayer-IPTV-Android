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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (candidate in candidateUrls(url)) {
            try {
                val builder = Request.Builder().url(candidate).header("User-Agent", userAgent)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) return@withContext response.body.string()
                    lastError = IllegalStateException("HTTP ${response.code} from $candidate")
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Network request failed")
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        JsonStore.json.parseToJsonElement(getText(url, headers))

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        var lastError: Throwable? = null
        for (candidate in candidateUrls(url)) {
            try {
                val builder = Request.Builder().url(candidate).post(requestBody).header("User-Agent", userAgent)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) return@withContext JsonStore.json.parseToJsonElement(response.body.string())
                    lastError = IllegalStateException("HTTP ${response.code} from $candidate")
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Network request failed")
    }

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        var lastError: Throwable? = null
        for (candidate in candidateUrls(url)) {
            try {
                val builder = Request.Builder().url(candidate).post(body).header("User-Agent", userAgent)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) return@withContext JsonStore.json.parseToJsonElement(response.body.string())
                    lastError = IllegalStateException("HTTP ${response.code} from $candidate")
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Network request failed")
    }

    private fun candidateUrls(raw: String): List<String> {
        val normalized = normalizeUrl(raw)
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return listOf(normalized)
        val scheme = uri.scheme?.lowercase() ?: return listOf(normalized)
        val opposite = when (scheme) {
            "https" -> "http"
            "http" -> "https"
            else -> return listOf(normalized)
        }
        val fallbackPort = when {
            scheme == "https" && (uri.port == -1 || uri.port == 443) -> 80
            scheme == "http" && uri.port == 80 -> 443
            else -> uri.port
        }
        val swapped = runCatching {
            URI(opposite, uri.userInfo, uri.host, fallbackPort, uri.path, uri.query, uri.fragment).toString()
        }.getOrNull()
        return listOfNotNull(normalized, swapped).distinct()
    }

    private fun normalizeUrl(raw: String): String = runCatching {
        val uri = URI(raw.trim())
        val scheme = if (uri.scheme.isNullOrBlank()) "http" else uri.scheme
        URI(scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
    }.getOrDefault(raw.trim())
}
