package com.gassplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
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
    val alternates = allItems.filter { it.kind == item.kind && it.id != item.id && it.title.equals(item.title, true) }
    Column(Modifier.fillMaxSize()) {
        IconButton(onBack, modifier = Modifier.focusable()) { Icon(Icons.Default.ArrowBack, "Indietro") }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.width(300.dp).fillMaxHeight(.72f).background(Color(0xFF0C0E13))) { (meta?.posterUrl ?: item.posterUrl)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                item { Text(meta?.title ?: item.title, color=Color.White, fontSize=30.sp, fontWeight=FontWeight.Bold) }
                item { Text(meta?.overview ?: item.plot.orEmpty(), color=Color.White.copy(.75f), fontSize=16.sp) }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) { FilledTonalButton({ onPlay(item, currentWatch?.positionMs ?: 0L) }, modifier=Modifier.focusable()){ Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(6.dp)); Text(if(currentWatch?.positionMs ?: 0L > 0) "Riprendi" else "Riproduci") }; Button({scope.launch{app.favorites.toggle(item)}}){Icon(if(item.id in favorite.movies)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null);Text("Preferito")}; OutlinedButton({app.downloads.enqueue(item, settings.downloadWifiOnly)}){Icon(Icons.Default.Download,null);Text("Download")} } }
                item { Text("Rating: ${meta?.rating ?: item.rating ?: "—"} • IMDb ${ratings?.imdb ?: "—"} • RT ${ratings?.rottenTomatoes ?: "—"} • Metacritic ${ratings?.metacritic ?: "—"} • Trakt ${traktRating ?: "—"}", color=Color.White.copy(.68f)) }
                if(meta?.genres?.isNotEmpty()==true) item{Text("Generi: ${meta!!.genres.joinToString()}",color=Color.White.copy(.7f))}
                if(meta?.cast?.isNotEmpty()==true) item{Text("Cast: ${meta!!.cast.joinToString()}",color=Color.White.copy(.7f))}
                item{Text("Sorgenti alternative",color=Color.White,fontWeight=FontWeight.SemiBold)}
                if(alternates.isEmpty()) item{Text("Nessuna sorgente alternativa rilevata",color=Color.White.copy(.55f))}
                items(alternates){alt->Card(onClick={onPlay(alt,0L)},modifier=Modifier.fillMaxWidth().focusable()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(alt.title,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White);Text(alt.sourceId,color=Color.White.copy(.55f),fontSize=12.sp)}}}
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(app: GassPlayerApplication, series: MediaItem, episodes: List<MediaItem>, favorite: FavoriteState, onBack: () -> Unit, onPlay: (MediaItem, Long) -> Unit) {
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val showEpisodes = episodes.filter { it.seriesId == series.id.substringAfterLast(':') }.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
    val seasons = showEpisodes.groupBy { it.seasonNumber ?: 0 }
    val scope = rememberCoroutineScope()
    var season by remember(showEpisodes) { mutableStateOf(seasons.keys.minOrNull() ?: 0) }
    var meta by remember(series.id) { mutableStateOf<MetadataResult?>(null) }
    LaunchedEffect(series.id, settings.tmdbApiKey) { meta = if(settings.tmdbApiKey.isBlank()) null else runCatching{app.tmdb.search(series.title, settings.tmdbApiKey).firstOrNull()}.getOrNull() }
    Column(Modifier.fillMaxSize()) {
        IconButton(onBack, modifier=Modifier.focusable()){Icon(Icons.Default.ArrowBack,"Indietro")}
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp)){
            Box(Modifier.width(300.dp).fillMaxHeight(.72f).background(Color(0xFF0C0E13))){(meta?.posterUrl?:series.posterUrl)?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}}
            Column(Modifier.fillMaxSize()){Text(meta?.title?:series.title,color=Color.White,fontSize=30.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text(meta?.overview?:series.plot.orEmpty(),color=Color.White.copy(.75f),maxLines=5,overflow=TextOverflow.Ellipsis);Spacer(Modifier.height(10.dp));Row{seasons.keys.sorted().forEach{s->FilterChip(s==season,{season=s},label={Text("S$s")})}};Spacer(Modifier.height(10.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(bottom=40.dp)){items(seasons[season].orEmpty()){ep->val wp=watch.firstOrNull{it.contentId==ep.id};Card(onClick={onPlay(ep,wp?.positionMs?:0L)},modifier=Modifier.fillMaxWidth().focusable()){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("E${ep.episodeNumber?:0} • ${ep.title}",color=Color.White,fontWeight=FontWeight.SemiBold);ep.plot?.let{Text(it,maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.White.copy(.65f))}};IconButton({app.downloads.enqueue(ep,settings.downloadWifiOnly)}){Icon(Icons.Default.Download,null)}}}}}}
        }
    }
}

