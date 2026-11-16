# Verifica fisica sul Samsung

Stato al 16 novembre 2026: **Creator · test aggiornata in-place alla 0.1.1/code 2**. Il telefono è tornato disponibile, con Debug USB autorizzato. Il backup completo aggiornato è verificato contro gli hash dei dati sul telefono: 17 file, 135.481.044 byte e tre progetti. Tutti i file sono identici subito dopo l’installazione; i 15 file dei progetti restano invariati dopo l’avvio, e i tre progetti sono visibili nella libreria. Nessuna disinstallazione o suite strumentale dell’app sull’archivio usato da Fabrizio. La nuova app personale `Creator` non è ancora installata.

Il nuovo recorder è stato aperto dal comando pubblico Continua: anteprima posteriore reale in verticale, comandi sovrapposti e REC abilitato interamente visibile senza scorrimento. Completata anche una nuova ripresa posteriore verticale 1080p SDR: REC e Pausa usati senza scorrere, stessa posizione del comando nei due stati, 4,8646 s salvati una sola volta. Il file H.264/AAC decodifica interamente e il fotogramma confrontato con la preview mostra testo e scena diritti, nessun evidente crop, deformazione o mirroring errato, e nessuna UI impressa nel filmato. La presenza di audio non costituisce una misura di sincronizzazione. Questa combinazione non certifica l’intera matrice hardware. Risultati in [samsung-recorder-011.json](evidence/samsung-recorder-011.json); archivio completo e schermate restano locali, esclusi da Git. La regola deriva anche da [CAMERA-QA.md](../../docs/CAMERA-QA.md): «Synthetic simulator frames validate layout only».

La candidata è **0.1.1/code 2**, con recorder a tutta schermata e comandi sovrapposti secondo [RECORDER-UX.md](RECORDER-UX.md). Proseguire le prove soltanto nell’archivio QA dedicato, conservando tutti i progetti e gli originali già presenti.

## Evidenza già raccolta

- Dispositivo effettivamente interrogato: Samsung **SM-S948B**, Android 17 / API 37, target dell'app 36, patch di sicurezza 5 ottobre 2026, pagine di memoria da 4 KiB. Nessun numero seriale o identificativo personale è pubblicato.
- Prima ripresa posteriore verticale: 31,986 s, H.264 High 1920×1080 con rotazione di visualizzazione verticale, circa 30,013 fps medi; AAC-LC 48 kHz stereo. Preview e fotogrammi salvati mostrano la scena riconoscibile correttamente orientata. L'originale e il progetto sono stati conservati integralmente sul Mac; il checksum corrisponde al manifest. Questo non misura ancora la sincronizzazione audiovisiva di un battito di mani.
- La prima prova ha riprodotto un difetto reale: REC era sotto la parte visibile della schermata. Il nuovo layout sovrappone il comando al video: ora è stato verificato visibile sul Samsung in stato pronto. Il confronto preview/file dopo l’ultima modifica è ora riuscito per la posteriore verticale a 1080p SDR; frontale e orizzontale restano da provare.
- Dieci test del motore multimediale passati sul Samsung con APK di test target 36, senza errori o test saltati. Sono fixture generate e isolate, non registrazioni dei sensori. Sei marcatori del file esportato, controllati anche con Python/FFmpeg sul Mac, hanno scarto A/V massimo **30,011 ms**, entro la soglia invariata di 33,334 ms. [Misura indipendente](evidence/samsung-synthetic-sync.json).
- Nuovo backup completo di `files` e `shared_prefs` acquisito e verificato prima dell’aggiornamento 0.1.1, poi confronto dei dati dopo installazione e avvio. Nessun progetto è stato cancellato o spostato per preparare il telefono.

Video reali, schermate e archivi completi restano in `.qa/evidence/samsung/`, esclusi da Git. I risultati seguenti restano aperti finché non è conservata la rispettiva evidenza dopo l'ultima modifica rilevante.

