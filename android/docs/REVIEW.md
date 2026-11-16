# Revisione indipendente della prima implementazione

Baseline documentale `c7fc76e`; prima implementazione `29d0f15`, con le successive correzioni incluse nella revisione finale. Due revisori Sol 6.1 Max hanno analizzato separatamente regole del repository e requisiti. Il coordinatore ha verificato e assegnato i rilievi agli autori; i revisori hanno ricontrollato correzioni e risultati. L'accettazione fisica Samsung resta distinta.

## Standards: 1 rilievo, chiuso

**P2 — Errori tecnici mostrati direttamente all'utente.** Alcuni percorsi inoltravano `Exception.message`, mostrando messaggi SDK in inglese e percorsi interni. `AppErrors` traduce gli errori attesi per tipo e operazione; soltanto le validazioni di dominio esplicitamente riconosciute conservano il proprio messaggio. I quattro test mirati passano nella suite finale. Il revisore ha confermato la chiusura senza altri rilievi sostanziali sulle regole del repository o sulle euristiche di manutenibilità.

## Spec: 4 rilievi, chiusi

| Priorità | Problema | Correzione e prova di chiusura |
| --- | --- | --- |
| P1 | Sync export oltre un fotogramma dopo tagli non allineati ai keyframe: fino a circa 42 ms | Preroll del decoder, selezione PCM sui timestamp dei campioni e riallineamento video. Sei marcatori misurati indipendentemente: massimo 30,011 ms entro 33,334 ms. Il controllo negativo con ritardo aggiunto fallisce correttamente; preview PCM e seek verificati. |
| P2 | Registrazione concorrente con un export pesante | Prenotazione sincrona della risorsa, esclusione camera/export anche durante l'avvio asincrono, rilascio dopo finalizzazione. Test unitario e flussi pubblici passati. |
| P2 | Importazione interrotta recuperata come ripresa non annullabile | Origine import/ripresa persistita prima di esporre il temporaneo. Recupero con origine sconosciuta come import annullabile. Test di riavvio, interruzioni e metadati malformati passati. |
| P2 | Split dipendente dalla clip selezionata in precedenza | La clip bersaglio deriva dal playhead. Il test pubblico seleziona A, sposta il playhead in B, divide B e annulla correttamente. |

Il revisore Spec ha confermato la chiusura di tutti e quattro i rilievi e non ha identificato nuovi problemi bloccanti o estensioni di perimetro non richieste.

## Revisione successiva a Linux e alla prima prova Samsung

La CI Linux ha riprodotto il riuso dell'inode dopo la chiusura dell'ultimo descrittore. La correzione mantiene una lease fino alla decisione di commit/rollback; la successiva revisione Android ha individuato e chiuso anche il descrittore nativo non posseduto dal wrapper Java. Riletti dagli auditor, questi percorsi sono coperti dai test descritti in [AUDIT.md](AUDIT.md).

Il coordinatore ha poi riprodotto REC sotto il viewport sul Samsung. La regressione usa le sue dimensioni reali nell'emulatore; REC e stato audio sono ora separati dai controlli scorrevoli. Il revisore Spec ha segnalato il doppio tap durante Finalize e il rischio di un footer compresso in landscape/font 200%; entrambi sono stati riprodotti o verificati e corretti. Root e revisore media hanno inoltre identificato il reset frontale dopo rotazione e la possibilità di una nuova ripresa prima del commit precedente.

La candidata conserva la camera scelta e mantiene una prenotazione di registrazione attraverso staging, CameraX, finalizzazione e commit. I completion handler rilasciano il token anche quando la coroutine non parte o il ViewModel è già cancellato. La nuova Activity attende il lavoro precedente e non riavvia REC automaticamente. Il revisore Spec ha ricontrollato helper e chiamante finali senza ulteriori blocchi. La suite completa finale passa con 14 test app su emulatore e 7 test JVM app; [evidenza rotazione/ordine/hash](evidence/recorder-rotation.json).

## Verifica finale e limiti aggiornati

La prima verifica integrata comprendeva 76 test superati. Dopo le regressioni Linux e Samsung, la candidata raggiunge **92 casi distinti superati**, build debug/release firmata e lint. Il conteggio e gli ambienti sono in [EVIDENCE.md](EVIDENCE.md) e [evidence/final-test-summary.json](evidence/final-test-summary.json).

Il margine del test sintetico A/V rispetto alla soglia è di circa 3 ms: non dimostra sincronizzazione perfetta né certifica i codec Samsung. L'ottimizzazione AAC-LC ha ridotto il test di un taglio tardivo a 1,171 s; per codec sconosciuti resta il percorso prudente dall'inizio, potenzialmente più lento. Sensori, microfono, mirroring, blocchi camera e sessioni lunghe richiedono [accettazione fisica](SAMSUNG-ACCEPTANCE.md).
