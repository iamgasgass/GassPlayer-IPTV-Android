package com.iamgasgass.gassplayer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

val Background = Color(0xFF080A12)
val Surface = Color(0xCC171A26)
val Purple = Color(0xFF8B5CF6)
val Text = Color(0xFFF7F5FF)
val Muted = Color(0xFFAAA5BA)

private val GassColorScheme = darkColorScheme(
    primary = Purple,
    secondary = Color(0xFF5EEAD4),
    background = Background,
    surface = Surface,
    onPrimary = Color.White,
    onBackground = Text,
    onSurface = Text,
)

@Composable
fun GassTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GassColorScheme,
        typography = Typography(),
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    val cardModifier = modifier
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = if (focused) 1.04f else 1f
            scaleY = if (focused) 1.04f else 1f
        }
        .clip(shape)
        .background(if (focused) Color(0xE6332855) else Surface)
        .border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) Purple else Color.White.copy(alpha = 0.10f),
            shape = shape,
        )
        .then(clickModifier)
        .padding(14.dp)

    Column(
        modifier = cardModifier,
        content = content,
    )
}
