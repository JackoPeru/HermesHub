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

- Remoto: `https://github.com/JackoPeru/HermesHub.git`.
- Branch di release: `main`.
- Non fare commit, push, tag o release senza richiesta esplicita dell'utente.
- Non sovrascrivere modifiche estranee presenti nel worktree.
- Ogni release deve incrementare insieme Windows, AdminBridge, Android `versionName` e Android `versionCode`.
- La cronologia delle release vive in `CHANGELOG.md`, non in questo file.

## Topologia reale

Endpoint client:

- configurato esplicitamente dall'utente, preferibilmente tramite MagicDNS;
- nessun hostname, IP o endpoint personale deve essere incluso come default nel repository;
- gli aggiornamenti devono conservare endpoint e impostazioni gia' salvati.

Valori operativi:

- modello: `hermes-agent`
- protocollo preferito: `hermes-native`
- token API: configurato dall'utente e conservato nello storage sicuro del dispositivo
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
- L'APK Android ufficiale deve includere Meta Wearables DAT; nessun fallback standard puo' essere pubblicato come asset di release.
- Errori reali visibili; nessun fallback demo silenzioso.
- Nessun codice diagnostico, segreto, foto utente, cache o artefatto di build tracciato.

### Meta Wearables DAT / Jarvis Mode

- Inizializzare Meta Wearables una sola volta per processo tramite `MetaWearablesRuntime`; nessun'altra classe deve chiamare direttamente `Wearables.initialize(...)`.
- L'inizializzazione deve essere thread-safe e idempotente; `WearablesError.ALREADY_INITIALIZED` indica runtime gia' valido, non un errore di avvio.
- Configurazione Meta e sessione Jarvis devono condividere lo stesso runtime DAT. Non registrare nuovamente un'app gia' `REGISTERED` e non resettare il runtime durante un normale avvio sessione.
- Selezionare solo un device DAT con `LinkState.CONNECTED`, verificare `Permission.CAMERA`, creare la sessione con `SpecificDeviceSelector` e aggiungere lo stream solo dopo `DeviceSessionState.STARTED`.
- Dichiarare Jarvis attivo solo dopo `StreamState.STREAMING`; monitorare errori e chiusure sia della sessione sia dello stream e propagare una sola causa terminale visibile.
- Un tap su Avvia deve produrre un solo tentativo deterministico. Vietati loop entra/esci, retry DAT concorrenti e workaround automatici su Bluetooth.
- Stop, errore e cancellazione devono chiudere nell'ordine: raccolta frame, stream, sessione DAT, job Android, sessione gateway; cleanup ripetuto deve restare sicuro.
- Non rimuovere da manifest `INTERNET`, `BLUETOOTH`, `BLUETOOTH_CONNECT`, `CAMERA` o `com.meta.wearable.mwdat.DAT_ENABLED=true`.
- `tests/test_release_consistency.py` deve continuare a impedire inizializzazioni DAT multiple e release APK prive di DAT.
- Evidenza fisica: `0.6.181` testata su Ray-Ban Meta reali; registrazione, avvio, stream video e sessione stabile. Modifiche future al lifecycle DAT richiedono nuovo test su hardware prima della release.
- Richiedere al DAT `7 FPS`; calcolare cambiamento scena sul piano luminanza e comprimere JPEG solo dopo il campionamento, per ridurre consumo occhiali e telefono.
- Una nuova immagine non deve cancellare un'inferenza visiva in corso: un worker unico termina il frame attivo e conserva soltanto il frame pendente piu' recente, con cadenza adattiva alla latenza.
- In `HERMES_JARVIS_SINGLE_MODEL=true`, observer compatto ed escalation condividono un solo semaforo GPU. Il percorso normale deve usare una sola chiamata diretta con prompt Jarvis minimo; `_run_agent` e il system prompt Hermes completo sono riservati a `needs_agent=true`.
- La memoria breve deve aggiornarsi nello stesso output strutturato dell'observer; vietato reintrodurre un Summarizer LLM periodico separato sul modello principale.
- Le domande vocali hanno priorita' sulle nuove osservazioni passive. STT Jarvis usa `beam_size=1` e fine-frase breve; non simulare streaming Whisper ripetendo trascrizioni sovrapposte.
- TTS Jarvis deve riprodurre segmenti validati appena disponibili; mantenere fallback WAV non-streaming per gateway precedenti e non ripetere una richiesta dopo l'inizio della riproduzione.

## Invarianti gateway Linux

- Il patcher deve essere idempotente su upstream puro e su versioni gia' patchate.
- Prima di sostituire `api_server.py`: patch su staging, `py_compile`, replace atomico.
- In caso di errore: gateway precedente intatto e avvio fallito in modo esplicito.
- Store Hub: scritture atomiche, lock e limiti configurabili.
- Upload/STT/TTS/media: timeout e limiti espliciti; mai caricare body grandi interamente senza necessita'.
- STT e TTS gateway devono completare un warm-up GPU bloccante prima dell'ascolto HTTP; se CUDA o preload falliscono, avvio fail-closed e nessun fallback CPU silenzioso.
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
.\scripts\package-android-release.ps1
```

Lo script ufficiale richiede PAT `read:packages`, `mwdatApplicationId` e `mwdatClientToken`, compila con `-PenableMetaDat=true` e verifica classi DAT, `META_DAT_ENABLED=true`, `minSdk 29`, versione e firma storica. Deve fallire senza produrre asset se uno di questi controlli manca. La release standard senza DAT e' consentita solo per sviluppo con `-PallowStandardReleaseForDevelopment=true` e non va mai pubblicata.

La CI usa `package-android-release.ps1 -CiValidation`: compila e verifica DAT con credenziali sintetiche, ma produce esclusivamente `*-DAT-validation-only.apk`, vietato come asset GitHub. Una release ufficiale non deve mai usare `-CiValidation`.

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
.\scripts\package-android-release.ps1 -Version X.Y.Z
.\scripts\package-windows-msix.ps1 -Version X.Y.Z -Platform x64
.\scripts\package-linux-gateway.ps1 -Version X.Y.Z
```

Asset attesi:

- `HermesHub-X.Y.Z-android.apk`
- `NemoclawChat.Windows_X.Y.Z.0_x64.msix`
- `HermesHub-X.Y.Z-linux-gateway.tar.gz` quando il gateway cambia

Prima della pubblicazione:

- firme APK/MSIX valide;
- APK Android prodotto esclusivamente da `package-android-release.ps1`, con DAT e credenziali Meta non-placeholder incorporate e confrontate col manifest compilato;
- versione e package identity coerenti;
- hash SHA-256 registrati;
- tar Linux contiene `VERSION` e soli file previsti;
- CI del commit verde;
- release note coerenti con le modifiche effettive.

## Release corrente

Versione corrente: `0.6.182`.
