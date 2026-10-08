package com.gassplayer.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

class NetworkApi {
    @Volatile var userAgent: String = DEFAULT_USER_AGENT

    /**
     * Shared client tuned for IPTV panels: up to 6 parallel connections per host (the number
     * Xtream panels tolerate, same as the iOS catalog session), no overall call timeout (catalogs
     * can be tens of MB on slow links) but a bounded idle read timeout.
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 6 })
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()

    /** Used for alternate scheme/port candidates: fail fast instead of stalling for 15s each. */
    private val fastClient: OkHttpClient = client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).build()

    /** Remembers which candidate base (scheme://host:port) worked so later calls skip the ladder. */
    private val workingBase = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class TextResponse(val text: String, val finalUrl: String)

    class HttpStatusException(val code: Int, val url: String) : java.io.IOException("HTTP $code da ${redact(url)}")

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        getTextResult(url, headers).text

    /** Fetches text and also exposes the final redirected URL, which is essential for resolving
     * relative M3U/M3U8 entry URLs against the actual playlist location. Handles gzip payloads
     * (with or without Content-Encoding) and falls back to ISO-8859-1 for legacy playlists. */
    suspend fun getTextResult(url: String, headers: Map<String, String> = emptyMap()): TextResponse =
        execute(url, headers, "*/*") { response, _ ->
            val bytes = response.body.bytes()
            TextResponse(decodeText(gunzipIfNeeded(bytes)), response.request.url.toString())
        }

    /**
     * Streams a (possibly huge, 100+ MB) text body straight into [block] as a [java.io.BufferedReader]
     * without ever materialising the whole payload (byte array + String) in memory.
     * Handles gzip (magic-number sniffing, with or without Content-Encoding) and picks UTF-8 or
     * ISO-8859-1 by sniffing the first chunk, like [decodeText] does for small bodies.
     * [block] receives the reader and the final (redirected) URL, and runs on the IO dispatcher.
     */
    suspend fun <T> readTextStream(url: String, headers: Map<String, String> = emptyMap(), block: (java.io.BufferedReader, String) -> T): T =
        execute(url, headers, "*/*") { response, _ ->
            val finalUrl = response.request.url.toString()
            var stream: java.io.InputStream = java.io.BufferedInputStream(response.body.source().inputStream(), 64 * 1024)
            stream.mark(2)
            val b0 = stream.read(); val b1 = stream.read()
            stream.reset()
            if (b0 == 0x1f && b1 == 0x8b) stream = java.util.zip.GZIPInputStream(stream, 64 * 1024)
            val sniffable = java.io.BufferedInputStream(stream, 64 * 1024)
            sniffable.mark(SNIFF_BYTES + 8)
            val head = ByteArray(SNIFF_BYTES)
            var filled = 0
            while (filled < head.size) {
                val n = sniffable.read(head, filled, head.size - filled)
                if (n <= 0) break
                filled += n
            }
            sniffable.reset()
            val charset = sniffCharset(head, filled)
            java.io.BufferedReader(java.io.InputStreamReader(sniffable, charset), 64 * 1024).use { reader -> block(reader, finalUrl) }
        }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        JsonStore.json.parseToJsonElement(
            execute(url, headers, "application/json, text/plain, */*") { response, _ ->
                decodeText(gunzipIfNeeded(response.body.bytes()))
            }
        )

    /**
     * Streams a (possibly huge) Xtream JSON array and maps every element as it is parsed, so the
     * whole catalog is never materialised as a JsonElement tree (that was an OOM source for
     * panels with 100k+ entries). Tolerates the shapes real panels return:
     *  - a plain array                         [ {...}, {...} ]
     *  - an indexed object                     { "1": {...}, "2": {...} }
     *  - an envelope with a `data` array       { "data": [ ... ] }
     *  - `false` / `null` / `[]`               (no content)
     * Malformed elements are skipped; a truncated stream keeps everything parsed so far.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun <T : Any> getJsonObjects(
        url: String,
        headers: Map<String, String> = emptyMap(),
        map: (JsonObject) -> T?
    ): List<T> = execute(url, headers, "application/json, text/plain, */*") { response, _ ->
        val raw = response.body.source().inputStream()
        val pushback = java.io.PushbackInputStream(java.io.BufferedInputStream(raw, 64 * 1024), 8)
        val magic = ByteArray(2)
        val n = pushback.read(magic, 0, 2)
        if (n > 0) pushback.unread(magic, 0, n)
        val stream: java.io.InputStream = if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
            java.io.PushbackInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(pushback, 64 * 1024), 64 * 1024), 8)
        } else pushback
        val pb = stream as? java.io.PushbackInputStream ?: java.io.PushbackInputStream(stream, 8)
        // Skip BOM and whitespace, find the first meaningful byte.
        var first = -1
        var skipped = 0
        while (true) {
            val b = pb.read()
            if (b == -1) break
            if (b == 0xEF || b == 0xBB || b == 0xBF || b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09) { if (++skipped > 16) { first = b; break }; continue }
            first = b; break
        }
        if (first == -1) return@execute emptyList<T>()
        pb.unread(first)
        val out = ArrayList<T>()
        when (first.toChar()) {
            '[' -> {
                val seq = JsonStore.json.decodeToSequence(pb, JsonElement.serializer(), kotlinx.serialization.json.DecodeSequenceMode.ARRAY_WRAPPED)
                try {
                    for (el in seq) {
                        val obj = el as? JsonObject ?: continue
                        map(obj)?.let(out::add)
                    }
                } catch (_: Throwable) { /* keep partial results */ }
            }
            '{' -> {
                val root = JsonStore.json.parseToJsonElement(decodeText(pb.readBytes())) as? JsonObject
                    ?: return@execute emptyList<T>()
                val data = root["data"]
                val objects: Collection<JsonElement> = when {
                    data is JsonArray -> data
                    root.containsKey("user_info") || root.containsKey("server_info") -> emptyList()
                    root.keys.any { it == "stream_id" || it == "series_id" || it == "category_id" } -> listOf(root)
                    else -> root.entries.sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key })).map { it.value }
                }
                for (el in objects) (el as? JsonObject)?.let { map(it)?.let(out::add) }
            }
            '<' -> throw java.io.IOException("Il server ha risposto con una pagina HTML invece di JSON (host o porta errati?)")
            else -> { /* false / null / numbers: provider has nothing for this action */ }
        }
        out
    }

    /** Streams a large XML/XMLTV body (plain or gzip) straight into [parse] without buffering it. */
    suspend fun <T> getXmlTv(url: String, parse: (java.io.InputStream) -> T): T =
        execute(url, emptyMap(), "application/xml, text/xml, application/gzip, */*") { response, _ ->
            val pb = java.io.PushbackInputStream(java.io.BufferedInputStream(response.body.source().inputStream(), 64 * 1024), 2)
            val magic = ByteArray(2)
            val n = pb.read(magic, 0, 2)
            if (n > 0) pb.unread(magic, 0, n)
            val stream: java.io.InputStream = if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte())
                java.io.BufferedInputStream(java.util.zip.GZIPInputStream(pb, 64 * 1024), 64 * 1024) else pb
            parse(stream)
        }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement =
        postBody(url, body.toRequestBody("application/json".toMediaType()), headers)

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): JsonElement {
        val body = form.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        return postBody(url, body, headers)
    }

    private suspend fun postBody(url: String, body: okhttp3.RequestBody, headers: Map<String, String>): JsonElement = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (candidate in candidateUrls(url)) {
            try {
                val builder = Request.Builder().url(candidate).post(body).header("User-Agent", userAgent)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) return@withContext JsonStore.json.parseToJsonElement(response.body.string())
                    lastError = HttpStatusException(response.code, candidate)
                    if (response.code in 401..499 && response.code != 408 && response.code != 429) throw lastError as Throwable
                }
            } catch (t: HttpStatusException) {
                throw t
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Network request failed")
    }

    /**
     * Core request loop. Tries the remembered working base first, then the transport/port ladder.
     * A definitive answer from the server (401/403/404...) stops the ladder immediately: those
     * are not transport problems and retrying 10 variants only made the app look frozen.
     */
    private suspend fun <R> execute(
        url: String,
        headers: Map<String, String>,
        defaultAccept: String,
        block: (okhttp3.Response, String) -> R
    ): R = withContext(Dispatchers.IO) {
        val (requestUrl, inlineHeaders) = splitInlineHeaders(url)
        val requestHeaders = linkedMapOf<String, String>().apply { putAll(inlineHeaders); putAll(headers) }
        val ladder = candidateUrls(requestUrl)
        val key = baseOf(ladder.first())
        val remembered = workingBase[key]
        val ordered = if (remembered != null) {
            ladder.sortedBy { if (baseOf(it) == remembered) 0 else 1 }
        } else ladder
        var lastError: Throwable? = null
        for ((index, candidate) in ordered.withIndex()) {
            try {
                val builder = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", requestHeaders["User-Agent"] ?: requestHeaders["user-agent"] ?: userAgent)
                    .header("Accept", requestHeaders["Accept"] ?: defaultAccept)
                requestHeaders.forEach { (k, v) ->
                    if (!k.equals("User-Agent", true) && !k.equals("Accept", true)) builder.header(k, v)
                }
                val call = (if (index == 0) client else fastClient).newCall(builder.build())
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        workingBase[key] = baseOf(candidate)
                        return@withContext block(response, candidate)
                    }
                    val ex = HttpStatusException(response.code, candidate)
                    lastError = ex
                    val definitive = response.code in 401..499 && response.code != 408 && response.code != 429 && response.code != 400
                    if (definitive) throw ex
                }
            } catch (t: HttpStatusException) {
                if (t.code in 401..499 && t.code != 408 && t.code != 429) throw t
                lastError = t
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Network request failed")
    }

    companion object {
        private const val SNIFF_BYTES = 256 * 1024

        private fun sniffCharset(buf: ByteArray, len: Int): java.nio.charset.Charset {
            if (len <= 0) return Charsets.UTF_8
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            // endOfInput = false: a multibyte char cut at the end of the sample is not an error.
            val result = decoder.decode(java.nio.ByteBuffer.wrap(buf, 0, len), java.nio.CharBuffer.allocate(len + 1), false)
            return if (result.isError) Charsets.ISO_8859_1 else Charsets.UTF_8
        }

        /** Same default as the iOS app (`StreamUserAgents.defaultVLC`): many panels reject unknown agents. */
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        fun redact(url: String): String = url
            .replace(Regex("(?i)(password|pass|pwd|token)=[^&]+"), "$1=***")
            .replace(Regex("(?i)(/(?:live|movie|series|timeshift)/[^/]+/)[^/]+"), "$1***")

        private fun baseOf(url: String): String {
            val i = url.indexOf("://")
            if (i < 0) return url
            val end = url.indexOf('/', i + 3).let { if (it < 0) url.length else it }
            return url.substring(0, end)
        }

        fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                runCatching { java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }.getOrDefault(bytes)
            else bytes

        fun decodeText(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
            return text.removePrefix("\uFEFF")
        }

        fun stripInlineHeaders(raw: String): String = splitInlineHeaders(raw).first

        fun extractInlineHeaders(raw: String): Map<String, String> = splitInlineHeaders(raw).second

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
