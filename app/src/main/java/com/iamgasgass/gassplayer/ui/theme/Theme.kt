package com.iamgasgass.gassplayer.ui.theme
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

val Background=Color(0xFF080A12);val Surface=Color(0xCC171A26);val Purple=Color(0xFF8B5CF6);val Text=Color(0xFFF7F5FF);val Muted=Color(0xFFAAA5BA)
private val scheme=darkColorScheme(primary=Purple,secondary=Color(0xFF5EEAD4),background=Background,surface=Surface,onPrimary=Color.White,onBackground=Text,onSurface=Text)
@Composable fun GassTheme(content:@Composable()->Unit)=MaterialTheme(colorScheme=scheme,typography=Typography(),content=content)
@Composable fun GlassCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit){var focused by remember{mutableStateOf(false)};val m=modifier.onFocusChanged{focused=it.isFocused}.graphicsLayer{scaleX=if(focused)1.04f else 1f;scaleY=if(focused)1.04f else 1f}.clip(RoundedCornerShape(18.dp)).background(if(focused)Color(0xE6332855) else Surface).border(if(focused)2.dp else 1.dp,if(focused)Purple else Color.White.copy(.10f),RoundedCornerShape(18.dp)).then(if(onClick!=null)Modifier.clickable(onClick=onClick)else Modifier).padding(14.dp);Column(m,content=content)}
