# Jarvis observer synthetic benchmark

`manifest-v1.json` descrive 50 casi sintetici. Le immagini non sono archiviate:
`scripts/benchmark-jarvis-observer.py` le genera deterministicamente come PNG in
una directory temporanea.

Il dataset non contiene fotografie, volti, dati biometrici, endpoint, token o
altro materiale privato. Forme, testi e scene sono creati dal renderer locale
`jarvis-synthetic-raster-v1`.

Validazione offline:

```powershell
python .\scripts\benchmark-jarvis-observer.py --validate-only
```

Il benchmark live richiede endpoint e modello espliciti. Nessuna API esterna è
usata come fallback.
