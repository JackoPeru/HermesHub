# AGENTS.md

## Obiettivo

Creare app chat stile ChatGPT per comunicare con home-server che ospita Hermes Agent.

Target:

- App desktop Windows.
- App Android.
- UI moderna chatbot: dark premium operator console, composer largo, menu `+`, modalita `Chat`/`Agente`.
- Nome visibile app: `Hermes Hub`.
- Compatibilita Android: `applicationId` resta `com.nemoclaw.chat`.
- Backend primario personale: Hermes Agent API Server su Tailscale/MagicDNS `http://hermes:8642/v1`; fallback diretto IP server Linux verificato `http://100.94.223.14:8642/v1`, poi `hermes.local`.
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
v0.6.136 Release Hermes Hub 0.6.136 tombstone patcher hotfix
```

## Release Corrente

Hermes Hub 0.6.136 (Tombstone patcher hotfix):

Release 0.6.136:
- Gateway Linux: hotfix patcher idempotente. Se il gateway era gia' stato patchato da release precedenti, ora le funzioni esistenti `_hermes_hub_normalize_conversation`, `_hermes_hub_merge_conversations` e `_hermes_hub_delete_conversation` vengono aggiornate per supportare davvero `deletedAt`, invece di saltare il blocco perche' gia' presente.
- Windows/Android: stesso sync cancellazione chat della 0.6.135, con bump app per mantenere updater app allineato alla latest release.
- Asset release attesi: Android APK `HermesHub-0.6.136-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.136.0_x64.msix`, Linux Gateway `HermesHub-0.6.136-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.136`, Android `versionName 0.6.136`, `versionCode 141`.

Hermes Hub 0.6.135 (Synced chat deletion):

Release 0.6.135:
- Windows/Android/Gateway: aggiunto sync cancellazione chat tramite tombstone `deletedAt`. Quando una chat viene eliminata su un dispositivo, il marker viene caricato sul gateway e gli altri dispositivi la rimuovono al sync senza farla riapparire.
- Windows: `ChatArchiveStore` conserva marker eliminazione non visibili nella UI, li include nel sync, e il merge applica last-write-wins tra aggiornamenti e cancellazioni.
- Android: `LocalConversation.deletedAt` viene persistito, caricato/scaricato dal gateway e filtrato dalla UI; `deleteConversation` crea tombstone invece di rimuovere silenziosamente la chat.
- Gateway Linux: `/v1/hub/conversations/import` conserva `deletedAt`; `DELETE /v1/hub/conversations/{id}` crea tombstone persistente invece di hard delete immediata.
- Asset release attesi: Android APK `HermesHub-0.6.135-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.135.0_x64.msix`, Linux Gateway `HermesHub-0.6.135-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.135`, Android `versionName 0.6.135`, `versionCode 140`.

Hermes Hub 0.6.134 (Automatic chat archive sync):

Release 0.6.134:
- Windows/Android: sincronizzazione archivio chat automatica. Ogni salvataggio locale programma upload debounce verso `/v1/hub/conversations/import`; all'avvio e periodicamente l'app scarica/merge dal gateway.
- Windows: aggiunto `ChatArchiveSyncService`, avviato con la finestra principale, con pull+push automatico ogni circa 2 minuti e upload debounce dopo `ChatArchiveStore.Changed`.
- Android: aggiunto `ConversationArchiveAutoSync`, con pull+upload automatico ogni circa 2 minuti mentre l'app e' aperta, upload debounce dopo `saveConversations` e refresh automatico quando si apre `Archivio`.
- I bottoni manuali `Carica server` / `Scarica server` restano come fallback diagnostico.
- Asset release attesi: Android APK `HermesHub-0.6.134-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.134.0_x64.msix`. Linux Gateway non incluso perche' invariato rispetto all'hotfix gateway 0.6.133.
- Release bump: Windows/AdminBridge `0.6.134`, Android `versionName 0.6.134`, `versionCode 139`.

Hermes Hub 0.6.133 (Server chat archive sync):

Release 0.6.133:
- Windows/Android: aggiunta sincronizzazione manuale archivio chat con gateway tramite sezione `Archivio`: `Carica server` invia le chat locali a `/v1/hub/conversations/import`, `Scarica server` ripristina/merge dal gateway senza cancellare chat locali piu' recenti.
- Gateway Linux: aggiunti endpoint persistenti `GET /v1/hub/conversations`, `POST /v1/hub/conversations/import`, `PUT /v1/hub/conversations/{id}`, `DELETE /v1/hub/conversations/{id}` con store default `~/.hermes/hub_conversations.json` (`HERMES_HUB_CONVERSATIONS_PATH` override).
- Gateway Linux hotfix 2026-07-05: patcher reso compatibile con upstream Hermes che usa `gw_state` in `/health/detailed` e startup guard `_api_key_passes_startup_guard`; corretto anche argomento errato in `capabilities hub support features`.
- Gateway Linux: launcher genera/riusa una chiave forte `~/.hermes/api_server.key` per `API_SERVER_KEY` quando il default `hermes-hub` e' troppo corto per i nuovi controlli upstream; `hermes-hub` resta alias compat per le app tramite patcher.
- Android: corretto `Backup locale`, ora esporta davvero `chatclaw_archive.items` oltre al campo compat `conversations`.
- Android: aggiunto import manuale da export testuale negli appunti (`Archivio > Importa appunti`) per migrazione da vecchie installazioni non aggiornabili per cambio firma.
- Nota migrazione firma Android: gateway non puo' estrarre direttamente chat dalla sandbox Android di una vecchia installazione firmata con key diversa. Prima di disinstallare vecchia app, usare `Archivio > Export` e salvare il testo; dopo reinstall nuova app, copiare quel testo negli appunti, usare `Archivio > Importa appunti`, poi `Carica server`.
- Asset release attesi: Android APK `HermesHub-0.6.133-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.133.0_x64.msix`, Linux Gateway `HermesHub-0.6.133-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.133`, Android `versionName 0.6.133`, `versionCode 138`.

Hermes Hub 0.6.132 (Whitespace token stream fix):

Release 0.6.132:
- Windows/Android: corretto il parser SSE dello stream. Ora rimuove solo il singolo spazio opzionale dopo `data:` invece di cancellare tutti gli spazi iniziali (`TrimStart`/`trimStart`), evitando la perdita di delta composti solo da spazio o con spazio iniziale.
- Windows/Android: i delta testuali, reasoning e tool arguments preservano stringhe whitespace-only quando arrivano dallo stream, evitando parole/articoli attaccati durante reasoning e tool call.
- Windows/Android: fix mirato alla visualizzazione token; nessun cambio al modello o al backend Hermes.
- Asset release attesi: Android APK `HermesHub-0.6.132-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.132.0_x64.msix`. Linux Gateway non incluso perche' invariato in questa release app-only.
- Release bump: Windows/AdminBridge `0.6.132`, Android `versionName 0.6.132`, `versionCode 137`.

Hermes Hub 0.6.131 (Chat Context Menu):

Release 0.6.131:
- Windows: Implementato il menu contestuale al passaggio del mouse sulle chat recenti nella barra laterale (stile ChatGPT).
- Windows: Il menu a tre puntini consente di accedere a opzioni rapide come `Rinomina` (tramite finestra di dialogo modale) ed `Elimina`.
- Backend Windows: Introdotto il metodo `Rename` nel `ChatArchiveStore` per il salvataggio persistente dei nuovi titoli.
- Asset release attesi: Android APK `HermesHub-0.6.131-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.131.0_x64.msix`, Linux Gateway `HermesHub-0.6.131-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.131`, Android `versionName 0.6.131`, `versionCode 136`.

Hermes Hub 0.6.130 (Speech-to-Text & Prompt Fix):

Release 0.6.130:
- Windows/Android: Eliminati i prompt automatici (es. "Analizza l'immagine") all'inserimento di allegati. I file vengono ora inviati puri al modello, senza contaminare il testo dell'utente.
- Windows/Android: Aggiunto pulsante Microfono nella chat per la dettatura vocale (Speech-to-Text). Registra la voce e usa Whisper per trascrivere l'audio. Il testo viene inserito nel composer permettendo la revisione prima dell'invio.
- Server Gateway: Integrato `faster-whisper` e l'endpoint `/v1/audio/transcriptions`. Configurati per utilizzare il modello `large-v3-turbo` in formato int8 sulla GPU, ottimizzato per trascrizioni accurate in italiano. Il modello Whisper viene ora precaricato all'avvio del server (dopo l'LLM) per garantire risposte istantanee. Risolto anche il caricamento automatico delle librerie CUDA (cuBLAS/cuDNN) via `LD_LIBRARY_PATH`.
- Asset release attesi: Android APK `HermesHub-0.6.130-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.130.0_x64.msix`, Linux Gateway `HermesHub-0.6.130-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.130`, Android `versionName 0.6.130`, `versionCode 135`.

Hermes Hub 0.6.129 (True Full Screen UX):

Release 0.6.129:
- Windows/Android: Implementato sistema video immersivo "stile YouTube" a vero schermo intero.
- Windows: `AppWindow.SetPresenter` massimizza il video oscurando taskbar e barra laterale, senza più artefatti di layout.
- Android: Player inserito in un `Dialog` Compose con `decorFitsSystemWindows = false`, bypassando lo Scaffold principale per eliminare le barre di navigazione residue in landscape.
- Asset release attesi: Android APK `HermesHub-0.6.129-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.129.0_x64.msix`, Linux Gateway `HermesHub-0.6.129-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.129`, Android `versionName 0.6.129`, `versionCode 134`.

Hermes Hub 0.6.128 (Windows Runtime Fix):

Release 0.6.128:
- Windows: fix crash istantaneo (`FormatException: Input string was not in a correct format` / 0xc000027b) all'avvio causato dal caricamento della chat history a causa di un formato stringa errato (`0.1` invece di `0.0`) introdotto nella formattazione del TTFT.
- Asset release attesi: Android APK `HermesHub-0.6.128-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.128.0_x64.msix`, Linux Gateway `HermesHub-0.6.128-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.128`, Android `versionName 0.6.128`, `versionCode 133`.

Hermes Hub 0.6.127 (Stream finale e reasoning persistente):

Release 0.6.127:
- Android: corretto il flush finale dei delta stream dopo `thinkExtractor.flush()`, cosi' l'ultimo token della risposta non viene piu' perso.
- Android: il reasoning viene salvato nel `ChatMessage`, mostrato nel messaggio finale e persistito nell'archivio locale, cosi' resta cliccabile anche dopo la fine dello stream.
- Windows: alla ricezione di `StreamDone` la UI riallinea bubble e builder al testo finale autorevole (`AccumulatedText`/`AccumulatedThinking`), evitando la perdita dell'ultimo token in chiusura stream.
- Asset release attesi: Android APK `HermesHub-0.6.127-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.127.0_x64.msix`, Linux Gateway `HermesHub-0.6.127-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.127`, Android `versionName 0.6.127`, `versionCode 132`.

Hermes Hub 0.6.126 (Finalizzazione release 0.6.125):

Release 0.6.126:
- Completata la release GitHub rimasta interrotta durante la preparazione della `0.6.125`, riallineando tutti i file di versione prima della pubblicazione.
- Mantiene le modifiche UI/metriche della `0.6.125`: TTFT espresso in secondi, soglie `t/s` piu' permissive e `ThinkingExpander` Android aggiornato con stile `FlagRow`.
- Asset release attesi: Android APK `HermesHub-0.6.126-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.126.0_x64.msix`, Linux Gateway `HermesHub-0.6.126-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.126`, Android `versionName 0.6.126`, `versionCode 131`.

Hermes Hub 0.6.125 (UI Tweaks & Metrics):

Release 0.6.125:
- Windows/Android: Aggiornato il calcolo del TTFT (Time To First Token) da millisecondi a secondi (es. `0.8s`).
- Windows/Android: Allentati i vincoli per la generazione dei `t/s` (tokens per second). Ora la metrica compare sempre, richiedendo solo 0.2s di durata e 2 token, invece dei precedenti 1.5s e 8 token.
- Android: Aggiornato `ThinkingExpander`. Ora quando Hermes pensa usa uno stile animato `FlagRow` ("Sto pensando" + token count), coerente con le altre animazioni di stato pre-generazione.
- Release bump: Windows/AdminBridge `0.6.125`, Android `versionName 0.6.125`, `versionCode 130`.

Hermes Hub 0.6.124 (Live Markdown and Thinking support):

Release 0.6.124:
- Windows/Android: Aggiunto supporto al parser streaming (`ThinkExtractor`) per isolare i blocchi `<think>` in `ThinkingDelta`. Questo permette di separare il "pensiero del modello" dal testo mostrato alla fine della generazione e aggiornare in modo coerente lo stato.
- Windows/Android: Risolto bug di formattazione non funzionante. Sostituito il TextBlock grezzo con un reale `MarkdownRenderer` in Windows e con `MarkdownText` in Android (già introdotto ma ora abilitato su testo in streaming). Il Markdown è ora renderizzato in tempo reale.
- Windows/Android: Abilitata la possibilità di selezionare e copiare il testo del modello durante e dopo lo stream (`IsTextSelectionEnabled = true` per Windows, aggiunto `SelectionContainer` su Android).
- Release bump: Windows/AdminBridge `0.6.124`, Android `versionName 0.6.124`, `versionCode 129`.

Hermes Hub 0.6.123 (Background multi-stream):

Release 0.6.123:
- Windows/Android: introdotta la generazione multi-stream in background. Ora l'utente può uscire da una chat mentre Hermes sta ancora generando; lo stream continuerà isolato in background e verrà ricollegato automaticamente alla UI quando si riapre la chat.
- Windows/Android: rimossa l'interruzione brutale della generazione al cambio schermata. L'architettura è stata refactored per usare una mappa globale degli stream attivi (`_activeStreams` su Windows, `ChatStateHolder.activeStreams` su Android).
- Android: la history dei messaggi in background è ora isolata dal thread UI per prevenire corruzioni del database durante i cambi di chat.
- Asset release attesi: Android APK `HermesHub-0.6.123-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.123.0_x64.msix`, Linux Gateway `HermesHub-0.6.123-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.123`, Android `versionName 0.6.123`, `versionCode 128`.

Hermes Hub 0.6.122 (Notifiche invisibili fix):

Release 0.6.122:
- Windows/Android: fix parsing array notifiche (supporto sia per formato legacy `items` che per nativo `notifications`).
- Windows/Android: mapping flessibile per i campi `severity`/`type` e `read_at`/`read`.
- Asset release attesi: Android APK `HermesHub-0.6.122-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.122.0_x64.msix`, Linux Gateway `HermesHub-0.6.122-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.122`, Android `versionName 0.6.122`, `versionCode 127`.

Hermes Hub 0.6.121 (Android WorkManager crash fix):

Release 0.6.121:
- Android: fix crash istantaneo `IllegalArgumentException` causato dall'uso di `setExpedited()` su un `PeriodicWorkRequest` non supportato da WorkManager.
- Asset release attesi: Android APK `HermesHub-0.6.121-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.121.0_x64.msix`, Linux Gateway `HermesHub-0.6.121-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.121`, Android `versionName 0.6.121`, `versionCode 126`.

Hermes Hub 0.6.120 (Android crash fix):

Release 0.6.120:
- Android: fix crash all'avvio causato da `ActivityNotFoundException` durante la richiesta esenzione ottimizzazione batteria senza blocco `try-catch`.
- Windows/Android: pulizia e allineamento build files e plugin.
- Asset release attesi: Android APK `HermesHub-0.6.120-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.120.0_x64.msix`, Linux Gateway `HermesHub-0.6.120-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.120`, Android `versionName 0.6.120`, `versionCode 125`.

Hermes Hub 0.6.119 (Android notifications background reliability):

Release 0.6.119:
- Android notifiche: WorkManager schedulato con `setExpedited()` (Android 12+), vincoli di rete (`NetworkType.CONNECTED`), `setRequiresBatteryNotLow(true)` e `BackoffPolicy.LINEAR` con backoff di 5 minuti.
- Android notifiche: aggiunto `BootReceiver` che riprogramma il worker all'avvio del dispositivo (`ACTION_BOOT_COMPLETED`), non solo all'apertura dell'app.
- Android notifiche: aggiunta gestione ottimizzazione batteria con richiesta `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; l'app guida l'utente nelle impostazioni di sistema per disabilitare le restrizioni batteria.
- Android notifiche: schedulazione estratta in `NotificationWorkerUtils.kt` per separare logica di utilita' da MainActivity.
- Asset release attesi: Android APK `HermesHub-0.6.119-android.apk`, Windows MSIX `NemoclawChat.Windows_0.6.119.0_x64.msix`.
- Release bump: Windows/AdminBridge `0.6.119`, Android `versionName 0.6.119`, `versionCode 124`.

