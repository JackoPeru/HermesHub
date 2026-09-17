# Hermes Agent upstream fixture

`hermes-agent-v2026.7.7.2-api_server.py` is an unmodified snapshot of
`gateway/platforms/api_server.py` from Nous Research Hermes Agent tag
[`v2026.7.7.2`](https://github.com/NousResearch/hermes-agent/blob/v2026.7.7.2/gateway/platforms/api_server.py)
(upstream package version `0.18.2`).

- Retrieved: 2026-07-13
- SHA-256: `d819f04f4f3a7d2c7f2d3b5befb13aa50dc0df7ca8416909f65f6d214e8c7b66`
- Purpose: deterministic, offline regression coverage for the idempotent Hermes Hub gateway patcher
- License: MIT; see `LICENSE.hermes-agent` in this directory

Keep the file byte-for-byte unchanged. The regression test verifies this digest before applying three patch passes in memory.

## Contratto moderno v2026.9.14 (leggero, mantenibile)

Snapshot completi `api_server.py` oltre v2026.7.7.2 non vendono copiati: troppo pesanti.
Il contratto attuale (stabile v2026.9.14 / v0.21.3, commit `345cd2b`, docs 2026-09-17)
vive in fixture JSON minime verificate dai docs ufficiali:

- `hermes-agent-v2026.9.14-capabilities.json` — `/v1/capabilities` completa
- `hermes-agent-v2026.9.14-model-options.json` — `/api/model/options`
- `hermes-agent-v2026.9.14-sessions.json` — Sessions API + stream + keepalive
- `hermes-agent-v2026.9.14-runs.json` — Runs API + steer/stop/approval + pending_steer

Verificate da `tests/test_hermes_modern_contract.py` e dai test Kotlin
`HermesModernApiTest`. Aggiornare queste fixture quando upstream cambia il contratto.
