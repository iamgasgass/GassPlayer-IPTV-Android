package com.gassplayer.android.data

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gassDataStore by preferencesDataStore("gassplayer_preferences")

class AppPreferences(private val context: Context) {
    private object Keys {
        val json = stringPreferencesKey("settings_json")
        val sourcesJson = stringPreferencesKey("sources_json")
        val favoritesJson = stringPreferencesKey("favorites_json")
        val historyJson = stringPreferencesKey("watch_json")
        val searchJson = stringPreferencesKey("search_history_json")
        val activeSource = stringPreferencesKey("active_source")
        val parentalJson = stringPreferencesKey("parental_json")
        val vpnJson = stringPreferencesKey("vpn_json")
        val traktJson = stringPreferencesKey("trakt_json")
        val externalEpgJson = stringPreferencesKey("external_epg_json")
        val mergedPlaylistsJson = stringPreferencesKey("merged_playlists_json")
        val debug = booleanPreferencesKey("debug")
        val lastRefresh = androidx.datastore.preferences.core.longPreferencesKey("last_refresh")
        val sleepTimer = intPreferencesKey("sleep_timer")
    }

    val settingsFlow: Flow<AppSettings> = context.gassDataStore.data.map { prefs ->
        val decodedSettings = prefs[Keys.json]?.let { reveal(it) }
        val stored = decodedSettings?.let { decoded ->
            runCatching { JsonStore.json.decodeFromString<AppSettings>(decoded) }.getOrNull()
        }
        val isTelevision = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        // Fresh installs: dark theme (model default) and, on TV, software decoding by default.
        val softwareByDefault = isTelevision || context.isTvOrFireTvDevice()
        var value = stored ?: AppSettings(
            groupUIStyle = defaultGroupUIStyle(isTelevision),
            hardwareDecode = !softwareByDefault,
            softwareDecode = softwareByDefault,
        )

        // The previous mobile factory layout was Scorrevole and older settings had no marker to
        // distinguish that default from an old manual choice. Apply the new app-wide default to
        // unversioned mobile settings; preserve non-default styles and versioned user selections.
        val hasGroupUiDefaultsVersion = decodedSettings?.contains("\"groupUIStyleDefaultsVersion\"") == true
        if (stored != null && !hasGroupUiDefaultsVersion) {
            value = stored.copy(
                groupUIStyle = migratedGroupUIStyle(stored.groupUIStyle, isTelevision, hasGroupUiDefaultsVersion),
                groupUIStyleDefaultsVersion = 1,
            )
        }

        // Older versions stored the library sections as visible by default. Because those saved
        // JSON documents contain explicit empty hidden-sets, changing Kotlin defaults alone is not
        // enough. Migrate legacy, untouched section layouts once; the version marker ensures a
        // user who later re-enables Categorie is never overridden by this migration again.
        val hasSectionDefaultsVersion = decodedSettings?.contains("\"librarySectionDefaultsVersion\"") == true
        if (stored != null && !hasSectionDefaultsVersion) {
            value = value.copy(
                hiddenLiveSections = migratedLegacyLibraryHiddenSections(stored.liveSectionOrder, stored.hiddenLiveSections, defaultLiveSections),
                hiddenMovieSections = migratedLegacyLibraryHiddenSections(stored.movieSectionOrder, stored.hiddenMovieSections, defaultMovieSections),
                hiddenSeriesSections = migratedLegacyLibraryHiddenSections(stored.seriesSectionOrder, stored.hiddenSeriesSections, defaultSeriesSections),
                librarySectionDefaultsVersion = 2
            )
        }

        // Android TV / Fire TV: software decoding is the factory default. Settings saved before this
        // marker existed get it applied once; later manual choices are stored with the marker and kept.
        val hasDecoderDefaultsVersion = decodedSettings?.contains("\"decoderDefaultsVersion\"") == true
        if (stored != null && !hasDecoderDefaultsVersion && softwareByDefault) {
            value = value.copy(hardwareDecode = false, softwareDecode = true, decoderDefaultsVersion = 1)
        }

        // The previous app release shipped 15/90 seconds as factory defaults.
        // Migrate that exact old default pair so upgrades also receive the new 3/30 defaults.
        if (stored != null && value.minBufferSec == 15 && value.maxBufferSec == 90) {
            value.copy(minBufferSec = 3, maxBufferSec = 30)
        } else value
    }
    val sourcesFlow: Flow<List<MediaSourceConfig>> = context.gassDataStore.data.map { it[Keys.sourcesJson]?.let { s -> reveal(s)?.let { decoded -> runCatching { JsonStore.json.decodeFromString<List<MediaSourceConfig>>(decoded) }.getOrNull() } } ?: emptyList() }
    val favoritesFlow: Flow<FavoriteState> = context.gassDataStore.data.map { it[Keys.favoritesJson]?.let { s -> runCatching { JsonStore.json.decodeFromString<FavoriteState>(s) }.getOrNull() } ?: FavoriteState() }
    val watchFlow: Flow<List<WatchEntry>> = context.gassDataStore.data.map { it[Keys.historyJson]?.let { s -> runCatching { JsonStore.json.decodeFromString<List<WatchEntry>>(s) }.getOrNull() } ?: emptyList() }
    val searchFlow: Flow<SearchHistory> = context.gassDataStore.data.map { it[Keys.searchJson]?.let { s -> runCatching { JsonStore.json.decodeFromString<SearchHistory>(s) }.getOrNull() } ?: SearchHistory() }
    val activeSourceFlow: Flow<String?> = context.gassDataStore.data.map { it[Keys.activeSource] }
    val parentalFlow: Flow<ParentalState> = context.gassDataStore.data.map { it[Keys.parentalJson]?.let { s -> runCatching { JsonStore.json.decodeFromString<ParentalState>(s) }.getOrNull() } ?: ParentalState() }
    val vpnFlow: Flow<VPNConfig> = context.gassDataStore.data.map { it[Keys.vpnJson]?.let { s -> reveal(s)?.let { decoded -> runCatching { JsonStore.json.decodeFromString<VPNConfig>(decoded) }.getOrNull() } } ?: VPNConfig() }
    val traktFlow: Flow<TraktAccount> = context.gassDataStore.data.map { it[Keys.traktJson]?.let { s -> reveal(s)?.let { decoded -> runCatching { JsonStore.json.decodeFromString<TraktAccount>(decoded) }.getOrNull() } } ?: TraktAccount() }
    val externalEpgFlow: Flow<List<ExternalEpgSource>> = context.gassDataStore.data.map { it[Keys.externalEpgJson]?.let { s -> reveal(s)?.let { decoded -> runCatching { JsonStore.json.decodeFromString<List<ExternalEpgSource>>(decoded) }.getOrNull() } } ?: emptyList() }
    val mergedPlaylistsFlow: Flow<List<MergedPlaylist>> = context.gassDataStore.data.map { it[Keys.mergedPlaylistsJson]?.let { s -> reveal(s)?.let { decoded -> runCatching { JsonStore.json.decodeFromString<List<MergedPlaylist>>(decoded) }.getOrNull() } } ?: emptyList() }

