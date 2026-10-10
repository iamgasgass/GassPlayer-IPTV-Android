package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Finestra XMLTV conservata: "Ieri" (fino a ~37 h fa) e "Domani" (fino a ~39 h avanti) restano coperti. */
const val XMLTV_PAST_MS = 40 * 3_600_000L
const val XMLTV_FUTURE_MS = 42 * 3_600_000L
private const val XMLTV_TTL_MS = 3 * 3_600_000L
private const val DESC_MAX = 400

class EpgRepository(private val context: Context, private val api: NetworkApi) {
    private class ShortEntry(val at: Long, val programs: List<EpgProgram>)
    private val shortCache = ConcurrentHashMap<String, ShortEntry>()
    private val xmltvMemory = ConcurrentHashMap<String, Pair<Long, XmlTvData>>()
    /** Un solo download/parse XMLTV alla volta: evita doppi scaricamenti e picchi di memoria. */
    private val xmltvLock = Mutex()

    /** Svuota ogni cache (memoria + disco): usato dal pulsante "Aggiorna guida". */
    fun invalidate() {
        shortCache.clear()
        xmltvMemory.clear()
        runCatching {
            context.filesDir.listFiles { f -> f.name.startsWith("epg_") && f.name.endsWith(".json") }?.forEach { it.delete() }
        }
    }

    /**
     * Guida di un canale Xtream. Con [preferTable] (giorni diversi da oggi) usa prima
     * `get_simple_data_table`, che copre passato e futuro; altrimenti `get_short_epg` (rapido, prossimi
     * programmi). Lancia l'eccezione SOLO se tutte le richieste sono fallite in rete: una risposta vuota
     * valida non e' un errore (cosi' il chiamante puo' ritentare i veri guasti).
     */
    suspend fun shortEpg(
        source: MediaSourceConfig,
        streamId: String,
        limit: Int = 40,
        preferTable: Boolean = false
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        val key = "${source.id}:$streamId:$limit:$preferTable"
        val now = System.currentTimeMillis()
        shortCache[key]?.let { e ->
            // Positivi 4 min (< intervallo di auto-aggiornamento), vuoti 10 min: non si martella il pannello.
            val ttl = if (e.programs.isEmpty()) 10 * 60_000L else 4 * 60_000L
            if (now - e.at < ttl) return@withContext e.programs
        }
        val base = XtreamRepository.serverBase(source.host)
        val auth = "username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}"
        var anySuccess = false
        var lastError: Throwable? = null
        suspend fun attempt(action: String, extra: String): List<EpgProgram> =
            runCatching { parse(api.getJson("$base/player_api.php?$auth&action=$action&stream_id=${enc(streamId)}$extra"), streamId) }
                .onSuccess { anySuccess = true }
                .onFailure { lastError = it }
                .getOrDefault(emptyList())

        val order = if (preferTable) listOf("get_simple_data_table" to "", "get_short_epg" to "&limit=$limit")
        else listOf("get_short_epg" to "&limit=$limit", "get_simple_data_table" to "")
        var programs = emptyList<EpgProgram>()
        for ((action, extra) in order) {
            programs = attempt(action, extra)
            if (programs.isNotEmpty()) break
        }
        if (programs.isEmpty() && !anySuccess) throw (lastError ?: java.io.IOException("EPG non raggiungibile"))
        shortCache[key] = ShortEntry(now, programs)
        programs
    }

    /** Full-guide XMLTV for an Xtream source (`xmltv.php`), the same feed the iOS grid uses. */
    suspend fun xtreamXmltv(source: MediaSourceConfig): List<EpgProgram> = xtreamXmltvData(source, null).programs

    suspend fun xtreamXmltvData(source: MediaSourceConfig, filter: XmlTvFilter?): XmlTvData {
        val base = XtreamRepository.serverBase(source.host)
        return xmltvData("$base/xmltv.php?username=${enc(source.username.orEmpty())}&password=${enc(source.password.orEmpty())}", "xt_${source.id}", filter)
    }

