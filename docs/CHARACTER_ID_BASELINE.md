# Hermes Character ID — Baseline (Fase 0)

Data audit: 2026-10-07/08. Server `hermes` via LAN. Repo branch `feat/character-id`.
Nessun file esistente modificato in questa fase (solo questo documento).

## 1. Host e hardware

| Voce | Valore |
|---|---|
| OS/kernel | Ubuntu 24.04, kernel 7.0.0-34-generic |
| Uptime al rilievo | 1d 18h, load ~2.2 |
| GPU | 2 × RTX 5060 Ti 16 GB (16311 MiB), driver 595.91.07 |
| RAM | 30 GB (15 usati al rilievo) |
| Swap | 8 GB file `/swap.img` (4.3 GB usati = pressione esistente) |
| Disco `/` | 936 GB totali, 531 GB liberi (41% usato) |
| CUDA toolkit di sistema | **assente** (no nvcc, no /usr/local/cuda) — torch porta il proprio runtime |

## 2. Python / PyTorch

| Ambiente | Python | Torch | CUDA torch |
|---|---|---|---|
| ComfyUI venv (`runtimes/comfyui/venv`) | 3.12.3 | 2.11.0+cu128 | 12.8 |
| Manager venv (`/opt/hermes/venv`) | 3.12.3 | 2.11.0+cu130 | 13.0 (nota: minor mismatch vs cu128, da uniformare in M4) |
| Sistema `/usr/bin/python3` | 3.12.3 | — | — |
| Hub gateway | bundle agent `python-3.14.7` (tools) | — | — |

Musubi Tuner: **non installato da nessuna parte** (verificato `find / -maxdepth 5`).

## 3. ComfyUI

- Path: `/opt/hermes/runtimes/comfyui/app` (+ `venv/` a fianco). **Snapshot senza git** (no commit pinnabile — deviazione §11).
- Eseguito come `hermes-comfyui.service` (headless, `--lowvram`, porta 8188), attualmente `inactive (dead)`: gestito dal GPU manager, caricato a richiesta.
- Custom nodes: `ComfyUI-GGUF` (valutato e scartato per H3), `ComfyUI-H3-MultiStream`, `websocket_image_save.py`.
- Nodi H3 **nativi** (niente custom node dedicato): `MiniMaxH3ImageToVideo`, `MiniMaxH3ReferenceToVideo`, CLIP type `minimax` (= Qwen3-VL).
- `ComfyUI-H3-PowerLoraStack` (serve per Fase 7 multi-LoRA): **non presente**, da valutare in M13.

## 4. Modelli H3 (`/opt/hermes/models/minimax-h3/`, 119 GB, symlinkati in `ComfyUI/models/*`)

| File | Size | Ruolo Character ID |
|---|---|---|
| `minimax_h3_fl2va_pruned_int8_convrot.safetensors` | 20 970 379 616 B (~20 GB) | **Base training LoRA (M4)** — è il pruned ConvRot INT8 voluto dalla spec |
| `minimax_h3_ref2va_pruned_int8_convrot.safetensors` | ~20 GB | Ref2VA Mode B — **già su disco** (deviazione §11: il doc diceva "mancante") |
| `minimax_h3_fl2va_pruned-Q4_K.gguf` | ~11 GB | Scartato (TE incompatibile, arch sconosciuta al nodo) |
| `minimax_h3_fl2v_turbo_4step_768p.safetensors` | ~2 GB | LoRA turbo per preview/eval veloci (già in `loras/`) |
| `minimax_h3_astro_nsfw.safetensors` | 285 MB | LoRA esistente = **precedente d'uso LoRA su H3** (pattern da riusare per character) |
| `minimax_h3_video_vae_fp16.safetensors` | ~5 GB | Video VAE |
| `qwen3vl_32b_minimax_h3_int8_convrot.safetensors` | ~26 GB | Text encoder principale (encode una tantum su CPU) |
| `qwen3vl_32b_minimax_h3_ultra_uncensored_heretic_int8_convrot.safetensors` | ~25 GB | TE alternativa (04-10) |
| `qwen3vl_32b_minimax_h3-Q2_K_M.gguf` | ~13 GB | Scartato per H3 |
| Audio VAE | **assente** | Preset sempre muted — nessun audio nei video |

Extra in ComfyUI models (non-H3): `seedvr2_3b_int8_convrot`, `qwen-image-2.1-UC-Q4_K_M.gguf`, `qwen3vl_8b_w4a8`, VAE Qwen/SVD. Hash SHA256 dei pesi H3 **da calcolare in M2** (119 GB: run notturno).

## 5. GPU Manager Hermes

- Service: `hermes-gpu-manager.service` (active), CPU-only orchestrator, porta **8643**, auth Bearer, fail-closed.
- Codice server = repo `gpu-manager/manager.py` (md5 `f317bf570a6e9f72722efb6dc9accdc9` identico al repo).
- Config: `/etc/hermes/gpu-manager.yaml` (root, backup `.bak-deploy-*` presenti).
- Lock globale esistente + stati `LLM_READY / MEDIA / DIRECT`, code, ruoli (localhost bloccato su `/mode/*` e `/system/*`), `allow_manual_modes=true`, `llm_serving` con cache+lock.
- Endpoint utili a Character ID: `POST /jobs/video` (preset+prompt+input), `GET /jobs/{id}`, `POST /jobs/{id}/cancel`, `GET /status` (coda, VRAM, serving), `POST /jobs/smart`.
- Workflow versionati: `/opt/hermes/media-workflows/h3/` (`t2v.json`, `i2v.json`, `i2v-turbo.json`, `first_last.json`, `reference.json`) — mirror in repo `gpu-manager/workflows/h3/`.
- Stato training dedicato `h3-character-train`: **da aggiungere in M6** (estende la state machine esistente, non la sostituisce).

