# Hermes Jarvis Mode

Jarvis Mode aggiunge a Hermes Hub Android una sessione vocale e visiva temporanea. Non crea una seconda app, un secondo gateway o una memoria parallela: usa il gateway Hermes esistente, STT/TTS esistenti e il tool loop di Hermes Agent.

## Architettura

```text
Ray-Ban Meta DAT / fotocamera debug         microfono -> STT
                 |                                  |
                 v                                  v
         FrameSampler -> Perceptor --------> perception bus RAM
                                                |
                         Senser <---------------+------> Summarizer incrementale
                           |                                |
                           +---------- trigger ------------+ memoria breve
                                                |
                                                v
              soul stabile + contesto + SITUATION -> Reactor -> Hermes Agent
                                                        |
                                      Android <- SSE <- decisione -> TTS
```

Lo stesso modello principale esegue due percorsi distinti. Le osservazioni passive e le domande visive elementari usano un turno breve, senza ragionamento visibile, e restituiscono JSON validato; l'osservatore passivo non può parlare direttamente. Le domande complesse e ogni intervento candidato usano Hermes Agent completo con fino a tre JPEG originali recenti. Il motore di iniziativa, separato dai prompt, applica modalita utente, confidenza, utilita, urgenza, cooldown, deduplicazione e stato di riproduzione.

Le osservazioni passive usano una politica latest-event-wins. Una domanda esplicita cancella l'osservazione obsoleta e ha priorita. Osservazione e ragionamento hanno semafori e timeout distinti anche se condividono il modello.

### Reactor e memoria breve

Il flusso riprende la separazione Perceptor/Senser/Summarizer/Reactor dell'architettura Minnarone, adattata a un assistente reale:

- il perception bus unifica parlato, osservazioni, risposte, interventi e feedback in eventi strutturati, bounded e solo RAM;
- il Summarizer usa lo stesso modello principale, senza thinking, ogni sei percezioni significative e aggiorna sintesi, argomento, fatti operativi e richieste ancora aperte;
- il Reactor riceve un prompt stabile di identita e policy, poi goal, memoria breve, finestra conversazionale, dialogo e percezioni recenti;
- `SITUATION` e il trigger corrente sono posti per ultimi nel prompt dinamico, così il motivo dell'azione resta saliente;
- la finestra conversazionale evita interruzioni mentre e attivo uno scambio e mantiene l'eventuale follow-up aperto;
- deduplicazione esatta e semantica impediscono di ripetere lo stesso avviso;
- `Utile` e `Non utile` regolano gradualmente la soglia di iniziativa della sola sessione.

Non esiste un timer che forza Hermes a parlare. Il sistema valuta solo domande esplicite o nuove percezioni campionate; in assenza di un evento rilevante resta silenzioso.

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

Stato della verifica corrente: dipendenze Meta DAT 0.8.0 risolte da GitHub Packages e source set DAT compilato con `lintRelease`, `testDebugUnitTest` e `assembleRelease`. Su emulatore API 36 la build release inizializza il bridge DAT senza crash; la build debug attiva e abbina il Mock Device Kit con feed fotocamera posteriore. La compatibilita API 31 deriva ancora dai requisiti SDK e non da una prova sul OnePlus 7. Gli occhiali reali restano da verificare sul dispositivo.

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

