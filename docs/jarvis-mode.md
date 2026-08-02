# Hermes Jarvis Mode

Jarvis Mode aggiunge a Hermes Hub Android una sessione vocale e visiva temporanea. Non crea una seconda app, un secondo gateway o una memoria parallela: usa il gateway Hermes esistente, STT/TTS esistenti e il tool loop di Hermes Agent.

## Architettura

```text
Ray-Ban DAT 7 FPS -> campionamento luminanza -> JPEG selezionato
                                              |
microfono -> VAD -> STT beam 1 ----------> perception bus RAM
                                              |
                                   Reactor multimodale compatto
                                   | risposta + memoria inline
                                   |
                                   +-- needs_agent=true --> Hermes Agent
                                              |
                               SSE -> TTS a segmenti -> Android
```

Con modello unico, percorso comune usa una sola inferenza diretta con prompt Jarvis compatto. Output validato contiene osservazione, risposta, punteggi, aggiornamento memoria breve e `needs_agent`. Hermes Agent completo entra solo per tool, memoria durevole, verifiche critiche o ragionamento multi-step.

Osservazione passiva usa un worker unico. Inferenza attiva non viene cancellata: frame intermedi vengono scartati e resta soltanto ultimo frame. Cadenza successiva si adatta alla latenza misurata. Domanda esplicita blocca nuove osservazioni e ottiene priorita appena termina unica inferenza gia in corso. In single-model, percorso compatto ed escalation condividono stesso semaforo GPU.

Memoria breve viene aggiornata nello stesso output compatto; nessuna inferenza Summarizer separata. Motore iniziativa resta deterministico: modalita, confidenza, utilita, urgenza, cooldown, deduplicazione, feedback e stato riproduzione decidono se parlare.

Non esiste timer che forza interventi. Sistema valuta domande o nuove percezioni; altrimenti resta silenzioso.
## Differenza da Voce

| Voce | Jarvis Mode |
|---|---|
| Conversazione audio continua | Conversazione audio con contesto visivo |
| Salva la chiamata nell'archivio quando termina | Sessione, trascrizione e frame non vengono archiviati |
| Nessuna iniziativa visiva | `questions_only`, `assistive`, `proactive` |
| Schermata immersiva | Servizio foreground, notifica e azioni pausa/riprendi/termina |

Wake word globale, Voce e Jarvis non aprono contemporaneamente due `AudioRecord`. Entrando nella scheda Jarvis il listener wake word globale viene sospeso.

## Android e Meta DAT

Integrazione verificata staticamente contro Meta Wearables DAT `0.8.0`:

- artifact: `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice`;
- repository: `https://maven.pkg.github.com/facebook/meta-wearables-dat-android`;
- gli AAR dichiarano `minSdk 29`, `targetSdk 33`;
- Android 12/API 31 soddisfa quindi il requisito SDK;
- la variante standard di sviluppo resta `minSdk 26`;
- ogni APK ufficiale include DAT e usa `minSdk 29`;
- telemetria DAT disabilitata con `ANALYTICS_OPT_OUT=true`.

Requisiti runtime reali: Meta AI compatibile, Developer Mode o progetto nel Wearables Developer Center, occhiali/firmware supportati, registrazione dell'app e consenso camera tramite Meta AI. DAT resta un Developer Preview: la compatibilita metadata non sostituisce una prova sul telefono e sugli occhiali.

Stato verifica corrente: versione `0.6.181` registrata e provata su Ray-Ban Meta reali; inizializzazione DAT, sessione, stream video e uso continuativo risultano stabili. Dipendenze Meta DAT 0.8.0 e packaging DAT restano verificati anche staticamente e in CI. Reactor v2 richiede nuova prova fisica prima della prossima release.

PAT Packages e credenziali Meta non vanno nel repository. La release ufficiale richiede PAT classic `read:packages`, application ID e client token del progetto Wearables Developer Center:

```powershell
$env:GITHUB_TOKEN = "<PAT read:packages>"
$env:GITHUB_ACTOR = "<utente GitHub>"
$env:META_DAT_APPLICATION_ID = "<application id Meta>"
$env:META_DAT_CLIENT_TOKEN = "<client token Meta>"
.\scripts\package-android-release.ps1
```

Alternativa locale ignorata da Git:

```properties
# src/NemoclawChat.Android/local.properties
sdk.dir=C:\\Users\\<utente>\\AppData\\Local\\Android\\Sdk
githubPackagesToken=<PAT read:packages>
mwdatApplicationId=<application id Meta>
mwdatClientToken=<client token Meta>
```

Il packaging fallisce prima della build se una credenziale manca o vale `0`, quindi non puo' produrre silenziosamente un APK privo di registrazione Meta. Verifica inoltre BuildConfig, classi DAT nel DEX, metadata manifest, `minSdk 29`, versione e firma storica. La variante standard e' solo diagnostica: `-PallowStandardReleaseForDevelopment=true`; non va pubblicata.

