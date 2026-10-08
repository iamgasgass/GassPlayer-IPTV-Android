package com.gassplayer.android.data

import android.content.Context
import android.util.Xml
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class EpgRepository(private val context: Context, private val api: NetworkApi) {
    suspend fun shortEpg(source: MediaSourceConfig, streamId: String, limit: Int = 40): List<EpgProgram> = withContext(Dispatchers.IO) {
        val c = normalizedCredentials(source)
        val safeLimit = limit.coerceIn(1, 500)
        val playerBase = "${c.host.trimEnd('/')}/player_api.php"
        val attempts = listOf(
            "action=get_short_epg&stream_id=${enc(streamId)}&limit=$safeLimit",
            "action=get_simple_data_table&stream_id=${enc(streamId)}",
        )
        for (action in attempts) {
            val result = runCatching {
                val root = api.getJson("$playerBase?username=${enc(c.username)}&password=${enc(c.password)}&$action")
                parse(root, streamId)
            }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return@withContext result
        }

        // Compatible panels may expose EPG through panel_api.php instead of the Player API.
        val panelResult = runCatching {
            val root = api.getJson("${c.host.trimEnd('/')}/panel_api.php?username=${enc(c.username)}&password=${enc(c.password)}&action=get_epg&stream_id=${enc(streamId)}")
            parse(root, streamId)
        }.getOrDefault(emptyList())
        if (panelResult.isNotEmpty()) return@withContext panelResult

        // Last resort: full XMLTV from the same Xtream credentials, then filter this channel.
        val full = xtreamXmltv(source, c)
        if (full.isEmpty()) return@withContext emptyList()
        full.filter { it.streamId.equals(streamId, true) }.sortedBy { it.startMs }.take(safeLimit)
    }

    suspend fun xtreamXmltv(source: MediaSourceConfig): List<EpgProgram> = withContext(Dispatchers.IO) {
        xtreamXmltv(source, normalizedCredentials(source))
    }

    private suspend fun xtreamXmltv(source: MediaSourceConfig, c: XtreamCredentials): List<EpgProgram> {
        val file = FileCache(context, "xtream_epg_${source.id.hashCode()}.json")
        file.readFresh(60 * 60_000L)?.let { return it.programs }
        val variants = listOf(
            "prev_days=1&next_days=3",
            "prev_days=2&next_days=5",
            "prev_days=0&next_days=7"
        )
        for (extra in variants) {
            val url = "${c.host.trimEnd('/')}/xmltv.php?username=${enc(c.username)}&password=${enc(c.password)}&$extra"
            val list = runCatching { XmlTvParser.parse(api.getText(url)) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                file.write(EpgSnapshot(System.currentTimeMillis(), list))
                return list
            }
        }
        return emptyList()
    }

    suspend fun xmltv(url: String, sourceId: String): List<EpgProgram> = withContext(Dispatchers.IO) {
        val file = FileCache(context, "epg_${sourceId.hashCode()}.json")
        file.readFresh(6 * 60 * 60_000L)?.let { return@withContext it.programs }
        val list = runCatching { XmlTvParser.parse(api.getText(url)) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) file.write(EpgSnapshot(System.currentTimeMillis(), list))
        list
    }

    suspend fun catchUpUrl(source: MediaSourceConfig, streamId: String, startMs: Long, endMs: Long, extension: String = "ts"): String {
        val c = normalizedCredentials(source)
        val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1)
        return "${c.host.trimEnd('/')}/timeshift/${enc(c.username)}/${enc(c.password)}/$durationMin/${startMs / 1000}/$streamId.${extension.trimStart('.') }"
    }

    private fun normalizedCredentials(source: MediaSourceConfig): XtreamCredentials {
        var raw = source.host.trim()
        if (!raw.contains("://")) raw = "http://$raw"
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: error("Host Xtream non valido")
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "http"
        val authority = uri.rawAuthority ?: error("Host Xtream non valido")
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.lowercase().endsWith("/player_api.php")) path = path.dropLast("/player_api.php".length)
        if (path.lowercase().endsWith("/get.php")) path = path.dropLast("/get.php".length)
        val base = "$scheme://$authority${if (path.isBlank()) "" else "/${path.trim('/')}"}"
        return XtreamCredentials(base, source.username.orEmpty(), source.password.orEmpty())
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
            EpgProgram(id, streamId, o.valueString("title").orEmpty(), o.valueString("description"), start, end, o.valueString("has_archive") == "1" || o.valueString("has_archive").equals("true", true))
        } }.sortedBy { it.startMs }
    }
    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    private fun parseTime(value: String?): Long? {
        val v = value ?: return null
        v.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).parse(v, Instant::from).toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
    }
    private fun stable(text: String) = text.hashCode().toString()
}

