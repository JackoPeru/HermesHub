# Hermes Hub

Client Windows + Android per parlare con Hermes Agent su home-server.

## Progetti

- Windows: `src/NemoclawChat.Windows`
- Android: `src/NemoclawChat.Android`
- Admin Bridge legacy/dev opzionale: `src/ChatClaw.AdminBridge`
- Guide: `docs/windows-desktop-guide.md`, `docs/android-app-guide.md`, `docs/hermes-hub-conversion.md`, `docs/hermes-hub-linux.md`
- Preset: `config/hermes-defaults.json`

## Stato attuale

- Windows WinUI 3: UI dark stile ChatGPT, sidebar, chat, archivio, jobs, Hermes server, runs, settings, profilo e updater.
- Android Compose: UI mobile dark stile ChatGPT, composer, menu `+`, archivio, jobs, Hermes server, runs, settings, profilo e updater in-app.
- Chat: `POST /v1/responses` primario con `store`, `conversation`, `previous_response_id`; fallback `POST /v1/chat/completions`.
- Visual Blocks v1: spiegazioni visuali statiche sicure nella chat (`markdown`, `code`, `table`, `chart`, `diagram`, `image_gallery`, `media_file`, `callout`) con fallback testuale.
- Jobs: task persistenti, sync reale su Hermes Jobs API `/api/jobs`, azioni `run`, `pause`, `delete`.
- Server: dashboard Hermes con `/health`, `/health/detailed`, `/v1/models`, `/v1/capabilities`.
- Runs: endpoint manuale e preset reali per health, models, capabilities, runs e jobs.
- Update: Android scarica APK in app e apre installer; Windows scarica asset `.msix`, `.exe` o `.zip`.

## Preset Hermes

Preset in [config/hermes-defaults.json](config/hermes-defaults.json):

- Hermes API URL: `http://hermes.local:8642/v1`
- Health: `http://hermes.local:8642/health`
- Model: `hermes-agent`
- API primaria: `/v1/responses`
- API fallback: `/v1/chat/completions`
- Accesso consigliato: `Tailscale/LAN`
- Auth: client usa `Authorization: Bearer hermes-hub` come default; se fallisce prova fallback compat.

## Build

Windows:

```powershell
dotnet build .\src\NemoclawChat.Windows\NemoclawChat.Windows.csproj -c Debug -p:Platform=x64
```

Android:

```powershell
$env:ANDROID_HOME='C:\Users\Matteo\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
cd .\src\NemoclawChat.Android
.\gradlew.bat assembleDebug
```

APK debug:

```text
src/NemoclawChat.Android/app/build/outputs/apk/debug/androidApp-debug.apk
```

## Release

Versione corrente:

```text
v0.6.38
```

Asset attesi dagli updater:

- Android: `.apk`
- Windows: `.msix`, `.exe` o `.zip`
