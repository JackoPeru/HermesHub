# Android

App Jetpack Compose di Hermes Hub.

## Compatibilita

- nome visibile: `Hermes Hub`;
- `applicationId`: `com.nemoclaw.chat`;
- `minSdk`: 26;
- `compileSdk`/`targetSdk`: 36;
- la firma e il `versionCode` devono restare compatibili con gli APK pubblicati.

Le preferenze storiche `chatclaw_*` e i dati utente vengono migrati, non azzerati.

## Backend

Ordine endpoint:

1. `http://hermes:8642/v1`
2. `http://100.94.223.14:8642/v1`
3. `http://hermes.local:8642/v1`

La chat usa Hermes Native/Responses e, se consentito, Chat Completions compat. Health, capabilities, archivio, media, STT, TTS, jobs, runs e stato Hub passano dallo stesso gateway.

## Build e test

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
cd .\src\NemoclawChat.Android
.\gradlew.bat lintRelease testDebugUnitTest assembleRelease
```

Output:

```text
src/NemoclawChat.Android/app/build/outputs/apk/release/app-release.apk
```

## QA release

- installare l'APK su emulatore o dispositivo supportato;
- verificare aggiornamento sopra la release precedente;
- aprire Chat, Voce, Archivio, Server, Video e Impostazioni;
- inviare e interrompere uno stream reale;
- verificare allegato, download, STT e TTS;
- controllare logcat per crash, ANR, leak e violazioni StrictMode;
- verificare versione, package name e certificato con `apkanalyzer`/`apksigner`.

## Regole runtime

- ogni coroutine lunga deve avere owner lifecycle e cancellazione;
- timeout finiti per rete, polling e media;
- niente retry automatico di operazioni mutate dopo accettazione server;
- allegati grandi in streaming su file, non interamente in memoria;
- WebView e player devono essere distrutti quando la schermata termina;
- il token Hermes non deve raggiungere host esterni.
