package com.gassplayer.android.ui

import com.gassplayer.android.data.ArtworkUrls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.TmdbService
import com.gassplayer.android.data.TmdbTrendingItem
import com.gassplayer.android.data.WatchEntry
import com.gassplayer.android.ui.theme.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// Port 1:1 di ContinueWatchingSection.swift e HomeTrendingRail (HomeCustomization.swift) dell'app iOS:
// stesse misure (pt iOS = dp), stessi testi, stessa logica. Su TV si aggiunge solo ciò che il
// telecomando richiede: focus visibile sulla card e menu "Rimuovi / Svuota tutto" con pressione lunga
// (equivalente del contextMenu iOS).

/** `.secondary` di iOS (etichette secondarie). */
@Composable
private fun iosSecondary(): Color = glassForeground(.6f)

/** `secondarySystemFill` di iOS: fondo del segnaposto delle card. */
private val IosSecondaryFill = Color(0x52787880)
private val IosDestructive = Color(0xFFFF453A)
private val RailFocusAccent = Color(0xFF8CB7FF)

// MARK: - Continua a guardare

/** Una card di "Continua a guardare" già risolta (titolo, categoria, immagine) fuori dal main thread. */
@Immutable
internal data class ContinueWatchingCard(
    /** Chiave stabile della card: la serie per gli episodi (una sola card per serie), altrimenti il contenuto. */
    val key: String,
    val entry: WatchEntry,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val kind: MediaKind,
    /** Elemento riaperto dal player alla pressione della card. */
    val playItem: MediaItem,
    /** Posizione di ripresa (0 = dall'inizio, come per un contenuto finito). */
    val resumePositionMs: Long,
    /** Voci della cronologia rappresentate dalla card (tutti gli episodi della serie): "Rimuovi" le toglie tutte. */
    val entryIds: Set<String>,
)

/** Filtro di "Continua a guardare" (`kindFilter` iOS): `null` = tutti i tipi. */
internal fun continueKindMatches(entry: WatchEntry, kindFilter: String?): Boolean = when (kindFilter) {
    "live" -> entry.kind == MediaKind.LIVE
    "movie" -> entry.kind == MediaKind.MOVIE
    "series" -> entry.kind == MediaKind.EPISODE || entry.kind == MediaKind.SERIES
    else -> true
}

private const val CONTINUE_MAX_ITEMS = 20 // RecentlyWatchedStore.maxItems

/**
 * Elementi da mostrare (ContinueWatchingSection.items + resolveMissingMetadata su iOS): filtrati per
 * tipo, dal più recente, e per le serie UNA sola card (l'episodio guardato più di recente). Immagine e
 * categoria mancanti si completano dal catalogo (poster/copertina e nome del gruppo) senza scrivere su
 * disco: ogni contenuto si legge una sola volta per passata, con una ricerca indicizzata.
 */
