package com.iamgasgass.gassplayer.ui
import androidx.lifecycle.*
import com.iamgasgass.gassplayer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AppState(val loading:Boolean=true,val refreshing:Boolean=false,val sources:List<MediaSource> = emptyList(),val selectedSource:MediaSource?=null,val catalog:Catalog=Catalog(),val favorites:Set<String> = emptySet(),val progress:List<WatchProgress> = emptyList(),val error:String?=null,val compact:Boolean=false,val showNumbers:Boolean=true)
class MainViewModel(private val store:AppStore):ViewModel(){
 private val _state=MutableStateFlow(AppState());val state=_state.asStateFlow()
 init{reload()}
 fun reload(force:Boolean=false)=viewModelScope.launch{_state.update{it.copy(loading=it.catalog.channels.isEmpty(),refreshing=force,error=null)};runCatching{val sources=store.sources();val selected=_state.value.selectedSource?.let{s->sources.firstOrNull{it.id==s.id}}?:sources.firstOrNull{it.enabled};val catalog=selected?.let{store.loadCatalog(it,force)}?:Catalog();AppState(false,false,sources,selected,catalog,store.favorites(),store.progress(),compact=_state.value.compact,showNumbers=_state.value.showNumbers)}.onSuccess{_state.value=it}.onFailure{e->_state.update{it.copy(loading=false,refreshing=false,error=e.message?:"Errore sconosciuto")}}}
 fun select(s:MediaSource){_state.update{it.copy(selectedSource=s)};reload()}
 fun add(name:String,type:SourceType,url:String,user:String,pass:String)=viewModelScope.launch{runCatching{store.addSource(name.ifBlank{"Nuova sorgente"},type,url,user,pass)}.onSuccess{reload(true)}.onFailure{e->_state.update{it.copy(error=e.message)}}}
 fun delete(id:String)=viewModelScope.launch{store.removeSource(id);reload()}
 fun favorite(id:String)=viewModelScope.launch{store.toggleFavorite(id);_state.update{it.copy(favorites=store.favorites())}}
 fun setCompact(v:Boolean)=_state.update{it.copy(compact=v)}
 fun setNumbers(v:Boolean)=_state.update{it.copy(showNumbers=v)}
 fun saveProgress(p:WatchProgress)=viewModelScope.launch{store.saveProgress(p);_state.update{it.copy(progress=store.progress())}}
 suspend fun episodes(id:String)=_state.value.selectedSource?.let{store.episodes(it,id)}?:emptyList()
 suspend fun export()=store.export(); fun import(raw:String)=viewModelScope.launch{runCatching{store.import(raw)}.onSuccess{reload(true)}.onFailure{e->_state.update{it.copy(error=e.message)}}}
 companion object{fun factory(store:AppStore)=object:ViewModelProvider.Factory{override fun <T:ViewModel> create(modelClass:Class<T>):T=@Suppress("UNCHECKED_CAST")(MainViewModel(store) as T)}}
}
