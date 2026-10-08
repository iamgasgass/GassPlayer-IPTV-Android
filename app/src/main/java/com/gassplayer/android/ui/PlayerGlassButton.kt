package com.gassplayer.android.ui

import androidx.compose.foundation.BorderStroke
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
    val fill: Color,
    val border: Color,
    val content: Color
)

@Composable
private fun glassPalette(darkSurface: Boolean, enabled: Boolean): GlassPalette {
    val scheme = MaterialTheme.colorScheme
    if (darkSurface) {
        val content = Color.White.copy(alpha = if (enabled) 1f else 0.28f)
        return GlassPalette(
            fill = Color.White.copy(alpha = 0.11f),
            border = Color.White.copy(alpha = 0.14f),
            content = content
        )
    }
    val content = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    return GlassPalette(
        fill = scheme.surfaceVariant.copy(alpha = if (enabled) 0.72f else 0.42f),
        border = scheme.outline.copy(alpha = if (enabled) 0.34f else 0.18f),
        content = content
    )
}

/**
 * Shared glass action used by the player and the rest of the app.
 * [darkSurface] keeps the original player control appearance over video;
 * dialogs and normal app surfaces use the selected Material theme by default.
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
            .padding(horizontal = 2.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = palette.fill,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 8.dp,
        contentColor = palette.content
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = palette.content)
        }
    }
}

/** Glass pill variant for labelled actions. */
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
        color = palette.fill,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 8.dp,
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

/** Compatibility shims: classic Material buttons now render through PlayerGlassButton. */
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
        color = palette.fill,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 8.dp,
        contentColor = palette.content
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides palette.content) {
                content()
            }
        }
    }
}
