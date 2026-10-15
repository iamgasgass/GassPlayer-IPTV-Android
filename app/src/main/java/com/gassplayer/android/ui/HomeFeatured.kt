package com.gassplayer.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.MetadataResult
import com.gassplayer.android.data.TmdbTrendingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// Sezione "In evidenza" (Featured) della Home, identica a HomeFeaturedSection.swift su iOS:
//  1. "Continua a guardare": gli ultimi film/serie (riprende subito);
//  2. "Per te": consigliati TMDB dall'ultimo titolo visto, solo se presenti nel catalogo;
//  3. "Di tendenza": serie e film della settimana (TMDB) presenti nel catalogo.
// Immagine a tutto schermo che sfuma verso il basso, etichetta in capsula, logo del titolo,
// "generi · anno · ★voto" (o "S1, E3 · genere · ★voto"), pulsante bianco a capsula, puntini.
// Avanzamento automatico in dissolvenza, scorrimento col dito, SINISTRA/DESTRA col telecomando.

internal enum class HomeFeaturedBadge(val title: String) {
    CONTINUE("Continua a guardare"),
    FOR_YOU("Per te"),
    TRENDING("Di tendenza"),
}

@Immutable
internal data class HomeFeaturedEntry(
    val id: String,
    val badge: HomeFeaturedBadge,
    val title: String,
    val isSeries: Boolean,
    /** Contenuto da riprendere (continua a guardare) o da aprire nella sua scheda. */
    val item: MediaItem,
    val providerImage: String?,
    val tmdbId: String? = null,
    val fallbackGenre: String? = null,
    val fallbackYear: String? = null,
    val episodeLabel: String? = null,
    val meta: MetadataResult? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val rating: Double? = null,
) {
    val isResume: Boolean get() = badge == HomeFeaturedBadge.CONTINUE
}

private const val FEATURED_MAX = 8
private const val FEATURED_MAX_CONTINUE = 2
private const val FEATURED_MAX_FOR_YOU = 3

/**
 * Memoria di processo della sezione: tornando alla Home (o cambiando schermata e rientrando) le schede e i
 * dettagli TMDB gia' scaricati compaiono SUBITO, senza attendere la rete; poi si aggiornano in silenzio.
 */
internal object HomeFeaturedMemory {
    @Volatile private var lastSourceKey: String? = null
    @Volatile private var lastEntries: List<HomeFeaturedEntry> = emptyList()
    /** Dettagli TMDB gia' arricchiti, per id della scheda. */
    val enriched = java.util.concurrent.ConcurrentHashMap<String, HomeFeaturedEntry>()

    fun entriesFor(sourceKey: String): List<HomeFeaturedEntry> =
        if (sourceKey == lastSourceKey) lastEntries.map { enriched[it.id] ?: it } else emptyList()

    fun store(sourceKey: String, entries: List<HomeFeaturedEntry>) {
        lastSourceKey = sourceKey
        lastEntries = entries
    }
}

