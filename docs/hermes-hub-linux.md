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
~/.local/bin/hermes-hub-agent-update
~/.config/systemd/user/hermes-hub.service
~/.config/systemd/user/hermes-hub-linux-update.timer
~/.config/systemd/user/hermes-hub-agent-update.timer
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

## Backup SQLite

`~/.local/bin/hermes-hub-backup` crea uno snapshot SQLite con un nome UTC univoco. La sorgente Hub è `HERMES_HUB_SQLITE_PATH` oppure `$HERMES_HOME/hub_state.sqlite3`; senza `HERMES_HOME` usa `~/.hermes`. Gli snapshot sono salvati in `$HERMES_HUB_BACKUP_DIR` oppure `~/.hermes/backups/hub`. Il tool legge i database in sola lettura e usa l'API SQLite online backup, così include anche i dati ancora nel WAL senza copiare file live.

Comandi manuali:

```bash
~/.local/bin/hermes-hub-backup
~/.local/bin/hermes-hub-backup --database /percorso/agent.sqlite3
~/.local/bin/hermes-hub-backup --include-agent-state
~/.local/bin/hermes-hub-backup --verify ~/.hermes/backups/hub/<snapshot>
~/.local/bin/hermes-hub-backup --restore-from ~/.hermes/backups/hub/<snapshot> --restore-to ~/hub-restore-2026-10-07
```

`--database` è ripetibile. `--include-agent-state` aggiunge solo i database noti: nella radice `state.db`, `response_store.db`, `projects.db`, `kanban.db`, `shared-state.db`, `runs_idempotency.db` e `verification_evidence.db`; `cron/executions.db` e `cron/notepad.db`; nei soli figli immediati di `profiles/` e `projects/`, `state.db`, `projects.db`, `response_store.db` e `kanban.db`. I candidati devono essere file regolari, non symlink. Le sorgenti mancanti non vengono create e non si cercano file in modo ricorsivo. Retention predefinita è 7 snapshot completi; `HERMES_HUB_BACKUP_RETENTION` o `--retention` accettano un numero da 1 a 365. Il manifest contiene percorso sorgente, label relativa, dimensione e SHA-256, non dati delle tabelle né credenziali/configurazione.

Il backup e il restore sono database-only: non includono media, configurazione, `.env`, chiavi, segreti o cache. Il restore recupera i database nella nuova directory indicata; l'applicazione deve rimanere offline e l'operatore deve gestire a parte gli altri asset necessari.

Per una copia off-host usare la utility PowerShell. Richiede un alias OpenSSH esplicito, per esempio `hermes-tailscale`; senza `-Snapshot` seleziona l'ultimo nome UTC valido. Verifica manifest, dimensioni e SHA-256 prima della pubblicazione atomica. Conserva al massimo 7 snapshot locali completi e lascia intatte le altre directory.

```powershell
powershell -File .\scripts\pull-hermes-hub-backup.ps1 -SshHost hermes-tailscale
powershell -File .\scripts\pull-hermes-hub-backup.ps1 -SshHost hermes-tailscale -Snapshot 20261007T031500000000Z-a1b2c3d4
```

L'installer copia sempre script e unità systemd, ma il timer giornaliero resta disabilitato salvo richiesta esplicita:

```bash
./scripts/install-hermes-hub-linux.sh --enable-backup
```

Il timer è persistente e applica un ritardo casuale finito. Verificare uno snapshot prima di un restore. Il restore richiede sia snapshot sia una nuova directory destinazione inesistente; non scrive mai automaticamente nel database live. Eseguire eventuale ripristino operativo solo con applicazione offline e destinazione scelta dall'operatore.

## Jarvis Mode

Il patcher aggiunge le sessioni Jarvis allo stesso processo gateway. Sono autenticate, effimere, bounded e trasportano eventi tramite SSE. Nessun frame, perception bus, sintesi o feedback viene scritto negli store Hub. Il Reactor combina prompt stabile, memoria breve incrementale, finestra conversazionale e trigger corrente. Non esiste un loop periodico che forza interventi. Modelli, concorrenza, timeout e soglie sono configurati con `HERMES_JARVIS_*`; il launcher li conserva atomicamente in `.env` senza stamparne le chiavi.

