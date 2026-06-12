# AGENTS.md

## Obiettivo

Creare app chat stile ChatGPT per comunicare con home-server che ospita Hermes Agent.

Target:

- App desktop Windows.
- App Android.
- UI moderna chatbot: dark premium operator console, composer largo, menu `+`, modalita `Chat`/`Agente`.
- Nome visibile app: `Hermes Hub`.
- Compatibilita Android: `applicationId` resta `com.nemoclaw.chat`.
- Backend primario: Hermes Agent API Server su `http://hermes.local:8642/v1`.
- Direzione nuova: Hermes Hub e' client operativo per Hermes Agent, non console del vecchio backend.

## Regola Git

Non fare commit o push su GitHub a meno che Matteo lo chieda esplicitamente.

Repo remoto:

```text
https://github.com/JackoPeru/app-interazione-nemoclaw.git
```

Branch usato:

```text
main
```

Ultimo push release fatto su richiesta utente:

```text
v0.6.64 Release Hermes Hub 0.6.64 Windows updater test
```

## Regola Memoria

Aggiornare questo file ogni volta che cambia qualcosa di importante nel progetto:

- obiettivo o scope;
- stato Windows/Android;
- decisioni tecniche;
- impostazioni default;
- comandi build/test;
- regole Git;
- bug noti;
- TODO/prossimi passi;
- preferenze utente.

Non lasciare `AGENTS.md` obsoleto dopo modifiche rilevanti.

## Release Corrente

Hermes Hub 0.6.64 (Windows + Android):

Nota blocco Android firma 2026-06-12:
- Android installato sul telefono e release storiche `0.6.46-0.6.53` usano certificato debug SHA-256 `6a676291c8f11e4e6cc6d4c38ed3da8faab2c30aea94d152cd77b2745f45c02d`.
- Il keystore presente su questo PC (`%USERPROFILE%\.android\debug.keystore`) firma invece SHA-256 `5e22fa7ca9bd9c8c39cb00788061ffbb7e254629283d3876c6d5274f6858494f`.
- Android rifiuta update con errore "pacchetto in conflitto con un pacchetto esistente" quando firma diversa; non e' bypassabile via codice app o APK.
- Finche' non si recupera il keystore privato `6a676...`, non pubblicare APK Android come aggiornamento compatibile. Opzioni reali: recuperare vecchio `%USERPROFILE%\.android\debug.keystore` dal PC/backup che firmava 0.6.46-0.6.53, oppure fare migrazione dati + reinstall una tantum.

Release 0.6.64:
- Release test per verificare updater Windows corretto installato manualmente in 0.6.63: solo bump versione sopra il nuovo helper update.
- Release bump: Windows/AdminBridge `0.6.64`, Android `versionName 0.6.64`, `versionCode 76`.

Hermes Hub 0.6.63 (Windows + Android):

Release 0.6.63:
- Fix updater Windows: install MSIX passa a helper esterno `.cmd` + `.ps1` in `%LOCALAPPDATA%\ChatClaw\updates`, aspetta la chiusura del processo app, usa `Add-AppxPackage -ForceUpdateFromAnyVersion -ForceApplicationShutdown -ForceTargetApplicationShutdown`, fa 3 retry, logga in `install-msix-update.log` e se fallisce apre fallback App Installer UI.
- Release bump: Windows/AdminBridge `0.6.63`, Android `versionName 0.6.63`, `versionCode 75`.

Hermes Hub 0.6.62 (Windows + Android):

Release 0.6.62:
- Preset personale plug-and-play Tailscale/LAN: Hermes Hub resta su `hermes-native`, ma `Strict native mode` e' OFF di default per evitare blocchi quando il gateway Hermes non e' perfettamente allineato; fallback Responses/Chat Completions/no-auth restano visibili in UI.
- Auth client resa permissiva per uso personale: Android e Windows provano API key salvata, `hermes-hub` e no-auth anche sul path native/stream.
- Auto-discovery endpoint Hermes: Windows e Android provano automaticamente `http://hermes.local:8642/v1`, `http://hermes:8642/v1`, `http://hermes-hub:8642/v1`, `http://hermeshub:8642/v1`, `http://home-server:8642/v1`, `http://server:8642/v1`; Windows salva il primo endpoint raggiungibile.
- Migrazione settings locale: se URL salvato e' vuoto/localhost o strict native era ON, la nuova app normalizza a preset Hermes plug-and-play.
- Release bump: Windows/AdminBridge `0.6.62`, Android `versionName 0.6.62`, `versionCode 74`.
- Nota Android release 0.6.62: la release GitHub deve includere asset `.apk`, altrimenti il bottone `Scarica` non compare in app. Se Android rifiuta installazione per firma diversa dalla build gia' installata, disinstallare una volta la vecchia app e installare l'APK 0.6.62; il problema e' keystore, non updater UI.

Hermes Hub 0.6.61 (Windows + Android):

Decisione Hermes Native:
- Hermes Hub usa `preferredApi=hermes-native` come default su Windows, Android e config.
- `Strict native mode` e' ON di default: niente fallback silenziosi a Chat Completions/no-auth quando il path native fallisce.
- Chat e Agente usano Responses/native transport con contesto delegato a Hermes Agent; Hermes Hub resta thin operational client.
- Gateway Hermes locale dichiara `hermes_native`, `native_event_passthrough`, `raw_hermes_events`, `context_owner=hermes-agent`, alias `POST /v1/hermes/native` e primo evento SSE `hermes.native.protocol`.
- Ubuntu/headless usa `scripts/patch-hermes-gateway-native.py` tramite `scripts/hermes-hub-linux.sh` per applicare lo stesso contratto native al gateway Python installato su Linux prima dell'avvio.
- Android/Windows conservano raw Hermes events e Visual Blocks futuri come `unknown_block`.

Decisione auth client:
- Hermes Hub usa API key lato app. Default: `hermes-hub`.
- Android e Windows inviano prima `Authorization: Bearer <API key salvata>`; se Hermes risponde `401 invalid_api_key`, provano `hermes-hub` e poi no-auth solo come fallback compat.
- Settings espone campo `API key Hermes` e azione `Ripristina API key`.
- I segreti provider/modello restano lato Hermes/server. AdminBridge resta separato e puo' continuare a usare `CHATCLAW_ADMIN_TOKEN`.

Decisione latenza Chat:
- Modalita `Chat` e `Agente` su Windows e Android partono da Hermes Native/Responses.
- Default `API preferita` e' `hermes-native`.
- Chat Completions resta solo fallback compat se `Strict native mode` e' disattivato.
- Android Responses fallback ridotto a 2 tentativi con pausa 500ms.

Decisione ingest Video:
- Sezione Video passa a modello `watched-folder`: cartella non viene decisa dal client ma da Hermes, che la annuncia via payload server (`/health/detailed`, campo tipo `video_library_path`).
- Windows Video page ora legge automaticamente i file video locali da quella cartella e li mostra come feed/player con UI piu' vicina a YouTube.
- Feedback video diventa per-file: utente puo' lasciare note rapide/editoriali; app le salva localmente e le invia a Hermes chiedendo di trattarle come memoria editoriale condivisa quando appropriato.
- Windows e Android sincronizzano automaticamente `Cartella video Hermes` dal server quando Hermes la espone; finche' manca, UI mostra stato "in attesa di sync server".

Decisione media chat:
- Visual Blocks v1 esteso con `media_file`: Hermes Agent puo' condividere singoli file multimediali in chat (`image`, `video`, `audio`, `document`) tramite proxy sicuro `/v1/media/...`, con `thumbnail_url`, filename, MIME, dimensione e durata quando disponibili.
- Android e Windows hanno UI dedicata per `media_file`: anteprima immagine/thumbnail, metadata compatti, azione `Apri` e `Copia link`; URL non proxy (`file://`, `data:`, path locali diretti o host non sicuri) vengono rifiutati.
- Istruzioni/metadata client aggiornati per chiedere all'agente `image_gallery` per gallerie immagini e `media_file` per asset singoli multimediali.

Decisione modalita vocale:
- Aggiunta modalita `Voce` suggestiva come placeholder visuale per futura voce reale.
- Android: tab `Voce` apre WebView fullscreen senza bottom nav e carica scena Three.js/WebGL offline da asset; tap touch assembla/disassembla Hermes; back Android esce dalla modalita.
- Windows: sidebar e slash `/voce` aprono `VoicePage`; shell/sidebar/top bar vengono nascosti durante la pagina; tap singolo assembla Hermes, doppio tap disassembla, Esc torna indietro se disponibile.
- Visual style: particelle orange holographic su fondo nero, idle random spaziale, assemblaggio rapido in figura Hermes/deity con ali/elmo e micro-fluttuazione continua.

Terminologia gateway:
- Il comando `hermes-hub` avvia **Hermes Gateway**: servizio ponte/API server che espone Hermes Agent alle app Hermes Hub Windows/Android e inoltra inferenza a LM Studio nei test o vLLM nel setup finale.
- La versione Linux/headless deve restare aggiornata e funzionante: `scripts/hermes-hub-linux.sh`, `scripts/hermes-hub-linux.service` e `docs/hermes-hub-linux.md` devono supportare Ubuntu headless + vLLM, con API stabile `http://SERVER:8642/v1` e API key default `hermes-hub`.

Release 0.6.61:
- UI refresh personale: Windows Chat rimuove larghezze fisse su lista/composer, usa suggerimenti compatti e lascia spazio ai controlli contesto; Windows Video passa a layout adattivo due colonne/stack verticale e feedback rapido a griglia; Windows Settings separa Connessione Hermes, Avanzate e Memoria.
- Android UI refresh: bottom nav ridotta a Chat/Runs/Voce/Video/Profilo; Profilo contiene scorciatoie per Hermes, News, Impostazioni, Archivio e Jobs; raw Hermes events non compaiono piu' nella chat normale; Visual Blocks markdown usano il renderer markdown condiviso; Settings mostra base subito e Avanzate collassate.
- Include hotfix Windows updater `Installa e riavvia` 0.6.60 e hotfix Android/gateway per stream Hermes vuoto con chunk role-only.
- Release bump: Windows/AdminBridge `0.6.61`, Android `versionName 0.6.61`, `versionCode 73`.

