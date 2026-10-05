package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.theme.*

@Composable fun SettingsScreen(state:AppState,onBack:()->Unit,compact:(Boolean)->Unit,numbers:(Boolean)->Unit,refresh:()->Unit){Column(Modifier.fillMaxSize()){ScreenHeader("Impostazioni",onBack);LazyColumn(contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Interfaccia",style=MaterialTheme.typography.headlineSmall)};item{SettingToggle("Griglia compatta","Mostra più contenuti sullo schermo",state.compact,compact)};item{SettingToggle("Mostra numero canale","Visualizza la numerazione nella griglia Live TV",state.showNumbers,numbers)};item{Text("Catalogo",style=MaterialTheme.typography.headlineSmall)};item{GlassCard(Modifier.fillMaxWidth(),onClick=refresh){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Refresh,null,tint=Purple);Spacer(Modifier.width(14.dp));Column{Text("Aggiorna catalogo");Text("Ricarica dati e sostituisce la cache",color=Muted)}}}};item{Text("Riproduzione",style=MaterialTheme.typography.headlineSmall)};item{GlassCard(Modifier.fillMaxWidth()){Text("Media3 ExoPlayer");Text("HLS, MPEG-TS, DASH, file progressivi, sottotitoli e tracce audio",color=Muted)}};item{Text("Informazioni",style=MaterialTheme.typography.headlineSmall)};item{GlassCard(Modifier.fillMaxWidth()){Text("GassPlayer IPTV per Android / Android TV");Text("Versione 1.0.0",color=Muted)}}}}}
@Composable private fun SettingToggle(title:String,detail:String,value:Boolean,change:(Boolean)->Unit){GlassCard(Modifier.fillMaxWidth(),onClick={change(!value)}){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title);Text(detail,color=Muted)};Switch(value,change)}}}