/** Contenuti della sezione, ricostruiti quando cambiano sorgente, catalogo, cronologia o chiave TMDB. */
@Composable
internal fun rememberHomeFeaturedEntries(
    enabled: Boolean,
    app: GassPlayerApplication,
    catalog: CatalogState?,
    continueCards: List<ContinueWatchingCard>,
    apiKey: String,
): List<HomeFeaturedEntry> {
    val sourceKey = catalog?.sourceIds?.joinToString(",").orEmpty()
    // Comparsa istantanea: le schede dell'ultima volta (con immagini e dettagli gia' pronti).
    var entries by remember(sourceKey) { mutableStateOf(if (enabled) HomeFeaturedMemory.entriesFor(sourceKey) else emptyList()) }
    val latestCatalog by rememberUpdatedState(catalog)
    val latestCards by rememberUpdatedState(continueCards)
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = if (!enabled) "off" else listOf(
        sourceKey,
        catalog?.updatedAt?.toString().orEmpty(),
        continueCards.take(4).joinToString(",") { it.key },
        if (apiKey.isBlank()) "-" else "tmdb",
    ).joinToString("|")

    LaunchedEffect(key) {
        if (!enabled) {
            entries = emptyList()
            return@LaunchedEffect
        }
        coroutineScope {
            val enriching = HashSet<String>()
            fun publish(list: List<HomeFeaturedEntry>) {
                if (list.isEmpty() && entries.isNotEmpty()) return // mai svuotare la sezione mentre si ricarica
                val shown = list.map { HomeFeaturedMemory.enriched[it.id] ?: it }
                entries = shown
                HomeFeaturedMemory.store(sourceKey, shown)
                // Dettagli TMDB scheda per scheda, in parallelo, appena una scheda compare.
                shown.forEachIndexed { index, entry ->
                    if (HomeFeaturedMemory.enriched.containsKey(entry.id) || !enriching.add(entry.id)) {
                        return@forEachIndexed
                    }
                    launch {
                        val done = withContext(Dispatchers.IO) {
                            runCatching { enrichHomeFeaturedEntry(app, entry, apiKey) }.getOrNull()
                        } ?: return@launch
                        HomeFeaturedMemory.enriched[entry.id] = done
                        prefetchHomeFeaturedImages(context, done, eager = index < 3)
                        entries = entries.map { if (it.id == entry.id) done else it }
                        HomeFeaturedMemory.store(sourceKey, entries)
                    }
                }
            }
            // Le schede compaiono a gruppi, appena pronte: prima "Continua a guardare" (nessuna rete),
            // poi "Per te" e "Di tendenza" (scaricati in parallelo).
            withContext(Dispatchers.IO) {
                runCatching {
                    buildHomeFeaturedEntries(app, latestCatalog, latestCards, apiKey) { partial ->
                        withContext(Dispatchers.Main) { publish(partial) }
                    }
                }
            }
        }
    }
    return entries
}

/** Scarica in anticipo le immagini della scheda (cache di Coil): scorrendo compaiono subito. */
private fun prefetchHomeFeaturedImages(context: android.content.Context, entry: HomeFeaturedEntry, eager: Boolean) {
    val loader = coil3.SingletonImageLoader.get(context)
    val urls = buildList {
        entry.logoUrl?.let(::add)
        if (eager) {
            entry.backdropUrl?.let(::add)
            entry.posterUrl?.let(::add)
        } else {
            (entry.backdropUrl ?: entry.posterUrl)?.let(::add)
        }
    }
    urls.forEach { url ->
        runCatching { loader.enqueue(coil3.request.ImageRequest.Builder(context).data(url).build()) }
    }
}

