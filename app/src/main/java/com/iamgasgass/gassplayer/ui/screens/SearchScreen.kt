package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.theme.*

@Composable fun SearchScreen(state:AppState,onBack:()->Unit,open:(String,String)->Unit){var q by remember{mutableStateOf("")};val channels=if(q.length<2) emptyList() else state.catalog.channels.filter{it.name.contains(q,true)}.take(30);val movies=if(q.length<2) emptyList() else state.catalog.movies.filter{it.name.contains(q,true)}.take(30);val series=if(q.length<2) emptyList() else state.catalog.series.filter{it.name.contains(q,true)}.take(30);Column(Modifier.fillMaxSize()){ScreenHeader("Ricerca globale",onBack);OutlinedTextField(q,{q=it},label={Text("Canali, film e serie")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=28.dp));LazyColumn(contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){if(q.length<2)item{Text("Inserisci almeno due caratteri",color=Muted)};if(channels.isNotEmpty()){item{Text("Canali",style=MaterialTheme.typography.headlineSmall)};items(channels){c->GlassCard(Modifier.fillMaxWidth(),onClick={open(c.id,"channel")}){Text(c.name);Text(c.group,color=Muted)}}};if(movies.isNotEmpty()){item{Text("Film",style=MaterialTheme.typography.headlineSmall)};item{LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){items(movies){m->PosterCard(m.name,m.poster,m.year,onClick={open(m.id,"movie")})}}}};if(series.isNotEmpty()){item{Text("Serie TV",style=MaterialTheme.typography.headlineSmall)};item{LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){items(series){s->PosterCard(s.name,s.poster,s.year,onClick={open(s.id,"series")})}}}}}}}
