package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GlassIconButton(systemImage: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit, tint: Color? = null, size: Int = 44) = IosGlassIconButton(systemImage, contentDescription, onClick, tint, size)

@Composable
fun GlassPrimaryButton(title: String, systemImage: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) = IosGlassPrimaryButton(title, systemImage, onClick = onClick)

@Composable
fun GlassCard(content: @Composable ColumnScope.() -> Unit) = IosGlassCard(content = content)

@Composable
fun GlassSettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) = IosGlassRow(icon, title, subtitle, IosBlue, onClick = onClick)

@Composable
fun ResumeConfirmationOverlay(formattedTime: String, onResume: () -> Unit, onRestart: () -> Unit) {
    IosDialogFrame("Riprendi la visione?", onDismiss = onRestart) {
        Text("Ti eri fermato a $formattedTime", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
        IosGlassPrimaryButton("Riprendi da $formattedTime", Icons.Default.PlayArrow, onClick = onResume)
        IosGlassPrimaryButton("Ricomincia da capo", Icons.Default.RestartAlt, onClick = onRestart)
    }
}

@Composable
fun GlassSettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tint: Color = IosGreen
) = IosSettingsToggleRow(icon, title, subtitle, tint, checked, onCheckedChange)

@Composable
fun GlassRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

@Composable
fun GlassSectionHeader(title: String, subtitle: String? = null) = IosSectionHeader(title, subtitle)

@Composable
fun GlassScreenBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) = IosBackground(modifier, content)

@Composable
fun GlassTab(text: String, selected: Boolean, onClick: () -> Unit) = IosChip(text, selected, onClick = onClick)

@Composable
fun GlassChip(text: String, selected: Boolean = false, onClick: () -> Unit) = IosChip(text, selected, onClick = onClick)

@Composable
fun GlassListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = IosBlue,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) = IosGlassRow(icon, title, subtitle, tint, onClick = onClick, trailing = trailing)

@Composable
fun GlassIconGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int = 44, tint: Color? = null) {
    Box(Modifier.size(size.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size((size * 0.41f).dp))
    }
}

@Composable
fun GlassSourceIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, size: Int = 44) {
    Box(
        Modifier.size(size.dp).padding(0.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = tint.copy(alpha = 0.16f),
            modifier = Modifier.fillMaxSize()
        ) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.38f).dp)) } }
    }
}

@Composable
fun GlassSourceRowLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = IosBlue,
    showChevron: Boolean = true
) {
    IosGlassRow(icon, title, subtitle, tint, showChevron = showChevron)
}

@Composable
fun GlassMenuPillLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color = IosBlue) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.widthIn(min = 142.dp, max = 260.dp).heightIn(min = 44.dp).padding(horizontal = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(androidx.compose.material.icons.Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
        }
    }
}
