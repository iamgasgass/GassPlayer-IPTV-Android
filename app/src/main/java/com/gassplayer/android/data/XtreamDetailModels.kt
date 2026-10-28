package com.gassplayer.android.data

/**
 * Port di `XtreamVODInfo` (Models/XtreamVODModels.swift): il blocco `info` di `get_vod_info`.
 * La maggior parte dei pannelli restituisce già trama, cast, genere e backdrop (spesso presi da TMDB
 * lato server): sono la base immediata della scheda, prima/in assenza dell'arricchimento TMDB.
 */
data class XtreamVodInfo(
    val movieImage: String? = null,
    val backdropPaths: List<String> = emptyList(),
    val youtubeTrailer: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val durationSecs: Int? = null,
    val duration: String? = null,
    val tmdbId: String? = null,
) {
    /** Il campo "cast" del provider è una stringa unica separata da virgole: solo nomi, niente foto. */
    val castNames: List<String> get() = splitNames(cast)
    val genreNames: List<String> get() = splitNames(genre)

    /** Primo backdrop, altrimenti `movie_image` (come iOS). */
    val backdropUrl: String? get() = backdropPaths.firstOrNull() ?: movieImage

    val year: String? get() = yearOf(releaseDate)

    /** iOS usa `duration_secs`; in sua assenza si legge anche "01:45:00" (alcuni pannelli danno solo quello). */
    val runtimeMinutes: Int?
        get() = durationSecs?.takeIf { it > 0 }?.div(60)
            ?: duration?.split(":")?.takeIf { it.size in 2..3 }?.fold(0) { acc, part -> acc * 60 + (part.trim().toIntOrNull() ?: 0) }
                ?.takeIf { it > 0 }?.div(60)

    /** Voto Xtream (spesso su base 10, a volte con la virgola decimale). */
    val ratingValue: Double? get() = parseRating(rating)

    val tmdbIdValue: Int? get() = tmdbId?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}

/** Port di `XtreamSeriesInfo.SeriesDetails`: il blocco `info` di `get_series_info`. */
data class XtreamSeriesDetails(
    val name: String? = null,
    val cover: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val backdropPaths: List<String> = emptyList(),
    val youtubeTrailer: String? = null,
    /** `tmdb` o `tmdb_id`, a seconda del pannello: il collegamento più affidabile alla serie giusta su TMDB. */
    val tmdbId: String? = null,
)

/** Port di `XtreamSeriesInfo.Season`: titolo/overview/copertina per stagione, quando il provider li fornisce. */
data class XtreamSeasonInfo(
    val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val cover: String? = null,
    val airDate: String? = null,
    val episodeCount: Int? = null,
)

/** Dettagli + stagioni di `get_series_info` (gli episodi restano su [XtreamRepository.seriesEpisodes]). */
data class XtreamSeriesInfo(
    val details: XtreamSeriesDetails?,
    val seasons: List<XtreamSeasonInfo> = emptyList(),
) {
    val castNames: List<String> get() = splitNames(details?.cast)
    val genreNames: List<String> get() = splitNames(details?.genre)
    val backdropUrl: String? get() = details?.backdropPaths?.firstOrNull() ?: details?.cover
    val year: String? get() = yearOf(details?.releaseDate)
    val ratingValue: Double? get() = parseRating(details?.rating)
    val tmdbIdValue: Int? get() = details?.tmdbId?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    fun seasonInfo(season: Int): XtreamSeasonInfo? = seasons.firstOrNull { it.seasonNumber == season }
}

internal fun splitNames(raw: String?): List<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

internal fun yearOf(date: String?): String? =
    date?.trim()?.takeIf { it.length >= 4 }?.take(4)?.takeIf { y -> y.all { it.isDigit() } }

internal fun parseRating(raw: String?): Double? =
    raw?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
