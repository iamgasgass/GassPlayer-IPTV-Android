package com.gassplayer.android.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class EpgRepository(private val context: Context, private val api: NetworkApi) {
    private val shortCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<EpgProgram>>>()

    suspend fun shortEpg(source: MediaSourceConfig, streamId: String, limit: Int = 40): List<EpgProgram> = withContext(Dispatchers.IO) {
        val key = "${source.id}:$streamId"
        shortCache[key]?.takeIf { System.currentTimeMillis() - it.first < SHORT_CACHE_TTL_MS }?.let { return@withContext it.second }
        val base = XtreamRepository.serverBase(source.host)
        val auth = "username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}"
        var programs = try {
            parse(api.getJson("$base/player_api.php?$auth&action=get_short_epg&stream_id=${enc(streamId)}&limit=$limit"), streamId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (programs.isEmpty()) {
            programs = try {
                parse(api.getJson("$base/player_api.php?$auth&action=get_simple_data_table&stream_id=${enc(streamId)}"), streamId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (programs.isNotEmpty()) shortCache[key] = System.currentTimeMillis() to programs
        programs
    }

    /** Compatibility API for screens that only need programmes. */
    suspend fun xtreamXmltv(source: MediaSourceConfig): List<EpgProgram> = xtreamXmltvGuide(source).programs

    /** Provider XMLTV, retaining `<channel>` aliases so channel names can be matched reliably. */
    suspend fun xtreamXmltvGuide(
        source: MediaSourceConfig,
        forceRefresh: Boolean = false,
        preloadedCache: XmlTvGuide? = null,
    ): XmlTvGuide {
        val base = XtreamRepository.serverBase(source.host)
        val url = "$base/xmltv.php?username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}"
        return xmltvGuide(url, "xt_${source.id}", forceRefresh, preloadedCache)
    }

    /** Reads the last known guide without applying the network freshness TTL (stale-while-revalidate). */
    suspend fun cachedXmltvGuide(url: String, sourceId: String): XmlTvGuide = withContext(Dispatchers.IO) {
        runCatching {
            FileCache(context, cacheFileName(url, sourceId)).read()
                .takeIf { it.programs.isNotEmpty() }
                ?.asGuide()
        }.getOrNull() ?: XmlTvGuide()
    }

    /** Same fast cache access for the provider-generated Xtream XMLTV URL. */
    suspend fun cachedXtreamXmltvGuide(source: MediaSourceConfig): XmlTvGuide {
        val base = XtreamRepository.serverBase(source.host)
        val url = "$base/xmltv.php?username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}"
        return cachedXmltvGuide(url, "xt_${source.id}")
    }

    /** Compatibility API for external feeds. */
    suspend fun xmltv(url: String, sourceId: String): List<EpgProgram> = xmltvGuide(url, sourceId).programs

    /**
     * Streams a plain or gzip XMLTV document. The cache is source-URL-specific, atomic, and only
     * replaced after a valid parse with programmes; a failed/truncated download keeps last-good data.
     */
    suspend fun xmltvGuide(
        url: String,
        sourceId: String,
        forceRefresh: Boolean = false,
        preloadedCache: XmlTvGuide? = null,
    ): XmlTvGuide = withContext(Dispatchers.IO) {
        // Include the URL fingerprint: changing a custom feed URL must not silently reuse its old guide.
        val file = FileCache(context, cacheFileName(url, sourceId))
        // The EPG screen already read this same cache to render immediately. Reuse that parsed
        // payload as the stale fallback, avoiding a second decode of potentially huge XMLTV JSON.
        val cached = if (forceRefresh && preloadedCache != null) {
            EpgSnapshot(0L, preloadedCache.programs, preloadedCache.channels)
        } else runCatching { file.read() }.getOrNull()
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && cached.programs.isNotEmpty() && now - cached.updatedAt < XMLTV_CACHE_TTL_MS) {
            return@withContext cached.asGuide()
        }

        try {
            val guide = api.getXmlTv(url) { XmlTvParser.parseGuide(it) }
            if (guide.programs.isNotEmpty()) {
                file.write(EpgSnapshot(now, guide.programs, guide.channels))
                guide
            } else {
                // Empty but syntactically valid feeds should not erase a previous useful guide.
                cached?.takeIf { it.programs.isNotEmpty() }?.asGuide() ?: guide
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cached?.takeIf { it.programs.isNotEmpty() }?.asGuide() ?: XmlTvGuide()
        }
    }

    suspend fun catchUpUrl(source: MediaSourceConfig, streamId: String, startMs: Long, endMs: Long, extension: String = "ts"): String {
        val c = XtreamCredentials(XtreamRepository.serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        return "${c.host.trimEnd('/')}/timeshift/${enc(c.username)}/${enc(c.password)}/$durationMin/${fmt.format(java.util.Date(startMs))}/$streamId.$extension"
    }

    private fun parse(root: JsonElement, streamId: String): List<EpgProgram> {
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> root["epg_listings"] ?: root["epgListings"] ?: root["listings"] ?: root["programs"] ?: root["data"] ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return elements.jsonArrayOrEmpty().mapNotNull { el -> el.jsonObjectOrNull()?.let { o ->
            val startRaw = o.valueString("start") ?: o.valueString("start_timestamp")
            val endRaw = o.valueString("end") ?: o.valueString("stop_timestamp")
            val id = o.valueString("id") ?: o.valueString("epg_id") ?: stable("$streamId:$startRaw:${o.valueString("title")}")
            val start = parseTime(startRaw) ?: return@let null
            val end = parseTime(endRaw) ?: return@let null
            if (end <= start) return@let null
            EpgProgram(
                id = id,
                streamId = streamId,
                title = decodeMaybeBase64(o.valueString("title")).orEmpty().ifBlank { "No information" },
                description = decodeMaybeBase64(o.valueString("description")),
                startMs = start,
                endMs = end,
                hasArchive = o.valueString("has_archive") == "1" || o.valueString("has_archive").equals("true", true),
            )
        } }.distinctBy { Triple(it.startMs, it.endMs, it.title.trim()) }.sortedBy { it.startMs }
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    private fun parseTime(value: String?): Long? {
        val v = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        v.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return runCatching {
            LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun decodeMaybeBase64(v: String?): String? {
        if (v.isNullOrBlank()) return v
        if (v.length < 4 || v.length % 4 != 0 || !v.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) return v
        return runCatching {
            val decoded = String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.any { it == '\uFFFD' || (it.code < 32 && it != '\n' && it != '\t' && it != '\r') }) v else decoded
        }.getOrDefault(v)
    }

    private fun stable(text: String) = text.hashCode().toString()

    private fun EpgSnapshot.asGuide() = XmlTvGuide(programs, channels)
    private fun cacheFileName(url: String, sourceId: String) = "epg_${sourceId.hashCode()}_${url.hashCode()}.json"

    companion object {
        private const val SHORT_CACHE_TTL_MS = 5 * 60_000L
        private const val XMLTV_CACHE_TTL_MS = 3 * 60 * 60_000L
    }
}

@Serializable
data class EpgSnapshot(
    val updatedAt: Long,
    val programs: List<EpgProgram>,
    val channels: List<XmlTvChannelAlias> = emptyList(),
)

private class FileCache(private val context: Context, private val name: String) {
    private val file get() = context.filesDir.resolve(name)
    private val atomicFile get() = AtomicFile(file)

    fun read(): EpgSnapshot = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
        JsonStore.json.decodeFromString(reader.readText())
    }

    fun write(value: EpgSnapshot) {
        val output = atomicFile.startWrite()
        try {
            output.write(JsonStore.json.encodeToString(value).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}

/** Pull parser for common XMLTV variants, intentionally streaming and tolerant of sparse metadata. */
object XmlTvParser {
    fun parse(input: InputStream): List<EpgProgram> = parseGuide(input).programs

    fun parseGuide(input: InputStream): XmlTvGuide {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val now = System.currentTimeMillis()
        // The grid offers yesterday/today/tomorrow; keep enough history for yesterday's noon window.
        val from = now - 48 * 3_600_000L
        val to = now + 48 * 3_600_000L
        val programs = ArrayList<EpgProgram>(8192)
        val aliases = ArrayList<XmlTvChannelAlias>(512)
        val seen = HashSet<String>()
        var event = parser.eventType

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannelAliases(parser, aliases)
                    "programme" -> readProgramme(parser, from, to)?.let { program ->
                        if (seen.add(program.id)) programs += program
                    }
                }
            }
            // A malformed/truncated document must fail so the repository keeps the last-good cache.
            event = parser.next()
        }
        return XmlTvGuide(programs.sortedBy { it.startMs }, aliases.distinctBy { it.epgId to it.displayName })
    }

    private fun readChannelAliases(parser: org.xmlpull.v1.XmlPullParser, output: MutableList<XmlTvChannelAlias>) {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        var depth = 1
        val names = ArrayList<String>(2)
        while (depth > 0) {
            when (parser.next()) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    if (parser.name == "display-name") {
                        val name = runCatching { parser.nextText().trim() }.getOrDefault("")
                        depth--
                        if (name.isNotEmpty()) names += name
                    } else depth++
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> depth--
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> return
            }
        }
        if (id.isNotEmpty()) {
            (names.ifEmpty { listOf(id) }).forEach { name -> output += XmlTvChannelAlias(id, name) }
        }
    }

    private fun readProgramme(
        parser: org.xmlpull.v1.XmlPullParser,
        from: Long,
        to: Long,
    ): EpgProgram? {
        val channel = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = xmlTvTime(parser.getAttributeValue(null, "start"))
        val parsedStop = xmlTvTime(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var description: String? = null
        var depth = 1

        while (depth > 0) {
            when (parser.next()) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> {
                            val text = runCatching { parser.nextText().trim() }.getOrDefault("")
                            depth--
                            if (title.isNullOrBlank() && text.isNotEmpty()) title = text
                        }
                        "desc" -> {
                            val text = runCatching { parser.nextText().trim() }.getOrDefault("")
                            depth--
                            if (description.isNullOrBlank() && text.isNotEmpty()) description = text
                        }
                        else -> depth++
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> depth--
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> return null
            }
        }

        if (channel.isEmpty() || start == null || start >= to) return null
        val end = parsedStop ?: (start + DEFAULT_DURATION_MS)
        if (end <= start || end <= from) return null
        val id = "$channel:$start"
        return EpgProgram(id, channel, title?.takeIf(String::isNotBlank) ?: "No information", description, start, end)
    }

    /**
     * XMLTV timestamps without an explicit offset are UTC (per the feed convention), not device
     * local time. Accepts YYYYMMDD, YYYYMMDDHH, YYYYMMDDHHMM, YYYYMMDDHHMMSS and ±HHMM/±HH:MM.
     */
    fun xmlTvTime(value: String?): Long? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val digitCount = raw.takeWhile(Char::isDigit).length
        if (digitCount != 8 && digitCount != 10 && digitCount != 12 && digitCount != 14) return null
        val digits = raw.substring(0, digitCount)
        val suffix = raw.substring(digitCount).trim()
        val offsetMinutes = parseOffsetMinutes(suffix) ?: if (suffix.isEmpty()) 0 else return null

        return try {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                clear()
                isLenient = false
                set(Calendar.YEAR, digits.substring(0, 4).toInt())
                set(Calendar.MONTH, digits.substring(4, 6).toInt() - 1)
                set(Calendar.DAY_OF_MONTH, digits.substring(6, 8).toInt())
                set(Calendar.HOUR_OF_DAY, digits.partOrZero(8))
                set(Calendar.MINUTE, digits.partOrZero(10))
                set(Calendar.SECOND, digits.partOrZero(12))
            }
            calendar.timeInMillis - offsetMinutes * 60_000L
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun String.partOrZero(offset: Int): Int =
        if (length >= offset + 2) substring(offset, offset + 2).toIntOrNull() ?: 0 else 0

    private fun parseOffsetMinutes(value: String): Int? {
        if (value.isEmpty() || value.equals("Z", true) || value.equals("UTC", true) || value.equals("GMT", true)) return 0
        val sign = when (value.firstOrNull()) { '+' -> 1; '-' -> -1; else -> return null }
        val digits = value.drop(1).replace(":", "")
        if (digits.length != 4) return null
        val hours = digits.substring(0, 2).toIntOrNull() ?: return null
        val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
        if (hours > 23 || minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }

    private const val DEFAULT_DURATION_MS = 30 * 60 * 1000L
}

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonObject.valueString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