    suspend fun xmltv(url: String, sourceId: String): List<EpgProgram> = xmltvData(url, sourceId, null).programs

    /**
     * Streams an XMLTV document (plain or gzip) with a pull parser keeping only the useful window and,
     * with [filter], only the channels of the user's playlists (a 100 MB guide with 8000 channels no
     * longer ends up entirely in RAM). The result is cached in memory (so the 5-minute refresh ticks
     * cost nothing) and on disk for 3 h; if the download fails a stale disk copy is still served.
     */
    suspend fun xmltvData(url: String, sourceId: String, filter: XmlTvFilter? = null): XmlTvData = withContext(Dispatchers.IO) {
        val sig = filter?.signature ?: 0
        val memKey = "$sourceId|$sig"
        fun freshMemory(): XmlTvData? = xmltvMemory[memKey]?.takeIf { System.currentTimeMillis() - it.first < XMLTV_TTL_MS }?.second
        freshMemory()?.let { return@withContext it }
        xmltvLock.withLock {
            freshMemory()?.let { return@withLock it }
            val prefix = "epg_${sourceId.hashCode()}_"
            val file = FileCache(context, "$prefix$sig.json")
            val cached = runCatching { file.read() }.getOrNull()
            if (cached != null && cached.programs.isNotEmpty() && System.currentTimeMillis() - cached.updatedAt < XMLTV_TTL_MS) {
                return@withLock XmlTvData(cached.programs, cached.aliases).also { xmltvMemory[memKey] = cached.updatedAt to it }
            }
            val downloaded = runCatching { api.getXmlTv(url) { XmlTvParser.parseFull(it, filter) } }.getOrNull()
            if (downloaded != null && downloaded.programs.isNotEmpty()) {
                runCatching {
                    // Una sola copia su disco per sorgente: le firme obsolete (playlist cambiata) si eliminano.
                    context.filesDir.listFiles { f -> f.name.startsWith(prefix) && f.name != file.name }?.forEach { it.delete() }
                    file.write(EpgSnapshot(System.currentTimeMillis(), downloaded.programs, downloaded.aliases, sig))
                }
                xmltvMemory[memKey] = System.currentTimeMillis() to downloaded
                downloaded
            } else if (cached != null) {
                // Rete assente/guida vuota: meglio dati vecchi che nessun dato; si riprova tra ~5 minuti.
                XmlTvData(cached.programs, cached.aliases).also {
                    xmltvMemory[memKey] = (System.currentTimeMillis() - XMLTV_TTL_MS + 5 * 60_000L) to it
                }
            } else XmlTvData(emptyList(), emptyMap())
        }
    }

