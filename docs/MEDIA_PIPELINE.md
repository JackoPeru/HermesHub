# Media Pipeline (HermesHub locale)

Pipeline image/video 100% locale: Qwen-Image-2.1 per immagini, MiniMax H3 per video
(**DISABLED_LICENSE_GATE**: vedi sotto), orchestrate da `hermes-gpu-manager`.

## License gate (verificato dalle fonti ufficiali, settembre 2026)

- **Qwen-Image-2.1 pesi: Qwen Research License Agreement (2026-09-20) — SOLO USO NON COMMERCIALE**
  (§2a). Uso commerciale (siti reali inclusi) richiede licenza separata:
  `model-business@notice.qwencloud.com`. Nessuna restrizione territoriale. Attribution
  obbligatoria in redistribuzione ("Qwen is licensed under the Qwen RESEARCH LICENSE
  AGREEMENT..."). "Built with Qwen" se alleni modelli sugli output. Uso locale qui: consentito.
- **MiniMax H3: MiniMax H3 Community License — UE/Italia ESCLUSE** (§I.5 Excluded Territories:
  EU, UK, Korea, USA; §V.4 vieta uso fuori Applicable Territory).
  **Pesi MAI scaricati, mai eseguiti.** Stato: `DISABLED_LICENSE_GATE` (visibile in `/status`
  e in app). Per abilitare: autorizzazione scritta MiniMax per uso Italia/UE, poi scaricare
  `MiniMaxAI/MiniMax-H3` ufficiali, aggiungere template in `media-workflows/h3/`, togliere i
  flag `disabled` dal registry, benchmarkare preview vs quality.

## Modelli installati (solo ufficiali)

`/opt/hermes/models/qwen-image-2.1/` (symlink controllati in `ComfyUI/models/*`):

| File | Dir ComfyUI | Size | Fonte |
|---|---|---|---|
| `qwen_image_2.1_bf16.safetensors` | `diffusion_models/` | 14.2 GB | `Comfy-Org/Qwen-Image-2.1` |
| `qwen3vl_8b_int8_convrot.safetensors` | `text_encoders/` | 9.4 GB | `Comfy-Org/Qwen-Image-2.1` |
| `qwen_image_2.1_vae_bf16.safetensors` | `vae/` | 0.7 GB | `Comfy-Org/Qwen-Image-2.1` |

Config/processor/tokenizer del repo `Qwen/Qwen-Image-2.1` NON serviti separatamente
(ComfyUI usa i propri tokenizer). License on redistribution: Qwen Research (vedi sopra).

## Workflow versionati (`/opt/hermes/media-workflows/`)

- `qwen/t2i.json` → preset `journey_image` (prompt, negative, seed, steps, resolution)
- `qwen/edit.json` → preset `journey_edit` (+ `input_images[]` fino a 4, multi-ref nativo)
- `qwen/rgba.json` → preset `journey_rgba` (output RGBA reale, alpha 0–255 verificato)
- `qwen/edit.json` riusato da `journey_product` (foto reali: geometria/materiale/venatura)
- `h3/README.json` → placeholder: t2v/i2v/first_last/reference DA FARE post-licenza

Nodi nativi ComfyUI (zero custom node): UNETLoader + CLIPLoader(`qwen_image`) +
VAELoader + TextEncodeQwenImage21 + KSampler(euler, cfg 1.0) + VAEDecode + SaveImage.

## Preset registry (manager `PRESETS`)

`journey_image|journey_edit|journey_rgba|journey_product` attivi;
`journey_video_preview|journey_video_quality|journey_video_first_last|journey_video_reference`
rifiutati con HTTP 409 finché H3 è gated (mai sostituzioni silenziose).

Job autosufficiente: `{preset, prompt, input_images[], parameters{...}}` →
il manager renderizza il workflow con `{{PLACEHOLDER}}` + `{{JOB_ID}}`, stagiona gli input
in `ComfyUI/input/<job>_inN.ext`, accoda. Qwen prepara TUTTO prima dell'unload.

## Output (`/opt/hermes/media-output/images|videos/<job_id>/`)

Master + `metadata.json` (job_id, created, preset, backend, model, parameters, runtime,
vram_peak, status, error, paths). Derivati FFmpeg automatici: WebP q80 + thumb 320px
(immagini, alpha preservato); H264+faststart, WebM, poster (video futuri).

## Hermes tools (per Qwen/agent via shell locale o HTTP 127.0.0.1:8643)

```bash
hermes-gpu submit workflow.json image     # oppure preset via API
curl -X POST :8643/jobs/image -d '{"preset":"journey_image","prompt":"...","parameters":{"seed":1}}'
hermes-gpu job <id>        # stato + result_paths quando done
curl :8643/status          # coda, preset attivo, VRAM, errori
```

Pattern: prepara tutto → submit → poll job → a Qwen ripristinato leggi i file locali.
Mai tenere HTTP aperto indefinitamente; mai dipendere da LLM durante l'offline.

## Prompting Journey (Marmeria)

- Immagini: soggetti reali (lastre, cucine, bagni, scale, CNC), luce studio, "preserve exact
  veining pattern and geometry" negli edit, negative corte (blurry, deformed, watermark).
- Video (futuro H3): comandi camera lenti — dolly-in/out, orbit, macro→wide reveal,
  slow push; evita morphing ("stable geometry, no deformation"); preferisci loopabili.
- Usa `journey_product` (edit con reference) per preservare materiale, mai T2I da zero
  quando esiste la foto reale.

## Benchmark misurati (2× RTX 5060 Ti 16GB, driver 595.91, torch cu128)

Strategia scelta: **single-GPU + CPU offload automatico ComfyUI** (diffusion bf16 +
TE int8). GPU1 resta libera. Niente sharding fragile.

| Caso | Load | Gen | VRAM0 peak | VRAM1 | RAM |
|---|---|---|---|---|---|
| T2I 1024 20step cold | ~4.5 min | — | 15.6 GB | idle | 8/30 |
| T2I 1024 20step warm | — | ~37 s | 15.7 GB | idle | — |
| Edit 1024 20step warm | — | ~39 s | 15.7 GB | idle | — |
| RGBA 1024 20step warm | — | ~37 s | 15.7 GB | idle | — |

## Benchmark H3 misurati (2× RTX 5060 Ti 16GB, driver 595.91, torch cu128)

Strategia scelta: DiT `fl2va_pruned_int8` (21GB) + TE `qwen3vl int8` (27GB, encode
una tantum con CPU offload) + VAE fp16 (5.2GB) + LoRA turbo 4-step per preview.
**Single GPU0 + CPU offload automatico** (GPU1 libera). Niente sharding fragile.
GGUF valutato e scartato (TE incompatibile con llama.cpp; arch DiT sconosciuta al nodo).

| Caso | Load | Gen 39f | Gen 124f | VRAM0 peak | VRAM1 | RAM/swap |
|---|---|---|---|---|---|---|
| Turbo 4-step 1344×768 warm | — | ~3 min | ~8 min | 15.8 GB | idle | 25GB + 7GB swap |
| Quality 25-step 1344×768 | — | ~21 min | ~66 min (estrapolato) | 15.8 GB | idle | idem |
| T2V turbo 39f | — | ~2 min | — | 15.8 GB | idle | idem |

Output: APNG master RGB 1344×768 + H264 + WebM + poster (derivati verificati non-vuoti).
TE 27GB su 30GB RAM + 8GB swap = thrash in encode; **64GB RAM toglierebbero lo swap**
(guadagno su load/encode, non su sampling). 124f quality oltre l'ora: usare turbo o
attese notturne; per finali valutare 12–16 step intermedi.

Output: PNG RGBA 1024 (master 1.1MB) + WebP + thumb. Edit preserva geometria via latent
reference; alpha icon 0–255 reale.

## Failure recovery (verificato)

Job invalido → `failed` → cleanup ComfyUI → restore Qwen → `LLM_READY` (testato live).
Retry `max_retries: 3` con backoff, poi `ERROR` con diagnostica; recovery tenta sempre
il restore. OOM live non provocato di proposito (picco misurato 15.7/16GB stabile);
il percorso OOM condivide restore+retry ed è code-reviewed.

## Update / rollback media

- Modelli: aggiungi file in `/opt/hermes/models/<nome>/`, symlink in ComfyUI, preset nuovo.
- ComfyUI: `git -C /opt/hermes/runtimes/comfyui/app pull` solo se serve (mai con job attivi).
- Rollback: `rollback-gpu-manager.sh` (repo `gpu-manager/`); poi solo sd-turbo+Tabby restano.
  Pesi Qwen-Image restano su disco (24GB) salvo cancellazione manuale verificata.

## H3 (MiniMax-H3) — test tecnici locali su override operatore

> L'operatore ha ordinato esplicitamente i test locali ignorando il gate.
> Resta valido: licenza community ESCLUDE UE/Italia — nessun uso produttivo/commerciale
> senza autorizzazione scritta MiniMax.

- Implementazione: **nativa ComfyUI** (`MiniMaxH3ImageToVideo`, reference, guide;
  CLIP `minimax` = Qwen3-VL). Solo `ComfyUI-GGUF` valutato e scartato (TE GGUF
  incompatibile con llama.cpp; arch DiT sconosciuta al nodo).
- Pesi ufficiali `Comfy-Org/MiniMax-H3`: DiT `fl2va_pruned_int8` (21GB) +
  TE `qwen3vl int8` (27GB, encode una tantum su CPU) + VAE video fp16 (5.2GB) +
  LoRA `fl2v_turbo_4step_768p` (2GB) per preview. Totale ~58GB su disco.
- Strategia VRAM misurata: **single GPU0 + CPU offload automatico** (GPU1 libera).
  Niente sharding fragile. RAM 30GB basta con swap in fase encode; 64GB toglierebbero
  lo swap-thrash (guadagno su cold start, non su sampling).
- Preset: `journey_video_preview` (turbo 4-step), `journey_video_quality` (25-step),
  `journey_video_first_last` (I2V + last frame); `journey_video_reference` TODO
  (mancano pesi ref2va). Nessuna sostituzione silenziosa.
- T2V/I2V/FL condividono lo stesso nodo (first/last opzionali). Audio mai generato
  (preset muted; VAE audio non scaricata).
