# Hermes Hub

Client Windows + Android per parlare con Hermes Agent su home-server.

## Progetti

- Windows: `src/NemoclawChat.Windows`
- Android: `src/NemoclawChat.Android`
- Admin Bridge legacy/dev opzionale: `src/ChatClaw.AdminBridge`
- Guide: `docs/windows-desktop-guide.md`, `docs/android-app-guide.md`, `docs/hermes-hub-conversion.md`, `docs/hermes-hub-linux.md`
- Preset: `config/hermes-defaults.json`

## Stato attuale

- Windows WinUI 3: UI dark stile ChatGPT, sidebar, chat, archivio, jobs, Hermes server, hardware, runs, settings, profilo e updater.
- Android Compose: UI mobile dark stile ChatGPT, composer, menu `+`, archivio, jobs, Hermes server, hardware, runs, settings, profilo e updater in-app.
- Chat: Hermes Native default via Responses/native transport con `store`, `conversation`, `previous_response_id`; fallback compat solo se strict native mode e' disattivato.
- Visual Blocks v1: spiegazioni visuali statiche sicure nella chat (`markdown`, `code`, `table`, `chart`, `diagram`, `image_gallery`, `media_file`, `callout`) con fallback testuale.
- Jobs: task persistenti, sync reale su Hermes Jobs API `/api/jobs`, azioni `run`, `pause`, `delete`.
- Server: dashboard Hermes con `/health`, `/health/detailed`, `/v1/models`, `/v1/capabilities`, video library, memoria e hub state.
- Hardware: prestazioni host remoto via gateway `GET /v1/hub/hardware`, polling 1s con CPU, RAM, swap, dischi, rete, processi e temperature se esposte dal sistema.
- Runs: endpoint manuale e preset reali per health, models, capabilities, runs e jobs.
- Memoria/Sync: `/v1/hub/memory` e `/v1/hub/state` per preferenze, feedback Video/News, progetto attivo e stato letto.
- Update: Android scarica APK in app e apre installer; Windows scarica asset `.msix`, `.exe` o `.zip`.
- Linux Gateway: update da GitHub Releases con `~/.local/bin/hermes-hub-linux-update --restart` e timer systemd opzionale.

## Preset Hermes

Preset in [config/hermes-defaults.json](config/hermes-defaults.json):

- Hermes API URL: `http://hermes:8642/v1`
- Health: `http://hermes:8642/health`
- Model: `hermes-agent`
- API primaria: Hermes Native (`/v1/responses` o alias gateway `/v1/hermes/native`)
- API fallback: `/v1/chat/completions` solo compat/strict OFF
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
v0.6.87
```

Asset attesi dagli updater:

- Android: `.apk`
- Windows: `.msix`, `.exe` o `.zip`
- Linux Gateway: `HermesHub-X.Y.Z-linux-gateway.tar.gz`