Release 0.6.59:
- Release test per verificare update interno Windows dalla `0.6.58`: solo bump versione, stesso helper detached.
- Release bump: Windows/AdminBridge `0.6.59`, Android `versionName 0.6.59`, `versionCode 72`.

Release 0.6.58:
- Fix update Windows: l'installer MSIX ora viene avviato da script PowerShell scritto su disco e lanciato con `UseShellExecute=true`, quindi resta vivo dopo la chiusura dell'app; log in `install-msix-update.log`.
- Release bump: Windows/AdminBridge `0.6.58`, Android `versionName 0.6.58`, `versionCode 71`.

Release 0.6.57:
- Fix update Windows: dopo installazione MSIX l'app viene rilanciata automaticamente via AUMID (`shell:AppsFolder\...!App`) con retry fino a 10 tentativi.
- Release bump: Windows/AdminBridge `0.6.57`, Android `versionName 0.6.57`, `versionCode 70`.

Release 0.6.56:
- Release test per verificare updater Windows corretto dalla versione installata `0.6.55`: solo bump versione, stesso codice funzionale.
- Release bump: Windows/AdminBridge `0.6.56`, Android `versionName 0.6.56`, `versionCode 69`.

Release 0.6.55:
- Fix update Windows MSIX: `Installa e chiudi` non usa piu' `Launcher.LaunchFileAsync` per `.msix` per evitare errore App Installer "Error in parsing the app package"; ora avvia install background con `Add-AppxPackage -ForceUpdateFromAnyVersion`, poi chiude/rilancia app.
- Release bump: Windows/AdminBridge `0.6.55`, Android `versionName 0.6.55`, `versionCode 68`.

Release 0.6.54:
- Release test update interno Windows: include fix Android build D8, stabilita timeout stream/API, `VoiceModeScreen` estratto, backup locale Android/Windows, packaging MSIX firmato e updater Windows `Installa e chiudi`.
- Release bump: Windows/AdminBridge `0.6.54`, Android `versionName 0.6.54`, `versionCode 67`.

Release 0.6.53:
- Hotfix Android Voce: fix schermo nero WebView quando HTML parte prima del layout valido usando fallback `window.innerWidth/innerHeight` e resize ritardati.
- Hotfix Android Voce: su Android i tratti Hermes non usano migliaia di `TubeGeometry`; renderer mobile usa `LineSegments` leggero e costruisce i tratti dopo il primo frame per evitare blocco GPU/memoria.
- Hotfix Android Voce: aggiunto overlay errore WebGL/JS e log console WebView `HermesVoiceWebView` in Logcat.
- Release bump: Windows/AdminBridge `0.6.53`, Android `versionName 0.6.53`, `versionCode 66`.

Post-audit 2026-06-11:
- Fix build Android debug: D8 falliva su coroutine stream generata da `suspend inline openSseStream`; `openSseStream` ora non e' inline e `:androidApp:assembleDebug` passa.
- Stabilita stream/API: SSE mantiene read/call timeout illimitati ma con connect/write timeout finiti; client API Android non-stream usa timeout finiti.
- Manutenibilita: `VoiceModeScreen` Android estratto da `MainActivity.kt` in file dedicato e WebView limitata alla scena asset locale.
- Backup locale: aggiunto export JSON per settings/API key/conversazioni su Android e Windows.
- Update Windows personale: packaging consigliato MSIX firmato self-signed, generato da `scripts/package-windows-msix.ps1`; updater Windows preferisce `.msix/.appinstaller`, scarica da GitHub Releases, apre installer e chiude Hermes Hub per completare install/update.

Release 0.6.52:
- Android modalita `Voce`: renderer Compose/Canvas sostituito da WebView fullscreen che carica `src/NemoclawChat.Android/app/src/main/assets/hermes_scene/orange_particles_3d.html`.
- Scena Voce Android porta asset offline: `three.min.js`, `reference_figure.png`, cartella `Hermes particelle/` e shape export `hermes-particles-shape.json`.
- HTML patchato per non usare CDN, usare `hermes-particles-shape.json` come forma Hermes bundled default (`localStorage` v2), mantenere editor/import/export/reset e toggle touch equivalente a `Space`.
- Release bump: Windows/AdminBridge `0.6.52`, Android `versionName 0.6.52`, `versionCode 65`.

Release 0.6.51:
- Android modalita `Voce`: recepito prompt tecnico Three.js come direzione visiva, ma portato nello stack nativo Compose/Canvas invece di introdurre React/WebGL nel progetto.
- Standby ora non mostra forma umana: pochi nodi/particelle arancioni su sfondo nero con gradiente radiale, drift lento, linee discrete tra nodi vicini e molto spazio vuoto.
- Assemble: particelle non-standby entrano gradualmente verso Hermes, cosi si vede movimento di aggregazione invece di figura gia' presente in idle.
- Performance: standby visibile resta su sottoinsieme di nodi; target Hermes ridotto rispetto alla 0.6.50 per evitare wallpaper/gaming e carico inutile.
- Release bump: Windows/AdminBridge `0.6.51`, Android `versionName 0.6.51`, `versionCode 64`.

Release 0.6.50:
- Android modalita `Voce` rifatta dopo feedback negativo su 0.6.49: fullscreen reale con system bars nascoste, animazione frame-by-frame via `withFrameNanos`, doppio tap toggle assemble/disassemble, particelle idle piu' lente e visibili nello spazio.
- Renderer Hermes Android molto piu' denso: 2500+ particelle target, curve glow multi-layer per volto, elmo, ali, busto e disco spalla; particelle interne per volto/elmo/busto invece di soli punti su linee.
- Nota qualita': questo resta Canvas 2D procedurale, non un modello 3D/asset bitmap pari all'immagine reference; per parita' fotografica serve generare/importare asset o mesh/shader dedicati.
- Release bump: Windows/AdminBridge `0.6.50`, Android `versionName 0.6.50`, `versionCode 63`.

Release 0.6.49:
- Hotfix release Android: nuovo `versionCode 62`/`versionName 0.6.49` per superare asset/cache rotta della 0.6.48.
- Asset Android release va firmato con debug keystore compatibile con 0.6.47 (`C=US, O=Android, CN=Android Debug`, SHA-256 cert `6a676291c8f11e4e6cc6d4c38ed3da8faab2c30aea94d152cd77b2745f45c02d`) prima dell'upload GitHub, non caricare `app-release-unsigned.apk`.
- Windows/AdminBridge bump a `0.6.49`; funzionalita Voce resta quella introdotta in 0.6.48.

Release 0.6.48:
- Android: aggiunta tab `Voce` con canvas fullscreen senza controlli; particelle orange holographic random si assemblano in Hermes con tap singolo e si disassemblano con doppio tap; slash `/voce` e `/voice` aprono la modalita.
- Windows: aggiunta `VoicePage` con lo stesso effetto particellare; la shell viene nascosta in modalita Voce, Esc torna indietro, sidebar e slash `/voce`/`/voice` aprono la pagina.
- Release bump: Windows/AdminBridge `0.6.48`, Android `versionName 0.6.48`, `versionCode 61`.

Release 0.6.46:
- Android/Windows/config: default Hermes Native con strict native mode ON, protocollo effettivo/fallback visibile e contesto delegato a Hermes.
- Android/Windows: raw Hermes events `hermes.*` conservati e mostrati; Visual Blocks forward-compatible con fallback `unknown_block`.
- Gateway locale: capability/native alias/eventi raw aggiornati per non castrare Hermes Agent; Linux helper/docs allineati per Ubuntu/vLLM.
- Linux headless: aggiunto patcher idempotente `scripts/patch-hermes-gateway-native.py`, chiamato automaticamente dal launcher con `HERMES_NATIVE_GATEWAY_PATCH=true`, per portare su Ubuntu lo stesso fix gateway native Windows.

Release 0.6.47:
- Android/Windows/Gateway: context meter usa evento nativo `hermes.context.usage`, derivato dagli stessi dati della CLI Hermes (`last_prompt_tokens`, `context_length`, `context_percent`), con stima locale solo fallback.
- Android/Windows: footer metriche mostra `ctx` e `max`; il cerchio resta reattivo anche dopo fine stream perche' conserva metrica server persistita.

Release 0.6.45:
- Android/Windows: fix indicatore `Contesto chat`: a chat e draft vuoti mostra 0% invece del 3%; overhead fisso di sistema viene aggiunto solo quando esiste contenuto reale.

Release 0.6.44:
- Android/Windows: metriche finali risposta Hermes visibili in stile LM Studio (`TTFT`, `t/s`, token output, token prompt, durata). Android conserva le metriche nel messaggio finale e nell'archivio; Windows le conserva in `ChatMessageRecord` e le renderizza anche riaprendo conversazioni salvate.

Release 0.6.43:
- Android/Windows: indicatore circolare `Contesto chat` al posto del chip/top button `Chat`/`Agente`, con percentuale e riempimento proporzionale. Usa stima token locale su finestra 32k e `promptTokens` reale quando Hermes lo invia; modalita `Chat`/`Agente` resta nel menu `+` e nei comandi slash.
- Android Video: Media3 ExoPlayer resta il player principale e aggiunge pulsante fullscreen sul player; il video si apre in dialog a schermo intero con controlli player.
- Windows Video: `MediaPlayerElement` aggiunge pulsante `Full` e usa `IsFullWindow` per guardare il video a schermo intero.

Release 0.6.42:
- Gateway locale: aggiunti endpoint protetti `GET/PATCH /v1/hub/memory`, `GET/POST /v1/hub/state`, `DELETE /v1/hub/state/{id}` con store JSON locale (`hub_memory.json`, `hub_state.json`) e capability `hub_memory`, `hub_state`, `diagnostics`.
- Linux/headless: helper, service e docs Ubuntu/vLLM aggiornati per `HERMES_HUB_MEMORY_PATH` e `HERMES_HUB_STATE_PATH`, mantenendo API key default `hermes-hub`.
- Android: player Video passa da `VideoView` a Media3 ExoPlayer con supporto header Authorization; feed Video mantiene thumbnail, like/dislike e feedback.
- Android/Windows: aggiunta Memoria Hermes editabile da Profilo/Settings con blocchi preferenze video, news, stile risposta, regole progetto e note generali; se gateway non espone endpoint mostra fallback chiaro.
- Android/Windows: hub state sincronizza feedback video/news, read state news e progetto attivo quando gateway 0.6.42 e' raggiungibile; fallback locale resta attivo se gateway non risponde.
- Android/Windows: diagnostica Hermes ampliata con health, health detailed, models, capabilities, video library, memory e hub state con messaggio e azione consigliata.
- Android/Windows: Modalita Progetto base con progetto attivo incluso nei metadata Hermes (`project_id`, `project_name`, `workspace`, memory shared); Windows lo espone in Settings, Android in Profilo.
- News: Android usa feed card articolo + reader markdown + feedback rapido/scritto; Windows aggiunge selezione articolo e feedback rapido/scritto, senza introdurre `/v1/news/library`.
- Update: Android e Windows mostrano changelog completo/asset/versioni/cartella update in modo piu' trasparente; nessuna installazione silenziosa.