Contratto, variabili e benchmark: [Hermes Jarvis Mode](jarvis-mode.md).

## Aggiornamento

```bash
~/.local/bin/hermes-hub-linux-update --check
~/.local/bin/hermes-hub-linux-update --restart
~/.local/bin/hermes-hub-agent-update --check
```

`hermes-hub-agent-update` is separate from gateway process. Its timer checks
hourly and applies transactionally; set `HERMES_HUB_AGENT_AUTO_UPDATE=false`
in a systemd override to leave it in check-only mode. Before any source mutation
it refuses staged, untracked, or unrelated Hermes Agent changes. The only allowed
unstaged changes are the exact output of the two Hub-managed patch targets.
It also validates bounded numeric probe settings and validates candidate
patchability in an isolated Git worktree.

Readiness is an API-key authenticated `/v1/capabilities` contract check, not a
generic JSON/HTTP-success check: it requires Hermes Agent identity, bearer auth,
server-agent runtime, native features and a native/responses endpoint. If a
candidate exposes a capability version, it must match the installed candidate.
Automatic updates install only the candidate editable package with `--no-deps`
and run `pip check`: they never update, add or remove third-party packages. If
the candidate needs incompatible dependencies, it rolls back/fails and requires
operator intervention. Failure quarantines that revision, restores the previous
editable revision/Hub patch, restarts it and probes it again before recording `rolled_back` in
`/v1/hub/runtime`. Any restore, restart or rollback-probe error records
`rollback_failed` and exits nonzero. A later healthy check clears stale
`failure` state.

The patcher creates sibling `*.bak-hermes-native-<timestamp>-<pid>-<ns>` files
while replacing source atomically. The updater removes only backups whose exact
content matches the active Git revision; every other untracked file remains a
hard block.

L'updater cerca la release piu' recente che contenga un asset Linux, verifica versione, dimensione e SHA-256, estrae su staging, aggiorna il symlink `current`, riavvia e fa health probe. Se il probe fallisce ripristina la release precedente.

Il timer controlla gli aggiornamenti ogni due minuti. Quando trova una release piu' recente, l'aggiorna automaticamente, riavvia il gateway e completa l'health probe con rollback in caso di errore. Non ridurre `TimeoutStartSec` sotto il budget complessivo di download, avvio e probe.

Il busy gate interroga il manager GPU con una chiave dedicata, configurabile tramite `HERMES_HUB_MANAGER_API_KEY` o `HERMES_GPU_MANAGER_KEY` nell'ambiente del servizio o in `~/.hermes/.env`. In alternativa, `HERMES_HUB_MANAGER_KEY_FILE` indica un file raw di una sola riga: deve essere regolare, non symlink, posseduto dall'utente updater e accessibile solo al proprietario (`0400` o `0600`). La variabile può essere impostata anche in `.env`. Se nessuna chiave manager è configurata, l'updater usa la chiave gateway per compatibilità con installazioni che condividono ancora il segreto.

Un 401/403 del manager viene registrato come rifiuto autenticazione distinto da rete irraggiungibile. Configurazione credenziale non valida, risposta HTTP non riuscita o status JSON sconosciuto/non valido fanno differire l'aggiornamento; solo uno status valido `LLM_READY`, coda vuota e nessun job corrente autorizza il gate idle. Il probe `/v1/capabilities` dopo il riavvio continua a usare esclusivamente la chiave gateway. Le chiavi passano a `curl` tramite file temporanei `0600`, rimossi dopo la richiesta, senza inserirle negli argomenti di processo o nei log.

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

## Aggiornamenti agent e re-patch

Il patcher supporta sia il layout single-file sia quello multi-modulo (pi_server_openai_routes.py, pi_server_runs.py): dopo OGNI aggiornamento dell'agent eseguire ehub-patch (in ~/.local/bin), che ricontrolla, riapplica se serve, riavvia il gateway solo in caso di modifiche e verifica gli endpoint Hub. Senza re-patch, le route /v1/hub/* restano 404 sul nuovo layout.
