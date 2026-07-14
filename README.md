# Hermes Hub

Client Windows e Android per usare Hermes Agent sul proprio home server, con gateway Linux integrato.

## Componenti

- `src/NemoclawChat.Windows`: app WinUI 3.
- `src/NemoclawChat.Android`: app Jetpack Compose.
- `scripts`: launcher, patcher, updater e packaging del gateway Linux.
- `src/ChatClaw.AdminBridge`: bridge locale opzionale per sviluppo.
- `config`: contratti di configurazione verificati dalla CI e schema Visual Blocks.
- `tests`: contratti e test automatici.

Hermes Hub resta un thin client: contesto, memoria, planning e strumenti appartengono a Hermes Agent.

## Connessione

Ordine predefinito:

1. `http://hermes:8642/v1`
2. `http://100.94.223.14:8642/v1`
3. `http://hermes.local:8642/v1`

Protocollo preferito: Hermes Native/Responses. Il fallback Chat Completions dipende dall'impostazione `strict native mode`. L'accesso HTTP cleartext e' intenzionale solo su Tailnet/LAN privata.

## Funzioni principali

- chat e agent mode in streaming;
- reasoning, progressi, tool call ed eventi Hermes separati;
- allegati, Visual Blocks e download media;
- archivio sincronizzato multidispositivo con tombstone;
- modalita Voce continua con VAD, Whisper STT e Kokoro TTS;
- jobs, runs, memoria, stato, video, notifiche e telemetria host;
- aggiornamento in-app Windows/Android;
- aggiornamento transazionale del gateway Linux con health probe e rollback.

## Build

Windows x64:

```powershell
dotnet build .\src\NemoclawChat.Windows\NemoclawChat.Windows.csproj -c Release -p:Platform=x64
```

Android:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
cd .\src\NemoclawChat.Android
.\gradlew.bat lintRelease testDebugUnitTest assembleRelease
```

Gateway e contratti:

```powershell
python -m pip install -r requirements-dev.txt
python -m ruff check scripts tests
python -m unittest discover -s tests -p "test_*.py"
.\scripts\verify-visual-blocks-contract.ps1
```

## Documentazione

- [Windows](docs/windows-desktop-guide.md)
- [Android](docs/android-app-guide.md)
- [Gateway Linux](docs/hermes-hub-linux.md)
- [Confini architetturali](docs/hermes-hub-vs-hermes-native.md)
- [Visual Blocks](docs/visual-blocks-schema.md)
- [Cronologia release](CHANGELOG.md)

Versione corrente: `0.6.156`.