## Ripresa con recorder 0.1.1

La prova usa soltanto il progetto QA già dedicato ai test: aggiunti un originale e una revisione, nessuna cancellazione. I tre originali preesistenti sono identici al backup precedente; gli 11 file degli altri progetti sono invariati. Le vecchie sorgenti e le voci del registro QA restano identiche. Il nuovo backup completo, acquisito dopo la prova, è verificato contro gli hash sul telefono e comprende anche il segmento appena registrato. La camera è stata chiusa tramite il comando pubblico Monta. Audio AAC-LC 48 kHz stereo con campioni non nulli; nessuna affermazione sulla sincronizzazione reale senza un riferimento audiovisivo misurato.

## Intervento minimo richiesto a Fabrizio

L’autorizzazione Debug USB e l’aggiornamento sono già completati. Per le riprese occorre tenere il telefono sbloccato e posizionarlo rispetto a una scena riconoscibile. Il coordinatore gestisce installazione, diagnostica e controlli automatici. Per misurare la ripresa reale servono anche un riferimento audio/visivo visibile e le posizioni verticale/orizzontale.

Sul Samsung `Creator · test` è già aggiornata in-place con la stessa firma e con progetti e preferenze conservati. Non disinstallarla né eseguire la suite strumentale dell'app su questa installazione: contiene già la prima ripresa. La suite va eseguita soltanto su emulatori o dispositivi di test dedicati con dati sacrificabili. Anche l'app personale si aggiorna senza disinstallarla. Prima di prove o pulizie su un'installazione già usata, conservare backup completi dei progetti, cronologia e originali.

## Sequenza di accettazione

| Passaggio | Evidenza da conservare |
| --- | --- |
| Identificazione | Versione APK, checksum, modello, build Android, patch, versione CameraX, camere e codec dal report locale |
| Camera posteriore | Preview e segmento salvato con testo leggibile e riferimenti ai quattro bordi; controllare geometria, rotazione, mirroring e profilo effettivo |
| Camera frontale | Stessa scena e confronto, includendo ripresa verticale e orizzontale |
| Registrazione | A, Pausa, B, uscita, riapertura, C: tre segmenti salvati una sola volta, nessun tempo di pausa nel montaggio |
| Controlli | Zoom, tap fuoco/esposizione e blocchi esposti solo quando supportati; controllare l'effetto reale oltre all'indicatore |
| Sincronizzazione | Battito di mani o altro riferimento audiovisivo a inizio/fine e fra segmenti; misurare il file sorgente e l'export, obiettivo entro un fotogramma a 30 fps |
| Interruzioni | Cambio app, blocco schermo, chiamata, permesso revocato e processo interrotto; verificare ciò che è stato finalizzato e il recupero dei temporanei |
| Montaggio | Import, split, rifila, riordino, volume/muto, Undo/Redo dopo riapertura; hash originali invariati |
| Export e backup | Riprodurre l'MP4 nella Galleria Samsung e in un secondo player; annullare/riprovare; ripristinare un backup in un'installazione di test |
| Aggiornamento | Aggiornare Creator · test in-place con la stessa firma e verificare inventario e originali; installare Creator separatamente dopo le prove |
| Durata | 30 minuti per l'alpha, poi 60 minuti per la beta: temperatura, memoria, frame e deriva audio/video |
| Accessibilità | Orientamenti, testo ingrandito, TalkBack, controlli raggiungibili e nessuna sovrapposizione con barre di sistema |

Il cambio camera è disponibile fra segmenti. Il confronto CameraX 1.6.2 / 1.7.0-rc02 resta un esperimento separato; non autorizza a presentare cambio continuo, HDR o 4K come funzioni già validate.

Per ogni prova registrare build, impostazioni, risultato, screenshot e file campione. Dopo una modifica rilevante, ripetere le prove fisiche interessate. Un test fallito mantiene aperta l'accettazione; una build successiva riuscita non la sostituisce.
