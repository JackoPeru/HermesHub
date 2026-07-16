# Windows

App WinUI 3 x64 di Hermes Hub.

## Compatibilita

- nome visibile: `Hermes Hub`;
- storage storico: `%LOCALAPPDATA%\ChatClaw`;
- package identity e publisher MSIX devono restare coerenti con le release precedenti;
- Windows 10 build minima: `17763`.

Migrazioni e ripristino JSON devono avvenire prima dell'avvio dei servizi che leggono lo store.

## Backend

L'endpoint non e' preconfigurato: inserisci il nome MagicDNS del tuo server, per esempio `http://<nome-magicdns>:8642/v1`. Gli aggiornamenti conservano impostazioni e dati gia' presenti; solo una nuova installazione o il reset partono vuoti.

Hermes Native/Responses e' il protocollo preferito. Health, capabilities, archivio, media, voce, jobs e runs passano dal gateway Linux.

## Build e test

```powershell
dotnet format .\NemoclawChat.sln --verify-no-changes
dotnet build .\src\NemoclawChat.Windows\NemoclawChat.Windows.csproj -c Release -p:Platform=x64
dotnet build .\src\ChatClaw.AdminBridge\ChatClaw.AdminBridge.csproj -c Release
```

## Pacchetto

```powershell
.\scripts\package-windows-msix.ps1 -Version X.Y.Z -Platform x64
```

Lo script deve produrre un solo MSIX, verificarne manifest e firma e rifiutare output vecchi o ambigui.

## QA release

- avviare la build x64 reale;
- navigare Chat, Voce, Archivio, Server, Video e Impostazioni;
- inviare e interrompere uno stream;
- verificare TTS, STT, allegati e download;
- chiudere l'app durante operazioni attive e verificare cleanup;
- installare l'MSIX sopra la release precedente;
- controllare firma, publisher, versione e log updater.

## Regole runtime

- nessuna I/O o rete pesante sul dispatcher UI;
- CTS sostituiti e disposti senza race;
- store JSON atomici con recovery da file temporaneo/backup;
- sync stoppabile e senza loop causati dal proprio merge;
- download updater su `.partial`, poi validazione e rename;
- credenziali escluse da backup e log.
