package com.gassplayer.android.data

import android.app.backup.BackupManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Equivalente Android di CloudSyncService.swift (iCloud key-value store).
 *
 * iOS invia su iCloud, con "Sincronizza con iCloud", le SORGENTI (JSON di `MediaSourceConfig`, credenziali
 * comprese) e gli id dei PREFERITI; "Ripristina da iCloud" aggiunge solo le sorgenti non ancora presenti
 * (confronto per id).
 *
 * Prima Android chiedeva soltanto un Auto Backup: ma sorgenti e impostazioni nel DataStore sono cifrate con una
 * chiave dell'Android Keystore, che NON viene trasferita su un nuovo dispositivo, quindi dal backup non si
 * recuperava alcuna sorgente. Ora le stesse chiavi di iOS (`sync.sources`, `sync.favorites`) vengono scritte in
 * un file di preferenze dedicato incluso nel backup dell'account Google (cifrato end-to-end da Android 9 con il
 * blocco schermo, come iCloud), e lette dal pulsante "Ripristina".
 */
class AndroidCloudSync(private val context: Context, private val prefs: AppPreferences) {
    private val store get() = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    fun requestBackup() { BackupManager(context).dataChanged() }

    suspend fun snapshot(): CloudSnapshot = CloudSnapshot(prefs.sourcesFlow.first(), prefs.favoritesFlow.first(), prefs.watchFlow.first())

    /** iOS `syncToCloud`: pushSources + pushFavorites, poi richiesta di backup. */
    suspend fun syncToCloud() = withContext(Dispatchers.IO) {
        val sources = prefs.sourcesFlow.first()
        val favorites = prefs.favoritesFlow.first()
        val ids = (favorites.live + favorites.movies + favorites.series).toList()
        store.edit()
            .putString(SOURCES_KEY, JsonStore.json.encodeToString<List<MediaSourceConfig>>(sources))
            .putString(FAVORITES_KEY, JsonStore.json.encodeToString<List<String>>(ids))
            .commit()
        requestBackup()
    }

    /** iOS `pullSources`. */
    fun pullSources(): List<MediaSourceConfig>? = store.getString(SOURCES_KEY, null)?.let { raw ->
        runCatching { JsonStore.json.decodeFromString<List<MediaSourceConfig>>(raw) }.getOrNull()
    }

    /** iOS `pullFavorites`. */
    fun pullFavorites(): Set<String> = store.getString(FAVORITES_KEY, null)?.let { raw ->
        runCatching { JsonStore.json.decodeFromString<List<String>>(raw).toSet() }.getOrNull()
    }.orEmpty()

    /**
     * iOS `restoreSourcesFromCloud`: aggiunge solo le sorgenti assenti (per id). Restituisce il messaggio da
     * mostrare, con gli stessi testi dell'app iOS.
     */
    suspend fun restoreSources(): String {
        val pulled = pullSources()
        if (pulled.isNullOrEmpty()) return "Nessuna sorgente trovata nel backup."
        val existing = prefs.sourcesFlow.first().map { it.id }.toSet()
        val fresh = pulled.filter { it.id !in existing }
        if (fresh.isEmpty()) return "Le sorgenti salvate nel backup sono già tutte presenti su questo dispositivo."
        prefs.saveSources(prefs.sourcesFlow.first() + fresh)
        return "${fresh.size} sorgent${if (fresh.size == 1) "e" else "i"} ripristinat${if (fresh.size == 1) "a" else "e"} dal backup."
    }

    private companion object {
        const val STORE_NAME = "gassplayer_cloud_sync"
        const val SOURCES_KEY = "sync.sources"
        const val FAVORITES_KEY = "sync.favorites"
    }
}

@kotlinx.serialization.Serializable
data class CloudSnapshot(val sources: List<MediaSourceConfig>, val favorites: FavoriteState, val watch: List<WatchEntry>)
