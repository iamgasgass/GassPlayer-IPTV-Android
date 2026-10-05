package com.iamgasgass.gassplayer.ui.screens
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.iamgasgass.gassplayer.MainActivity
import com.iamgasgass.gassplayer.data.WatchProgress
import com.iamgasgass.gassplayer.ui.AppState

@OptIn(UnstableApi::class) @Composable fun PlayerScreen(state:AppState,id:String,explicitUrl:String,explicitTitle:String,onBack:()->Unit,save:(WatchProgress)->Unit){val context=LocalContext.current;val channel=state.catalog.channels.firstOrNull{it.id==id};val movie=state.catalog.movies.firstOrNull{it.id==id};val url=explicitUrl.ifBlank{channel?.streamUrl?:movie?.streamUrl.orEmpty()};val title=explicitTitle.ifBlank{channel?.name?:movie?.name?:"Riproduzione"};val poster=movie?.poster?:channel?.logo.orEmpty();val existing=state.progress.firstOrNull{it.mediaId==id};val player=remember(url){ExoPlayer.Builder(context).build().apply{setMediaItem(MediaItem.fromUri(url));existing?.positionMs?.takeIf{it>0}?.let(::seekTo);prepare();playWhenReady=true}};DisposableEffect(player){onDispose{if(player.duration>0&&player.currentPosition>0)save(WatchProgress(id,title,player.currentPosition,player.duration,poster,url));player.release()}};Box(Modifier.fillMaxSize().background(Color.Black)){if(url.isBlank())EmptyState("Stream non disponibile","Controlla la sorgente e aggiorna il catalogo")else AndroidView({PlayerView(it).apply{this.player=player;useController=true;controllerShowTimeoutMs=5000;layoutParams=ViewGroup.LayoutParams(-1,-1)}},Modifier.fillMaxSize());Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Indietro",tint=Color.White)};Text(title,color=Color.White,modifier=Modifier.weight(1f));IconButton({(context as? MainActivity)?.enterPip()}){Icon(Icons.Default.PictureInPictureAlt,"Picture in Picture",tint=Color.White)}}}}
