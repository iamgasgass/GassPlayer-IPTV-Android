package com.gassplayer.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

/**
 * Port di Models/MediaDetail.swift (`MediaCastMember`, `MediaRatings`, `MediaDetail`, `MediaDetailSeed`,
 * `MediaDetailLoader`): la scheda dettaglio riceve ESATTAMENTE le stesse fonti dell'app iOS.
 *
 * Ordine (identico a iOS):
 * 1. base Xtream (`get_vod_info` / `get_series_info`): trama, backdrop, generi, cast (solo nomi), anno, durata,
 *    voto del pannello come riserva per "TMDB";
 * 2. TMDB `fullDetails` (id del pannello verificato → ricerca di precisione → spareggio per cast): trama,
 *    backdrop, logo, poster, generi, anno, durata, top 12 cast con foto e ruolo, voto, id TMDB e IMDb;
 * 3. con l'IMDb id: OMDb (IMDb, Rotten Tomatoes, Metacritic) e Trakt (voto community) — qui in parallelo.
 * Ogni fonte è facoltativa: la mancanza di una non blocca le altre.
 */
data class MediaCastMember(
    val id: String,
    val name: String,
    val role: String? = null,
    val photoUrl: String? = null,
)

data class MediaRatings(
    val tmdbPercent: Int? = null,
    val traktPercent: Int? = null,
    val imdbScore: Double? = null,
    val rottenTomatoesPercent: Int? = null,
    val metacriticScore: Int? = null,
) {
    val isEmpty: Boolean
        get() = tmdbPercent == null && traktPercent == null && imdbScore == null && rottenTomatoesPercent == null && metacriticScore == null

    /** Formato usato dai componenti di valutazione Android (`MediaDetailRatings`). */
    fun toRatings(): Ratings = Ratings(imdbScore, rottenTomatoesPercent?.let { "$it%" }, metacriticScore)
}

data class MediaDetail(
    val overview: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val runtimeMinutes: Int? = null,
    val cast: List<MediaCastMember> = emptyList(),
    val ratings: MediaRatings = MediaRatings(),
    val imdbId: String? = null,
    /** Id TMDB del titolo, quando trovato: serve per i dettagli per-episodio delle serie. */
    val tmdbId: String? = null,
    /** Risultato TMDB grezzo (titolo/originale, immagini senza testo...), `null` se TMDB non ha risposto. */
    val tmdb: MetadataResult? = null,
) {
    /** iOS `runtimeLabel`: "1h 45min", "2h", "50min". */
    val runtimeLabel: String?
        get() {
            val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return null
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h > 0 && m > 0 -> "${h}h ${m}min"
                h > 0 -> "${h}h"
                else -> "${m}min"
            }
        }

    /**
     * Adattamento al modello già usato dalle schede Compose ([MetadataResult]). `previous` (es. i dati della
     * card "di tendenza" mostrati subito) riempie solo i campi che la scheda completa non ha.
     */
    fun toMetadataResult(fallbackTitle: String, previous: MetadataResult? = null): MetadataResult {
        val base = tmdb ?: previous
        return MetadataResult(
            id = tmdbId ?: previous?.id.orEmpty(),
            title = tmdb?.title?.takeIf { it.isNotBlank() } ?: previous?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            rating = ratings.tmdbPercent?.let { it / 10.0 },
            posterUrl = posterUrl ?: previous?.posterUrl,
            backdropUrl = backdropUrl ?: previous?.backdropUrl,
            overview = overview ?: previous?.overview,
            genres = genres.ifEmpty { previous?.genres.orEmpty() },
            cast = cast.map { it.name },
            castMembers = cast.map { MetadataCastMember(name = it.name, character = it.role, profileUrl = it.photoUrl) },
            externalId = imdbId ?: previous?.externalId,
            originalTitle = base?.originalTitle,
            logoUrl = logoUrl ?: previous?.logoUrl,
            year = year ?: previous?.year,
            textlessPosterUrl = base?.textlessPosterUrl,
            textlessBackdropUrl = base?.textlessBackdropUrl,
            runtimeMinutes = runtimeMinutes,
            voteCount = base?.voteCount,
        )
    }
}

