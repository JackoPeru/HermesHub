# Changelog

Le modifiche rilevanti di Hermes Hub sono documentate qui. Le release GitHub restano la fonte per asset e note complete.

## 0.6.314 - 2026-10-25

- Hero: alone largo dietro il logo e proporzioni riferimento (logo ~29%).
- Solo stile: suite verdi.

## 0.6.313 - 2026-10-25

- Titolo hero = bottone invisibile con tendina bot, avatar = % contesto + menu manager/comfy/laya, fullscreen uniforme, logo hero luminoso dedicato, bolle con luce dal logo.
- Funzioni invariate, solo stile+header: suite verdi.

## 0.6.312 - 2026-10-25

- Home come il riferimento: bolle animate in Canvas (niente PNG), composer minimale con alone ambra, hero in alto.
- Solo stile: suite verdi, screenshot JVM di verifica.

## 0.6.311 - 2026-10-24

- Archi home: ripristinate le curve ellittiche originali (le bande dritte erano sbagliate), solo più luminose.
- Solo asset: suite verdi.

## 0.6.310 - 2026-10-24

- Hero 1:1 verificata dal vivo: header con inset corretto, composer a riga singola, glow logo/avatar senza alone scuro, asset archi rigenerato e ancorato in alto.
- Solo stile: suite verdi.

## 0.6.309 - 2026-10-24

- Home 1:1 rifinita: toggle nascosto a chat vuota, riga Legacy nascosta, logo e archi potenziati, bot coerente.
- Solo stile: suite verdi.

## 0.6.308 - 2026-10-24

- Home pulita come il riferimento: via le risposte rapide; bot coerente.
- Audit UI totale: pill vetro, card 20dp, hairline e import ovunque.
- Solo stile: suite verdi.

## 0.6.307 - 2026-10-24

- Home vuota 1:1 dal riferimento (header, archi glow, hero) + asset aurora_home.
- Mock Jarvis/Hardware/Impostazioni/Connessione/drawer applicati; stile Apple esteso a personaggi, bot, navigazione, privacy, setup, dropdown.
- Solo stile: suite verdi.

## 0.6.306 - 2026-10-24

- UI Apple su tutta l'app Android: sistema centrale (vetro, aurora, tipi, motion), hero "Che vuoi fare oggi?", composer pill di vetro, banner hairline, transizioni tra schermate, sezioni animate.
- Solo stile: nessuna logica, stringa o firma cambiata; suite verdi.

## 0.6.305 - 2026-10-23

- Chat Android standard intoccabile: follow/resume in staging con Mostra esplicito (mai auto-append), approval solo a chat ferma, flush ordinato su invio.
- Metriche anche a fine anomala (durata reale), riprova via draft, indicatore prefill unificato, chiavi feed anti-collisione.
- God file spezzati in 45 moduli, ~2.800 righe morte rimosse, 11k import puliti.
- Test: staging/durate/metriche, suite verdi.

## 0.6.304 - 2026-10-23

- Android: UNA SOLA notifica ongoing per i lavori (gli avanzamenti aggiornano la foreground con pulsante Interrompi); fine/approvazioni restano transitorie e cancellabili.
- Test: HermesWorkServiceTest (2), suite verdi.

## 0.6.303 - 2026-10-23

- Chat Android: gruppi tool aperti di default a turno vivo; riga "Elaborazione lunga · Ns" dopo 30s senza eventi; mai piu label "tool" generica.
- Test: fallback generico in WorkFeedFormatTest, suite verdi.

## 0.6.302 - 2026-10-23

- Chat Android: feed compatto stile desktop ufficiale (prosa + righe dense Pensiero · Ns / Eseguito · 25s / N attività ∨, pill "↓ N messaggi" oltre 8 blocchi); durate tool reali; turni chiusi con feed inline invece del box "Attività Hermes".
- Test: durate e titoli in WorkFeedFormatTest, suite verdi.

## 0.6.301 - 2026-10-23

- Chat Android: mai piu nomi evento grezzi in UI (message.started -> "Invio richiesta", wrapper vuoti -> invisibili, envelope/raw non toccano lo status); niente piu "Evento Hermes: ..." bloccato per minuti.
- Test: 3 nuovi in HermesModernApiTest (31/31), suite verdi.

## 0.6.300 - 2026-10-23

- Chat Android: feed attivita minimale stile timeline (Ragionamento + righe tool singole Eseguito/Letto/Esplorato/Modificato/Cercato, espandibili); prefill `Prefill · xk/max tok · tk/s · %` anche quando stimato (~); invio mostra solo "Invio richiesta" con shimmer; metriche messaggio di nuovo attive di default.
- Gateway session_follow: cursore monotono + overlap (niente piu buchi su backlog >100), prune cursori orfani, flip inactive garantito anche fuori top-25; Android merge con mutex single-flight + dismiss banner persistente.
- Test: WorkFeedFormatTest (7), ChatStreamProtocolTest (47), session_follow (11), suite verdi.

## 0.6.299 - 2026-10-23

- Follow turni detached: sync gateway sessioni->Hub + merge live Android con banner; chiude la dead zone legacy.

## 0.6.298 - 2026-10-22

- Android: browse immagini con chiave app (fix 401); tripwire anti-regressione.

## 0.6.297 - 2026-10-22

- Generative UI v1: blocco genui (29 nodi, stato locale, formule, condizioni, azioni agent, update stessa-id) su Android+Windows; validazione strict; fallback; gate never; istruzioni agente.
- Test: test_genui_contract (9), GenUiTest (11), harness Windows differenziale.

## 0.6.296 - 2026-10-22

- Updater Linux: /status manager con timeout 90s + fallback /jobs (idle→procedi, busy/down→defer).
- Test: marker fallback in test_release_consistency.

## 0.6.295 - 2026-10-22

- Updater Linux: busy check manager con chiave app root-only via sudo (gateway revocata dal lockdown); senza fix, defer 401 permanente.
- Test: marker updater in test_release_consistency.

## 0.6.294 - 2026-10-22

- Activity gruppi strutturata + storico; phone_use (gateway+Android+Windows); fleet; PR/diff/merge; pulse 7 giorni; wake sensitivity+stats.
- Test: nuovi unit/marker per ogni voce.

## 0.6.293 - 2026-10-22

- Manager: ruoli laya (controllo) / app (letture+coda), chiavi root-only, vecchia chiave revocata.
- laya: ruoli ingresso, submit preset per agenti, intento reboot manager.
- App: chiave app dedicata, modi/reboot via laya, lista device con revoca.
- Agente: manager off-limits via protocollo.

## 0.6.292 - 2026-10-22

- Android, chat: follow live del turno sul gateway al rientro (SSE run / poll Responses), banner con stop, resume al terminale.
- Test: LiveFollowTest (5/5), test_live_follow (3/3).

## 0.6.291 - 2026-10-21

- Sicurezza: chiavi dispositivo P-256 + firma con biometrico sulle azioni server, anti-replay sul gateway, revoca per-telefono.
- Push FCM a fine job (manager->gateway->telefono), solo segnale; service account operatore opzionale.
- Test: test_device_signature (10), test_device_push (7).

## 0.6.290 - 2026-10-21

- Gateway, proxy media: supporto HEAD (header senza body, mai transcode) per i player nativi (Windows MediaPlayerElement).
- Windows, Video: errori player e stato feed nel log diagnostico.
- Test: test_media_head (3/3).

## 0.6.289 - 2026-10-21

- Android, turno staccato: banner leggibile (3 righe, niente ellipsis), stati di recupero espliciti e note quando non c'e' niente da recuperare.
- Recupero veloce: check HTTP singolo (~12s max) invece di retry lunghi.
- Test: DetachedResumeTest (3/3), test_detached_resume (5/5).

## 0.6.288 - 2026-10-21

- Chat Windows e Android: blocchi interattivi quiz (semplice/multiplo) e form (testo, numero, data, bool, select) con invio risposta in chat e validazione required.
- Protocollo agente: schemi choice/form e formato delle risposte dell'utente.
- Test: test_interactive_blocks (6/6), VisualBlocksContractTest esteso.

## 0.6.287 - 2026-10-20

