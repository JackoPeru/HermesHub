# Hermes Character ID — Hardware Acceptance (run reale v1, 2026-10-09/10)

Personaggio: Matteo, 38 foto (31 usabili primo giro, 38 dopo full-body),
25 train / resto validation. GPU singola esplicita (GPU1), RTX 5060 Ti 16 GB.

## Misure

| Fase | VRAM peak GPU1 | RAM peak | Swap | Tempo | Note |
|---|---|---|---|---|---|
| Cache latenti (ref2va) | ~4 GB | ok | 8 GB bastavano | ~5 min | VAE fp16, liscia |
| Cache testo (TE 32B) | OOM senza swap | 24 GB anon → oom-kill | serviti 39 GB | ~35 min | `--text_encoder_blocks_to_swap 48`, streaming CPU |
| Train 500 step | ~8 GB | ok | si | ~3 h (~16-24 s/step) | teacher matching, dim16, sdpa, h2d-only |
| Eval+benchmark (~50 gen) | ~6 GB | ok | si | ~7 h | collo: reload modelli per run + TE su CPU |

- Temperatura max: 58 °C. Mai throttling, mai errori CUDA.
- Output: `character_v1.safetensors` 298 MB (rank 16).
- Margine VRAM enorme (8/16 GB): alla prossima si puo scendere con
  `blocks_to_swap` per velocizzare (48 → 24 da provare).

## Requisiti sistema aggiornati

- Swap 39 GB totali (`/swapfile-hcid` 32 GB, permanente): senza, il load del
  text encoder 32B viene oom-killato a 24 GB anon. Con 8 GB originali non basta.
- RAM 30 GB sufficiente con swap; senza swap insufficiente per TE.

## Failure log (tutti sistemati nel codice)

1. Gate VRAM prima dell'unload → ordine invertito.
2. `--dit` inesistente nel latent cache → rimosso.
3. Processor Qwen3-VL senza rete (OFFLINE troppo aggressivo) → rete on per cache.
4. OOM TE 26 GB → swap CPU + 32 GB swapfile.
5. `--text_encoder_blocks_to_swap 60` > 50 layer → 48.
6. Scheduler CONSTANT rifiuta warmup → cosine.
7. Attention backend mancante → `--sdpa` (niente build Blackwell).
8. Race block swap cuda-after-wait → `--block_swap_h2d_only`.
9. Gate durate 5-15s in eval → `--allow_experimental_duration`.
10. OOM generate (TE+DiT insieme) → TE swap 50 + expandable_segments.

## Verdetto V1 (quality acceptance)

- Identity LoRA: 0.058 → 0.097 → **0.183** (soglia 0.5) — trend positivo ma
  insufficiente. Containment 0.86, temporal 0.93, preservation 0.5.
- Occhio umano: stesso tipo (occhiali, capelli corti, barba) ma barba
  visibilmente piu rada/chiara dell'originale (barba folta scura). Somiglianza
  debole, come dice il numero.
- Ref2VA benchmark: **0.65** — il motore reference funziona ORA.
- Engine raccomandato: `reference`. Stato: `needs_retrain` (corretto).
- Ipotesi principale: sotto-training (curva ancora in salita) + foto WhatsApp
  compresse. V2 proposta: 1000 step (cache riusate), resto invariato.