In CI lo stesso script usa `-CiValidation`: le credenziali sono sintetiche e l'output termina in `-DAT-validation-only.apk`. Questo percorso prova DAT end-to-end ma non puo' essere confuso con l'asset ufficiale.

Nella schermata Jarvis, `Configura occhiali Meta` apre registrazione, consenso camera e, in debug, Mock Device Kit. Il mock simula il video DAT; audio e TTS restano quelli Android.

## Modalita telefono

Nelle build debug è disponibile `Fotocamera telefono`. Usa camera posteriore a 640x480, JPEG, stesso sampler, stessi endpoint, SSE, STT e TTS. È un percorso diagnostico, non il comportamento principale della release.

Permessi usati durante una sessione: microfono, camera quando il fallback telefono è selezionato, Bluetooth Connect per il routing audio e notifica foreground. La notifica offre `Pausa vista`, `Solo domande`, `Riprendi`, `Termina`.

## Contratto gateway

Tutti gli endpoint usano la stessa autenticazione Bearer del gateway:

```text
POST   /v1/jarvis/sessions
GET    /v1/jarvis/sessions/{session_id}
PATCH  /v1/jarvis/sessions/{session_id}
DELETE /v1/jarvis/sessions/{session_id}
POST   /v1/jarvis/sessions/{session_id}/frames
POST   /v1/jarvis/sessions/{session_id}/turns
GET    /v1/jarvis/sessions/{session_id}/events
POST   /v1/jarvis/sessions/{session_id}/feedback
```

Creazione:

```json
{"mode":"assistive","goal":"Aiutami a montare questo computer"}
```

Aggiornamento:

```json
{"mode":"questions_only","view_paused":true,"status":"paused"}
```

Frame: body `image/jpeg`, `image/png` o `image/webp`, oppure multipart con campo `frame`. Il limite è applicato durante lo streaming del body. La risposta `202` include `frame_id`, deduplicazione, pianificazione observer e tempo upload.

Turno:

```json
{"transcript":"Questo è il connettore corretto?","frame_ids":["facoltativo"]}
```

Eventi SSE principali: `session.ready`, `session.updated`, `observer.result`, `memory.summary`, `assistant.thinking`, `assistant.escalating`, `assistant.speak`, `initiative.silent`, `feedback.updated`, `session.error`, `session.ended`. `memory.summary` deriva dall'output compatto gia in corso e non avvia inferenze aggiuntive. `Last-Event-ID` riproduce eventi ancora nel buffer limitato; keepalive e code client sono bounded.

Il feedback accetta soltanto l'`event_id` di un intervento autonomo esistente e una sola valutazione per intervento.

`/v1/capabilities` espone solo disponibilita, limiti e path pubblici. URL modello e chiavi non vengono restituiti.

## Configurazione server

```bash
HERMES_JARVIS_ENABLED=true
HERMES_JARVIS_MOCK_MODE=false
HERMES_JARVIS_SINGLE_MODEL=true

HERMES_JARVIS_REASONING_BASE_URL=http://127.0.0.1:<porta>/v1
HERMES_JARVIS_REASONING_API_KEY=
HERMES_JARVIS_REASONING_MODEL=Qwen3.5-35B-A3B

HERMES_JARVIS_MAX_FRAME_BYTES=1000000
HERMES_JARVIS_FRAME_TTL_SECONDS=20
HERMES_JARVIS_SESSION_TTL_SECONDS=3600
HERMES_JARVIS_MAX_CONTEXT_EVENTS=64
HERMES_JARVIS_MAX_PERCEPTIONS=128
HERMES_JARVIS_FAST_TIMEOUT_SECONDS=12
HERMES_JARVIS_REASONING_TIMEOUT_SECONDS=60
HERMES_JARVIS_FAST_MAX_TOKENS=256
HERMES_JARVIS_REASONING_MAX_TOKENS=96
HERMES_JARVIS_MAX_CONCURRENT_FAST=1
HERMES_JARVIS_MAX_CONCURRENT_REASONING=1
HERMES_JARVIS_OBSERVER_LATENCY_MULTIPLIER=0.25
HERMES_JARVIS_OBSERVER_MIN_GAP_SECONDS=0.75
HERMES_JARVIS_OBSERVER_MAX_GAP_SECONDS=8
HERMES_JARVIS_CONVERSATION_WINDOW_SECONDS=120
HERMES_JARVIS_SEMANTIC_DEDUPE_THRESHOLD=0.82
HERMES_JARVIS_SEMANTIC_DEDUPE_SECONDS=600
HERMES_JARVIS_FEEDBACK_STEP=0.04
HERMES_JARVIS_ASSISTIVE_THRESHOLD=0.48
HERMES_JARVIS_PROACTIVE_THRESHOLD=0.48
HERMES_KOKORO_STREAM_CHUNK_CHARS=220
```

