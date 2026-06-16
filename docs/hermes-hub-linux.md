# Hermes Gateway su Ubuntu/Linux

`hermes-hub` e' il launcher del **Hermes Gateway**: il servizio ponte/API server che espone Hermes Agent alle app Hermes Hub Windows/Android. Durante i test puo' puntare a LM Studio; sul server finale Ubuntu headless deve restare compatibile con vLLM.

Avvio manuale:

```bash
chmod +x scripts/hermes-hub-linux.sh
./scripts/hermes-hub-linux.sh
```

Default:

```text
Hermes Gateway API: http://0.0.0.0:8642/v1
API key: hermes-hub
Max iterations: gateway loop guard (`HERMES_MAX_ITERATIONS=120`)
Auxiliary local-only: true (`HERMES_AUXILIARY_LOCAL_ONLY=true`)
Native events: true (`HERMES_NATIVE_EVENTS=true`)
Raw event passthrough: true (`HERMES_RAW_EVENT_PASSTHROUGH=true`)
Native gateway patch: true (`HERMES_NATIVE_GATEWAY_PATCH=true`)
Provider: lmstudio
Inference: http://127.0.0.1:1234/v1
Config: ~/.hermes/config.yaml
Env: ~/.hermes/.env
Media roots: $HERMES_TERMINAL_CWD, ~/.hermes/cache, ~/.hermes/media
Video library: ~/.hermes/media/video
Hub state: ~/.hermes/hub_state.json
Hub memory: ~/.hermes/hub_memory.json
Hardware endpoint: GET /v1/hub/hardware
```

Il launcher prova a leggere il modello attualmente caricato in LM Studio da `/api/v1/models` e poi `/v1/models`.
Se lo trova, aggiorna `~/.hermes/config.yaml` e imposta `HERMES_INFERENCE_MODEL`.

Installazione come servizio user systemd:

```bash
mkdir -p ~/.config/systemd/user
cp scripts/hermes-hub-linux.sh ~/hermes-hub-linux.sh
cp scripts/patch-hermes-gateway-native.py ~/patch-hermes-gateway-native.py
chmod +x ~/hermes-hub-linux.sh
cp scripts/hermes-hub-linux.service ~/.config/systemd/user/hermes-hub.service
systemctl --user daemon-reload
systemctl --user enable --now hermes-hub.service
```

Se LM Studio gira su un altro host:

```bash
LM_STUDIO_BASE_URL=http://IP_DEL_SERVER:1234 ./scripts/hermes-hub-linux.sh
```

Profilo finale Ubuntu headless con vLLM:

```bash
HERMES_INFERENCE_PROVIDER=vllm \
VLLM_BASE_URL=http://127.0.0.1:8000 \
HERMES_INFERENCE_MODEL=nome-modello-vllm \
./scripts/hermes-hub-linux.sh
```

Oppure nel servizio systemd:

```ini
Environment=HERMES_INFERENCE_PROVIDER=vllm
Environment=VLLM_BASE_URL=http://127.0.0.1:8000
Environment=HERMES_INFERENCE_MODEL=nome-modello-vllm
```

Il servizio deve esporre sempre `http://SERVER:8642/v1` verso Hermes Hub e usare `API_SERVER_KEY=hermes-hub`, cosi' Android/Windows non cambiano configurazione quando si passa da Windows test a Ubuntu/vLLM. Il launcher imposta sia `API_SERVER_HOST=0.0.0.0`/`API_SERVER_PORT=8642` sia le variabili compat `HERMES_API_HOST`/`HERMES_API_PORT`: Android deve usare l'IP del server o l'IP Tailscale, non `127.0.0.1`.

`HERMES_AUXILIARY_LOCAL_ONLY=true` mantiene i task ausiliari dentro il provider locale e impedisce fallback esterni OpenRouter/Nous durante i test LM Studio o su vLLM headless.

`HERMES_NATIVE_GATEWAY_PATCH=true` applica automaticamente, prima dell'avvio, la stessa patch gateway usata su Windows al file Python `gateway/platforms/api_server.py` installato su Ubuntu. Il patcher e' idempotente, crea backup `api_server.py.bak-hermes-native-*` e compila il file con `py_compile`; se non trova il file si ferma invece di avviare un gateway non-native. Se Hermes upstream include gia' questi fix, il patcher non cambia nulla.

Se Hermes e' installato in un path non standard:

```bash
HERMES_GATEWAY_API_SERVER_PATH=/path/to/gateway/platforms/api_server.py ./hermes-hub-linux.sh
```

