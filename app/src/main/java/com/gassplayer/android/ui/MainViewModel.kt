package com.gassplayer.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(val app: GassPlayerApplication) : ViewModel() {
    val sources = app.sources.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = app.prefs.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val favorites = app.favorites.flow.stateIn(viewModelScope, SharingStarted.Eagerly, FavoriteState())
    val watch = app.watch.flow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val parental = app.parental.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ParentalState())
    val activeSource = app.sources.activeSource.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _catalog = MutableStateFlow<CatalogState?>(null)
    val catalog = _catalog.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val initialSettings = settings.first()
            val last = app.prefs.lastRefreshFlow().first()
            val interval = RefreshInterval.from(initialSettings.catalogRefreshInterval).millis
            val intervalDue = interval != null && (last == null || System.currentTimeMillis() - last >= interval)
            refresh(force = initialSettings.catalogRefreshOnLaunch || intervalDue)
        }
    }

    fun refresh(force: Boolean = true) = viewModelScope.launch {
        _loading.value = true
        val result = runCatching { app.catalog.loadAll(force) }
        _catalog.value = result.getOrNull()
        _message.value = result.exceptionOrNull()?.message ?: result.getOrNull()?.errors?.firstOrNull()
        _loading.value = false
        // Il timestamp rappresenta un aggiornamento realmente riuscito, non
        // un semplice tentativo fallito/parziale.
        if (force && result.isSuccess && result.getOrNull()?.errors.orEmpty().isEmpty()) {
            app.prefs.saveLastRefresh(System.currentTimeMillis())
        }
    }

    fun addSource(source: MediaSourceConfig) = viewModelScope.launch {
        app.sources.addOrUpdate(source)
        if (activeSource.value == null) app.sources.setActive(source.id)
        refresh(true)
    }

    fun deleteSource(id: String) = viewModelScope.launch {
        app.sources.delete(id)
        if (activeSource.value == id) app.sources.setActive(sources.value.firstOrNull()?.id)
        refresh(true)
    }

    fun toggleFavorite(item: MediaItem) = viewModelScope.launch { app.favorites.toggle(item) }
    fun toggleLock(id: String) = viewModelScope.launch { app.parental.toggleLock(id) }
    fun setActive(id: String?) = viewModelScope.launch { app.sources.setActive(id) }

    fun updateSettings(s: AppSettings) = viewModelScope.launch {
        app.prefs.saveSettings(s)
        app.playback.setSettings(s)
    }

    class Factory(private val app: GassPlayerApplication) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    }
}
