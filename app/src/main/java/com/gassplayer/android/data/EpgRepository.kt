package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

class EpgRepository(private val context: Context, private val api: NetworkApi) {
    private val shortCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<EpgProgram>>>()

    suspend fun shortEpg(source: MediaSourceConfig, streamId: String, limit: Int = 40): List<EpgProgram> = withContext(Dispatchers.IO) {
        val key = "${source.id}:$streamId"
        shortCache[key]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000L }?.let { return@withContext it.second }
        val base = XtreamRepository.serverBase(source.host)
        val auth = "username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}"
        var programs = runCatching { parse(api.getJson("$base/player_api.php?$auth&action=get_short_epg&stream_id=${enc(streamId)}&limit=$limit"), streamId) }.getOrDefault(emptyList())
        if (programs.isEmpty()) {
            programs = runCatching { parse(api.getJson("$base/player_api.php?$auth&action=get_simple_data_table&stream_id=${enc(streamId)}"), streamId) }.getOrDefault(emptyList())
        }
        if (programs.isNotEmpty()) shortCache[key] = System.currentTimeMillis() to programs
        programs
    }

    /** Full-guide XMLTV for an Xtream source (`xmltv.php`), the same feed the iOS grid uses. */
    suspend fun xtreamXmltv(source: MediaSourceConfig): List<EpgProgram> {
        val base = XtreamRepository.serverBase(source.host)
        return xmltv("$base/xmltv.php?username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}", "xt_${source.id}")
    }

    /**
     * Streams an XMLTV document (plain or gzip) with a pull parser, keeping only programmes in a
     * -3h..+72h window. The previous regex implementation loaded the whole file as one String and
     * choked (or ran out of memory) on real guides of 20-100 MB.
     */
    suspend fun xmltv(url: String, sourceId: String): List<EpgProgram> = withContext(Dispatchers.IO) {
        val file = FileCache(context, "epg_${sourceId.hashCode()}.json")
        runCatching { file.read().takeIf { System.currentTimeMillis() - it.updatedAt < 3 * 60 * 60_000L && it.programs.isNotEmpty() }?.programs }.getOrNull()
            ?: runCatching {
                val list = api.getXmlTv(url) { XmlTvParser.parse(it) }
                if (list.isNotEmpty()) file.write(EpgSnapshot(System.currentTimeMillis(), list))
                list
            }.getOrElse { runCatching { file.read().programs }.getOrDefault(emptyList()) }
    }

    suspend fun catchUpUrl(source: MediaSourceConfig, streamId: String, startMs: Long, endMs: Long, extension: String = "ts"): String {
        val c = XtreamCredentials(XtreamRepository.serverBase(source.host), source.username.orEmpty(), source.password.orEmpty()); val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        return "${c.host.trimEnd('/')}/timeshift/${enc(c.username)}/${enc(c.password)}/$durationMin/${fmt.format(java.util.Date(startMs))}/$streamId.$extension"
    }

    private fun parse(root: JsonElement, streamId: String): List<EpgProgram> {
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> root["epg_listings"] ?: root["epgListings"] ?: root["listings"] ?: root["programs"] ?: root["data"] ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return elements.jsonArrayOrEmpty().mapNotNull { el -> el.jsonObjectOrNull()?.let { o ->
            val id = o.valueString("id") ?: o.valueString("epg_id") ?: stable("$streamId:${o.valueString("start")}:${o.valueString("title")}")
            val start = parseTime(o.valueString("start") ?: o.valueString("start_timestamp")) ?: return@let null
            val end = parseTime(o.valueString("end") ?: o.valueString("stop_timestamp")) ?: return@let null
            EpgProgram(id, streamId, decodeMaybeBase64(o.valueString("title")).orEmpty(), decodeMaybeBase64(o.valueString("description")), start, end, o.valueString("has_archive") == "1" || o.valueString("has_archive").equals("true", true))
        } }.sortedBy { it.startMs }
    }
    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    private fun parseTime(value: String?): Long? {
        val v = value ?: return null
        v.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).parse(v, Instant::from).toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
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
}

@Serializable
data class EpgSnapshot(val updatedAt: Long, val programs: List<EpgProgram>)

private class FileCache(private val context: Context, private val name: String) {
    private val file get() = context.filesDir.resolve(name)
    fun read(): EpgSnapshot = JsonStore.json.decodeFromString(file.readText())
    fun write(v: EpgSnapshot) { file.writeText(JsonStore.json.encodeToString(v)) }
}

object XmlTvParser {
    /** Channel display names, filled while parsing, so the UI can match by name when ids differ. */
    fun parse(input: java.io.InputStream): List<EpgProgram> {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val now = System.currentTimeMillis()
        val from = now - 3 * 3_600_000L
        val to = now + 72 * 3_600_000L
        val out = ArrayList<EpgProgram>(8192)
        var event = parser.eventType
        var channel = ""; var start = 0L; var stop = 0L; var title = ""; var desc: String? = null; var inProg = false
        var tag = ""
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "programme") {
                        inProg = true; title = ""; desc = null
                        channel = parser.getAttributeValue(null, "channel").orEmpty()
                        start = xmlTvTime(parser.getAttributeValue(null, "start").orEmpty()) ?: 0L
                        stop = xmlTvTime(parser.getAttributeValue(null, "stop").orEmpty()) ?: 0L
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inProg) {
                    val t = parser.text.trim()
                    if (t.isNotEmpty()) {
                        if (tag == "title" && title.isEmpty()) title = t
                        else if (tag == "desc" && desc == null) desc = t
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name == "programme") {
                        if (inProg && start > 0 && stop > start && stop > from && start < to && channel.isNotEmpty()) {
                            out += EpgProgram("$channel:$start", channel, title, desc, start, stop)
                        }
                        inProg = false
                    }
                    tag = ""
                }
            }
            event = try { parser.next() } catch (_: org.xmlpull.v1.XmlPullParserException) { break }
        }
        return out
    }

    fun xmlTvTime(v: String): Long? {
        val raw = v.trim()
        if (raw.length < 12) return null
        val date = raw.take(14).padEnd(14, '0')
        val zone = raw.drop(14).trim()
        return runCatching {
            val local = java.time.LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            val zoneId = if (zone.isNotEmpty()) java.time.ZoneOffset.of(zone.let { if (it.length == 5 && (it[0] == '+' || it[0] == '-')) it.substring(0, 3) + ":" + it.substring(3) else it }) else ZoneId.systemDefault()
            local.atZone(zoneId).toInstant().toEpochMilli()
        }.getOrNull()
    }
}

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonObject.valueString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
