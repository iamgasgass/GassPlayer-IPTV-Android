package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState

@Composable fun VodScreen(state:AppState,onBack:()->Unit,onFavorite:(String)->Unit,play:(String)->Unit){var q by remember{mutableStateOf("")};val list=state.catalog.movies.filter{q.isBlank()||it.name.contains(q,true)};Column(Modifier.fillMaxSize()){ScreenHeader("Film",onBack);OutlinedTextField(q,{q=it},label={Text("Cerca film")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=28.dp));LazyVerticalGrid(GridCells.Adaptive(if(state.compact)160.dp else 200.dp),contentPadding=PaddingValues(28.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){items(list,key={it.id}){m->PosterCard(m.name,m.poster,listOf(m.year,if(m.rating>0)"★ ${m.rating}" else "").filter{it.isNotBlank()}.joinToString(" • "),m.id in state.favorites,state.compact,{play(m.id)},{onFavorite(m.id)})}}}}
@Composable fun SeriesScreen(state:AppState,onBack:()->Unit,onFavorite:(String)->Unit,open:(String)->Unit){var q by remember{mutableStateOf("")};val list=state.catalog.series.filter{q.isBlank()||it.name.contains(q,true)};Column(Modifier.fillMaxSize()){ScreenHeader("Serie TV",onBack);OutlinedTextField(q,{q=it},label={Text("Cerca serie")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=28.dp));LazyVerticalGrid(GridCells.Adaptive(if(state.compact)160.dp else 200.dp),contentPadding=PaddingValues(28.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){items(list,key={it.id}){s->PosterCard(s.name,s.poster,listOf(s.year,if(s.rating>0)"★ ${s.rating}" else "").filter{it.isNotBlank()}.joinToString(" • "),s.id in state.favorites,state.compact,{open(s.id)},{onFavorite(s.id)})}}}}
