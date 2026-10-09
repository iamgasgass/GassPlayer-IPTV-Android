package com.gassplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.launch

@Composable
fun MovieDetailScreen(app: GassPlayerApplication, item: MediaItem, allItems: List<MediaItem>, favorite: FavoriteState, onBack: () -> Unit, onPlay: (MediaItem, Long) -> Unit) {
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val currentWatch = watch.firstOrNull { it.contentId == item.id }
    var meta by remember(item.id) { mutableStateOf<MetadataResult?>(null) }
    var ratings by remember(item.id) { mutableStateOf<Ratings?>(null) }
    var traktRating by remember(item.id) { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id, settings.tmdbApiKey, settings.omdbApiKey, settings.traktClientId) {
        meta = if (settings.tmdbApiKey.isNotBlank()) item.tmdbId?.let { runCatching { app.tmdb.details(it, settings.tmdbApiKey, false) }.getOrNull() } ?: runCatching { app.tmdb.search(item.title, settings.tmdbApiKey).firstOrNull() }.getOrNull() else null
        ratings = runCatching { app.omdb.lookup(item.title, settings.omdbApiKey) }.getOrNull()
        traktRating = runCatching { app.trakt.ratings(item.title, settings.traktClientId) }.getOrNull()
    }
    val alternates = remember(allItems, item.id) { allItems.sameTitleAs(item) }
    Column(Modifier.fillMaxSize()) {
        IconButton(onBack, modifier = Modifier.focusable()) { Icon(Icons.Default.ArrowBack, "Indietro") }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.width(300.dp).fillMaxHeight(.72f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0C0E13))) { (meta?.posterUrl ?: item.posterUrl)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius=20.dp, contentPadding=16.dp) { Text(meta?.title ?: item.title, color = glassForeground(), fontSize=30.sp, fontWeight=FontWeight.Bold) } }
                item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius=18.dp, contentPadding=15.dp) { Text(meta?.overview ?: item.plot.orEmpty(), color = glassForeground().copy(.78f), fontSize=16.sp) } }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) { FilledTonalButton({ onPlay(item, currentWatch?.positionMs ?: 0L) }, modifier=Modifier.focusable()){ Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(6.dp)); Text(if(currentWatch?.positionMs ?: 0L > 0) "Riprendi" else "Riproduci") }; Button({scope.launch{app.favorites.toggle(item)}}){Icon(if(item.id in favorite.movies)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null);Text("Preferito")}; OutlinedButton({app.downloads.enqueue(item, settings.downloadWifiOnly)}){Icon(Icons.Default.Download,null);Text("Download")} } }
                item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius=15.dp, contentPadding=12.dp) { Text("Rating: ${meta?.rating ?: item.rating ?: "—"} • IMDb ${ratings?.imdb ?: "—"} • RT ${ratings?.rottenTomatoes ?: "—"} • Metacritic ${ratings?.metacritic ?: "—"} • Trakt ${traktRating ?: "—"}", color = glassForeground().copy(.72f)) } }
                if(meta?.genres?.isNotEmpty()==true) item{LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius=15.dp, contentPadding=12.dp){Text("Generi: ${meta!!.genres.joinToString()}",color = glassForeground().copy(.78f))}}
                if(meta?.cast?.isNotEmpty()==true) item{LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius=15.dp, contentPadding=12.dp){Text("Cast: ${meta!!.cast.joinToString()}",color = glassForeground().copy(.78f))}}
                item{Text("Sorgenti alternative",color = glassForeground(),fontWeight=FontWeight.SemiBold)}
                if(alternates.isEmpty()) item{Text("Nessuna sorgente alternativa rilevata",color = glassForeground().copy(.55f))}
                items(alternates){alt->Surface(onClick={onPlay(alt,0L)},modifier=Modifier.fillMaxWidth().focusable(),shape=RoundedCornerShape(16.dp),color=Color.Transparent){LiquidGlassSurface(Modifier.fillMaxWidth(),cornerRadius=16.dp,contentPadding=0.dp){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(alt.title,maxLines=1,overflow=TextOverflow.Ellipsis,color = glassForeground(),modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));Text(alt.sourceId,color = glassForeground().copy(.55f),fontSize=12.sp)}}}}
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(app: GassPlayerApplication, series: MediaItem, episodes: List<MediaItem>, favorite: FavoriteState, onBack: () -> Unit, onPlay: (MediaItem, Long) -> Unit) {
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    var loadedEpisodes by remember(series.id) { mutableStateOf(episodes.filter { it.seriesId == series.id.substringAfterLast(':') }) }
    var loadingEpisodes by remember(series.id) { mutableStateOf(false) }
    var loadError by remember(series.id) { mutableStateOf<String?>(null) }
    var meta by remember(series.id) { mutableStateOf<MetadataResult?>(null) }
    val scope = rememberCoroutineScope()

    fun reloadEpisodes() {
        scope.launch {
            loadingEpisodes = true
            loadError = null
            val seriesKey = series.id.substringAfterLast(':')
            val result = runCatching { app.catalog.loadSeriesEpisodes(series.sourceId, seriesKey, series.title) }
            result.onSuccess { fetched ->
                loadedEpisodes = (loadedEpisodes + fetched).filter { it.seriesId == seriesKey }.distinctBy { it.id }
            }.onFailure { loadError = it.message ?: "Impossibile caricare gli episodi" }
            loadingEpisodes = false
        }
    }

    LaunchedEffect(series.id, settings.tmdbApiKey) {
        meta = if (settings.tmdbApiKey.isBlank()) null else runCatching { app.tmdb.search(series.title, settings.tmdbApiKey).firstOrNull() }.getOrNull()
    }
    LaunchedEffect(series.id) { reloadEpisodes() }

    val showEpisodes = loadedEpisodes.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title.lowercase() }))
    val seasons = showEpisodes.groupBy { it.seasonNumber ?: 0 }
    var season by remember(series.id) { mutableStateOf(0) }
    LaunchedEffect(showEpisodes) { if (seasons.isNotEmpty() && season !in seasons.keys) season = seasons.keys.minOrNull() ?: 0 }
    val scopeSettings = settings

    Column(Modifier.fillMaxSize()) {
        IconButton(onBack, modifier=Modifier.focusable()){Icon(Icons.Default.ArrowBack,"Indietro")}
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp)){
            Box(Modifier.width(300.dp).fillMaxHeight(.72f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0C0E13))){
                (meta?.posterUrl ?: series.posterUrl)?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            }
            Column(Modifier.fillMaxSize()){
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius=20.dp, contentPadding=16.dp) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(meta?.title ?: series.title,color = glassForeground(),fontSize=30.sp,fontWeight=FontWeight.Bold)
                        Text(meta?.overview ?: series.plot.orEmpty(),color = glassForeground().copy(.76f),maxLines=5,overflow=TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                    seasons.keys.sorted().forEach{s->FilterChip(s==season,{season=s},label={Text("S$s")})}
                    if (loadingEpisodes) CircularProgressIndicator(modifier=Modifier.size(22.dp), strokeWidth=2.dp)
                    OutlinedButton({ reloadEpisodes() }, enabled = !loadingEpisodes) { Text("Aggiorna") }
                }
                loadError?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text(err, color=Color(0xFFFF9E9E), maxLines=2, overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                if (!loadingEpisodes && seasons.isEmpty()) {
                    Text("Nessun episodio disponibile: il provider non ha restituito i dettagli della serie.", color = glassForeground().copy(.65f))
                } else {
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(bottom=40.dp)){
                        items(seasons[season].orEmpty()){ep->
                            val wp=watch.firstOrNull{it.contentId==ep.id}
                            Surface(onClick={onPlay(ep,wp?.positionMs?:0L)},modifier=Modifier.fillMaxWidth().focusable(),shape=RoundedCornerShape(16.dp),color=Color.Transparent){
                                LiquidGlassSurface(Modifier.fillMaxWidth(),cornerRadius=16.dp,contentPadding=0.dp){
                                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                                        Column(Modifier.weight(1f)){
                                            Text("E${ep.episodeNumber?:0} • ${ep.title}",color = glassForeground(),fontWeight=FontWeight.SemiBold)
                                            ep.plot?.let{Text(it,maxLines=2,overflow=TextOverflow.Ellipsis,color = glassForeground().copy(.65f))}
                                        }
                                        IconButton({app.downloads.enqueue(ep,scopeSettings.downloadWifiOnly)}){Icon(Icons.Default.Download,null)}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

