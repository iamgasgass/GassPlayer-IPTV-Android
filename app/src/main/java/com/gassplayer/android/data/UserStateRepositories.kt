package com.gassplayer.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class FavoritesRepository(private val prefs: AppPreferences) {
    val flow: Flow<FavoriteState> = prefs.favoritesFlow
    suspend fun toggle(item: MediaItem) {
        val s = prefs.favoritesFlow.first(); val set = when (item.kind) { MediaKind.LIVE -> s.live; MediaKind.MOVIE -> s.movies; MediaKind.SERIES -> s.series; MediaKind.EPISODE -> s.movies }
        val new = set.toMutableSet().apply { if (!add(item.id)) remove(item.id) }
        prefs.saveFavorites(when (item.kind) { MediaKind.LIVE -> s.copy(live = new); MediaKind.MOVIE, MediaKind.EPISODE -> s.copy(movies = new); MediaKind.SERIES -> s.copy(series = new) })
    }
    fun contains(s: FavoriteState, item: MediaItem): Boolean = when (item.kind) { MediaKind.LIVE -> item.id in s.live; MediaKind.MOVIE, MediaKind.EPISODE -> item.id in s.movies; MediaKind.SERIES -> item.id in s.series }
}

class WatchHistoryRepository(private val prefs: AppPreferences) {
    val flow: Flow<List<WatchEntry>> = prefs.watchFlow
    /**
     * Records (or moves to the top) a title just played, like RecentlyWatchedStore.record on iOS: when this
     * call carries no image/category/series the previous ones are kept, and a new playback makes a title
     * removed from "Continua a guardare" visible again.
     */
    suspend fun upsert(entry: WatchEntry) {
        val current = prefs.watchFlow.first().toMutableList()
        val previous = current.firstOrNull { it.contentId == entry.contentId }
        current.removeAll { it.contentId == entry.contentId }
        current.add(0, entry.copy(
            lastWatchedMs = System.currentTimeMillis(),
            imageUrl = entry.imageUrl ?: previous?.imageUrl,
            subtitle = entry.subtitle ?: previous?.subtitle,
            seriesId = entry.seriesId ?: previous?.seriesId,
            sourceId = entry.sourceId ?: previous?.sourceId,
            dismissed = false
        ))
        prefs.saveWatch(current.take(100))
    }
    suspend fun clear() = prefs.saveWatch(emptyList())

    /** "Rimuovi" / "Svuota": hides entries from "Continua a guardare" without losing their resume position. */
    suspend fun dismiss(matches: (WatchEntry) -> Boolean) {
        val current = prefs.watchFlow.first()
        if (current.none { !it.dismissed && matches(it) }) return
        prefs.saveWatch(current.map { if (!it.dismissed && matches(it)) it.copy(dismissed = true) else it })
    }

    /** Aligns the card image with the detail sheet (RecentlyWatchedStore.updateImage), order unchanged. */
    suspend fun updateImage(url: String, matches: (WatchEntry) -> Boolean) {
        val current = prefs.watchFlow.first()
        if (current.none { matches(it) && it.imageUrl != url }) return
        prefs.saveWatch(current.map { if (matches(it) && it.imageUrl != url) it.copy(imageUrl = url) else it })
    }
}

class SearchHistoryRepository(private val prefs: AppPreferences) {
    val flow: Flow<SearchHistory> = prefs.searchFlow
    suspend fun add(term: String) { val t = term.trim(); if (t.isEmpty()) return; val old = prefs.searchFlow.first().terms.toMutableList(); old.removeAll { it.equals(t, true) }; old.add(0, t); prefs.saveSearch(SearchHistory(old.take(30))) }
    suspend fun remove(term: String) = prefs.saveSearch(SearchHistory(prefs.searchFlow.first().terms.filterNot { it == term }))
    suspend fun clear() = prefs.saveSearch(SearchHistory())
}
