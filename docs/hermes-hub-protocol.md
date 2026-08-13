# Hermes Hub Protocol v1

Hermes Hub Protocol v1 è il contratto canonico tra client Hermes Hub, gateway e
Hermes Agent. Mantiene gli endpoint pubblici esistenti e aggiunge metadati
forward-compatible, senza trasferire a Hub memoria agente, planning, tool loop,
policy o retry di Hermes Agent.

Schema normativo: `config/hermes-hub-protocol.schema.json`.

## Event envelope

Ogni nuovo evento SSE può usare questo envelope:

```json
{
  "protocol_version": 1,
  "event_id": "evt_...",
  "sequence": 12,
  "request_id": "req_...",
  "correlation_id": "corr_...",
  "run_id": null,
  "type": "hermes.processing.progress",
  "payload": {},
  "source_type": "hermes-agent"
}
```

`request_id` identifica richiesta logica; `correlation_id` collega eventi della
stessa operazione anche quando attraversano gateway, Hermes Agent e tool loop.
Gli header HTTP equivalenti sono `X-Hermes-Request-Id` e
`X-Hermes-Correlation-Id`. `X-Request-Id` viene emesso come alias compatibile.
Gli ID contengono solo metadati; non includere token, prompt, transcript,
immagini o altri payload nei log.

`sequence` è monotona nel flusso/event source. `event_id` identifica il singolo
evento. `run_id` è opzionale. `source_type` distingue `hermes-agent`,
`hermes-gateway`, `hermes-hub` e `unknown`.

Client riconosce tipi noti; per tipi futuri conserva envelope e payload in forma
non interpretata, senza impedire la visualizzazione di `output_text`.

## Errori

Gli errori v1 espongono `code`, messaggio umano, stessi ID di correlazione e
`retryable`. Un retry client è consentito solo quando l'operazione non è stata
accettata o l'errore è esplicitamente retryable; richieste mutanti accettate non
vanno ripetute.

## Compatibilità

Gli endpoint `/v1/responses`, `/v1/hermes/native`, `/v1/chat/completions`,
`/v1/capabilities` e `/v1/hub/*` restano validi. Client v1 deve tollerare
risposte legacy prive di envelope e trattarle con il parser già esistente.

Verifica fixture:

```powershell
python scripts/validate-hermes-hub-protocol.py
```