## Regola Linux Gateway Update

Quando serve preparare/aggiornare il server Linux Hermes Gateway:

- Primo trasferimento manuale: copiare sul server questi file da `scripts/`: `hermes-hub-linux.sh`, `patch-hermes-gateway-native.py`, `install-hermes-hub-linux.sh`, `hermes-hub-linux-update.sh`, `hermes-hub-linux.service`, `hermes-hub-linux-update.service`, `hermes-hub-linux-update.timer`, `hermes-wait-tailscale.sh`, `hermes-wait-llama.sh`.
- Se il trasferimento lo fa un'altra IA, darle queste istruzioni:

```text
Devi installare Hermes Gateway helper su server Linux.

Dal repo HermesHub prendi SOLO questi file:
- scripts/hermes-hub-linux.sh
- scripts/patch-hermes-gateway-native.py
- scripts/install-hermes-hub-linux.sh
- scripts/hermes-hub-linux-update.sh
- scripts/hermes-hub-linux.service
- scripts/hermes-hub-linux-update.service
- scripts/hermes-hub-linux-update.timer
- scripts/hermes-wait-tailscale.sh
- scripts/hermes-wait-llama.sh
- scripts/hermes-power-monitor.sh
- scripts/hermes-power-monitor.service

Copiali sul server Linux in una cartella temporanea, per esempio:
~/hermes-hub-transfer/scripts/

Poi sul server esegui:
cd ~/hermes-hub-transfer/scripts
chmod +x install-hermes-hub-linux.sh hermes-hub-linux.sh hermes-hub-linux-update.sh hermes-wait-tailscale.sh hermes-wait-llama.sh hermes-power-monitor.sh
./install-hermes-hub-linux.sh --enable-service --enable-auto-update --enable-power-monitor

Verifica:
systemctl --user status hermes-hub.service --no-pager
systemctl --user list-timers | grep hermes-hub
systemctl --user status hermes-power-monitor.service --no-pager
curl -H "Authorization: Bearer hermes-hub" http://127.0.0.1:8642/v1/capabilities

Se curl non risponde, controlla log:
journalctl --user -u hermes-hub.service -n 100 --no-pager

Configurazione visudo (Obbligatoria per Power Monitor):
Poichè il servizio gira come utente e deve eseguire lo spegnimento, aggiungi questa riga tramite `sudo visudo`:
matteo ALL=(ALL) NOPASSWD: /usr/sbin/shutdown

Dopo install iniziale, non trasferire piu' file a mano: per aggiornare usa:
~/.local/bin/hermes-hub-linux-update --restart
```

- Comando prima install sul server:

```bash
cd /percorso/dove/hai/messo/scripts
chmod +x install-hermes-hub-linux.sh hermes-hub-linux.sh hermes-hub-linux-update.sh hermes-wait-tailscale.sh hermes-wait-llama.sh
./install-hermes-hub-linux.sh --enable-service --enable-auto-update
```

- Dopo la prima install, gli update futuri vanno fatti dal server con:

```bash
~/.local/bin/hermes-hub-linux-update --restart
```

- Regola importante: non ricreare ne' caricare l'asset Linux gateway se script/patcher/service gateway non hanno subito cambiamenti e non serve aggiornare il server. Le release app-only devono includere solo gli asset delle app modificate (Android/Windows).
- Creare e caricare l'asset Linux gateway solo quando la release deve aggiornare anche il gateway Linux (modifiche in `scripts/hermes-hub-linux*.sh`, `scripts/patch-hermes-gateway-native.py`, unit systemd, wait scripts, power monitor o contratto server):

```powershell
.\scripts\package-linux-gateway.ps1 -Version X.Y.Z
```

Asset atteso:

```text
artifacts\HermesHub-X.Y.Z-linux-gateway.tar.gz
```

- Quando il gateway cambia, caricare `HermesHub-X.Y.Z-linux-gateway.tar.gz` nella stessa GitHub Release degli asset app. Se il gateway non cambia, non includere questo asset nella release app-only.
- Se Android/Windows mostrano diagnostica 404 su endpoint gateway (`/v1/video/library`, `/v1/hub/memory`, `/v1/hub/state`, `/v1/hub/hardware`), non consigliare mai downgrade o build vecchie: aggiornare il patcher gateway nella release corrente, pubblicare asset Linux gateway, poi lasciare auto-update server o eseguire `~/.local/bin/hermes-hub-linux-update --restart`.
- Memoria Hermes = preferenze/profilo/regole persistenti lato Hermes Agent/server condivise tra app, CLI, jobs e sezioni operative. Non e' RAM del telefono o memoria hardware.
- Non dimenticare che gli script Linux devono restare LF; `.gitattributes` forza LF per `.sh`, `.service`, `.timer`.

Stato server Linux verificato 2026-06-18:

