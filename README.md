# GassPlayer IPTV — Android / Android TV

GassPlayer è il client IPTV nativo Kotlin/Jetpack Compose per telefoni, tablet, Android TV, Google TV e dispositivi Fire OS compatibili.

## Funzioni implementate

- Xtream Codes con verifica credenziali, categorie, Live TV, VOD, serie ed episodi.
- Playlist M3U/M3U8 con `tvg-id`, `tvg-name`, `tvg-logo`, `group-title` e rilevamento catch-up.
- Cache catalogo locale con scadenza e aggiornamento forzato.
- UI responsive per touch e D-pad con focus visibile, griglia compatta e numerazione canali.
- Home, Live TV, Film, Serie, episodi, ricerca globale, preferiti e Continua a guardare.
- Player Media3/ExoPlayer collegato a `MediaSession`, HLS/MPEG-TS/DASH/file progressivi compatibili con Media3, ripresa posizione e Picture-in-Picture.
- EPG reale: `get_short_epg` per Xtream e XMLTV opzionale per M3U.
- DataStore per sorgenti, preferiti e cronologia.
- Import/export JSON completo.
- Parser XMLTV, client TMDB/OMDb, Trakt device flow, OpenSubtitles, download VOD, parental PIN, promemoria EPG e diagnostica rete come servizi riutilizzabili.
- GitHub Actions per build release e pubblicazione dell'APK come artifact.

## Build

Requisiti locali: JDK 17, Android SDK 35 e Gradle 8.11.1.

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Lo script `verify-project.sh` esegue gli stessi controlli. In CI il workflow `.github/workflows/android.yml` usa Java 17 e produce una release firmata come artifact.

## Primo avvio

1. Aprire **Sorgenti**.
2. Aggiungere un account Xtream Codes oppure una playlist M3U.
3. Per M3U, inserire facoltativamente l'URL XMLTV per avere la guida EPG.
4. L'app verifica la sorgente prima di renderla attiva e scarica il catalogo.
5. Usare telecomando, tastiera, touch o controller.

## Sicurezza

Le credenziali provider sono salvate nel DataStore privato dell'app. Per una distribuzione commerciale è consigliabile cifrare le credenziali tramite Android Keystore e non inserire API key di TMDB/OMDb/Trakt/OpenSubtitles nel repository.

## Verifica dell'ambiente

Il progetto include il Gradle Wrapper corretto. Nel sandbox di lavorazione la compilazione non può essere conclusa perché l'ambiente non può risolvere `services.gradle.org` per scaricare Gradle 8.11.1; il progetto è stato comunque sottoposto a controlli statici su sorgenti, manifest, wrapper, riferimenti locali e struttura del pacchetto.