- Windows, pagina Comfy: sezione NODI DEL JOB con il grafo del workflow dell'agente (nodi, link, completati verdi dal live di Comfy, dettaglio al tocco).
- Server: Comfy gestito in ascolto su Tailscale (ufw gia' ristretto), cosi' la vista diretta risponde anche in AUTO.

## 0.6.286 - 2026-10-20

- Windows, pagina Comfy: sezione JOB AGENTE con i job autonomi dell'agente (stato, %, dettagli, output) dal manager, visibili anche quando Comfy e' fermo e la vista workflow diretta e' vuota.
- Job: dettaglio con tipo/preset, fase, backend/modello, output, errore e orari; "Apri output" apre il media pubblicato su /v1/media.
- Test: nuovo test_agent_jobs_contract (3/3) sul contratto GET /jobs.

## 0.6.285 - 2026-10-20

- Android, sidebar istantanea: l'elenco conversazioni si mostra subito (cache precedente) e si aggiorna in sottofondo; mai piu' parse JSON sul thread UI all'apertura.
- Android, reattivita': tutti i salvataggi impostazioni (inclusa chiave KeyStore) girano in IO, mai sul main thread.
- Android, transizione Chat <-> Bot stile pagina di libro: flip 3D 380ms con pivot sui dorsi, ombra pagina e fade; il lato nascosto non intercetta tocchi.
- Test: ArchiveCacheTest verde (5/5), ComfyStatusTest verde (12/12).

## 0.6.284 - 2026-10-20

- Android, avvio: settings + chiave + profilo voce si caricano in parallelo e la UI si sblocca appena pronti i primi due (la voce non blocca piu' la chat).
- Android, chat: l'archivio conversazioni si rilegge da disco ma si riparsa solo se cambiato (cache di processo invalidata a ogni scrittura); niente piu' rewrite a ogni lettura.
- Android, transizione Chat <-> Bot stile iOS: 320ms con easing, uscita in parallasse e niente clip ai bordi.
- Test: nuovo ArchiveCacheTest (isolamento cache, invalidazioni).

## 0.6.283 - 2026-10-20

- Gruppi bot: al primo sync i gruppi solo-locali vengono migrati sul gateway invece di essere cancellati; i rifiuti definitivi (4xx) escono dai pending senza riprovare all'infinito.
- Manager GPU: verita' per-processo (VRAM attribuita a tabby/exllama vs Comfy, nome modello LLM servito, file modello media dagli fd dei processi Comfy anche con load manuale); `llm_loaded` non scatta piu' sul video. Nuovi campi /status: `llm_model`, `llm_vram_mb`, `media_vram_mb`, `media_loaded_models`. Nota deploy: file staged sul server, attivazione (restart manager+laya) su OK utente.
- laya: multi-intent in un messaggio ("carica llm e metti in auto"), "passa a comfy" va in MEDIA (DIRECT solo se esplicito), attesa backend con conferma ("Comfy attivo" vs "non risponde ancora").
- Client: sottotitoli GPU veritieri (Android TopBar + Comfy, Windows Home/ComfyPage).
- Test: suite completa verde + nuovi contratti test_gpu_truth.py.

## 0.6.282 - 2026-10-20

- Gateway: patcher tollerante anche all'albero agent piu' nuovo (tabella capability feature-flagged, `_parse_chat_messages` estratto, features standalone): deploy e sonda --check verdi e idempotenti sul candidato origin/main del server. Include i fix 0.6.281 (firme PEP 604, annotation non quotate, `list/dict` minuscoli).
- Test: suite completa verde + nuovo contratto test_upstream_variant_tolerance.py (resolver, upsert con varianti, regex features, resolver runs-event).

## 0.6.281 - 2026-10-20

- Gateway: patcher tollerante alle firme upstream migrate di hermes-agent (modulo runs separato, annotation non quotate, `str | None`, `list/dict` minuscoli): il deploy 0.6.280 falliva con "Patch anchor not found" contro l'albero agent nuovo del server. Nessun cambio funzionale lato client.
- Test: suite completa verde + nuovi contratti test_runs_resume_anchor_variants.py (entrambe le firme `_callback`) e sonda --check contro l'albero agent reale del server.

## 0.6.280 - 2026-10-20

- Gruppi bot sincronizzati server-side: il gateway introduce `GET/POST/DELETE /v1/hub/bot-groups` (auth `_check_auth`, storage JSON limitato `HERMES_HUB_BOT_GROUPS_PATH`, validazione pari alla lista Android: nome 1..160 senza newline, 2..6 membri, identity_key `connection_id::profile` unica, cap 200 gruppi). Android ora usa load/upsert/delete sincronizzati col gateway come fonte di verita' quando raggiungibile, cache locale offline + pending push/delete riverificati al primo load online (nessuna azione utente si perde).
- Bug visualizzato: un gruppo creato nel desktop ufficiale resta invisibile all'hub (storage separato); da ora i gruppi dell'hub vivono sul gateway e sono condivisi tra telefono e PC.
- Test: suite 765 OK (nuovi contratti test_bot_groups_gateway.py + test_bot_groups_android_contract.py), patch gateway idempotente su entrambi i fixture upstream.

## 0.6.279 - 2026-10-19

- Windows: "Workflow live" in pagina Comfy — WebView2 incorporata sull'interfaccia web di ComfyUI (:8188) con graph completo e realtime, piu' interventi utente (annulla job, modifica workflow del job in coda, interrompi e risottometti) che NON cambiano mai la modalita' del manager: si tocca solo la riga del job.
- Manager (gpu-manager): nuovi endpoint `GET/POST /jobs/{id}/workflow` (riscrivi workflow del job in coda, fail-closed su validate_workflow) e `POST /jobs/{id}/resubmit` (interrompe il job in esecuzione e ne mette in coda una copia su NUOVO job_id: il tracker morto del vecchio vede "cancelled" e si ferma, zero race, zero autoplay del workflow originale); nessuno dei due tocca set_state/desired_mode/cooldown.
- Test: suite 758 OK (nuovo contratto AST test_manager_resubmit.py), build Windows verde, manager deployato sul server con backup .bak-0.6.279. Gateway server non deployato (pende OK utente).

## 0.6.278 - 2026-10-18

- Manager (gpu-manager): fallback sempre-operativo — qualunque errore riporta il desired su AUTO e il recovery va prima a LLM_READY (l'agente/chat torna operativa prima di ogni retry media/direct); eccezioni non gestite di _drive/_supervisor_step entrano in ERROR con worker che sopravvive; poi resetto media cooldown.
- Windows: menu tre puntini nel top chat (come Android): nuova chat, switch LLM su GPU, switch gestione automatica GPU, switch Comfy diretto, chat laya e stato Comfy con poll 3 s; rimosso il tasto penna (nuova chat resta nel menu).
- Test: suite 753 OK (nuovo contratto fallback manager, deploy manager), build Windows/Android/Linux verdi. Gateway server non deployato (pende OK utente).

## 0.6.277 - 2026-10-18

- Audit totale di applicazione, backend e manager: sicurezza, cap server-side e robustezza stream.
- Android: chiave API rimossa dal build ( APK senza segreti ); retry auth fail-closed unificato; session key nel KeyStore; redaction raw eventi in activity log.
- Windows: self-update con verifica SHA-256 della release e script installazione a nome random; cache chiave PasswordVault; watchdog SSE 90 s; bounded reader condiviso.
- Gateway: paging cursor + budget byte su `/v1/hub/conversations`, sync fuori dall'event loop con budget byte, pruning change-log, chmod WAL/SHM, cooldown sui control endpoint; triage copia byte-identica (test).
- Deploy del gateway sul server rinviato (agente attivo; l'update verra' mandato in produzione su conferma dell'utente).

## 0.6.276 - 2026-10-18

- Windows: fix ChatArchiveSync: cap dedicato a 32 MB per GET `hub/archivio` e POST import; l'archivio condiviso (con mirror bot/Jarvis da SQLiteHubStateStore) ha superato i 10 MB e bloccava pull/push/events.
- Windows: niente piu retry senza Authorization quando esiste una chiave gateway salvata (causa dei ~1800 rifiuti 401/giorno nel log server).
- Android: bump versionName 0.6.276 / versionCode 280, nessun codice nuovo rispetto a 0.6.274.
- Test: consistenza release verde; build Android, Windows e gateway Linux verdi.

## 0.6.274 - 2026-10-18

- Windows: fix aggiornamento in-app: il pacchetto scaricato veniva validato col nome del file temporaneo (`.partial`), quindi rifiutato come "Download non valido"; ora la validazione usa il nome definitivo e l'asset invalido viene rimosso.
- Android: "Chat laya" raggiungibile dai tre puntini del top bar chat (flag manager) oltre alla card GPU della scheda Server.
- Test: consistenza release verde; build Android, Windows e gateway Linux verdi.

## 0.6.273 - 2026-10-17

- Canale laya diretto: bridge `hermes-laya-bridge` (porta 8645) con `POST /laya` (domanda in linguaggio naturale → modalità) e `POST /force` sul manager per forzare la transizione; nessun retry automatico dei job falliti.
- Android: pannello "Chat laya" sempre presente nella card GpuCompute (funziona anche a manager fermo); base bridge su :8645.
- Windows: pannello "Chat laya" nella pagina Comfy con invio e risposta; base bridge su :8645.
- Test: bridge laya, force e deploy verdi; build Android e Windows verdi.

## 0.6.272 - 2026-10-15

- Android: schermate di dettaglio in stile: header, righe piatte, bottoni ambra/testuali/rossi, dialog coerenti (bot, personaggi, video, news, archivio, automazioni, gallery, approvazioni).
- Test: consistenza release verde; build Android + test verdi.

## 0.6.271 - 2026-10-15

- Android: passata visual Apple su tutte le schermate: righe con tile+chevron, bottoni ambra/testuali, header maiuscoli, tema M3 nei colori brand; chat con più respiro e composer con focus ambra.
- Test: consistenza release verde; build Android + test verdi.

## 0.6.270 - 2026-10-15

- Windows: header scheletro con tile icona in tutte le pagine; avatar Hermes nei messaggi; chip contesto nel composer; tile icona topbar.

## 0.6.269 - 2026-10-15

- Windows: scheletro ridisegnato: avatar Hermes nei messaggi, chip contesto (identità/modalità) nel composer, tile icona per pagina nella topbar.
- Test: consistenza release verde; build Windows verde.

## 0.6.268 - 2026-10-15

- Windows: bottoni vivi (hover/press senza grigio sistema), transizioni tra pagine, hero con icone e descrizioni.
- Test: consistenza release verde; build Windows verde.

## 0.6.267 - 2026-10-15

- Windows: redesign strutturale: sidebar stretta, hero home centrato, titoli 34px, alone ambra di profondità, composer e messaggi più leggibili.
- Test: consistenza release verde; build Windows verde.

## 0.6.266 - 2026-10-15

- Windows: stile Apple minimalista: header sezioni maiuscoli, hairline tra righe, text-button ambra per azioni secondarie, primario solo per commit veri.
- Test: consistenza release verde; build Windows verde.

## 0.6.265 - 2026-10-15

- Windows: rimozione card decorative (canva dentro canva): contenuti raggruppati con respiro e tipografia, superfici funzionali intatte.
- Test: consistenza release verde; build Windows verde.

## 0.6.264 - 2026-10-15

- Windows: restyling premium UI: gerarchia bottoni primario/ghost/danger (mai blu sistema), scala tipografica condivisa, header pagine unificati, selettore identità in stile ghost.
- Test: consistenza release verde; build Windows + format verdi.

## 0.6.263 - 2026-10-15

- Windows: parità funzionale Android fase 1-6: reattach stream con recovery via GET (continue_on_disconnect), forever-chat bot canonica + selettore identità, sezione Immagini con gallery/zoom, hardening WebView2 news, pagine Comfy/GPU e Personaggi, pagina Schermo bot noVNC.
- Test: consistenza release verde; build Windows + format verdi.

## 0.6.262 - 2026-10-15

- Android: recovery turni anche sul percorso Sessions/Bot — a stream accettato ma chiuso senza terminale, prima poll run osservato (GET /v1/runs) poi riconciliazione via history sessione; solo dopo l'errore vero. Niente piu "risposta non confermata" a ogni blip di rete.
- Test: 11 nuovi (491 android verdi).
- Windows/AdminBridge: allineati a 0.6.262 (parity versioni, nessuna modifica funzionale).

## 0.6.261 - 2026-10-13

- Android: WebView news senza JS/bridge, screen bridge blindato (no file/mixed, asset jail con decode); copia archivio con conferma, import validato.
- Android: sidebar ricaricata a ogni apertura, deep-link con cap e MIME allowlist, TTS con rifiuto onesto oltre 4000 char, note vocali max 10min/100MB, news con singolo GET.
- Server: SQLite sotto lock, training wall-clock 12h, workflow raw con cap 480p/3600, retention release/backup (5), digest su bytes.
- Test: 16 nuovi (528 python + 480 android verdi).

## 0.6.260 - 2026-10-13

- Android: riavvio server col flusso ticket single-use (compatibile col manager precedente a 200 diretto).

## 0.6.259 - 2026-10-13

- Sicurezza gateway: media sempre con token (fine bypass tailnet), `?root=` solo dentro i media roots, cap upload 64MB con rifiuto oltre limite.
- Sicurezza manager: reboot con ticket single-use, kill solo basename esatto, cleanup ancorata a /opt/hermes, display takeover solo utente.
- App: miniature a 256px (niente piu OOM galleria), letture NULL sicure, reset pulisce secret/archivio, bot remoto senza chiave bloccato, URL redatti nei backup, resume con profilo e single-flight.
- Release: file .sha256 accanto agli asset, check monotonicita versionCode, 58 nuovi test comportamentali.

## 0.6.258 - 2026-10-12

- Android: turni staccati — a stream morto (watchdog 90s incluso) la chat recupera la risposta via GET invece di chiudere il turno; kill telefono = detach (binding tenuto), solo lo stop utente chiude davvero; nuovo banner "Hermes continua sul server" con ricontrollo.

## 0.6.257 - 2026-10-12

- Android: fix swipe galleria — il pan dell'immagine non ruba piu i trascinamenti a zoom 1x (solo zoomato panoramano).

## 0.6.256 - 2026-10-12

- Android: galleria sfogliabile nel viewer immagini (swipe destra/sinistra tra le foto, contatore, zoom per foto).
- Android: chiudendo una foto resti dove eri (lista e scroll non si resettano piu).

## 0.6.255 - 2026-10-12

- Android: viewer immagini con zoom persistente (pinch + doppio tap, resta tra rotazione e tutto-schermo) e pulsante tutto-schermo con zoom sempre attivo.

## 0.6.254 - 2026-10-12

- Android: cartella immagini preimpostata su denudate (fresh install e guarigione blank): la sezione Immagini parte gia dalla cartella giusta.

## 0.6.253 - 2026-10-12

- Android: 403 browse = "cartella non consentita" (non piu "verifica la chiave"); `/.hermes/...` normalizzato in `~/.hermes/...`.

## 0.6.252 - 2026-10-12

- Android: sezione Immagini come browser — Home/Su/breadcrumb, righe cartelle, griglia solo immagini; funziona col gateway attuale.
- Manager: nuovo `GET /browse` blindato (solo lettura, un livello, jail sui root media, symlink verificati, auth key) per sfogliare le cartelle immagini.
- Verificato live: `hub_uploads/denudate` → claudia/valentina → webp serviti dal proxy (traversal 403, no-auth 401).

## 0.6.251 - 2026-10-12

- Android: verdetto gateway veloce — il pallino si decide subito dopo il probe (stato runtime dopo, non blocca), gara root a 2.5s per il dot.
- Android: verdetto legato all'URL effettivo — cambiando bot sullo stesso gateway niente piu "Verifica gateway…" perenne.

## 0.6.250 - 2026-10-12

- Android: selettore bot nel titolo della chat (stile desktop) — tap sul nome, tendina con Hermes + bot disponibili; la chat parla con quel profilo, la scelta persiste al riavvio.
- Android: sidebar RECENTI filtrata per bot (ogni bot ha le sue chat); aprendo una chat di un altro bot l'identita cambia da sola (vale anche da archivio/ricerca/continuita/deeplink).
- Android: fix round-trip `botIdentityKey` (NULL JSON riletto come "null" avrebbe nascosto le chat) + reset chiave stale a roster reale.

## 0.6.249 - 2026-10-12

- Android: nuova sezione Immagini sotto Video — griglia, fullscreen con scarica, filtri per cartella, refresh; cartella principale dal server + cartelle extra configurabili (una per riga) nelle Impostazioni.
- Gateway: nuovo endpoint `GET /v1/image/library[?path=]` con proxy autenticato (patcher + test).

## 0.6.248 - 2026-10-12

- Android: audit totale connessioni — Jarvis (intera tab morta) sotto `/v1`; apertura media esterna con fallback token; poll run con 401/403 subito terminali; coda continuity senza veleno 401; health senza chiave; capability mai stale su 401; cancel manager con chiave del bot.
- Android: fast path onesto quando i file esistono sul server ma non pubblicati.

## 0.6.247 - 2026-10-12

- Android: URL media nudi scritti dall'agente (`xxx.webp`, anche con query) risolti a `/v1/media/...` — le immagini modificate tornano a mostrarsi invece delle card col solo nome.

## 0.6.246 - 2026-10-12

- Android: audit totale endpoint contro server live — chat, responses, capabilities, models, trascrizioni, titoli, controllo server usano sempre `/v1/` (i path nudi davano 404); rete anti-regressione in suite.
- Android: blocchi media senza estensione ma con mime/estensione immagine si aprono come immagini (niente piu card col solo nome file).

## 0.6.245 - 2026-10-12

- Android: il timeout 90s non uccide piu il turno — stacca e recupera la risposta dal server (anche con risposta parziale, senza duplicati); se chiudi l'app, al rientro la risposta recuperata sostituisce il messaggio parziale.
- Android: binding di resume anche per i turni Responses (prima solo run); pulizia a turno pulito/stop, scadenza 55min.

## 0.6.244 - 2026-10-12

- Android: audit totale endpoint app/server — chat, responses, capabilities, models, trascrizioni vocali e titoli usano sempre `/v1/...` (prima alcuni path nudi davano 404); centralizzato in `profileUrl`/`resolveHermesUrl` cosi non ricapita.

## 0.6.243 - 2026-10-12

- Android: upload allegati su `/v1/media/upload` (il path senza `/v1` dava 404 e bloccava ogni invio con foto).

## 0.6.242 - 2026-10-12

- Manager: `llm_loaded` somma la VRAM di tutte le GPU (lo split 6.6+10.8GB risultava scarico, incastrava BOOT e ripeteva restore a vuoto); stati di nuovo veritieri.
- Android: rimossa la forzatura /mode/llm all'apertura chat (bloccava tutto su LLM manuale mentre si usava Comfy); resta il toggle manuale.

## 0.6.241 - 2026-10-11

- Android: valori vuoti salvati guariscono ai default (chi aveva impostazioni vuote torna online senza reinstallare); cartelle video/news preimpostate ai path reali del server.

## 0.6.240 - 2026-10-11

- Android: connessione preimpostata di base — fresh install con gateway Tailscale + LAN casa e chiave API gia configurati, niente schermata vuota dopo reinstallazione (lo stored utente vince sempre; il default non si scrive mai).

## 0.6.239 - 2026-10-11

- Android: rete fire-and-forget su scope IO (mai piu sul Main); eventi SSE oltre 2M troncati con avviso invece di turno perso; frame terminali troncati rilevati senza JSON completo; expand/collapse con chiavi stabili; liste con key; rotazione (scelta Accoda/Correggi, dialoghi, filtri); timer banner gated su lifecycle; settings/secret ricaricati al cambio gateway; niente piu loop di copia duplicato.
- Android: keystore release dedicato (firma debug rimossa dalle release). ATTENZIONE: firma diversa dalle precedenti, aggiornare richiede disinstallazione + reinstallazione (backup chat prima).
- Gateway: stream mai rotto da run_id anomalo; split UTF-8 riassemblato; kwargs filtrate sulle chiavi di routing + migrazione file gia patchati.
- GPU manager: kill con ricontrollo cmdline; lock sul submit; area-cap OOM su tutti i video; docs corretti.
- Character-ID: claim atomico per le generate; conferma esplicita oltre 4000 step.
- Script: allowlist host GitHub per gli asset; backup `previous` all'install.

## 0.6.238 - 2026-10-10

- Sidebar bot: long-press su un bot apre il menu contestuale stile desktop (Apri Bot Chat, Apri schermo, auto-schermo con spunta, Fissa in alto, Nascondi, Modifica, Gestisci gruppi, Duplica, Nuova chat con questo bot, Apri sessione recente, Sposta in sezione, Elimina).
- Bot: "Nuova chat con questo bot" crea una sessione dedicata pulita senza toccare la forever-chat canonica; "Apri sessione recente" elenca le altre sessioni del bot per titolo e attivita.
- Bot: Modifica/Duplica/Elimina/Gruppi/Schermo dal menu sidebar aprono la sezione Bot con l'azione gia pronta (stessi dialoghi ed effetti della card).
- Android: badge FISSATO nella sidebar; test helper sessioni (id, titoli, filtri, limiti).

## 0.6.237 - 2026-10-10

- Chat e bot: feed agente in stile transcript — testo dell'agente interleavato in ordine cronologico con pensieri ("Ragionamento · Ns") e raffiche di tool, ognuno espandibile al tocco; a fine turno tutto rientra nel flag "Attivita Hermes" e resta visibile solo la risposta finale.
- Chat e bot: messaggi archiviati e trascrizioni canoniche mostrano lo stesso transcript derivato (tool consecutivi raggruppati, solo ultimo prefill, filtro tool rispettato).
- Android: 11 nuovi test sul transcript (interleaving, burst, snapshot, derivation, flag).

## 0.6.236 - 2026-10-09

- GPU manager: hold fail-closed se store o lock del training sono illeggibili o corrotti; deploy con staging e cleanup affidabili, rollback su errore/interruzione e chiave solo via stdin.
- Deploy GPU: timeout esteso per la verifica `/status`, che aggrega probe backend indipendenti.
- Windows MSIX: endpoint RFC 3161 corretti (DigiCert HTTP primario, Sectigo fallback); timestamp obbligatorio mantenuto.
- Android Archivio: import accetta risposte fino a 64 MiB; il limite JSON ordinario resta 2 MiB.

- CI: Pillow esplicito nei test; analyzer backup corretto con interruzione se l'ACL privata viene rifiutata e cleanup staging.

## 0.6.235 - 2026-10-09

- Chat: stop che sblocca sempre la UI (anche a rete morta), fine turno
  mai silenziosa (messaggio onesto al posto del vuoto), cleanup
  garantito anche se snapshot/titolo falliscono, stop che non tocca i
  turni nuovi, niente titoli da "Connessione persa".

## 0.6.234 - 2026-10-09

- Chat/bot: banner live v2 (timer elapsed, poll 2s a turno attivo,
  onesta sul silenzio oltre 45s invece di etichetta congelata).
- Server: fix dipendenza snowballstemmer nel runtime gateway, hold GPU
  con fallback lockfile se lo store e illeggibile.

## 0.6.233 - 2026-10-07

- Chat/bot: eco turno scopata per profilo, stop 60s onesto, swipe
  deciso anti-fling, guard anti-reset a turno attivo, stream puliti al
  cambio chat, foto senza caption conservate.
- Rete/voce: VPN conta come locale, probe senza cancellazioni ingoiate,
  backoff ovunque, trascrizione vuota che non uccide la chiamata.
- Server: prova risposta vera con cache+lock, timeout watchdog 20s,
  loopback via ipaddress fail-closed, clamp video ad area, rollback
  che ferma anche direct/voce, deploy con verify 403/409/llm_serving.

## 0.6.232 - 2026-10-07

- Manager: solo l'utente puo cambiare modo/riavviare (l'agente in
  locale e limitato a coda e letture); log applicativi ripristinati.
- App: campo URL locale casa + scelta automatica percorso veloce;
  letture stato con timeout brevi e valori noti se non aggiornati.

## 0.6.231 - 2026-10-06

- Lavori in background: la chat ricontrolla lo stato da sola al
  rientro (niente piu fermo immagine); risultato aggiunto sempre;
  notifica "Risposta pronta" anche se l'app era stata chiusa.

## 0.6.230 - 2026-10-06

- Chat bot: lo stato live ignora l'eco del turno appena finito
  (niente piu banner incastrato ne stop "dal desktop" sbagliato);
  stop che si autocorregge; niente notifiche coi propri prompt ad
  app aperta.
- Chat bot: allegati inviati anche nelle sessioni, immagini e card
  conservate nella cronologia riaperta.

## 0.6.229 - 2026-10-06

- Nuova sezione Comfy: stato GPU, lavoro in corso con avanzamento
  live, coda ed errori ComfyUI; voce "Comfy: cosa sta facendo" nel
  menu con riassunto live.

## 0.6.228 - 2026-10-06

- Invio bot: il retry canonico tenta prima del fallback legacy (mai
  piu scritture fuori dalla chat condivisa); errori HTTP sempre
  visibili, mai drop silenziosi; bozza ripristinata se fallisce.
- Rete: cancellazioni rispettate, backoff nei retry, URL encode
  completi, trascrizione vuota segnalata, testi senza host.
- Swipe deciso (ampio+veloce, mai durante invio), back dal roster che
  tiene la bot chat, retry che ricarica davvero, rotazione sicura per
  voce e schermo, topbar accessibile.

## 0.6.227 - 2026-10-06

- Invio bot: la chat esistente invia sempre diretto via Sessions
  (niente piu blocco "Sessions API non disponibili" quando il controllo
  capabilities fallisce); eventuali errori reali dal server.

## 0.6.226 - 2026-10-06

- Chat bot: all'apertura parti in fondo all'ultima risposta; apertura
  e chiusura animate con scorrimento; swipe orizzontale per passare
  tra Chat e Bot (oltre al pulsante).

## 0.6.225 - 2026-10-06

- Stato gateway diagnosticabile: grigio "Verifica gateway…" prima del
  primo responso (mai piu rosso prematuro), motivo del fallimento
  visibile (HTTP/timeout/URL) in topbar e in chat bot.
- Chat bot: nome del bot nel titolo in alto al posto di "Hermes Hub",
  banda "Bot attivo" rimossa, toggle [Chat|Bot] compatto.

## 0.6.224 - 2026-10-06

- Fix critico: deadlock all'apertura bot (lock annidato sullo stesso
  Mutex bloccava la chat per sempre senza errori); ora il lock vive
  solo dentro il resolve. Potatura mappe lucchetti e creazione
  atomica anti-race, con 2 nuovi test di serializzazione.

## 0.6.223 - 2026-10-06

- Audit completo: Bot Chat canoniche paginate con lock e retry
  idempotente (niente piu fork/duplicati); timeout di rete finiti con
  backoff e auth fail-closed sui profili; watchdog GPU che non uccide
  i prefill lunghi e stray-CUDA fail-closed; toggle e sidebar con
  target 48dp, stati loading/errore con riprova, stringhe corrette.

## 0.6.222 - 2026-10-05

- Bot live esterno: la chat mostra quando il bot lavora altrove con
  banner shimmer e stato (tool, ragionamento, scrittura); stop attivo
  anche senza turno locale; nuovi messaggi in diretta.

## 0.6.221 - 2026-10-05

- Bot canoniche: scan del registro con include_hidden (le Bot Chat sono
  sempre hidden); risolte le aperture finite in HTTP 400 per duplicato.

## 0.6.220 - 2026-10-05

- Editor bot: validazione limiti server (profilo, nomi, descrizione,
  soul) con contatori ed errori espliciti; niente piu 400 criptico.

## 0.6.219 - 2026-10-05

- Bot canoniche desktop-parity: un bot = una forever-chat (sessione
  titled "Bot Chat"), transcript condivisa col desktop, invii nella
  stessa sessione, roster con anteprima ultimo messaggio; mai fork,
  fail-closed sul registro, niente rename della canonica.

## 0.6.218 - 2026-10-05

- Bot collegati al desktop: ogni bot si collega alla sua chat desktop
  (stessa entity Hub, uso sequenziale); picker al primo uso, scollega
  dal menu; snapshot con preserve dei puntatori e union anti-perdita.
- Hardening apertura bot: gate unico anti doppio-tap sui 4 percorsi,
  pull con timeout solo per linkate, guardie a turno attivo.

## 0.6.217 - 2026-10-05

- Sezione Bot: la pagina principale e la chat dell'ultimo bot usato
  (riuso sessione, roster solo al primo uso); roster server ripristinato
  (7 bot come desktop).

## 0.6.216 - 2026-10-05

- Toggle [Chat|Bot] ridisegnato: pill sottile (~20dp) elegante al posto
  dei bottoni grandi.
- Sidebar in modalita bot stile Hermes desktop: con il lato bot attivo
  mostra tutti i bot con cui parlare (fissati/nascosti rispettati, bot
  attivo evidenziato) invece di tab e chat normali; apertura diretta
  della chat persistente.

## 0.6.215 - 2026-10-05

- Sezione Bot: toggle [Chat|Bot] in alto alla chat con slide da destra;
  bot fuori dalla sidebar, schermo dentro la sezione; ogni bot ha
  un'unica chat persistente (stesso id su Hub e desktop via autosync).
- Menu bot stile desktop (long-press/⋮): apri chat/schermo, auto-screen,
  fissa/nascondi, modifica, gruppi, duplica, nuova chat, sessioni
  recenti, sezioni locali; fix doppio POST all'apertura.
- Manager: il watchdog non uccide piu i backend gestiti (cgroup) e non
  ripristina sopra motori in generazione (gate 4 fallimenti + progress
  tabby); risolto loop kill/restart durante i prefill lunghi.
- Audit: trim memoria, banner conversazione eliminata, catalogo modelli
  cliccabile, curl via file, ufw e wait-nvidia versionati.

## 0.6.214 - 2026-10-05

- Fast path media: 5 casi in Impostazioni (crea/modifica/video con prompt
  incollato, analisi sempre chat, foto senza istruzioni chat/video);
  prompt canned saltano gate LLM e chiamata (zero token); preset forzato.
- Audit: triage off-loop anche forzato, guard manual-mode prima del
  verdetto, prompt con {{...}} neutralizzati, has_image reale, preset
  ignoti mai fail-open.

## 0.6.213 - 2026-10-04

- Fast path media: con allegati l'app tenta /jobs/smart (triage Laya,
  prompt-only LLM, submit) con poll, progressi e rendering; fallback
  automatico al flusso normale, stop cancella anche lato server.
- Audit fast path: input confinati a root fidate, node id sanitizzati,
  backpressure 429, triage off-loop, publish idempotente, running
  marcati failed al restart processo, deadline watch direct, UI sempre
  nella conversazione giusta, niente chiamate senza chiave, kind/mime
  dall'estensione reale.

## 0.6.212 - 2026-10-04

- Triage media: decision core con backend regole + LayaBackend multilingual
  (gate 0.75, fallback, decision log); ollaya + laya:multilingual live.
- Manager direct: watch tollerante ai restart Comfy, boot preserve su
  stesso boot_id (reboot vero sempre AUTO).
- Server: modelli SeedVR2 3B per upscale video in Comfy diretto.

## 0.6.211 - 2026-10-04

- App run 4-5: timeout anche sul polling run SSE, claim bounded LRU con
  rilascio stop a esaurimento, viewer immagini con retry, costanti
  centralizzate, dead code rimosso, restore backup completo e sicuro,
  pendingBot oltre il process death.
- Certificazione repo: ruff pulito, 245 test Python verdi, contratto
  visual-blocks ok.

## 0.6.210 - 2026-10-04

- App: upload allegati con Content-Length esplicito (fix 411), navigazione
  senza flash (no-anim, istanza singola per tab, nessun fallback a Chat).
- Manager: TTS/STT sempre con l'LLM (Kokoro int8 su CUDA, warmup STT,
  stop su media/direct, ensure dal watchdog); supporto user-units systemd.

