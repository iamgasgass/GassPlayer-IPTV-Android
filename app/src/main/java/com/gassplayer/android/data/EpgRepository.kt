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
    suspend fun shortEpg(source: MediaSourceConfig, streamId: String, limit: Int = 40): List<EpgProgram> = withContext(Dispatchers.IO) {
        val c = XtreamCredentials(source.host, source.username.orEmpty(), source.password.orEmpty())
        val params = "username=${enc(c.username)}&password=${enc(c.password)}&action=get_short_epg&stream_id=${enc(streamId)}&limit=$limit"
        val root = api.getJson("${c.host.trimEnd('/')}/player_api.php?$params")
        val programs = parse(root, streamId)
        if (programs.isNotEmpty()) programs else {
            val root2 = api.getJson("${c.host.trimEnd('/')}/player_api.php?username=${enc(c.username)}&password=${enc(c.password)}&action=get_simple_data_table&stream_id=${enc(streamId)}")
            parse(root2, streamId)
        }
    }

    suspend fun xmltv(url: String, sourceId: String): List<EpgProgram> = withContext(Dispatchers.IO) {
        val file = FileCache(context, "epg_${sourceId.hashCode()}.json")
        runCatching { file.read().takeIf { System.currentTimeMillis() - it.updatedAt < 6 * 60 * 60_000L }?.programs } .getOrNull() ?: runCatching {
            val text = api.getText(url); val list = XmlTvParser.parse(text); file.write(EpgSnapshot(System.currentTimeMillis(), list)); list
        }.getOrDefault(emptyList())
    }

    suspend fun catchUpUrl(source: MediaSourceConfig, streamId: String, startMs: Long, endMs: Long, extension: String = "ts"): String {
        val c = XtreamCredentials(source.host, source.username.orEmpty(), source.password.orEmpty()); val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1)
        return "${c.host.trimEnd('/')}/timeshift/${enc(c.username)}/${enc(c.password)}/$durationMin/${startMs / 1000}/$streamId.$extension"
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
    fun write(v: EpgSnapshot) { file.writeText(JsonStore.json.encodeToString(v)) }
}

private object XmlTvParser {
    fun parse(xml: String): List<EpgProgram> {
        val out = mutableListOf<EpgProgram>(); val block = Regex("<programme[^>]*start=\"([^\"]+)\"[^>]*stop=\"([^\"]+)\"[^>]*channel=\"([^\"]+)\"[\\s\\S]*?</programme>", RegexOption.IGNORE_CASE)
        for (m in block.findAll(xml)) {
            val body = m.value; val title = Regex("<title[^>]*>([\\s\\S]*?)</title>").find(body)?.groupValues?.getOrNull(1)?.stripXml().orEmpty()
            val desc = Regex("<desc[^>]*>([\\s\\S]*?)</desc>").find(body)?.groupValues?.getOrNull(1)?.stripXml()
            val start = xmlTvTime(m.groupValues[1]) ?: continue; val end = xmlTvTime(m.groupValues[2]) ?: continue
            out += EpgProgram("${m.groupValues[3]}:$start", m.groupValues[3], title, desc, start, end)
        }
        return out
    }
    private fun xmlTvTime(v: String): Long? {
        val raw = v.trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val date = parts.firstOrNull() ?: return null
        val zone = parts.getOrNull(1)?.trim().orEmpty()
        return runCatching {
            val formatter = if (zone.isNotBlank()) DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z") else DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            if (zone.isNotBlank()) java.time.ZonedDateTime.parse("$date $zone", formatter).toInstant().toEpochMilli()
            else java.time.LocalDateTime.parse(date, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
    private fun String.stripXml() = replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonObject.valueString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
