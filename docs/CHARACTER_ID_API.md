# Hermes Character ID — API (`:8643/characters/...`)

Auth: Bearer manager-key (stessa chiave gateway). Letture: sola chiave.
Scritture (POST/PATCH/DELETE/train/cancel/retrain/export/import): chiave +
controllo-utente (localhost → 403: solo l'utente gestisce il manager).

## Personaggi

```text
GET    /characters                        lista summary
POST   /characters            {name}      crea draft (201, manifest)
GET    /characters/{id}                   manifest completo
PATCH  /characters/{id}       {name?, default_mode?, lora_strength?}
DELETE /characters/{id}                   cancella tutto (DB + file)
GET    /characters/{id}/status            {status, current_version, active_job}
```

## Foto e pipeline (M3)

```text
POST   /characters/{id}/images            multipart files[] (20-80 tot, jpg/png/webp, max 15MB)
GET    /characters/{id}/images            verdict accepted/warning/rejected + motivi
DELETE /characters/{id}/images/{image_id} rimuove file + riga
POST   /characters/{id}/analyze           202 {job_id} (worker tools-venv, CPU)
```

## Training e valutazione (M6-M7)

```text
POST   /characters/{id}/train             202 {job_id, version} (409 se GPU occupata / foto <20)
POST   /characters/{id}/retrain           = train su versione N+1 (vN mai sovrascritta)
POST   /characters/{id}/cancel            SIGTERM gruppo, restore tabby, stato coerente
POST   /characters/{id}/rollback          {version} (riattiva vN precedente + re-link LoRA)
GET    /characters/{id}/metrics           suite dataset/engine/eval
```

## Generazione (M10)

```text
POST   /characters/{id}/generate
  {prompt, identity_mode: auto|lora|reference, duration: 3|5|10,
   aspect_ratio, seed, additional_loras?[]}
  -> 202 {job_id, engine} (media queue normale; 409 se training in corso)
```

Il backend risolve `@Nome` (409 se punta a un altro personaggio), inietta il
trigger, valida lo stack LoRA (family fl2va/ref2va, max 4) e sottomette un
workflow t2v/reference con LoraLoaderModelOnly. Il frontend non costruisce workflow.

## Preview / export / import (M10/M14)

```text
GET    /characters/{id}/previews              nomi file
GET    /characters/{id}/previews/{name}       file autenticato (jpg/png/webp/mp4)
POST   /characters/{id}/export                {include_originals?} -> .hcid in cache/
GET    /characters/{id}/export/download       file .hcid
POST   /characters/import                     multipart .hcid (max 2GB) -> 201, niente retrain
```

## Manager (estensioni esistenti)

- `GET /status` include `character_training[]` (job train/evaluate/benchmark attivi).
- `POST /jobs/image|/jobs/video|/jobs/smart` → 409 `GPU occupata: training
  Character ID` mentre un training/eval gira (mai code parallele sulla GPU).
- Al boot: job character con worker morto → `failed` + personaggio `interrupted`
  (resume da checkpoint con `--resume`, mai `ready` presunto).

## Errori

`422` validazione, `404` personaggio/versione assente, `409` conflitto di stato
(job attivo, GPU occupata, foto insufficienti), `403` localhost su scritture.
Mai 501: ogni rotta documentata qui e implementata.