## 0.6.209 - 2026-10-04

- Audit completo app in 3 run: single-flight approvazioni + no-downgrade,
  rehydrate foreground service, timeout SSE con watchdog, deeplink validati,
  notifiche private su lockscreen, risposte tronche marcate, upload parziali
  bloccanti; navigazione su Navigation-Compose, poll unificati lifecycle-aware,
  ChatFeature spezzata in 7 file, bitmap loader con cache, stato chat nel
  ViewModel, export cifrato (Keystore + password), backup ripristino draft.
- Manager/agente: nodo H3MultiStream nel workflow video (split 2 GPU).

## 0.6.208 - 2026-10-04

- Manager: modo DIRECT, ComfyUI diretto sulla tailnet con LLM scaricato
  e worker parcheggiato; chiusura/crash del backend fa auto-ritornare
  su AUTO con LLM ricaricato; mai i due Comfy insieme.
- App: voce "Comfy diretto" nel menu con URL e stato; allegati inviati
  ora persistiti nel messaggio (sopravvivono al riavvio) con fallback
  sobrio se la cache viene pulita.

## 0.6.207 - 2026-10-04

- Manager: ogni (re)boot atterra su AUTO con coda Comfy annullata
  (job interrotti cancellati, non riaccodati); chat-first con fail-fast,
  restore LLM e cooldown dopo failure media ripetuti.
