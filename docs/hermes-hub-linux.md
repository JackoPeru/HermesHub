# Gateway Linux

Il server di produzione usa Hermes Agent su Linux e pubblica il gateway su `0.0.0.0:8642` per Tailnet/LAN.

## Installazione

Dal bundle release:

```bash
chmod +x scripts/*.sh
./scripts/install-hermes-hub-linux.sh --enable-service --enable-auto-update
```

Percorsi principali:

```text
~/.local/share/hermes-hub-gateway/releases/<versione>
~/.local/share/hermes-hub-gateway/current
~/.local/bin/hermes-hub-linux-update
~/.config/systemd/user/hermes-hub.service
~/.config/systemd/user/hermes-hub-linux-update.timer
~/.hermes/.env
```

Il launcher conserva le chiavi `.env` non gestite e aggiorna atomicamente solo quelle necessarie.

## Backend locale

Default:

```text
provider: custom
inference: http://127.0.0.1:8000/v1
gateway: http://0.0.0.0:8642/v1
model: letto da /v1/models; fallback hermes-agent
```

Il servizio attende Tailscale e llama.cpp con timeout finiti. `HERMES_AUXILIARY_LOCAL_ONLY=true` impedisce fallback esterni per i task ausiliari.

## Patch gateway

`patch-hermes-gateway-native.py` modifica l'`api_server.py` installato da Hermes Agent.

Garanzie richieste:

- compatibilita con upstream supportato;
- idempotenza su file puro e gia' patchato;
- staging e `py_compile` prima del replace;
- rollback se la patch o la compilazione fallisce;
- nessun avvio silenzioso del gateway non patchato.

Verifica:

```bash
python3 ~/patch-hermes-gateway-native.py --check
curl -fsS -H 'Authorization: Bearer <your-api-key>' http://127.0.0.1:8642/v1/capabilities
```

## Store e media

Default sotto `~/.hermes`:

- `hub_conversations.json`
- `hub_state.json`
- `hub_memory.json`
- `hub_uploads/`
- `media/`

Le root media specifiche precedono sempre `$HERMES_TERMINAL_CWD` o `%h`, che restano fallback finali. I mutatori usano lock e replace atomico.

## Jarvis Mode

Il patcher aggiunge le sessioni Jarvis allo stesso processo gateway. Sono autenticate, effimere, bounded e trasportano eventi tramite SSE. Nessun frame, perception bus, sintesi o feedback viene scritto negli store Hub. Il Reactor combina prompt stabile, memoria breve incrementale, finestra conversazionale e trigger corrente. Non esiste un loop periodico che forza interventi. Modelli, concorrenza, timeout e soglie sono configurati con `HERMES_JARVIS_*`; il launcher li conserva atomicamente in `.env` senza stamparne le chiavi.

Contratto, variabili e benchmark: [Hermes Jarvis Mode](jarvis-mode.md).

## Aggiornamento

```bash
~/.local/bin/hermes-hub-linux-update --check
~/.local/bin/hermes-hub-linux-update --restart
```

L'updater cerca la release piu' recente che contenga un asset Linux, verifica versione, dimensione e SHA-256, estrae su staging, aggiorna il symlink `current`, riavvia e fa health probe. Se il probe fallisce ripristina la release precedente.

Il timer controlla gli aggiornamenti ogni due minuti. Quando trova una release piu' recente, l'aggiorna automaticamente, riavvia il gateway e completa l'health probe con rollback in caso di errore. Non ridurre `TimeoutStartSec` sotto il budget complessivo di download, avvio e probe.

## Packaging

```powershell
.\scripts\package-linux-gateway.ps1 -Version X.Y.Z
```

Output:

```text
artifacts\HermesHub-X.Y.Z-linux-gateway.tar.gz
```

Il tar deve includere `VERSION`, launcher, patcher, installer, updater, unit/timer systemd e script di attesa/monitoraggio previsti.

## Probe pre-release

- `/health` e `/health/detailed`;
- `/v1/capabilities`;
- `GET/PUT/DELETE /v1/hub/wellbeing` per i riepiloghi salute giornalieri (solo aggregati, autenticati);
- chat SSE;
- `/v1/audio/transcriptions` e `/v1/audio/speech`;
- sessione/frame/turno/eventi/cleanup Jarvis quando abilitato;
- archivio e relativo stream eventi;
- upload/download media;
- update simulato con health probe riuscito e fallito.

Non riavviare il gateway live senza accesso shell e rollback verificato.

## Readiness GPU STT/TTS

Il launcher mantiene Whisper `large-v3-turbo` int8 e Kokoro FP16 sulla GPU 1. Entrambi eseguono inferenza di warm-up bloccante durante l'avvio: la porta gateway non diventa disponibile finche' modelli e workspace CUDA non sono pronti. Con i default ufficiali, preload disabilitato, provider CPU o errore CUDA fanno fallire il processo; systemd lo riavvia senza degradare silenziosamente su CPU.

Override operativi principali:

- `HERMES_WHISPER_PRELOAD_REQUIRED=1`, `HERMES_WHISPER_DEVICE=cuda`, `HERMES_WHISPER_DEVICE_INDEX=1`;
- `HERMES_KOKORO_PRELOAD_REQUIRED=1`, `HERMES_KOKORO_REQUIRE_GPU=1`, `HERMES_KOKORO_CUDA_DEVICE=1`;
- `HERMES_WHISPER_PRELOAD_TIMEOUT_SECONDS=300` e `HERMES_KOKORO_PRELOAD_TIMEOUT_SECONDS=180`.