- Boot order impostato senza reboot: `tailscaled.service` -> `hermes-llama.service` -> `hermes-hub.service`.
- `hermes-llama.service` e' system service: richiede Tailscale, attende `tailscale status`, poi carica modello in GPU con llama.cpp TurboQuant.
- `hermes-hub.service` e' user service con `loginctl enable-linger matteo`: resta vivo dopo logout SSH, PATH completo include `%h/.local/bin`, attende Tailscale con `hermes-wait-tailscale.sh`, attende llama.cpp con `hermes-wait-llama.sh` su `http://127.0.0.1:8000/v1/models`, poi espone gateway su `0.0.0.0:8642`.
- Fix applicati sul server diventati default repo: provider gateway `custom`, base `http://127.0.0.1:8000/v1`, path systemd portabili con `%h`, timeout start 1000s e stop 240s.
- Decisione 2026-06-19: non hardcodare nel service release ne' `/home/matteo` ne' il modello Qwen specifico. Il modello viene rilevato da llama.cpp via `/v1/models`; fallback launcher generico `hermes-agent`. Per configurazioni server specifiche usare override/drop-in systemd o variabili env, cosi' gli update futuri non reintroducono LM Studio o path utente sbagliati.
- Verifica finale attesa: `tailscaled.service`, `hermes-llama.service`, `hermes-hub.service` active; `curl -H "Authorization: Bearer hermes-hub" http://127.0.0.1:8642/v1/capabilities` OK; timer auto-update enabled.
- Firewall finale atteso: UFW apre solo `8642/tcp` da LAN `192.168.1.0/24` e su `tailscale0`; nessuna apertura WAN/router.
- Backup creati sul server prima delle modifiche: `/etc/systemd/system/hermes-llama.service.*.bootseq.bak`, `/home/matteo/.config/systemd/user/hermes-hub.service.*.bootseq.bak`, `/etc/ufw/user.rules.*.hermeshub.bak`, `/etc/ufw/user6.rules.*.hermeshub.bak`.

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

Hermes Hub 0.6.119 (Android notifications background reliability):
- Vedi sezione "Release Corrente" all'inizio di questo file per i dettagli completi.

Hermes Hub 0.6.118 (Android notifications debug instrumentation):

Release 0.6.118:
- Android notifiche debug: aperta sessione `android-notifications-missed` con diario `debug-android-notifications-missed.md` e Debug Server remoto su `http://192.168.1.6:7777/event` per raccogliere evidenza runtime dal device.
- Android notifiche debug: la build debug ora traccia scheduling del `WorkManager`, caricamento secret/API key, richiesta HTTP a `/v1/hub/notifications`, filtro `seenHubNotifications`, esito `NotificationManager.notify()` ed eventuali retry del worker.
- Nota tecnica importante: Android continua a usare polling `WorkManager` periodico con intervallo minimo pratico di circa 15 minuti; un cron Hermes ogni minuto non equivale a push realtime.
- Asset release attesi: Windows MSIX `NemoclawChat.Windows_0.6.118.0_x64.msix`, Android APK `HermesHub-0.6.118-android.apk`, Linux Gateway `HermesHub-0.6.118-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.118`, Android `versionName 0.6.118`, `versionCode 123`.

Hermes Hub 0.6.117 (Windows updater senza fallback App Installer UI):

Release 0.6.117:
- Windows updater: rimosso il fallback `Start-Process $packagePath` quando `Add-AppxPackage` fallisce. Invece di aprire l'UI di App Installer che puo' essere bloccata da Smart App Control, l'installer scrive il motivo nel log `%LOCALAPPDATA%\\ChatClaw\\updates\\install-msix-update.log` e termina pulitamente.
- Windows updater: la pagina About mostra anche il path del log installazione MSIX oltre alla cartella update, cosi' l'errore residuo e' leggibile senza finestre di blocco del sistema.
- Asset release inclusi: Windows MSIX `NemoclawChat.Windows_0.6.117.0_x64.msix`, Android APK `HermesHub-0.6.117-android.apk`, Linux Gateway `HermesHub-0.6.117-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.117`, Android `versionName 0.6.117`, `versionCode 122`.

Hermes Hub 0.6.116 (Stop stream reattivo + gateway Windows port-aware):

Release 0.6.116:
- Windows chat: il bottone composer diventa realmente `Invia/Stop` durante lo stream invece di restare disabilitato; al click rilascia subito la UI locale, cancella lo stream client e, in modalita Agente, invia anche `POST /v1/runs/{run_id}/stop` al gateway.
- Android chat/agente: lo stop invia anche la richiesta `POST /v1/runs/{run_id}/stop` quando esiste una run server-side, oltre a cancellare immediatamente il job locale.
- Windows gateway discovery: il failover non e' piu' fissato solo ai candidate hardcoded `:8642/v1`, ma preserva host/porta/path correnti e prova candidate plug-and-play port-aware prima del fallback standard.
- Windows notifiche/chat: le notifiche ora usano la stessa discovery raggiungibile del resto dell'app e la chat eredita il comportamento port-aware quando il gateway configurato richiede una porta non standard o un path gia' personalizzato.
- Asset release inclusi: Windows MSIX `NemoclawChat.Windows_0.6.116.0_x64.msix`, Android APK `HermesHub-0.6.116-android.apk`, Linux Gateway `HermesHub-0.6.116-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.116`, Android `versionName 0.6.116`, `versionCode 121`.

Hermes Hub 0.6.115 (Android notifications auth recovery):

Release 0.6.115:
- Android notifiche auth: `loadGatewaySecret()` ora recupera la secret anche da `LEGACY_SETTINGS_PREFS` quando manca nel prefs corrente, evitando worker notifiche autenticati con il fallback sbagliato dopo update/migrazioni.
- Android notifiche auth: le secret salvate in formato plaintext/legacy vengono ora lette correttamente invece di essere convertite silenziosamente in `hermes-hub`; questo copre i casi in cui la cifratura locale non era riuscita ma il valore era stato comunque persistito.
- Android release: la telemetria di debug per questa analisi resta attiva solo in `BuildConfig.DEBUG`; la build release non prova a inviare eventi di debug.
- Asset release inclusi: Windows MSIX `NemoclawChat.Windows_0.6.115.0_x64.msix`, Android APK `HermesHub-0.6.115-android.apk`, Linux Gateway `HermesHub-0.6.115-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.115`, Android `versionName 0.6.115`, `versionCode 120`.

Hermes Hub 0.6.114 (Notifiche reliability + News/Video fullscreen):

Hermes Hub 0.6.113 (Notifiche hotfix + Windows Smart App Control):

Release 0.6.113:
- Hotfix gateway Linux: verifica e hardening del blocco notifiche nel patcher, per evitare scenario dove le route `/v1/hub/notifications` esistono ma le funzioni backend non sono definite (500 Internal Server Error sul server dopo auto-update).
- Gateway Linux: launcher `hermes-hub-linux.sh` aggiornato per esportare `HERMES_HUB_NOTIFICATIONS_PATH` al default `~/.hermes/hub_notifications.json` se non impostato.
- Windows: fix installazione post-SAC; l'app MSIX self-signed viene ora firmata con certificato `CN=AppPublisher` e installata via `Add-AppxPackage -ForceUpdateFromAnyVersion -ForceApplicationShutdown`, superando il blocco Smart App Control quando il certificato e' in `TrustedPeople`.
- Windows: mantenuta compatibilita' updater in-app 0.6.69+ verificato; update futuro via UI resta funzionante perche' il nuovo MSIX e' firmato con lo stesso certificato.
- Android: conferma funzionamento notifiche e aggiornamento corretto a 0.6.113 tramite `versionCode 118`.
- Asset release inclusi: Windows MSIX `NemoclawChat.Windows_0.6.113.0_x64.msix`, Android APK `HermesHub-0.6.113-android.apk`, Linux Gateway `HermesHub-0.6.113-linux-gateway.tar.gz`.
- Release bump: Windows/AdminBridge `0.6.113`, Android `versionName 0.6.113`, `versionCode 118`.
- Nota server: dopo pubblicazione release, eseguire sul server Linux `~/.local/bin/hermes-hub-linux-update --restart` per applicare il fix notifiche. Verificare con `curl -H "Authorization: Bearer hermes-hub" http://127.0.0.1:8642/v1/hub/notifications` che risponda 200 e non 500.

Hermes Hub 0.6.112 (Hermes notifications inbox):

Release 0.6.112:
- Gateway Linux: aggiunto inbox notifiche persistente `GET/POST /v1/hub/notifications` e `PATCH /v1/hub/notifications/{id}` per segnare lettura.
- Contratto agente: cron/monitor/agent devono pubblicare avvisi importanti su `/v1/hub/notifications` con `title`, `message`, `severity`, `source`, `conversation_prompt`.
- Windows: nuova sezione `Notifiche`, apertura chat da notifica e poll toast mentre Hermes Hub e' in esecuzione/background.
- Android: nuova sezione `Notifiche`; WorkManager controlla periodicamente l'inbox anche con app chiusa/schermo spento e mostra notifiche di sistema quando Android consente il background polling.
- Nota tecnica: Android usa polling periodico OS (minimo pratico WorkManager circa 15 minuti), non FCM realtime. Windows app chiusa completamente non puo' ricevere push vero senza WNS/cloud o helper residente; l'inbox gateway resta persistente e il toast arriva quando processo app e' attivo.
- Release bump: Windows/AdminBridge `0.6.112`, Android `versionName 0.6.112`, `versionCode 117`.

Hermes Hub 0.6.111 (News folder-only hotfix):

Release 0.6.111:
- Hotfix Gateway Linux: `/v1/news/library` non scansiona piu' `HERMES_MEDIA_ROOTS` insieme alla cartella news. Prima poteva dichiarare `/home/matteo/news` ma includere HTML da `~/.hermes/profiles`, `~/.hermes/node`, ecc.
- La sezione News ora deve mostrare solo file `.html/.htm` presenti nella cartella richiesta da `path`/`library_path` o nel default `HERMES_NEWS_LIBRARY_PATH`.
- Mantiene i link media con `root` esplicito per cartelle news custom configurate da Windows/Android.
- Release bump: Windows/AdminBridge `0.6.111`, Android `versionName 0.6.111`, `versionCode 116`.

Hermes Hub 0.6.110 (Configurable News library path):

Release 0.6.110:
- Windows/Android: aggiunta impostazione `Cartella news Hermes`, default `/home/matteo/news`, modificabile come destinazione per articoli/giornali HTML.
- Windows/Android: metadata, prompt workspace News e messaggi UI usano `newsLibraryPath` invece di hardcodare sempre `/home/matteo/news`.
- Windows/Android: il refresh della sezione News chiama `/v1/news/library?path=...`, cosi' la lista articoli legge la cartella news scelta nelle impostazioni.
- Gateway Linux patcher: `/v1/news/library` accetta `path`/`library_path` autenticato e genera URL media con `root` esplicito; `/v1/media/...` risolve quel root extra solo dentro la root richiesta, mantenendo compatibilita' col default `HERMES_NEWS_LIBRARY_PATH`.
- Verifica locale: `python -m py_compile scripts/patch-hermes-gateway-native.py`, `dotnet build src/NemoclawChat.Windows/NemoclawChat.Windows.csproj -c Debug -r win-x64`, `./gradlew.bat -p src/NemoclawChat.Android assembleDebug`.
- Release bump: Windows/AdminBridge `0.6.110`, Android `versionName 0.6.110`, `versionCode 115`.