internal fun buildContinueWatchingCards(
    watch: List<WatchEntry>,
    catalog: CatalogState?,
    kindFilter: String?,
): List<ContinueWatchingCard> {
    val lookup = HashMap<String, MediaItem?>()
    fun find(id: String): MediaItem? = lookup.getOrPut(id) { runCatching { catalog?.findById(id) }.getOrNull() }

    val cards = ArrayList<ContinueWatchingCard>()
    val seriesCardIndex = HashMap<String, Int>()
    val extraIds = HashMap<Int, MutableSet<String>>()

    for (entry in watch) {
        if (entry.dismissed || !continueKindMatches(entry, kindFilter)) continue
        val item = find(entry.contentId)
        val isSeriesEntry = entry.kind == MediaKind.EPISODE || entry.kind == MediaKind.SERIES
        val sourceId = entry.sourceId ?: item?.sourceId
        val seriesKey = when {
            entry.kind == MediaKind.SERIES -> entry.contentId
            entry.kind == MediaKind.EPISODE -> (entry.seriesId ?: item?.seriesId)?.let { seriesId ->
                sourceId?.takeIf { it.isNotBlank() }?.let { "$it:series:$seriesId" }
            }
            else -> null
        }
        if (seriesKey != null) {
            val existing = seriesCardIndex[seriesKey]
            if (existing != null) {
                // Episodio più vecchio della stessa serie: niente nuova card, ma "Rimuovi" lo toglie insieme.
                extraIds.getOrPut(existing) { HashSet() } += entry.contentId
                continue
            }
        }
        if (cards.size >= CONTINUE_MAX_ITEMS) continue

        val series = if (isSeriesEntry) seriesKey?.let(::find) else null
        // Immagine salvata o dal catalogo: la prima davvero utilizzabile, con i percorsi relativi del provider risolti.
        val artBase = sourceId?.takeIf { it.isNotBlank() }?.let(ArtworkUrls::baseFor)
        val title: String
        val image: String?
        val subtitle: String?
        when {
            isSeriesEntry -> {
                // Per le serie la card mostra solo il nome della serie, come nel riferimento.
                title = series?.title?.takeIf { it.isNotBlank() }
                    ?: entry.title.substringBefore(" · ").trim().ifBlank { entry.title }
                image = ArtworkUrls.firstValid(artBase, entry.imageUrl, series?.backdropUrl, series?.posterUrl, item?.backdropUrl, item?.posterUrl)
                subtitle = entry.subtitle ?: series?.group ?: item?.group
            }
            entry.kind == MediaKind.LIVE -> {
                title = item?.title?.takeIf { it.isNotBlank() } ?: entry.title
                image = ArtworkUrls.firstValid(artBase, entry.imageUrl, item?.logoUrl, item?.posterUrl)
                subtitle = entry.subtitle ?: item?.group
            }
            else -> {
                title = item?.title?.takeIf { it.isNotBlank() } ?: entry.title
                image = ArtworkUrls.firstValid(artBase, entry.imageUrl, item?.backdropUrl, item?.posterUrl, item?.logoUrl)
                subtitle = entry.subtitle ?: item?.group
            }
        }
        val playItem = item ?: MediaItem(
            id = entry.contentId,
            sourceId = sourceId.orEmpty(),
            kind = entry.kind,
            title = entry.title,
            streamUrl = entry.url,
            seasonNumber = entry.season,
            episodeNumber = entry.episode,
            seriesId = entry.seriesId,
        )
        val resume = entry.positionMs.takeIf {
            entry.kind != MediaKind.LIVE && it > 5_000L && (entry.durationMs <= 0L || it < entry.durationMs - 45_000L)
        } ?: 0L
        if (seriesKey != null) seriesCardIndex[seriesKey] = cards.size
        cards += ContinueWatchingCard(
            key = seriesKey ?: entry.contentId,
            entry = entry,
            title = title,
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            imageUrl = image?.takeIf { it.isNotBlank() },
            kind = entry.kind,
            playItem = playItem,
            resumePositionMs = resume,
            entryIds = setOf(entry.contentId),
        )
    }
    return if (extraIds.isEmpty()) cards else cards.mapIndexed { index, card ->
        extraIds[index]?.let { card.copy(entryIds = card.entryIds + it) } ?: card
    }
}

/** Ultimo elenco calcolato per filtro: rientrando in una sezione la riga compare subito, senza attese. */
private object ContinueWatchingMemory {
    val cards = ConcurrentHashMap<String, List<ContinueWatchingCard>>()
}

/**
 * Card di "Continua a guardare" ricalcolate fuori dal main thread quando cambiano cronologia, catalogo o
 * filtro. Il catalogo è confrontato per identità (non per contenuto: sono liste enormi).
 */
@Composable
internal fun rememberContinueWatchingCards(
    watch: List<WatchEntry>,
    catalog: CatalogState?,
    kindFilter: String?,
    enabled: Boolean = true,
): List<ContinueWatchingCard> {
    val memoryKey = kindFilter ?: "all"
    val latestCatalog by rememberUpdatedState(catalog)
    val catalogIdentity = catalog?.let { System.identityHashCode(it) } ?: 0
    val cards by produceState(
        initialValue = if (enabled) ContinueWatchingMemory.cards[memoryKey].orEmpty() else emptyList(),
        watch, catalogIdentity, catalog?.updatedAt, kindFilter, enabled,
    ) {
        if (!enabled) {
            value = emptyList()
            return@produceState
        }
        val built = withContext(Dispatchers.IO) { buildContinueWatchingCards(watch, latestCatalog, kindFilter) }
        ContinueWatchingMemory.cards[memoryKey] = built
        value = built
    }
    return cards
}