@Serializable
data class EpgSnapshot(val updatedAt: Long, val programs: List<EpgProgram>)

private class FileCache(private val context: Context, private val name: String) {
    private val file get() = context.filesDir.resolve(name)
    fun read(): EpgSnapshot = JsonStore.json.decodeFromString(file.readText())
    fun readFresh(maxAgeMs: Long): EpgSnapshot? = runCatching {
        val value = read()
        value.takeIf { System.currentTimeMillis() - it.updatedAt < maxAgeMs }
    }.getOrNull()
    fun write(v: EpgSnapshot) { file.writeText(JsonStore.json.encodeToString(v)) }
}

private object XmlTvParser {
    fun parse(xml: String): List<EpgProgram> = runCatching { parsePull(xml) }.getOrElse { parseRegexFallback(xml) }

    private fun parsePull(xml: String): List<EpgProgram> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<EpgProgram>()
        var event = parser.eventType
        var programmeChannel: String? = null
        var programmeStart: String? = null
        var programmeEnd: String? = null
        var title: String? = null
        var description: String? = null
        var inProgramme = false
        var inTitle = false
        var inDesc = false
        val titleBuffer = StringBuilder()
        val descBuffer = StringBuilder()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "programme" -> {
                        inProgramme = true
                        programmeChannel = parser.getAttributeValue(null, "channel")?.trim()
                        programmeStart = parser.getAttributeValue(null, "start")?.trim()
                        programmeEnd = parser.getAttributeValue(null, "stop")?.trim()
                        title = null
                        description = null
                        titleBuffer.setLength(0)
                        descBuffer.setLength(0)
                    }
                    "title" -> if (inProgramme) { inTitle = true; titleBuffer.setLength(0) }
                    "desc", "description" -> if (inProgramme) { inDesc = true; descBuffer.setLength(0) }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (inTitle) titleBuffer.append(parser.text)
                    if (inDesc) descBuffer.append(parser.text)
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "title" -> { if (inTitle) { title = titleBuffer.toString().trim(); inTitle = false } }
                    "desc", "description" -> { if (inDesc) { description = descBuffer.toString().trim(); inDesc = false } }
                    "programme" -> {
                        if (inProgramme) {
                            val channel = programmeChannel.orEmpty()
                            val start = xmlTvTime(programmeStart.orEmpty())
                            val end = xmlTvTime(programmeEnd.orEmpty())
                            if (channel.isNotBlank() && start != null && end != null && end > start) {
                                out += EpgProgram("$channel:$start", channel, title.orEmpty(), description?.takeIf { it.isNotBlank() }, start, end)
                            }
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseRegexFallback(xml: String): List<EpgProgram> {
        val out = mutableListOf<EpgProgram>()
        val programme = Regex("(?is)<programme\\b([^>]*)>(.*?)</programme>")
        val attr = Regex("(?is)([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
        for (m in programme.findAll(xml)) {
            val attrs = attr.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifBlank { it.groupValues[3] } }
            val channel = attrs["channel"].orEmpty()
            val start = xmlTvTime(attrs["start"].orEmpty())
            val end = xmlTvTime(attrs["stop"].orEmpty())
            if (channel.isBlank() || start == null || end == null || end <= start) continue
            val body = m.groupValues[2]
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.getOrNull(1)?.stripXml().orEmpty()
            val desc = Regex("(?is)<(?:desc|description)[^>]*>(.*?)</(?:desc|description)>").find(body)?.groupValues?.getOrNull(1)?.stripXml()?.takeIf { it.isNotBlank() }
            out += EpgProgram("$channel:$start", channel, title, desc, start, end)
        }
        return out.sortedBy { it.startMs }
    }

    private fun xmlTvTime(v: String): Long? {
        val raw = v.trim()
        if (raw.isBlank()) return null
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val date = parts.firstOrNull() ?: return null
        val zone = parts.getOrNull(1)?.trim().orEmpty()
        return runCatching {
            val formatter = if (zone.isNotBlank()) DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z") else DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            if (zone.isNotBlank()) java.time.ZonedDateTime.parse("$date $zone", formatter).toInstant().toEpochMilli()
            else java.time.LocalDateTime.parse(date, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun String.stripXml() = replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonObject.valueString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
