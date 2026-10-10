package com.gassplayer.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.*

internal const val GROUP_NONE = "__none__"


/**
 * Compute playlist category counters without doing SQLite reads or a full-list
 * grouping on the main thread. The result is shared by category tiles and the
 * independent group selector, avoiding duplicate catalog scans on library entry.
 */
@Composable
internal fun rememberLibraryCategoryCounts(items: List<MediaItem>): Map<String?, Int> {
    val counts by produceState<Map<String?, Int>>(initialValue = emptyMap(), key1 = items) {
        value = withContext(Dispatchers.IO) {
            runCatching { items.categoryCounts() }.getOrDefault(emptyMap())
        }
    }
    return counts
}

internal fun matchesGroup(item: MediaItem, filter: String?): Boolean = when (filter) {
    null -> true
    GROUP_NONE -> (item.categoryId ?: item.group).isNullOrBlank()
    else -> (item.categoryId ?: item.group) == filter
}

/** Grid cell width for the library density chosen in the "…" menu (Compatta / Comoda / Poster). */
internal fun libraryGridMin(settings: AppSettings, live: Boolean) = when (settings.density) {
    "compact", "compatta" -> if (live) 150.dp else 128.dp
    "poster" -> if (live) 190.dp else 200.dp
    else -> if (live) 190.dp else 160.dp
}

/**
 * Library header shared by Live TV / Film / Serie, mirroring iOS `ChannelGridView`:
 * group selector (scrolling chips or the expandable glass "pill" depending on "UI Gruppi") and the
 * "…" menu with Densità griglia, UI Gruppi and Ricarica <sezione>.
 * ALL categories are shown (the previous build silently truncated to the first 60).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryHeader(
    kindLabel: String,
    categories: List<Category>,
    items: List<MediaItem>,
    categoryCounts: Map<String?, Int>,
    filter: String?,
    onFilter: (String?) -> Unit,
    settings: AppSettings,
    vm: MainViewModel,
    allowPoster: Boolean,
    showGroups: Boolean = true
) {
    val counts = categoryCounts
    val ordered = remember(categories, counts) {
        val known = categories.distinctBy { it.id }.filter { counts.containsKey(it.id) }
        val knownIds = known.map { it.id }.toSet()
        known + counts.keys.filterNotNull().filterNot { it in knownIds }.map { Category(it, it) }
    }
    val uncategorized = counts[null] ?: 0
    val nameOf = { id: String? -> when (id) {
        null -> "Tutti"
        GROUP_NONE -> "Senza categoria"
        else -> ordered.firstOrNull { it.id == id }?.name ?: "Tutti"
    } }
    var menuOpen by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    var groupOpen by remember { mutableStateOf(false) }
    val expandable = settings.groupUIStyle == "espansibile"

    // Riserva sempre lo spazio del pulsante (…) prima di misurare i gruppi.
    // In precedenza la pill espansibile poteva misurare fino a 320 dp più
    // l'IconButton, superando la larghezza disponibile sui telefoni stretti.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!showGroups) {
            // Mantieni lo slot dedicato, così il pulsante (…) resta sempre
            // nella stessa posizione anche mentre la ricerca è attiva.
            Spacer(Modifier.weight(1f).heightIn(min = 44.dp))
        } else if (expandable) {
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clipToBounds()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(.65f))
                        .clickable { groupOpen = true }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nameOf(filter),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = groupOpen,
                    onDismissRequest = { groupOpen = false },
                    modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 440.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Tutti (${items.size})", maxLines = 1) },
                        onClick = { onFilter(null); groupOpen = false },
                        leadingIcon = { Icon(Icons.Default.GridView, null) }
                    )
                    if (uncategorized > 0) DropdownMenuItem(
                        text = { Text("Senza categoria ($uncategorized)", maxLines = 1) },
                        onClick = { onFilter(GROUP_NONE); groupOpen = false },
                        leadingIcon = { Icon(Icons.Default.Inbox, null) }
                    )
                    HorizontalDivider()
                    ordered.forEach { category ->
                        DropdownMenuItem(
                            text = { Text("${category.name} (${counts[category.id] ?: 0})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { onFilter(category.id); groupOpen = false }
                        )
                    }
                }
            }
        } else {
            // Isola fisicamente la rail dei gruppi dal controllo laterale: su
            // schermi stretti le capsule non possono disegnarsi o ricevere tap
            // nell'area riservata al pulsante (…).
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).clipToBounds()
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clipToBounds(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    item(key = "group-all") { PlaylistGroupGlassPill("Tutti", filter == null) { onFilter(null) } }
                    if (uncategorized > 0) item(key = "group-none") { PlaylistGroupGlassPill("Senza categoria", filter == GROUP_NONE) { onFilter(GROUP_NONE) } }
                    items(ordered, key = { it.id }) { category ->
                        PlaylistGroupGlassPill(category.name, filter == category.id) { onFilter(category.id) }
                    }
                }
            }
        }

        // Layer dedicato sopra la rail (ma senza coprire il suo spazio di
        // misura): garantisce hit target e z-order stabili su mobile e TV.
        Box(Modifier.size(44.dp).zIndex(2f), contentAlignment = Alignment.Center) {
            Surface(
                onClick = { sub = null; menuOpen = true },
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
                contentColor = glassForeground()
            ) {
                LiquidGlassSurface(Modifier.fillMaxSize(), cornerRadius = 50.dp, contentPadding = 0.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = "Altre opzioni", modifier = Modifier.size(22.dp))
                    }
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false; sub = null },
                modifier = Modifier.widthIn(min = 220.dp, max = 280.dp).heightIn(max = 440.dp)
            ) {
                when (sub) {
                    null -> {
                        Text(
                            "Libreria",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(.65f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Densità griglia", maxLines = 1) },
                            onClick = { sub = "density" },
                            leadingIcon = { Icon(Icons.Default.GridOn, null) },
                            trailingIcon = { Icon(Icons.Default.ChevronRight, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("UI Gruppi", maxLines = 1) },
                            onClick = { sub = "groups" },
                            leadingIcon = { Icon(Icons.Default.ViewAgenda, null) },
                            trailingIcon = { Icon(Icons.Default.ChevronRight, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Ricarica $kindLabel", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { vm.refresh(true); menuOpen = false },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) }
                        )
                    }
                    "density" -> {
                        DropdownMenuItem(text = { Text("Densità griglia") }, onClick = { sub = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Compatta", settings.density == "compact") { vm.updateSettings(settings.copy(density = "compact")); menuOpen = false; sub = null }
                        CheckItem("Comoda", settings.density == "comfortable") { vm.updateSettings(settings.copy(density = "comfortable")); menuOpen = false; sub = null }
                        if (allowPoster) CheckItem("Poster", settings.density == "poster") { vm.updateSettings(settings.copy(density = "poster")); menuOpen = false; sub = null }
                    }
                    "groups" -> {
                        DropdownMenuItem(text = { Text("UI Gruppi") }, onClick = { sub = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Scorrevole", settings.groupUIStyle != "espansibile") { vm.updateSettings(settings.copy(groupUIStyle = "scorrevole")); menuOpen = false; sub = null }
                        CheckItem("Espansibile", settings.groupUIStyle == "espansibile") { vm.updateSettings(settings.copy(groupUIStyle = "espansibile")); menuOpen = false; sub = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistGroupGlassPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        contentColor = glassForeground()
    ) {
        LiquidGlassPill(selected = selected) {
            Text(
                label,
                color = if (selected) Color.White else glassForeground().copy(.86f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp)
            )
        }
    }
}
