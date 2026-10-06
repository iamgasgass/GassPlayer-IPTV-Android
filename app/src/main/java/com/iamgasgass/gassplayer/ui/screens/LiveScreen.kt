package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState

@Composable fun LiveScreen(state:AppState,onBack:()->Unit,onFavorite:(String)->Unit,play:(String)->Unit){var query by remember{mutableStateOf("")};var group by remember{mutableStateOf("")};val groups=state.catalog.channels.map{it.group}.filter{it.isNotBlank()}.distinct();val list=state.catalog.channels.filter{(group.isBlank()||it.group==group)&&(query.isBlank()||it.name.contains(query,true))};Column(Modifier.fillMaxSize()){ScreenHeader("Live TV",onBack){Text("${list.size} canali")};Row(Modifier.padding(horizontal=28.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(query,{query=it},label={Text("Cerca canale")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,modifier=Modifier.weight(1f));var menu by remember{mutableStateOf(false)};Box{Button({menu=true}){Text(group.ifBlank{"Tutte le categorie"});Icon(Icons.Default.ArrowDropDown,null)};DropdownMenu(menu,{menu=false}){DropdownMenuItem({Text("Tutte")},{group="";menu=false});groups.forEach{g->DropdownMenuItem({Text(g)},{group=g;menu=false})}}}};Spacer(Modifier.height(14.dp));if(list.isEmpty())EmptyState("Nessun canale","Configura o aggiorna una sorgente IPTV")else LazyVerticalGrid(GridCells.Adaptive(if(state.compact)300.dp else 370.dp),contentPadding=PaddingValues(28.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){items(list,key={it.id}){c->ChannelCard(c.number,c.name,c.logo,c.group,state.showNumbers,c.id in state.favorites,{play(c.id)},{onFavorite(c.id)})}}}}
