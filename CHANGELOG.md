# Changelog

Le modifiche rilevanti di Hermes Hub sono documentate qui. Le release GitHub restano la fonte per asset e note complete.

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
