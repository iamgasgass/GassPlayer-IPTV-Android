/*
 * This file is part of GassTV.
 * Copyright (C) 2026 The GassTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.gasstv.core

import android.content.Context
import app.gasstv.data.db.GassTvDatabase
import app.gasstv.data.remote.StalkerApi
import app.gasstv.data.remote.StremioClient
import app.gasstv.data.remote.XtreamApi
import app.gasstv.data.repo.CatalogRepository
import app.gasstv.data.repo.EpgRepository
import app.gasstv.data.repo.RecordingRepository
import app.gasstv.data.repo.ReminderRepository
import app.gasstv.data.repo.SourceRepository
import app.gasstv.recording.RecordingEngine
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency container.
 *
 * Hilt would be the conventional choice. It is deliberately not used: it adds an annotation
 * processor, a build-time graph, and a class of error message that is genuinely hard to read,
 * in exchange for wiring roughly a dozen objects. For a project whose main hope is drive-by
 * contributions from people fixing their own bugs, "you can read the whole graph in one file"
 * is worth more than the ceremony.
 */
object ServiceLocator {

    @Volatile private var instance: Graph? = null

    fun get(context: Context): Graph =
        instance ?: synchronized(this) {
            instance ?: Graph(context.applicationContext).also { instance = it }
        }

    class Graph(context: Context) {
        private val appContext = context.applicationContext

        val settings: AppSettings by lazy { AppSettings.get(appContext) }

        val database: GassTvDatabase by lazy { GassTvDatabase.build(context) }

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Generous: catalogue endpoints on a busy panel can take a long time to
                // produce 40,000 rows, and timing out mid-list is worse than waiting.
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }

        /**
         * A separate client for the player's data source. A live stream is an effectively
         * infinite HTTP response body, so the catalogue client's five-minute [callTimeout] would
         * guillotine every live channel at that mark — here there is no overall call timeout, only
         * a read timeout that catches a genuinely dead connection. Cross-protocol redirects are
         * followed explicitly because IPTV stream URLs routinely bounce http→https onto a CDN, and
         * a client that stops at the redirect surfaces as "lost connection to server".
         */
        val streamingHttpClient: OkHttpClient by lazy {
            httpClient.newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        val xtreamApi: XtreamApi by lazy { XtreamApi(httpClient) }

        /** Stalker / Ministra portal client (MAC handshake + create_link). */
        val stalkerApi: StalkerApi by lazy { StalkerApi(httpClient) }

        /** Neutral Stremio add-on protocol client. Talks only to user-added manifest URLs. */
        val stremioClient: StremioClient by lazy { StremioClient(httpClient) }

        val sourceRepository: SourceRepository by lazy {
            SourceRepository(database.sources(), xtreamApi, stalkerApi)
        }

        val catalogRepository: CatalogRepository by lazy {
            CatalogRepository(
                sourceDao = database.sources(),
                channelDao = database.channels(),
                categoryDao = database.categories(),
                movieDao = database.movies(),
                seriesDao = database.series(),
                episodeDao = database.episodes(),
                positionDao = database.positions(),
                api = xtreamApi,
                stalkerApi = stalkerApi,
                http = httpClient,
                settings = settings,
            )
        }

        val epgRepository: EpgRepository by lazy {
            EpgRepository(
                programmeDao = database.programmes(),
                feedDao = database.epgFeeds(),
                aliasDao = database.epgAliases(),
                channelDao = database.channels(),
                sourceDao = database.sources(),
                api = xtreamApi,
                http = httpClient,
            )
        }

        val playbackPositions get() = database.positions()

        val profiles get() = database.profiles()

        val recordingRepository: RecordingRepository by lazy {
            RecordingRepository(database.recordings(), database.seriesRules())
        }

        val reminderRepository: ReminderRepository by lazy {
            ReminderRepository(database.reminders())
        }

        val recordingEngine: RecordingEngine by lazy {
            RecordingEngine(
                appContext, recordingRepository, sourceRepository, settings,
                database.channels(), epgRepository,
            )
        }
    }
}