private suspend fun buildHomeFeaturedEntries(
    app: GassPlayerApplication,
    catalog: CatalogState?,
    cards: List<ContinueWatchingCard>,
    apiKey: String,
    emit: suspend (List<HomeFeaturedEntry>) -> Unit = {},
): List<HomeFeaturedEntry> = coroutineScope {
    val result = ArrayList<HomeFeaturedEntry>()
    val used = HashSet<String>()

    // 1. Continua a guardare: film e serie (le card sono gia' una per serie).
    for (card in cards) {
        if (result.size >= FEATURED_MAX_CONTINUE) break
        val isSeries = card.kind == MediaKind.EPISODE || card.kind == MediaKind.SERIES
        if (!isSeries && card.kind != MediaKind.MOVIE) continue
        if (!used.add(card.key)) continue
        val season = card.entry.season ?: card.playItem.seasonNumber
        val episode = card.entry.episode ?: card.playItem.episodeNumber
        result += HomeFeaturedEntry(
            id = "cw|${card.key}",
            badge = HomeFeaturedBadge.CONTINUE,
            title = card.title,
            isSeries = isSeries,
            item = card.playItem,
            providerImage = card.imageUrl,
            episodeLabel = if (isSeries && season != null && episode != null) "S$season, E$episode" else null,
        )
    }

    // Subito, senza rete.
    emit(result.toList())
    if (apiKey.isBlank() || catalog == null) return@coroutineScope result

    // Tutte le richieste TMDB partono insieme (prima erano una dopo l'altra).
    val trendingSeries = async { runCatching { app.tmdb.trendingRail(isSeries = true, apiKey = apiKey) }.getOrDefault(emptyList()) }
    val trendingMovies = async { runCatching { app.tmdb.trendingRail(isSeries = false, apiKey = apiKey) }.getOrDefault(emptyList()) }

    fun entryFor(match: MediaItem, trend: TmdbTrendingItem, badge: HomeFeaturedBadge) = HomeFeaturedEntry(
        id = "${badge.name}|${if (trend.isSeries) "tv" else "movie"}|${trend.id}",
        badge = badge,
        title = match.title,
        isSeries = trend.isSeries,
        item = match,
        providerImage = match.posterUrl ?: match.logoUrl,
        tmdbId = trend.id,
        fallbackGenre = trend.genre,
        fallbackYear = trend.year,
        meta = trend.toMetadata(),
    )

    suspend fun match(trend: TmdbTrendingItem): MediaItem? {
        val catalogue = if (trend.isSeries) catalog.series else catalog.movies
        return findTrendInCatalogue(catalogue, trend.toMetadata(), if (trend.isSeries) MediaKind.SERIES else MediaKind.MOVIE)
    }

    // 2. Per te: consigliati TMDB dall'ultimo titolo visto.
    result.firstOrNull()?.let { seed ->
        val found = runCatching {
            if (seed.isSeries) app.tmdb.searchSeries(seed.title, apiKey) else app.tmdb.searchMovies(seed.title, apiKey)
        }.getOrNull()?.firstOrNull()
        if (found != null) {
            val recommended = runCatching { app.tmdb.recommendations(found.id, apiKey, seed.isSeries) }.getOrDefault(emptyList())
            var added = 0
            for (trend in recommended) {
                if (added >= FEATURED_MAX_FOR_YOU || result.size >= FEATURED_MAX) break
                val match = match(trend) ?: continue
                if (!used.add(match.id)) continue
                result += entryFor(match, trend, HomeFeaturedBadge.FOR_YOU)
                added++
            }
        }
    }
    if (result.isNotEmpty()) emit(result.toList())

    // 3. Di tendenza: film e serie della settimana, alternati.
    if (result.size < FEATURED_MAX) {
        val series = trendingSeries.await()
        val movies = trendingMovies.await()
        val mixed = ArrayList<TmdbTrendingItem>()
        for (index in 0 until maxOf(series.size, movies.size)) {
            movies.getOrNull(index)?.let(mixed::add)
            series.getOrNull(index)?.let(mixed::add)
        }
        for (trend in mixed) {
            if (result.size >= FEATURED_MAX) break
            val match = match(trend) ?: continue
            if (!used.add(match.id)) continue
            result += entryFor(match, trend, HomeFeaturedBadge.TRENDING)
            // Ogni scheda trovata compare subito.
            emit(result.toList())
        }
    }
    trendingSeries.cancel()
    trendingMovies.cancel()
    emit(result.toList())
    result
}

/** Locandina senza testo, logo, generi, anno e voto da TMDB (come la scheda dettaglio). */
private suspend fun enrichHomeFeaturedEntry(app: GassPlayerApplication, entry: HomeFeaturedEntry, apiKey: String): HomeFeaturedEntry? {
    if (apiKey.isBlank()) return null
    val tmdbId = entry.tmdbId ?: entry.item.tmdbId?.takeIf { it.isNotBlank() }
    val details = tmdbId?.let { id -> runCatching { app.tmdb.details(id, apiKey, entry.isSeries) }.getOrNull() }
        ?: run {
            val found = runCatching {
                if (entry.isSeries) app.tmdb.searchSeries(entry.title, apiKey) else app.tmdb.searchMovies(entry.title, apiKey)
            }.getOrNull()?.firstOrNull() ?: return null
            runCatching { app.tmdb.details(found.id, apiKey, entry.isSeries) }.getOrNull() ?: found
        }
    return entry.copy(
        meta = details,
        posterUrl = details.textlessPosterUrl ?: details.posterUrl,
        backdropUrl = details.textlessBackdropUrl ?: details.backdropUrl,
        logoUrl = details.logoUrl,
        genres = details.genres.take(2),
        year = details.year,
        rating = details.rating,
    )
}

