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
import java.util.Locale

class NetworkApi {
    @Volatile var userAgent: String = "GassPlayer/Android/1.0"

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(okhttp3.ConnectionPool(12, 5, TimeUnit.MINUTES))
        .build()

    data class TextResponse(val text: String, val finalUrl: String)

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        getTextResult(url, headers).text

    /** Fetches text and also exposes the final redirected URL, which is essential for resolving
     * relative M3U/M3U8 entry URLs against the actual playlist location. */
    suspend fun getTextResult(url: String, headers: Map<String, String> = emptyMap()): TextResponse = withContext(Dispatchers.IO) {
        val (requestUrl, inlineHeaders) = splitInlineHeaders(url)
        val requestHeaders = linkedMapOf<String, String>().apply {
            putAll(inlineHeaders)
            putAll(headers)
        }
        var lastError: Throwable? = null
        val uas = userAgentCandidates(requestHeaders)
        outer@ for (candidate in candidateUrls(requestUrl)) {
            for (ua in uas) {
                try {
                    val builder = Request.Builder()
                        .url(candidate)
                        .header("User-Agent", ua)
                        .header("Accept", requestHeaders["Accept"] ?: "*/*")
                        .header("Accept-Encoding", requestHeaders["Accept-Encoding"] ?: "identity")
                        .header("Cache-Control", requestHeaders["Cache-Control"] ?: "no-cache")
                    requestHeaders.forEach { (k, v) ->
                        if (!k.equals("User-Agent", true) && !k.equals("Accept", true) &&
                            !k.equals("Accept-Encoding", true) && !k.equals("Cache-Control", true)) builder.header(k, v)
                    }
                    client.newCall(builder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val text = response.body.string().removePrefix("\uFEFF")
                            val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                            if (isLikelyTextPayload(text, contentType, requestUrl)) {
                                return@withContext TextResponse(text, response.request.url.toString())
                            }
                            lastError = IllegalStateException("Risposta non testuale da $candidate")
                        } else {
                            lastError = IllegalStateException("HTTP ${response.code} from $candidate")
                        }
                    }
                } catch (t: Throwable) {
                    lastError = t
                }
                // A 200 HTML/login response may be a UA-specific block page. Try the next UA
                // before abandoning the candidate URL. If all UAs fail, continue with transport fallback.
            }
        }
        throw lastError ?: IllegalStateException("Network request failed")
    }

    private fun userAgentCandidates(headers: Map<String, String>): List<String> {
        val explicit = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.trim()
        return buildList {
            explicit?.takeIf { it.isNotBlank() }?.let(::add)
            add(userAgent)
            addAll(companionObjectUserAgents())
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun companionObjectUserAgents(): List<String> = listOf(
        "VLC/3.0.20 LibVLC/3.0.20",
        "Lavf/60.16.100",
        "IPTVSmartersPro",
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    )

    private fun isLikelyTextPayload(text: String, contentType: String, requestedUrl: String): Boolean {
        if (text.isBlank()) return false
        if (contentType.contains("json") || contentType.contains("mpegurl") || contentType.contains("text/") || contentType.contains("xml")) return true
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return true
        if (trimmed.startsWith("#EXT") || trimmed.startsWith("<?xml") || trimmed.contains("<tv")) return true
        val lowerUrl = requestedUrl.lowercase(Locale.ROOT)
        if (listOf(".m3u", ".m3u8", "player_api.php", "xmltv.php", "get.php").any { lowerUrl.contains(it) }) {
            val head = trimmed.take(4096).lowercase(Locale.ROOT)
            return head.contains("#extm3u") || head.contains("#extinf") || head.contains("#ext-x-") || head.contains("stream_id") || head.contains("user_info") || head.contains("<tv") || head.contains("<?xml")
        }
        // Generic JSON/text API payloads can be valid without a distinctive header.
        return !trimmed.startsWith("<html") && !trimmed.startsWith("<!doctype html")
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

    companion object {
        fun stripInlineHeaders(raw: String): String = splitInlineHeaders(raw).first
        fun looksLikePlaylist(text: String): Boolean {
            val t = text.trimStart()
            if (t.isBlank()) return false
            val head = t.take(16 * 1024).lowercase(Locale.ROOT)
            return head.contains("#extm3u") || head.contains("#extinf") || head.contains("#ext-x-") ||
                Regex("(?m)^(?:https?|rtmp|rtsp|rtsps|udp|srt)://").containsMatchIn(head)
        }

        fun looksLikeXmlTv(text: String): Boolean {
            val t = text.trimStart().lowercase(Locale.ROOT)
            return t.startsWith("<?xml") || t.startsWith("<tv") || t.contains("<channel") && t.contains("<programme")
        }


        fun candidateUrls(raw: String): List<String> {
            val normalized = normalizeUrl(raw)
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return listOf(normalized)
            val scheme = uri.scheme?.lowercase() ?: return listOf(normalized)
            if (scheme != "https" && scheme != "http") return listOf(normalized)

            val result = linkedSetOf<String>()
            result += normalized
            val opposite = if (scheme == "https") "http" else "https"

            buildCandidate(opposite, uri, uri.port)?.let(result::add)
            pairedPort(uri.port)?.let { paired ->
                buildCandidate(scheme, uri, paired)?.let(result::add)
                buildCandidate(opposite, uri, paired)?.let(result::add)
            }
            when {
                scheme == "https" && uri.port == -1 -> {
                    buildCandidate("http", uri, 80)?.let(result::add)
                    buildCandidate("http", uri, 443)?.let(result::add)
                }
                scheme == "http" && uri.port == -1 -> {
                    buildCandidate("https", uri, 443)?.let(result::add)
                    buildCandidate("https", uri, 80)?.let(result::add)
                }
                else -> {
                    // Last-resort normalization: many panels expose a custom Xtream port but
                    // terminate the same service on the standard 80/443 ports as well.
                    buildCandidate("https", uri, 443)?.let(result::add)
                    buildCandidate("http", uri, 80)?.let(result::add)
                }
            }
            return result.toList()
        }

        private fun splitInlineHeaders(raw: String): Pair<String, Map<String, String>> {
            val parts = raw.trim().split('|')
            val base = parts.firstOrNull().orEmpty().trim()
            if (parts.size == 1) return base to emptyMap()
            val names = "user-agent|referer|referrer|origin|cookie|authorization|accept|accept-language|http-user-agent|http-referrer|http-referer|http-origin|http-cookie"
            val pattern = Regex("(?i)(?:^|[&|])\\s*($names)\\s*=\\s*(\\\"[^\\\"]*\\\"|'[^']*'|.*?)(?=(?:&(?:$names)\\s*=)|$)")
            val headers = linkedMapOf<String, String>()
            for (part in parts.drop(1)) {
                for (match in pattern.findAll(part)) {
                    val key = normalizeHeaderName(match.groupValues[1]) ?: continue
                    val value = match.groupValues[2].trim().trim('"', '\'')
                    if (value.isNotBlank()) headers[key] = runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
                }
            }
            return base to headers
        }

        private fun normalizeHeaderName(raw: String): String? = when (raw.trim().lowercase()) {
            "user-agent", "http-user-agent" -> "User-Agent"
            "referer", "referrer", "http-referrer", "http-referer" -> "Referer"
            "origin", "http-origin" -> "Origin"
            "cookie", "http-cookie" -> "Cookie"
            "authorization" -> "Authorization"
            "accept" -> "Accept"
            "accept-language" -> "Accept-Language"
            else -> null
        }

        private fun buildCandidate(scheme: String, original: URI, port: Int): String? {
            val rawAuthority = original.rawAuthority ?: return null
            val at = rawAuthority.lastIndexOf('@')
            val userInfo = if (at >= 0) rawAuthority.substring(0, at + 1) else ""
            val hostPort = rawAuthority.substring(at + 1)
            val newHostPort = rewritePort(hostPort, port)
            val raw = original.toString()
            val authorityStart = raw.indexOf("://").let { if (it >= 0) it + 3 else return null }
            val suffixIndex = raw.substring(authorityStart).indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authorityEnd = if (suffixIndex >= 0) authorityStart + suffixIndex else raw.length
            val suffix = raw.substring(authorityEnd)
            return "$scheme://${userInfo}${newHostPort}${suffix}"
        }


        private fun pairedPort(port: Int): Int? = when (port) {
            25461 -> 25463
            25463 -> 25461
            2052 -> 2053
            2053 -> 2052
            2082 -> 2083
            2083 -> 2082
            2086 -> 2087
            2087 -> 2086
            2095 -> 2096
            2096 -> 2095
            8000 -> 8443
            8443 -> 8000
            8080 -> 8443
            8443 -> 8080
            8880 -> 443
            else -> null
        }

        private fun rewritePort(hostPort: String, port: Int): String {
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end >= 0) {
                    val host = hostPort.substring(0, end + 1)
                    return if (port == -1) host else "$host:$port"
                }
            }
            val colon = hostPort.lastIndexOf(':')
            val hasNumericPort = colon > 0 && hostPort.substring(colon + 1).all { it.isDigit() }
            val host = if (hasNumericPort) hostPort.substring(0, colon) else hostPort
            return if (port == -1) host else "$host:$port"
        }

        private fun normalizeUrl(raw: String): String = runCatching {
            val trimmed = raw.trim()
            val uri = URI(trimmed)
            if (uri.scheme.isNullOrBlank()) "http://$trimmed" else trimmed
        }.getOrDefault(raw.trim())
    }
}