Release 0.6.41:
- Android Video: le date dei video nel feed e nel player ora sono timestamp completi `dd/MM/yyyy HH:mm`, invece di etichette relative tipo `oggi`.
- Android Video: aggiunti like/dislike stile YouTube nella schermata video; la reazione viene salvata localmente, inviata subito a Hermes come feedback rapido primario e usata anche dal filtro `Feedback`.
- Android Video: il commento scritto resta un rinforzo qualitativo opzionale sopra la reazione rapida.

Release 0.6.40:
- Android Video UI: feed ridisegnato in stile YouTube con thumbnail 16:9, lista video cliccabile, detail view con player inline `VideoView`, rimozione bottone `Copia path`, chip rapidi di feedback e invio feedback a Hermes come memoria editoriale condivisa per migliorare i video futuri.

Release 0.6.39:
- Video Library v2: gateway locale espone `GET /v1/video/library`, scansiona `HERMES_VIDEO_LIBRARY_PATH`, registra automaticamente i video tramite proxy `/v1/media/...` e Android mostra feed video da quella cartella invece di dipendere da workspace/job/chat.
- Android: la sezione Video ora ha feed diretto con `Aggiorna feed`, `Apri` e `Copia path`; fix runtime sull'apertura video usando `Intent.setDataAndType(...)` per non perdere l'URI media.
- Android: quando il prompt parla di video aggiunge un system prompt secondario che obbliga l'agent a salvare i file finali nella watched folder e a usare `/v1/media/...` se li mostra in chat.
- Windows/Android/Linux: istruzioni e metadata aggiornati per dichiarare `/v1/video/library` come contratto ufficiale della sezione Video.
- Linux helper/service/docs: aggiunto `HERMES_AUXILIARY_LOCAL_ONLY=true` anche su Ubuntu/vLLM, oltre a `HERMES_MAX_ITERATIONS=0`, API key `hermes-hub`, video library e media roots.

Release 0.6.38:
- Android/Windows chat archive: salvataggio a snapshot. Il prompt utente viene persistito subito all'invio; durante streaming viene scritto un checkpoint parziale circa ogni 2s; il salvataggio finale sostituisce lo snapshot invece di aggiungere di nuovo la coppia prompt/risposta. Android conserva anche messaggi di stato/action nell'archivio.
- Android: stream chat spostato su scope applicativo non legato alla composizione; chiudere la UI non cancella subito il job finche' il processo resta vivo. Se il trasporto cade con `connection abort`, il parziale viene salvato come stream scollegato invece di sparire.
- Gateway locale: disconnessione SSE del client non interrompe piu' l'agent task; il gateway lascia proseguire il lavoro invece di chiamare `agent.interrupt("SSE client disconnected")`.
- Gateway locale: rimosso hard cap globale da 90 iterazioni per Hermes Hub. `HERMES_MAX_ITERATIONS=0` significa illimitato; i vecchi hard-stop tool-loop sono forzati off anche se la config prova a riattivarli. Il loop guardrail ora emette prompt di assestamento dopo 10 fallimenti consecutivi dello stesso tool.
- Wrapper Windows imposta `HERMES_AUXILIARY_LOCAL_ONLY=true` e il client ausiliario salta fallback esterni OpenRouter/Nous per evitare warning/credit errors nei test LM Studio locali. Hotfix locale post-release: `_try_payment_fallback` torna sempre tripla `(client, model, label)` anche in local-only; `session_search` in local-only usa raw preview senza LLM summarization per evitare timeout LM Studio e log rumorosi.
- Linux helper/service/docs: impostano `HERMES_MAX_ITERATIONS=0` per mantenere lo stesso comportamento su Ubuntu/vLLM.

Release 0.6.37:
- Android: tool call della chat raccolti in una singola flag `Tool` espandibile con conteggio/stato, per evitare che molti tool allunghino la chat. Le righe tool esistenti restano dentro la flag e mantengono JSON argomenti/risultato.
- Gateway media: parser locale aggiornato per convertire anche riferimenti tipo `File: nome.png` in `visual_blocks media_file` via proxy `/v1/media/...` se il file sta dentro `HERMES_MEDIA_ROOTS`. Wrapper Windows aggiunge il workspace corrente alle media roots; helper Linux documenta/configura `HERMES_MEDIA_ROOTS` per Ubuntu/vLLM.
- Video Library: gateway locale ora espone `video_library_path` in `/health/detailed` e capability `video_library`; wrapper Windows imposta `HERMES_VIDEO_LIBRARY_PATH` a `%LOCALAPPDATA%\hermes\media\video` e la aggiunge alle media roots. I prompt Android/Windows dicono all'agente di salvare li ogni video creato/scaricato/modificato per la sezione Video.

Release 0.6.36:
- Android: timeout OkHttp rimossi per chat/API Hermes (`connect/read/write/callTimeout = 0`) come richiesto da Matteo; nessun limite client a 60s/120s.
- Android: activity streaming semplificata. Mostra flag `Processing prompt` con percentuale reale se emessa dal gateway, flag `Ragionamento`, flag tool cliccabili e label finale `tok/sec`; rimossa percentuale stimata/fittizia.
- Gateway locale `%LOCALAPPDATA%\hermes\hermes-agent\gateway\platforms\api_server.py`: aggiunti eventi SSE `hermes.prompt.progress` su Chat Completions e Responses (`0/25/50/75/100`) e capability `prompt_progress_events`. Nota: sono milestone gateway/handoff; vera percentuale di prompt-eval del modello richiede telemetria upstream LM Studio/vLLM.

Release 0.6.35:
- Android e Windows ora leggono davvero gli eventi SSE custom del gateway: `hermes.tool.progress`, `hermes.visual_blocks`, `hermes.reasoning.available`.
- Chat Completions gateway locale verificato: eventi tool ora includono `arguments` e `result`, quindi app puo' mostrare chiamate tool e output in tempo reale invece di restare su `Sto processando`.
- Android rileva link immagine HTTPS nel testo risposta e li converte in preview `media_file`; Windows permette preview immagini HTTPS oltre ai proxy `/v1/media/...`.
- Fix mirato a problema reale visto in app: ragionamento/tool invisibili e immagini esterne rese solo come testo.

## Modifiche non rilasciate

- Audit architetturale aggiunto in `docs/hermes-hub-vs-hermes-native.md`: rischio che Hermes Hub castri Hermes Agent se resta orchestratore rigido. Direzione proposta: `Hermes Hub = thin operational client`, `Hermes Gateway = transport/security/media proxy`, `Hermes Agent = brain/memory/planner/tools/artifacts`.
- Implementata direzione Hermes Native: default Android/Windows/config `preferredApi=hermes-native`, `strict native mode` default ON, Chat e Agente passano da Responses/native path con context delegato a Hermes; Chat Completions/no-auth restano solo fallback compat quando strict e' OFF.
- Gateway Hermes locale `%LOCALAPPDATA%\hermes\hermes-agent\gateway\platforms\api_server.py` aggiornato per dichiarare `hermes_native`, `native_event_passthrough`, `raw_hermes_events`, `context_owner=hermes-agent`, alias `POST /v1/hermes/native` e primo evento SSE `hermes.native.protocol`; tool/progress custom `hermes.*` vengono inoltrati raw oltre alla compat Responses.
- Linux helper/docs aggiornati per `HERMES_NATIVE_EVENTS=true`, `HERMES_RAW_EVENT_PASSTHROUGH=true`, `HERMES_NATIVE_GATEWAY_PATCH=true`, patcher automatico `scripts/patch-hermes-gateway-native.py` e contratto `/v1/hermes/native`; quando si aggiorna Hermes o si passa a Ubuntu/vLLM, il launcher prova a patchare il gateway installato prima dell'avvio.
- Linux helper hardening post-test Windows/Tailscale: `scripts/hermes-hub-linux.sh` ora imposta/esporta esplicitamente `API_SERVER_HOST=0.0.0.0` e `API_SERVER_PORT=8642` oltre alle variabili compat `HERMES_API_HOST/PORT`, usa provider canonico `lmstudio`, sceglie l'istanza LM Studio caricata con contesto piu' alto e mantiene Android/Windows su URL server/Tailscale `http://SERVER:8642/v1` con API key `hermes-hub`. `scripts/patch-hermes-gateway-native.py` reso piu' robusto sul layout gateway Hermes aggiornato.
- Hotfix gateway stream Android: Chat Completions gateway ora emette `final_response` come delta se Hermes/LM Studio non produce token streaming ma restituisce risposta finale; evita errore Android `Stream Hermes vuoto` con solo chunk iniziale `delta.role=assistant`. Android parser non mostra piu' il chunk role-only come evento raw.
- Hotfix Windows updater locale 0.6.60: il pulsante update passa da `Installa e chiudi` a `Installa e riavvia`; script MSIX usa `Add-AppxPackage -Path` invece di `-LiteralPath` per compat PowerShell/Appx e logga errori in `install-msix-update.log`. Install manuale fatto da 0.6.58 a 0.6.59 per superare updater rotto; poi build locale 0.6.60 contiene il fix.
- Android/Windows mostrano protocollo effettivo/fallback e conservano raw Hermes events; Visual Blocks ora forward-compatible (`visual_blocks_version >= 1`, fallback `unknown_block` JSON); context meter mostra delega a Hermes finche' server non invia `promptTokens`.
- Hotfix post-0.6.46: context meter Android/Windows corretto per usare token server persistiti anche dopo fine stream e `ctx=prompt+output` su finestra 90k; prima poteva tornare alla stima locale e mostrare percentuali tipo 4% anche con 68k token reali.
- Evoluzione post-hotfix: gateway ora emette `hermes.context.usage` usando lo stesso dato della CLI Hermes (`context_compressor.last_prompt_tokens`, `context_length`, `context_percent`); Android/Windows preferiscono questo indicatore nativo e usano stima locale solo come fallback.
- Verifiche passate: `python -m py_compile %LOCALAPPDATA%\hermes\hermes-agent\gateway\platforms\api_server.py`, `python -m py_compile scripts\patch-hermes-gateway-native.py`, `python scripts\patch-hermes-gateway-native.py --target %LOCALAPPDATA%\hermes\hermes-agent\gateway\platforms\api_server.py --check`, `dotnet build NemoclawChat.sln`, `dotnet build NemoclawChat.sln -c Release`, Android `:app:compileDebugKotlin`, Android `:app:lintDebug`, Android `:app:assembleRelease`, `scripts/verify-visual-blocks-contract.ps1`, `git diff --check`.