- App: allegati utente ridisegnati (strip miniature sopra il composer,
  immagine sola in chat con viewer come Hermes); flag manager/LLM con
  errori HTTP espliciti e refresh proattivo.

## 0.6.206 - 2026-10-03

- Background work: run detachabili, re-attach, foreground service; auto-approve
  off/session/always globale e per-bot; tab Schermo noVNC con take-over;
  sezione Bot rivista; bridge display su manager; gate updater anti-interruzione;
  resume persistente run.

## 0.6.205 - 2026-10-02

- Composer stile ChatGPT con pulsante arancione; preload voce best-effort, compat PyAV19, cuDNN cu13.

## 0.6.204 - 2026-10-02

- Pulizia bassa priorità: import morti, raw placeholder, expander key, retry voce, export prune, mode debounce, jobs paging, docs off, Bearer mai in ps, XFF, lock stale, sqlite 600.

## 0.6.203 - 2026-10-02

- Sicurezza manager: clamp numerici, allowlist nodi, chiave API obbligatoria, cleanup artefatti, fix overwrite cancelled.
- Script: Bearer mai in ps, rehub --check reale, XFF non fidato, lock stale.
- App: stop stream all'uscita, job singolo in Chat, BT cleanup, draft persistente, niente retry anonimi (stop 401).