/**
 * Sezione "Continua a guardare" condivisa da Home (tutti i tipi o quello scelto), VOD (film) e Serie TV
 * (serie): un'unica implementazione, quindi le tre schermate mostrano esattamente la stessa card.
 *
 * UI (misure iOS): titolo 18,5 medium secondario + "Svuota" in capsula glass; card 288×162 (16:9) con
 * angoli 16 e filo di bordo chiaro, nessun pannello vetro; sotto la card titolo 13,5 medium e categoria
 * in grigio, rientrati di 12; spaziatura fra le card 11, fra titolo sezione e card 10, card-testo 5.
 */
@Composable
internal fun ContinueWatchingSection(
    app: GassPlayerApplication,
    cards: List<ContinueWatchingCard>,
    kindFilter: String?,
    onResume: (ContinueWatchingCard) -> Unit,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 0.dp,
    /** Modificatore per card (es. bordo sinistro della "Categorie TV"): riceve indice e chiave di focus. */
    itemModifier: (index: Int, focusKey: String) -> Modifier = { _, _ -> Modifier },
    onItemFocus: () -> Unit = {},
) {
    if (cards.isEmpty()) return
    val scope = rememberCoroutineScope()
    val clearAll: () -> Unit = {
        scope.launch { app.watch.dismiss { continueKindMatches(it, kindFilter) } }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Continua a guardare",
                color = iosSecondary(),
                fontSize = 18.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            // Tasto "Svuota": svuota solo il tipo della sezione (tutto in Home), Liquid Glass a capsula.
            GlassCapsuleButton("Svuota", onClick = clearAll, contentDescription = "Svuota Continua a guardare")
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            contentPadding = PaddingValues(horizontal = horizontalInset),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                // Nessuna animazione di uscita (`.transition(.identity)` su iOS): la card rimossa sparisce subito.
                Box(itemModifier(index, "continue:${card.key}")) {
                    ContinueWatchingCardView(
                        card = card,
                        onResume = { onResume(card) },
                        onRemove = { scope.launch { app.watch.dismiss { it.contentId in card.entryIds } } },
                        onClearAll = clearAll,
                        onFocus = onItemFocus,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCardView(
    card: ContinueWatchingCard,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onClearAll: () -> Unit,
    onFocus: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(288.dp)
                .focusReturnTarget()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onResume,
                    onLongClick = { menuOpen = true },
                    onClickLabel = "Riprendi la riproduzione",
                ),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            RailArtwork(
                url = card.imageUrl,
                width = 288.dp,
                height = 162.dp,
                cornerRadius = 16.dp,
                placeholder = when (card.kind) {
                    MediaKind.LIVE -> Icons.Default.LiveTv
                    MediaKind.MOVIE -> Icons.Default.Movie
                    MediaKind.SERIES, MediaKind.EPISODE -> Icons.Default.Tv
                },
                // Le icone dei canali live (quadrate, sfondo trasparente) restano intere; film e serie riempiono.
                fit = card.kind == MediaKind.LIVE,
                focused = focused,
            )
            Column(
                Modifier.width(288.dp).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    card.title,
                    color = glassForeground(),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                card.subtitle?.let {
                    Text(it, color = iosSecondary(), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rimuovi", color = IosDestructive.readable()) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = IosDestructive) },
                onClick = { menuOpen = false; onRemove() },
                modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
            )
            DropdownMenuItem(
                text = { Text("Svuota tutto", color = IosDestructive) },
                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = IosDestructive) },
                onClick = { menuOpen = false; onClearAll() },
                modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
            )
        }
    }
}

// MARK: - Serie di tendenza / Film di tendenza

/** Ultimo elenco di tendenza ricevuto (HomeTrendingMemory): mostrato subito al rientro, senza rete. */
private object HomeTrendingMemory {
    val items = ConcurrentHashMap<String, List<TmdbTrendingItem>>()
}

/**
 * Riga orizzontale dei 20 titoli più in tendenza della settimana (TMDB), con la stessa impostazione di
 * "Continua a guardare": titolo sezione 18,5, card 16:9 (200×112,5, angoli 11) a tutta immagine con
 * filo di bordo e, sotto, numero di classifica grande (44) + titolo e genere.
 */
@Composable
internal fun HomeTrendingRail(
    title: String,
    isSeries: Boolean,
    apiKey: String,
    tmdb: TmdbService,
    onSelect: (TmdbTrendingItem) -> Unit,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 0.dp,
    /** Se presente, la riga del titolo mostra il tasto "Modifica" (identico a "Svuota"). */
    onEdit: (() -> Unit)? = null,
    itemModifier: (index: Int, focusKey: String) -> Modifier = { _, _ -> Modifier },
    onItemFocus: () -> Unit = {},
) {
    val memoryKey = if (isSeries) "tv" else "movie"
    var items by remember(memoryKey) { mutableStateOf(HomeTrendingMemory.items[memoryKey].orEmpty()) }
    var didFail by remember(memoryKey) { mutableStateOf(false) }

    LaunchedEffect(apiKey, isSeries) {
        if (apiKey.isBlank()) return@LaunchedEffect
        try {
            val loaded = tmdb.trendingRail(isSeries, apiKey, limit = 20)
            HomeTrendingMemory.items[memoryKey] = loaded
            items = loaded
            didFail = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (items.isEmpty()) didFail = true
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = iosSecondary(),
                fontSize = 18.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            if (onEdit != null) GlassCapsuleButton("Modifica", onClick = onEdit, contentDescription = "Modifica $title")
        }

        when {
            apiKey.isBlank() -> TrendingHint("Aggiungi la chiave API TMDB nelle Impostazioni per vedere i titoli di tendenza.", horizontalInset)
            items.isNotEmpty() -> LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                contentPadding = PaddingValues(horizontal = horizontalInset),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Box(itemModifier(index, "trend:${item.id}")) {
                        TrendingRailCard(item, rank = index + 1, onClick = { onSelect(item) }, onFocus = onItemFocus)
                    }
                }
            }
            didFail -> TrendingHint("Impossibile caricare i titoli di tendenza.", horizontalInset)
            else -> Box(
                Modifier
                    .padding(horizontal = horizontalInset)
                    .padding(bottom = 44.dp)
                    .size(width = 200.dp, height = 112.5.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(IosSecondaryFill)
            )
        }
    }
}

