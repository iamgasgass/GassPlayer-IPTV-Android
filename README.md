# GassPlayer IPTV — Android / Android TV

Porting nativo Kotlin/Jetpack Compose di GassPlayer IPTV per telefoni, tablet, Android TV, Google TV e dispositivi compatibili Fire OS.

## Funzioni incluse

- Xtream Codes: autenticazione, categorie, Live TV, VOD, serie ed episodi.
- Playlist M3U/M3U8 con attributi `tvg-id`, `tvg-logo`, `group-title` e catch-up.
- UI responsive con navigazione D-pad, focus TV, griglia compatta e numeri canale.
- Home, Live TV, Film, Serie, episodi, ricerca globale, preferiti, cronologia e Continua a guardare.
- Player Media3/ExoPlayer, controlli TV, PiP, tracce audio/sottotitoli supportate dal flusso e ripresa della posizione.
- Cache catalogo, DataStore, import/export JSON e GitHub Actions.
- Parser XMLTV, client TMDB/OMDb, Trakt device flow e OpenSubtitles.
- Download VOD, parental PIN, promemoria EPG, diagnostica rete e punto di integrazione VPN.

## Build

Requisiti: JDK 17, Android SDK 35 e Gradle 8.11.1.

```bash
gradle assembleDebug
```

L'APK sarà in `app/build/outputs/apk/debug/`. Su GitHub è sufficiente caricare la repository e avviare **Android CI**.

## Primo avvio

1. Aprire **Sorgenti**.
2. Aggiungere account Xtream Codes oppure URL M3U.
3. Attendere il caricamento e scegliere la sorgente attiva.
4. Usare telecomando, tastiera, touch o controller.

## Sicurezza

Le credenziali restano nel DataStore privato dell'app. Prima della distribuzione pubblica è consigliato integrare un backend o cifratura Keystore specifica per le credenziali provider. Non inserire API key in Git.

