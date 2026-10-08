# Hermes Character ID — Troubleshooting

## `POST .../train` → 409

| Dettaglio | Causa | Fare |
|---|---|---|
| `foto insufficienti` | <20 utilizzabili | Aggiungi foto, ri-analizza |
| `GPU occupata: training…` | lock/job attivo | Attendi o `/cancel` |
| `GPU occupata: media job…` | coda media non vuota | Attendi fine code |
| `trainer non installato` | venv assente | setup M4 sul server |
| `personaggio in stato X` | analizza prima | Completa wizard fino a `ready_to_train` |

## Training fallito (log `logs/train-vN-*.log`)

- **OOM / CUDA out of memory**: riduci `blocks_to_swap`? No: prima verifica che
  Qwen/Comfy fossero davvero scarichi (`nvidia-smi` nel log di avvio worker);
  poi prova `--h3_convrot_int8_bwd bf16` (rischio noto upstream #1059).
- **Cache fallita**: controlla `dataset.jsonl` (path validi?) e audio VAE presente.
- **VRAM insufficiente al gate**: il worker rifiuta sotto 13 GB liberi — mai
  forzare, attendi GPU libera.
- Il manager ripristina Tabby da solo al boot; il job resta `failed` con errore,
  il personaggio va in `failed` (retry) o resta in `validating` solo se l'eval
  e stata concatenata e gira.

## Eval `needs_retrain`

Soglie V1: identity ≥0.5, containment ≥0.5, preservation ≥0.5. Sotto soglia:
controlla `metrics/eval-vN.json` (quale asse), aggiungi foto dell'asse debole
(profili, luci, espressioni), retrain vN+1 (vN resta usabile + rollback).

## Generate 422 / workflow rifiutato

- `family incompatibile`: LoRA ref2va nello stack FL2VA (o viceversa) — mai
  mescolare (il LoRA FL2VA sopra Ref2VA e vietato).
- `nessuna reference utilizzabile`: reference pack vuoto (analyze senza volti?).
- `placeholder irrisolti`: template workflow modificato a mano — ripristina da repo.

## Boot / crash durante training

Al boot il manager marca job con worker morto → `failed`, personaggio
`interrupted`, checkpoint validi conservati in `models/fl2va/vN/`. Retrain con
`--resume` automatico se `last_state` esiste, altrimenti ripartenza pulita.

## Spazio disco

Dataset <5 GB/personaggio; cache Musubi la parte grossa (`training/cache`,
rigenerabile con `--skip_existing`). Modelli/fl2va conservano gli step
100/250/500: tieni solo il vincitore (`character_vN`) + quello da cui deriva.

## Reinstallazione trainer

```bash
rm -rf /opt/hermes/character-id/trainer/venv
python3 -m venv /opt/hermes/character-id/trainer/venv
# ... pacchetti come TRAINER_VERSION, commit pin in SRC_HEAD
```

## Rollback manager

`gpu-manager/scripts/deploy-gpu-manager.sh` fa backup datato (inclusa
`character_id/`) + rollback automatico su verify fallita. Dati personaggi
(`/opt/hermes/character-id/characters.db` + `characters/`) mai toccati dal deploy.
