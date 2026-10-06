package com.iamgasgass.gassplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.iamgasgass.gassplayer.data.AppStore
import com.iamgasgass.gassplayer.data.Catalog
import com.iamgasgass.gassplayer.data.EpgProgramme
import com.iamgasgass.gassplayer.data.MediaSource
import com.iamgasgass.gassplayer.data.SourceType
import com.iamgasgass.gassplayer.data.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class AppState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val sources: List<MediaSource> = emptyList(),
    val selectedSource: MediaSource? = null,
    val catalog: Catalog = Catalog(),
    val favorites: Set<String> = emptySet(),
    val progress: List<WatchProgress> = emptyList(),
    val epg: Map<String, List<EpgProgramme>> = emptyMap(),
    val error: String? = null,
    val compact: Boolean = false,
    val showNumbers: Boolean = true,
)

class MainViewModel(
    private val store: AppStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun reload(force: Boolean = false) = viewModelScope.launch {
        _state.update {
            it.copy(
                loading = force || it.catalog.channels.isEmpty(),
                refreshing = force,
                error = null,
            )
        }

        runCatching {
            val sources = store.sources()
            val previousId = _state.value.selectedSource?.id
            val selected = sources.firstOrNull { it.id == previousId }
                ?: sources.firstOrNull { it.enabled }
                ?: sources.firstOrNull()
            val catalog = selected?.let { store.loadCatalog(it, force) } ?: Catalog()

            AppState(
                loading = false,
                refreshing = false,
                sources = sources,
                selectedSource = selected,
                catalog = catalog,
                favorites = store.favorites(),
                progress = store.progress(),
                epg = emptyMap(),
                compact = _state.value.compact,
                showNumbers = _state.value.showNumbers,
            )
        }.onSuccess { newState ->
            _state.value = newState
        }.onFailure { error ->
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = error.message ?: "Errore durante il caricamento",
                )
            }
        }
    }

    fun select(source: MediaSource) {
        _state.update {
            it.copy(
                selectedSource = source,
                loading = true,
                error = null,
                epg = emptyMap(),
            )
        }
        reload()
    }

    fun add(
        name: String,
        type: SourceType,
        url: String,
        user: String,
        password: String,
        epgUrl: String = "",
    ) = viewModelScope.launch {
        runCatching {
            val source = store.addSource(name, type, url, user, password, epgUrl)
            if (!store.verify(source)) {
                store.removeSource(source.id)
                error("La sorgente non risponde o le credenziali non sono valide")
            }
        }.onSuccess {
            reload(true)
        }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "Impossibile aggiungere la sorgente") }
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        store.removeSource(id)
        reload(true)
    }

    fun favorite(id: String) = viewModelScope.launch {
        store.toggleFavorite(id)
        _state.update { it.copy(favorites = store.favorites()) }
    }

    fun setCompact(value: Boolean) = _state.update { it.copy(compact = value) }

    fun setNumbers(value: Boolean) = _state.update { it.copy(showNumbers = value) }

    fun saveProgress(progress: WatchProgress) = viewModelScope.launch {
        store.saveProgress(progress)
        _state.update { it.copy(progress = store.progress()) }
    }

    suspend fun episodes(id: String) =
        _state.value.selectedSource?.let { store.episodes(it, id) }.orEmpty()

    suspend fun epg(channelId: String): List<EpgProgramme> {
        _state.value.epg[channelId]?.let { return it }
        val source = _state.value.selectedSource ?: return emptyList()

        return runCatching { store.epg(source, channelId) }
            .getOrElse { emptyList() }
            .also { programmes ->
                _state.update { current ->
                    current.copy(epg = current.epg + (channelId to programmes))
                }
            }
    }

    suspend fun preloadEpg(maxChannels: Int = 8) = supervisorScope {
        val channels = _state.value.catalog.channels.take(maxChannels)
        channels.map { channel ->
            async(Dispatchers.IO) { channel.id to epg(channel.id) }
        }.awaitAll()
    }

    suspend fun export(): String = store.export()

    fun import(raw: String) = viewModelScope.launch {
        runCatching { store.import(raw) }
            .onSuccess { reload(true) }
            .onFailure { error ->
                _state.update {
                    it.copy(error = error.message ?: "Backup JSON non valido")
                }
            }
    }

    companion object {
        fun factory(store: AppStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(store) as T
            }
    }
}
