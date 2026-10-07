package com.gassplayer.android

import android.app.Application
import com.gassplayer.android.data.*
import com.gassplayer.android.media.PlaybackController
import com.gassplayer.android.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GassPlayerApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var prefs: AppPreferences; lateinit var network: NetworkApi; lateinit var xtream: XtreamRepository; lateinit var sources: SourceRepository; lateinit var catalog: CatalogRepository; lateinit var favorites: FavoritesRepository; lateinit var watch: WatchHistoryRepository; lateinit var search: SearchHistoryRepository; lateinit var parental: ParentalRepository; lateinit var epg: EpgRepository; lateinit var backup: BackupService; lateinit var diagnostics: DiagnosticsService; lateinit var downloads: DownloadRepository; lateinit var vpn: VpnController; lateinit var tmdb: TmdbService; lateinit var omdb: OmdbService; lateinit var trakt: TraktService; lateinit var subtitles: OpenSubtitlesService; lateinit var cloud: AndroidCloudSync; lateinit var reminders: ReminderScheduler; lateinit var playback: PlaybackController; lateinit var mergedPlaylists: MergedPlaylistRepository; lateinit var externalEpg: ExternalEpgRepository
    override fun onCreate() {
        super.onCreate(); ensureNotificationChannel(this)
        prefs = AppPreferences(this); network = NetworkApi(); xtream = XtreamRepository(network); diagnostics = DiagnosticsService(this); sources = SourceRepository(this, prefs, xtream, network); catalog = CatalogRepository(this, prefs, xtream, network); favorites = FavoritesRepository(prefs); watch = WatchHistoryRepository(prefs); search = SearchHistoryRepository(prefs); parental = ParentalRepository(prefs); epg = EpgRepository(this, network); backup = BackupService(this, prefs); downloads = DownloadRepository(this); vpn = VpnController(this, network); tmdb = TmdbService(network); omdb = OmdbService(network); trakt = TraktService(network); subtitles = OpenSubtitlesService(network); cloud = AndroidCloudSync(this, prefs); reminders = ReminderScheduler(this); playback = PlaybackController(this, diagnostics); mergedPlaylists = MergedPlaylistRepository(prefs); externalEpg = ExternalEpgRepository(prefs)
        appScope.launch { prefs.settingsFlow.collectLatest { network.userAgent = it.customUserAgent.trim().ifBlank { "GassPlayer/Android/1.0" } } }
    }
}
