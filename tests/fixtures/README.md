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
