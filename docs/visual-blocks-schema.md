# Hermes Visual Blocks v1

Hermes Visual Blocks e' il contratto per spiegazioni visuali sicure dentro Hermes Hub. Il server produce testo autosufficiente piu' blocchi tipizzati; i client Windows e Android renderizzano v1 e conservano payload futuri come `unknown_block`.

## Regole generali

- `output_text` e' sempre obbligatorio, completo e leggibile anche senza blocchi.
- `visual_blocks_version` e' `>= 1`.
- `visual_blocks` pesa al massimo 500 KB serializzato e contiene al massimo 20 blocchi.
- Client v1 renderizza tipi noti e conserva tipi/versioni future come `unknown_block` JSON compatto.
- `title` e `caption` sono testo plain, non Markdown.
- Markdown subset unico: paragrafi, H1-H3, liste, bold/italic, link `http/https`, inline code, fenced code. No HTML raw, no math, no tabelle Markdown, no Mermaid inline.
- History rimandata a Hermes contiene solo testo umano, mai JSON dei blocchi.

## Client metadata

```json
{
  "metadata": {
    "client": "hermes-hub",
    "visual_blocks": {
      "min_supported_version": 1,
      "max_supported_version": 1,
      "forward_compatible": true,
      "mode": "auto"
    }
  }
}
```

`mode`: `never` disabilita hard; `auto` lascia decidere Hermes; `always` preferisce blocchi quando ragionevole, senza forzarli se inutili.

## Capabilities

`GET /v1/capabilities` dovrebbe includere limiti machine-readable:

```json
{
  "visual_blocks": {
    "min_supported_version": 1,
    "max_supported_version": 1,
    "types": ["markdown", "code", "table", "chart", "diagram", "image_gallery", "media_file", "callout", "unknown_block"],
    "max_blocks": 20,
    "max_payload_kb": 500,
    "max_table_columns": 12,
    "max_table_rows": 100,
    "max_chart_series": 8,
    "max_chart_points_per_series": 200,
    "max_diagram_bytes": 512000,
    "max_gallery_images": 12,
    "max_gallery_image_bytes": 2097152,
    "generation": "structured_output_json_schema"
  }
}
```

## Tipi supportati

Ogni blocco ha `id`, `type`, `title?`, `caption?`. `id` e' stabile dentro la response. `type` sconosciuti vengono convertiti in `unknown_block` e mostrati come JSON compatto/debug.

### markdown

```json
{ "id": "b1", "type": "markdown", "title": "Spiegazione", "text": "## Flusso\n- Passo 1" }
```

### code

```json
{ "id": "b2", "type": "code", "language": "json", "filename": "response.json", "code": "{ \"ok\": true }", "highlight_lines": [1] }
```

Linguaggi: `plaintext`, `mermaid`, `powershell`, `bash`, `json`, `xml`, `csharp`, `kotlin`, `python`, `javascript`, `typescript`, `sql`, `yaml`, `markdown`.

### table

```json
{
  "id": "b3",
  "type": "table",
  "columns": [
    { "key": "name", "label": "Nome", "align": "left", "format": "text", "sortable": true },
    { "key": "score", "label": "Score", "align": "right", "format": "number" }
  ],
  "rows": [{ "name": "Hermes", "score": 92 }]
}
```

Limiti: 12 colonne, 100 righe. Formati: `text`, `number`, `percent`, `currency`, `date`, `code`.

### chart

```json
{
  "id": "b4",
  "type": "chart",
  "chart_type": "line",
  "x_label": "Mese",
  "y_label": "Richieste",
  "unit": "req",
  "summary": "Le richieste crescono da 42 a 180 tra gennaio e giugno.",
  "series": [
    { "name": "Windows", "points": [{ "x": "Gen", "y": 42 }, { "x": "Giu", "y": 180 }] }
  ]
}
```

`summary` e' obbligatorio. Tipi: `bar`, `line`. Limiti: 8 serie, 200 punti per serie.

### diagram

