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
    /** Compatibility alias used by the iOS-style source manager. */
    val currentSource = activeSource

    private val _catalog = MutableStateFlow<CatalogState?>(null)
    val catalog = _catalog.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    /** "Guarda tutte le liste insieme": Live TV di tutte le sorgenti abilitate (separato dal catalogo attivo). */
    private val _allSourcesLive = MutableStateFlow<CatalogState?>(null)
    val allSourcesLive = _allSourcesLive.asStateFlow()

    /** Aggiornamento in corso e se e' "forzato" (rete per tutte le sorgenti). */
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var refreshJobForced = false
    private var refreshJobTargets: Set<String>? = null
    private var refreshJobKinds: Set<MediaKind>? = null

    init {
        // Cataloghi ricomposti altrove (aggiornamento automatico in background): la UI li mostra subito.
        viewModelScope.launch { app.catalog.updates.collect { if (isForActiveSource(it)) _catalog.value = it } }
        viewModelScope.launch {
            val initialSettings = settings.first()
            val last = app.prefs.lastRefreshFlow().first()
            val interval = RefreshInterval.from(initialSettings.catalogRefreshInterval).millis
            val intervalDue = interval != null && (last == null || System.currentTimeMillis() - last >= interval)
            refresh(force = initialSettings.catalogRefreshOnLaunch || intervalDue)
        }
    }


    /**
     * [force] = riscarica dalla rete ([onlySources] = solo quelle sorgenti); senza force si usa la cache su disco
     * e la rete solo per sorgenti nuove, modificate o scadute.
     * - Il catalogo in cache compare SUBITO (prima l'app restava vuota finche' la rete non finiva).
     * - Una richiesta gia' coperta da quella in corso non ne avvia un'altra (tap ripetuti, piu' schermate).
     */
    fun refresh(
        force: Boolean = true,
        onlySources: Set<String>? = null,
        /** Solo queste sezioni (es. "Ricarica Live TV"): come iOS `refresh(kind:)`, le altre restano dalla cache. */
        onlyKinds: Set<MediaKind>? = null,
    ): kotlinx.coroutines.Job {
        val running = refreshJob
        if (running != null && running.isActive) {
            // Solo un download forzato gia' in corso che copre le stesse sorgenti E sezioni assorbe la nuova richiesta;
            // le richieste dalla cache (economiche) vengono sempre eseguite dopo, per vedere ogni modifica.
            val targets = refreshJobTargets
            val kindsCovered = refreshJobKinds == null || (onlyKinds != null && refreshJobKinds!!.containsAll(onlyKinds))
            val covered = force && refreshJobForced && kindsCovered && (targets == null || (onlySources != null && targets.containsAll(onlySources)))
            if (covered) return running
        }
        val job = viewModelScope.launch {
            running?.join()
            _loading.value = true
            if (_catalog.value == null) {
                runCatching { app.catalog.cachedState() }.getOrNull()?.let { _catalog.value = it }
            }
            val result = runCatching { app.catalog.loadAll(force, onlySources, onlyKinds) }
            // Un errore di rete non deve svuotare l'app: resta il catalogo gia' mostrato (cache).
            // Scartato se nel frattempo e' stata scelta un'altra sorgente (menu "Cambia" della Home).
            result.getOrNull()?.takeIf { isForActiveSource(it) }?.let { _catalog.value = it }
            // La vista "Guarda tutte le liste insieme", se aperta, rilegge i canali aggiornati dalla cache.
            if (_allSourcesLive.value != null) runCatching { app.catalog.allSourcesLiveState() }.getOrNull()?.let { _allSourcesLive.value = it }
            _message.value = result.exceptionOrNull()?.message ?: result.getOrNull()?.errors?.firstOrNull()
            _loading.value = false
            // Il timestamp rappresenta un aggiornamento realmente riuscito, non
            // un semplice tentativo fallito/parziale.
            if (force && onlySources == null && onlyKinds == null && result.isSuccess && result.getOrNull()?.errors.orEmpty().isEmpty()) {
                app.prefs.saveLastRefresh(System.currentTimeMillis())
            }
        }
        refreshJob = job
        refreshJobForced = force
        refreshJobTargets = onlySources
        refreshJobKinds = onlyKinds
        return job
    }

    fun addSource(source: MediaSourceConfig) = viewModelScope.launch {
        val isNew = app.sources.sources.first().none { it.id == source.id }
        app.sources.addOrUpdate(source)
        // Come iOS SourceManager.add: una sorgente appena aggiunta diventa quella attiva (e' l'unica mostrata).
        if (isNew || activeSource.value == null) {
            app.sources.setActive(source.id)
            _catalog.value = null
        }
        // Solo la sorgente nuova/modificata va scaricata: le altre restano dalla cache.
        refresh(force = true, onlySources = setOf(source.id))
    }

    fun deleteSource(id: String) = viewModelScope.launch {
        // Capture the list before deletion, then select a surviving enabled source.
        val before = app.sources.sources.first()
        app.sources.delete(id)
        if (activeSource.value == id) {
            val next = before.filterNot { it.id == id }.firstOrNull { it.isEnabled }
                ?: before.firstOrNull { it.id != id }
            app.sources.setActive(next?.id)
        }
        // Eliminare una sorgente non richiede download: si rimuove dalla cache e si ricompone il catalogo.
        refresh(false)
    }

    fun toggleFavorite(item: MediaItem) = viewModelScope.launch { app.favorites.toggle(item) }
    fun toggleLock(id: String) = viewModelScope.launch { app.parental.toggleLock(id) }
    fun setActive(id: String?) = viewModelScope.launch {
        if (id != null && id == app.prefs.activeSourceFlow.first()) return@launch
        app.sources.setActive(id)
        // Come iOS (ContentView.loadActiveSource): Home, Live TV, Film, Serie, Guida e Ricerca mostrano SOLO la
        // sorgente scelta. Il suo catalogo in cache compare subito; senza cache la vista si svuota (niente canali
        // della sorgente precedente) finche' il caricamento non termina. La rete si usa solo se serve.
        _catalog.value = runCatching { app.catalog.cachedState() }.getOrNull()?.takeIf { isForActiveSource(it) }
        refresh(false)
    }

    /** True se [state] e' il catalogo della sorgente attiva in questo momento. */
    private suspend fun isForActiveSource(state: CatalogState): Boolean {
        val active = app.prefs.activeSourceFlow.first() ?: return true
        return state.sourceIds.isEmpty() || state.sourceIds == listOf(active)
    }

    /** Apre "Guarda tutte le liste insieme" con i canali in cache di tutte le sorgenti abilitate. */
    fun openAllSourcesLive() = viewModelScope.launch {
        _allSourcesLive.value = runCatching { app.catalog.allSourcesLiveState() }.getOrNull()
    }

    fun closeAllSourcesLive() { _allSourcesLive.value = null }

    fun updateSettings(s: AppSettings) = viewModelScope.launch {
        app.prefs.saveSettings(s)
        app.playback.setSettings(s)
    }

    class Factory(private val app: GassPlayerApplication) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    }
}
