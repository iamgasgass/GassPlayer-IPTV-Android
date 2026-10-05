# Stato build

La repository include Gradle Wrapper e workflow GitHub Actions. Nel sandbox di generazione non erano installati JDK e Android SDK e l'installazione di toolchain eseguibili è stata bloccata; sono stati eseguiti controlli statici su XML, manifest, parentesi Kotlin, struttura Gradle e risorse.

La verifica definitiva si esegue automaticamente con GitHub Actions oppure localmente tramite:

```bash
./verify-project.sh
```

Il workflow esegue `lintDebug`, test unitari e `assembleDebug`, quindi pubblica l'APK come artifact.
