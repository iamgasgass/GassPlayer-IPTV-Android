package com.gassplayer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IosAccent = Color(0xFF3478F6)
private val IosPurple = Color(0xFF8A5CF6)
private val LightBackground = Color(0xFFF7F8FC)
private val DarkBackground = Color(0xFF0D0E12)

private val LightColors = lightColorScheme(
    primary = IosAccent,
    onPrimary = Color.White,
    secondary = IosPurple,
    background = LightBackground,
    onBackground = Color(0xFF16171B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16171B),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF555963),
    outline = Color(0xFFB9BDC7)
)

private val DarkColors = darkColorScheme(
    primary = IosAccent,
    onPrimary = Color.White,
    secondary = IosPurple,
    background = DarkBackground,
    onBackground = Color(0xFFF4F5F8),
    surface = Color(0xFF17191E),
    onSurface = Color(0xFFF4F5F8),
    surfaceVariant = Color(0xFF252831),
    onSurfaceVariant = Color(0xFFB8BCC6),
    outline = Color(0xFF555A65)
)

@Composable
fun GassPlayerTheme(theme: String = "system", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
