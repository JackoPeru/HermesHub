# Debug Session: android-notifications-auth

Status: OPEN

## Sintomo

- Android sembra non controllare correttamente le notifiche.
- Evidenza utente: il gateway riceve `GET /v1/hub/notifications?unread=1` da Android ma risponde `Invalid API key`, mentre Windows riceve `200`.

## Ipotesi iniziali

1. Il worker Android legge una API key diversa da quella usata dalla chat/UI principale.
2. La API key salvata dall'app non viene resa disponibile correttamente al `WorkManager`.
3. Il polling notifiche usa un path/auth flow differente rispetto alle chiamate chat e quindi perde il token corretto.
4. La secret salvata localmente viene troncata, migrata male o sovrascritta in uno storage diverso.
5. Il gateway sta rifiutando solo una variante di auth che Android usa nel worker ma non nella UI foreground.

## Piano

1. Strumentare solo lettura secret, scheduling worker e chiamata `loadHubNotifications`.
2. Riprodurre il problema su Android con log runtime.
3. Confermare o scartare le ipotesi.
4. Applicare fix minimo solo dopo evidenza.

## Stato Corrente

- Debug server avviato in modalita' remote.
- File env generato in `.dbg/android-notifications-auth.env`.
- Strumentazione aggiunta in `MainActivity.kt` su:
  - `loadGatewaySecret()`
  - `saveGatewaySecret()`
  - `HermesNotificationWorker.doWork()`
  - `httpGetResponse()` solo per `/v1/hub/notifications`
- Build `assembleDebug` completata con successo.

## Analisi Confermata

- Evidenza disponibile: dal gateway risulta `GET /v1/hub/notifications?unread=1` da Android con risposta `Invalid API key`, mentre Windows ottiene `200`.
- Root cause piu' probabile e coerente col codice:
  1. `saveGatewaySecret()` salva in chiaro il valore se la cifratura fallisce.
  2. `loadGatewaySecret()` fino a prima del fix trattava quel formato non cifrato come errore e tornava silenziosamente `hermes-hub`.
  3. `loadGatewaySecret()` leggeva inoltre solo `CURRENT_SETTINGS_PREFS`, senza recuperare esplicitamente la secret da `LEGACY_SETTINGS_PREFS` se il nuovo store era gia' popolato ma senza chiave.
- Effetto pratico: UI/settings potevano sembrare corrette, ma il worker notifiche poteva finire a chiamare il gateway con la key fallback sbagliata.

## Fix Applicato

- `loadGatewaySecret()` ora usa `migratePrefs(...)`.
- Se la secret e' assente nel prefs corrente, prova a recuperarla dal prefs legacy e la migra.
- Se trova una secret in formato plaintext/legacy, la usa invece di sostituirla con `hermes-hub`.
- `saveGatewaySecret()` salva la secret nel prefs migrato coerente e rimuove l'eventuale vecchio campo `gatewaySecret`.

## Stato

- Fix applicato.
- Build Android debug riuscita.
- In attesa di verifica utente sul device.

## Prossima Riproduzione

1. Installare la build debug strumentata su Android.
2. Aprire l'app, verificare/impostare la API key corretta nelle impostazioni.
3. Lasciare schedulare il worker o forzare il controllo notifiche.
4. Raccogliere gli eventi in `.dbg/trae-debug-log-android-notifications-auth.ndjson`.
