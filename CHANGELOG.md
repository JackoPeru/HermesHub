# Changelog

Le modifiche rilevanti di Hermes Hub sono documentate qui. Le release GitHub restano la fonte per asset e note complete.

## Unreleased

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
