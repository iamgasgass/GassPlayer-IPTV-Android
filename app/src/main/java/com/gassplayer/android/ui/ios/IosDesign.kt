package com.gassplayer.android.ui.ios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.data.SourceType
import com.gassplayer.android.ui.PlayerGlassButton

val IosBlue = Color(0xFF3478F6)
val IosPurple = Color(0xFF8A5CF6)
val IosRed = Color(0xFFFF3B30)
val IosOrange = Color(0xFFFF9500)
val IosGreen = Color(0xFF34C759)
val IosTeal = Color(0xFF30B0C7)

@Composable
fun IosBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    IosBlue.copy(alpha = if (dark) 0.18f else 0.10f),
                    surface,
                    IosPurple.copy(alpha = if (dark) 0.12f else 0.06f)
                )
            )
        ), content = content
    )
}

@Composable
fun IosScreen(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    IosBackground(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (title != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(10.dp))
            }
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
fun IosGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 20,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(padding), content = content)
    }
}

@Composable
fun IosGlassRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = IosBlue,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                    fontWeight = FontWeight.Medium
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) trailing()
            else if (showChevron) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f))
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) { content() }
    } else content()
}

@Composable
fun IosSectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
    }
}

@Composable
fun IosGlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, tint: Color? = null, size: Int = 44) {
    PlayerGlassButton(icon, contentDescription, size = size.dp, tint = tint, onClick = onClick)
}

@Composable
fun IosGlassPrimaryButton(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    PlayerGlassButton(modifier = modifier.fillMaxWidth(), enabled = enabled, onClick = onClick) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun IosChip(text: String, selected: Boolean, tint: Color = IosBlue, onClick: () -> Unit) {
    PlayerGlassButton(
        modifier = Modifier.height(40.dp),
        onClick = onClick,
        content = {
            Text(
                text,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    )
}

@Composable
fun IosSettingsToggleRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = IosGreen, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    IosGlassCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun IosGroupSelector(
    groups: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    expandable: Boolean = false,
    counts: Map<String, Int> = emptyMap()
) {
    var expanded by remember { mutableStateOf(!expandable) }
    IosGlassCard(padding = PaddingValues(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Gruppi playlist", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (expandable) {
                PlayerGlassButton(icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Espandi gruppi", onClick = { expanded = !expanded })
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((listOf("Tutti") + groups).distinct()) { group ->
                    IosChip(text = if (counts.containsKey(group)) "$group  ${counts[group]}" else group, selected = selected == group, onClick = { onSelected(group) })
                }
            }
        }
    }
}

fun sourceTint(type: SourceType): Color = when (type) {
    SourceType.XTREAM -> IosOrange
    SourceType.M3U8 -> IosBlue
    SourceType.PLEX -> Color(0xFFE5B500)
    SourceType.JELLYFIN -> IosPurple
    SourceType.EMBY -> IosGreen
}

@Composable
fun SourceBadge(source: MediaSourceConfig, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(sourceTint(source.type)))
        Spacer(Modifier.width(7.dp))
        Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f))
    }
}

@Composable
fun IosEmptyState(title: String, message: String, icon: ImageVector, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    IosGlassCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (actionTitle != null && onAction != null) IosGlassPrimaryButton(actionTitle, onClick = onAction, modifier = Modifier.widthIn(max = 340.dp))
        }
    }
}

@Composable
fun IosTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, supportingText: String? = null, singleLine: Boolean = true, isPassword: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = singleLine,
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun IosDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            tonalElevation = 2.dp,
            shadowElevation = 18.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IosGlassIconButton(androidx.compose.material.icons.Icons.Default.Close, "Chiudi", onDismiss, size = 38)
                }
                content()
            }
        }
    }
}

@Composable
fun IosPoster(item: com.gassplayer.android.data.MediaItem, modifier: Modifier = Modifier, width: Int = 130, height: Int = 190) {
    coil3.compose.AsyncImage(
        model = item.posterUrl ?: item.logoUrl,
        contentDescription = item.title,
        modifier = modifier.width(width.dp).height(height.dp).clip(RoundedCornerShape(14.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}

@Composable
fun IosMediaCard(item: com.gassplayer.android.data.MediaItem, compact: Boolean = false, onClick: () -> Unit) {
    val width = if (compact) 110 else 148
    val height = if (compact) 150 else 205
    androidx.compose.material3.Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.Transparent) {
        Column(Modifier.width(width.dp)) {
            Box(Modifier.fillMaxWidth()) {
                IosPoster(item, Modifier, width, height)
                if (item.rating != null) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.66f), modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)) {
                        Text("%.1f".format(item.rating), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(item.year, item.group).firstOrNull()
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
