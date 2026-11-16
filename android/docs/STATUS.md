# Stato della candidata personale 0.1.2

Aggiornato il 16 novembre 2026. La candidata C 0.1.2/code 3 interviene sul percorso dell’anteprima frontale non specchiata e separa la rotazione del display da quella del segmento registrato. **100 test locali e CI verdi; accettazione fisica ancora aperta.** Il Samsung è stato ricollegato e la candidata C è ora installata in-place: il checksum dell’APK effettivamente sul telefono coincide con `29d2ba0844b38fadc2ecf73979b05fe0596ff494648d295433378bc96ce44ada`. Tutti i 25 file sono identici subito dopo l’aggiornamento. La prima frontale in orizzontale con USB a destra è ora verificata: preview e file diritti e coerenti. La posteriore della stessa posa resta non conclusiva: una ripresa è stata rifiutata dall’utente e la ripetizione mostra una poltrona senza un riferimento affidabile per il verso. Il Samsung è ora scollegato; sono richieste una nuova scena con testo e le altre pose della matrice.

## Consegna

- Fork: <https://github.com/thegitpan/CreatorAndroid>, ramo `android/main`.
- Sorgente della candidata C: `7e019ae1e89e372ea47038253bedb652c78e8e96`.
- Consegna locale: `android/outputs/Creator-0.1.2/`, con APK test per aggiornare `it.panebarco.creator.debug` e APK personale separato `it.panebarco.creator`.
- Firme precedenti mantenute; chiave personale fuori dal repository. SHA-256, firme e stato esatto della CI sono in BUILD.json nella consegna.
- Ultimo backup completo verificato: **quattro progetti, 27 file, 193.169.693 byte**, dopo le prime due riprese della candidata C. I sei originali precedenti e i 13 file degli altri progetti sono identici. Le due ripetizioni successive sono estratte con hash verificati, ma il backup completo finale è ancora da acquisire. Nessuna disinstallazione o cancellazione di progetti effettuata.
- Nessuna release pubblica dell’APK o pubblicazione Google Play.

## Comportamento disponibile

Recorder a tutta schermata sul riferimento Samsung Video Pro: comandi compatti sovrapposti, REC/Pausa sempre visibili senza scorrere, cambio camera fra segmenti, zoom e blocchi AF/AE secondo capacità. Registrazione a segmenti con finalizzazione persistita, pausa/ripresa e continuazione dopo riapertura.

Editor sequenziale con miniature, waveform, scrub, split, trim, riordino, volume/muto e Undo/Redo persistenti. Formati 9:16/16:9, export MP4 H.264/AAC SDR, annullamento, galleria e condivisione. Originali immutabili, cestino ripristinabile, backup ZIP completo e recupero dei temporanei con avvisi. Camera e render pesanti si escludono prima dell’avvio; ricevute di export associate a lavoro e revisione.

## Verifiche della candidata C

| Verifica | Risultato locale |
| --- | --- |
| JVM: dominio, archivio, media, app | 9 + 36 + 19 + 7 = 71 passati |
| App Android API 36 arm64 | 14 passati |
| Media Android API 36 arm64 | 15 passati |
| Totale | **100 casi distinti, zero fallimenti/errori/esclusioni** |
| Test di gesto reale attraverso ViewGroup e factory CameraX | RED senza compensazione, poi 5/5 passati; quattro rotazioni e riuso fra camere |
| Build debug, release firmata, lint | Passati |
| Certificati precedenti e allineamento ZIP a 16 KiB | Verificati; 12 librerie native identiche alla 0.1.1 |
| Audit sorgente indipendente e del coordinatore | Nessun blocco residuo; verifica fisica richiesta |

La [CI della candidata C](https://github.com/thegitpan/CreatorAndroid/actions/runs/43934615846) è verde: build, JVM e lint su Linux, più 14 test app e 15 media su Android API 35 x86_64. Stato e risultati dettagliati sono nel [report della matrice fisica](evidence/samsung-camera-matrix.json). I 100 casi non sommano le ripetizioni su ambienti diversi.

## Evidenza fisica e limite corrente

La 0.1.1 ha superato una ripresa posteriore verticale: REC/Pausa visibili, segmento salvato una sola volta e immagine coerente con la preview. La successiva frontale verticale è risultata diritta. In orizzontale, invece, il sensore frontale ha mostrato preview capovolta di 180° con file salvato diritto. I log identificano camera e renderer effettivi; i nomi frontale/secondaria usati durante la prova non sostituiscono questa identificazione.

Le candidate A e B non sono state accettate: anche il passaggio a SurfaceView ha lasciato il difetto sul sensore frontale, pur con test e CI verdi. C usa il contratto nativo di mirroring frontale CameraX e una riflessione orizzontale del solo PreviewView. Il normale dispatch inverso del View gestisce i tocchi, evitando correzioni doppie. I controlli Compose e il file registrato non vengono riflessi. Dettagli in [RECORDER-UX.md](RECORDER-UX.md).

## Prossimo passaggio necessario

Backup completo, aggiornamento C e confronto degli originali sono ora riusciti sul Samsung ricollegato. Completare frontale e posteriore, in verticale e nei due orientamenti orizzontali, con scena riconoscibile e testo, confrontando preview e file salvato dopo l’ultima modifica. Controllare tocchi AF/AE e pannelli sovrapposti.

La matrice più ampia di [SAMSUNG-ACCEPTANCE.md](SAMSUNG-ACCEPTANCE.md), inclusi sincronizzazione audiovisiva reale, interruzioni e durata, resta distinta da questi test di rotazione. La candidata personale ha archivio separato e non è installata sul telefono. Titoli, musica separata, PiP, 4K/HDR e sottotitoli restano nelle fasi successive del [piano](PLAN.md).