@Composable
private fun TrendingRailCard(item: TmdbTrendingItem, rank: Int, onClick: () -> Unit, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .focusReturnTarget()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            },
        color = Color.Transparent,
    ) {
        Column(Modifier.width(200.dp)) {
            RailArtwork(
                url = item.backdropUrl,
                width = 200.dp,
                height = 112.5.dp,
                cornerRadius = 11.dp,
                placeholder = if (item.isSeries) Icons.Default.Tv else Icons.Default.Movie,
                fit = false,
                focused = focused,
            )
            Row(Modifier.width(200.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "$rank",
                    color = iosSecondary(),
                    fontSize = 44.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.height(44.dp).wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        item.title,
                        color = glassForeground(),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.genre?.let {
                        Text(it, color = iosSecondary(), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingHint(text: String, horizontalInset: Dp) {
    Text(text, color = iosSecondary(), fontSize = 15.sp, modifier = Modifier.padding(horizontal = horizontalInset))
}

// MARK: - Elementi comuni

/**
 * Immagine a tutta card (CachedPosterImage iOS) con filo di bordo chiaro 0,7. Segnaposto a icona su
 * `secondarySystemFill` finché l'immagine non è caricata. Col focus del telecomando la card si ingrandisce
 * leggermente e il bordo diventa l'accento di focus dell'app.
 */
@Composable
private fun RailArtwork(
    url: String?,
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    placeholder: ImageVector,
    fit: Boolean,
    focused: Boolean,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "rail-artwork-focus")
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = width, height = height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(IosSecondaryFill)
            .border(
                width = if (focused) 2.5.dp else 0.7.dp,
                color = if (focused) RailFocusAccent else glassForeground(.18f),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!loaded) Icon(placeholder, contentDescription = null, tint = glassForeground(.45f), modifier = Modifier.size(30.dp))
        if (!url.isNullOrBlank()) {
            ArtworkImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (fit) Modifier.padding(18.dp) else Modifier),
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                onSuccess = { loaded = true },
            )
        }
    }
}

/** Capsula Liquid Glass neutra (ClearGlassCapsule iOS): "Svuota" e "Modifica". */
@Composable
internal fun GlassCapsuleButton(label: String, onClick: () -> Unit, contentDescription: String = label) {
    Surface(
        onClick = onClick,
        modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
    ) {
        LiquidGlassPill {
            Text(
                label,
                color = glassForeground(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.semanticsLabel(contentDescription)
            )
        }
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
