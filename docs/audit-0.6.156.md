# Audit release 0.6.156

Verifica manuale eseguita il 14 luglio 2026. Nessuna release e' dichiarata valida senza queste prove.

- Statico: `ruff`, `py_compile`, 21 test Python, contratto visual blocks, `bash -n`, ShellCheck e PSScriptAnalyzer completati senza errori.
- Android: clean lint, 21 unit test e APK release completati. APK `com.nemoclaw.chat` versione `0.6.156` codice `160`, firma storica invariata. Runtime su API 36: chat SSE `OK` singolo, Hardware senza filesystem virtuali, Voce aperta, nessun FATAL/ANR; dati test eliminati.
- Windows: format, Debug/Release x64 e AdminBridge Debug/Release completati. MSIX firmata e versione `0.6.156.0`; runtime package reale: chat, gateway, archivio, voce, hardware e annullamento stream verificati. L'annullamento mantiene `Risposta interrotta.` e riabilita invio.
- Gateway: package Linux verificato con `VERSION` `0.6.156`, sola whitelist di script e permessi eseguibili attesi.
- Asset: SHA-256 Android `C9E820CFA02AB9D1E0BB0F5FAA208E93C0B71607DAF11D932F93EBF9757C28D4`; Windows `FD4FF7B62924E626FB59CC225F0E017DC1A161FAEA0D2049DD35E3D76F2A651E`; gateway `5BFCF76745297653DCF7280868C28EFF93D01190DF8DC3A84165C582AAA2244C`.
