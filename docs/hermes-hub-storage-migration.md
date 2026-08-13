# Hermes Hub structured storage migration

## Stato attuale

Il gateway ora compone `SQLiteHubStateStore` e `RevisionSyncEngine` nel
runtime generato dal patcher. SQLite usa migrazioni esplicite, WAL, foreign
keys, `busy_timeout`, query con limite e transazioni per import e scritture.

Sono Hub-owned e migrabili in questa fase:

- conversazioni, inclusi tombstone e metadati necessari all'archivio;
- stato strutturato Hub generico e relativo change log/revisioni;
- eventi di sincronizzazione e metadati di migrazione.

I file JSON legacy restano invariati come sorgente verificabile durante
l'import. Un import fallito esegue rollback SQLite e lascia il file originale
intatto; un import riuscito conserva comunque il file per il cutover e il
rollback operativo. Le API archivio compatibili continuano a delegare al
runtime SQLite quando il package modulare è presente.

## Non migrato intenzionalmente

Hermes Agent resta proprietario di memoria durevole dell'agente, planning,
tool loop, policy, retry e cron/job ownership. Questi dati non vengono copiati
nel database Hub con questa modifica. Media, allegati, artifact ed export
restano file su storage dedicato; SQLite conserva soltanto i riferimenti e i
metadati Hub necessari.

## Strategia successiva

Ogni modulo ulteriore deve essere migrato separatamente con:

1. schema versionato e migration forward-only verificata su una copia;
2. import transazionale con conteggi, tombstone e revisioni confrontati;
3. backup/sorgente legacy preservata fino al cutover osservabile;
4. capability negotiation e rollback fail-closed prima di rimuovere il
   percorso legacy.

Non esiste una dual-write permanente: durante la transizione il JSON è una
sorgente/import legacy e SQLite è il percorso Hub attivo solo per le entità
dichiarate sopra. Una capability o una migration report rende visibile il
percorso effettivo al client e agli operatori.
