# Run-resume: stato lavori + cosa resta (PARCHEGGIATO — Hermes al lavoro, NON TOCCARE IL SERVER)

Data freeze: 2026-10-03. Commit locali NON pushati: `8c75f69` (resume v1), `f7c0dff` (gate updater).
Modifiche NON committate (fase 2, in working tree): `legacy_patcher.py`, `run_resume.py`, `tests/test_run_resume.py`.
Nessuna release creata. Sul server gira già il motore fase-1 + parte fase-2 (vedi sotto).

## Cosa fa il sistema (già live sul server)

- `api_server_runs.py` patchato (backup `*.bak-rr-*` accanto al file): hook track/event/finish
  + reconciler thread (boot delay ~10s + readiness hub+LLM, max 10 min; poi ogni 10 min).
- Checkpoint per run in `~/.hermes/runs_idempotency.db`, tabella `run_resume`, transazioni atomiche.
- Stati: `running → checkpointed → interrupted → resuming → running → completed`,
  più `recovery_required` (sticky), `superseded`, mirror di `failed/cancelled`.
- Hook `/stop` → riga `run_user_stop` (mai resume). Hook shutdown → marker
  `~/.hermes/hub_controlled_shutdown.json` (consumato al boot).
- Il resume NON parte mai se: run terminale, approval pendente, tool strict in-flight
  senza risultato, transcript illeggibile/rewound, LLM scarico (`llm_ready()==False`
  → resta `interrupted`, riprova dopo), child vivo, lease/boot corrente.

## Dimostrato live (log nei journal 2026-10-03)

- kill -9 durante strict in-flight → `recovery_required`, zero duplicati, nessun child.
- kill nel gap → child completa 6/6 marker esattamente 1 volta; poi completed stabile.
- user `/stop` → `cancelled` + restart → mai resume. Completed → mai resume.
- Restart controllati multipli: nessun resume spurio, marker consumato.
- Readiness: `hub=True llm=True after 9s` (niente più attesa fissa 120s).
- Difetto trovato e fixato: reconcile colpiva run vive (boot_id) + regressione stati (ora guarded).

## Scoperto live (importante per il design)

- Il drain graceful (TimeoutStopSec 240s) fa finire i task corti: serve lavoro >240s per
  forzare il percorso resume-su-restart.
- **Burst media sfrattano Tabby** (manager → MEDIA, LLM unload) e uccidono le run LLM
  con `HTTP 503 No models are currently loaded` (3 casi osservati: T1b-child, S6B, S6C).
  Il gate `llm_ready()==False → defer` è nato per questo ed è già nel motore.
- Il resume NON protegge dal 503: se un burst parte mentre il child lavora, il child
  fallisce → parent `recovery_required` (corretto, niente loop).

## RESTA DA FARE (solo con Hermes IDLE: LLM_READY + coda 0 stabile)

Check idle: `bash /tmp/qidle.sh` (ricreare, vedi sotto — /tmp server ripulito).

1. **T8 (doppio restart, a metà)**: run S8B `run_73c11da079a8` + child `run_52295a2f5108`
   (stato al freeze: parent `resuming`, child attivo con 3 completed/1 inflight).
   Serviva RESTART#2 mentre il child lavora → catena singola attesa
   (parent ri-resume con C2, C1 `superseded`, marker 1..12 una volta sola).
   Se il child ha già finito: parent `completed` → T8 da rifare da zero con
   `/tmp/qe2et8b.sh` (ricreare, 12 step × 25s).
2. **T2 (updater vero)**: run attiva + `VERSION=0.6.0` trick + `hermes-hub-linux-update
   --restart` → deve resumere dopo l'update (script `/tmp/qe2et2.sh` da ricreare).
   Nota: l'update NON tocca `api_server_runs.py` (solo helper), patch al sicuro.
3. **T6-strict-graceful**: run lunga (>240s rimanenti al restart!) + restart graceful
   durante sleep strict → atteso `recovery_required`, marker dello step eseguito una
   volta sola, nessun child (script `/tmp/qe2et6c.sh` da ricreare, 5 step × 60s).
4. **Cleanup finale**: cancellare righe test `run_resume` (`run_a3ba%`, `run_9ad1%`,
   `run_a160%`, `run_36e%`, `run_b75c%`, `run_a202%`, `run_8f84%`, `run_52db%`,
   `run_2d48%`, `run_7458%`, `run_de81%`, `run_73c1%`, `run_5229%`, S6/S6B/S6C/S8/S8B),
   `rm /tmp/rr_* /tmp/q*.sh /tmp/VERSION.saved`, verificare hub+manager sani.
5. **Commit separato** (no push/release): i 3 file modified sopra.

## Script da ricreare in C:\Users\matte\AppData\Local\Temp\opencode\ (cancellati dal server)

Tutti piccoli wrapper bash+python over ssh (vedi history comandi 2026-10-03):
`qidle.sh` (status manager), `qckpt.sh` (righe resume + marker file),
`qrun2.sh <tag> <steps> <sleep>` (POST /v1/runs + marker), `qwait.sh`,
`qe2et8b.sh`, `qe2et2.sh`, `qe2et6c.sh`, `qe2et3b.sh`, `qdeploy2.sh` (applica patch
da `/tmp/rrbundle`), `qclean.sh`. Bundle deploy: `rrbundle/hermes_hub_gateway/{run_resume.py,
adapters/hermes/legacy_patcher.py}` + driver python che chiama
`legacy_patcher._patch_runs_resume` sul file live (con backup atomico + py_compile).

## Avvertenze

- MAI restart/kill con coda media > 0 o run reali attive: ogni restart uccide le run
  agent in memoria (è proprio ciò che il resume mitiga, ma il test distrugge comunque
  il lavoro di Hermes in corso).
- Il timer updater gira ogni 2 min: senza nuova release è no-op ("Already installed").
  Non pubblicare release durante i test (farebbe restartare l'hub a metà test).
- Il file live `api_server_runs.py` contiene già TUTTI gli hook (verificare con
  `grep -c HERMES_HUB_RUN_RESUME_V1_BEGIN` == 1 e presenza di
  `_hermes_hub_rr_user_stop`, `_hermes_hub_rr_shutdown_mark`).
- Drop-in di test `40-rr-test.conf`: GIÀ RIMOSSO. Non ricreare.
- Backups `api_server_runs.py.bak-rr-*`: tenuti, non cancellare fino a release.
