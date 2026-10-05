package com.iamgasgass.gassplayer.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.iamgasgass.gassplayer.ui.screens.*
import com.iamgasgass.gassplayer.ui.theme.*

object Routes{const val HOME="home";const val LIVE="live";const val VOD="vod";const val SERIES="series";const val EPG="epg";const val SEARCH="search";const val SOURCES="sources";const val SETTINGS="settings";const val PLAYER="player/{id}";const val EPISODES="episodes/{id}"}
@Composable fun GassPlayerApp(vm:MainViewModel){GassTheme{val nav=rememberNavController();val state by vm.state.collectAsState();Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF211340),Background),radius=1100f))){NavHost(nav,if(state.sources.isEmpty())Routes.SOURCES else Routes.HOME){
 composable(Routes.HOME){HomeScreen(state,{nav.navigate(it)},{id->nav.navigate("player/$id")})}
 composable(Routes.LIVE){LiveScreen(state,{nav.popBackStack()},{vm.favorite(it)},{nav.navigate("player/$it")})}
 composable(Routes.VOD){VodScreen(state,{nav.popBackStack()},{vm.favorite(it)},{nav.navigate("player/$it")})}
 composable(Routes.SERIES){SeriesScreen(state,{nav.popBackStack()},{vm.favorite(it)},{nav.navigate("episodes/$it")})}
 composable(Routes.EPG){EpgScreen(state,{nav.popBackStack()},{nav.navigate("player/$it")})}
 composable(Routes.SEARCH){SearchScreen(state,{nav.popBackStack()},{id,type->nav.navigate(if(type=="series")"episodes/$id" else "player/$id")})}
 composable(Routes.SOURCES){SourcesScreen(state,{nav.popBackStack()},{n,t,u,user,p->vm.add(n,t,u,user,p)},{vm.select(it)},{vm.delete(it)})}
 composable(Routes.SETTINGS){SettingsScreen(state,{nav.popBackStack()},{vm.setCompact(it)},{vm.setNumbers(it)},{vm.reload(true)})}
 composable(Routes.EPISODES, listOf(navArgument("id"){type=NavType.StringType})){SeriesEpisodesScreen(state,it.arguments?.getString("id").orEmpty(),vm,{nav.popBackStack()}){url,title,id->nav.navigate("player/$id?url=${android.net.Uri.encode(url)}&title=${android.net.Uri.encode(title)}")}}
 composable("player/{id}?url={url}&title={title}",listOf(navArgument("id"){type=NavType.StringType},navArgument("url"){defaultValue=""},navArgument("title"){defaultValue=""})){PlayerScreen(state,it.arguments?.getString("id").orEmpty(),it.arguments?.getString("url").orEmpty(),it.arguments?.getString("title").orEmpty(),{nav.popBackStack()},vm::saveProgress)}
 }}}
}
