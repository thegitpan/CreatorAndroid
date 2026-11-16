# Stato della candidata personale 0.1.1

Aggiornato il 16 novembre 2026. La 0.1.1 sostituisce il recorder con una camera a tutta schermata: comandi sovrapposti, REC circolare bianco/rosso sempre visibile e nessuno scorrimento per registrare. Segue il riferimento Samsung Video Pro fornito dall'utente. Registrazione, montaggio ed export sono verificati con media sintetici ed emulatori. **Creator · test 0.1.1 è installata sul Samsung**: backup aggiornato verificato, tre progetti conservati e nuova UI controllata in verticale con anteprima reale. L’accettazione delle nuove riprese resta aperta. Requisiti, audit visivo, prove e limiti dell'aggiornamento sono in [RECORDER-UX.md](RECORDER-UX.md).

## Consegna

- Fork: <https://github.com/thegitpan/CreatorAndroid>, ramo predefinito `android/main`.
- Upstream conservato: `antirez/YouRecApp`, commit `faf1815e82e1f2d5f03034a583363d3c3af464a2`.
- App: **Creator 0.1.1**, versione interna 2, ID personale `it.panebarco.creator`.
- Consegna locale: `android/outputs/Creator-0.1.1/`, con APK personale e `Creator-test-0.1.1.apk` per aggiornare la stessa Creator · test già installata. SHA-256, firme, anteprime e metadati sono nella cartella, esclusa da Git.
- Firma personale stabile; chiave e credenziali fuori dal repository. La build di test usa l'ID separato `it.panebarco.creator.debug`.
- Nessuna pubblicazione su Google Play o release pubblica dell'APK effettuata.

## Funzioni implementate

Registrazione a segmenti, pausa/ripresa e continuazione dopo riapertura; cambio camera fra segmenti; controlli di zoom e fuoco/esposizione secondo capacità. Progetti, importazione, editor sequenziale con miniature e waveform, scrub, split al playhead, trim, riordino, volume/muto, Undo/Redo persistenti. Formati 9:16 e 16:9 con fit, anteprima ed export MP4 H.264/AAC SDR, annullamento, galleria e condivisione. Originali immutabili, cestino ripristinabile, backup ZIP completo e recupero delle operazioni interrotte con avvisi espliciti.

Camera e render pesanti si escludono prima dell'avvio. I temporanei conservano l'origine ripresa/importazione: recuperare un import non lo trasforma in una ripresa non annullabile. Le ricevute di export identificano lavoro e revisione del progetto.

## Verifiche completate

| Verifica finale | Risultato |
| --- | --- |
| Test JVM: dominio, archivio, media, app | 68 superati |
| Test app e media API 36 arm64, eseguiti in sequenza | 14 + 10 superati |
| Totale | **92 casi distinti superati, 0 errori, 0 fallimenti, 0 saltati** |
| Build debug, test APK, release firmata e lint | Superati |
| CI Linux e Android API 35 x86_64, commit `8b15315` | Entrambi i job superati; 14 test app e 10 media senza esclusioni |
| Audit indipendenti Standards e Spec | Rilievi iniziali e successivi ricontrollati; nessun blocco residuo nel codice candidato |
| Confronto indipendente sync su sei marcatori | Scarto A/V massimo 30,011 ms, soglia 33,334 ms |
| Aggiornamento personale 0.1.0 → 0.1.1, su emulatore | 15 file identici subito dopo l'installazione; gli 11 file dei progetti restano identici dopo l'avvio, due progetti visibili |
| Aggiornamento Samsung 0.1.0 → 0.1.1 | Backup completo di 17 file verificato contro il telefono; tutti identici subito dopo l’installazione; 15 file dei tre progetti invariati dopo l’avvio |
| Recorder Samsung in verticale, stato pronto | Anteprima posteriore reale e controlli sovrapposti visibili, REC abilitato senza scorrimento; confronto con nuova ripresa ancora da completare |
| Firma, allineamento APK/native library a 16 KiB | Verificati |

I risultati della 0.1.1 sono nel [report strutturato](evidence/recorder-pro.json). [EVIDENCE.md](EVIDENCE.md), [AUDIT.md](AUDIT.md) e [REVIEW.md](REVIEW.md) conservano le prove e gli audit della baseline 0.1.0. La pipeline GitHub esegue build su Linux e test API 35 x86_64; le ripetizioni su ambienti diversi non aumentano il conteggio dei 92 casi distinti.

## Decisioni finali rispetto al piano iniziale

- Tempo persistito in microsecondi interi `Long`, con conversioni e confini testati; non i 240.000 tick/s proposti inizialmente.
- Pausa finalizza un segmento; cambio camera consentito fra segmenti. Nessuna promessa di cambio continuo durante REC.
- CameraX 1.6.2 è la baseline; 1.7.0-rc02 rimane una variante diagnostica. Media3 1.11.1 serve anteprima ed export.
- Il preroll di decodifica evita il disallineamento dei tagli AAC. Per AAC-LC si usa un punto di avvio calcolato dai campioni; codec non riconosciuti usano prudentemente la decodifica dall'inizio, che può costare tempo su clip lunghe.
- Il recorder conserva la camera scelta alla rotazione. Una ricreazione durante REC finalizza il segmento e attende il salvataggio prima di consentire Riprendi; non riavvia automaticamente. REC/Pausa è sovrapposto all'immagine e non dipende da scorrimento o apertura dei pannelli.
- Pubblicazione degli output con creazione esclusiva, sincronizzazione e lease mantenuta fino a commit/rollback: i hardlink non funzionano nel percorso Android provato. Un arresto durante la copia finale può lasciare un file privato incompleto, mai dichiarato completato o condiviso.

## Prossimo passaggio necessario

Completare il confronto fra la nuova anteprima e una ripresa salvata secondo [SAMSUNG-ACCEPTANCE.md](SAMSUNG-ACCEPTANCE.md). L’aggiornamento di Creator · test è già riuscito: il nuovo backup comprende anche i progetti aggiunti dopo lo scollegamento precedente. La nuova app personale Creator ha archivio separato e non è ancora installata sul Samsung. Nessun progetto è stato cancellato. Il [report del ritorno del telefono](evidence/samsung-recorder-011.json) distingue installazione e UI verificate dalle prove di registrazione ancora aperte.

Il codice è stato scritto dagli agenti Sol 6.1 Max `domain_data`, `media_engine` e `app_ui`, revisionato da `audit_storage`, `audit_standards` e `audit_spec`, integrato e verificato dal coordinatore. Non risultano rilievi bloccanti aperti nel perimetro verificabile senza telefono. Titoli, musica separata, PiP, 4K/HDR e sottotitoli restano nelle fasi successive del [piano](PLAN.md).
