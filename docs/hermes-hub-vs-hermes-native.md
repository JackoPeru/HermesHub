# Confine Hermes Hub / Hermes Agent

Hermes Hub e' una superficie operativa. Hermes Agent resta il cervello.

```text
Hermes Hub     input, rendering, stato UI, cache e archivio locale
Gateway Linux trasporto stabile, auth, media, store condivisi e compatibilita
Hermes Agent   contesto, memoria, planner, strumenti, retry agentici e artifact
```

## Regole

- Il client preferisce il protocollo dichiarato da `/v1/capabilities`.
- `hermes-native` usa Responses e conserva `conversation`/`previous_response_id`.
- Chat Completions e no-auth sono fallback espliciti, mai invisibili.
- Gli eventi `hermes.*` sconosciuti vengono conservati, non scartati.
- Il testo finale resta sempre disponibile anche se un Visual Block non e' renderizzabile.
- Le istruzioni UI devono essere minime; non devono sostituire il system prompt di Hermes.
- Il client non decide il modello remoto, non ricostruisce il tool loop e non simula memoria server.

## Compatibilita

Il gateway espone almeno:

- `POST /v1/responses` e alias `/v1/hermes/native`;
- `POST /v1/chat/completions` per compatibilita;
- `GET /v1/capabilities`;
- eventi SSE OpenAI Responses piu' eventi `hermes.*`;
- proxy media e store Hub.

Se una capability manca, la UI mostra protocollo e motivo del fallback. In strict native mode l'assenza del protocollo richiesto produce un errore esplicito.

## Criterio di revisione

Una nuova feature appartiene al client solo se riguarda esperienza utente, accesso a device, rendering o cache locale. Planning, memoria, retrieval, selezione strumenti e policy restano nel server.
