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

## Aggiornamento

```bash
~/.local/bin/hermes-hub-linux-update --check
~/.local/bin/hermes-hub-linux-update --restart
```

L'updater cerca la release piu' recente che contenga un asset Linux, verifica versione, dimensione e SHA-256, estrae su staging, aggiorna il symlink `current`, riavvia e fa health probe. Se il probe fallisce ripristina la release precedente.

Il timer controlla gli aggiornamenti ogni ora. Non ridurre `TimeoutStartSec` sotto il budget complessivo di download, avvio e probe.

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
- chat SSE;
- `/v1/audio/transcriptions` e `/v1/audio/speech`;
- archivio e relativo stream eventi;
- upload/download media;
- update simulato con health probe riuscito e fallito.

Non riavviare il gateway live senza accesso shell e rollback verificato.