/**
 * Hero "In evidenza". [edgeToEdge]: a tutta larghezza in cima alla Home, con [topContent] (barra "Home" e
 * ricerca) disegnato sopra l'immagine; altrimenti card arrotondata dentro la lista.
 */
@Composable
internal fun HomeFeaturedHero(
    entries: List<HomeFeaturedEntry>,
    isTelevision: Boolean,
    edgeToEdge: Boolean,
    onSelect: (HomeFeaturedEntry) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    topContent: (@Composable () -> Unit)? = null,
    /** Il focus del telecomando e' entrato nel pulsante o nei puntini della sezione. */
    onFocusInside: () -> Unit = {},
) {
    if (entries.isEmpty()) {
        topContent?.let { Box(modifier) { it() } }
        return
    }
    var selection by remember { mutableIntStateOf(0) }
    val index = selection.coerceIn(0, entries.lastIndex)
    var buttonFocused by remember { mutableStateOf(false) }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val height = when {
        !edgeToEdge -> (screenHeight * if (isTelevision) .62f else .56f).dp
        isTelevision -> (screenHeight * .80f).dp
        else -> (screenHeight * .70f).dp
    }
    val shape = if (edgeToEdge) RoundedCornerShape(0.dp) else RoundedCornerShape(26.dp)

    // Nessun cambio automatico: si scorre col dito, con SINISTRA/DESTRA o toccando i puntini.

    // Altezza minima dello schermo, ma il riquadro cresce se serve: intestazione (Home, ricerca) e contenuto
    // della scheda non si sovrappongono mai (prima su TV/schermi bassi badge e logo finivano sulla ricerca).
    val homeBackground = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .pointerInput(entries.size) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (abs(dragged) > 60f && entries.size > 1) {
                            selection = if (dragged < 0) (selection + 1) % entries.size else (selection - 1 + entries.size) % entries.size
                        }
                    },
                ) { change, amount ->
                    dragged += amount
                    change.consume()
                }
            }
    ) {
        // Immagine in dissolvenza + sfumatura verso il basso (sfondo della Home sotto).
        Crossfade(targetState = index, animationSpec = tween(600), label = "featured-artwork", modifier = Modifier.matchParentSize()) { shown ->
            val entry = entries.getOrNull(shown) ?: return@Crossfade
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > maxHeight * .9f
                val url = if (wide) entry.backdropUrl ?: entry.posterUrl else entry.posterUrl ?: entry.backdropUrl
                Box(Modifier.fillMaxSize().background(Color(0xFF141414)))
                ArtworkImage(
                    model = url ?: entry.providerImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // Sfumatura: piu' scura in alto (intestazione leggibile) e, a tutta larghezza, fino al colore reale
        // della Home in basso (come iOS: nessun bordo netto tra immagine e contenuto sotto).
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .55f),
                        .22f to Color.Black.copy(alpha = .18f),
                        .42f to Color.Transparent,
                        .62f to Color.Black.copy(alpha = .42f),
                        .84f to Color.Black.copy(alpha = .72f),
                        1f to if (edgeToEdge) homeBackground else Color.Black.copy(alpha = .78f),
                    )
                )
        )

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = height),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
        topContent?.let { content ->
            Box(Modifier.fillMaxWidth()) { content() }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(top = if (topContent != null) 20.dp else 0.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(targetState = index, animationSpec = tween(600), label = "featured-content") { shown ->
                val entry = entries.getOrNull(shown) ?: return@Crossfade
                HomeFeaturedSlide(
                    entry = entry,
                    isTelevision = isTelevision,
                    onSelect = onSelect,
                    onFocusChanged = {
                        buttonFocused = it
                        if (it) onFocusInside()
                    },
                    onStep = { forward ->
                        if (entries.size < 2) false else {
                            // SINISTRA sulla prima scheda lascia uscire il focus (barra di navigazione).
                            if (!forward && index == 0) false else {
                                selection = if (forward) (index + 1) % entries.size else index - 1
                                true
                            }
                        }
                    },
                )
            }
            if (entries.size > 1) {
                Spacer(Modifier.height(18.dp))
                // Puntini: col telecomando il focus e' evidenziato come nel resto dell'app e, appena un
                // puntino e' selezionato, la sua scheda compare subito (senza premere OK).
                // Stesso spazio e stessa misura di prima (7 dp, distanza 6,5 dp): il focus e' solo disegnato
                // sopra (contorno), senza spostare nulla.
                Row(horizontalArrangement = Arrangement.spacedBy(6.5.dp), verticalAlignment = Alignment.CenterVertically) {
                    entries.indices.forEach { dot ->
                        var dotFocused by remember { mutableStateOf(false) }
                        Box(
                            Modifier
                                .size(7.dp)
                                .onFocusChanged { state ->
                                    dotFocused = state.isFocused
                                    if (state.isFocused) {
                                        selection = dot
                                        onFocusInside()
                                    }
                                }
                                .remoteFocusOutline(cornerRadius = 50.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (dot == index || dotFocused) 1f else .35f))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selection = dot }
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun HomeFeaturedSlide(
    entry: HomeFeaturedEntry,
    isTelevision: Boolean,
    onSelect: (HomeFeaturedEntry) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onStep: (forward: Boolean) -> Boolean,
) {
    val logoMaxWidth = if (isTelevision) 380.dp else 270.dp
    val logoMaxHeight = if (isTelevision) 110.dp else 86.dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            entry.badge.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = .38f))
                .padding(horizontal = 9.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(10.dp))

        val titleText: @Composable () -> Unit = {
            Text(
                entry.title,
                color = Color.White,
                fontSize = if (isTelevision) 40.sp else 34.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = if (isTelevision) 44.sp else 38.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .45f), blurRadius = 18f)),
                modifier = Modifier.widthIn(max = logoMaxWidth + 60.dp)
            )
        }
        if (entry.logoUrl != null) {
            ArtworkImage(
                model = entry.logoUrl,
                contentDescription = entry.title,
                modifier = Modifier.sizeIn(maxWidth = logoMaxWidth, maxHeight = logoMaxHeight),
                contentScale = ContentScale.Fit,
                fallback = { titleText() },
            )
        } else {
            titleText()
        }
        Spacer(Modifier.height(12.dp))

        homeFeaturedMetaLine(entry)?.let { meta ->
            Text(
                meta,
                color = Color.White.copy(alpha = .94f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(13.dp))
        }

        // Pulsante Liquid Glass identico al tasto "Modifica" (GlassCapsuleButton / ClearGlassCapsule iOS).
        val label = if (entry.isResume) "Continua a guardare" else "Maggiori informazioni"
        Surface(
            onClick = { onSelect(entry) },
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> onStep(true)
                        Key.DirectionLeft -> onStep(false)
                        else -> false
                    }
                }
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .remoteFocusOutline(cornerRadius = 50.dp)
                .semantics { contentDescription = "$label: ${entry.title}" },
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
        ) {
            // Dimensioni del pulsante bianco di prima: altezza 46 (52 su TV), margini 22, icona 22, testo 17.
            // La pillola aggiunge gia' 14 dp di lato e 9 dp sopra/sotto.
            LiquidGlassPill {
                Row(
                    Modifier
                        .height((if (isTelevision) 52.dp else 46.dp) - 18.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (entry.isResume) Icons.Default.PlayArrow else Icons.Outlined.Info,
                        contentDescription = null,
                        tint = glassForeground(),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(label, color = glassForeground(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** "S1, E3 · Commedia · ★7" (ripresa di una serie) oppure "Azione, Crime · 2025 · ★7". */
private fun homeFeaturedMetaLine(entry: HomeFeaturedEntry): String? {
    val parts = ArrayList<String>()
    val genres = entry.genres.ifEmpty { listOfNotNull(entry.fallbackGenre) }
    if (entry.isResume && entry.isSeries) {
        entry.episodeLabel?.let(parts::add)
        genres.firstOrNull()?.let(parts::add)
    } else {
        if (genres.isNotEmpty()) parts += genres.joinToString(", ")
        (entry.year ?: entry.fallbackYear)?.let(parts::add)
    }
    entry.rating?.takeIf { it > 0 }?.let { parts += "★${it.roundToInt()}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
