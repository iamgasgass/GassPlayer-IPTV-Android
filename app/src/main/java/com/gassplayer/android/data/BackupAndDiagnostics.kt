package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupService(private val context: Context, private val prefs: AppPreferences) {
    suspend fun exportSources(selectedSources: List<MediaSourceConfig>? = null): String =
        JsonStore.json.encodeToString(SourceBackup(sources = selectedSources ?: prefs.sourcesFlow.first()))
    suspend fun exportPreferences(): String = JsonStore.json.encodeToString(PreferencesBackup(settings = prefs.settingsFlow.first()))
    suspend fun importSources(json: String) { val p = JsonStore.json.decodeFromString<SourceBackup>(json); prefs.saveSources(p.sources) }
    suspend fun importPreferences(json: String) { val p = JsonStore.json.decodeFromString<PreferencesBackup>(json); prefs.saveSettings(p.settings) }
    suspend fun fullExport(): String = JsonStore.json.encodeToString(FullBackup(sources = prefs.sourcesFlow.first(), settings = prefs.settingsFlow.first()))
    suspend fun importAny(json: String) { val full = runCatching { JsonStore.json.decodeFromString<FullBackup>(json) }.getOrNull(); if (full != null) { prefs.saveSources(full.sources); prefs.saveSettings(full.settings); return }; runCatching { importSources(json) }.recoverCatching { importPreferences(json) }.getOrThrow() }
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