Eventi SSE principali: `session.ready`, `session.updated`, `observer.result`, `memory.summary`, `memory.summary_failed`, `assistant.thinking`, `assistant.escalating`, `assistant.speak`, `initiative.silent`, `feedback.updated`, `session.error`, `session.ended`. Un errore del summarizer e non fatale e usa backoff; le percezioni restano disponibili al tentativo successivo. `Last-Event-ID` riproduce gli eventi ancora nel buffer limitato; keepalive e code client sono bounded.

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
HERMES_JARVIS_SUMMARY_TIMEOUT_SECONDS=15
HERMES_JARVIS_FAST_MAX_TOKENS=256
HERMES_JARVIS_REASONING_MAX_TOKENS=96
HERMES_JARVIS_SUMMARY_MAX_TOKENS=256
HERMES_JARVIS_MAX_CONCURRENT_FAST=2
HERMES_JARVIS_MAX_CONCURRENT_REASONING=1
HERMES_JARVIS_SUMMARY_EVERY_EVENTS=6
HERMES_JARVIS_CONVERSATION_WINDOW_SECONDS=120
HERMES_JARVIS_SEMANTIC_DEDUPE_THRESHOLD=0.82
HERMES_JARVIS_SEMANTIC_DEDUPE_SECONDS=600
HERMES_JARVIS_FEEDBACK_STEP=0.04
HERMES_JARVIS_SIMPLE_MIN_CONFIDENCE=0.95
HERMES_JARVIS_ASSISTIVE_THRESHOLD=0.48
HERMES_JARVIS_PROACTIVE_THRESHOLD=0.48
```

Con `HERMES_JARVIS_SINGLE_MODEL=true`, osservazione e ragionamento usano il modello principale configurato da `HERMES_JARVIS_REASONING_*` o, se vuoto, da `HERMES_INFERENCE_*`. Le variabili `FAST_*` regolano ancora timeout, token e concorrenza del percorso osservatore; URL e modello `FAST_*` restano solo per un eventuale opt-in esplicito a due modelli. Non esiste fallback cloud implicito.

Le soglie incluse derivano dal benchmark live descritto sotto e restano conservative: non sono una garanzia di accuratezza su scene reali.

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

Misura live del 25 luglio 2026 sul modello principale: latenza osservatore p50 1.743 ms, p95 2.440 ms e media 1.792 ms. Nel gateway completo una domanda visiva semplice ha richiesto 2.161 ms; due prove complesse con Hermes Agent hanno richiesto 27.532 e 36.258 ms. TTS su frase breve: 129 ms; STT sul WAV generato: 624 ms. I target iniziali di 1,5/3 secondi non sono quindi raggiunti. Il gateway forza al silenzio le risposte passive semplici e invia al ragionamento completo solo candidati con importanza e utilità elevate. Le domande visive semplici possono usare il percorso breve dello stesso modello solo con confidenza alta; tutte le altre passano a Hermes Agent completo.

## Test

```powershell
python -m ruff check scripts tests
python -m unittest discover -s tests -p "test_*.py"
python -m py_compile .\scripts\patch-hermes-gateway-native.py
.\scripts\verify-visual-blocks-contract.ps1

.\scripts\package-android-release.ps1
```

QA runtime richiesta: API 36, API 31, telefono debug, Mock Device Kit, poi occhiali reali. Per ogni percorso controllare start/pause/resume/stop, domanda semplice, escalation con frame originale, intervento autonomo, STT, TTS, riconnessione SSE, logcat e assenza di file residui.

Verificato in questa implementazione: build standard su emulatore API 36; apertura della schermata release e guardia configurazione senza crash; build debug con fotocamera telefono, sessione HTTP/SSE, upload JPEG, foreground service, pausa senza nuovi upload, ripresa, DELETE e cleanup; compilazione completa della variante Meta DAT; bridge DAT release e Mock Device Kit debug su emulatore API 36. Non verificati: runtime API 31 e Ray-Ban reali.

## Troubleshooting

- `401` da GitHub Packages: il PAT non ha `read:packages`, è scaduto o l'utente non coincide con le credenziali Maven.
- `Meta DAT non incluso`: l'APK non proviene dal packaging ufficiale; non pubblicarlo e rieseguire `scripts/package-android-release.ps1`.
- `Jarvis Mode non abilitato`: impostare `HERMES_JARVIS_ENABLED=true` e riavviare il gateway dopo il patcher.
- `fast_model_unavailable`: in single-model indica che il modello principale non è configurato o raggiungibile.
- `reasoning_model_unavailable`: domande e iniziativa autonoma restano disabilitate finché il modello principale non torna disponibile.
- `frame_too_large`: ridurre risoluzione/qualita JPEG o aumentare il limite con cautela.
- nessun video DAT: verificare registrazione Meta AI, consenso camera, firmware, stato indossato/connesso e compatibilita della versione DAT.

Fonti upstream: [Meta Wearables DAT Android](https://github.com/facebook/meta-wearables-dat-android), [Mock Device Kit](https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/).
