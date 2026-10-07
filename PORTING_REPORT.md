# Porting report — GassPlayer iOS → Android / Android TV

## Sorgente analizzata

Sorgente: `GassPlayer-IPTV-main(3).zip`.
Il progetto iOS contiene 119 file complessivi, 103 file Swift, XcodeGen `project.yml`, test XCTest, un Packet Tunnel e servizi separati per catalogo, EPG, metadata, download, VPN, cache, preferenze e diagnostica.

## Architettura target

- `data/Models.kt`: contratto dati centrale.
- `data/AppPreferences.kt`: stato persistente DataStore.
- `data/XtreamRepository.kt`: API Xtream + URL builder.
- `data/M3UParser.kt`: parser M3U/M3U8 robusto.
- `data/CatalogRepository.kt`: cache per sorgente, refresh parallelo e merge.
- `data/EpgRepository.kt`: short/full fallback, XMLTV e catch-up.
- `data/MetadataServices.kt`: TMDB/OMDb/Trakt/OpenSubtitles.
- `data/NotificationAndDownloads.kt`: WorkManager background downloads.
- `data/ReminderScheduler.kt`: EPG reminders.
- `vpn/VpnController.kt`: IKEv2, WireGuard adapter e provider discovery.
- `media/PlaybackController.kt`: Media3/ExoPlayer, cache, buffer, track selection, PiP hooks.
- `ui/AppScreens.kt`: Home, Live, Film, Serie, EPG, Search, Sources, Settings, Backup, VPN, Parental, Diagnostics, Player.

## Decisioni di porting

1. Nessun wrapper web.
2. UI rifatta in Compose, con focus D-pad/telecomando.
3. Media playback rifatto con Media3, non con una traduzione di AVFoundation/KSPlayer.
4. Persistenza piccola in DataStore; cache/cataloghi su file JSON per mantenere l'isolamento per sorgente e la semplicità del modello iOS.
5. WorkManager per download persistenti invece di un `URLSession` background clone.
6. IKEv2 implementato con API VPN di piattaforma Android.
7. WireGuard agganciato alla libreria tunnel ufficiale Android.
8. OpenVPN lasciato esplicitamente come configurazione, perché anche la sorgente iOS ammetteva che il motore non fosse implementato.

## Ricerca tecnica utilizzata

- Media3 stabile 1.11.1.
- Compose for TV stabile: `tv-material` 1.1.0 e `tv-foundation` 1.0.0.
- Target API 36 per la distribuzione generale Android.
- IKEv2 tramite `VpnManager`/`Ikev2VpnProfile` API 30+.
- WireGuard Android tag stabile 1.0.20260315.
- WorkManager per lavori di lunga durata/download.
- DataStore per le preferenze.
- Media3/ExoPlayer per HLS/DASH e sessioni media.

## Verifica effettuata in sandbox

- Inventario completo dello ZIP sorgente.
- Analisi del `README.md` e dei servizi Swift principali.
- Controlli statici sui sorgenti Kotlin.
- Controllo di equilibrio sintattico eseguito tramite il compilatore Kotlin locale: gli errori residui dipendono dall'assenza dell'Android SDK/classpath AndroidX nel sandbox, non da una build Gradle eseguita.
- `./gradlew` non è stato eseguito fino ad APK perché il sandbox non contiene Android SDK/Gradle cache e non può scaricare le distribuzioni da Internet.

## Cose che richiedono test su device/SDK reale

- Build AGP/Gradle completa.
- Play/Pause, seek, track selection e sottotitoli su stream reali.
- PiP su Android TV compatibile.
- EPG/reminder su API diverse.
- IKEv2 su dispositivi con `FEATURE_IPSEC_TUNNELS`.
- WireGuard con chiavi/provider reali.
- Download su rete unmetered/metered.
- D-pad su più form factor TV.
- 16 KB page-size / ABI sulle immagini native delle dipendenze.
