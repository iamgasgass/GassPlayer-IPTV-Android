package com.gassplayer.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.*

internal const val GROUP_NONE = "__none__"

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
    filter: String?,
    onFilter: (String?) -> Unit,
    settings: AppSettings,
    vm: MainViewModel,
    allowPoster: Boolean
) {
    val counts = remember(items) { items.groupingBy { (it.categoryId ?: it.group)?.takeIf { k -> k.isNotBlank() } }.eachCount() }
    val ordered = remember(categories, counts) {
        val known = categories.distinctBy { it.id }.filter { counts.containsKey(it.id) }
        val knownIds = known.map { it.id }.toSet()
        known + counts.keys.filterNotNull().filter { it !in knownIds }.map { Category(it, it) }
    }
    val uncategorized = counts[null] ?: 0
    val nameOf = { id: String? -> when (id) { null -> "Tutti"; GROUP_NONE -> "Senza categoria"; else -> ordered.firstOrNull { it.id == id }?.name ?: "Tutti" } }
    var menuOpen by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    var groupOpen by remember { mutableStateOf(false) }
    val expandable = settings.groupUIStyle == "espansibile"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (expandable) {
            Box {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.10f)).combinedClickable(onClick = { groupOpen = true }).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GridView, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text(nameOf(filter), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(groupOpen, { groupOpen = false }, modifier = Modifier.heightIn(max = 440.dp)) {
                    DropdownMenuItem({ Text("Tutti (${items.size})") }, { onFilter(null); groupOpen = false }, leadingIcon = { Icon(Icons.Default.GridView, null) })
                    if (uncategorized > 0) DropdownMenuItem({ Text("Senza categoria ($uncategorized)") }, { onFilter(GROUP_NONE); groupOpen = false }, leadingIcon = { Icon(Icons.Default.Inbox, null) })
                    HorizontalDivider()
                    ordered.forEach { c -> DropdownMenuItem({ Text("${c.name} (${counts[c.id] ?: 0})") }, { onFilter(c.id); groupOpen = false }) }
                }
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(filter == null, { onFilter(null) }, label = { Text("Tutto") }) }
                if (uncategorized > 0) item { FilterChip(filter == GROUP_NONE, { onFilter(GROUP_NONE) }, label = { Text("Senza categoria") }) }
                items(ordered, key = { it.id }) { c -> FilterChip(filter == c.id, { onFilter(c.id) }, label = { Text(c.name) }) }
            }
        }
        Box {
            IconButton({ sub = null; menuOpen = true }) { Icon(Icons.Default.MoreHoriz, "Altre opzioni", tint = Color.White) }
            DropdownMenu(menuOpen, { menuOpen = false; sub = null }) {
                when (sub) {
                    null -> {
                        Text("Libreria", color = Color.White.copy(.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        DropdownMenuItem({ Text("Densità griglia") }, { sub = "density" }, leadingIcon = { Icon(Icons.Default.GridOn, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        DropdownMenuItem({ Text("UI Gruppi") }, { sub = "groups" }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        DropdownMenuItem({ Text("Ricarica $kindLabel") }, { vm.refresh(true); menuOpen = false }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                    }
                    "density" -> {
                        DropdownMenuItem({ Text("Densità griglia") }, { sub = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Compatta", settings.density == "compact") { vm.updateSettings(settings.copy(density = "compact")); menuOpen = false; sub = null }
                        CheckItem("Comoda", settings.density == "comfortable") { vm.updateSettings(settings.copy(density = "comfortable")); menuOpen = false; sub = null }
                        if (allowPoster) CheckItem("Poster", settings.density == "poster") { vm.updateSettings(settings.copy(density = "poster")); menuOpen = false; sub = null }
                    }
                    "groups" -> {
                        DropdownMenuItem({ Text("UI Gruppi") }, { sub = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Scorrevole", settings.groupUIStyle != "espansibile") { vm.updateSettings(settings.copy(groupUIStyle = "scorrevole")); menuOpen = false; sub = null }
                        CheckItem("Espansibile", settings.groupUIStyle == "espansibile") { vm.updateSettings(settings.copy(groupUIStyle = "espansibile")); menuOpen = false; sub = null }
                    }
                }
            }
        }
    }
}