Hermes Hub usa il profilo **Hermes Native** come default: il client deve essere un thin client operativo, non un orchestratore. Il gateway deve quindi dichiarare in `/v1/capabilities`:

```text
features.hermes_native=true
features.native_responses=true
features.native_event_passthrough=true
features.raw_hermes_events=true
features.context_owner=hermes-agent
endpoints.hermes_native.path=/v1/hermes/native
```

`/v1/hermes/native` e' un alias stabile di `/v1/responses`: mantiene compat OpenAI Responses, ma rende esplicito che memoria, planning, tool loop, retrieval e contesto sono responsabilita' di Hermes Agent. In stream, il primo evento custom dopo `response.created` deve essere `event: hermes.native.protocol`; gli eventi custom `hermes.*` successivi vanno inoltrati ai client senza schiacciarli dentro solo testo o solo `function_call`.

Verifica patch native su Ubuntu:

```bash
python3 ~/patch-hermes-gateway-native.py --check
curl -H "Authorization: Bearer hermes-hub" http://SERVER:8642/v1/capabilities
```

La risposta deve includere `features.hermes_native=true`, `features.raw_hermes_events=true` e `endpoints.hermes_native.path=/v1/hermes/native`.

La sezione Hardware/Prestazioni usa `GET /v1/hub/hardware` protetto da `Authorization: Bearer hermes-hub`. Android e Windows fanno polling ogni secondo e mostrano CPU, RAM, swap, dischi, rete, processi e temperature quando il sistema le espone:

```bash
curl -H "Authorization: Bearer hermes-hub" http://SERVER:8642/v1/hub/hardware
```

Su Ubuntu headless installa `psutil` nell'ambiente Python usato da Hermes; per temperature reali abilita anche i sensori OS, ad esempio `lm-sensors`. Se i sensori non sono disponibili il gateway restituisce `temperature_support=no_sensors_reported`, ma CPU/RAM/dischi/rete restano utilizzabili.

La sezione Video usa una cartella watched-folder ufficiale esposta da `/health/detailed` e interrogabile da Android tramite `GET /v1/video/library`:

```text
video_library_path=~/.hermes/media/video
```

Quando Hermes crea/scarica/renderizza un video destinato a Hermes Hub, il file finale deve essere salvato dentro `HERMES_VIDEO_LIBRARY_PATH`. Android chiama `/v1/video/library`, riceve item con `media_url=/v1/media/...` e mostra il feed senza dipendere dalla chat; Windows puo' leggere la cartella locale quando gira sullo stesso PC e usare gli stessi metadata quando arrivano dal gateway.

Verifica media proxy:

```bash
curl -H "Authorization: Bearer hermes-hub" http://SERVER:8642/v1/capabilities
```

Per chat con file multimediali, `features` deve includere `media_proxy`, `media_register` e `visual_blocks_media_file`. Per native mode deve includere anche `hermes_native`, `native_event_passthrough` e `raw_hermes_events`. Se una build Hermes non li espone ancora, portare la patch gateway usata nei test Windows prima di usare Ubuntu/vLLM in produzione.

Hermes Hub 0.6.42 richiede anche questi endpoint gateway protetti da `Authorization: Bearer hermes-hub`:

```text
GET/PATCH /v1/hub/memory
GET/POST  /v1/hub/state
DELETE    /v1/hub/state/{state_id}
```

`/v1/hub/memory` espone i blocchi editabili dall'app: preferenze video, preferenze news, stile risposta, regole progetto e note generali. `/v1/hub/state` e' lo store leggero condiviso Android/Windows per feedback video/news, like/dislike, letto/non letto, progetto attivo e preferenze operative. Il launcher Linux salva di default in `~/.hermes/hub_memory.json` e `~/.hermes/hub_state.json`; puoi sovrascrivere con `HERMES_HUB_MEMORY_PATH` e `HERMES_HUB_STATE_PATH`.

Per permettere all'agente di mostrare file locali in Android/Windows, il gateway deve poterli pubblicare tramite proxy `/v1/media/...`. Configura `HERMES_MEDIA_ROOTS` con le cartelle da cui Hermes puo' condividere media. Il launcher Linux usa di default:

```bash
HERMES_MEDIA_ROOTS="$HERMES_TERMINAL_CWD:$HOME/.hermes/cache:$HOME/.hermes/media:$HOME/.hermes/media/video"
```

Se l'agente scrive `File: immagine.png` e il file esiste dentro una root media, il gateway deve convertirlo in `visual_blocks media_file` con `media_url=/v1/media/...`, non lasciare path locali nel testo.