## Release 0.6.34

Hermes Hub 0.6.34 (Windows + Android):
- Android streaming activity mostra piu' stati real-time: timer, percentuale stimata, stato corrente, log eventi, reasoning, tool call, argomenti e risultati tool.
- Parser Android legge piu' formati SSE per tool/reasoning/result e conserva gli eventi nell'activity panel.
- Android fallback media converte `MEDIA:[...](...)` in `media_file` invece di mostrare markdown grezzo.
- Prompt/metadata Android e Windows richiedono esplicitamente proxy media Hermes `/v1/media/...` e stream realtime reasoning/tool/intermediate calls.
- Linux helper aggiornato per chiamare `hermes-hub` **Hermes Gateway**, con provider configurabile `lm_studio`/`vllm`, API key default `hermes-hub`, service file e documentazione Ubuntu headless/vLLM.
- Hermes Gateway locale verificato con proxy media: `POST /v1/media/register`, `GET /v1/media/{media_id}`, capabilities media e conversione output `MEDIA:[...](file://...)` in `visual_blocks media_file`. Nota: patch applicata all'install locale `%LOCALAPPDATA%\hermes\hermes-agent\gateway\platforms\api_server.py`; va mantenuta/portata quando si aggiorna Hermes o si passa a Ubuntu/vLLM.

## Release 0.6.33

Hermes Hub 0.6.33 (Windows + Android):

- Aggiunto Visual Block `media_file` per condividere in chat singoli asset `image`, `video`, `audio` e `document`.
- Android e Windows renderizzano una UI dedicata con anteprima immagine/thumbnail, metadata, `Apri` e `Copia link`.
- Media chat vincolati a proxy Hermes/same-host `/v1/media/...`; rifiutati `file://`, `data:`, path locali diretti e URL esterni non sicuri.
- Aggiornati schema, fixture, documentazione e verifier Visual Blocks.
- Aggiornate istruzioni/metadata agent: `image_gallery` per gallerie, `media_file` per asset singoli.

## Release 0.6.28

Hermes Hub 0.6.28 (Windows + Android):

Fix auth Android:
- Android chat streaming ora se riceve `401 invalid_api_key` con una API key salvata ritenta automaticamente senza header `Authorization`. Serve quando Hermes/LM Studio gira senza key ma l'app conserva una key vecchia nel Keystore.
- Settings Android aggiunge bottone `Cancella API key`, per rimuovere solo il segreto Hermes senza fare reset totale delle impostazioni.

## Release 0.6.27

Hermes Hub 0.6.27 (Windows + Android):

Run post-audit:
- Android `demoMode` default/reset passato a `false`. Se Hermes non risponde, l'app mostra errore reale invece di mascherarlo con fallback locale.
- Verifiche passate: Android `compileDebugKotlin`, Android `lintDebug`, Windows `dotnet build`, AdminBridge `dotnet build`.

## Release 0.6.26

Hermes Hub 0.6.26 (Windows + Android):

Audit round 7. Tutti i fix in `docs/audit-0.6.26.md`.

Android critici/importanti:
- AES-GCM secret Hermes con AAD stabile; debug HTTP logging redige `Authorization`/`Cookie`.
- FileProvider limitato a `exports/`; update APK salvati in `externalFilesDir/exports`.
- Titoli conversazione filtrano bidi control chars; back di sistema torna tra tab o chiude sidebar invece di uscire.
- Offline state via `ConnectivityManager`; banner immediato e send bloccato se rete assente.
- Responses API SSE con retry/backoff prima del fallback Chat Completions; fallback loggato e mostrato come status.
- Manifest con `ACCESS_NETWORK_STATE` + `<queries>` per picker/camera/browser.
- Tool result gia' monospace (N/A markdown injection); `prettifyJson` troncato a 20k char.
- Messaggi selezionabili/copiabili con `SelectionContainer`; Settings/Profile con `imePadding`.
- Adaptive icon Android 13 con monochrome layer; StrictMode debug; lint block configurato.
- Defer dichiarati: tablet WindowSizeClass e scrollbar chat Compose (scope basso/grosso come audit).

Windows/AdminBridge critici/importanti:
- HomePage messaggi spostati da `StackPanel` a `ListView` virtualizzata con collection `MessageViewModel`.
- PromptBox: `MaxLength=50000`, spellcheck, automation name, drag/drop file, paste immagine intercettato.
- Errori stream mostrati in `InfoBar`; messaggi con context menu `Copia`; shortcut `Ctrl+N` nuova chat e `Ctrl+L` pulisci chat.
- ChatStream SSE hard cap 50MB totale; MarkdownRenderer cap 200k char/500 blocchi.
- VisualBlocks parse/deserialization con `MaxDepth=16`.
- AdminBridge: audit log rotation 10MBx5, rate limiting 60 req/min/IP, CORS whitelist Hermes, `/v1/reload`, shutdown log.
- Windows: posizione/dimensione finestra persistenti, `RequestedTheme=Dark`, file logger release `%LOCALAPPDATA%\ChatClaw\logs\app.log`, high contrast brush override, sidebar tooltips/automation.
- AppSettingsStore migration tollerante a race I/O.
- Defer dichiarati: WindowsAppSDK version bump e touch/pen custom handlers.

## Release 0.6.25

Hermes Hub 0.6.25 (Windows + Android):

Audit round 6. Tutti i fix in `docs/audit-0.6.25.md`.

Critici:
- Android `postJson` ora riusa `apiHttpClient` singleton lazy (era `OkHttpClient.Builder().build()` per call). Connection pool condiviso, fd risparmiati.
- Android `postJson` DELETE: verificato no body (gia' corretto via `.delete()`).
- AdminBridge `/v1/files/write`: `PathWriteLocks.Get(path)` (`SemaphoreSlim` per path con `ConcurrentDictionary`). Write paralleli stesso path serializzati.
- Windows HomePage `SendCurrentPromptAsync` set `_isSending=true` + `IsEnabled=false` PRIMA di prompt validation. No race su rapid Send click multipli.

High:
- Android ChatStreamUi `validBlocks.forEach` e `toolCalls.forEach` ora avvolti in `androidx.compose.runtime.key(id)`. Compose recomposition stabile.
- Android `"%.1f".format(...)` â†’ `String.format(java.util.Locale.US, "%.1f", ...)` su ChatStreamUi. Locale IT non corrompe piu' decimali stats/timings (era asimmetrico con MainActivity gia' corretto).
- Android `Tab.entries.filterNot{...}` ora `remember`-ato nella NavigationBar. No alloc per recompose.
- Windows `WorkspaceRequestStore` e `AgentTaskStore` ora con cache statico `List<...>?` + `lock` + invalidate su SaveAll. Parita' con `ChatArchiveStore`.

Med:
- Android `Regex("\\s+")` ora `MULTI_WHITESPACE_REGEX` top-level. Non compila piu' per call.
- Android `makeTitle` fallback `"Nuova richiesta"` se input solo whitespace/punctuation.
- Android AlertDialog delete: title runtime `.replace('\n', ' ')` + truncate 60 char. Lunghi titoli no overflow.
- Android VisualBlock: verificato `isValidVisualBlock` gia' rifiuta `id.isBlank()`.
- Windows `App.UnhandledException`/domain/task ora con tag marker `telemetry/...` + stack trace + IsTerminating flag. Pronto per hookup futuro telemetry.

Low:
- Android `Modifier.widthIn(min=3.dp, max=3.dp)` â†’ `Modifier.width(3.dp)` (CalloutBlock).
- Android `allowBackup` verificato gia' `false` in AndroidManifest.
- Android ShimmerText/InfiniteTransition conditional: deferred (refactor lifecycle).

## Release 0.6.24

Audit round 5 â€” focus UI + code. Tutti i fix in `docs/audit-0.6.24.md`.

Critici:
- Android `AppColors.Muted` da `#A2ADBF` (3.8:1) a `#C8D2E0` (~6.5:1 su Background). Conformita' WCAG AA per testo secondario. Faint da `#6B7585` a `#8892A2`.
- Android data class `@Immutable` su `ChatMessage`, `VisualBlock`, `VisualTableColumn`, `VisualChartSeries`, `VisualChartPoint`, `VisualGalleryImage`, `ChatStreamStats`, `ToolCallState`, `StreamingState`. Compose puo' skip recomposition stabile.
- Windows `GatewayService.HttpClient` con `MaxResponseContentBufferSize = 10MB`. Server malevolo o response gigante non OOM-a app.

High:
- Android touch target su expander rows: `heightIn(min = 48.dp)` + icone bumped a 18-20dp. Conformita' Material 48dp.
- Android `StreamingBubbleView` con `animateContentSize()`. Recompose token-per-token smooth, no flicker.
- Android markdown code block: `softWrap = false` + `horizontalScroll(rememberScrollState())`. Codice lungo non clippa silenzioso.
- Windows `GatewayCredentialStore.SaveSecret`/`DeleteSecret` ora usa `SharedVault.Value` cached + try-catch + log.
- Windows `HomePage.Unloaded` handler: unsubscribe `PromptBox.TextChanged` + chiude `_slashPopup.IsOpen = false` + stop bubble shimmer.
- Windows `App.xaml.cs` global handler: `UnhandledException` (UI) + `AppDomain.CurrentDomain.UnhandledException` + `TaskScheduler.UnobservedTaskException`. async void handler che crash non spengono app.

