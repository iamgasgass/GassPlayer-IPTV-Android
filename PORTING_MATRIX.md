# GassPlayer iOS → Android / Android TV — Porting Matrix

Questo progetto deriva dallo ZIP iOS allegato e conserva il comportamento applicativo come riferimento, sostituendo le API Apple con componenti Android nativi.

## Mappatura funzionale

| Area iOS | Android / Android TV | Stato |
|---|---|---|
| SwiftUI App/Views | Jetpack Compose + Compose for TV | Portata nativamente |
| Modelli Xtream / VOD / Series / EPG / M3U | `data/Models.kt` + repository dedicati | Portata |
| Xtream login + validazione | `XtreamRepository` + dialog Verifica e salva | Portata |
| M3U/M3U8 parser | `M3UParser` | Portata |
| Multiple sources, enable/disable/pin/sort/rename/duplicate/delete/verify | `SourceRepository` + `SourcesScreen` | Portata |
| Cache per source + retry | `CatalogRepository` + `PlaybackController` | Portata |
| Global search + history | `SearchHistoryRepository` + `SearchScreen` | Portata |
| Favorites + continue watching + resume | `FavoritesRepository` / `WatchHistoryRepository` | Portata |
| Home customization hide/reorder | `HomeCustomizationScreen` + `AppSettings` | Portata |
| Unified playlists | `MergedPlaylistRepository` + schermata dedicata | Portata |
| EPG XMLTV + M3U EPG + cache | `EpgRepository` + `ExternalEpgRepository` | Portata |
| Catch-up / archive URL | EPG program model + player URL | Portata |
| EPG reminders | `ReminderScheduler` / AlarmManager | Portata nativa |
| TMDB / OMDb / Trakt / OpenSubtitles | `MetadataServices.kt` | Portata |
| Trakt device flow + account token | `TraktScreen` + secure prefs | Portata |
| Media player HLS / DASH / progressive | AndroidX Media3 1.11.1 | Portata nativa |
| Quality / tracks / speed / loop / PiP | Media3 track selector + PlayerView + PiP | Portata |
| Adaptive playback + stream fallback | `StreamUrlCandidates` + UA ladder + retry | Portata |
| HTTP cache / buffer settings | Media3 + `AppSettings` | Portata |
| Downloads Wi‑Fi/background | WorkManager | Portata nativa |
| Parental PIN / lock content | SHA-256 + `ParentalRepository` | Portata |
| iCloud KVS / backup | Android Auto Backup + JSON export/import | Adattato a piattaforma |
| Debug / diagnostics | `DiagnosticsService` | Portata |
| Custom User-Agent | `NetworkApi` + `PlaybackController` | Portata |
| DNS preference | Setting + VPN configuration | Portata |
| IKEv2 personal VPN | `VpnController` + Android `VpnManager` | Portata nativa |
| WireGuard | Official WireGuard Android tunnel dependency | Portata nativa |
| OpenVPN | Config model/provider discovery | Limitazione ereditata dalla sorgente iOS: l'engine iOS non era integrato |
| AirPlay | External player / Android routing | Adattamento piattaforma |
| Chromecast UI point | Android TV / external playback | Adattamento piattaforma; il sorgente iOS non includeva Cast SDK completo |
| Plex / Jellyfin / Emby model slots | Source model retained | Il sorgente iOS non aveva backend dedicati, quindi non viene inventata un'integrazione |
| Liquid Glass SwiftUI | Material3 / TV Material | Adattamento Android nativo, non simulazione pixel-level |
| PacketTunnel / NetworkExtension | Android VpnService / VpnManager | Riscrittura nativa |

## File Apple coperti per area

**Models/** → `Models.kt` più modelli EPG/VPN/backup nelle data classes Android.

**Services/** → repository Android separati per catalogo, sorgenti, EPG, metadata, download, backup, sicurezza, reminder, diagnosi, playback, VPN e stato utente.

**Views/** → schermate Compose consolidate senza perdere i flussi: Home, live, Film, Serie, dettaglio, EPG, sorgenti, ricerca, impostazioni, parental, VPN, download, backup, diagnostica, Trakt e personalizzazione Home.

**PacketTunnel/** → `VpnController.kt`; le credenziali/configurazioni sensibili sono protette da Android Keystore.

**Resources/** → icona originale iOS riutilizzata nell'app Android, banner TV e risorse Android; entitlements/plist Apple sostituiti da Manifest/backup/provider Android.

**Tests/** → test unitari Android per parser M3U e URL Xtream; la matrice iOS di servizi puri è stata trasferita nelle logiche repository/utilità Android dove utile.

## Cosa non viene falsamente dichiarato “identico”

Un'app SwiftUI/iOS non può essere convertita letteralmente a livello di API, rendering o framework. Il port quindi mantiene **funzioni e flussi**, ma usa le primitive native Android equivalenti. Le superfici Apple-specifiche (Liquid Glass, NetworkExtension PacketTunnel, AirPlay, iCloud KVS, VideoToolbox) sono state tradotte secondo il modello Android/Android TV invece di essere emulate con codice fittizio.

## Stato di verifica

È stato eseguito un audit statico del sorgente e un passaggio del parser Kotlin locale per intercettare errori sintattici puri. La build Android completa non è stata eseguita in questo sandbox: non è disponibile un Android SDK/Gradle distribution locale compatibile e la rete del sandbox non consente il download delle toolchain. Il workflow GitHub Actions incluso esegue invece `testDebugUnitTest` e `assembleDebug` su una macchina Android CI reale.


## Kotlin/Media3 compile fixes (FIXED10)
- Fixed nullable `SourceSnapshot` fallback in `CatalogRepository`.
- Updated Media3 `ExoPlayer.Builder` usage to `setLoadControl(...)`.
- Fixed Trakt device-code expiration field usage (`expiresInSec`).
- Added Kotlin Serialization `jsonObject` extension import for VPN discovery.
