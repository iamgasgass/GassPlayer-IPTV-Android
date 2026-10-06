# Matrice migrazione iOS → Android

| Area iOS | Android |
|---|---|
| SwiftUI App/ContentView | MainActivity, GassPlayerApp, Navigation Compose |
| HomeView | HomeScreen |
| ChannelGridView/ChannelsView | LiveScreen, ChannelCard |
| PlayerView/KSPlaybackController | PlayerScreen, Media3 ExoPlayer, PlaybackService |
| Sources/SourceManage/SourceManager | SourcesScreen, AppStore |
| XtreamAPIService/XtreamCatalogStore | XtreamClient, AppStore cache |
| M3UPlaylistService/Store | M3uParser, AppStore |
| EPGGrid/EPGService/M3UEPGService | EpgScreen, XmlTvParser, shortEpg API |
| MovieDetail/SeriesEpisodes | VodScreen, SeriesScreen, SeriesEpisodesScreen |
| GlobalSearchService/View | SearchScreen |
| Favorites/RecentlyWatched/PlaybackPosition | DataStore + WatchProgress |
| TMDB/OMDb | MetadataClients |
| ThemeManager/LiquidGlass | GassTheme, GlassCard |
| SettingsView | SettingsScreen |
| Picture in Picture | Android PiP |
| Local notifications | WorkManager/NotificationManager extension point |
| iCloud sync | Richiede backend Android esterno |
| AirPlay | Google Cast richiede registrazione Cast SDK |
| NetworkExtension/WireGuardKit | Richiede VpnService e tunnel Android dedicato |