Med:
- Android empty states Archive/Jobs/Recent ora con titolo bold + CTA descrittiva ("Tocca + per iniziarne una.").
- Android error `Text` con `liveRegion = Assertive`. TalkBack annuncia errori stream subito.
- Windows `MutedTextBrush` da `#FFA2ADBF` a `#FFC8D2E0`. WCAG AA su dark bg.
- Windows `ChatArchiveStore` cache statico `List<ConversationRecord>?` con `lock`, invalidato su SaveAll. No piu' reparse JSON ad ogni `Load()`/`Find()`/`Recent()`.
- Windows HomePage track `_currentStreamingBubble`, `Unloaded` stop shimmer su navigation away.

Low:
- Windows `AppUpdateService.HttpClient.Timeout` 5min â†’ 30min (asset > 100MB su conn lenta).
- Windows sidebar dynamic buttons: `ToolTipService.SetToolTip` + `AutomationProperties.SetName` per accessibility.

Esclusi (scope troppo grande):
- Windows sidebar collapse animation (WinUI 3 GridLength richiede Composition API).
- Android light theme (refactor `ChatClawTheme` con dynamic schema).
- `ChatStateHolder` Parcelable (refactor ViewModel + SavedStateHandle).
- i18n strings.xml extraction, Material typography scale, spacing constants.

## Release 0.6.23

Audit round 4. Tutti i fix in `docs/audit-0.6.23.md`.

Critici:
- Android `extractAssistantText` con depth cap 10. Stop overflow su SSE nidificato malevolo.
- AdminBridge `CHATCLAW_ADMIN_TIMEOUT`/`MAX_READ_BYTES`/`MAX_REQUEST_BYTES`/`MAX_WRITE_CHARS` clampati con `Math.Max`. Niente piu' `ArgumentOutOfRangeException` su env var negativo.
- AdminBridge `Process.Kill(true)` in try-catch (`InvalidOperationException`, `Win32Exception`). stdin/stdout read fault-tolerant via try-catch su `await stdoutTask`/`await stderrTask`.
- Windows ChatStream SSE `dataBuilder` cap 10MB per evento. Server malevolo non puo' piu' OOM stream loop.
- Windows `GatewayService.HttpClient.Timeout` 20s â†’ 5min. Chat non-streaming su modelli grandi non si abortisce piu' a meta'.

High:
- Android `chatState.draft` persistito via `rememberSaveable savedDraft` + `LaunchedEffect` sync. Process death non perde piu' draft.
- Android `ChatMessage` ora ha `id: String` UUID stabile + LazyColumn `items(messages, key=id)`. Niente piu' collision hashCode su messaggi identici. JSON `readMessages`/`writeMessages` round-trip id.
- Android `TasksScreen.loadTasks` ora dentro `LaunchedEffect` + `withContext(Dispatchers.IO)`. Niente piu' I/O sync su Composition thread.
- Android: rimosso dead code 90+90 righe in `VideoScreen`/`NewsScreen` (codice post-return mai eseguito).
- Windows `GatewayCredentialStore.PasswordVault` cached come `Lazy` singleton invece di `new` per call.
- Windows `MainWindow_Closed` guarda con flag `_closing` + try-catch per re-entrancy safety.

Med:
- Android AlertDialog delete `DialogProperties(dismissOnClickOutside=false)`. Tap fuori non chiude piu' modale di conferma.
- Android `fontScale` `isFinite()` check con fallback 1f. NaN/Infinity da settings corrotti non rompe `coerceIn`.
- Android `StreamingBubbleView` `semantics { liveRegion = Polite }` per TalkBack live updates.
- AdminBridge `/v1/logs/tail` ora usa `ReadLastLinesAsync` che fa seek-from-end + read backward in chunk 8KB. Niente piu' allocazione di tutto il file.

Low:
- Android `makeTitle` sanitize char non-stampabili + collapse whitespace + filter caratteri pericolosi.

Note: C4 (DispatcherQueue Windows) verificato come falso positivo. `await foreach` preserva sync context UI in WinUI 3.

## Release 0.6.22

Audit round 3 + hardening. Tutti i fix elencati in `docs/audit-0.6.22.md`.

Critici:
- Android `extractTextFromAnyJson` (ChatStream.kt) e `extractJsonText` (MainActivity.kt) ora hanno depth cap a 10. JSON profondo malevolo non causa piu' stack overflow.
- Android `streamChatRequest` ora invia `messages.takeLast(CHAT_HISTORY_MAX_MESSAGES=30)` invece di full history. Convo lunghe non esplodono il prompt.
- AdminBridge `/v1/files/write`: `File.Copy` backup in try-catch (IOException/UnauthorizedAccessException). 503 se backup fallisce, 500 su write fail.
- Windows BitmapImage `RenderDiagram`/`RenderGallery` con `DecodePixelWidth = 720` + `MaxWidth = 720`. Immagini 10000x10000 non possono piu' OOM-are l'app.

High:
- AdminBridge `AuditLog.Write` ora con `lock` + try-catch su `File.AppendAllText`. Audit log non corrompe piu' su richieste concorrenti.
- AdminBridge `CommandRunner.RunAsync` usa `ProcessStartInfo.ArgumentList` invece di string. `fileName` con spazi sicuro, no shell injection.
- Android `MarkdownText`: `renderInlineMarkdown` ora memoizzato per `Paragraph`/`Header`/`Bullet` via `remember(block.text, color)`. Streaming bubble non ricostruisce AnnotatedString ad ogni frame.
- Android `loadRemoteBitmap`: cap 10MB advertised + 10MB letti + scaling via `inSampleSize` per max 2048px lato + `finally { disconnect() }`. Immagini grandi non OOM-ano, no socket leak.
- Android `SettingsField`/`SettingsPasswordField`: `take(SETTINGS_FIELD_MAX_LENGTH=2048)` su onValueChange. Paste 1MB non grippa UI.
- Android prefs cache: `ConcurrentHashMap` su `getSharedPreferences` per nome + flag `migratedPrefs` per evitare migrate retry. Riduce I/O ripetuto.

Med:
- Android Composer `heightIn` ora scala con `LocalDensity.current.fontScale` (composer cresce con font scale 150%).
- Windows `GatewayCredentialStore.LoadSecret` distingue `COMException` / `CryptographicException` / generic e logga via `Debug.WriteLine`.
- Windows `GatewayService.HttpClient` ora con `HttpClientHandler.AllowAutoRedirect = false` esplicito. Niente piu' redirect verso host non validati.
- Windows BitmapImage `MaxWidth = 720` accoppiato a `MaxHeight` (no tall narrow image OOM).

Low:
- Windows `_isSending` ora `volatile` (pattern memoria meno fragile).

Note: `mergeTextDelta` audit segnalato come edge-case, ma la logica esistente gia' copre i casi standard SSE replay (`startsWith`, `endsWith`). Skip.

## Release 0.6.21

Audit round 2 + hardening. Tutti i fix elencati in `docs/audit-0.6.21.md`.

Critici:
- Windows `AppUpdateService`: hardening download. Whitelist host (`github.com`/`githubusercontent.com`), cap dimensione asset 500MB (sia da `Content-Length` sia da bytes letti), sanitize asset name (rifiuta path separators / `..`), max redirect 5, timeout 5 min, check destination dentro updates directory.
- Windows JSON store: scrittura atomica via `AtomicJsonFile` (temp + `File.Replace` con backup `.bak`). Crash mid-write non corrompe piu' `settings.json`/`conversations.json`/`tasks.json`/`workspace.json`.

High:
- Android: I/O off main thread su path caldo. `saveConversationExchange` + `saveWorkspaceRequest` + load conversazione in `LaunchedEffect` ora dentro `withContext(Dispatchers.IO)`. SaveSettings/Reset usa `chatScope.launch(Dispatchers.IO)`.
- Android: `getOrCreateGatewaySecretKey` ora `synchronized(gatewaySecretKeyLock)`, evita race che generava chiavi duplicate.
- AdminBridge: `ResolveSafePath` canonicalizza con `DirectoryInfo.ResolveLinkTarget`/`FileInfo.ResolveLinkTarget`, rifiuta `FileAttributes.ReparsePoint`. Symlink/junction non possono piu' uscire dalle root.
- AdminBridge: token Bearer ora con length guard prima di `[7..]`, evita IndexOutOfRange e timing leak su Authorization header malformato.

Med:
- Android stream: `accumText`/`accumThink` cap 2_000_000 char con truncate marker. Niente piu' OOM su risposta gigante.
- Android send button: pre-guard `state.activeStreamJob == null` + flag `sending` settato prima di append/state mutation, riduce finestra double-send.
- Android markdown inline parser: cap 200KB input + max 500 stili inline per blocco, blocca pattern adversarial O(nÂ²).
- Android OkHttp: `HttpLoggingInterceptor` (HEADERS) attivo solo in build debug via reflection (no dep release).
- Android settings: gateway/admin/inference URL ora trimmati e senza trailing slash su save (`normalizeUrl`).
- Windows ChatStream: `catch (JsonException)` ora logga in `Debug.WriteLine` invece di swallow silenzioso.
- AdminBridge: `ReadAllLinesAsync` con `Encoding.UTF8` esplicito.

## Release 0.6.20

Audit + hardening post-0.6.19. Tutti i fix elencati in `docs/audit-0.6.20.md`.

Critici risolti:
- Android `OkHttpClient` ora singleton modulo (era new per request â†’ fd waste + zero connection pool).
- Android `LazyColumn` messaggi con `key=` stabile via `itemsIndexed` (no recomposition mismatch su append).
- Android UI state rotation/process-death safe: `selectedTab`, `pendingPrompt`, `pendingConversationId`, `sidebarOpen` via `rememberSaveable`.
- AdminBridge: limite body request Kestrel (`CHATCLAW_ADMIN_MAX_REQUEST_BYTES`, default 4MB) + check `MaxWriteChars` su `/v1/files/write` + check size su `/v1/logs/tail` + fail-fast se `CHATCLAW_ADMIN_TOKEN` non set.

Importanti:
- Android: cleanup warning compilatore `!!`/`?.` ridondanti, `KeyboardOptions.autoCorrectEnabled`.
- Android: `Brush.verticalGradient` memoizzato in `ChatScreen`.
- Android: lista `validBlocks` memoizzata per recompose stream.
- Android: `network_security_config.xml` con `base-config` esplicito + commento doc strategia (cleartext LAN per design).
- Windows: `MainWindow` unsubscribe `ChatArchiveStore.Changed` su `Closed`.
- Windows: `DemoMode` default `false` (era `true`, mascherava errori reali).

