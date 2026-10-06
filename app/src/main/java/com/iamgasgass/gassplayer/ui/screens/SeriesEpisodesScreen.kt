package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.data.Episode
import com.iamgasgass.gassplayer.ui.*
import com.iamgasgass.gassplayer.ui.theme.*

@Composable fun SeriesEpisodesScreen(state:AppState,id:String,vm:MainViewModel,onBack:()->Unit,play:(String,String,String)->Unit){val series=state.catalog.series.firstOrNull{it.id==id};var episodes by remember{mutableStateOf<List<Episode>>(emptyList())};var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)};LaunchedEffect(id){runCatching{vm.episodes(id)}.onSuccess{episodes=it}.onFailure{error=it.message};loading=false};Column(Modifier.fillMaxSize()){ScreenHeader(series?.name?:"Episodi",onBack);if(loading)LoadingView("Caricamento episodi…")else if(error!=null)EmptyState("Impossibile caricare gli episodi",error!!)else LazyColumn(contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){episodes.groupBy{it.season}.forEach{(season,list)->item{Text("Stagione $season",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)};items(list,key={it.id}){e->GlassCard(Modifier.fillMaxWidth(),onClick={play(e.streamUrl,e.title,e.id)}){Text("${e.episode}. ${e.title}",fontWeight=FontWeight.Bold);if(e.plot.isNotBlank())Text(e.plot,maxLines=2,color=Muted);if(e.duration.isNotBlank())Text(e.duration,color=Muted)}}}}}}
