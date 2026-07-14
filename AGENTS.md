# AGENTS.md

## Comunicazione

- Usa sempre la skill `caveman` all'inizio di ogni chat.
- Comunica in italiano, in modo sintetico e operativo.
- Non dichiarare una release pronta senza prove statiche, build, runtime e pacchetti.

## Obiettivo

Hermes Hub e' il client operativo di Hermes Agent sul server personale:

- Windows: WinUI 3.
- Android: Jetpack Compose.
- Gateway: patch e launcher Linux per Hermes Agent.
- Nome visibile: `Hermes Hub`.
- Android mantiene `applicationId = com.nemoclaw.chat` e la stessa firma storica.

Il client non deve sostituire memoria, planning, tool loop o policy di Hermes Agent.

## Repository e Git

- Remoto: `https://github.com/JackoPeru/app-interazione-nemoclaw.git`.
- Branch di release: `main`.
- Non fare commit, push, tag o release senza richiesta esplicita di Matteo.
- Non sovrascrivere modifiche estranee presenti nel worktree.
- Ogni release deve incrementare insieme Windows, AdminBridge, Android `versionName` e Android `versionCode`.
- La cronologia delle release vive in `CHANGELOG.md`, non in questo file.

## Topologia reale

Ordine endpoint client:

1. `http://hermes:8642/v1`
2. `http://100.94.223.14:8642/v1`
3. `http://hermes.local:8642/v1`

Valori operativi:

- modello: `hermes-agent`
- protocollo preferito: `hermes-native`
- token compat personale: `hermes-hub`
- accesso: Tailnet/LAN, HTTP privato intenzionale

Non aggiungere host generici, localhost o backend paralleli come fallback impliciti.
Le impostazioni salvate dall'utente non vanno sovrascritte durante migrazioni o avvio.

## Invarianti app

- Chat streaming: preservare spazi iniziali e delta whitespace-only.
- Cancellazione: annullare davvero rete, parser, polling e salvataggi tardivi.
- Retry: non ripetere richieste mutanti dopo che il server le ha accettate.
- Archivio: scrittura atomica, tombstone, merge last-write-wins e sync push-assisted.
- Allegati: streaming su disco; un file fallito non deve eliminare quelli validi.
- Credenziali: mai in backup, log, URL esterni o file repository.
- Media: token Hermes solo verso endpoint Hermes; URL HTTPS esterni senza Bearer.
- TTS/STT/Voce: timeout finiti, cleanup deterministico, riproduzione sequenziale.
- Updater: download parziale separato, verifica dimensione/firma/versione/publisher, installazione solo dopo validazione.
- Errori reali visibili; nessun fallback demo silenzioso.
- Nessun codice diagnostico, segreto, foto utente, cache o artefatto di build tracciato.

## Invarianti gateway Linux

- Il patcher deve essere idempotente su upstream puro e su versioni gia' patchate.
- Prima di sostituire `api_server.py`: patch su staging, `py_compile`, replace atomico.
- In caso di errore: gateway precedente intatto e avvio fallito in modo esplicito.
- Store Hub: scritture atomiche, lock e limiti configurabili.
- Upload/STT/TTS/media: timeout e limiti espliciti; mai caricare body grandi interamente senza necessita'.
- `HERMES_MEDIA_ROOTS`: directory specifiche prima; `$HERMES_TERMINAL_CWD`/`%h` solo fallback finale.
- Updater: lock, asset Linux corretto, digest/size/versione, staging, symlink atomico, health probe e rollback.
- Il timer deve poter completare download, riavvio e probe entro `TimeoutStartSec`.
- Non riavviare il server live senza accesso shell e percorso di rollback verificato.

## Progetti

- Windows: `src/NemoclawChat.Windows`
- Android: `src/NemoclawChat.Android`
- AdminBridge opzionale/dev: `src/ChatClaw.AdminBridge`
- Gateway e packaging: `scripts`
- Contratti e test: `tests`
- Contratti di configurazione verificati dalla CI: `config`

## Verifiche minime

Windows:

```powershell
dotnet format .\NemoclawChat.sln --verify-no-changes
dotnet build .\src\NemoclawChat.Windows\NemoclawChat.Windows.csproj -c Release -p:Platform=x64
dotnet build .\src\ChatClaw.AdminBridge\ChatClaw.AdminBridge.csproj -c Release
```

Android:

```powershell
cd .\src\NemoclawChat.Android
.\gradlew.bat lintRelease testDebugUnitTest assembleRelease
```

Gateway e contratti:

```powershell
python -m pip install -r requirements-dev.txt
python -m ruff check scripts tests
python -m unittest discover -s tests -p "test_*.py"
python -m py_compile .\scripts\patch-hermes-gateway-native.py
.\scripts\verify-visual-blocks-contract.ps1
```

Su Linux o GitHub Actions eseguire anche `bash -n` e `shellcheck` su tutti gli script `.sh`.

## Runtime pre-release

- Android: installare APK release su emulatore/API supportata; aprire Chat, Voce, Archivio, Server e Impostazioni; controllare crash/ANR in logcat.
- Windows: installare e avviare l'MSIX release firmato (identita' pacchetto reale); navigare le sezioni principali, inviare una chat reale e verificare arresto stream e chiusura pulita.
- Gateway live: health, capabilities, chat SSE, TTS WAV, STT multipart, archivio ed eventi SSE.
- Dopo ogni prova mutante, verificare che non siano rimasti dati diagnostici o chat temporanee.

## Packaging e release

Per `X.Y.Z`:

```powershell
.\scripts\package-windows-msix.ps1 -Version X.Y.Z -Platform x64
.\scripts\package-linux-gateway.ps1 -Version X.Y.Z
```

Asset attesi:

- `HermesHub-X.Y.Z-android.apk`
- `NemoclawChat.Windows_X.Y.Z.0_x64.msix`
- `HermesHub-X.Y.Z-linux-gateway.tar.gz` quando il gateway cambia

Prima della pubblicazione:

- firme APK/MSIX valide;
- versione e package identity coerenti;
- hash SHA-256 registrati;
- tar Linux contiene `VERSION` e soli file previsti;
- CI del commit verde;
- release note coerenti con le modifiche effettive.

## Release corrente

Versione corrente: `0.6.156`.