    /**
     * Costruisce l'indice di ricerca usato dalla griglia: sorgenti EPG esterne abilitate e, se non danno
     * nulla, `xmltv.php` di ogni sorgente Xtream che ha canali live.
     */
    suspend fun buildIndex(
        external: List<ExternalEpgSource>,
        sources: List<MediaSourceConfig>,
        live: List<MediaItem>,
        version: Int
    ): XmlTvIndex = withContext(Dispatchers.IO) {
        if (live.isEmpty()) return@withContext XmlTvIndex(emptyMap(), emptyMap(), version, ready = true)
        val filter = XmlTvFilter.forChannels(live)
        val all = ArrayList<XmlTvData>()
        for (e in external.filter { it.isEnabled }) {
            runCatching { xmltvData(e.urlString, e.id, filter) }.getOrNull()?.takeIf { it.programs.isNotEmpty() }?.let(all::add)
        }
        if (all.isEmpty()) {
            val used = live.mapTo(HashSet()) { it.sourceId }
            for (s in sources.filter { it.type == SourceType.XTREAM && it.id in used && it.isEnabled }) {
                runCatching { xtreamXmltvData(s, filter) }.getOrNull()?.takeIf { it.programs.isNotEmpty() }?.let(all::add)
            }
        }
        XmlTvIndex.build(all, version)
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
            // `start`/`end` sono stringhe nel fuso orario del SERVER (lette come fuso del telefono spostavano
            // i programmi di ore); `start_timestamp`/`stop_timestamp` sono epoch UTC affidabili: hanno priorita'.
            val start = parseTime(o.valueString("start_timestamp") ?: o.valueString("start")) ?: return@let null
            val end = parseTime(o.valueString("stop_timestamp") ?: o.valueString("end")) ?: return@let null
            if (end <= start) return@let null
            val id = o.valueString("id") ?: o.valueString("epg_id") ?: stable("$streamId:$start:${o.valueString("title")}")
            val archive = o.valueString("has_archive").let { it == "1" || it.equals("true", true) }
            EpgProgram(id, streamId, decodeMaybeBase64(o.valueString("title")).orEmpty(), decodeMaybeBase64(o.valueString("description"))?.take(DESC_MAX), start, end, archive)
        } }.sortedBy { it.startMs }
    }
    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    private fun parseTime(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { LocalDateTime.parse(v, SQL_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
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

    private companion object {
        val SQL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

@Serializable
data class EpgSnapshot(
    val updatedAt: Long,
    val programs: List<EpgProgram>,
    val aliases: Map<String, String> = emptyMap(),
    val signature: Int = 0
)

/** Programmi XMLTV + mappa "nome canale normalizzato -> id canale XMLTV (minuscolo)". */
class XmlTvData(val programs: List<EpgProgram>, val aliases: Map<String, String>)

/** Nomi di canale confrontabili: "IT: Rai 1 HD" e "RAI1" diventano entrambi "rai1". */
object EpgNames {
    private val prefix = Regex("^[a-z]{2,3}\\s*[:|]\\s*")
    private val quality = Regex("\\b(full\\s*hd|fhd|uhd|hevc|h265|h264|4k|hd|sd|raw)\\b")

    fun normalize(name: String): String {
        var n = name.lowercase(Locale.ROOT).trim()
        n = prefix.replace(n, "")
        n = quality.replace(n, " ")
        return n.filter { it.isLetterOrDigit() }
    }
}

/** Canali della playlist: l'XMLTV viene filtrato su questi id/nomi mentre lo si legge. */
class XmlTvFilter(val ids: Set<String>, val names: Set<String>) {
    /** Indipendente dall'ordine; cambia solo se cambia l'insieme di canali (chiave della cache su disco). */
    val signature: Int = 31 * ids.hashCode() + names.hashCode()

    companion object {
        fun forChannels(live: List<MediaItem>): XmlTvFilter {
            val ids = HashSet<String>(live.size * 3)
            val names = HashSet<String>(live.size)
            for (c in live) {
                c.metadataTag?.trim()?.takeIf { it.isNotEmpty() }?.let { ids += it.lowercase(Locale.ROOT) }
                ids += c.id.substringAfterLast(':').lowercase(Locale.ROOT)
                ids += c.title.trim().lowercase(Locale.ROOT)
                EpgNames.normalize(c.title).takeIf { it.isNotEmpty() }?.let { names += it }
            }
            return XmlTvFilter(ids, names)
        }
    }
}

/** Indice immutabile (id canale minuscolo -> programmi ordinati) con ricerca tollerante. */
class XmlTvIndex(
    val byId: Map<String, List<EpgProgram>>,
    val aliases: Map<String, String>,
    val version: Int,
    val ready: Boolean
) {
    /** Ordine: id EPG del canale, nome esatto, nome normalizzato (alias XMLTV), per ultimo l'id stream. */
    fun programsFor(tag: String?, streamId: String, title: String): List<EpgProgram> {
        if (byId.isEmpty()) return emptyList()
        tag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { t -> byId[t]?.let { return it } }
        byId[title.trim().lowercase(Locale.ROOT)]?.let { return it }
        val norm = EpgNames.normalize(title)
        if (norm.isNotEmpty()) aliases[norm]?.let { id -> byId[id]?.let { return it } }
        byId[streamId.lowercase(Locale.ROOT)]?.let { return it }
        return emptyList()
    }

    companion object {
        val EMPTY = XmlTvIndex(emptyMap(), emptyMap(), 0, ready = false)

        fun build(parts: List<XmlTvData>, version: Int): XmlTvIndex {
            val grouped = HashMap<String, ArrayList<EpgProgram>>()
            val aliases = HashMap<String, String>()
            for (part in parts) {
                for (p in part.programs) grouped.getOrPut(p.streamId.lowercase(Locale.ROOT)) { ArrayList() }.add(p)
                for ((k, v) in part.aliases) aliases.putIfAbsent(k, v)
            }
            val byId = HashMap<String, List<EpgProgram>>(grouped.size * 2)
            for ((k, v) in grouped) byId[k] = v.sortedBy { it.startMs }.distinctBy { it.startMs }
            return XmlTvIndex(byId, aliases, version, ready = true)
        }
    }
}

/**
 * Unisce la guida del pannello (primaria, piu' precisa) con l'XMLTV: dell'XMLTV si tengono solo i programmi
 * che NON si sovrappongono a quelli primari, cosi' passato e giorni successivi si riempiono senza duplicati.
 */
fun mergePrograms(primary: List<EpgProgram>, secondary: List<EpgProgram>): List<EpgProgram> {
    if (secondary.isEmpty()) return primary.sortedBy { it.startMs }
    if (primary.isEmpty()) return secondary.sortedBy { it.startMs }
    val p = primary.sortedBy { it.startMs }
    val firstStart = p.first().startMs
    val lastEnd = p.maxOf { it.endMs }
    val extra = secondary.filter { it.endMs <= firstStart || it.startMs >= lastEnd }
    return if (extra.isEmpty()) p else (p + extra).sortedBy { it.startMs }
}

private class FileCache(private val context: Context, val name: String) {
    private val file get() = context.filesDir.resolve(name)
    fun read(): EpgSnapshot = JsonStore.json.decodeFromString(file.readText())
    fun write(v: EpgSnapshot) { file.writeText(JsonStore.json.encodeToString(v)) }
}

object XmlTvParser {
    /** Compatibilita': solo i programmi, senza filtro. */
    fun parse(input: java.io.InputStream): List<EpgProgram> = parseFull(input, null).programs

    /**
     * Lettura in streaming. Con [filter] i programmi dei canali non presenti nelle playlist vengono
     * scartati PRIMA di leggerne orari e testi. Un file troncato (gzip interrotto, XML spezzato) non
     * butta piu' via tutto: si tengono i programmi letti fino a quel punto.
     */
    fun parseFull(input: java.io.InputStream, filter: XmlTvFilter?, now: Long = System.currentTimeMillis()): XmlTvData {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val from = now - XMLTV_PAST_MS
        val to = now + XMLTV_FUTURE_MS
        val out = ArrayList<EpgProgram>(8192)
        val aliases = HashMap<String, String>()
        val wantedChannels = HashSet<String>()
        val chNames = ArrayList<String>()
        var inChannel = false; var chId = ""
        var inProg = false; var skip = false
        var channel = ""; var start = 0L; var stop = 0L; var title = ""; var desc: String? = null
        var tag = ""
        var event = try { parser.eventType } catch (_: Exception) { return XmlTvData(out, aliases) }
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "channel") {
                        inChannel = true; chNames.clear()
                        chId = parser.getAttributeValue(null, "id").orEmpty()
                    } else if (tag == "programme") {
                        inProg = true; title = ""; desc = null
                        channel = parser.getAttributeValue(null, "channel").orEmpty()
                        val idl = channel.lowercase(Locale.ROOT)
                        skip = channel.isEmpty() || (filter != null && idl !in wantedChannels && idl !in filter.ids)
                        if (!skip) {
                            start = xmlTvTime(parser.getAttributeValue(null, "start").orEmpty()) ?: 0L
                            stop = xmlTvTime(parser.getAttributeValue(null, "stop").orEmpty()) ?: 0L
                            if (start <= 0L || stop <= start || stop <= from || start >= to) skip = true
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (inProg) {
                        if (!skip) {
                            val t = parser.text.trim()
                            if (t.isNotEmpty()) {
                                if (tag == "title" && title.isEmpty()) title = t
                                else if (tag == "desc" && desc == null) desc = t.take(DESC_MAX)
                            }
                        }
                    } else if (inChannel && tag == "display-name") {
                        val t = parser.text.trim()
                        if (t.isNotEmpty()) chNames += t
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            if (inProg && !skip) out += EpgProgram("$channel:$start", channel, title, desc, start, stop)
                            inProg = false; skip = false
                        }
                        "channel" -> {
                            if (inChannel && chId.isNotEmpty()) {
                                val idl = chId.lowercase(Locale.ROOT)
                                var hit = filter == null || idl in filter.ids
                                for (n in chNames) {
                                    val nn = EpgNames.normalize(n)
                                    if (nn.isEmpty()) continue
                                    if (filter == null || nn in filter.names) { aliases.putIfAbsent(nn, idl); hit = true }
                                }
                                if (hit) wantedChannels += idl
                            }
                            inChannel = false
                        }
                    }
                    tag = ""
                }
            }
            event = try { parser.next() } catch (_: Exception) { break }
        }
        return XmlTvData(out, aliases)
    }

    /**
     * Orario XMLTV (`yyyyMMddHHmmss [+hhmm]`). Accetta anche `Z`/`UTC`/`GMT`, `+hh:mm`, `+hh` e l'assenza
     * di fuso (fuso del telefono): prima un fuso come "UTC" scartava in silenzio tutti i programmi.
     * Niente DateTimeFormatter per chiamata: su guide da centinaia di migliaia di programmi pesava molto.
     */
    fun xmlTvTime(v: String): Long? {
        val raw = v.trim()
        if (raw.length < 12) return null
        fun num(from: Int, len: Int): Int? {
            var r = 0
            for (i in from until from + len) {
                val c = raw.getOrNull(i) ?: return null
                if (c !in '0'..'9') return null
                r = r * 10 + (c - '0')
            }
            return r
        }
        val y = num(0, 4) ?: return null
        val mo = num(4, 2) ?: return null
        val d = num(6, 2) ?: return null
        val h = num(8, 2) ?: return null
        val mi = num(10, 2) ?: return null
        val sec = if (raw.length >= 14) (num(12, 2) ?: 0) else 0
        val zone = if (raw.length > 14) raw.substring(14).trim() else ""
        return runCatching {
            val local = LocalDateTime.of(y, mo, d, h, mi, sec)
            val offset = offsetSeconds(zone)
            if (offset != null) local.toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000L
            else local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** Secondi di offset, o null = nessun fuso valido (si usa quello del telefono). */
    internal fun offsetSeconds(zone: String): Int? {
        var z = zone.trim().uppercase(Locale.ROOT)
        if (z.isEmpty()) return null
        if (z == "Z" || z == "UT") return 0
        z = z.removePrefix("UTC").removePrefix("GMT")
        if (z.isEmpty()) return 0
        val sign = when (z[0]) { '+' -> 1; '-' -> -1; else -> return null }
        val digits = z.drop(1).replace(":", "")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val hh = digits.take(2).toInt()
        val mm = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        return sign * (hh * 3600 + mm * 60)
    }
}

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonObject.valueString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