Hermes Hub 0.6.109 (Minimal News reader and fullscreen video):

Release 0.6.109:
- Windows News: sezione ridisegnata minimalista, senza pannelli tecnici/brief/feedback in vista primaria; mostra solo card articolo HTML a sinistra e reader WebView2 interno a destra.
- Windows News: fix runtime WebView2; il reader chiama `EnsureCoreWebView2Async()` prima di `NavigateToString`, evitando errore "valid CoreWebView2 is not present".
- Android News: home ridotta a lista card HTML + refresh; tap su card apre pagina HTML in WebView interna con `loadDataWithBaseURL`, senza mostrare sorgente HTML come testo.
- Windows Video fullscreen: bottone piu' chiaro, doppio tap sul player apre fullscreen e avvia playback.
- Android Video fullscreen: dialog immersivo reale, nasconde status/nav bar, forza landscape durante fullscreen e ripristina orientamento/UI all'uscita.
- Release bump: Windows/AdminBridge `0.6.109`, Android `versionName 0.6.109`, `versionCode 114`.

Hermes Hub 0.6.108 (Video streaming and compat playback):

Release 0.6.108:
- Hotfix vision: test live 2026-06-21 conferma che il gateway/Hermes converte `input_image`/`image_url` in placeholder tipo `[screenshot]` o `attachment:image/png`; il tool `vision_analyze` riceve sorgente non valida e il modello deduce falsamente "non supporto vision".
- Windows/Android: se upload immagine su `/v1/media/upload` riesce, il payload al modello non include piu' anche la data URL inline; resta solo prompt testuale con path server esatto per `vision_analyze`. Questo evita che Hermes scelga il placeholder invece del file reale.
- Prompt allegati piu' rigido: per leggere immagini deve chiamare `vision_analyze` usando il path server come `image_url`; vietati `attachment:image/png`, `None`, path `/tmp` inventati e URL incompleti.
- Video streaming: il feed gateway non espone piu' `playback_url` come `/v1/media/{id}?format=mp4` di default, perche' forza transcode/cache completa prima del play e rende lento anche un file piccolo.
- Nuovo contratto video: `playback_url` punta allo stream originale `/v1/media/{id}`, mentre `compat_url` punta al fallback transcodato `/v1/media/{id}?format=mp4`.
- Gateway media proxy aggiunge header `Accept-Ranges: bytes` su stream originale e compat, cosi' i player possono fare richieste parziali stile streaming/Jellyfin.
- Windows/Android Video partono dallo stream originale e passano automaticamente a `compat_url` solo se il player fallisce per codec/formato.
- Compat vecchio gateway: se il feed contiene ancora `playback_url` con `?format=mp4` e manca `compat_url`, i client lo trattano come fallback e usano `media_url` originale come playback primario.
- Release bump: Windows/AdminBridge `0.6.108`, Android `versionName 0.6.108`, `versionCode 113`.

Hermes Hub 0.6.107 (Vision upload bridge):

Release 0.6.107:
- Hotfix vision reale: test live 2026-06-21 mostra che `hermes-agent` dietro gateway riceve token dell'immagine ma non interpreta `input_image/image_url` come vision nativa; risponde `NO_IMAGE` o `modello non supporta vision`.
- Windows/Android: prima dello stream, le immagini allegate vengono caricate su gateway tramite nuovo endpoint `POST /v1/media/upload`.
- Windows/Android: il prompt inviato a Hermes contiene anche `percorso server` e `URL proxy` degli allegati caricati, cosi' tool come `vision_analyze` non ricevono piu' `None` e possono leggere file/URL reali sul server.
- Gateway Linux: nuovo endpoint protetto `POST /v1/media/upload` accetta JSON `{filename,mime_type,data_url}`, salva in `HERMES_HUB_UPLOAD_PATH` e restituisce `path`, `server_path`, `media_url`.
- Gateway Linux: nuova cartella default upload `~/.hermes/hub_uploads`, inclusa in `HERMES_MEDIA_ROOTS`; `hermes-hub-linux.sh` la crea/esporta.
- Mantiene input image inline per futuri backend vision nativi, ma non dipende piu' da quello per i tool vision.
- Release bump: Windows/AdminBridge `0.6.107`, Android `versionName 0.6.107`, `versionCode 112`.

Hermes Hub 0.6.106 (Markdown, vision and media playback):

Release 0.6.106:
- Windows/Android: renderer Markdown finale allineato meglio allo streaming; aggiunto supporto esplicito a tabelle pipe Markdown e liste ordinate, cosi' risposte e tabelle non vengono compattate male a fine generazione.
- Windows/Android: fallback Chat Completions conserva contenuti vision. Se Responses non e' disponibile, le immagini vengono inviate come `image_url` standard invece di perdere gli allegati.
- Nota post-release: il test `VISION_OK` era insufficiente per validare percezione reale; retest con immagine concreta ha mostrato che il backend non interpreta `input_image/image_url` come vision nativa. Fix completo spostato in 0.6.107 con upload server path/URL per tool vision.
- Windows: aggiunto paste-image reale da appunti nel composer e nel menu `+`; l'immagine diventa allegato data URL vision con preview.
- Android: aggiunto `Incolla immagine` best-effort dal clipboard Android; usa URI immagine o data URL se disponibili, altrimenti chiede di usare `Allega file`.
- Windows Video: i link `/v1/media/...` usati dal player aggiungono `hub_token` query token, perche' `MediaPlayerElement` non puo' inviare header `Authorization` su URI diretto.
- Gateway Linux: media proxy accetta `hub_token`/`api_key`/`token` query solo se combacia con le API key valide; serve al player Windows, non apre media pubblicamente.
- Gateway Linux: transcode MP4 compat passa a cache `compatv2`, H.264 Main level 4.2, `yuv420p`, `avc1`, `faststart`, audio AAC; se il sorgente non ha audio aggiunge traccia AAC silenziosa per player browser/WebView piu' rigidi.
- Android Video: ExoPlayer mostra errore diagnostico nella UI se la riproduzione fallisce, invece di restare silenzioso.
- Release bump: Windows/AdminBridge `0.6.106`, Android `versionName 0.6.106`, `versionCode 111`.

Hermes Hub 0.6.105 (Media transcode hotfix):

Release 0.6.105:
- Hotfix Gateway Linux: transcode `GET /v1/media/{media_id}?format=mp4` passa `-f mp4` a ffmpeg quando scrive il file temporaneo `.compat.mp4.tmp`; evita errore runtime `Unable to choose an output format`.
- Verifica live richiesta: `/v1/media/...` originale ora risponde 200; il path compat `?format=mp4` deve rispondere 200 dopo auto-update gateway 0.6.105.
- Release bump: Windows/AdminBridge `0.6.105`, Android `versionName 0.6.105`, `versionCode 110`.


Hermes Hub 0.6.104 (Cron, media proxy and News HTML):

Release 0.6.104:
- Windows/Android: sezioni user-facing `Jobs` e `Runs` rimosse; nuova sezione unica `Cron` mostra cron reali del gateway via `GET /api/jobs?type=cron&include_disabled=1`.
- Cron: azioni `Esegui ora`, `Pausa/Riprendi`, `Elimina` usano endpoint ufficiali `/api/jobs/{id}/run`, `/pause`, `/resume`, `DELETE /api/jobs/{id}`; slash command `/cron`, rimossi `/runs` e `/tasks`.
- Windows chat: il blocco invio e' legato alla chat/composer corrente; una chat in generazione non impedisce di inviare prompt in una nuova chat.
- Gateway Linux: aggiunto endpoint protetto `GET /v1/media/{media_id}` con risoluzione sicura dentro `HERMES_MEDIA_ROOTS`/`HERMES_VIDEO_LIBRARY_PATH`; `?format=mp4` transcodifica/cache con ffmpeg in MP4 H.264 + AAC + yuv420p + faststart.
- Windows/Android Video: i player usano `playback_url` quando presente o aggiungono `?format=mp4` al media proxy; supporto feed esteso a mp4, mov, mkv, webm, avi, wmv, flv, mpeg, ts, m2ts, 3gp, ogv.
- Gateway Linux News: nuova cartella default `HERMES_NEWS_LIBRARY_PATH=/home/matteo/news`, inclusa in `HERMES_MEDIA_ROOTS`; nuovo endpoint protetto `GET /v1/news/library` per file `.html/.htm`.
- Windows News: aggiunto elenco pagine HTML dal gateway e rendering in WebView2 interno, senza aprire app esterne.
- Android News: aggiunto elenco pagine HTML dal gateway e reader WebView interno fullscreen, senza intent esterni.
- Istruzioni Hermes aggiornate: quando Matteo chiede un giornale online/HTML, salvare il file finale in `/home/matteo/news` o `HERMES_NEWS_LIBRARY_PATH` cosi' appare nella sezione News.
- Release bump: Windows/AdminBridge `0.6.104`, Android `versionName 0.6.104`, `versionCode 109`.

Hermes Hub 0.6.103 (Stability hardening):

Release 0.6.103:
- Windows: hardening `ChatArchiveStore`; `Load()` ora restituisce copie difensive profonde e le mutazioni/scritture archivio sono serializzate sotto lock, riducendo race tra checkpoint streaming, salvataggio finale e UI.
- Windows: hardening `WorkspaceRequestStore`; cache non espone piu' record mutabili condivisi e salvataggio/feedback sono serializzati.
- Windows: hardening `VideoFeedbackStore`; feedback video serializzati sotto lock, riducendo rischio di perdere conteggio/stato se arrivano refresh o feedback ravvicinati.
- Android: pagina Runs/Operator usa lo scope Compose esistente per RPC Hermes invece di creare `CoroutineScope` orfani a ogni click.
- Android: RPC GET manuali e feed Video conservano HTTP status; 404/500 non vengono piu' presentati come risposta riuscita.
- Android: allegati letti a stream con cap massimo prima di base64; evita di caricare file oltre limite interamente in RAM.
- Android: archivio chat e task locali serializzati con lock sull'intero read-modify-write, riducendo race tra checkpoint streaming/finale e update Jobs.
- Android: parser archivio/task piu' resiliente; un record JSON corrotto viene saltato invece di svuotare tutta la lista.
- Android: `visualBlocksVersion` JSON null non viene piu' riletto come `0`.
- Android: parser asset GitHub update usa `optJSONObject` e ignora asset malformati/senza URL invece di fallire l'intero controllo update.
- Release bump: Windows/AdminBridge `0.6.103`, Android `versionName 0.6.103`, `versionCode 108`.

Hermes Hub 0.6.102 (Simple Jobs/Runs and video path):

