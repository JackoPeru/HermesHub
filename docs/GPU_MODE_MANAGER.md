# GPU Mode Manager (HermesHub)

Condivisione automatica delle 2× RTX 5060 Ti tra LLM (TabbyAPI/Qwen EXL3) e media (ComfyUI).

## Architettura

HermesHub e l'uso manuale parlano con `hermes-gpu-manager` (daemon CPU-only, FastAPI su `127.0.0.1:8643`
di default, configurabile, raggiungibile via LAN/Tailnet, mai esposto su Internet).
Il manager è l'unico a toccare le GPU: Qwen non gestisce mai il passaggio perché quando è scaricato
non può ragionare. I job media sono autosufficienti (workflow completo nel job).

Stati: `LLM_READY → LLM_UNLOADING → GPU_FREE → MEDIA_STARTING → MEDIA_READY ⇄ MEDIA_BUSY
→ MEDIA_STOPPING → LLM_LOADING → LLM_READY`, più `ERROR`. `desired_mode`: `LLM`/`MEDIA`/`AUTO`
(persistita). `AUTO + idle = LLM_READY` (default sicuro).

Batching: i job MEDIA in coda vengono eseguiti tutti di fila con UN SOLO unload/reload;
dopo `media_idle_timeout` (default 30s) senza nuovi job si torna in LLM.

## Servizi systemd

| Unit | Tipo | Boot | Note |
|---|---|---|---|
| `hermes-gpu-manager.service` | system | enabled | daemon, restart on-failure |
| `hermes-comfyui.service` | system | **disabled** | gestito dal manager, mai al boot |
| `hermes-tabby.service` | system | enabled | LLM, invariato |

ComfyUI: `/opt/hermes/runtimes/comfyui` (venv isolato, torch cu128), checkpoint in
`app/models/checkpoints`, output in `app/output`. Manager: `/opt/hermes/gpu-manager`
(venv con fastapi/uvicorn/pyyaml), stato SQLite `/var/lib/hermes-gpu-manager/state.db`,
output job in `/opt/hermes/gpu-manager/outputs/<job_id>/` (+ `metadata.json`).
Config: `/etc/hermes/gpu-manager.yaml`.

## API (OpenAPI su `/docs`)

- `GET /status` → desired_mode, current_state, llm_online/llm_loaded, media_online,
  queue_length, current_job, media_progress, active_preset, active_media_model,
  qwen_image_installed/ready, h3_installed/ready/license_state, presets[], GPU[].
- `POST /mode/llm|media|auto`
- `POST /jobs/image`, `POST /jobs/video` (raw workflow oppure `{preset, prompt,
  input_images[], parameters{}}` → workflow versionati da `/opt/hermes/media-workflows/`)
- `GET /jobs`, `GET /jobs/{id}`, `POST /jobs/{id}/cancel`

Dettagli pipeline media (preset, H3 gate, benchmark, prompting): `docs/MEDIA_PIPELINE.md`.

CLI: `hermes-gpu status|mode|submit|job|jobs|cancel|logs` (anche per l'agent Hermes come tool:
`hermes-gpu submit workflow.json image && hermes-gpu job <id>` in polling; Qwen prepara
workflow+prompt+negativi+parametri PRIMA del submit e non serve durante l'offline).

## Switching e health check (reali, mai presunti)

- LLM→MEDIA: drain 10s → `POST /v1/model/unload` → verifica `nvidia-smi` (soglia
  `vram_free_mb`) → kill solo residui CUDA nostri (comfy/exllama/tabby) → start ComfyUI →
  health `/system_stats` → submit → poll `/history` → copie output → idle timeout →
  stop ComfyUI → `restart hermes-tabby` → `/v1/models` + VRAM + vera completion di test.
- Fail-safe: retry `max_retries` con backoff, poi `ERROR` con diagnostica; recovery tenta
  sempre il ripristino Qwen (`restart hermes-tabby` + health check).
- Boot: reconcile (processi, Tabby, ComfyUI, GPU, job `running`→`queued`), default LLM.

## UI HermesHub

Server → card "Modalità GPU (Qwen / ComfyUI)": pulsanti LLM/MEDIA/AUTO, stato reale da
`/status`, VRAM per GPU, coda, job corrente, ultimo errore. Mai stato inventato:
se il manager non risponde mostra "non raggiungibile". Base URL derivata dal gateway
(`:8642`→`:8643`, vedi `gpuManagerBase`).

## Logging / diagnostica

`journalctl -u hermes-gpu-manager` (transizioni, job %, idle, restore),
`journalctl -u hermes-comfyui`, `hermes-gpu logs`, `nvidia-smi`, `hermes-gpu status`.
Mai segreti nei log.

## Rollback

`/opt/hermes/gpu-manager/rollback-gpu-manager.sh [--purge]`: spegne manager+ComfyUI,
riabilita TabbyAPI. Hermes torna LLM-only come prima.

## Troubleshooting

- `state=ERROR`: leggi `last_error` da `/status`, poi `POST /mode/auto` per far ripartire
  la recovery; se Qwen non torna: `sudo systemctl restart hermes-tabby`.
- Transizioni lente: warm-up Tabby ~3-5 min e ComfyUI ~1 min sono normali (timeout in config).
- `401` sul manager: hai impostato `api_key` in config ma chiami senza Bearer.
- VRAM contesa dopo crash: il manager uccide solo residui comfy/exllama/tabby; altri
  carichi vanno fermati a mano.

## Nuovi workflow / modelli futuri

1. Metti checkpoint in `app/models/checkpoints` (mai nel venv LLM).
2. Testa il workflow su ComfyUI diretto (`:8188`), poi invialo via `POST /jobs/image`.
3. Per video: stesso meccanismo, `kind=video` (timeout dedicato `job_timeout_video`).
4. Altri runtime futuri: nuovo `media.backend` + adapter in `manager.py` (stessa state machine).