```json
{
  "id": "b5",
  "type": "diagram",
  "source_format": "mermaid",
  "source": "graph TD; A-->B",
  "rendered_media_url": "/v1/media/diagram-abc.png",
  "alt": "Diagramma del flusso A verso B"
}
```

Il client renderizza solo media proxy Hermes `png/jpeg/webp`. Se il media manca o viene rifiutato, mostra `source` come code block `mermaid`.

### image_gallery

```json
{
  "id": "b6",
  "type": "image_gallery",
  "layout": "grid",
  "images": [{ "media_url": "/v1/media/img-1.webp", "alt": "Logo Hermes", "caption": "Logo corrente" }]
}
```

Solo media proxy Hermes. Max 12 immagini. `alt` obbligatorio.

### media_file

```json
{
  "id": "b7",
  "type": "media_file",
  "title": "Clip finale",
  "media_url": "/v1/media/clip-finale.mp4",
  "media_kind": "video",
  "mime_type": "video/mp4",
  "filename": "clip-finale.mp4",
  "size_bytes": 48234421,
  "duration_ms": 94000,
  "thumbnail_url": "/v1/media/clip-finale-thumb.webp",
  "alt": "Video finale generato da Hermes",
  "caption": "Pronto per revisione."
}
```

Serve per un singolo asset condiviso in chat: `image`, `video`, `audio` o `document`. `media_url` e `thumbnail_url` devono essere proxy Hermes o URL same-host ammessi dal client. Il client mostra preview se possibile e azioni apri/scarica/copia link.

### callout

```json
{ "id": "b7", "type": "callout", "variant": "warning", "title": "Attenzione", "text": "Verifica API key prima di esporre il server fuori LAN." }
```

Varianti: `info`, `warning`, `error`, `success`.

### unknown_block

```json
{
  "id": "future-1",
  "type": "unknown_block",
  "title": "Blocco Hermes non renderizzato: task_graph",
  "raw_json": "{ \"id\": \"future-1\", \"type\": \"task_graph\" }"
}
```

Usato dal client quando riceve tipi futuri o non validi. Non e' un target primario per Hermes: e' fallback forward-compatible per non perdere artifact.

## Generazione Hermes

Ordine raccomandato: structured output con JSON Schema enforcement; tool interno unico `emit_visual_blocks({ "blocks": [...] })`; prompt few-shot + validator + un retry repair. Se validazione fallisce, Hermes scarta i blocchi e conserva `output_text`.

## Tipi generati

I tipi C# e Kotlin devono derivare da `config/visual-blocks.schema.json`. Dopo ogni generazione quicktype va controllato che i discriminator e gli enum restino semanticamente uguali su entrambe le piattaforme:

- discriminator blocchi: `type`;
- valori `type`: `markdown`, `code`, `table`, `chart`, `diagram`, `image_gallery`, `media_file`, `callout`, `unknown_block`;
- enum chart: `bar`, `line`;
- enum callout: `info`, `warning`, `error`, `success`;
- enum visual mode: `auto`, `always`, `never`.

Quicktype puo' nominare enum in modo diverso tra C# e Kotlin; il wire value JSON resta l'unica sorgente di verita. Se i nomi generati divergono, aggiungere adapter/annotation prima di toccare i renderer.

Verifica locale:

```powershell
.\scripts\verify-visual-blocks-contract.ps1
```

## Sicurezza media

- No HTML, no JS, no SVG client-side.
- Diagrammi: Mermaid source + media proxy pre-renderizzato.
- Client blocca `file://`, `data:`, URL assoluti esterni non proxy/same-host.
- Media proxy controlla content-type, max byte, timeout e redirect limit.
- Video/audio/documenti vanno esposti come URL scaricabili via Hermes, non come path filesystem locali.
- Diagram max 500 KB; gallery image default max 2 MB.

## Fixture contrattuale

`tests/contracts/visual-blocks-fixture.json` copre tutti i tipi v1. Lo script
`scripts/verify-visual-blocks-contract.ps1` verifica schema, fixture e wire
value condivisi dai due client. Non esiste ancora un test golden screenshot;
la documentazione non deve presentarlo come controllo attivo.