    private fun protect(value: String): String = SecurePrefsCodec.encrypt(value)
    private fun reveal(value: String): String? = SecurePrefsCodec.decrypt(value) ?: value // backwards-compatible migration from older plaintext builds

    suspend fun saveSettings(value: AppSettings) = context.gassDataStore.edit { it[Keys.json] = protect(JsonStore.json.encodeToString(value)) }
    suspend fun saveSources(value: List<MediaSourceConfig>) = context.gassDataStore.edit { it[Keys.sourcesJson] = protect(JsonStore.json.encodeToString(value)) }
    suspend fun saveFavorites(value: FavoriteState) = context.gassDataStore.edit { it[Keys.favoritesJson] = JsonStore.json.encodeToString(value) }
    suspend fun saveWatch(value: List<WatchEntry>) = context.gassDataStore.edit { it[Keys.historyJson] = JsonStore.json.encodeToString(value) }
    suspend fun saveSearch(value: SearchHistory) = context.gassDataStore.edit { it[Keys.searchJson] = JsonStore.json.encodeToString(value) }
    suspend fun saveActiveSource(id: String?) = context.gassDataStore.edit { if (id == null) it.remove(Keys.activeSource) else it[Keys.activeSource] = id }
    suspend fun saveParental(value: ParentalState) = context.gassDataStore.edit { it[Keys.parentalJson] = JsonStore.json.encodeToString(value) }
    suspend fun saveVpn(value: VPNConfig) = context.gassDataStore.edit { it[Keys.vpnJson] = protect(JsonStore.json.encodeToString(value)) }
    suspend fun saveTrakt(value: TraktAccount) = context.gassDataStore.edit { it[Keys.traktJson] = protect(JsonStore.json.encodeToString(value)) }
    suspend fun saveExternalEpg(value: List<ExternalEpgSource>) = context.gassDataStore.edit { it[Keys.externalEpgJson] = protect(JsonStore.json.encodeToString(value)) }
    suspend fun saveMergedPlaylists(value: List<MergedPlaylist>) = context.gassDataStore.edit { it[Keys.mergedPlaylistsJson] = protect(JsonStore.json.encodeToString(value)) }
    suspend fun saveLastRefresh(value: Long) = context.gassDataStore.edit { it[Keys.lastRefresh] = value }
    suspend fun lastRefreshFlow(): Flow<Long?> = context.gassDataStore.data.map { it[Keys.lastRefresh] }
    suspend fun clearAll() = context.gassDataStore.edit { it.clear() }
}

@kotlinx.serialization.Serializable
data class ParentalState(val enabled: Boolean = false, val pinHash: String = "", val lockedIds: Set<String> = emptySet())