## 6. HermesHub (repo locale)

- App Android (`src/NemoclawChat.Android`, `com.nemoclaw.chat`): chat/bot/voice/server/comfy già esistenti — la UI `Characters` si aggiunge come feature come `ComfyFeature`.
- Backend gateway impacchettato: `scripts/hermes_hub_gateway/` (API `bootstrap.py`, adapters, modules, protocol, triage, run_resume).
- Hub live = upstream Hermes Agent (`~/.hermes/hermes-agent`, gateway v0.6.206, `gateway run` su porta **8642**).
- Datastore esistenti (nessun DB dedicato ai media): `hub_state.sqlite3`, `hub_conversations.json`, `kanban.db`, `state.db` in `~/.hermes/` → **SQLite è la convenzione** (spec DB confermata).
- Porte/API: hub 8642 · manager 8643 · tabby 8000 · TTS 8020 · STT 8010 · Comfy 8188 · laya 11435.
- Output: `/opt/hermes/media-output/images|videos/<job_id>/` = master + `metadata.json` + derivati FFmpeg. **Il preset dei video H3 è il formato di output anche per preview/eval Character ID.**
- Job system: coda manager + poll `GET /jobs/{id}` + `metadata.json` su disco (riavvio-safe). Recovery failure verificata (failed → cleanup → restore Qwen → `LLM_READY`).
- Notifiche/progressi: `hub_notifications.json` + endpoint hub `GET/POST/PATCH /v1/hub/notifications` (l'app fa poll) → **sistema realtime da riusare per i progressi training** (niente WebSocket da costruire).

## 7. Baseline H3 (evidenza senza disturbare il server)

Run fresco differito a finestra idle (al rilievo GPU0 97% / GPU1 83%, Tabby attivo, video generati la mattina stessa — un job baseline da 5+ min con unload Qwen avrebbe degradato la chat; deviazione §11).
Ultimo job H3 reale riuscito (da `media-output/videos/1e0511b5a15f/metadata.json`):

- preset `journey_video_preview`, backend `minimax-h3`, model `fl2va_pruned_int8+turbo4`
- runtime 300.3 s, `vram_peak_mb` 14791, status `done`, output APNG + H264 + WebM + poster

Comando baseline da eseguire in idle (M15 lo riusa come riferimento pre/post training):
`curl -X POST 127.0.0.1:8643/jobs/video -d '{"preset":"journey_video_preview","prompt":"baseline character-id: woman portrait, neutral light","parameters":{"seed":42}}'` poi poll `GET /jobs/{id}`.

Benchmark noti (2×5060Ti, doc MEDIA_PIPELINE.md): turbo 4-step 39f ~2-3 min warm / 124f ~8 min; quality 25-step 1344×768 39f ~21 min; VRAM peak ~15.8 GB su GPU0, GPU1 libera; RAM 25 GB + 7 GB swap in encode TE.

## 8. Spazio per Character ID

531 GB liberi: ampiamente sufficienti per `/opt/hermes/character-id` (dataset < 5 GB/char) + venv Musubi (~10 GB) + cache Musubi + LoRA output. Nessun problema disco.

## 9. Convenzioni directory (riuso)

`/opt/hermes/{models,media-output,media-workflows,runtimes,venv,gpu-manager,logs,hf-cache}` (owner `matteo`, 755/775 — scrivibile senza sudo) + `~/.hermes/*.sqlite3|*.json` per stato hub. Backup esistenti: `/opt/hermes/_backups`, `/opt/hermes/backups`, `*.bak-*` — la regola "backup prima di modificare" è già prassi del progetto.

## 10. Stato servizi al rilievo

Active: `hermes-gpu-manager`, `hermes-tabby` (Qwen 27B EXL3), `hermes-kokoro-tts`, `hermes-laya`, fan profiles. Dead/by-design: `hermes-comfyui` (on-demand), `hermes-llama`.

## 11. Deviazioni dalla spec (adattamenti meno invasivi)

1. **Path dati**: `/var/lib/hermes` non esiste → uso `/opt/hermes/character-id/` (codice+runtime+dati sotto un'unica root esistente, stessi permessi di models/media-output). Struttura interna `characters/<uuid>/...` invariata.
2. **Ref2VA già presente**: `minimax_h3_ref2va_pruned_int8_convrot` + `reference.json` esistono (il doc MEDIA_PIPELINE.md è datato: "mancano pesi ref2va"). M8 diventa verifica/integrazione, non installazione da zero.
3. **License gate MiniMax**: `docs/MEDIA_PIPELINE.md` documenta esclusione UE/Italia per H3 con `DISABLED_LICENSE_GATE`, ma registra anche override esplicito dell'operatore per test locali ("H3 — test tecnici locali su override operatore"). Questo progetto prosegue sullo stesso override ordinato: tutto locale, nessun upload, nessun uso commerciale, nessuna redistribuzione pesi.
4. **Baseline run fresco**: differito a finestra idle (motivo §7); evidenza interinale = job `1e0511b5a15f`.
5. **ComfyUI senza git**: nessun commit pinnabile; pin = snapshot + lista custom nodes + hash pesi (M2, run notturno per i 119 GB).
6. **Torch cu130 (manager venv) vs cu128 (comfy venv)**: nota per M4 (Musubi userà cu128 come ComfyUI).

## 12. Stato Milestone 1

- [x] Audit installazione/server/modelli/manager/convenzioni/API/notifiche
- [x] Questo documento
- [ ] Run fresco baseline H3 in finestra idle (comando pronto §7)
- [ ] (M2 può partire subito: niente blocker)
