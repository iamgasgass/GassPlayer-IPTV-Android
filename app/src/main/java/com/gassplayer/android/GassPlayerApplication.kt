package com.gassplayer.android

import android.app.Application
import com.gassplayer.android.data.*
import com.gassplayer.android.media.PlaybackController
import com.gassplayer.android.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GassPlayerApplication : Application() {
    // Startup/maintenance work is best-effort. A scheduler or storage exception must be logged,
    // not delivered to Android's uncaught-exception handler and turn into an app-start crash.
    private val startupExceptionHandler = CoroutineExceptionHandler { _, error ->
        android.util.Log.e("GassPlayerStartup", "Background startup task failed", error)
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + startupExceptionHandler)
    @Volatile private var latestSettings = AppSettings()

    lateinit var prefs: AppPreferences
    lateinit var network: NetworkApi
    lateinit var xtream: XtreamRepository
    lateinit var sources: SourceRepository
    lateinit var catalog: CatalogRepository
    lateinit var favorites: FavoritesRepository
    lateinit var watch: WatchHistoryRepository
    lateinit var search: SearchHistoryRepository
    lateinit var parental: ParentalRepository
    lateinit var epg: EpgRepository
    lateinit var backup: BackupService
    lateinit var diagnostics: DiagnosticsService
    // Rarely used subsystems stay out of the process' cold-start path.
    val downloads: DownloadRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DownloadRepository(this) }
    val vpn: VpnController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VpnController(this, network) }
    lateinit var tmdb: TmdbService
    lateinit var omdb: OmdbService
    lateinit var trakt: TraktService
    lateinit var subtitles: OpenSubtitlesService
    val cloud: AndroidCloudSync by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidCloudSync(this, prefs) }
    val reminders: ReminderScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ReminderScheduler(this) }
    // Media3 track selection and profile storage are not needed to render the home screen.
    // Initialize them only when playback is first requested, avoiding heavyweight player setup
    // during Application.onCreate().
    private val playbackDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackController(this, diagnostics).also { it.setSettings(latestSettings) }
    }
    val playback: PlaybackController get() = playbackDelegate.value
    val mergedPlaylists: MergedPlaylistRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MergedPlaylistRepository(prefs) }
    val externalEpg: ExternalEpgRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ExternalEpgRepository(prefs) }

    override fun onCreate() {
        super.onCreate()
        runCatching { ensureNotificationChannel(this) }
            .onFailure { android.util.Log.w("GassPlayerStartup", "Notification channel unavailable", it) }
        prefs = AppPreferences(this)
        network = NetworkApi()
        xtream = XtreamRepository(network)
        diagnostics = DiagnosticsService(this)
        sources = SourceRepository(this, prefs, xtream, network)
        catalog = CatalogRepository(this, prefs, xtream, network)
        favorites = FavoritesRepository(prefs)
        watch = WatchHistoryRepository(prefs)
        search = SearchHistoryRepository(prefs)
        parental = ParentalRepository(prefs)
        epg = EpgRepository(this, network)
        backup = BackupService(this, prefs)
        tmdb = TmdbService(network)
        omdb = OmdbService(network)
        trakt = TraktService(network)
        subtitles = OpenSubtitlesService(network)
        com.gassplayer.android.data.EmptyCategoryStore.init(this)

        appScope.launch {
            prefs.settingsFlow.collectLatest { settings ->
                latestSettings = settings
                network.userAgent = settings.customUserAgent.trim().ifBlank { com.gassplayer.android.media.StreamUserAgents.DEFAULT_VLC }
                // WorkManager initialization and periodic scheduling are optional for the first
                // frame. Isolate failures so a device-specific scheduler issue cannot kill launch.
                try {
                    CatalogRefreshScheduler.sync(this@GassPlayerApplication, prefs, settings)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    android.util.Log.w("GassPlayerStartup", "Catalog scheduler unavailable", error)
                    runCatching { diagnostics.log("WARN", "startup", "Catalog scheduler unavailable: ${error.message}") }
                }
                // Do not force the player to initialize just because its settings were loaded.
                updatePlaybackSettingsIfInitialized(settings)
            }
        }
    }

    /** Update the cached settings even before Media3 is needed; never create a player just for settings. */
    fun updatePlaybackSettingsIfInitialized(settings: AppSettings) {
        latestSettings = settings
        if (!playbackDelegate.isInitialized()) return
        try {
            playbackDelegate.value.setSettings(settings)
        } catch (error: Exception) {
            android.util.Log.w("GassPlayerStartup", "Player settings could not be applied", error)
            runCatching { diagnostics.log("WARN", "startup", "Player settings failed: ${error.message}") }
        }
    }
}
