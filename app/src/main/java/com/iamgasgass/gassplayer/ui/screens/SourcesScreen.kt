package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.data.*
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.theme.*

@Composable fun SourcesScreen(state:AppState,onBack:()->Unit,add:(String,SourceType,String,String,String)->Unit,select:(MediaSource)->Unit,delete:(String)->Unit){var dialog by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize()){ScreenHeader("Sorgenti",if(state.sources.isEmpty())null else onBack){Button({dialog=true}){Icon(Icons.Default.Add,null);Text(" Aggiungi")}};if(state.sources.isEmpty())EmptyState("Nessuna sorgente","Aggiungi un account Xtream Codes o una playlist M3U")else LazyColumn(contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(state.sources,key={it.id}){s->GlassCard(Modifier.fillMaxWidth(),onClick={select(s)}){Row{Icon(if(s.type==SourceType.XTREAM)Icons.Default.Dns else Icons.Default.PlaylistPlay,null,tint=Purple,modifier=Modifier.size(36.dp));Spacer(Modifier.width(16.dp));Column(Modifier.weight(1f)){Text(s.name,fontWeight=FontWeight.Bold);Text("${s.type} • ${s.url}",color=Muted,maxLines=1);if(s.id==state.selectedSource?.id)Text("ATTIVA",color=MaterialTheme.colorScheme.secondary)};IconButton({delete(s.id)}){Icon(Icons.Default.Delete,"Elimina")}}}}};if(dialog)AddSourceDialog({dialog=false}){n,t,u,user,p->add(n,t,u,user,p);dialog=false}}}
@Composable private fun AddSourceDialog(close:()->Unit,done:(String,SourceType,String,String,String)->Unit){var name by remember{mutableStateOf("")};var url by remember{mutableStateOf("")};var user by remember{mutableStateOf("")};var pass by remember{mutableStateOf("")};var type by remember{mutableStateOf(SourceType.XTREAM)};AlertDialog(close,title={Text("Aggiungi sorgente")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Row{FilterChip(type==SourceType.XTREAM,{type=SourceType.XTREAM},{Text("Xtream")});Spacer(Modifier.width(8.dp));FilterChip(type==SourceType.M3U,{type=SourceType.M3U},{Text("M3U")})};OutlinedTextField(name,{name=it},label={Text("Nome")});OutlinedTextField(url,{url=it},label={Text(if(type==SourceType.XTREAM)"URL server" else "URL playlist")});if(type==SourceType.XTREAM){OutlinedTextField(user,{user=it},label={Text("Username")});OutlinedTextField(pass,{pass=it},label={Text("Password")})}}},confirmButton={Button({done(name,type,url,user,pass)},enabled=url.isNotBlank()&&(type==SourceType.M3U||user.isNotBlank())){Text("Salva")}},dismissButton={TextButton(close){Text("Annulla")}})}
