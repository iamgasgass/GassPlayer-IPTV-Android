package com.gassplayer.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Formati di backup dell'app iOS, per scambiare i file fra iPhone/iPad e Android nei due sensi.
 *
 * - `SourceBackupCodec.swift`: `{ "version": 1, "exportedAt": "<ISO-8601>", "sources": [MediaSourceConfig] }`,
 *   con `type` = "Xtream Codes" / "M3U / M3U8 Playlist" / "Plex" / "Jellyfin" / "Emby", `id` = UUID e
 *   `lastVerifiedAt` ISO-8601.
 * - `AppPreferencesBackupCodec.swift`: oggetto piatto (`autoplayNextEpisode`, `resumePlayback`,
 *   `preferredPlaybackSpeed`, `channelGridDensity`, `showChannelNumbers`, `subtitleLanguage`, `preferredDNS`,
 *   `downloadWifiOnly`, `theme` = "Automatico"/"Chiaro"/"Scuro", `catalogRefreshInterval`,
 *   `catalogRefreshOnLaunch`, `showEPGInChannelTiles`, `preloadSeries`, `epgAutoUpdateEnabled`).
 */
object IosBackupCodec {
    private val typeNames = mapOf(
        SourceType.XTREAM to "Xtream Codes",
        SourceType.M3U8 to "M3U / M3U8 Playlist",
        SourceType.PLEX to "Plex",
        SourceType.JELLYFIN to "Jellyfin",
        SourceType.EMBY to "Emby",
    )