UX:
- Android composer: autocorrect + capitalizzazione frasi attivi (`KeyboardCapitalization.Sentences`).
- Android settings: API key con toggle visibilita' (icona occhio).
- Android: haptic feedback `LongPress` a fine generazione stream.
- Android: `SlashCommandList` scrollabile con `heightIn(max=260.dp) + verticalScroll`.

Esclusi (richiedono input/refactor pervasivo):
- Signing keystore release dedicato (necessita keystore vero non in repo).
- ProGuard/R8 minify (rischio rottura senza test su device).
- Refactor MainActivity.kt 5000+ righe â†’ moduli.
- Migrazione AppColors â†’ `MaterialTheme.colorScheme`.

## Release 0.6.19

Hermes Hub 0.6.19 (Windows + Android):

- Android composer: rimosso gap residuo tra textbox e tastiera (padding bottom del Row composer passato da 6.dp a 0.dp), input flush sopra IME.
- Android streaming activity: rimossa percentuale fittizia animata (`activityProgressPercent`, basata solo su una fase 0â†’1 su 90s, mostrava 42% appena partita e non riflette il vero prompt processing di LM Studio). Sostituita con indicatore reale derivato dallo stato stream: `promptâ€¦` shimmer in attesa del primo token, `reasoning N tok` durante reasoning, `N tok` durante generazione, `toolâ€¦` per tool pending, `Completato` a fine. Etichetta `Fase` non mostra piu' la percentuale.

## Release 0.6.18

Hermes Hub 0.6.18 (Windows + Android):

- Android sidebar: tap sul logo Hermes in alto a sinistra apre drawer stile ChatGPT con nuova chat, Archivio, Jobs e lista chat recenti apribili. Archivio e Jobs rimossi dalla bottom nav ma restano accessibili dal drawer.
- Android streaming parser: parsing Responses/Chat Completions piu' robusto per eventi finali annidati (`output_item.done`, `response.completed`, `content[]`, `output[]`) e deduplica del testo quando Hermes manda sia delta live sia risposta finale completa.

## Release 0.6.17

Hermes Hub 0.6.17 (Windows + Android):

- Android activity header: rimosso testo tecnico lungo (`Hermes Chat Completions connesso...`) dalla riga chiusa. Ora mostra solo stato breve animato + freccia + percentuale live; shimmer attraversa il testo da sinistra verso destra.

## Release 0.6.16

Hermes Hub 0.6.16 (Windows + Android):

- Memoria Hermes condivisa: app Android/Windows inviano contesto Hermes Hub e metadata `memory_policy.scope=shared-hermes-agent-memory`, `share_with_cli=true`. Chat Completions fallback include system message. Video/News/Jobs dichiarano `workspace` e memoria condivisa cosi Hermes puo' usare stessa memoria/profilo di CLI, app e jobs.
- Android streaming activity: pannello espandibile `Attivita Hermes` sempre durante generazione, anche dopo il primo token: stato connessione/processamento, reasoning live, generazione testo, tool call, argomenti e risultati tool.
- Streaming piu' rapido: Android e Windows non fanno piu' probe `/capabilities` prima di ogni messaggio; tentano direttamente `/v1/responses` e fanno fallback solo se serve. Android stop aggiorna subito UI con `Interruzione richiesta` mentre cancella lo stream OkHttp.

## Release 0.6.15

Hermes Hub 0.6.15 (Windows + Android):

- Immagini in chat: Android renderizza `visual_blocks` di tipo `image_gallery` caricando bitmap da media proxy Hermes `/v1/media/...`; Windows gia' renderizza image gallery e ora dichiara esplicitamente supporto immagini nelle istruzioni/metadata.
- Agent instructions: chat/agent chiedono a Hermes di inviare immagini solo come `image_gallery` con `media_url` proxy, `alt` e `caption`, rifiutando `file://`, `data:` e URL esterni diretti.
- Fallback visuale Android: i prompt con `immagine`, `image` o `foto` attivano i Visual Blocks anche in fallback locale.

## Release 0.6.14

Hermes Hub 0.6.14 (Windows + Android + Linux helper):

- Update UX: Android e Windows leggono il body della GitHub Release e mostrano una stringa `Novita'` nel riquadro Aggiornamenti, vicino a scarica/installa update.
- Windows chat: Enter invia sempre il messaggio; Shift+Enter inserisce newline manuale nel composer.
- Linux server helper: aggiunti `scripts/hermes-hub-linux.sh`, `scripts/hermes-hub-linux.service` e `docs/hermes-hub-linux.md` per esporre Hermes API su Ubuntu/Linux (`0.0.0.0:8642`), rilevare modello LM Studio caricato e avviare `hermes gateway run --replace`.
- Android Video/News: sezioni rese feed/aggregatori persistenti con Jobs Hermes, stream/download URL, feedback e riconoscimento automatico da chat primaria.
- Android chat empty state: gradiente amber piu' evidente e lungo circa il doppio.

## Release 0.6.13

Hermes Hub 0.6.13 (Windows + Android):

- Android chat empty state: il gradiente amber parte dal contenitore chat intero e si estende verso l'alto fuori dallo schermo; topbar logo/nome e azioni `Nuova`/`Chat` restano nella stessa posizione ma senza sfondo/pill.

## Release 0.6.12

Hermes Hub 0.6.12 (Windows + Android):

- Android keyboard: composer ancorato sopra la tastiera con `imePadding` solo sulla barra input, senza spostare tutta la chat e senza vuoto verticale eccessivo.
- Android settings: slider `Dimensione caratteri` continuo 85%-125%; percentuale cliccabile/editabile a mano e confermabile da tastiera.
- Android UI: vecchie card/vignette nelle sezioni convertite in righe/pannelli flat con separatori dritti, stile impostazioni Android; niente box arrotondati per i blocchi informativi principali.

## Release 0.6.11

Hermes Hub 0.6.11 (Windows + Android):

- Android settings: aggiunto slider `Dimensione caratteri` persistente (`fontScale` 85%-125%) con anteprima immediata; la scala viene applicata all'intera UI tramite `LocalDensity`.
- Android UI: sostituito il rendering delle vecchie card/vignette con pannelli premium piatti, bordo sottile, raggio basso e zero elevation; mantenuta bubble utente distinta ma meno giocattolosa.
- Android keyboard: composer resta aderente alla tastiera usando `adjustResize` senza doppio `imePadding`.

## Release 0.6.10

Hermes Hub 0.6.10 (Windows + Android):

- Android chat UI: composer compatto stile ChatGPT Android con `+` esterno, send interno, mic placeholder rimosso; risposte assistente libere senza vignetta; `Sto pensando` libero/cliccabile con shimmer e reasoning espandibile.
- Android streaming control: durante generazione il bottone invio diventa stop e interrompe job + chiamata OkHttp; testo parziale resta in chat marcato come interrotto.
- Setup locale Hermes Hub: wrapper `hermes-hub.ps1` forza modello LM Studio caricato, `terminal.cwd=C:/Users/Matteo` e Tirith disattivato se non installato per ridurre warning Windows non fatali.

## Release 0.6.9

Hermes Hub 0.6.9 (Windows + Android):

- Header `Sto pensando` con shimmer mostrato sempre durante streaming finchÃ© non arriva il primo token di testo, anche quando il server non emette eventi `reasoning`. Si congela in `Pensato per Xs` solo se reasoning Ã¨ stato ricevuto, altrimenti viene nascosto.
- Rendering markdown del messaggio assistente (testo finale + delta streaming) su Android (`MarkdownText` con `parseMarkdownBlocks` + `renderInlineMarkdown` su `AnnotatedString`) e Windows (`Pages/MarkdownRenderer.cs` con `TextBlock` + `Inlines/Run`). Supporta `# ## ###` headers, `**bold**`, `*italic*` / `_italic_`, `` `inline code` ``, fenced ``` code blocks ``` con language hint, bullet `- ` / `* `.
- Tool call ora dentro `Expander` (Windows) / collapsible `Surface` (Android): header con icona di stato (in corso / riuscito / fallito), nome tool, status; espandendo si vedono `Argomenti` e `Risultato` formattati come JSON pretty-printed (`JsonSerializer.Serialize(WriteIndented=true)` Windows, `org.json.JSONObject/JSONArray.toString(2)` Android) e una riga `Esito: in corso / riuscito / fallito`.
- Inferenza esito tool: `"error"` nel payload del risultato â†’ fallito; status `completato`/`risultato pronto`/`done`/`success` o presenza di `result` â†’ riuscito; altrimenti in corso.

## Release 0.6.8

Hermes Hub 0.6.8 (Windows + Android):

- Stato chat persistente cross-tab: `ChatStateHolder` con `messages`, `draft`, `mode`, `activeConversationId`, `previousResponseId`, `streamingState`, `sending` vive in `ChatApp`; switch verso Archive/Server/Settings/etc non resetta la chat. `chatScope` rememberCoroutineScope sale a `ChatApp` per non interrompere lo streaming durante navigazione.
- Bottone `Nuova` accanto al chip mode (Android + Windows): chiama `resetForNewChat()` Android / svuota `MessagesPanel` + `_messageHistory` su Windows.
- Composer Android non piÃ¹ coperto da tastiera: manifest `android:windowSoftInputMode="adjustResize"`, `WindowCompat.setDecorFitsSystemWindows(window, false)` in onCreate, `Modifier.imePadding()` sulla Column chat + `statusBarsPadding` sulla TopBar.
- Auto-scroll Android: `LazyListState.animateScrollToItem(last)` lanciato in `LaunchedEffect` sui cambiamenti di `messages.size` e `streamingState.text.length` per spostare la chat sempre sull'ultimo messaggio anche durante streaming.
- Windows `HomePage` con `NavigationCacheMode="Required"`: navigando ad altre pagine il Frame riusa la stessa istanza e mantiene history/conversation.

## Release 0.6.7

Hermes Hub 0.6.7 (Windows + Android):