## 0.6.202 - 2026-10-02

- Chat: preriscaldamento LLM all'apertura (niente più minuto morto dopo i job media).
- Manager: ritorno a LLM dopo 10s di idle media (era 30s).

## 0.6.201 - 2026-10-01

- Qwen repo-esatto live: DiT Q4 + TE w4a8 + node leejet, stessi workflow della repo 8gb (testati).
- Chat: allegati persistenti, immagini inviate compatte, pill/flag reasoning, anti-ripetizione.

## 0.6.200 - 2026-10-01

- Chat: anti-ripetizione a display (blocchi 2x/4x collassati), pill modello rimossa, flag reasoning affianco al microfono con Auto reale.
- Chat: allegati pending persistenti, immagini inviate compatte.
- Skill hermes-local-media v2.0 riscritta; disabilitate qwen-image-21-native-edit (preset fantasma) e comfyui generica.

## 0.6.199 - 2026-10-01

- Chat: fix doppia risposta (snapshot finale con micro-differenze non più accodato).
- Edit: stack confermato repo-identico (qwen3vl TE + denoise 1.0); i file pe_* sono prompt-enhancer, non encoder.

## 0.6.198 - 2026-10-01

- Chat: menu + ridotto a 3 voci (file, foto, scansione) in bottom-sheet minimal, allegati silenziosi, scatto foto funzionante con FileProvider.
- Chat: bottone fine-chat scorre a fine risposta (non a inizio ultima).
- Edit immagini: denoise configurabile (default 0.8 per restare fedeli), negative repo di default, seconda immagine riferimento opzionale.
- Manager: fix worker che ignorava la coda a LLM caricato, heartbeat worker_alive_s, reference forte al task.

