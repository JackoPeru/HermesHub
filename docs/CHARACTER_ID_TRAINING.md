# Hermes Character ID — Training (Musubi Tuner + Teacher Matching)

## Trainer (M4, installato)

- Upstream: `https://github.com/kohya-ss/musubi-tuner` (non patchato)
- Commit pin: `f8a1b03794a49239a3539015075f5123d6c07d66` (2026-09-27)
- Path: `/opt/hermes/character-id/trainer/src` (+ `SRC_HEAD`), venv
  `/opt/hermes/character-id/trainer/venv` (Python 3.12.3, torch 2.11.0+cu128 =
  stesso del venv ComfyUI), `TRAINER_VERSION` sul server
- Esecuzione: `PYTHONPATH=<src>/src` (il package vive in `src/src/musubi_tuner`)
- Docs upstream di riferimento: `docs/minimax_h3.md` (setup+ricette),
  `docs/minimax_h3_1f.md` (one-frame), `docs/minimax_h3_advanced.md`
  (meccanismo teacher + data contract)

## Base training (verificata presente)

- DiT: `minimax_h3_fl2va_pruned_int8_convrot.safetensors` (20 GB, riuso file attuale)
- Text encoder: `qwen3vl_32b_minimax_h3_int8_convrot.safetensors` (26 GB)
- Video VAE: `minimax_h3_video_vae_fp16.safetensors` (5 GB)
- Audio VAE: `minimax_h3_audio_vae_fp32.safetensors` (605 MB, scaricata M4 da
  `Comfy-Org/MiniMax-H3:vae/` — **sempre richiesta dal latent cache**, anche one-frame)

## Ricetta Character LoRA (validata upstream su set 20 immagini, rank 16)

Cache latenti (`--task ref2va --one_frame`, include target + subject references):

```bash
PYTHONPATH=$SRC/src $VENV/bin/python $SRC/minimax_h3_cache_latents.py \
  --dataset_config <char>/training/images.toml \
  --task ref2va --one_frame \
  --dit /opt/hermes/models/minimax-h3/minimax_h3_fl2va_pruned_int8_convrot.safetensors \
  --video_vae /opt/hermes/models/minimax-h3/minimax_h3_video_vae_fp16.safetensors \
  --audio_vae /opt/hermes/models/minimax-h3/minimax_h3_audio_vae_fp32.safetensors \
  --cache_seed 42 --skip_existing
```

Cache testo (`--task t2va --one_frame --teacher_conditions subject_ref`):

```bash
PYTHONPATH=$SRC/src $VENV/bin/python $SRC/minimax_h3_cache_text_encoder_outputs.py \
  --dataset_config <char>/training/images.toml \
  --task t2va --one_frame --teacher_conditions subject_ref \
  --text_encoder /opt/hermes/models/minimax-h3/qwen3vl_32b_minimax_h3_int8_convrot.safetensors \
  --skip_existing
```

Training (student sempre `--task t2va --one_frame --video_only`):

```bash
CUDA_VISIBLE_DEVICES=1 PYTHONPATH=$SRC/src $VENV/bin/accelerate launch \
  --num_cpu_threads_per_process 1 --mixed_precision bf16 \
  $SRC/minimax_h3_train_network.py \
  --dataset_config <char>/training/images.toml \
  --task t2va --one_frame --video_only \
  --dit /opt/hermes/models/minimax-h3/minimax_h3_fl2va_pruned_int8_convrot.safetensors \
  --network_module networks.lora_minimax_h3 --network_dim 16 --network_alpha 16 \
  --learning_rate 3e-4 --lr_warmup_steps 50 --max_train_steps 500 \
  --h3_teacher_matching --h3_teacher_conditions subject_ref \
  --h3_teacher_condition_sigma_min 0.15 \
  --h3_teacher_loss_mag_weight 0.5 --h3_teacher_loss_dc_weight 0.3 \
  --mixed_precision bf16 --gradient_checkpointing \
  --optimizer_type adamw8bit --blocks_to_swap 48 \
  --output_dir <char>/models/fl2va --output_name character \
  --save_every_n_steps 50 --save_last_n_steps 600
```

Checkpoint su disco: `<name>-step00000100.safetensors` (zero-padding Musubi).
`save_last` alto di proposito: con `save_every=50` un valore piccolo
cancellerebbe 100/250 durante il run (`remove_step_no`); i non-vincitori
vengono eliminati dopo la selezione eval. Verificato: bitsandbytes 0.50.2
funziona su Blackwell (micro-step CUDA ok).

Note vincolanti (dal data contract upstream):

- JSONL Musubi: `{"image_path", "caption", "references": [{"type": "image", "path"}]}`
  (il nostro `dataset.jsonl` usa già questo schema).
- Student caption = trigger + `<Subject 1>` + scena misurata, mai tratti identitari
  (l'auto-wrap definisce `<Subject 1>` sul teacher legandolo alla foto).
- `--h3_teacher_condition_sigma_max` resta 1.0 default (con 0.75 lo student non
  impara l'identita); niente timestep focus (campionamento uniforme).
- Teacher matching NON si combina con `h3_guidance_loss_scale`.
- Checkpoint: si salvano 100/250/500 (keep-last-3 + eval M7 sceglie il migliore).
- Rischio noto upstream (#1059): backward CUBLAS su checkpoint Ref2VA pruned INT8 —
  noi addestriamo su **FL2VA** (stessa famiglia ConvRot: se il backward fallisce,
  fallback documentato `--h3_convrot_int8_bwd bf16`).

## Smoke test (M4, in finestra idle: richiede GPU libera, Tabby scaricato)

1. Dataset sintetico 4 immagini 768px + jsonl con refs (meccanica, non identita).
2. Cache latenti + testo (misura tempo, RAM, swap).
3. Train 1 step (`--max_train_steps 1`): misura VRAM peak, errori CUDA/OOM.
4. Train 10 step: misura s/step, temperatura, stabilita.
5. Se 48 block swap lasciano >2 GB margine, prova 32/24 per trovare il minimo sicuro.
6. Ripristino immediato stato manager (AUTO + Tabby) dopo ogni run.

Risultati registrati in `docs/CHARACTER_ID_HARDWARE_ACCEPTANCE.md` (M16).

## Automazione (M6)

Il worker training gira sotto lock manager con stato `h3-character-train`:
claim esclusivo `O_EXCL` (due POST concorrenti: uno solo vince),
acquisisce lock, scarica Qwen/Comfy (`sudo -n`, stessa policy del manager),
verifica VRAM (gate 13 GB), cache+train con la ricetta sopra, watchdog
stallo 6h/tetto 14h, libera VRAM, ripristina stato.
Mentre gira, il worker manager e parcheggiato (niente restore che rubino VRAM).
Job `train` persistito (stati queued…ready/failed/cancelled), pid registrato,
cancel via terminazione pulita (M12), resume da checkpoint (M13-recovery).
