# Stato build

La versione corrente corregge il Gradle Wrapper, completa il percorso sorgenti → catalogo → EPG → player e aggiunge preferiti dedicati e backup/import JSON.

Controlli eseguiti nel sandbox:

- struttura Gradle e Wrapper verificati;
- manifest XML valido e componenti dichiarati presenti nel codice;
- nessun riferimento residuo a `OpenTV`/`opentv`;
- nessun `TODO`, `FIXME` o `NotImplemented` nel codice applicativo;
- file sorgente e risorse inclusi nello ZIP verificati;
- `./gradlew --version` arriva al download di Gradle 8.11.1, ma la rete del sandbox non risolve `services.gradle.org`.

Verifica definitiva consigliata in un ambiente Android/CI con accesso alla rete:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
