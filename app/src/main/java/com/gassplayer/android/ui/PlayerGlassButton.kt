package com.gassplayer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private data class GlassPalette(
    val content: Color
)

@Composable
private fun glassPalette(darkSurface: Boolean, enabled: Boolean): GlassPalette {
    val content = if (darkSurface) {
        Color.White.copy(alpha = if (enabled) 1f else 0.28f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    }
    return GlassPalette(content = content)
}

/**
 * Transparent shared action used over video and throughout the app.
 * The button intentionally draws no permanent fill, border, or elevation:
 * this prevents the light translucent layer from obscuring the UI beneath it.
 * Its content, click target, semantics, and normal press feedback remain active.
 */
@Composable
fun PlayerGlassButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    darkSurface: Boolean = true,
    onClick: () -> Unit
) {
    val palette = glassPalette(darkSurface, enabled)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        contentColor = palette.content
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = palette.content)
        }
    }
}

/** Labelled transparent action; the caller controls surrounding spacing. */
@Composable
fun PlayerGlassButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    darkSurface: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val palette = glassPalette(darkSurface, enabled)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        contentColor = palette.content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun PlayerGlassButtonContent(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) = PlayerGlassButton(
    modifier = modifier,
    enabled = enabled,
    onClick = onClick,
    content = content,
    darkSurface = false
)

/** Compatibility shims keep existing call sites and click behavior unchanged. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = PlayerGlassButtonContent(modifier, enabled, onClick, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = PlayerGlassButtonContent(modifier, enabled, onClick, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = PlayerGlassButtonContent(modifier, enabled, onClick, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = PlayerGlassButtonContent(modifier, enabled, onClick, content)

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    darkSurface: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = glassPalette(darkSurface, enabled)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        contentColor = palette.content
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides palette.content) {
                content()
            }
        }
    }
}