Release 0.6.102:
- Windows: bottone impostazioni header abbassato sotto la titlebar estesa, con riga superiore portata a 64 px.
- Windows Runs: aggiunta card primaria `Avvia lavoro in background` con input naturale e pulsanti `Avvia lavoro`, `Crea video`, `Controlla stato`, `Vedi lavori`; pannelli endpoint/JSON spostati sotto `Avanzate tecniche`.
- Windows Jobs: copy e CTA resi non tecnici (`Lavori Hermes`, `Cosa deve fare Hermes?`, `Crea lavoro`) e aggiunto template video che salva in `/home/matteo/video`.
- Android Runs: aggiunta card primaria `Avvia lavoro in background`, con input naturale e pulsanti rapidi per run generico, video e lista lavori.
- Android Jobs: copy e CTA semplificati, template video incluso.
- Gateway Linux: cartella video default cambiata a `/home/matteo/video`; `HERMES_MEDIA_ROOTS` la include per feed `/v1/video/library`.
- Windows/Android: default `videoLibraryPath` e migrazione blank/vecchia cartella `.hermes/media/video` verso `/home/matteo/video`.
- Windows Video: feed prova prima `/v1/video/library` dal gateway e poi fallback locale; evita di creare cartelle Windows false quando path server e' Linux.
- Release bump: Windows/AdminBridge `0.6.102`, Android `versionName 0.6.102`, `versionCode 107`.

Hermes Hub 0.6.101 (Generic files hotfix):

Release 0.6.101:
- Hotfix Windows: aggiunta risorsa globale `AccentBrush`, necessaria a chat/streaming bubble; evita errore runtime `Cannot find a resource with the given key: AccentBrush`.
- Hotfix Android release: APK release firmato con lo stesso debug keystore locale usato dagli update correnti; non caricare piu' `app-release-unsigned.apk`.
- Windows/Android: default allegati portato a 150 MB e migrazione vecchio valore salvato `6` -> `150`.
- Linux Gateway: default `HERMES_HUB_MAX_UPLOAD_MB=150` in launcher, `.env` e service; capabilities espone `max_upload_mb`.
- Windows/Android: allegati generici `*/*` mantenuti sopra composer con preview immagini o card file.
- Release bump: Windows/AdminBridge `0.6.101`, Android `versionName 0.6.101`, `versionCode 106`.

Hermes Hub 0.6.100 (Message/media UX controls):

Release 0.6.100:
- Windows/Android: metriche messaggi configurabili singolarmente: TTFT, token/sec, token output, token input, contesto e durata. Il toggle generale `Metriche sotto messaggi` resta indipendente.
- Windows: impostazioni chat importanti spostate fuori da `Avanzate`: modello/agente, tool call, dettagli tecnici e metriche sono visibili direttamente nella pagina impostazioni.
- Windows: composer mostra anteprima allegati vision sopra la textbox con miniatura, nome file, mime type, dimensione e pulsante rimozione.
- Android: composer mostra anteprima allegati vision con thumbnail reale, nome file, mime type, dimensione e rimozione; non e' piu' solo un chip testuale.
- Windows/Android Video: aggiunto `URL video manuale` validato http/https, riproducibile direttamente nel player quando la sync cartella/libreria non basta.
- Windows Video: feedback e copia riferimento funzionano anche per URL video manuali, non solo file locali del feed.
- Nota server/headless: la vision continua a usare data URL inline verso il gateway; il server Ubuntu non richiede GUI per far vedere immagini al modello.
- Release asset attesi: pubblicare anche `HermesHub-0.6.100-linux-gateway.tar.gz` nella release GitHub, anche se il gateway non cambia, per non rompere auto-update server.
- Release bump: Windows/AdminBridge `0.6.100`, Android `versionName 0.6.100`, `versionCode 105`.

Hermes Hub 0.6.99 (UI stability controls):

Release 0.6.99:
- Windows/Android: rimossa la sezione `Voce` dalle navigazioni principali e dagli slash command; la funzione sperimentale resta nel codice ma non viene piu' proposta come area attiva.
- Windows: fix critico `ResetServerContextMeter()` che richiamava se stesso e poteva causare stack overflow su nuova chat/clear.
- Windows/Android: aggiunte impostazioni `Tool call in chat` e `Metriche messaggi`; i tool restano in pannello compatto collassabile, metriche TTFT/token/t/s sono opzionali e non piu' obbligatorie nella UI pulita.
- Windows/Android: limite allegati vision configurabile da 1 a 150 MB, default 6 MB per non saturare memoria/gateway con payload base64 grandi.
- Windows: sidebar profilo resa un vero bottone invece di overlay invisibile; header rimosso `Hermes preset: hermes.local`; `Hardware` rinominato `Prestazioni`.
- Windows: riquadri Prestazioni usano barra utilizzo custom senza animazione reset a ogni refresh da 1 secondo.
- Windows/Android: testi Jobs/Runs chiariti: Jobs = coda lavori tracciabile; Runs = lavori server-side Hermes che possono continuare sul gateway anche se lo stream client cade.
- Gateway Linux patcher: `video_library` espone anche `media_roots` derivati da `HERMES_MEDIA_ROOTS`, cosi' le app possono vedere le cartelle media monitorate dal server.
- Controllo generale: build Windows, build Android debug/release e patcher gateway passano.
- Release bump: Windows/AdminBridge `0.6.99`, Android `versionName 0.6.99`, `versionCode 104`.

Hermes Hub 0.6.98 (Physical SSD grouping):

Release 0.6.98:
- Windows/Android Prestazioni raggruppa le partizioni dello stesso disco fisico: `/`, `/boot`, `/boot/efi` su `nvme0n1` diventano un solo riquadro `SSD 0`, non tre dischi separati.
- Logica grouping: partizioni `nvme...pN` vengono aggregate al device base; se esiste un solo disco fisico NVMe, anche root LVM `/dev/mapper/...` viene associato a quello stesso SSD.
- Il dettaglio SSD mostra spazio usato/libero/totale aggregato, partizioni e device sottostanti, mantenendo grafico utilizzo e temperatura SSD quando disponibile.
- Pulizia codice hardware: rimosso vecchio gauge Android inutilizzato e campo Windows non usato; helper disk grouping isolati per migliorare manutenzione.
- Controllo generale release: build Windows, build Android debug/release e patcher gateway passano; endpoint live hardware server resta sano.
- Release bump: Windows/AdminBridge `0.6.98`, Android `versionName 0.6.98`, `versionCode 103`.

Hermes Hub 0.6.97 (Task Manager hardware layout):

Release 0.6.97:
- Windows/Android Prestazioni ridisegnata in stile Gestione attivita: lista verticale a sinistra con CPU, Memoria, Swap, Ethernet, GPU e dischi; pannello dettaglio a destra per il componente selezionato.
- Ogni riquadro laterale mostra valore live e barra utilizzo; il pannello dettaglio mostra titolo, sottotitolo, valore principale, grafico utilizzo realtime e grafico temperatura quando il sensore esiste.
- Storia grafici locale mantenuta per circa 120 campioni, aggiornata ogni secondo dal polling hardware.
- Dettagli per componente: CPU core/frequenza/processi/uptime, RAM usata/totale/disponibile, rete down/up/totali, GPU VRAM/temp/power/driver, dischi spazio usato/libero/totale/device.
- Nota tecnica: per i dischi il grafico usa percentuale spazio usato finche' il gateway non espone I/O disco/activity time.
- Release bump: Windows/AdminBridge `0.6.97`, Android `versionName 0.6.97`, `versionCode 102`.

Hermes Hub 0.6.96 (GPU performance monitoring):

Release 0.6.96:
- Gateway Linux hardware endpoint espone `gpus[]` usando `nvidia-smi` quando disponibile nel PATH del servizio.
- Metriche GPU incluse: index, nome modello, utilizzo GPU, utilizzo memoria, VRAM usata/totale, temperatura, power draw/limit e driver NVIDIA.
- Windows/Android Prestazioni mostrano sezione `GPU` con righe `GPU 0`, `GPU 1`, progress bar uso GPU, VRAM, temperatura, watt e driver, piu' vicina alla vista Prestazioni di Gestione attivita.
- Fallback pulito: se `nvidia-smi` manca o non risponde, UI mostra messaggio diagnostico invece di nascondere silenziosamente il problema.
- Release bump: Windows/AdminBridge `0.6.96`, Android `versionName 0.6.96`, `versionCode 101`.

Hermes Hub 0.6.95 (Hardware performance polish):

Release 0.6.95:
- Windows/Android: sezione Hardware rinominata in `Prestazioni`, con testi e metriche piu' vicini alla sezione Prestazioni di Gestione attivita Windows.
- Temperature normalizzate con nomi leggibili: `CPU package`, `CPU CCD`, `SSD NVMe`, `SSD NVMe controller`, `SSD NVMe NAND`, `RAM DIMM`, `Ethernet controller`.
- Filtri anti-letture spurie: nascosti sensori con temperatura negativa o oltre 150 C e soglie impossibili tipo `65261 C`/`254 C`; `Sensor 2` NVMe viene nascosto quando e' la lettura fittizia sotto zero gia' coperta dal composito.
- Gateway Linux patcher aggiorna anche la raccolta sensori lato server, cosi' le installazioni nuove o auto-aggiornate non espongono piu' valori termici palesemente falsi.
- Chiarimento UI: `Memoria` nella pagina prestazioni diventa `Memoria RAM`, distinta dalla memoria Hermes/profilo agente.
- Release bump: Windows/AdminBridge `0.6.95`, Android `versionName 0.6.95`, `versionCode 100`.

Hermes Hub 0.6.94 (Vision input and detached runs):

Release 0.6.94:
- Windows: il menu `+` allega immagini reali (`PNG/JPEG/WebP/BMP`, max 6 MB) e le invia a Hermes come `input_image` data URL nel payload Responses; non passa piu' solo path locali inutilizzabili dal server.
- Android: il menu `+` apre picker immagini reale, mostra chip allegati nel composer e invia immagini a Hermes vision come `input_image` data URL; invio consentito anche con sola immagine.
- Modalita `Agente`: Windows e Android avviano `/v1/runs` server-side invece di tenere solo uno stream Responses legato al client. Se l'app viene chiusa o la connessione eventi cade, il run continua sul gateway/server e resta interrogabile da `/v1/runs/{run_id}`.
- Gateway Linux patcher: run stream TTL portato a 6h e sweep orphan non rimuove riferimenti a run ancora attivi; evita di rompere lavori lunghi senza client collegato.
- Verifica live 2026-06-20: Ubuntu server headless accetta immagine inline su `/v1/responses` e modello vision risponde `VISION_OK`; non serve GUI sul server, serve solo payload immagine passato al provider.
- Nota costo: anche immagini piccole possono consumare molti token input sul modello vision; client limita immagini a 6 MB per evitare payload/gateway troppo grandi.
- Release bump: Windows/AdminBridge `0.6.94`, Android `versionName 0.6.94`, `versionCode 99`.

Hermes Hub 0.6.93 (Gateway status endpoints):

