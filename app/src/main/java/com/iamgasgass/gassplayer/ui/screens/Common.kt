package com.iamgasgass.gassplayer.ui.screens
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.iamgasgass.gassplayer.ui.theme.*

@Composable fun ScreenHeader(title:String,onBack:(()->Unit)?=null,actions:@Composable RowScope.()->Unit={}){Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically){if(onBack!=null)IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Indietro")};Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));actions()}}
@Composable fun EmptyState(title:String,detail:String){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){GlassCard(Modifier.widthIn(max=520.dp)){Icon(Icons.Default.LiveTv,null,tint=Purple,modifier=Modifier.size(52.dp));Spacer(Modifier.height(12.dp));Text(title,style=MaterialTheme.typography.headlineSmall);Text(detail,color=Muted)}}}
@Composable fun PosterCard(title:String,image:String,subtitle:String="",favorite:Boolean=false,compact:Boolean=false,onClick:()->Unit,onFavorite:(()->Unit)?=null){GlassCard(Modifier.width(if(compact)150.dp else 190.dp).height(if(compact)225.dp else 285.dp),onClick){Box(Modifier.fillMaxWidth().weight(1f)){AsyncImage(image,contentDescription=title,modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFF242636)),contentScale=ContentScale.Crop);if(onFavorite!=null)IconButton(onClick=onFavorite,modifier=Modifier.align(Alignment.TopEnd)){Icon(if(favorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=if(favorite)Color.Red else Color.White)}};Spacer(Modifier.height(8.dp));Text(title,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold);if(subtitle.isNotBlank())Text(subtitle,maxLines=1,color=Muted,style=MaterialTheme.typography.bodySmall)}}
@Composable fun ChannelCard(number:Int,name:String,logo:String,group:String,showNumber:Boolean,favorite:Boolean,onClick:()->Unit,onFavorite:()->Unit){GlassCard(Modifier.fillMaxWidth().height(108.dp),onClick){Row(verticalAlignment=Alignment.CenterVertically){if(showNumber)Text(number.toString().padStart(3,'0'),color=Muted,modifier=Modifier.width(48.dp));AsyncImage(logo,name,Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(.07f)),contentScale=ContentScale.Fit);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(name,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold);Text(group,color=Muted,maxLines=1)};IconButton(onClick=onFavorite){Icon(if(favorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=if(favorite)Color.Red else Color.White)}}}}
@Composable fun LoadingView(label:String="Caricamento catalogo…"){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator(color=Purple);Spacer(Modifier.height(16.dp));Text(label)}}}
