package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class SourceRepository(private val context: Context, private val prefs: AppPreferences, private val xtream: XtreamRepository, private val network: NetworkApi) {
    val sources: Flow<List<MediaSourceConfig>> = prefs.sourcesFlow
    val activeSource: Flow<String?> = prefs.activeSourceFlow

    suspend fun addOrUpdate(source: MediaSourceConfig) {
        val list = prefs.sourcesFlow.first().toMutableList()
        val idx = list.indexOfFirst { it.id == source.id }
        if (idx >= 0) list[idx] = source else list += source.copy(sortOrder = list.size)
        prefs.saveSources(list.sortedBy { it.sortOrder })
    }

    suspend fun delete(id: String) = prefs.saveSources(prefs.sourcesFlow.first().filterNot { it.id == id })
    suspend fun setActive(id: String?) = prefs.saveActiveSource(id)
    suspend fun toggle(id: String, enabled: Boolean) = updateById(id) { it.copy(isEnabled = enabled) }
    suspend fun pin(id: String, pinned: Boolean) = updateById(id) { it.copy(isPinned = pinned) }
    suspend fun rename(id: String, name: String) = updateById(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    suspend fun duplicate(id: String) { prefs.sourcesFlow.first().firstOrNull { it.id == id }?.let { addOrUpdate(it.copy(id = UUID.randomUUID().toString(), name = "${it.name} copia", isPinned = false, sortOrder = Int.MAX_VALUE)) } }

    suspend fun verify(source: MediaSourceConfig): Result<Int> = runCatching {
        val count = when (source.type) {
            SourceType.XTREAM -> { val catalog = xtream.loadCatalog(source); catalog.live.size + catalog.movies.size }
            SourceType.M3U8 -> {
                val playlistUrl = source.playlistUrl ?: source.host
                val sourceHeaders = NetworkApi.extractInlineHeaders(playlistUrl)
                network.getStream(
                    playlistUrl,
                    mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*"),
                    defaultAccept = "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*"
                ) { input, finalUrl ->
                    M3UParser.parse(
                        source.id,
                        input,
                        NetworkApi.stripInlineHeaders(finalUrl),
                        defaultHeaders = sourceHeaders
                    ).size
                }
            }
            else -> 0
        }
        updateById(source.id) { it.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = true, lastKnownChannelCount = count) }
        count
    }.onFailure { updateById(source.id) { s -> s.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = false) } }

    private suspend fun updateById(id: String, block: (MediaSourceConfig) -> MediaSourceConfig) {
        prefs.saveSources(prefs.sourcesFlow.first().map { if (it.id == id) block(it) else it })
    }
}