/** Punto di partenza dal catalogo Xtream, usato come base e come riserva se TMDB/OMDb/Trakt non rispondono. */
data class MediaDetailSeed(
    val title: String,
    val isSeries: Boolean,
    val overview: String? = null,
    val backdropUrl: String? = null,
    val genreNames: List<String> = emptyList(),
    val castNames: List<String> = emptyList(),
    val year: String? = null,
    val runtimeMinutes: Int? = null,
    val xtreamRating: Double? = null,
    /** `tmdb_id` dichiarato dal pannello Xtream: il collegamento più affidabile al titolo giusto. */
    val tmdbId: String? = null,
)

class MediaDetailLoader(
    private val tmdb: TmdbService,
    private val omdb: OmdbService,
    private val trakt: TraktService,
) {
    suspend fun load(seed: MediaDetailSeed, tmdbApiKey: String, omdbApiKey: String, traktClientId: String): MediaDetail {
        var detail = MediaDetail(
            overview = seed.overview?.trim()?.takeIf { it.isNotEmpty() },
            backdropUrl = seed.backdropUrl?.takeIf { it.isNotBlank() },
            genres = seed.genreNames,
            year = seed.year,
            runtimeMinutes = seed.runtimeMinutes?.takeIf { it > 0 },
            cast = seed.castNames.map { MediaCastMember(id = it, name = it) },
            // Xtream esprime spesso il voto su base 10: solo riserva, sovrascritto se TMDB risponde.
            ratings = MediaRatings(tmdbPercent = seed.xtreamRating?.takeIf { it > 0 }?.let { (it * 10).roundToInt() }),
        )

        if (tmdbApiKey.isBlank()) return detail

        val found = tmdb.fullDetails(
            title = seed.title,
            isSeries = seed.isSeries,
            apiKey = tmdbApiKey,
            year = seed.year,
            tmdbId = seed.tmdbId,
            castNames = seed.castNames,
        ) ?: return detail

        val tmdbCast = found.castMembers.take(12).mapIndexed { index, member ->
            MediaCastMember(id = "tmdb-$index-${member.name}", name = member.name, role = member.character, photoUrl = member.profileUrl)
        }
        detail = detail.copy(
            overview = found.overview?.trim()?.takeIf { it.isNotEmpty() } ?: detail.overview,
            backdropUrl = found.backdropUrl ?: detail.backdropUrl,
            logoUrl = found.logoUrl,
            posterUrl = found.posterUrl,
            genres = found.genres.ifEmpty { detail.genres },
            year = found.year ?: detail.year,
            runtimeMinutes = found.runtimeMinutes?.takeIf { it > 0 } ?: detail.runtimeMinutes,
            cast = tmdbCast.ifEmpty { detail.cast },
            ratings = detail.ratings.copy(
                tmdbPercent = found.rating?.takeIf { it > 0 }?.let { (it * 10).roundToInt() } ?: detail.ratings.tmdbPercent
            ),
            tmdbId = found.id,
            imdbId = found.externalId?.takeIf { it.isNotBlank() },
            tmdb = found,
        )

        val imdbId = detail.imdbId ?: return detail

        return coroutineScope {
            val omdbD = async { if (omdbApiKey.isBlank()) null else safe { omdb.ratings(imdbId, omdbApiKey) } }
            val traktD = async { if (traktClientId.isBlank()) null else safe { trakt.ratings(imdbId, seed.isSeries, traktClientId.trim()) } }
            val omdbRatings = omdbD.await()
            val traktRatings = traktD.await()
            detail.copy(
                ratings = detail.ratings.copy(
                    imdbScore = omdbRatings?.imdbScore ?: detail.ratings.imdbScore,
                    rottenTomatoesPercent = omdbRatings?.rottenTomatoesPercent ?: detail.ratings.rottenTomatoesPercent,
                    metacriticScore = omdbRatings?.metacriticScore ?: detail.ratings.metacriticScore,
                    traktPercent = traktRatings?.rating?.let { (it * 10).roundToInt() } ?: detail.ratings.traktPercent,
                )
            )
        }
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? = try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        null
    }
}