## 0.6.197 - 2026-10-01

- Chat: la penna in alto a destra diventa menu a 3 puntini (impostazioni rapide); dentro: Nuova chat e flag LLM su GPU (stato reale da gpu-manager, accende/scarica il modello al tocco).
- Profilo: bottone Riavvia server con conferma, riavvio completo (come sudo reboot now) via nuovo endpoint manager POST /system/reboot.
- Manager: cancel reale con stop prompt ComfyUI, escaping JSON nei template, limite coda 429, reboot fire-and-forget.
- Chiamata vocale in ViewModel: la rotazione non uccide più la chiamata (stato + tono + coroutine sopravvivono al recreate).

## 0.6.196 - 2026-10-01

- Media senza card: immagine nuda con viewer e icona download, dedup per URL, kind corretto dall'evidenza, loopback riscritto sull'host gateway.
- Player video inline, audio compatto, riga slim documenti.
- Traccia reasoning intera e cerchio su finestra reale (come 0.6.195, incluso qui).

## 0.6.195 - 2026-10-01

- Ragionamento e risposte: gli snapshot non cumulativi si accumulano invece di mostrare solo l'ultimo token; merge su finestre sovrapposte.
- Cerchio contesto tarato sulla finestra reale (131072 token runtime): parte da 0 e sale col contesto, senza percent del compattatore.

## 0.6.194 - 2026-09-30

- Shadow conversations: le sessioni agent fuori Hub (es. desktop ufficiale) compaiono in Hub con le risposte dell'assistente; continuazione via previous_response_id. Sync via timer server ogni 2 minuti.
- Bottoni azione a icone in Archivio, Bot, Cron, Server, Impostazioni, Jarvis e chat (-testuale solo dove serve: selettori, conferme, wizard).
- Stop chat risanato: niente più stream orfani né composer bloccato dopo lo stop.
- GPU manager autosufficiente: watchdog LLM, recovery ERROR, igiene shm, fix deadlock GPU_FREE, guard anti-tracking perso.

## 0.6.193 - 2026-09-30

- Timeline: tutti i tool in un unico flag richiudibile; bottone freccia-giù per tornare a fine chat quando il fondo è lontano.
- Media in chat senza card: immagine nuda con viewer e icona download, player inline per video e audio compatti, riga slim per documenti; dedup per URL e correzione kind document→image.

## 0.6.192 - 2026-09-30

- Timeline: prima i tool (chiusi), sotto un unico canvas di ragionamento; nomi funzione catturati al parse, specifiche visibili con segreti oscurati e persistite in archivio.
- Cerchio contesto: fallback alla finestra reale 262k del backend Qwen/EXL3; ricalcolo su cambio server/modello.
- Manager GPU: rilevamento H3 reale (era hardcoded non pronto).

## 0.6.191 - 2026-09-30

- Timeline attività compatta: prefill mostrato una sola volta sopra i tool, tool e ragionamento in righe richiudibili dentro il flag Attività Hermes.
- Nomi tool leggibili (nome funzione, id corto come fallback); metrica acceptance "Accept".
- Cerchio contesto ricalcolato anche su cambio server/modello.

## 0.6.190 - 2026-09-30

- Fix framing SSE gateway (`_enrich_sse_chunk` ora termina ogni frame con riga vuota): niente più risposte vuote su Android né dump di eventi raw; verificato sul flusso live `/v1/responses`, con regression test.
- Android: riga di stato gateway a scorrimento orizzontale, leggibile per intero.
- Android: dialog modello mostra i provider server con warning quando non ci sono singoli modelli selezionabili (il backend LLM resta quello configurato sul server).
- Android: player video inline nelle card media_file della chat (anteprima, controlli, schermo intero, fallback MP4 compatibile); i video restano salvati automaticamente negli Artefatti versionati.

## 0.6.189 - 2026-09-30

- Reasoning effort con fallback template-safe `xhigh/medium/low` quando il server non dichiara la ladder; selettore sempre visibile, mai più `max`/`ultra`/`none` verso i template EXL3.
- Patcher gateway multi-modulo (`api_server.py` + `api_server_openai_routes.py` + `api_server_runs.py`): re-patch dopo ogni aggiornamento agent via `rehub-patch`, senza più route `/v1/hub/*` in 404.
- Launcher Linux con `readlink -f` per symlink e documentazione re-patch in `docs/hermes-hub-linux.md`.

## 0.6.188 - 2026-09-29

