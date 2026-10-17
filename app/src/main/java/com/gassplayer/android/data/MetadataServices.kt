package com.gassplayer.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Locale

class TmdbService(private val api: NetworkApi) {
    suspend fun search(query: String, apiKey: String): List<MetadataResult> = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext emptyList(); val root = api.getJson("https://api.themoviedb.org/3/search/multi?api_key=${enc(apiKey)}&query=${enc(query)}&language=it-IT"); root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList() }
    /** Trending rails used by the iOS HomeView, returned as metadata cards to be matched against the active playlist. */
    suspend fun trending(mediaType: String, apiKey: String, window: String = "week"): List<MetadataResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val path = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)) "tv" else "movie"
        val period = if (window == "day") "day" else "week"
        val root = api.getJson("https://api.themoviedb.org/3/trending/$path/$period?api_key=${enc(apiKey)}&language=it-IT")
        root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList()
    }
    private val trendingRailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TmdbTrendingItem>>>()
    private val genreNamesCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, String>>()

    /**
     * TMDBService.trending(isSeries:limit:) on iOS: the week's 20 most trending titles, pages 1-2 until the
     * limit, only titles with a backdrop (16:9 card), first genre in Italian, cached for 30 minutes.
     * Throws when nothing could be loaded, so the rail can show "Impossibile caricare…".
     */
    suspend fun trendingRail(isSeries: Boolean, apiKey: String, limit: Int = 20): List<TmdbTrendingItem> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Chiave API TMDB mancante" }
        val key = if (isSeries) "tv" else "movie"
        val cacheKey = "$key|$apiKey"
        trendingRailCache[cacheKey]?.let { (date, items) ->
            if (System.currentTimeMillis() - date < 1_800_000L) return@withContext items.take(limit)
        }
        val genres = genreNames(key, apiKey)
        val items = ArrayList<TmdbTrendingItem>()
        val seen = HashSet<String>()
        var lastError: Throwable? = null
        for (page in 1..2) {
            if (items.size >= limit) break
            val root = try {
                api.getJson("https://api.themoviedb.org/3/trending/$key/week?api_key=${enc(apiKey)}&language=it-IT&page=$page")
            } catch (error: Throwable) {
                lastError = error
                break
            }
            root.jsonObject["results"]?.jsonArray?.forEach { element ->
                val raw = element.jsonObject
                val id = raw.stringOrNull("id") ?: return@forEach
                val backdrop = raw.stringOrNull("backdrop_path")?.takeIf { it.isNotBlank() } ?: return@forEach
                val title = (raw.stringOrNull("title") ?: raw.stringOrNull("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (!seen.add(id)) return@forEach
                val date = raw.stringOrNull("release_date") ?: raw.stringOrNull("first_air_date")
                items += TmdbTrendingItem(
                    id = id,
                    title = title,
                    originalTitle = raw.stringOrNull("original_title") ?: raw.stringOrNull("original_name"),
                    genre = raw["genre_ids"]?.jsonArray?.firstNotNullOfOrNull { element -> element.jsonPrimitive.intOrNull?.let { genres[it] } },
                    backdropUrl = "https://image.tmdb.org/t/p/w780$backdrop",
                    year = date?.takeIf { it.length >= 4 }?.take(4),
                    isSeries = isSeries
                )
            }
        }
        if (items.isEmpty()) throw (lastError ?: IllegalStateException("Nessun titolo di tendenza"))
        trendingRailCache[cacheKey] = System.currentTimeMillis() to items
        items.take(limit)
    }

    /**
     * Titoli consigliati da TMDB a partire da un titolo visto (`{movie|tv}/{id}/recommendations`), per "Per te"
     * nella sezione "In evidenza" della Home (iOS TMDBService.recommendations). Cache di 30 minuti.
     */
    suspend fun recommendations(id: String, apiKey: String, series: Boolean, limit: Int = 20): List<TmdbTrendingItem> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || id.isBlank()) return@withContext emptyList()
        val key = if (series) "tv" else "movie"
        val cacheKey = "rec|$key|$id|$apiKey"
        trendingRailCache[cacheKey]?.let { (date, items) ->
            if (System.currentTimeMillis() - date < 1_800_000L) return@withContext items.take(limit)
        }
        val genres = genreNames(key, apiKey)
        val root = api.getJson("https://api.themoviedb.org/3/$key/${enc(id)}/recommendations?api_key=${enc(apiKey)}&language=it-IT")
        val seen = HashSet<String>()
        val items = root.jsonObject["results"]?.jsonArray?.mapNotNull { element ->
            val raw = element.jsonObject
            val itemId = raw.stringOrNull("id") ?: return@mapNotNull null
            val title = (raw.stringOrNull("title") ?: raw.stringOrNull("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seen.add(itemId)) return@mapNotNull null
            val date = raw.stringOrNull("release_date") ?: raw.stringOrNull("first_air_date")
            TmdbTrendingItem(
                id = itemId,
                title = title,
                originalTitle = raw.stringOrNull("original_title") ?: raw.stringOrNull("original_name"),
                genre = raw["genre_ids"]?.jsonArray?.firstNotNullOfOrNull { genre -> genre.jsonPrimitive.intOrNull?.let { genres[it] } },
                backdropUrl = raw.stringOrNull("backdrop_path")?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" },
                year = date?.takeIf { it.length >= 4 }?.take(4),
                isSeries = series
            )
        }.orEmpty()
        trendingRailCache[cacheKey] = System.currentTimeMillis() to items
        items.take(limit)
    }

    private suspend fun genreNames(key: String, apiKey: String): Map<Int, String> {
        genreNamesCache[key]?.let { return it }
        val map = runCatching {
            api.getJson("https://api.themoviedb.org/3/genre/$key/list?api_key=${enc(apiKey)}&language=it-IT")
                .jsonObject["genres"]?.jsonArray?.mapNotNull { element ->
                    val genre = element.jsonObject
                    val id = genre["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val name = genre["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    id to name
                }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
        if (map.isNotEmpty()) genreNamesCache[key] = map
        return map
    }

    /** Type-specific title search used by detail screens to avoid selecting a same-title film/series/person. */
    suspend fun searchMovies(query: String, apiKey: String): List<MetadataResult> = searchByType(query, apiKey, "movie")
    suspend fun searchSeries(query: String, apiKey: String): List<MetadataResult> = searchByType(query, apiKey, "tv")
    private suspend fun searchByType(query: String, apiKey: String, type: String): List<MetadataResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext emptyList()
        val root = api.getJson("https://api.themoviedb.org/3/search/$type?api_key=${enc(apiKey)}&query=${enc(query)}&language=it-IT")
        root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList()
    }
    suspend fun details(id: String, apiKey: String, series: Boolean): MetadataResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val path = if (series) "tv" else "movie"
        api.getJson("https://api.themoviedb.org/3/$path/$id?api_key=${enc(apiKey)}&language=it-IT&append_to_response=credits,external_ids,images&include_image_language=it,en,null").jsonObject.toMeta()
    }
    /**
     * Episode metadata fallback mirroring SeriesEpisodesView.swift (TMDB fills missing provider synopsis/stills).
     * Come TMDBService.seasonEpisodes iOS: le trame vuote in italiano vengono completate con quelle inglesi
     * (TMDB lascia spesso vuoto `overview` in it-IT), stessa immagine/data di riserva. Cache per serie+stagione.
     */
    suspend fun seasonEpisodes(tvId: String, season: Int, apiKey: String): List<TmdbEpisodeResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || tvId.isBlank() || season < 0) return@withContext emptyList()
        val cacheKey = "$tvId::$season"
        seasonCache[cacheKey]?.let { return@withContext it }
        var episodes = fetchSeasonEpisodes(tvId, season, apiKey, "it-IT")
        if (episodes.any { it.overview.isNullOrBlank() }) {
            val english = runCatching { fetchSeasonEpisodes(tvId, season, apiKey, "en-US") }.getOrNull()
            if (english != null) {
                val byNumber = english.associateBy { it.episodeNumber }
                episodes = episodes.map { episode ->
                    val fallback = byNumber[episode.episodeNumber]
                    if (!episode.overview.isNullOrBlank() || fallback?.overview.isNullOrBlank()) episode
                    else episode.copy(
                        overview = fallback?.overview,
                        stillUrl = episode.stillUrl ?: fallback?.stillUrl,
                        airDate = episode.airDate ?: fallback?.airDate
                    )
                }
            }
        }
        seasonCache[cacheKey] = episodes
        episodes
    }
    private val seasonCache = java.util.concurrent.ConcurrentHashMap<String, List<TmdbEpisodeResult>>()
    private suspend fun fetchSeasonEpisodes(tvId: String, season: Int, apiKey: String, language: String): List<TmdbEpisodeResult> {
        val root = api.getJson("https://api.themoviedb.org/3/tv/${enc(tvId)}/season/$season?api_key=${enc(apiKey)}&language=$language")
        return root.jsonObject["episodes"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject
            val number = obj["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            TmdbEpisodeResult(
                seasonNumber = obj["season_number"]?.jsonPrimitive?.intOrNull ?: season,
                episodeNumber = number,
                title = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                overview = obj["overview"]?.jsonPrimitive?.contentOrNull,
                stillUrl = obj["still_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w780$it" },
                airDate = obj["air_date"]?.jsonPrimitive?.contentOrNull,
                runtimeMinutes = obj["runtime"]?.jsonPrimitive?.intOrNull
            )
        } ?: emptyList()
    }

    // MARK: - Dettaglio arricchito con matching di precisione (TMDBService.fullDetails iOS)

    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, MetadataResult>()
    private val fullDetailsCache = java.util.concurrent.ConcurrentHashMap<String, MetadataResult>()
    private val noResultCache: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun cachedDetails(id: String, apiKey: String, series: Boolean): MetadataResult? {
        val key = "${if (series) "tv" else "movie"}::$id"
        detailsCache[key]?.let { return it }
        val result = details(id, apiKey, series) ?: return null
        detailsCache[key] = result
        return result
    }

    /**
     * Scorciatoia usata dalle schede dettaglio, identica a TMDBService.fullDetails iOS. Ordine di affidabilità:
     * 1. id TMDB fornito dal pannello Xtream (`tmdb_id`), verificato (titolo simile o un attore in comune);
     * 2. ricerca per titolo ripulito + anno con punteggio (mai il primo risultato "a caso");
     * 3. spareggio fra candidati vicini tramite sovrapposizione del cast dichiarato dal provider.
     * Restituisce `null` se non c'è una corrispondenza sufficientemente sicura.
     */
    suspend fun fullDetails(
        title: String,
        isSeries: Boolean,
        apiKey: String,
        year: String? = null,
        tmdbId: String? = null,
        castNames: List<String> = emptyList(),
    ): MetadataResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val key = "full::${titleCacheKey(isSeries, title, year)}::${tmdbId.orEmpty()}"
        fullDetailsCache[key]?.let { return@withContext it }

        var providerIdDetails: MetadataResult? = null
        val providerId = tmdbId?.trim()?.takeIf { id -> id.isNotEmpty() && (id.toIntOrNull() ?: 0) > 0 }
        if (providerId != null) {
            val byId = runCatching { cachedDetails(providerId, apiKey, isSeries) }.getOrNull()
            if (byId != null) {
                if (isTrustworthy(byId, title, castNames)) {
                    fullDetailsCache[key] = byId
                    return@withContext byId
                }
                providerIdDetails = byId
            }
        }

        val ranked = runCatching { rankedCandidates(title, isSeries, year, apiKey) }.getOrNull()
            ?: return@withContext providerIdDetails
        val best = ranked.firstOrNull() ?: return@withContext providerIdDetails

        var chosen = best
        var chosenDetails: MetadataResult? = null
        val nearTies = ranked.filter { best.total - it.total <= 0.15 }.take(3)
        if (castNames.isNotEmpty() && nearTies.size > 1) {
            var bestOverlap = -1
            for (candidate in nearTies) {
                val candidateDetails = runCatching { cachedDetails(candidate.result.id, apiKey, isSeries) }.getOrNull() ?: continue
                val overlap = castOverlap(castNames, candidateDetails.castMembers.map { it.name })
                if (overlap > bestOverlap || (overlap == bestOverlap && candidate.total > chosen.total)) {
                    bestOverlap = overlap
                    chosen = candidate
                    chosenDetails = candidateDetails
                }
            }
        }
        val result = chosenDetails ?: runCatching { cachedDetails(chosen.result.id, apiKey, isSeries) }.getOrNull() ?: chosen.result
        fullDetailsCache[key] = result
        result
    }

    private data class ParsedTitle(val query: String, val year: String?, val trailingYear: String?)
    private class ScoredCandidate(val result: MetadataResult, val titleScore: Double, val total: Double)

    /**
     * Titolo del provider scomposto in query pulita + anno: tag di qualità/lingua rimossi solo come parole intere
     * (mai "Suburbicon" -> "urbicon"), prefissi "IT - ", "|IT|", "[ITA]", "IT: " tolti, anno "(2001)" estratto.
     */
    private fun parseTitle(rawTitle: String): ParsedTitle {
        var working = rawTitle
        val explicitYear = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""").find(working)?.groupValues?.getOrNull(1)
        val prefixPatterns = listOf(
            Regex("""^\s*[|\[]\s*([A-Za-z0-9]{2,5})\s*[|\]]\s*"""),
            Regex("""^\s*([A-Za-z0-9]{2,5})\s+[-–—]\s+"""),
            Regex("""^\s*([A-Za-z0-9]{2,5})\s*:\s+""")
        )
        for (pattern in prefixPatterns) {
            val match = pattern.find(working) ?: continue
            if (match.groupValues[1].uppercase(Locale.ROOT) in providerPrefixCodes) {
                working = working.removeRange(match.range)
                break
            }
        }
        for (pattern in listOf("""\(.*?\)""", """\[.*?\]""", """\{.*?\}""")) {
            working = working.replace(Regex(pattern), " ")
        }
        for (word in noiseWords) {
            working = working.replace(Regex("(?<![A-Za-z0-9])${Regex.escape(word)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE), " ")
        }
        working = working.replace(Regex("""\s+"""), " ").trim { it.isWhitespace() || it in "-–—|:._" }
        var trailingYear: String? = null
        Regex("""\s+((?:19|20)\d{2})$""").find(working)?.let { match ->
            if (working.substring(0, match.range.first).trim().length >= 2) trailingYear = match.groupValues[1]
        }
        return ParsedTitle(working, explicitYear, trailingYear)
    }

    private fun titleCacheKey(isSeries: Boolean, title: String, year: String?): String {
        val parsed = parseTitle(title)
        return "${if (isSeries) "tv" else "movie"}::${normalizeTitle(parsed.query)}::${year ?: parsed.year.orEmpty()}"
    }

    private fun score(result: MetadataResult, query: String, yearHint: String?): ScoredCandidate {
        val normalizedQuery = normalizeTitle(query)
        val titleScore = listOfNotNull(result.title, result.originalTitle)
            .map { normalizeTitle(it) }.filter { it.isNotEmpty() }
            .maxOfOrNull { titleSimilarity(normalizedQuery, it) } ?: 0.0
        val (yearValue, penalty) = yearScore(result.year, yearHint)
        val popularity = minOf(kotlin.math.log10(1.0 + (result.voteCount ?: 0)) / 4.0, 1.0)
        val total = titleScore * 0.7 + yearValue * 0.2 + popularity * 0.1 - penalty
        return ScoredCandidate(result, titleScore, total)
    }

    private suspend fun searchWithYear(query: String, isSeries: Boolean, year: String?, apiKey: String): List<MetadataResult> {
        val type = if (isSeries) "tv" else "movie"
        val yearParam = year?.let { "&${if (isSeries) "first_air_date_year" else "year"}=${enc(it)}" }.orEmpty()
        val root = api.getJson("https://api.themoviedb.org/3/search/$type?api_key=${enc(apiKey)}&query=${enc(query)}&language=it-IT&include_adult=false$yearParam")
        return root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList()
    }

    /** Tentativi di ricerca, dal più specifico al più permissivo (searchAttempts iOS). */
    private fun searchAttempts(parsed: ParsedTitle, yearOverride: String?): List<Pair<String, String?>> {
        val hint = parsed.year ?: yearOverride
        val attempts = ArrayList<Pair<String, String?>>()
        if (hint != null) attempts += parsed.query to hint
        attempts += parsed.query to null
        parsed.trailingYear?.let { trailing ->
            val withoutYear = parsed.query.replace(Regex("""\s+(?:19|20)\d{2}$"""), "")
            attempts += withoutYear to (hint ?: trailing)
            attempts += withoutYear to null
        }
        for (separator in listOf(":", " - ")) {
            val head = parsed.query.split(separator).firstOrNull()
            if (head != null && head.length >= 3 && head != parsed.query) attempts += head.trim() to hint
        }
        val seen = HashSet<String>()
        return attempts.filter { seen.add("${it.first.lowercase(Locale.ROOT)}|${it.second.orEmpty()}") }
    }

    private suspend fun rankedCandidates(rawTitle: String, isSeries: Boolean, yearHint: String?, apiKey: String): List<ScoredCandidate> {
        val parsed = parseTitle(rawTitle)
        if (parsed.query.isEmpty()) return emptyList()
        val cacheKey = titleCacheKey(isSeries, rawTitle, yearHint)
        if (cacheKey in noResultCache) return emptyList()
        val pool = HashMap<String, ScoredCandidate>()
        var lastError: Throwable? = null
        var anySuccess = false
        for ((query, year) in searchAttempts(parsed, yearHint)) {
            val results = try {
                searchWithYear(query, isSeries, year, apiKey).also { anySuccess = true }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            val hint = parsed.year ?: yearHint ?: year ?: parsed.trailingYear
            for (result in results.take(10)) {
                val scored = score(result, query, hint)
                val existing = pool[result.id]
                if (existing != null && existing.total >= scored.total) continue
                pool[result.id] = scored
            }
            val best = pool.values.maxByOrNull { it.total }
            if (best != null && best.titleScore >= 1.0 && best.total >= CONFIDENT_TOTAL_SCORE) break
        }
        if (!anySuccess && lastError != null) throw lastError
        val ranked = pool.values
            .filter { it.titleScore >= MINIMUM_TITLE_SCORE && it.total >= MINIMUM_TOTAL_SCORE }
            .sortedByDescending { it.total }
        if (ranked.isEmpty()) noResultCache += cacheKey
        return ranked
    }

    /** L'id del pannello è attendibile se il titolo coincide abbastanza o se un attore del provider è nel cast TMDB. */
    private fun isTrustworthy(details: MetadataResult, rawTitle: String, castNames: List<String>): Boolean {
        val query = normalizeTitle(parseTitle(rawTitle).query)
        val similarity = listOfNotNull(details.title, details.originalTitle)
            .map { normalizeTitle(it) }.maxOfOrNull { titleSimilarity(query, it) } ?: 0.0
        if (similarity >= 0.6) return true
        return castOverlap(castNames, details.castMembers.map { it.name }) > 0
    }

    private companion object {
        const val MINIMUM_TITLE_SCORE = 0.6
        const val MINIMUM_TOTAL_SCORE = 0.62
        const val CONFIDENT_TOTAL_SCORE = 0.88
        val noiseWords = listOf(
            "4K", "UHD", "HD", "FHD", "SD", "HDR", "HDR10", "DV", "ITA", "ENG", "SUB", "SUBITA",
            "DUAL", "MULTI", "BLURAY", "BDRIP", "WEBRIP", "WEB-DL", "WEBDL", "H264", "H265", "HEVC", "X264", "X265"
        )
        val providerPrefixCodes = setOf(
            "IT", "ITA", "EN", "ENG", "FR", "DE", "ES", "UK", "US", "PT", "NL", "TR", "AR",
            "MULTI", "SUB", "VOD", "4K", "UHD", "NF", "AMZN", "DSNP", "SKY", "NOW"
        )

        /** Minuscolo, senza accenti, solo lettere/numeri separati da singoli spazi ("Spider-Man" -> "spider man"). */
        fun normalizeTitle(text: String): String {
            val folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
                .replace(Regex("""\p{M}+"""), "")
                .lowercase(Locale.ROOT)
            val result = StringBuilder(folded.length)
            var lastWasSpace = true
            for (ch in folded) {
                if (ch.isLetterOrDigit()) { result.append(ch); lastWasSpace = false }
                else if (!lastWasSpace) { result.append(' '); lastWasSpace = true }
            }
            return result.toString().trim()
        }

        /** 1 solo se identici; altrimenti coefficiente di Dice sui token, al massimo 0.95. */
        fun titleSimilarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            val tokensA = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val tokensB = b.split(' ').filter { it.isNotEmpty() }.toSet()
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
            val common = tokensA.intersect(tokensB).size.toDouble()
            return minOf(2.0 * common / (tokensA.size + tokensB.size), 0.95)
        }

        fun yearScore(candidate: String?, hint: String?): Pair<Double, Double> {
            val hintValue = hint?.toIntOrNull() ?: return 0.5 to 0.0
            val candidateValue = candidate?.toIntOrNull() ?: return 0.35 to 0.0
            return when (kotlin.math.abs(candidateValue - hintValue)) {
                0 -> 1.0 to 0.0
                1 -> 0.7 to 0.0
                else -> 0.0 to 0.2
            }
        }

        fun castOverlap(names: List<String>, credits: List<String>): Int {
            val wanted = names.map { normalizeTitle(it) }.filter { it.isNotEmpty() }.toSet()
            if (wanted.isEmpty()) return 0
            return credits.take(15).count { normalizeTitle(it) in wanted }
        }
    }
    private fun JsonObject.toMeta(): MetadataResult? {
        val id = stringOrNull("id") ?: return null
        val logos = this["images"]?.jsonObject?.get("logos")?.jsonArray.orEmpty()
        val logo = logos.mapNotNull { it.jsonObject }.sortedBy { image ->
            when (image["iso_639_1"]?.jsonPrimitive?.contentOrNull) { "it" -> 0; "en" -> 1; null -> 2; else -> 3 }
        }.firstOrNull()
        return MetadataResult(
            id = id,
            title = stringOrNull("title") ?: stringOrNull("name") ?: "",
            year = (stringOrNull("release_date") ?: stringOrNull("first_air_date"))?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } },
            rating = doubleOrNull("vote_average"),
            posterUrl = stringOrNull("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            backdropUrl = stringOrNull("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            overview = stringOrNull("overview"),
            genres = this["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            runtimeMinutes = this["runtime"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                ?: this["episode_run_time"]?.let { it as? JsonArray }?.firstOrNull()?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            voteCount = this["vote_count"]?.jsonPrimitive?.intOrNull,
            cast = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(12)?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            castMembers = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(12)?.mapNotNull { element ->
                val person = element.jsonObject
                val name = person["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                MetadataCastMember(
                    name = name,
                    character = person["character"]?.jsonPrimitive?.contentOrNull,
                    profileUrl = person["profile_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w185$it" }
                )
            } ?: emptyList(),
            externalId = this["external_ids"]?.jsonObject?.get("imdb_id")?.jsonPrimitive?.contentOrNull,
            originalTitle = stringOrNull("original_title") ?: stringOrNull("original_name"),
            logoUrl = logo?.get("file_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" },
            // Immagini senza testo (iso_639_1 nullo) per la sezione "In evidenza" della Home.
            textlessPosterUrl = this["images"]?.jsonObject?.get("posters")?.jsonArray?.mapNotNull { it.jsonObject }
                ?.firstOrNull { it["iso_639_1"]?.jsonPrimitive?.contentOrNull == null }
                ?.get("file_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w780$it" },
            textlessBackdropUrl = this["images"]?.jsonObject?.get("backdrops")?.jsonArray?.mapNotNull { it.jsonObject }
                ?.firstOrNull { it["iso_639_1"]?.jsonPrimitive?.contentOrNull == null }
                ?.get("file_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w1280$it" }
        )
    }
    private fun JsonObject.stringOrNull(k: String)=this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.doubleOrNull(k:String)=this[k]?.jsonPrimitive?.doubleOrNull
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}

class OmdbService(private val api: NetworkApi) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Ratings>()

    /**
     * Valutazioni OMDb (OMDbService.ratings iOS): IMDb, Rotten Tomatoes (critica) e Metacritic. Per IMDb id quando
     * TMDB lo fornisce (corrispondenza esatta), altrimenti per titolo (+ anno/tipo). `Response: False` = nessun voto;
     * Metacritic da `Metascore` o, se "N/A", dalla voce "Metacritic" di `Ratings` ("52/100"). Cache per sessione.
     */
    suspend fun lookup(title: String, apiKey: String, imdbId: String? = null, year: String? = null, series: Boolean? = null): Ratings? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val query = if (!imdbId.isNullOrBlank()) "i=${enc(imdbId)}" else {
            if (title.isBlank()) return@withContext null
            "t=${enc(title)}" + year?.takeIf { it.isNotBlank() }?.let { "&y=${enc(it)}" }.orEmpty() +
                series?.let { if (it) "&type=series" else "&type=movie" }.orEmpty()
        }
        cache[query]?.let { return@withContext it }
        val o = api.getJson("https://www.omdbapi.com/?apikey=${enc(apiKey)}&$query").jsonObject
        if (o["Response"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) != true) return@withContext null
        val sources = o["Ratings"]?.let { it as? JsonArray }?.mapNotNull { it as? JsonObject }.orEmpty()
        fun sourceValue(name: String) = sources.firstOrNull { it["Source"]?.jsonPrimitive?.contentOrNull == name }?.get("Value")?.jsonPrimitive?.contentOrNull
        val imdb = o["imdbRating"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }?.toDoubleOrNull()
        val rotten = sourceValue("Rotten Tomatoes")?.replace("%", "")?.trim()?.toIntOrNull()?.let { "$it%" }
        val metacritic = o["Metascore"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }?.toIntOrNull()
            ?: sourceValue("Metacritic")?.substringBefore('/')?.trim()?.toIntOrNull()
        Ratings(imdb, rotten, metacritic).also { cache[query] = it }
    }
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}

class OpenSubtitlesService(private val api: NetworkApi) {
    suspend fun search(query: String, apiKey: String): List<SubtitleResult> = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext emptyList(); val root = api.getJson("https://api.opensubtitles.com/api/v1/subtitles?query=${URLEncoder.encode(query, "UTF-8")}", mapOf("Api-Key" to apiKey, "Accept" to "application/json")); root.jsonObject["data"]?.jsonArray?.mapNotNull { el -> val o = el.jsonObject; val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null; SubtitleResult(id, o["attributes"]?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull.orEmpty(), o["attributes"]?.jsonObject?.get("release")?.jsonPrimitive?.contentOrNull.orEmpty()) } ?: emptyList() }
}

class TraktService(private val api: NetworkApi) {
    suspend fun ratings(title: String, clientId: String): Double? = withContext(Dispatchers.IO) { if (clientId.isBlank()) return@withContext null; val o = api.getJson("https://api.trakt.tv/search/movie?query=${URLEncoder.encode(title, "UTF-8")}&limit=1", mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId)).jsonArray.firstOrNull()?.jsonObject ?: return@withContext null; o["score"]?.jsonPrimitive?.doubleOrNull?.times(10) }
    /**
     * Voto medio Trakt (community) per IMDb id, come TraktService.ratings iOS (`{movies|shows}/{imdb}/ratings`,
     * solo header `trakt-api-key`): restituito in percentuale (rating × 10) per la sezione "VALUTAZIONI".
     */
    suspend fun ratingsByImdb(imdbId: String, series: Boolean, clientId: String): Double? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || imdbId.isBlank()) return@withContext null
        val path = if (series) "shows" else "movies"
        val o = api.getJson(
            "https://api.trakt.tv/$path/${URLEncoder.encode(imdbId, "UTF-8")}/ratings",
            mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId.trim(), "Content-Type" to "application/json")
        ) as? JsonObject ?: return@withContext null
        o["rating"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }?.let { it * 10 }
    }
    suspend fun deviceCode(clientId: String, clientSecret: String): TraktDeviceCode? = withContext(Dispatchers.IO) { if (clientId.isBlank() || clientSecret.isBlank()) return@withContext null; val o = api.postJson("https://trakt.tv/oauth/device/code", JsonStore.json.encodeToString(kotlinx.serialization.json.buildJsonObject { put("client_id", clientId); }), mapOf("Content-Type" to "application/json")); TraktDeviceCode(o.jsonObject["device_code"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.jsonObject["user_code"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.jsonObject["verification_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.jsonObject["expires_in"]?.jsonPrimitive?.intOrNull ?: 600) }
    suspend fun pollDevice(clientId: String, clientSecret: String, deviceCode: String): TraktAccount? = withContext(Dispatchers.IO) { if (clientId.isBlank() || clientSecret.isBlank() || deviceCode.isBlank()) return@withContext null; val o = api.postJson("https://trakt.tv/oauth/device/token", JsonStore.json.encodeToString(buildJsonObject { put("code", deviceCode); put("client_id", clientId); put("client_secret", clientSecret) })); val access=o.jsonObject["access_token"]?.jsonPrimitive?.contentOrNull ?: return@withContext null; TraktAccount(access, o.jsonObject["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(), "") }
    suspend fun scrobbleStart(account: TraktAccount, clientId: String, item: MediaItem, progress: Double) { scrobble("start", account, clientId, item, progress) }
    suspend fun scrobbleStop(account: TraktAccount, clientId: String, item: MediaItem, progress: Double) { scrobble("stop", account, clientId, item, progress) }
    private suspend fun scrobble(action:String, account:TraktAccount, clientId:String, item:MediaItem, progress:Double){ if(account.accessToken.isBlank()||clientId.isBlank()) return; api.postJson("https://api.trakt.tv/scrobble/$action", buildJsonObject { put("progress", progress.coerceIn(0.0,100.0)); put(if(item.kind==MediaKind.MOVIE)"movie" else "episode", buildJsonObject { put("title", item.title); item.tmdbId?.let{put("ids", buildJsonObject{put("tmdb", it.toIntOrNull() ?: 0)})} }) }.toString(), mapOf("Content-Type" to "application/json","trakt-api-key" to clientId,"trakt-api-version" to "2","Authorization" to "Bearer ${account.accessToken}")) }
}

@kotlinx.serialization.Serializable
data class MetadataResult(
    val id: String,
    val title: String,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val castMembers: List<MetadataCastMember> = emptyList(),
    val externalId: String? = null,
    val originalTitle: String? = null,
    val logoUrl: String? = null,
    /** Durata TMDB in minuti (film: `runtime`; serie: primo `episode_run_time`). */
    val runtimeMinutes: Int? = null,
    /** Numero di voti TMDB: solo spareggio del matching di precisione fra omonimi. */
    val voteCount: Int? = null,
    /** Release / first-air year from TMDB, used to match trending titles to the playlist accurately. */
    val year: String? = null,
    /** Locandina verticale senza testo (Home "In evidenza"); null se TMDB non la ha. */
    val textlessPosterUrl: String? = null,
    /** Immagine orizzontale senza testo (Home "In evidenza" su schermi larghi). */
    val textlessBackdropUrl: String? = null
)

/** TMDBTrendingItem on iOS: one card of "Film/Serie di tendenza". */
data class TmdbTrendingItem(
    val id: String,
    val title: String,
    val originalTitle: String?,
    /** First genre (Italian), shown under the title. */
    val genre: String?,
    /** Horizontal 16:9 image for the card. */
    val backdropUrl: String?,
    val year: String?,
    val isSeries: Boolean
) {
    /** Metadata handed to the catalogue matcher and shown at once by the detail sheet. */
    fun toMetadata(): MetadataResult = MetadataResult(
        id = id,
        title = title,
        backdropUrl = backdropUrl,
        genres = listOfNotNull(genre),
        originalTitle = originalTitle,
        year = year
    )
}

@kotlinx.serialization.Serializable
data class MetadataCastMember(
    val name: String,
    val character: String? = null,
    val profileUrl: String? = null
)

data class TmdbEpisodeResult(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null
)

data class Ratings(val imdb: Double?, val rottenTomatoes: String?, val metacritic: Int?)
data class SubtitleResult(val id: String, val language: String, val release: String)
data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int)
