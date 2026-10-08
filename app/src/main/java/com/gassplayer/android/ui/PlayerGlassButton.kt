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

private val GlassFill = Color.White.copy(alpha = 0.11f)
private val GlassBorder = Color.White.copy(alpha = 0.14f)

/**
 * The shared glass action used by the player and the rest of the app.
 * The icon-only overload intentionally matches the original player control exactly.
 */
@Composable
fun PlayerGlassButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .padding(horizontal = 2.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = GlassFill,
        border = BorderStroke(1.dp, GlassBorder),
        shadowElevation = 8.dp
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.28f)
            )
        }
    }
}

/** Glass pill variant for actions that need a label and/or multiple icons. */
@Composable
fun PlayerGlassButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = GlassFill,
        border = BorderStroke(1.dp, GlassBorder),
        shadowElevation = 8.dp,
        contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.28f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** Shared implementation used by the compatibility shims below. */
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
    content = content
)

/**
 * Compatibility shims: every classic Material button used by the app now renders
 * through PlayerGlassButton, so existing screens keep their APIs and behavior.
 */
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
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(percent = 50),
        color = GlassFill,
        border = BorderStroke(1.dp, GlassBorder),
        shadowElevation = 8.dp,
        contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.28f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) Color.White else Color.White.copy(alpha = 0.28f)) {
                content()
            }
        }
    }
}