Release 0.6.93:
- Gateway Linux patcher aggiunge endpoint protetti `GET /v1/video/library`, `GET/PATCH /v1/hub/memory`, `GET/POST /v1/hub/state` e `DELETE /v1/hub/state/{id}`; i tre controlli Android non devono piu' finire in 404 dopo auto-update server.
- Video Library ora risponde 200 anche con cartella vuota/non popolata, legge `HERMES_VIDEO_LIBRARY_PATH` e restituisce `items: []` con path monitorato invece di errore.
- Memoria Hermes usa store JSON server-side `HERMES_HUB_MEMORY_PATH` e chiarisce che e' profilo/preferenze Hermes Agent, non RAM telefono.
- Hub State usa store JSON server-side `HERMES_HUB_STATE_PATH` per feedback/stato operativo sincronizzato da app.
- Android/Windows diagnostica: rimossi riferimenti a vecchia build 0.6.42; azione corretta e' aggiornare Hermes Gateway alla latest release e riavviare/autoupdate.
- Release bump: Windows/AdminBridge `0.6.93`, Android `versionName 0.6.93`, `versionCode 98`.

Hermes Hub 0.6.92 (Android tool UX stability):

Release 0.6.92:
- Hotfix Android tool UX: eventi/tool payload (`hermes.tool.*`, `tool.completed`, `tool_result`, `function_call_output`) non possono piu' cadere nel fallback `TextDelta`; i risultati tool restano nel pannello tool collassabile invece di allungare il messaggio finale.
- Hotfix Android scroll: rimosso autoscroll a ogni token/tool update. Ora la chat scrolla al fondo solo quando nasce lo stream, poi l'utente puo' scorrere liberamente senza essere riportato al banner tool.
- Release bump: Windows/AdminBridge `0.6.92`, Android `versionName 0.6.92`, `versionCode 97`.

Hermes Hub 0.6.91 (Markdown ordered list preservation):

Release 0.6.91:
- Hotfix Windows render finale: il Markdown renderer ora riconosce righe numerate (`1. ...`, `2) ...`) come blocchi lista ordinata e non le fonde piu' in un unico paragrafo.
- Risolve bug test 0.6.90: durante lo streaming le righe numerate apparivano una per riga, ma a fine generazione il render finale le compattava tutte in una riga lunga.
- Release bump: Windows/AdminBridge `0.6.91`, Android `versionName 0.6.91`, `versionCode 96`.

Hermes Hub 0.6.90 (Windows metrics fallback):

Release 0.6.90:
- Hotfix Windows metriche streaming: se lo stream produce testo ma l'evento finale `StreamDone` non arriva/risulta perso, la UI sintetizza metriche conservative da clock locale (`TTFT`, durata, token stimati, t/s filtrati <=70) e completa comunque la bubble.
- Verifica runtime 2026-06-20 su Windows 0.6.89 installata: risposta lunga 90 righe non ha freezato (`notResponding=0`, producer `346` delta -> `186` batch), ma snapshot aveva `Stats=null`; 0.6.90 chiude questo buco.
- Mantiene fix 0.6.89 su `response.output_item.done` message e tutte le ottimizzazioni llama.cpp precedenti.
- Release bump: Windows/AdminBridge `0.6.90`, Android `versionName 0.6.90`, `versionCode 95`.

Hermes Hub 0.6.89 (Responses output item stability):

Release 0.6.89:
- Hotfix Android Responses SSE: `response.output_item.done` con item `message` non viene piu' trattato come nuovo `TextDelta`, per evitare duplicazione del testo gia' arrivato in streaming e metriche/stato finali incoerenti su llama.cpp.
- Hotfix Windows Responses SSE: `response.output_item.done` con item `message` non genera piu' un tool-end finto; conserva solo eventuali Visual Blocks.
- Verifica live gateway 2026-06-20: payload reale `response.output_item.done` contiene `item.type=message` e `content[].text`, quindi va ignorato come delta testo per client streaming.
- Mantiene tutte le ottimizzazioni 0.6.87/0.6.88 per batching, metriche conservative, raw events, checkpoint e protezioni anti-freeze.
- Release bump: Windows/AdminBridge `0.6.89`, Android `versionName 0.6.89`, `versionCode 94`.

Hermes Hub 0.6.88 (Android stream wording polish):

Release 0.6.88:
- Polish Android stato streaming: rimossi residui user-facing `Processing prompt` da default/event/activity log; ora usa `llama.cpp: prefill prompt` e `Prefill prompt X%` anche nelle righe diagnostiche.
- Mantiene tutte le ottimizzazioni 0.6.87 per burst llama.cpp, batching, metriche conservative, raw events, checkpoint e protezioni anti-freeze.
- Release bump: Windows/AdminBridge `0.6.88`, Android `versionName 0.6.88`, `versionCode 93`.

Hermes Hub 0.6.87 (Windows + Android llama.cpp stream stability):

Release 0.6.87:
- Hotfix Windows streaming UI: durante `await foreach` dello stream, il loop cede esplicitamente il thread UI ogni ~33ms con flush preview e micro-delay, cosi' WinUI puo' disegnare token/stato invece di restare bloccata e mostrare tutta la risposta solo alla fine.
- `StreamingBubble` espone `FlushPreview()` per forzare update testo plain leggero fuori dal `DispatcherTimer` quando il timer non riesce a tickare per eventi SSE molto ravvicinati.
- Hotfix Windows piu' profondo: producer SSE/JSON gira su threadpool via `Channel<ChatStreamEvent>` bounded con backpressure, cosi' `ReadLineAsync` e parsing burst llama.cpp non saturano il thread UI.
- Ottimizzazione Windows per burst llama.cpp: producer coalescia `StreamTextDelta`/`StreamThinkingDelta` in batch max ~33ms o 2048 caratteri prima del canale UI, riducendo drasticamente wakeup/render quando il server spara token ravvicinati.
- Ottimizzazione Android per burst llama.cpp: `streamChatRequest` coalescia testo/reasoning in batch max ~33ms o 2048 caratteri prima di aggiornare Compose, evitando ricomposizioni per ogni micro-delta.
- Fix Android SSE Responses: eventi `response.output_text.done` e `response.completed` non vengono piu' trattati come delta testo finale, evitando duplicazione del contenuto gia' arrivato in streaming e metriche gonfiate.
- Raw events Android cappati a 200 per chat negli snapshot e, con raw hidden, non sovrascrivono piu' lo stato UI con `Evento Hermes...`.
- Ottimizzazione Windows raw events: con `Dettagli chat avanzati` OFF, i raw Hermes events non passano piu' nel channel UI e non vengono salvati negli snapshot; con dettagli ON sono cappati a 200 eventi per chat.
- Ottimizzazione Windows render finale: Markdown completo viene applicato solo sotto 32k caratteri; risposte molto lunghe restano in preview plain con cap live 120k caratteri, evitando freeze del thread UI durante render finale.
- Ottimizzazione checkpoint stream Windows/Android: snapshot parziali passano da 2s a 5s e salvano preview max 50k caratteri; snapshot finale resta completo. Riduce copie grandi e IO durante risposte lunghe.
- Protezione memoria Windows: accumulatori stream service/UI cappati a 2M caratteri con marker di troncamento, per evitare crescita memoria se llama.cpp genera output enorme.
- Metriche t/s rese piu' conservative su Windows/Android per llama.cpp: accetta t/s server solo con almeno 8 token output, stima locale solo su finestra >=1.5s, usa `(tokens - 1) / durata`, scarta valori oltre 70 t/s.
- Verifica live gateway 2026-06-19 su `http://hermes:8642/v1/responses`: stream lungo ha prodotto burst reali (`291` delta, `124` gap <=33ms, min gap `0ms`), quindi batching 33ms e' necessario; altra prova 80 righe ha prodotto stima locale raw `87.7 t/s`, correttamente nascosta dal filtro >70.
- Diagnostica Windows: producer stream scrive in `%LOCALAPPDATA%\ChatClaw\logs\app.log` riepilogo leggero con durata, delta input, batch emessi e max batch chars, utile per verificare test reali senza appesantire UI.
- Stati stream piu' espliciti per llama.cpp: Android traduce `PromptProgress` generico in `llama.cpp: prefill prompt`, mostra `attesa primo token`/`elaborazione prompt` se il server non manda reasoning; Windows parte da `Invio prompt a Hermes...` e usa `llama.cpp: prefill prompt...`.
- Obiettivo test: quando il server manda delta molto rapidi, finestra non deve andare in "Non risponde" e la risposta deve apparire progressiva.
- Verifica locale: `dotnet build src\NemoclawChat.Windows\NemoclawChat.Windows.csproj -c Debug -p:Platform=x64` OK; `.\gradlew.bat :androidApp:assembleDebug` OK.
- Release bump locale: Windows/AdminBridge `0.6.87`, Android `versionName 0.6.87`, `versionCode 92`.

Hermes Hub 0.6.86 (Windows + Android endpoint correction):

Release 0.6.86:
- Correzione endpoint Tailscale 2026-06-19: `100.105.46.6` risulta essere `pc-matteo-1` Windows, non server Linux Hermes; per questo la porta `8642` rispondeva con connection refused.
- Default Windows/Android cambiato a `http://hermes:8642/v1` con fallback diretto `http://100.94.223.14:8642/v1`; `100.105.46.6` resta solo ultimo fallback legacy.
- Migrazione settings: se un client ha salvato `100.105.46.6`, lo normalizza al nuovo default `http://hermes:8642/v1`.
- Verifica locale: `http://hermes:8642/v1/capabilities` e `http://100.94.223.14:8642/v1/capabilities` rispondono 200 con API key `hermes-hub`.
- Release bump: Windows/AdminBridge `0.6.86`, Android `versionName 0.6.86`, `versionCode 91`.

Hermes Hub 0.6.85 (Windows + Android llama.cpp stream stability):

Release 0.6.85:
- Stabilita streaming per server llama.cpp: Windows non aggiorna piu' il TextBlock a ogni token, ma usa batching UI circa 15 fps e scroll throttled per evitare freeze quando i delta arrivano rapidi.
- Windows mostra sempre stato operativo anche con dettagli avanzati OFF: processing prompt, connessione stream, attesa primo token, generazione; le metriche finali sono visibili anche nella UI pulita.
- Android mostra stati piu' reali invece del solo `Processing prompt`: connessione stream, prompt inviato/attesa primo token, generazione e tool/reasoning quando arrivano eventi.
- Metriche `t/s` rese conservative: usa timings llama.cpp (`predicted_per_second`) quando disponibili, altrimenti calcolo su finestra primo/ultimo token; valori non plausibili, infiniti o calcolati su finestre sotto 750ms vengono nascosti.
- Asset Linux gateway caricato nella release GitHub: `HermesHub-0.6.85-linux-gateway.tar.gz`, cosi' il server puo' continuare ad auto-aggiornarsi dalla latest release.
- Release bump: Windows/AdminBridge `0.6.85`, Android `versionName 0.6.85`, `versionCode 90`.

Hermes Hub 0.6.84 (Windows + Android fallback isolation):