- Streaming SSE token-by-token: client tenta prima `POST /v1/responses` con `stream: true`, fallback `POST /v1/chat/completions` streaming. Parser SSE comune che gestisce eventi Responses (`response.output_text.delta`, `response.reasoning.delta`, `response.output_item.added`, `response.function_call.arguments.delta`, `response.completed`) e formato Chat Completions (`choices[].delta.content`, `delta.reasoning`, `delta.tool_calls`, `usage`).
- Bubble assistente live: header "Sto pensando" con shimmer gradient continuo (LinearGradientBrush animato Windows, `Brush.linearGradient` su `TextStyle` Compose Android). Header cliccabile per espandere il ragionamento (delta di `reasoning`/`reasoning_content`). Dopo il primo token utile lo shimmer si congela in `Pensato per Xs`.
- Tool calls visibili inline: card per ogni tool con nome, status, argomenti streamed e risultato.
- Statistiche risposta: `TTFT / token/s / token / prompt-token / durata` calcolate da `Stopwatch` lato client, usando `usage` server quando disponibile, altrimenti stima `len/4`.
- Composer slash-command: digitando `/` compare popup con lista comandi (`/chat /agente /clear /new /health /server /runs /archive /tasks /settings /about /setup /visual /research /web /image /help`). Navigation tabbed da slash su Android, `Frame.Navigate` su Windows.
- Rimosso vecchio messaggio placeholder `Hermes sta preparando la risposta...` su entrambi i client.
- File `agent.md` (duplicato di `AGENTS.md`) eliminato: era inutile contesto duplicato.

## Stato Attuale

Windows:

- Progetto: `src/NemoclawChat.Windows`
- Stack: WinUI 3, C#, .NET 8, Windows App SDK self-contained.
- Versione app: `0.6.61`.
- Brand/UI: `Hermes Hub`, logo Hermes da `logo hermeshub.png` applicato agli asset Windows e alla UI principale, dark stile ChatGPT, sidebar, composer largo, menu `+`, settings reali.
- UI design system applicato: superfici elevation-aware `#0F1115/#14171D/#1A1E26/#232831`, accent Hermes amber `#F5A524`, hover `#FFC857`, testo muted `#A2ADBF`, bubble utente amber scuro `#7A3E00`, card/composer radius premium e bordi soft.
- Azioni locali: file picker Windows, screen clip, camera URI, nota vocale prompt.
- Chat: invio con Enter, nuova riga con Shift+Enter, indicatore circolare `Contesto chat` in alto a destra, modalita `Chat`/`Agente` nel menu `+` e slash, action bubble per menu `+`, scroll automatico, salvataggio cronologia locale; lista messaggi e composer sono responsive via `MaxWidth`, senza larghezze rigide.
- Voce: pagina fullscreen senza UI visibile, particelle orange holographic random; tap assembla Hermes, doppio tap disassembla, Esc torna indietro.
- Archivio: ricerca locale + dati persistenti, filtri chat/progetti/task/server, riapertura conversazioni, segna progetto, eliminazione elementi salvati con conferma preventiva.
- Recenti sidebar: letti dallo store locale e aggiornati quando cambia archivio; nessun elemento seed finto.
- Chat: default Hermes Native/Responses con `store`, `conversation` e `previous_response_id`; fallback reale `POST /v1/chat/completions` solo se strict native mode e' disattivato; fallback locale solo se abilitato.
- Chat/Hermes memory contract: app invia istruzioni e metadata che dichiarano Hermes Hub come client operativo dello stesso Hermes Agent usato dalla CLI. Preferenze stabili, feedback Video/News e regole di lavoro devono usare memoria agente condivisa lato Hermes quando disponibile, non memoria separata solo app. Chat Completions fallback include system message con lo stesso contesto.
- Visual Blocks v1 implementato lato client: chat puo' ricevere `output_text` autosufficiente + `visual_blocks_version: 1` + blocchi tipizzati statici (`markdown`, `code`, `table`, `chart`, `diagram`, `image_gallery`, `media_file`, `callout`). Contratto in `docs/visual-blocks-schema.md` e schema in `config/visual-blocks.schema.json`.
- Jobs: coda persistente su disco, tentativo reale su Hermes Jobs API (`/api/jobs`), `run`/`pause`/`delete` con sync Hermes se disponibile e fallback locale se no.
- Server: dashboard Hermes con `/health`, `/health/detailed`, `/v1/models`, `/v1/capabilities`.
- Runs: pagina dedicata con preset HTTP reali per health, models, capabilities, `POST /v1/runs`, `GET/POST /api/jobs`.
- Vecchio WebSocket operator rimosso dalla UX principale. Servizi legacy restano nel repo non primari.
- Settings: `GatewayUrl` ora significa `Hermes API URL`, default `http://hermes.local:8642/v1`; `GatewayWsUrl` vuoto; `AdminBridgeUrl` derivato root Hermes.
- Settings: include `Cartella video Hermes` in sola lettura/sync server; Hermes decide il path e l'app lo recepisce automaticamente.
- Auth Hermes lato app con API key: Windows usa `Authorization: Bearer <API key salvata>`; default `hermes-hub`. Settings permette modifica/ripristino key, salvata in Credential Locker.
- Video: feed desktop ora cartella-centrico, non solo job-centrico. Ogni `.mp4/.m4v/.mov/.mkv/.webm/.avi` nella cartella monitorata compare automaticamente con player locale, fullscreen, note rapide e feedback a Hermes; layout adattivo passa da due colonne a stack verticale su finestra stretta.
- Profilo/About: info app/profilo locale, versione, privacy, gateway attivo.
- Update system: controlla GitHub Releases latest, preferisce asset `.msix/.appinstaller`, scarica in app con progresso e poi installa/riavvia da bottone `Installa e riavvia`.
- Compatibilita storage: usa `%LOCALAPPDATA%\\ChatClaw\\...` ma migra automaticamente da `%LOCALAPPDATA%\\NemoclawChat\\...` se esiste.
- Settings: validazione URL/campi obbligatori, salvataggio locale, reset default, test Hermes `/health`; UI separata in Connessione Hermes, Avanzate e Memoria.
- Settings salvate in:

```text
%LOCALAPPDATA%\ChatClaw\settings.json
```

Conversazioni/progetti salvati in:

```text
%LOCALAPPDATA%\ChatClaw\conversations.json
```

Android:

- Progetto: `src/NemoclawChat.Android/app`
- Stack: Kotlin, Jetpack Compose, Gradle.
- Versione app: `0.6.61`, versionCode `73`.
- Brand/UI: `Hermes Hub`, logo Hermes da `logo hermeshub.png` applicato a launcher + UI, bottom nav primaria ridotta a Chat/Runs/Voce/Video/Profilo, composer mobile compatto stile ChatGPT Android, menu `+` con Material icons, profilo locale con scorciatoie alle aree secondarie.
- UI design system applicato: superfici elevation-aware `#0F1115/#14171D/#1A1E26/#232831`, accent Hermes amber `#F5A524`, testo muted `#A2ADBF`, bubble utente amber scuro `#7A3E00`, empty state con wash amber e logo grande.
- Azioni locali: file picker Android, camera intent e prompt helper nel menu `+`; dettatura/mic placeholder rimossi finche' non c'e' integrazione reale.
- Chat: action bubble per menu `+`, mode `Chat`/`Agente` nel menu `+` e slash, indicatore circolare `Contesto chat` in alto a destra, Hermes Native/Responses default con fallback `/v1/chat/completions` solo se strict native mode e' disattivato, fallback locale esplicito se abilitato, composer stabile compatto a campo singolo/multiriga con `+` esterno e send interno; keyboard handling usa `adjustResize` + `imePadding` solo sul composer, quindi resta sopra la tastiera senza gap inutile; durante generazione il send diventa stop e cancella job + chiamata OkHttp; mic placeholder rimosso; risposte assistente Android libere senza vignetta, thinking cliccabile con shimmer e reasoning espandibile; font globale regolabile da settings con slider continuo e percentuale editabile; sezioni Android rese come righe flat con separatori dritti al posto di card/vignette, salvataggio cronologia locale con `previous_response_id`.
- Voce: tab fullscreen senza bottom nav con WebView Three.js/WebGL offline; usa `hermes-particles-shape.json` bundled come forma Hermes default, editor integrato attivo, touch assembla/disassembla, back Android esce.
- Android streaming activity: durante generazione mostra una riga shimmer cliccabile con stato live (`Sto processando`, `Sto pensando`, `Sto generando`, `Uso tool: ...`). Espandendo si vedono stato, reasoning ricevuto dal server, tool call, argomenti e risultato; resta visibile anche quando il testo ha gia' iniziato a uscire.
- Android streaming activity UI: header compatto senza frasi tecniche del trasporto; mostra freccia accanto allo stato e percentuale di progresso live. Shimmer deve scorrere da sinistra a destra su tutti gli stati attivi.
- Android streaming latency: il path caldo evita la richiesta `/capabilities` pre-invio, usa loop SSE senza `source.exhausted()` prima di ogni read e mostra feedback immediato quando l'utente preme stop.
- Android `demoMode` default/reset e' `false`: se Hermes non risponde, l'app deve mostrare errore reale invece di mascherare con fallback locale. Fallback solo se utente lo abilita esplicitamente dalle impostazioni.
- Chat/Hermes memory contract: Android invia istruzioni e metadata `memory_policy.scope=shared-hermes-agent-memory`, `share_with_cli=true`, sezioni Hermes Hub e profilo Matteo. Preferenze e feedback devono essere salvati/riusati lato Hermes quando il server espone memoria/tool, non restare solo nello storico app.
- Visual Blocks v1 implementato lato client: stesso contratto Windows, storage retrocompatibile, renderer Compose statico sicuro, nessun HTML/JS/SVG client-side.
- Archivio: tab mobile con ricerca locale persistente, filtri, riapertura conversazioni, salvataggio progetti, contatori, export appunti, rename/delete conversazioni salvate, conferma preventiva prima del delete, icona delete sempre visibile sulla card e azioni che vanno a capo su schermi stretti; nessun seed progetto/chat finto.
- Android UI hardening: righe di azioni in Archivio, Ordini, Server, Aggiornamenti e Settings convertite a layout che va a capo su schermi stretti, per evitare pulsanti nascosti o compressi.
- Jobs: coda task persistente in `SharedPreferences`, creazione job con tentativo reale su Hermes Jobs API, run/pause/delete con sync Hermes se disponibile e fallback locale se no.
- Server: dashboard Hermes/modello/API/sicurezza, test `/health`, lettura reale di `/health/detailed`, `/v1/models`, `/v1/capabilities`.
- Runs: tab dedicata con endpoint manuale e preset reali Hermes per dashboard, modelli, capabilities, runs e jobs. Vecchio WS operator rimosso dalla UX principale.
- Settings: aggiunto campo `Cartella video Hermes` per dichiarare a Hermes path condiviso usato dalla sezione Video.
- Settings: `gatewayWsUrl` vuoto, non mostrato nella UX Hermes.
- Settings: `adminBridgeUrl` derivato da root Hermes, non requisito primario.
- Auth Hermes lato app con API key: Android usa `Authorization: Bearer <API key salvata>`; default `hermes-hub`. Settings permette modifica/ripristino key, salvata cifrata via Android Keystore AES-GCM.
- Profilo: info Matteo/app/gateway/privacy/parita Windows.
- Update system: controlla GitHub Releases latest, scarica APK dentro l'app con progress bar + dimensione file e poi apre installer Android con tasto `Aggiorna`.
- Nessun bottone `Release` nella UI update Android: il flusso resta interno all'app come UniNote (`Controlla > Scarica > Aggiorna`).
- Se la versione installata e' gia' l'ultima disponibile: mostra solo stato aggiornato e il controllo refresh, senza bottoni `Scarica`/`Aggiorna`.
- Top bar chat Android: niente label `Demo: ...`; mostra solo brand + chip `Chat/Agente`.
- Icona launcher Android: adaptive icon con foreground ritagliato piu' grande per ridurre il vuoto attorno al logo tra le app.
- Settings: validazione URL/campi obbligatori, salvataggio locale, reset default, test Hermes `/health`.
- Android consente cleartext HTTP verso Hermes locale/Tailscale/LAN tramite `network_security_config`, necessario per `http://<ip-pc>:8642/v1`.
- Android chat HTTP usa timeout lungo per richieste Hermes lente: connect 15s, write 60s, read/call 60 minuti. Serve per modelli LM Studio locali che continuano a generare oltre il timeout breve OkHttp default.
- Setup locale Matteo: `%LOCALAPPDATA%\hermes\.env` contiene default Hermes API Server + LM Studio; `API_SERVER_KEY` deve essere `hermes-hub` per combaciare con app Windows/Android. Comando PATH `hermes-hub.cmd` avvia `hermes-hub.ps1`, forza `API_SERVER_KEY=hermes-hub`, legge il modello LLM attualmente caricato da LM Studio via `/api/v1/models` (`loaded_instances`), aggiorna `model.default/provider/base_url` in `%LOCALAPPDATA%\hermes\config.yaml`, forza `terminal.cwd=C:/Users/Matteo`, disattiva Tirith se non installato (`TIRITH_ENABLED=false`/`security.tirith_enabled=false`), poi avvia `hermes gateway run --replace`. Su Ubuntu/Linux usare `scripts/hermes-hub-linux.sh` o il servizio user systemd `scripts/hermes-hub-linux.service` per esporre API su `0.0.0.0:8642` con modello LM Studio caricato. Serve perché Hermes dà precedenza al config model rispetto a `HERMES_INFERENCE_MODEL` e per ridurre warning Windows non fatali.
- Settings salvate in `SharedPreferences` `chatclaw_settings` con migrazione automatica da `nemoclaw_settings`.
- Conversazioni/progetti salvati in `SharedPreferences` `chatclaw_archive` con migrazione automatica da `nemoclaw_archive`.
- Task salvati in `SharedPreferences` `chatclaw_tasks`.
- Progetto Android ora include file root Gradle + wrapper per essere buildabile dal repo.

