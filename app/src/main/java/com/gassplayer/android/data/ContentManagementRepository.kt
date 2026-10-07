package com.gassplayer.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MergedPlaylistRepository(private val prefs: AppPreferences) {
    val flow: Flow<List<MergedPlaylist>> = prefs.mergedPlaylistsFlow
    suspend fun create(name: String, sourceIds: List<String>) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || sourceIds.isEmpty()) return
        val current = prefs.mergedPlaylistsFlow.first().toMutableList()
        current += MergedPlaylist(name = trimmed, memberSourceIds = sourceIds.distinct(), sortOrder = current.size)
        prefs.saveMergedPlaylists(current)
    }
    suspend fun rename(id: String, name: String) = prefs.saveMergedPlaylists(prefs.mergedPlaylistsFlow.first().map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    suspend fun remove(id: String) = prefs.saveMergedPlaylists(prefs.mergedPlaylistsFlow.first().filterNot { it.id == id })
    suspend fun reorder(from: Int, to: Int) {
        val current = prefs.mergedPlaylistsFlow.first().toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val item = current.removeAt(from); current.add(to, item)
        prefs.saveMergedPlaylists(current.mapIndexed { index, value -> value.copy(sortOrder = index) })
    }
}

class ExternalEpgRepository(private val prefs: AppPreferences) {
    val flow: Flow<List<ExternalEpgSource>> = prefs.externalEpgFlow
    suspend fun add(name: String, url: String): Boolean {
        val cleanName = name.trim(); val cleanUrl = url.trim()
        val parsed = runCatching { java.net.URI(cleanUrl) }.getOrNull()
        if (cleanName.isBlank() || parsed?.scheme?.lowercase() !in setOf("http", "https")) return false
        val current = prefs.externalEpgFlow.first().toMutableList()
        current += ExternalEpgSource(name = cleanName, urlString = cleanUrl)
        prefs.saveExternalEpg(current)
        return true
    }
    suspend fun toggle(id: String, enabled: Boolean) = prefs.saveExternalEpg(prefs.externalEpgFlow.first().map { if (it.id == id) it.copy(isEnabled = enabled) else it })
    suspend fun remove(id: String) = prefs.saveExternalEpg(prefs.externalEpgFlow.first().filterNot { it.id == id })
}
