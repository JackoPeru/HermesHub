# Visual Blocks contract fixture

`visual-blocks-fixture.json` contiene un esempio valido per ogni tipo Visual
Blocks v1 supportato. Viene verificato insieme allo schema e ai wire value dei
client da:

```powershell
.\scripts\verify-visual-blocks-contract.ps1
```

Questa cartella non contiene screenshot o baseline visuali: non dichiarare
controlli golden finche non esiste un test riproducibile che li generi e li
confronti davvero.
