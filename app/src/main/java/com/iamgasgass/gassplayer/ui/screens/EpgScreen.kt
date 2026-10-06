package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable fun EpgScreen(state:AppState,onBack:()->Unit,play:(String)->Unit){val now=remember{System.currentTimeMillis()};Column(Modifier.fillMaxSize()){ScreenHeader("Guida TV",onBack){Text(SimpleDateFormat("EEEE d MMMM",Locale.ITALIAN).format(Date(now)))};if(state.catalog.channels.isEmpty())EmptyState("EPG non disponibile","Aggiungi una sorgente Xtream o XMLTV") else LazyColumn(contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(state.catalog.channels.take(300),key={it.id}){c->Row(Modifier.fillMaxWidth().height(86.dp)){GlassCard(Modifier.width(260.dp).fillMaxHeight(),onClick={play(c.id)}){Text(c.name,fontWeight=FontWeight.Bold,maxLines=2);Text(c.group,color=Muted)};Spacer(Modifier.width(8.dp));GlassCard(Modifier.weight(1f).fillMaxHeight(),onClick={play(c.id)}){Text("Programmazione del canale",fontWeight=FontWeight.SemiBold);Text(if(c.catchup)"Catch-up disponibile" else "Seleziona per guardare in diretta",color=if(c.catchup)Color(0xFF5EEAD4) else Muted)}}}}}}
