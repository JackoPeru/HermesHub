# Hermes Character ID — UI (app Android, tab Personaggi in CONTENUTI)

## Schermata Personaggi

Griglia card responsive: avatar-iniziale, nome, stato italiano, `Identità NN% ·
Auto · LoRA/Reference` oppure conteggio foto. Azioni: `[Usa]` (solo `ready`),
`[Apri]`. Poll lista ogni 15s a schermo attivo. CTA `+` e empty-state guidano
alla creazione.

## Wizard Crea personaggio (5 passi)

1. **Nome** — campo + Continua (crea draft `POST /characters`).
2. **Foto** — picker multiplo `image/*`, upload a batch (tetto 80, 15MB/cad):
   `POST .../images`.
3. **Analisi** — `POST .../analyze` + progress reale dal job
   (`Analisi NN%`), errori onesti (foto insufficienti, nessun volto…).
4. **Qualità dataset** — conteggio utilizzabili + checklist angoli
   (frontale, 3/4, profili, figura intera) + raccomandazioni; training
   bloccato sotto 20 foto.
5. **Pronto** — `N foto pronte` + `[Crea Nome]` = `POST .../train` (mai
   chiamato "Train LoRA" in UI).

## Dettaglio personaggio

Header (nome, stato), card info (foto, versione modello, identity score,
motore consigliato), checklist qualità, azioni: Usa / Crea-Ripeti / Rinomina /
Esporta / Elimina (doppio tap). Durante i job: fasi in italiano
(`Preparazione → Cache identità → Apprendimento NN% → Verifica coerenza`),
pulsante Annulla, `Dettagli tecnici` collassato (job id, kind, errori).
Nomi tecnici (Musubi, rank, trigger token, block swap) mai mostrati.

## Generazione (foglio Usa)

- Personaggio fisso (selezionato dalla card), `@Nome` accettato nel prompt.
- `Identità [Auto]` (Auto / Character LoRA / Reference); Hybrid nascosto
  finche non esiste un Ref2VA-LoRA reale.
- Durata 3/5/10s, formato 16:9/9:16/1:1; `Impostazioni avanzate identità`
  collassato (seed; strength e LoRA extra restano server-side).
- Submit → poll job media → esito + scorciatoia alla sezione Video.

## Stati e testi

Stati: Bozza, Analisi foto, Pronto a creare, Creazione, Verifica, Pronto,
Errore, Da migliorare, Interrotto. Motivi reject mostrati in chiaro
(`Troppo mossa`, `Piu persone`, `Duplicato`, `Volto troppo piccolo`…).

## V1 non include (onesto)

Thumbnail remote nelle card (niente dipendenze nuove: avatar-iniziale),
anteprime video inline (nomi file + export), multi-persona (`@A + @B`
rifiutato dal backend con 409 esplicativo).
