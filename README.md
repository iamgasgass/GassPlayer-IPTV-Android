# GassPlayer Android / Android TV

Port nativo del progetto iOS `GassPlayer-IPTV` allegato, ricostruito per Android phone/tablet e Android TV.

## Stack

- Kotlin 2.4.20 / JVM 17
- Android Gradle Plugin 9.4.x
- compile SDK 37 / target SDK 37 / min SDK 25
- Jetpack Compose + Compose for TV
- AndroidX Media3 1.11.1
- WorkManager 2.12.0
- DataStore 1.2.x + Android Keystore
- OkHttp 5.5.x / Coil 3.6.x
- Official WireGuard Android tunnel

## Funzioni portate

Xtream Codes, M3U/M3U8, sorgenti multiple con gestione avanzata, cache per sorgente, EPG XMLTV/M3U/external, catch-up, reminder, live/VOD/serie, ricerca globale, cronologia/ripresa, preferiti, metadata TMDB/OMDb/Trakt/OpenSubtitles, player HLS/DASH/progressive Media3, fallback URL/User-Agent, PiP, selezione tracce e velocità, download persistenti, parental PIN, backup JSON + Auto Backup Android, diagnostica, playlist unificate, personalizzazione Home, VPN personale IKEv2/WireGuard e UI ottimizzata per D-pad/Android TV.

## Sicurezza

Preferenze sensibili, sorgenti, VPN e token Trakt vengono cifrati con Android Keystore AES-GCM. I dati precedenti in chiaro vengono letti come fallback per permettere la migrazione.

## Build

Da Android Studio o da una macchina con SDK Android configurato:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Il repository contiene anche `.github/workflows/android-build.yml` per compilazione e unit test in CI.

## Nota sul porting

Le funzioni Apple-specifiche non vengono emulate con stub: vengono riscritte usando le API Android equivalenti. Vedi `PORTING_MATRIX.md` per la copertura area-per-area e per le limitazioni ereditate dal progetto iOS, ad esempio OpenVPN engine non incluso nel sorgente originale.


## Kotlin/Media3 compile fixes (FIXED10)
- Fixed nullable `SourceSnapshot` fallback in `CatalogRepository`.
- Updated Media3 `ExoPlayer.Builder` usage to `setLoadControl(...)`.
- Fixed Trakt device-code expiration field usage (`expiresInSec`).
- Added Kotlin Serialization `jsonObject` extension import for VPN discovery.
