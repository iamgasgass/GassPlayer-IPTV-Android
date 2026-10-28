package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupService(private val context: Context, private val prefs: AppPreferences) {
    /** Backup sorgenti nel formato Android dell'allegato (invariato). */
    suspend fun exportSources(selectedSources: List<MediaSourceConfig>? = null): String =
        JsonStore.json.encodeToString(SourceBackup(sources = selectedSources ?: prefs.sourcesFlow.first()))
    /** Backup sorgenti nel formato di SourceBackupCodec.swift, da incollare in "Importa backup" sull'app iOS. */
    suspend fun exportSourcesForIos(selectedSources: List<MediaSourceConfig>? = null): String =
        IosBackupCodec.encodeSources(selectedSources ?: prefs.sourcesFlow.first())
    suspend fun exportPreferences(): String = JsonStore.json.encodeToString(PreferencesBackup(settings = prefs.settingsFlow.first()))
    /** Preferenze nel formato di AppPreferencesBackupCodec.swift (incollabile in Impostazioni → Importa su iOS). */
    suspend fun exportPreferencesForIos(): String = IosBackupCodec.encodePreferences(prefs.settingsFlow.first())

    /**
     * iOS `SourceManager.importSources`: aggiunge solo le sorgenti nuove (stesso host, senza distinzione di
     * maiuscole, e stesso utente = già presente), con nuovo id, in coda e non fissate. Prima Android sostituiva
     * TUTTE le sorgenti esistenti con quelle del file. Restituisce quante ne ha aggiunte.
     */
    suspend fun importSources(json: String): Int {
        val root = JsonStore.json.parseToJsonElement(json)
        val imported = IosBackupCodec.decodeSources(root) ?: error("Il testo non è un backup GassPlayer valido.")
        return mergeSources(imported)
    }

    private suspend fun mergeSources(imported: List<MediaSourceConfig>): Int {
        val current = prefs.sourcesFlow.first().toMutableList()
        var added = 0
        for (source in imported) {
            val duplicate = current.any { it.host.equals(source.host, ignoreCase = true) && it.username == source.username }
            if (duplicate) continue
            // Come l'allegato Android, la sorgente ripristinata conserva id, ordine e "fissata" quando non entrano in
            // conflitto con quelle già presenti (così preferiti e cronologia restano collegati); altrimenti nuovo id
            // e coda dell'elenco come su iOS.
            val idFree = current.none { it.id == source.id }
            current += if (idFree) source else source.copy(id = java.util.UUID.randomUUID().toString(), sortOrder = current.size, isPinned = false)
            added++
        }
        if (added > 0) prefs.saveSources(current)
        return added
    }

    suspend fun importPreferences(json: String) {
        val root = JsonStore.json.parseToJsonElement(json)
        if (IosBackupCodec.isPreferencesBackup(root)) {
            prefs.saveSettings(IosBackupCodec.applyPreferences(root as kotlinx.serialization.json.JsonObject, prefs.settingsFlow.first()))
            return
        }
        val p = JsonStore.json.decodeFromString<PreferencesBackup>(json); prefs.saveSettings(p.settings)
    }
    suspend fun fullExport(): String = JsonStore.json.encodeToString(FullBackup(sources = prefs.sourcesFlow.first(), settings = prefs.settingsFlow.first()))

    /**
     * Accetta: backup completo Android (ripristino integrale), preferenze iOS o Android, sorgenti iOS o Android
     * (unione come su iOS).
     */
    suspend fun importAny(json: String) {
        val full = runCatching { JsonStore.json.decodeFromString<FullBackup>(json) }.getOrNull()
        if (full != null) { prefs.saveSources(full.sources); prefs.saveSettings(full.settings); return }
        val root = JsonStore.json.parseToJsonElement(json)
        if (IosBackupCodec.isPreferencesBackup(root) || (root as? kotlinx.serialization.json.JsonObject)?.containsKey("settings") == true) {
            importPreferences(json); return
        }
        importSources(json)
    }
}

class DiagnosticsService(private val context: Context) {
    private val file get() = File(context.filesDir, "gassplayer_debug.log")
    fun log(level: String, tag: String, message: String) { val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()); file.appendText("$now [$level][$tag] $message\n") }
    fun read(): String = file.takeIf { it.exists() }?.readText().orEmpty()
    fun clear() { file.delete() }
    fun cacheCount(prefix: String? = null): Int = context.cacheDir.listFiles()?.count { prefix == null || it.name.startsWith(prefix) } ?: 0
}

@kotlinx.serialization.Serializable
data class FullBackup(val version:Int=1,val exportedAt:Long=System.currentTimeMillis(),val sources:List<MediaSourceConfig>,val settings:AppSettings)