Release 0.6.84:
- Hotfix isolamento fallback: anche il percorso Chat Completions compat riceve `X-Hermes-Session-Id` e `session_id` namespaced per chat/superficie.
- Questo evita collisioni se Responses/Hermes Native non e' disponibile e il gateway deve usare Chat Completions, dove prima poteva derivare una sessione dal primo messaggio.
- Windows e Android usano gli stessi id isolati gia' introdotti: `hermes-hub:windows-app:<id>` e `hermes-hub:android-app:<id>`.
- Asset Linux gateway caricato nella release GitHub: `HermesHub-0.6.84-linux-gateway.tar.gz`, cosi' il server puo' auto-aggiornarsi dalla latest release.
- Test Matteo 2026-06-19: dopo aggiornamento, le chat Android e Windows risultano separate; Hermes non sembra piu' fondere il contesto tra device.
- Release bump: Windows/AdminBridge `0.6.84`, Android `versionName 0.6.84`, `versionCode 89`.

Hermes Hub 0.6.83 (Windows + Android conversation hotfix):

Release 0.6.83:
- Hotfix Responses context: quando Hermes Hub manda `conversation` namespaced, non manda piu' anche `previous_response_id`, per rispettare il contratto gateway dove i due campi sono mutuamente esclusivi.
- Il gateway risolve il contesto dal mapping server `conversation -> latest response_id`; cosi Windows e Android mantengono cronologia per chat senza retry 400 fragile.
- Mantiene isolamento 0.6.82: `hermes-hub:windows-app:<id>` e `hermes-hub:android-app:<id>` restano separati per superficie e chat locale.
- Release bump: Windows/AdminBridge `0.6.83`, Android `versionName 0.6.83`, `versionCode 88`.

Hermes Hub 0.6.82 (Windows + Android chat isolation):

Release 0.6.82:
- Fix isolamento chat cross-device: ogni richiesta chat Windows/Android invia a Hermes un `conversation` server-side namespaced per superficie e chat locale (`hermes-hub:windows-app:<id>` / `hermes-hub:android-app:<id>`), cosi' Hermes non deve fondere sessioni Android e Windows simultanee.
- Metadata chat aggiunge `hub_conversation` con `scope=per-chat-per-surface`, `isolation_required=true`, `do_not_merge_with_other_conversations=true`, `do_not_merge_with_other_surfaces=true`.
- Policy memoria chiarita: memoria lunga condivisa resta valida solo per preferenze/decisioni stabili; contesto transitorio della chat deve restare isolato nella singola conversation id.
- Migrazione anti-contaminazione: archivi locali salvano `serverConversationId`; se una chat vecchia non ha questo marker, il client non riusa il vecchio `previous_response_id` creato prima dell'isolamento.
- Release bump: Windows/AdminBridge `0.6.82`, Android `versionName 0.6.82`, `versionCode 87`.

Hermes Hub 0.6.81 (Windows + Android stability):

Release 0.6.81:
- Android: se Hermes/Gateway risponde 401 o errore recuperabile quando viene inviato `previous_response_id`, il client ritenta automaticamente lo stesso prompt senza `previous_response_id` prima di dichiarare API key rifiutata. Questo evita il blocco dopo il primo messaggio quando il contesto server e' sporco o non allineato.
- Android/Windows: se il retry senza `previous_response_id` sblocca la risposta e il gateway non restituisce un nuovo response id, il client cancella l'id vecchio invece di ripresentarlo al turno successivo.
- Windows: streaming chat reso piu' stabile sotto risposte lunghe; durante lo stream mostra testo plain leggero e renderizza Markdown solo a risposta completata, evitando render completo a ogni token.
- Windows: salvataggi snapshot chat spostati fuori dal thread UI e refresh recenti reso dispatcher-safe, riducendo rischio freeze/"Non risponde" durante messaggi lunghi.
- Release bump: Windows/AdminBridge `0.6.81`, Android `versionName 0.6.81`, `versionCode 86`.

Hermes Hub 0.6.80 (Linux Gateway):

Release 0.6.80:
- Auto-update gateway Linux cambiato da check giornaliero 04:20 a check ogni 2 minuti.
- Timer user systemd: `OnBootSec=2min`, `OnUnitActiveSec=2min`, `AccuracySec=30s`.
- Update service resta `hermes-hub-linux-update --restart`: controlla GitHub Releases latest, scarica solo se versione nuova e riavvia `hermes-hub.service` solo dopo install nuova.
- Asset update server atteso: `artifacts/HermesHub-0.6.80-linux-gateway.tar.gz`.

Hermes Hub 0.6.79 (Linux Gateway):

Release 0.6.79:
- Gateway Linux service portabile: usa `%h` invece di `/home/matteo` per PATH e `ExecStartPre`.
- Wait Tailscale/llama.cpp tornano obbligatori: se non pronti, systemd deve fallire/ritentare invece di avviare gateway senza backend.
- Service non hardcoda piu' `HERMES_INFERENCE_MODEL`; il launcher rileva il modello da llama.cpp `/v1/models`, fallback generico `hermes-agent`.
- Launcher esporta PATH con `$HOME/.local/bin`, `$HOME/.hermes/bin`, `$HOME/.hermes/node/bin` prima di avviare Hermes.
- Asset update server atteso: `artifacts/HermesHub-0.6.79-linux-gateway.tar.gz`.

Hermes Hub 0.6.78 (Linux Gateway):

Release 0.6.78:
- Correzione 2026-06-19: `hermes-hub-linux.service` usa `%h` invece di `/home/matteo`, quindi la release e' installabile anche su utenti Linux diversi.
- Correzione 2026-06-19: `ExecStartPre` non e' piu' best-effort. Deve attendere davvero `hermes-wait-tailscale.sh` e `hermes-wait-llama.sh`; se Tailscale o llama.cpp non sono pronti, systemd deve ritentare invece di avviare gateway senza backend.
- Correzione 2026-06-19: il service release non imposta piu' `HERMES_INFERENCE_MODEL` hardcoded su Qwen. Il launcher legge il modello esposto da llama.cpp su `/v1/models`; fallback solo generico `hermes-agent`.
- Correzione 2026-06-19: `hermes-hub-linux.sh` esporta PATH con `$HOME/.local/bin`, `$HOME/.hermes/bin` e `$HOME/.hermes/node/bin`, cosi' `hermes` viene trovato anche in avvio systemd/headless.
- `hermes-hub-linux.sh` aspetta comunque Tailscale e llama.cpp internamente con `HERMES_WAIT_ON_START=true`, quindi il gateway non parte prima che `tailscaled` e `http://127.0.0.1:8000/v1/models` siano pronti.
- Asset update server atteso: `artifacts/HermesHub-0.6.78-linux-gateway.tar.gz`.

Hermes Hub 0.6.77 (Linux Gateway):

Release 0.6.77:
- Hotfix service wait path: `ExecStartPre` usa i path con estensione `.sh` come sul server (`/home/matteo/.local/bin/hermes-wait-tailscale.sh`, `/home/matteo/.local/bin/hermes-wait-llama.sh`).
- Installer/updater creano sia symlink `.sh` sia alias senza estensione per compatibilita'.
- Asset update server atteso: `artifacts/HermesHub-0.6.77-linux-gateway.tar.gz`.

Hermes Hub 0.6.76 (Linux Gateway):

Release 0.6.76:
- Gateway Linux production defaults allineati al server Hermes llama.cpp: user service PATH completo, provider `custom`, base `http://127.0.0.1:8000/v1`, modello `HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive:IQ4_XS`.
- Service gateway ora aspetta Tailscale e llama.cpp con `ExecStartPre=/home/matteo/.local/bin/hermes-wait-tailscale.sh` e `ExecStartPre=/home/matteo/.local/bin/hermes-wait-llama.sh`.
- Aggiunti script installati/versionati `hermes-wait-tailscale.sh` e `hermes-wait-llama.sh`; installer, updater e package Linux li includono e li linkano in `~/.local/bin`.
- Timeout service: `TimeoutStartSec=1000`, `TimeoutStopSec=240`.
- Asset update server atteso: `artifacts/HermesHub-0.6.76-linux-gateway.tar.gz`.

Hermes Hub 0.6.75 (Linux Gateway):

Release 0.6.75:
- Auto-update gateway Linux: timer user systemd ora controlla update anche a ogni boot (`OnBootSec=2min`) oltre al check giornaliero 04:20 con jitter.
- Asset update server atteso: `artifacts/HermesHub-0.6.75-linux-gateway.tar.gz`.

Hermes Hub 0.6.74 (Linux Gateway):

Release 0.6.74:
- Decisione operativa 2026-06-18: basta gateway su Windows; gateway produzione unico e' Linux sul server Hermes. Windows resta solo client.
- Fix auth gateway Linux: launcher/service esportano `API_SERVER_KEY`, `HERMES_API_KEY`, `HERMESAPIKEY`, `HERMES_HUB_API_KEY`, `HERMES_GATEWAY_API_KEY` con default `hermes-hub`.
- Patcher gateway Linux rende `_check_auth` compatibile con tutte le key alias e con default `hermes-hub`, cosi' richieste Android successive con `previous_response_id` non falliscono se config Hermes contiene una key precedente.
- Asset update server atteso: `artifacts/HermesHub-0.6.74-linux-gateway.tar.gz`; pubblicarlo in GitHub Release per testare `~/.local/bin/hermes-hub-linux-update --restart`.

Hermes Hub 0.6.73 (Windows + Android):

Release 0.6.73:
- Hotfix visibilita Android: Hardware ora e' voce diretta nella bottom nav (`Chat`/`Hardware`/`Voce`/`Video`/`Profilo`), per non restare nascosta dentro Profilo.
- Runs resta accessibile da Profilo/Aree rapide e slash `/runs`.
- Release bump: Windows/AdminBridge `0.6.73`, Android `versionName 0.6.73`, `versionCode 85`.

Hermes Hub 0.6.72 (Windows + Android):

Release 0.6.72:
- Nuova sezione Hardware/Prestazioni: Windows ha pagina `Hardware` in sidebar; Android la apre da Profilo e slash `/hardware`/`/prestazioni`.
- Telemetria hardware passa dal gateway protetto `GET /v1/hub/hardware` con polling client 1 volta al secondo.
- Dati mostrati: host/sistema, CPU, RAM, swap, dischi, rete, processi e temperature quando esposte dal sistema.
- Gateway patcher aggiornato per Windows e Ubuntu/headless; Linux usa `psutil` e sensori OS/lm-sensors per temperature reali, Windows puo' indicare `temperature_support=no_sensors_reported` se i sensori non sono disponibili.
- Gateway locale Windows patchato e verificato su `http://127.0.0.1:8642/v1/hub/hardware` e Tailscale `http://100.105.46.6:8642/v1/hub/hardware`.
- Release bump: Windows/AdminBridge `0.6.72`, Android `versionName 0.6.72`, `versionCode 84`.

Hermes Hub 0.6.71 (Windows + Android):