- Sidebar con sezioni collassabili persistenti (Operatività, Controllo, Contenuti, Account, Recenti ridotti con link all'archivio).
- Sezione Bot ripresentata: gerarchia azioni, stati vuoti con CTA, conferma rimozione connessioni, errori di validazione visibili, esiti turno in italiano.
- Conferme distruttive su Cron, Archivio, Reset impostazioni e manutenzione server; label Impostazioni chiarite.
- Reasoning effort `max`/`ultra` normalizzato a `xhigh` senza capabilities note (niente più HTTP 400 dai template EXL3); override `null` dell'archivio riletti come vuoti.
- Sezione Server con stato GPU Manager (Modalità GPU, VRAM, coda media) e label gateway leggibile.

## 0.6.187 - 2026-09-18

- Nei parametri dei messaggi agente compare l'acceptance rate dello speculative decoding (`Acc NN%`, con tipo `mtp`/`dflash`/`dspark` quando dichiarato dal server), con toggle dedicato nelle metriche.
- Progetto verticale su Android: la parte Windows e' esclusa dalle attivita' ordinarie (direttiva in `AGENTS.md`).

## 0.6.186 - 2026-09-17

- Android usa la Sessions API nativa Hermes (`/api/sessions`, chat/stream, messaggi, fork, rename/delete propagate) come percorso primario della Chat quando `/v1/capabilities` la dichiara, con fallback legacy esplicito.
- Runs API completa in Chat: stop reale, steer con gestione 409/`pending_steer`, approval server-side con scelte dinamiche offerte dal server, eventi SSE nativi come fonte primaria.
- Model picker moderno (`/api/model/options` con fallback `/v1/models`), reasoning effort per-chat con ladder capability-driven e model lock persistente per sessione.
- Cron Android allineato al trasporto esterno `/api/jobs` (whitelist verificata su upstream): name/schedule/prompt/deliver/skills, pausa/resume/run/elimina; i campi dashboard-only restano in sola lettura.
- Rename/delete sessioni e model lock falliscono esplicitamente senza falsi successi; isolamento auth profili fail-closed anche su GET e session-chat.

## 0.6.185 - 2026-08-22

- Bot Mode usa i profili reali di Hermes Agent per elenco, creazione, modifica, clonazione e rimozione, senza inventare un runtime bot locale.
- Bot groups orchestrano da 2 a 6 bot anche su connessioni diverse, con routing per menzioni, richiesta utente originale preservata e limiti espliciti di 3 round e 10 risposte.
- Connections aggiunge endpoint nominativi cross-machine con token separati per connessione, conservati negli store sicuri del client e mai nel catalogo JSON; handle stabili disambiguano bot omonimi.
- Errori di singoli membri restano parziali e visibili, senza retry incerti dopo un'accettazione server; cancellazione e sostituzione del turno invalidano davvero lavoro e salvataggi tardivi.
- Bot Chat diventa il percorso canonico per i profili Hermes e resta fail-closed quando il multiplexing profili non è esplicitamente disponibile; cron e chat mantengono lo scope del profilo.
- Rafforzato lifecycle Meta DAT/Jarvis con inizializzazione process-wide idempotente, sessione/stream serializzati, stato attivo solo dopo `STREAMING`, cleanup deterministico e osservazione unica degli errori terminali.

## 0.6.184 - 2026-08-11

- Aggiunta timeline attivita' stile Codex per ragionamento, avanzamento e tool, disponibile durante streaming e negli archivi Windows/Android senza esporre eventi grezzi come risposta finale.
- Android carica impostazioni, credenziali Keystore e profilo voce prima dell'interfaccia; la migrazione conserva dati e nessuna credenziale viene scritta in chiaro se Keystore non e' disponibile.
- Stato gateway mostra solo disponibilita' autenticata reale e stato aggiornamento; gateway irraggiungibile mantiene esattamente `Rete non disponibile`.
- Rafforzato lifecycle Ray-Ban Meta DAT: permesso camera, stabilizzazione link, timeout stream e chiusure osservate; DAM resta disabilitato per percorso camera-only.
- Gateway Linux include updater separato e transazionale Hermes Agent, con rollback, quarantena revisioni fallite, check capability autenticato e endpoint runtime privo di segreti.
- Pacchetto Linux include nuovi script e timer updater Agent; aggiornamento gateway conserva e reinstalla le unit correlate.

## 0.6.183 - 2026-08-02

- Il patcher gateway supporta il nuovo upstream Hermes Agent basato su `agent_kwargs` e autenticazione profile-aware, evitando il crash-loop `model route max tokens agent`.
- Gli alias API Hermes Hub restano validi sul listener predefinito senza indebolire l'isolamento delle chiavi dei profili nominati.
- Android mostra il gateway verde soltanto dopo un probe autenticato reale a `/v1/capabilities`; gateway irraggiungibile o non autenticato produce pallino rosso e `Rete non disponibile`.
- Aggiunta fixture dell'upstream Hermes Agent `0a62610f1` con test di compilazione, idempotenza e compatibilita' del patcher.

## 0.6.182 - 2026-08-02

- Ray-Ban Meta DAT acquisisce a 7 FPS e filtra i cambiamenti sul piano luminanza prima della compressione JPEG.
- L'observer usa un solo worker latest-frame: completa l'inferenza corrente, scarta i frame intermedi e adatta la cadenza alla latenza del modello.
- Il percorso comune usa una sola inferenza multimodale compatta sul modello principale; Hermes Agent completo viene riservato a tool, memoria durevole, verifiche critiche e ragionamento complesso.
- Memoria breve aggiornata nello stesso output strutturato, senza un Summarizer LLM separato; domande vocali prioritarie sulle osservazioni passive.
- Jarvis usa STT con `beam_size=1`, rilevamento fine-frase piu' rapido e TTS WAV a segmenti riprodotti mentre il gateway genera i successivi.
- Aggiunti contratti, test comportamentali e direttive permanenti per impedire regressioni sulla pipeline DAT/Jarvis.

## 0.6.181 - 2026-08-02

- Meta DAT viene inizializzato una sola volta per processo e condivide lo stesso runtime tra configurazione occhiali e avvio Jarvis.
- `WearablesError.ALREADY_INITIALIZED` viene riconosciuto come stato valido invece di interrompere immediatamente la sessione.
- Aggiunto contratto di release che impedisce nuove inizializzazioni DAT dirette fuori dal coordinatore process-wide.

## 0.6.180 - 2026-08-02

- Android allinea avvio e streaming Ray-Ban Meta al ciclo di vita ufficiale DAT: una sola sessione, attesa di `STARTED`, creazione dello stream e stato attivo soltanto dopo `STREAMING`.
- Rimossi retry automatici e suggerimenti di riavvio Bluetooth che potevano produrre il ciclo sonoro entra/esci sugli occhiali.
- Aggiunti permessi Bluetooth completi, DAM esplicito, controllo preventivo del permesso fotocamera degli occhiali e accesso all'aggiornamento DAT.
- Errori di registrazione, sessione e stream vengono osservati e propagati una sola volta; Jarvis non resta piu' falsamente attivo dopo una chiusura del dispositivo.
- Aggiunti contratti di release per impedire regressioni su manifest, permessi, stato `STREAMING` e assenza del retry-loop.

## 0.6.179 - 2026-07-26

- Android usa il provider Health Connect predefinito di sistema sia per disponibilita', autorizzazione e lettura, eliminando il falso invito ad aggiornare Health Connect sui telefoni recenti.
- Dashboard e sincronizzazione aggregano i dati in una sola lettura per intervallo; lo storico settimanale non esegue piu' una serie di richieste complete per ogni giorno e categoria.
- Aggiunti cache breve, mutua esclusione e cooldown esplicito per evitare di riesaurire la quota Health Connect con tap, navigazione o worker concorrenti.
- Gli errori quota vengono attribuiti a Health Connect prima di contattare Hermes; l'URL wellbeing viene ricostruito correttamente anche da endpoint completi o reverse proxy.
- Aggiunti test Android e di contratto per provider, rate limit, aggregazione e normalizzazione URL.

## 0.6.178 - 2026-07-26

- Il gateway Linux cerca aggiornamenti ogni due minuti e, dopo un aggiornamento riuscito, ricarica anche il timer per applicare subito la nuova cadenza.
- Android riconosce il rate-limit restituito da gateway privi delle route wellbeing anche quando il codice HTTP non e' `429`, indicando l'aggiornamento del gateway invece di mantenere il retry.
- Aggiunti test di regressione per timer, riavvio del timer e diagnosi Health Connect/gateway.

## 0.6.177 - 2026-07-26

- Aggiunta sezione Android Salute con dati letti direttamente da Health Connect: valori di oggi e grafici degli ultimi sette giorni per passi, sonno, allenamenti e frequenza cardiaca aggregata.
- La dashboard resta disponibile anche se il gateway non e' aggiornato; dati wellness mostrati in locale e mai salvati come campioni grezzi.
- La sincronizzazione ora riconosce il `429 Rate limited request quota` di un gateway privo delle route wellbeing e indica esplicitamente di aggiornare il pacchetto Linux Hermes Hub.
- Aggiunti test di contratto per storico locale, dashboard e diagnosi del gateway non aggiornato.

## 0.6.176 - 2026-07-26

- Android integra Health Connect per ricevere, previo consenso, i dati che Galaxy Watch 7 sincronizza tramite Samsung Health: passi e calorie, sonno, allenamenti e frequenza cardiaca aggregata.
- La sincronizzazione invia soltanto riepiloghi giornalieri scelti dall'utente; nessun campione, battito grezzo o dato medico viene conservato nel gateway.
- Il gateway aggiunge endpoint autenticati `GET/PUT/DELETE /v1/hub/wellbeing`, validazione stretta, retention configurabile, scrittura atomica e cancellazione completa dal client.
- La lettura in background richiede il permesso separato di Health Connect; una revoca interrompe il lavoro periodico senza ritentare silenziosamente.
- Aggiunti test di contratto per privacy, payload, autenticazione, limiti, lock e idempotenza del patcher.

## 0.6.175 - 2026-07-26

- Ripristinati i default illimitati per request e upload file del gateway (`0`); restano configurabili limiti espliciti per installazioni che li richiedono.
- Il patcher converte anche gateway gia' aggiornati alla configurazione finita di 0.6.174, riportando capability e default al comportamento precedente.
- Aggiunti test di regressione per launcher, patch su upstream puro, idempotenza e migrazione da 0.6.174.

## 0.6.174 - 2026-07-26

- Le credenziali Android non vengono piu' esportate nei backup locali e non possono degradare in chiaro se Android Keystore non e' disponibile.
- Token e API key Hermes restano confinati all'origine configurata: URL media esterni e link copiati non ricevono piu' Bearer o query token.
- Jarvis Android annulla in modo deterministico gli avvii incompleti, attende la sorgente prima dello stato attivo e impedisce feedback o aggiornamenti su sessioni scadute.
- Il gateway Jarvis ricontrolla la sessione dopo ogni I/O asincrono, impedendo frame, turni e feedback tardivi su sessioni eliminate.
- Le nuove installazioni Android non tentano piu' la sincronizzazione archivio finche' non e' configurato un endpoint Hermes assoluto.
- Il gateway applica limiti finiti e configurabili a request e upload e rifiuta il base64 sovradimensionato prima della decodifica in memoria.
- Aggiunti test di regressione per backup, Keystore, origine media, lifecycle Jarvis, concorrenza gateway e configurazione iniziale.

## 0.6.173 - 2026-07-25

- Gateway Linux ora completa warm-up reale di Whisper STT e Kokoro TTS su GPU prima di accettare traffico; preload o CUDA mancanti fanno fallire esplicitamente l'avvio, senza fallback CPU silenzioso.
- Il packaging Android ufficiale e' ora DAT-only e fallisce senza PAT Packages, credenziali Meta reali, classi DAT, `META_DAT_ENABLED=true`, `minSdk 29`, versione e firma storica corrette.
- Gradle blocca ogni task release senza DAT, salvo override esplicito riservato allo sviluppo; AGENTS e CI usano lo stesso script ufficiale verificabile.

## 0.6.172 - 2026-07-25

- Corretto il launcher Linux che ruotava silenziosamente la chiave API a ogni riavvio invece di recuperare i valori gia persistiti in `~/.hermes/.env`.
- Le chiavi configurate esistenti, incluse quelle legacy piu corte, vengono conservate; le nuove installazioni senza chiave continuano a generarne una casuale forte.
- L'updater Linux recupera la chiave di probe dal file persistente e privilegia gli alias Hub, evitando rollback o gateway irraggiungibili dopo l'aggiornamento.
- Aggiunti test di regressione per chiave primaria, alias compatibile e probe post-riavvio.

## 0.6.171 - 2026-07-25

- Aggiunta la modalita Jarvis Android: sessione vocale e visiva temporanea, streaming SSE, foreground service, pausa vista, solo domande e cleanup deterministico.
- Integrata la sorgente Ray-Ban Meta tramite source set DAT 0.8.0 opzionale e la fotocamera telefono per debug, senza archiviare frame o trascrizioni.
- Gateway esteso con sessioni Jarvis autenticate, upload frame limitato, priorita alle domande, deduplicazione, cooldown e inoltro dei frame originali al ragionamento.
- Rimossa la dipendenza operativa dal modello 0.8B: osservazione e domande usano soltanto il modello principale; l'osservatore passivo non puo parlare direttamente.
- Aggiunta l'architettura Reactor: perception bus effimero, memoria breve incrementale, finestra conversazionale, trigger saliente, deduplicazione semantica e feedback utile/non utile.
- L'iniziativa resta guidata esclusivamente da nuovi eventi rilevanti: nessun messaggio forzato o timer periodico di conversazione.
- Aggiunti benchmark riproducibile da 50 casi, schemi JSON e test di contratto per gateway, Android e privacy.

## 0.6.170 - 2026-07-18

- Ragionamento separato dalla risposta finale: eventi SSE `analysis`, `reasoning`, `analysis_content` e blocchi `<think>` finali alimentano la sezione dedicata su Windows e Android.
- Gli item di analisi non contaminano piu' il testo della risposta; se il modello non espone ragionamento, l'interfaccia lo dichiara esplicitamente.

- Wake word trasformata in attivazione reale: quando Hermes Hub è aperto, la frase configurata porta l'app in primo piano, apre Voce e avvia la chiamata su Windows e Android.
- Dentro una chiamata non serve più ripetere la wake word a ogni intervento; la conversazione resta continua fino alla chiusura.
- Android mantiene l'ascolto wake word tramite servizio microfono foreground e richiede il permesso audio quando il toggle viene abilitato.
- Corretto il riavvio del listener dopo stop e cambi pagina; con frase `Hermes` sono accettate anche invocazioni naturali come `Ehi Hermes` e `Ok Hermes`.
- CI Windows resa affidabile su SDK recenti: corretti analyzer .NET e bootstrap di PSGallery per PSScriptAnalyzer.
- Patcher gateway compatibile con callback Responses già modificate dalle release precedenti, evitando il crash loop visto durante l'aggiornamento 0.6.164.
- Updater Linux mette in quarantena lo stesso asset fallito dopo rollback, impedendo nuovi blackout orari finché versione o digest non cambiano.

## 0.6.164 - 2026-07-16

- Wake word configurabile per progetto su Windows e Android: preset `Hermes`, `Ehi Hermes`, `Ok Hermes` o frase personalizzata.
- Matching wake word reso robusto a maiuscole, accenti, punteggiatura e trascrizione italiana `Ermes`; lo stato Voce indica quando attende la frase scelta.
- Corrette le tre operazioni rapide della chat: le liste trasparenti non intercettano più click e tap nello stato vuoto.
- Impostazioni Voce estese con selezione della forma particelle, preservando profili e preferenze già salvati.

## 0.6.163 - 2026-07-16

- UI Windows riallineata al linguaggio visivo Android: palette scura comune, superfici gerarchiche, accento arancione e bordi coerenti.
- Sidebar Windows riorganizzata per aree operative, con stato selezionato, chat recenti e intestazione contestuale per ogni sezione.
- Home Chat Windows aggiornata con sfondo sfumato, stato vuoto compatto, operazioni rapide verticali, context meter e composer rifiniti.
- Messaggi Windows aggiornati con label `HERMES`, bubble utente asimmetrica, streaming coerente e azioni integrate nel nuovo stile.
- Normalizzate card e superfici di Impostazioni, Server, Archivio, Cron, About, News e Video senza cambiare contratti, dati o impostazioni salvate.

## 0.6.162 - 2026-07-16

- Rimosso il nome del backend dagli stati chat: l'interfaccia indica ora connessione, attesa del primo evento, elaborazione prompt e generazione risposta.
- La percentuale di elaborazione prompt usa esclusivamente i contatori reali `processed/total` ricevuti dal server; progressi stimati e conteggi token dedotti dai caratteri non vengono mostrati.
- Il ragionamento e' sempre accessibile dalla voce cliccabile dedicata su Windows e Android, anche per dichiarare in modo esplicito quando il server non lo ha inviato.
- Gateway esteso per richiedere il progresso reale a llama.cpp e inoltrare `reasoning_content` dai chunk modello agli eventi Hermes.
- Windows salva e sincronizza il ragionamento nell'archivio, mantenendolo disponibile dopo riapertura e cambio dispositivo.

## 0.6.161 - 2026-07-15

- Ripulita la sezione Voce su Windows e Android: controlli spostati nelle Impostazioni e profili Kokoro limitati alle voci realmente disponibili `if_sara` e `im_nicola`.
- Corretta la risposta Android ripetuta: gli snapshot finali SSE sono autoritativi e il reasoning resta separato nella tendina persistente.
- Semplificati i Progetti a nome e system prompt facoltativo, con selezione e attivazione automatiche su entrambe le piattaforme.
- Le nuove chat ricevono una sola volta un titolo generato da Hermes dopo la prima risposta; le tre azioni rapide ora inviano davvero la richiesta.
- Gateway aggiornato per inoltrare reasoning e system prompt progetto dedicato, limitato e distinto dai system prompt generici del client.

## 0.6.160 - 2026-07-14

- Introdotti workspace progetto su Windows e Android, con contesto attivo, istruzioni, memoria, strumenti autorizzati, chat e artifact collegati.
- Aggiunti ricerca universale, gestione conversazioni, esportazione/importazione, ramificazioni e indicizzazione degli artifact.
- Estesi Automation Studio, notifiche, continuita' tra dispositivi, audit operativo e controllo dei servizi del server.
- Android integra widget, scorciatoie, tile Voce, risposta rapida dalle notifiche e servizio foreground per le chiamate vocali.
- Gateway aggiornato con i nuovi contratti Hub e gestione corretta dei servizi systemd utente/sistema, inclusi restart differiti e audit.

## 0.6.159 - 2026-07-14

- Gateway TTS: pronuncia mista italiano/inglese per termini tecnici, con segmenti inglesi `en-us` e fallback sicuro alla voce italiana.
- Il patcher Kokoro unisce i segmenti WAV con micro-pause e conserva fallback CPU/CUDA e timeouts esistenti.
- Aggiunti test di segmentazione e regressione del patcher idempotente.
- Completato il rename della repository in `JackoPeru/HermesHub` e aggiornati updater Windows, Android e Linux, documentazione e metadati systemd.
- Preservati `applicationId`, package identity, firme, percorsi dati `ChatClaw`, namespace e nomi dei servizi; l'override Linux `HERMES_HUB_REPO` resta disponibile.

## 0.6.158 - 2026-07-14

- Corretto il loop di rotazione del player Android in schermo intero quando la rotazione automatica e' disattivata.
- Reso il fullscreen transitorio e stabile, senza ricreazioni dell'Activity o ripristini concorrenti dell'orientamento.
- Gestiti separatamente landscape fisso e landscape sensor in base all'impostazione di sistema.
- Rifiniti immersive mode, supporto notch, controlli Media3 e barra superiore a scomparsa.
- Aggiunti test regressione per la politica di orientamento fullscreen.

## 0.6.157 - 2026-07-14

- Ridisegnata la UI Android con una gerarchia piu pulita e una palette scura coerente.
- Rimossa la barra di navigazione inferiore e introdotto un drawer globale organizzato per aree operative.
- Rinnovate testata Chat, stato vuoto, messaggi, azioni rapide e composer.
- Aggiunta una testata coerente alle sezioni secondarie, con accesso diretto alla navigazione e ritorno alla Chat.
- Preservati firma, `applicationId`, dati, configurazione e percorsi funzionali esistenti.

## 0.6.156 - 2026-07-14

- Audit manuale completo di Windows, Android, gateway, script, build e packaging.
- Correzioni a cancellazione, timeout, retry, persistenza atomica, sync archivio, allegati, TTS/STT e lifecycle.
- Updater app e gateway resi transazionali con validazione e rollback.
- Patcher gateway reso idempotente e verificato contro Hermes Agent upstream 0.18.2.
- Rimossi asset, log, dati diagnostici e documenti obsoleti tracciati per errore.
- Aggiunti test automatici, quality gate e prove runtime pre-release.
- Corretto doppio rendering Android di risposte SSE brevi e resa la discovery gateway limitata, cancellabile e senza tentativi ridondanti.
- Corretto stato persistente di annullamento Windows, filtro dischi virtuali Android/gateway e copia MSIX dopo firma.

## 0.6.155 - 2026-07-12

- Chiamate tool Windows raggruppate nell'expander collassato `Azioni`.

## 0.6.154 - 2026-07-12

- Rendering particelle Windows rifinito con glow Win2D.
- Assemblaggio particelle Android reso frame-driven.
- Suono d'attesa Android spostato su `MediaPlayer`.

## 0.6.152 - 2026-07-11

- Modalita Voce continua riscritta su Windows e Android.
- VAD PCM, pipeline STT/chat/TTS e cleanup unificati.
- Kokoro ONNX accelerato su GPU nel gateway, con fallback CPU.

Per le release precedenti consultare la [pagina Releases](https://github.com/JackoPeru/HermesHub/releases).
