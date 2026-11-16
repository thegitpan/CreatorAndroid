# Verifica fisica sul Samsung

Stato al 16 novembre 2026: **candidata C 0.1.2/code 3 installata e checksum dell’APK effettivo verificato**. Dopo il ricollegamento è stato acquisito un nuovo backup completo; tutti i 25 file, appartenenti ai quattro progetti e alle preferenze, sono identici immediatamente dopo l’aggiornamento in-place. La candidata B è stata sostituita. La prima frontale orizzontale con USB a destra è verificata su preview e file salvato, dopo l’ultima modifica del sorgente. La prima posteriore ha una scena scura e non viene accettata come prova di orientamento. L’utente ha rifiutato una ripetizione; la nuova clip mostra una poltrona senza testo o un verso affidabile. Le clip sono conservate e nessuna delle tre posteriori certifica l’orientamento. Il Samsung è ora scollegato, in attesa di una nuova prova con testo. La matrice delle altre pose resta in corso. La nuova app personale Creator non è installata.

Prima dei due aggiornamenti precedenti sono stati verificati backup completi e dati identici immediatamente dopo l’installazione. L’ultimo backup valido comprende **quattro progetti, 27 file, 193.169.693 byte**, dopo le prime due riprese C. I sei originali precedenti e i 13 file degli altri progetti sono identici al confronto. Le ripetizioni successive sono estratte con hash verificati ma non ancora incluse in un nuovo backup completo. Tutti gli originali precedenti sono stati conservati; nessuna suite strumentale dell’app è stata eseguita sul Samsung con i suoi progetti.

La 0.1.1 posteriore verticale e la frontale verticale sono prove storiche riuscite. Il confronto della frontale orizzontale ha riprodotto preview capovolta e MP4 diritto. La candidata C mantiene lo stream Preview nel contratto nativo CameraX e riflette soltanto la presentazione del View per mostrare la stessa scena non specchiata del file; il gesto attraversa il normale dispatch inverso Android. Le cinque regressioni di gesto passano, ma non certificano il sensore fisico. [Report corrente](evidence/samsung-camera-matrix.json), [prove precedenti](evidence/samsung-recorder-011.json), [requisito UI](RECORDER-UX.md).

Il nuovo backup e l’installazione C con checksum verificato sono completati. Ripetere ora la matrice camera dopo l’ultima modifica del sorgente. Usare soltanto il progetto QA dedicato; non registrare o cancellare contenuti negli altri progetti. Identificare la frontale come sensore sul lato dello schermo e la posteriore come sensore sul lato degli obiettivi; conservare Camera ID e lens facing effettivi insieme ai campioni.

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

L’autorizzazione Debug USB è già concessa. Occorre ricollegare il telefono, sbloccarlo e posizionarlo rispetto a una scena riconoscibile con testo; la candidata C è già installata e non richiede un altro aggiornamento. Il coordinatore gestisce installazione, diagnostica e controlli automatici. Per misurare la ripresa reale servono anche un riferimento audio/visivo visibile e le posizioni verticale/orizzontale.

Sul Samsung `Creator · test` è già aggiornata in-place alla candidata C, con la stessa firma e con progetti e preferenze conservati. Non disinstallarla né eseguire la suite strumentale dell'app su questa installazione: contiene già la prima ripresa. La suite va eseguita soltanto su emulatori o dispositivi di test dedicati con dati sacrificabili. Anche l'app personale si aggiorna senza disinstallarla. Prima di prove o pulizie su un'installazione già usata, conservare backup completi dei progetti, cronologia e originali.

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