    private fun iso(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun formatDate(ms: Long): String = iso().format(Date(ms))

    fun parseDate(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.let { seconds ->
            // JSONEncoder senza strategia: secondi dal 1° gennaio 2001 (reference date Apple).
            return ((seconds + 978_307_200.0) * 1000).toLong()
        }
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(value)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    /** Tipo sorgente da nome iOS ("Xtream Codes") o Android ("XTREAM"). */
    fun sourceType(raw: String?): SourceType? {
        val value = raw?.trim() ?: return null
        typeNames.entries.firstOrNull { it.value.equals(value, true) }?.let { return it.key }
        SourceType.entries.firstOrNull { it.name.equals(value, true) }?.let { return it }
        return when {
            value.contains("xtream", true) -> SourceType.XTREAM
            value.contains("m3u", true) -> SourceType.M3U8
            else -> null
        }
    }

    /** iOS richiede un UUID valido: gli id Android che non lo sono diventano un UUID stabile derivato. */
    fun iosId(id: String): String = runCatching { UUID.fromString(id).toString().uppercase(Locale.ROOT) }
        .getOrElse { UUID.nameUUIDFromBytes(id.toByteArray()).toString().uppercase(Locale.ROOT) }

    // MARK: - Sorgenti

    fun encodeSources(sources: List<MediaSourceConfig>, now: Long = System.currentTimeMillis()): String {
        val root = buildJsonObject {
            put("version", 1)
            put("exportedAt", formatDate(now))
            put("sources", buildJsonArray {
                sources.forEach { source ->
                    add(buildJsonObject {
                        put("id", iosId(source.id))
                        put("name", source.name)
                        put("type", typeNames[source.type] ?: "Xtream Codes")
                        // iOS tiene l'indirizzo della playlist M3U in `host`.
                        put("host", if (source.type == SourceType.XTREAM) source.host else source.playlistUrl ?: source.host)
                        source.username?.let { put("username", it) }
                        source.password?.let { put("password", it) }
                        source.apiToken?.let { put("apiToken", it) }
                        put("isEnabled", source.isEnabled)
                        put("sortOrder", source.sortOrder)
                        put("isPinned", source.isPinned)
                        source.lastVerifiedAt?.let { put("lastVerifiedAt", formatDate(it)) }
                        if (source.lastVerifiedAt != null) put("lastVerificationSucceeded", source.lastVerificationSucceeded)
                        put("lastKnownChannelCount", source.lastKnownChannelCount)
                        source.iconName?.let { put("iconName", it) }
                    })
                }
            })
        }
        return JsonStore.json.encodeToString(JsonObject.serializer(), root)
    }

    /** Sorgenti da un backup iOS; `null` se il JSON non ha questa forma. */
    fun decodeSources(root: JsonElement): List<MediaSourceConfig>? {
        val list = (root as? JsonObject)?.get("sources") as? JsonArray ?: (root as? JsonArray) ?: return null
        val sources = list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val type = sourceType(o.str("type")) ?: return@mapNotNull null
            val host = o.str("host") ?: return@mapNotNull null
            MediaSourceConfig(
                id = o.str("id") ?: UUID.randomUUID().toString(),
                name = o.str("name") ?: host,
                type = type,
                host = host,
                username = o.str("username"),
                password = o.str("password"),
                apiToken = o.str("apiToken"),
                playlistUrl = o.str("playlistUrl") ?: host.takeIf { type != SourceType.XTREAM },
                isEnabled = o.bool("isEnabled") ?: true,
                sortOrder = o.int("sortOrder") ?: 0,
                isPinned = o.bool("isPinned") ?: false,
                lastVerifiedAt = o["lastVerifiedAt"]?.let { (it as? JsonPrimitive)?.let { p -> p.longOrNullLenient() ?: parseDate(p.contentOrNull) } },
                lastVerificationSucceeded = o.bool("lastVerificationSucceeded") ?: false,
                lastKnownChannelCount = o.int("lastKnownChannelCount") ?: 0,
                iconName = o.str("iconName"),
            )
        }
        return sources.takeIf { it.isNotEmpty() || list.isEmpty() }
    }

    // MARK: - Preferenze

    fun isPreferencesBackup(root: JsonElement): Boolean =
        root is JsonObject && root.containsKey("autoplayNextEpisode") && root.containsKey("theme") && !root.containsKey("settings")

    fun applyPreferences(root: JsonObject, current: AppSettings): AppSettings = current.copy(
        autoplayNextEpisode = root.bool("autoplayNextEpisode") ?: current.autoplayNextEpisode,
        resumePlayback = root.bool("resumePlayback") ?: current.resumePlayback,
        preferredPlaybackSpeed = (root["preferredPlaybackSpeed"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: current.preferredPlaybackSpeed,
        density = root.str("channelGridDensity") ?: current.density,
        showChannelNumbers = root.bool("showChannelNumbers") ?: current.showChannelNumbers,
        subtitleLanguage = root.str("subtitleLanguage") ?: current.subtitleLanguage,
        preferredDns = root.str("preferredDNS") ?: current.preferredDns,
        downloadWifiOnly = root.bool("downloadWifiOnly") ?: current.downloadWifiOnly,
        theme = when (root.str("theme")) {
            "Automatico" -> "system"
            "Chiaro" -> "light"
            "Scuro" -> "dark"
            else -> current.theme
        },
        catalogRefreshInterval = root.str("catalogRefreshInterval")?.takeIf { id -> RefreshInterval.entries.any { it.id == id } } ?: current.catalogRefreshInterval,
        catalogRefreshOnLaunch = root.bool("catalogRefreshOnLaunch") ?: current.catalogRefreshOnLaunch,
        showEpgInChannelTiles = root.bool("showEPGInChannelTiles") ?: current.showEpgInChannelTiles,
        preloadSeries = root.bool("preloadSeries") ?: current.preloadSeries,
        epgAutoUpdateEnabled = root.bool("epgAutoUpdateEnabled") ?: current.epgAutoUpdateEnabled,
    )

    fun encodePreferences(settings: AppSettings, now: Long = System.currentTimeMillis()): String {
        val root = buildJsonObject {
            put("version", 1)
            put("exportedAt", formatDate(now))
            put("autoplayNextEpisode", settings.autoplayNextEpisode)
            put("resumePlayback", settings.resumePlayback)
            put("preferredPlaybackSpeed", settings.preferredPlaybackSpeed.toDouble())
            put("channelGridDensity", settings.density)
            put("showChannelNumbers", settings.showChannelNumbers)
            put("subtitleLanguage", settings.subtitleLanguage)
            put("preferredDNS", settings.preferredDns)
            put("downloadWifiOnly", settings.downloadWifiOnly)
            put("theme", when (settings.theme) { "light" -> "Chiaro"; "dark" -> "Scuro"; "system" -> "Automatico"; else -> "Scuro" })
            put("catalogRefreshInterval", settings.catalogRefreshInterval)
            put("catalogRefreshOnLaunch", settings.catalogRefreshOnLaunch)
            put("showEPGInChannelTiles", settings.showEpgInChannelTiles)
            put("preloadSeries", settings.preloadSeries)
            put("epgAutoUpdateEnabled", settings.epgAutoUpdateEnabled)
        }
        return JsonStore.json.encodeToString(JsonObject.serializer(), root)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != "null" }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
    private fun JsonPrimitive.longOrNullLenient(): Long? = if (isString) null else contentOrNull?.toLongOrNull()
}