Release 0.6.71:
- Decisione gateway trasparente: Hermes Hub non deve piu' iniettare prompt native/instructions nella chat native. Il prompt di Hermes Agent resta fonte unica.
- Gateway Hermes aggiornato per ignorare `instructions`/system prompt dei client Hermes Hub su Responses, Chat Completions e session chat, mantenendo compatibilita' per client generici.
- Client Android/Windows native: rimosso prompt native lato app; inviano solo input, conversation/previous_response_id e metadata strutturali `hub_client`.
- Loop guard gateway: il gateway mantiene solo il contatore tool per prevenire loop, con `HERMES_MAX_ITERATIONS=120` su Windows locale e helper/service Linux.
- Updater Windows: mantenere invariata la modalita' 0.6.69+ gia' verificata da Matteo.
- Regola release notes confermata: non pubblicare fingerprint SHA/keystore completi nelle patch notes.
- Release bump: Windows/AdminBridge `0.6.71`, Android `versionName 0.6.71`, `versionCode 83`.

Hermes Hub 0.6.70 (Windows + Android):

Release 0.6.70:
- Updater Windows 0.6.69 verificato da Matteo: update in-app riuscito perfettamente. Mantieni questa modalita' stabile: MSIX scaricato in `LocalCache\Local\ChatClaw\updates`, helper PowerShell con newline reali, path fisico, `Add-AppxPackage -ForceUpdateFromAnyVersion -ForceApplicationShutdown`, rilancio via AUMID.
- Regola release notes: non pubblicare fingerprint SHA/keystore completi nelle patch notes. Indicare solo "nuova key locale" o "key storica non disponibile".
- Android auth: riconosce anche messaggi italiani tipo `API key rifiutata` come auth-error, quindi non cade piu' su fallback Chat Completions dopo un 401 native.
- Android/Windows native prompt: per saluti/chat semplice risponde direttamente senza strumenti e non deve parlare di limiti tool/iterazioni/turno salvo errore reale della richiesta corrente.
- Release bump: Windows/AdminBridge `0.6.70`, Android `versionName 0.6.70`, `versionCode 82`.

Hermes Hub 0.6.69 (Windows + Android):

Release 0.6.69:
- Windows Chat UI pulita: messaggi utente allineati a destra in bubble arancione; risposte Hermes a sinistra come testo libero senza card/blob.
- Nuovo toggle Windows `Dettagli chat avanzati` in Impostazioni/Avanzate: default OFF; quando OFF nasconde eventi Hermes, tool call e metriche TTFT/tok/context nella chat. Quando ON ripristina vista diagnostica.
- Android auth polish: retry 401 piu' permissivo; se Hermes rifiuta token salvato, `hermes-hub` e no-auth, mostra errore italiano pulito invece di JSON raw `Invalid API key` da Chat Completions.
- Prompt native Android/Windows: rispondere nella lingua dell'utente, italiano se l'utente scrive italiano.
- Release bump: Windows/AdminBridge `0.6.69`, Android `versionName 0.6.69`, `versionCode 81`.

Hermes Hub 0.6.68 (Windows + Android):

Release 0.6.68:
- Fix reale updater Windows post-fallimento 0.6.66: lo script PowerShell generato ora contiene newline reali invece di letterali `` `n`` e usa path fisico MSIX `ApplicationData.Current.LocalCacheFolder\Local\ChatClaw\updates`, cosi il PowerShell esterno vede file/log dentro la virtualizzazione MSIX.
- PC Matteo riparato manualmente prima installando `0.6.67.0`, poi aggiornato manualmente a `0.6.68.0`; app rilanciata e processo vivo.
- Default personale plug-and-play: Windows e Android puntano direttamente a `http://100.105.46.6:8642/v1` su Tailscale, con `hermes.local` e alias LAN come fallback discovery.
- Release bump: Windows/AdminBridge `0.6.68`, Android `versionName 0.6.68`, `versionCode 80`.

Hermes Hub 0.6.67 (Windows manual repair):

Release 0.6.67:
- Build locale installata manualmente sul PC di Matteo per uscire dalla 0.6.65/0.6.66 con updater rotto.
- Contiene stesso fix updater e default Tailscale della 0.6.68, ma non e' la release test finale.
- Release bump locale: Windows/AdminBridge `0.6.67`, Android `versionName 0.6.67`, `versionCode 79`.

Hermes Hub 0.6.66 (Windows + Android):

Release 0.6.66:
- Release test sopra 0.6.65 installata manualmente: verifica updater Windows senza `.cmd` e conferma distribuzione Android con nuova key locale.
- Release bump: Windows/AdminBridge `0.6.66`, Android `versionName 0.6.66`, `versionCode 78`.

Hermes Hub 0.6.65 (Windows + Android):

Release 0.6.65:
- Hotfix updater Windows: rimosso helper `.cmd` che poteva mostrare errore file mancante; ora Hermes Hub scrive solo `install-msix-update.ps1` e lancia direttamente `powershell.exe` detached (`UseShellExecute=false`, `CreateNoWindow=true`) prima di chiudere l'app.
- Decisione Android key: vecchio keystore dichiarato irrecuperabile da Matteo; da ora si usa il nuovo debug keystore locale. Primo passaggio richiede reinstall manuale, poi gli update successivi saranno compatibili con questa key finche' il keystore viene conservato.
- Release bump: Windows/AdminBridge `0.6.65`, Android `versionName 0.6.65`, `versionCode 77`.

Hermes Hub 0.6.64 (Windows + Android):

Nota blocco Android firma 2026-06-12:
- Android installato sul telefono e release storiche `0.6.46-0.6.53` usano certificato debug storico non piu' disponibile.
- Il keystore presente su questo PC (`%USERPROFILE%\.android\debug.keystore`) firma invece con nuova key locale.
- Android rifiuta update con errore "pacchetto in conflitto con un pacchetto esistente" quando firma diversa; non e' bypassabile via codice app o APK.
- Decisione finale 2026-06-13: vecchio keystore dichiarato irrecuperabile; Matteo ha disinstallato l'app Android. Pubblicare di nuovo APK firmati con nuova key locale e conservarla per tutti gli update futuri.

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
- Auto-discovery endpoint Hermes: Windows e Android provano automaticamente `http://100.105.46.6:8642/v1`, `http://hermes.local:8642/v1`, `http://hermes:8642/v1`, `http://hermes-hub:8642/v1`, `http://hermeshub:8642/v1`, `http://home-server:8642/v1`, `http://server:8642/v1`; Windows salva il primo endpoint raggiungibile.
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
- Il comando `hermes-hub` avvia **Hermes Gateway**: servizio ponte/API server che espone Hermes Agent alle app Hermes Hub Windows/Android e inoltra inferenza al backend OpenAI-compatible locale.
- La versione Linux/headless deve restare aggiornata e funzionante: `scripts/hermes-hub-linux.sh`, `scripts/hermes-hub-linux.service` e `docs/hermes-hub-linux.md` devono supportare Ubuntu headless + llama.cpp su `http://127.0.0.1:8000/v1`, con API stabile `http://SERVER:8642/v1` e API key default `hermes-hub`.

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
- Asset Android release va firmato con il keystore locale deciso per le release correnti; non caricare APK unsigned.
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
- Linux helper/service/docs: aggiunto `HERMES_AUXILIARY_LOCAL_ONLY=true` anche su Ubuntu/vLLM, API key `hermes-hub`, video library e media roots. Dal 0.6.71 il loop guard gateway usa `HERMES_MAX_ITERATIONS=120`.

Release 0.6.38:
- Android/Windows chat archive: salvataggio a snapshot. Il prompt utente viene persistito subito all'invio; durante streaming viene scritto un checkpoint parziale circa ogni 2s; il salvataggio finale sostituisce lo snapshot invece di aggiungere di nuovo la coppia prompt/risposta. Android conserva anche messaggi di stato/action nell'archivio.
- Android: stream chat spostato su scope applicativo non legato alla composizione; chiudere la UI non cancella subito il job finche' il processo resta vivo. Se il trasporto cade con `connection abort`, il parziale viene salvato come stream scollegato invece di sparire.
- Gateway locale: disconnessione SSE del client non interrompe piu' l'agent task; il gateway lascia proseguire il lavoro invece di chiamare `agent.interrupt("SSE client disconnected")`.
- Gateway locale: rimosso hard cap globale da 90 iterazioni per Hermes Hub. Dal 0.6.71 il gateway resta trasparente sui prompt ma mantiene loop guard tool con `HERMES_MAX_ITERATIONS=120`.
- Wrapper Windows imposta `HERMES_AUXILIARY_LOCAL_ONLY=true` e il client ausiliario salta fallback esterni OpenRouter/Nous per evitare warning/credit errors nei test LM Studio locali. Hotfix locale post-release: `_try_payment_fallback` torna sempre tripla `(client, model, label)` anche in local-only; `session_search` in local-only usa raw preview senza LLM summarization per evitare timeout LM Studio e log rumorosi.
- Linux helper/service/docs: dal 0.6.71 impostano `HERMES_MAX_ITERATIONS=120` come contatore tool gateway.

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

- Infrastruttura update Linux Gateway aggiunta: `scripts/install-hermes-hub-linux.sh` installa launcher/patcher/updater in `~/.local/share/hermes-hub-gateway` e symlink compat `~/hermes-hub-linux.sh`; `scripts/hermes-hub-linux-update.sh` controlla GitHub Releases latest, scarica asset Linux gateway/helper, aggiorna atomicamente `current` e puo' riavviare `hermes-hub.service`; timer user systemd opzionale `hermes-hub-linux-update.timer`; packaging release con `scripts/package-linux-gateway.ps1` produce `HermesHub-X.Y.Z-linux-gateway.tar.gz`.
- Sezione Hardware/Prestazioni aggiunta come Gestione attivita remoto: Windows ha pagina `Hardware` in sidebar, Android la apre da Profilo e slash `/hardware`/`/prestazioni`; entrambe fanno polling 1 volta al secondo su `GET /v1/hub/hardware`.
- Gateway Hermes patcher aggiunge endpoint protetto `GET /v1/hub/hardware` con snapshot host/CPU/RAM/swap/dischi/rete/processi/temperature quando disponibili. Linux Ubuntu headless usa `psutil` e, per temperature reali, sensori OS/lm-sensors; Windows puo' non esporre temperature senza driver/tool vendor e in quel caso restituisce `temperature_support=no_sensors_reported`.
- Gateway locale Windows `%LOCALAPPDATA%\hermes\hermes-agent\gateway\platforms\api_server.py` patchato e riavviato per test hardware. Verificato `GET http://127.0.0.1:8642/v1/hub/hardware` e `GET http://100.105.46.6:8642/v1/hub/hardware`: risposta 200 con host `PC-Matteo`, CPU/RAM/dischi/rete/processi reali; temperature Windows non esposte (`temperature_support=no_sensors_reported`).
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
- Versione app: `0.6.118`.
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
- Versione app: `0.6.118`, versionCode `123`.
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
- Se firma Android cambia per cambio PC/debug keystore, update in-place fallisce con conflitto pacchetto. Non si puo' ricostruire private key da SHA/cert pubblico; preservare chat solo con export/sync prima della disinstallazione.
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
