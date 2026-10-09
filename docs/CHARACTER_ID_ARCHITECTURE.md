# Hermes Character ID — Architettura

Stato: backend completo e deployato (M2-M10/M12-M14: storage, upload, pipeline,
train/eval/generate/cancel/export/import/recovery) + UI Android (M11).
Pendenti: smoke GPU 1/10-step + baseline H3 in finestra idle (M4/M15),
training reale (foto utente), acceptance, release.

## Scelta di integrazione (Fase 0 → M2)

Il backend Character ID **vive dentro `hermes-gpu-manager`** (porta 8643) come
pacchetto separato `gpu-manager/character_id/`, non come servizio duplicato:

- stessa auth Bearer (l'app usa già la chiave gateway come manager-key);
- stesso lock GPU / stati / code del manager (training M6 riusa la state machine);
- stesso deploy (`deploy-gpu-manager.sh` con backup + rollback) e stesso file di config;
- `manager.py` toccato in 3 punti soli (config default, import guardato, register
  guardato): se il pacchetto manca o solleva, il manager parte comunque.

Alternative scartata: servizio dedicato `:8644` + proxy hub. Avrebbe richiesto
nuova auth, nuovo deploy, nuovo systemd, nuova chiave nell'app. Rivalutare solo
se il training dovesse mai destabilizzare il manager.

## Componenti

```text
gpu-manager/character_id/
├── __init__.py      costanti: status, job status, mode, famiglie LoRA
├── validation.py    validatori puri (solo stdlib)
├── store.py         SQLite + filesystem (solo stdlib)
└── api.py           rotte FastAPI /characters/* (richiede fastapi)
```

### Storage

- Root: `/opt/hermes/character-id/` (config `character_id.root`; deviazione da
  `/var/lib/hermes/character-id`: dir assente sul server, riusata convenzione
  `/opt/hermes`). DB: `<root>/characters.db` (SQLite, WAL non richiesto in M2).
- Layout per personaggio `characters/<uuid>/` con `manifest.json` + 11 subdir
  da spec (`originals normalized training references validation
  models/{fl2va,ref2va} previews metrics logs cache`).
- Trigger token: `HCID_` + 6 hex da `secrets`, mai scelto dall'utente, mai il
  nome reale. Lezione dai LoRA character pubblici (es. Sydney/Wan2.1: trigger
  "woman" contamina tutte le donne nella scena — critica della community):
  token raro e unico, niente parole comuni.
- Identificatore filesystem = UUID v4. Il nome visualizzato non appare mai nei path.
- Permessi: dir `0700`, manifest `0600` (verificati su POSIX).
- Trigger token: `HCID_` + 6 hex maiuscole da `secrets`, UNIQUE in DB con retry.
- Slug: derivato dal nome, UNIQUE con suffisso `-2...`. Omonimi permessi (UUID distingue).

### Database (5 tabelle da spec)

`characters | character_assets | character_models | character_jobs | character_metrics`
+ indici per `character_id`. Job con stati
`queued…cancelled`, progress 0..1, pid, error: il reboot non perde lo stato
(test `test_job_lifecycle_persists` ricarica da nuova istanza).

### API (`:8643/characters/...`)

| Rotta | M2 |
|---|---|
| `GET /characters` | lista summary |
| `POST /characters {name}` → 201 | crea draft + manifest |
| `GET /characters/{id}` | manifest completo |
| `PATCH /characters/{id}` | name/default_mode/lora_strength (422 su campi ignoti) |
| `DELETE /characters/{id}` | cancella tutto: DB + dataset + modelli + log |
| `GET /characters/{id}/status` | status + active job |
| `POST .../images`, `DELETE .../images/{image_id}` | TODO M3 (501 onesto) |
| `POST .../analyze` | TODO M3 (501) |
| `POST .../train`, `.../cancel`, `.../retrain` | TODO M6/M12/M11 (501) |
| `GET .../metrics`, `.../previews` | TODO M7/M10 (501) |

Ruoli: letture = sola chiave; **scritture = chiave + controllo-utente**
(localhost → 403, come `/mode/*`: solo l'utente gestisce il manager, mai l'agente).

### Progress realtime

Poll `GET .../status` ogni 3s a schermo attivo (progress reali 0..1 dai job,
mai simulati). Niente WebSocket: `hub_notifications.json` e riservato all'hub
(scritture concorrenti senza lock rischierebbero corruzione); il poll basta
per training da ore (l'app dorme in background, lo stato persiste nel DB).

## Mappa fasi → codice (TODO oltre M2)

- M3 upload/preprocessing: `POST .../images` (multipart, 20–80 file, jpg/png/webp,
  max 15 MB, nomi UUID, 0600) + worker `worker_analyze.py` nel tools-venv
  (numpy/Pillow/opencv/insightface-buffalo_l, CPU-only): EXIF→RGB, sha256, dhash,
  duplicati, normalizzate JPEG, blur, volti+embedding+yaw/pitch, cluster identita,
  accepted/warning/rejected, split 80/20 stratificato, reference pack in symlink,
  caption col solo trigger, `training/dataset.jsonl` validato. Verificato live:
  verdict corretti, job fallito onesto sotto minimo, cleanup a cascata.
- M4 Musubi: venv isolato `/opt/hermes/character-id/trainer/`, `TRAINER_VERSION`.
- M5–M6 training: job `train` nel manager worker + stato `h3-character-train`
  (acquisisce lock, scarica Qwen/Comfy, verifica VRAM, avvia, ripristina).
- M7 eval: suite 8 prompt seed fissi + `character_metrics` + checkpoint selection.
- M8 Ref2VA: pesi e `reference.json` **già presenti** → solo integrazione Mode B.
- M9 benchmark + `recommended_engine`; M10 `models/fl2va/*.safetensors` caricato
  come LoRA nel workflow t2v (precedente: `astro_nsfw` in `loras/`) + Mode B/C
  routing nel backend (il frontend non costruisce workflow).
- M11 UI Android `Characters`; M12 cancel/recovery (killpg con verifica cmdline,
  boot-scan, lock stale); M13 multi-LoRA (`ComfyUI-H3-PowerLoraStack` assente:
  chain native `LoraLoaderModelOnly` + validazione family/strength/file, max 4);
  M14 export `.hcid` (zip-slip guard); M15 acceptance (baseline in `CHARACTER_ID_BASELINE.md` §7).
- Limiti noti V1: caption/diversita geometriche (niente VLM locale); recovery =
  ripartenza pulita con cache presenti (niente `--resume` presunto).
- Futuro hybrid `Ref2VA + Ref2VA-LoRA`: `manifest.models.ref2va` già previsto,
  sempre `null` in V1. Mai applicare il LoRA FL2VA sopra Ref2VA.
