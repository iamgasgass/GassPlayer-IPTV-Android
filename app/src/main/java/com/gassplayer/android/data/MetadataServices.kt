package com.gassplayer.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLEncoder

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
        if (apiKey.isBlank() || id.isBlank()) return@withContext null
        val path = if (series) "tv" else "movie"
        val cacheKey = "$path::$id"
        detailsCache[cacheKey]?.let { return@withContext it }
        api.getJson("https://api.themoviedb.org/3/$path/${enc(id)}?api_key=${enc(apiKey)}&language=it-IT&append_to_response=credits,external_ids,images&include_image_language=it,en,null")
            .jsonObject.toMeta()?.also { detailsCache[cacheKey] = it }
    }

    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, MetadataResult>()
    private val lookupCache = java.util.concurrent.ConcurrentHashMap<String, MetadataResult>()
    private val noResultCache: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val fullDetailsCache = java.util.concurrent.ConcurrentHashMap<String, MetadataResult>()
    private val seasonCache = java.util.concurrent.ConcurrentHashMap<String, List<TmdbEpisodeResult>>()

    /** `search/movie|tv` come iOS: lingua it-IT, niente contenuti adult, anno (`year` / `first_air_date_year`) se noto. */
    private suspend fun searchForMatching(query: String, isSeries: Boolean, year: String?, apiKey: String): List<MetadataResult> = withContext(Dispatchers.IO) {
        val endpoint = if (isSeries) "search/tv" else "search/movie"
        val yearParam = year?.let { "&${if (isSeries) "first_air_date_year" else "year"}=${enc(it)}" }.orEmpty()
        val root = api.getJson("https://api.themoviedb.org/3/$endpoint?api_key=${enc(apiKey)}&query=${enc(query)}&language=it-IT&include_adult=false$yearParam")
        root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList()
    }

    /** Candidati ordinati per punteggio (decrescente), già filtrati dalle soglie minime (iOS `rankedCandidates`). */
    private suspend fun rankedCandidates(title: String, isSeries: Boolean, yearHint: String?, apiKey: String): List<TmdbTitleMatcher.Scored> {
        val parsed = TmdbTitleMatcher.parseTitle(title)
        if (parsed.query.isEmpty()) return emptyList()
        val pool = LinkedHashMap<String, TmdbTitleMatcher.Scored>()
        var lastError: Throwable? = null
        var anySuccess = false
        for ((query, attemptYear) in TmdbTitleMatcher.searchAttempts(parsed, yearHint)) {
            val results = try {
                searchForMatching(query, isSeries, attemptYear, apiKey).also { anySuccess = true }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            val hint = parsed.year ?: yearHint ?: attemptYear ?: parsed.trailingYear
            for (result in results.take(10)) {
                val scored = TmdbTitleMatcher.score(result, query, hint)
                val existing = pool[result.id]
                if (existing != null && existing.total >= scored.total) continue
                pool[result.id] = scored
            }
            val best = pool.values.maxByOrNull { it.total }
            if (best != null && best.titleScore >= 1.0 && best.total >= TmdbTitleMatcher.CONFIDENT_TOTAL_SCORE) break
        }
        if (!anySuccess && lastError != null) throw lastError!!
        return pool.values
            .filter { it.titleScore >= TmdbTitleMatcher.MINIMUM_TITLE_SCORE && it.total >= TmdbTitleMatcher.MINIMUM_TOTAL_SCORE }
            .sortedByDescending { it.total }
    }

    /**
     * iOS `TMDBService.lookup`: ricerca di precisione di un titolo del provider. `null` se non c'è una
     * corrispondenza abbastanza sicura (mai più il primo risultato "a caso"). I "nessun risultato" sono
     * memorizzati, gli errori di rete no.
     */
    suspend fun lookup(title: String, isSeries: Boolean, apiKey: String, year: String? = null): MetadataResult? {
        if (apiKey.isBlank() || title.isBlank()) return null
        val key = TmdbTitleMatcher.cacheKey(isSeries, title, year)
        lookupCache[key]?.let { return it }
        if (key in noResultCache) return null
        val ranked = try {
            rankedCandidates(title, isSeries, year, apiKey)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return null
        }
        val best = ranked.firstOrNull()?.result
        if (best == null) noResultCache += key else lookupCache[key] = best
        return best
    }

    /**
     * iOS `TMDBService.fullDetails` (schede dettaglio). Ordine di affidabilità:
     * 1. id TMDB dichiarato dal pannello Xtream, verificato (titolo o cast in comune);
     * 2. ricerca per titolo + anno con punteggio;
     * 3. spareggio fra candidati vicini (≤ 0.15) tramite il cast dichiarato dal provider.
     * Dettaglio completo (`credits,images,external_ids`) oppure `null`.
     */
    suspend fun fullDetails(
        title: String,
        isSeries: Boolean,
        apiKey: String,
        year: String? = null,
        tmdbId: String? = null,
        castNames: List<String> = emptyList(),
    ): MetadataResult? {
        if (apiKey.isBlank()) return null
        val providerId = tmdbId?.trim()?.takeIf { id -> id.isNotEmpty() && id != "0" && id.all { it.isDigit() } }
        val key = "full::${TmdbTitleMatcher.cacheKey(isSeries, title, year)}::${providerId ?: 0}"
        fullDetailsCache[key]?.let { return it }

        var providerIdDetails: MetadataResult? = null
        if (providerId != null) {
            val byId = safe { details(providerId, apiKey, isSeries) }
            if (byId != null) {
                if (TmdbTitleMatcher.isTrustworthy(byId, title, castNames)) {
                    fullDetailsCache[key] = byId
                    return byId
                }
                providerIdDetails = byId
            }
        }

        val ranked = try {
            rankedCandidates(title, isSeries, year, apiKey)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return providerIdDetails
        }
        val best = ranked.firstOrNull() ?: return providerIdDetails

        var chosen = best
        var chosenDetails: MetadataResult? = null
        val nearTies = ranked.filter { best.total - it.total <= 0.15 }.take(3)
        if (castNames.isNotEmpty() && nearTies.size > 1) {
            var bestOverlap = -1
            for (candidate in nearTies) {
                val candidateDetails = safe { details(candidate.result.id, apiKey, isSeries) } ?: continue
                val overlap = TmdbTitleMatcher.castOverlap(castNames, candidateDetails.cast)
                if (overlap > bestOverlap || (overlap == bestOverlap && candidate.total > chosen.total)) {
                    bestOverlap = overlap
                    chosen = candidate
                    chosenDetails = candidateDetails
                }
            }
        }
        val result = chosenDetails ?: safe { details(chosen.result.id, apiKey, isSeries) } ?: return providerIdDetails
        lookupCache[TmdbTitleMatcher.cacheKey(isSeries, title, year)] = chosen.result
        fullDetailsCache[key] = result
        return result
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? = try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        null
    }
    /** Episode metadata fallback mirroring SeriesEpisodesView.swift (TMDB fills missing provider synopsis/stills). */
    suspend fun seasonEpisodes(tvId: String, season: Int, apiKey: String): List<TmdbEpisodeResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || tvId.isBlank() || season < 0) return@withContext emptyList()
        val cacheKey = "$tvId::$season"
        seasonCache[cacheKey]?.let { return@withContext it }
        var episodes = fetchSeason(tvId, season, apiKey, "it-IT")
        // iOS: TMDB lascia spesso vuota la trama in it-IT; le mancanti si completano con la versione inglese.
        if (episodes.any { it.overview.isNullOrBlank() }) {
            val english = runCatching { fetchSeason(tvId, season, apiKey, "en-US") }.getOrNull()?.associateBy { it.episodeNumber }.orEmpty()
            if (english.isNotEmpty()) episodes = episodes.map { episode ->
                val fallback = english[episode.episodeNumber]
                if (!episode.overview.isNullOrBlank() || fallback?.overview.isNullOrBlank()) episode
                else episode.copy(
                    overview = fallback?.overview,
                    stillUrl = episode.stillUrl ?: fallback?.stillUrl,
                    airDate = episode.airDate ?: fallback?.airDate
                )
            }
        }
        seasonCache[cacheKey] = episodes
        episodes
    }

    private suspend fun fetchSeason(tvId: String, season: Int, apiKey: String, language: String): List<TmdbEpisodeResult> {
        val root = api.getJson("https://api.themoviedb.org/3/tv/${enc(tvId)}/season/$season?api_key=${enc(apiKey)}&language=$language")
        return root.jsonObject["episodes"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject
            val number = obj["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            TmdbEpisodeResult(
                seasonNumber = obj["season_number"]?.jsonPrimitive?.intOrNull ?: season,
                episodeNumber = number,
                title = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                overview = obj["overview"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                stillUrl = obj["still_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" },
                airDate = obj["air_date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                runtimeMinutes = obj["runtime"]?.jsonPrimitive?.intOrNull
            )
        } ?: emptyList()
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
            // 15 nomi: servono anche allo spareggio per cast (iOS `castOverlap` usa i primi 15); in scheda se ne mostrano 12.
            cast = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(15)?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            castMembers = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(12)?.mapNotNull { element ->
                val person = element.jsonObject
                val name = person["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                MetadataCastMember(
                    name = name,
                    character = person["character"]?.jsonPrimitive?.contentOrNull,
                    profileUrl = person["profile_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" }
                )
            } ?: emptyList(),
            externalId = (this["external_ids"]?.jsonObject?.get("imdb_id") ?: this["imdb_id"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            // iOS `runtimeMinutes`: `runtime` (film) oppure la prima durata media per episodio (serie).
            runtimeMinutes = this["runtime"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                ?: this["episode_run_time"]?.jsonArray?.firstNotNullOfOrNull { it.jsonPrimitive.intOrNull?.takeIf { v -> v > 0 } },
            voteCount = this["vote_count"]?.jsonPrimitive?.intOrNull,
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
    private val cache = java.util.concurrent.ConcurrentHashMap<String, OmdbRatings>()

    /**
     * iOS `OMDbService.ratings(imdbId:)`: voto IMDb, Rotten Tomatoes (critica) e Metacritic per un IMDb id
     * (da TMDB `external_ids`). `null` senza chiave, se OMDb risponde `Response: False` o in caso di errore.
     */
    suspend fun ratings(imdbId: String, apiKey: String): OmdbRatings? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || imdbId.isBlank()) return@withContext null
        cache[imdbId]?.let { return@withContext it }
        val o = api.getJson("https://www.omdbapi.com/?i=${enc(imdbId)}&apikey=${enc(apiKey)}").jsonObject
        if (o["Response"]?.jsonPrimitive?.contentOrNull?.lowercase() != "true") return@withContext null
        val list = o["Ratings"]?.jsonArray?.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val source = r["Source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            source to r["Value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.orEmpty()
        val imdb = o["imdbRating"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }?.toDoubleOrNull()
        val rt = list.firstOrNull { it.first == "Rotten Tomatoes" }?.second?.replace("%", "")?.trim()?.toIntOrNull()
        val metacritic = o["Metascore"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }?.toIntOrNull()
            ?: list.firstOrNull { it.first == "Metacritic" }?.second?.substringBefore('/')?.trim()?.toIntOrNull()
        OmdbRatings(imdb, rt, metacritic).also { cache[imdbId] = it }
    }

    suspend fun lookup(title: String, apiKey: String, imdbId: String? = null): Ratings? = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext null; val o = api.getJson("https://www.omdbapi.com/?apikey=${enc(apiKey)}&" + (if (!imdbId.isNullOrBlank()) "i=${enc(imdbId)}" else "t=${enc(title)}")).jsonObject; Ratings(o["imdbRating"]?.jsonPrimitive?.doubleOrNull, o["Ratings"]?.jsonArray?.firstOrNull { it.jsonObject["Source"]?.jsonPrimitive?.contentOrNull == "Rotten Tomatoes" }?.jsonObject?.get("Value")?.jsonPrimitive?.contentOrNull, o["Metascore"]?.jsonPrimitive?.intOrNull) }
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}

class OpenSubtitlesService(private val api: NetworkApi) {
    /** iOS `OpenSubtitlesService.search(query:language:)`: `query` + `languages` (predefinita "it"), header `Api-Key`. */
    suspend fun search(query: String, apiKey: String, language: String = "it"): List<SubtitleResult> = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext emptyList(); val root = api.getJson("https://api.opensubtitles.com/api/v1/subtitles?query=${URLEncoder.encode(query, "UTF-8")}&languages=${URLEncoder.encode(language.ifBlank { "it" }, "UTF-8")}", mapOf("Api-Key" to apiKey, "Accept" to "application/json")); root.jsonObject["data"]?.jsonArray?.mapNotNull { el -> val o = el.jsonObject; val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null; SubtitleResult(id, o["attributes"]?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull.orEmpty(), o["attributes"]?.jsonObject?.get("release")?.jsonPrimitive?.contentOrNull.orEmpty()) } ?: emptyList() }
}

class TraktService(private val api: NetworkApi) {
    /**
     * iOS `TraktService.ratings(imdbId:isSeries:)`: voto medio della community da `/movies|shows/{imdb}/ratings`.
     * Pubblico: basta il Client ID Trakt nell'header, nessun login. (Prima Android usava lo `score` di
     * `/search/movie`, che è la pertinenza della ricerca e non un voto, e solo per i film.)
     */
    suspend fun ratings(imdbId: String, isSeries: Boolean, clientId: String): TraktRatings? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || imdbId.isBlank()) return@withContext null
        val path = if (isSeries) "shows" else "movies"
        val o = api.getJson(
            "https://api.trakt.tv/$path/${URLEncoder.encode(imdbId, "UTF-8")}/ratings",
            mapOf("trakt-api-key" to clientId, "trakt-api-version" to "2", "Content-Type" to "application/json")
        ).jsonObject
        val rating = o["rating"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
        TraktRatings(rating, o["votes"]?.jsonPrimitive?.intOrNull ?: 0)
    }
    /**
     * iOS `TraktService.requestDeviceCode`: POST `https://api.trakt.tv/oauth/device/code` con `{"client_id"}`.
     * (Prima Android usava `trakt.tv/oauth/device/code` e ignorava `interval`.)
     */
    suspend fun deviceCode(clientId: String, clientSecret: String): TraktDeviceCode? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || clientSecret.isBlank()) return@withContext null
        val o = api.postJson(
            "$TRAKT_API/oauth/device/code",
            JsonStore.json.encodeToString(buildJsonObject { put("client_id", clientId) }),
            mapOf("Content-Type" to "application/json")
        ).jsonObject
        TraktDeviceCode(
            o["device_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["user_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["verification_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["expires_in"]?.jsonPrimitive?.intOrNull ?: 600,
            o["interval"]?.jsonPrimitive?.intOrNull ?: 5
        )
    }

    /**
     * iOS `TraktService.pollForToken`: POST `https://api.trakt.tv/oauth/device/token` con code, client_id e
     * client_secret. `null` finché l'utente non ha autorizzato (Trakt risponde 400 "pending").
     */
    suspend fun pollDevice(clientId: String, clientSecret: String, deviceCode: String): TraktAccount? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || clientSecret.isBlank() || deviceCode.isBlank()) return@withContext null
        val o = api.postJson(
            "$TRAKT_API/oauth/device/token",
            JsonStore.json.encodeToString(buildJsonObject { put("code", deviceCode); put("client_id", clientId); put("client_secret", clientSecret) }),
            mapOf("Content-Type" to "application/json")
        ).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        TraktAccount(
            accessToken = access,
            refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            username = "",
            expiresInSec = o["expires_in"]?.jsonPrimitive?.intOrNull,
            connectedAt = System.currentTimeMillis()
        )
    }
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
    /** Release / first-air year from TMDB, used to match trending titles to the playlist accurately. */
    val year: String? = null,
    /** Locandina verticale senza testo (Home "In evidenza"); null se TMDB non la ha. */
    val textlessPosterUrl: String? = null,
    /** Immagine orizzontale senza testo (Home "In evidenza" su schermi larghi). */
    val textlessBackdropUrl: String? = null,
    /** Durata in minuti (film: `runtime`; serie: prima `episode_run_time`). */
    val runtimeMinutes: Int? = null,
    /** Numero di voti TMDB: solo spareggio fra omonimi nel matching di precisione. */
    val voteCount: Int? = null
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
/** iOS `OMDbRatingsResult` già interpretato: IMDb su base 10, Rotten Tomatoes in %, Metacritic su base 100. */
data class OmdbRatings(val imdbScore: Double?, val rottenTomatoesPercent: Int?, val metacriticScore: Int?)
/** iOS `TraktRatings`: voto medio community (base 10) e numero di voti. */
data class TraktRatings(val rating: Double, val votes: Int)
data class SubtitleResult(val id: String, val language: String, val release: String)
data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int, val intervalSec: Int = 5)

private const val TRAKT_API = "https://api.trakt.tv"