Con `HERMES_JARVIS_SINGLE_MODEL=true`, osservazione, risposta compatta ed eventuale escalation usano modello principale configurato da `HERMES_JARVIS_REASONING_*` o `HERMES_INFERENCE_*`. Percorso comune esegue una sola chiamata diretta. `FAST_*` regola timeout e token del prompt compatto; URL e modello `FAST_*` restano per opt-in esplicito a due modelli. Nessun fallback cloud implicito.

Soglie restano conservative e vanno ricalibrate con benchmark reale dopo nuova build.

## Privacy e cleanup

- frame solo in RAM, massimo tre per sessione, TTL predefinito 20 secondi;
- nessun frame nello store upload, archivio conversazioni, backup Android o cache permanente;
- trascrizione, perception bus, sintesi e feedback limitati alla sessione e mai salvati come chat;
- payload, prompt, immagini, trascrizioni e token esclusi dalle metriche;
- delete, TTL, arresto gateway e stop Android cancellano task, stream, sessione DAT, recorder, player, SSE e buffer;
- token Hermes inviato solo all'host gateway configurato; chiavi modello restano sul server.

## Benchmark osservatore

Il manifest contiene 50 casi e 76 frame PNG generati deterministicamente: scene normali, cambiamenti, OCR, domande semplici, ambiguita, errori ed escalation. Nessuna immagine privata è tracciata.

```powershell
python .\scripts\benchmark-jarvis-observer.py --validate-only

$env:HERMES_JARVIS_REASONING_API_KEY = "<solo se richiesto>"
python .\scripts\benchmark-jarvis-observer.py `
  --base-url http://127.0.0.1:<porta>/v1 `
  --model <modello-principale> `
  --output .\artifacts\jarvis-observer-benchmark.json
```

Il report misura JSON valido, accuracy azione, falsi positivi/negativi, silenzio, precisione risposte semplici, escalation e latenza p50/p95. Calibrazione e holdout sono separati. Il report propone soglie gateway; va applicato solo dopo controllo dell'holdout.

Misura live del 25 luglio 2026, precedente a Reactor v2: osservatore p50 1.743 ms, p95 2.440 ms, media 1.792 ms; domanda visiva semplice 2.161 ms; due prove Hermes Agent 27.532 e 36.258 ms; TTS breve 129 ms; STT 624 ms. Dati usati come baseline, non come prova delle nuove ottimizzazioni. Reactor v2 elimina restart continui, doppia inferenza comune e Summarizer separato. TTS invia WAV incorniciati e Android riproduce primo segmento mentre server genera successivi. Jarvis richiede STT `beam_size=1` e usa 420 ms di silenzio finale.

## Test

```powershell
python -m ruff check scripts tests
python -m unittest discover -s tests -p "test_*.py"
python -m py_compile .\scripts\patch-hermes-gateway-native.py
.\scripts\verify-visual-blocks-contract.ps1

.\scripts\package-android-release.ps1
```

QA runtime richiesta: API 36, API 31, telefono debug, Mock Device Kit, poi occhiali reali. Per ogni percorso controllare start/pause/resume/stop, domanda semplice, escalation con frame originale, intervento autonomo, STT, TTS, riconnessione SSE, logcat e assenza di file residui.

Verificato prima di Reactor v2: build standard API 36, fotocamera telefono, HTTP/SSE, upload JPEG, lifecycle servizio, packaging DAT e Ray-Ban Meta reali su `0.6.181`. Reactor v2 richiede nuova build e nuova prova fisica prima della release.

## Troubleshooting

- `401` da GitHub Packages: il PAT non ha `read:packages`, è scaduto o l'utente non coincide con le credenziali Maven.
- `Meta DAT non incluso`: l'APK non proviene dal packaging ufficiale; non pubblicarlo e rieseguire `scripts/package-android-release.ps1`.
- `Jarvis Mode non abilitato`: impostare `HERMES_JARVIS_ENABLED=true` e riavviare il gateway dopo il patcher.
- `fast_model_unavailable`: in single-model indica che il modello principale non è configurato o raggiungibile.
- `reasoning_model_unavailable`: domande e iniziativa autonoma restano disabilitate finché il modello principale non torna disponibile.
- `frame_too_large`: ridurre risoluzione/qualita JPEG o aumentare il limite con cautela.
- nessun video DAT: verificare registrazione Meta AI, consenso camera, firmware, stato indossato/connesso e compatibilita della versione DAT.

Fonti upstream: [Meta Wearables DAT Android](https://github.com/facebook/meta-wearables-dat-android), [Mock Device Kit](https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/).