Documentazione:

- `docs/windows-desktop-guide.md`
- `docs/android-app-guide.md`
- `docs/hermes-hub-conversion.md`
- `docs/visual-blocks-schema.md`
- `config/hermes-defaults.json`
- `config/visual-blocks.schema.json`

Admin Bridge:

- Progetto: `src/ChatClaw.AdminBridge`
- Stack: ASP.NET Core minimal API, .NET 8.
- Auth: bearer token da `CHATCLAW_ADMIN_TOKEN`.
- File root: `CHATCLAW_ADMIN_ROOTS`.
- Audit log: `~/.chatclaw-admin-bridge/audit.log` o `CHATCLAW_ADMIN_AUDIT`.
- Endpoints reali: `/v1/status`, `/v1/actions/{doctor|security-audit|plugin-list}`, `/v1/logs/tail`, `/v1/files/list`, `/v1/files/read`, `/v1/files/write`.

## Preset Hermes

Usare questi default finche' server reale non esiste:

```text
Hermes API URL: http://hermes.local:8642/v1
Health root: http://hermes.local:8642/health
Detailed health: http://hermes.local:8642/health/detailed
Provider: hermes-agent
Endpoint API lato server: http://hermes.local:8642/v1
API primaria: POST /v1/responses
API fallback: POST /v1/chat/completions
Model demo: hermes-agent
Accesso: Tailscale/LAN
Auth app: nessuna API key lato client; accesso consigliato solo LAN/Tailscale o reverse proxy sicuro lato server.
```

Nota architetturale:

- App non devono parlare direttamente con Ollama/local inference.
- App devono parlare a Hermes Agent API Server.
- Chat primaria via Responses API con `store`, `conversation` e `previous_response_id`.
- Runs API e Jobs API sostituiscono la vecchia console operator WS.
- Segreti/provider token restano lato server; Hermes Hub non usa segreti client per Hermes API in LAN/Tailscale.
- Visual Blocks v1: `output_text` deve essere completo anche quando ci sono blocchi; history inviata a Hermes deve contenere solo testo umano, non JSON dei blocchi. Client dichiara `metadata.visual_blocks.min_supported_version/max_supported_version/mode`. `mode`: `never` disabilita, `auto` lascia decidere Hermes, `always` preferisce blocchi quando ragionevole senza forzarli.
- Hermes Hub context: ogni richiesta chat/job/run deve dichiarare che l'app ha sezioni Chat, Video, News, Jobs/Runs e Archivio. Video/News devono essere creati lato Hermes/PC come job/artifact con `workspace=video|news`, `stream_url`/`download_url` per video e fonti per news. La memoria agente e' condivisa tra app, CLI e jobs; lo storico locale app non sostituisce la memoria Hermes.
- Sicurezza Visual Blocks: niente HTML, JS o SVG client-side. Diagrammi solo Mermaid source + media proxy pre-renderizzato con fallback code block `mermaid`. Media solo da proxy Hermes, no `file://`, no `data:`, no URL esterni diretti.
- Quicktype Visual Blocks: dopo generazione tipi, controllare discriminator `type` ed enum wire-value C#/Kotlin con `scripts/verify-visual-blocks-contract.ps1`.
- Golden screenshot Visual Blocks: baseline unica versionata in `tests/golden/`, generata da Windows CI; Android confronta con tolleranza e non crea baseline autonoma.

## UI Composer

Direzione richiesta utente:

- Composer deve essere largo come ChatGPT desktop, non stretto.
- Prompt deve occupare la maggior parte dello spazio.
- Finche' testo e' corto: una riga ampia.
- Quando testo va a fine riga: composer cresce in altezza mantenendo larghezza.
- Scelta `Chat`/`Agente` deve stare dentro menu `+`, non occupare spazio principale.
- Menu `+` deve avere simboli/icone per capire subito funzioni.
- Android: bottom nav deve usare icone vere, non lettere placeholder.

## Build

Windows:

```powershell
dotnet build .\src\NemoclawChat.Windows\NemoclawChat.Windows.csproj -c Debug -p:Platform=x64
```

Admin Bridge:

```powershell
dotnet build .\src\ChatClaw.AdminBridge\ChatClaw.AdminBridge.csproj -c Debug
```

Android:

```powershell
$env:ANDROID_HOME='C:\Users\Matteo\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat assembleDebug
```

APK debug:

```text
src/NemoclawChat.Android/app/build/outputs/apk/debug/androidApp-debug.apk
```

Nota update:

- Le app controllano `https://api.github.com/repos/JackoPeru/app-interazione-nemoclaw/releases/latest`.
- Tag release atteso: `vX.Y.Z`, esempio `v0.3.2`.
- Android richiede APK con stesso `applicationId` e stessa firma, `versionCode` maggiore.
- Android debug build non deve usare `applicationIdSuffix`: l'APK generato per release in-app deve restare `com.nemoclaw.chat`.
- Android updater ora supporta download in-app con barra progresso e poi handoff all'installer di sistema.
- Windows ora scarica asset release `.msix`, `.exe` o `.zip` dentro `%LOCALAPPDATA%\ChatClaw\updates\` e poi apre l'installer/asset.

Android SDK locale:

```text
.android-sdk/
```

Questo e' ignorato da Git.

## File Ignorati

Non aggiungere a Git:

```text
.android-sdk/
.tools/
.gradle/
local.properties
bin/
obj/
src/NemoclawChat.Android/app/build/
```

## Prossimi Passi Probabili

- Sezioni Video e News ready to go: aree separate dalla chat con invio reale a Hermes Runs, fallback chat e storico locale separato. Backlog in `prossime implementazioni.md`.
- Migliorare ulteriormente UI desktop/mobile.
- Migliorare UI verticale oltre console unica: pagine separate per approvals realtime, config diff/rollback visuale, file/workspace manager, Admin Bridge, security center avanzato.
- Se arriva backend definitivo, allineare i payload/contratti reali se differiscono da quelli flessibili attuali.
- Prossima milestone: trasformare i probe WS in pagine operative vere: approvazioni exec realtime, modelli/provider, plugin/skills, log/audit, nodi, workspace, config patch, security center.
- Migliorare pairing completo: QR/pairing device e revoca dispositivi. Base credenziali sicure gia' presente: Windows Credential Locker, Android Keystore.
- Collegare evento `exec.approval.requested` e RPC `exec.approval.resolve` quando Gateway reale disponibile.
- Eventuale rebrand tecnico completo dei namespace interni solo se non rompe la compatibilita update Android.
- Aggiungere import archivio locale se richiesto.

## Preferenze Comunicazione

Utente ha attivato `caveman`: risposte brevi, tecniche, senza filler.

Usare italiano.
