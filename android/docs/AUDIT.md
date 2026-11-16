# Audit dell'implementazione

Il coordinatore ha revisionato il lavoro dei tre autori Sol 6.1 Max, riprodotto i problemi e richiesto correzioni. Baseline precedente all'implementazione Android: `c7fc76e`; upstream `faf1815e`. I rilievi iniziali sotto sono chiusi; i controlli successivi su Linux e Samsung sono descritti nelle sezioni seguenti. L'accettazione della camera fisica resta aperta.

| ID | Rilievo | Correzione e verifica |
| --- | --- | --- |
| R1 | Export che potrebbe sostituire un file esistente o concorrente | Creazione esclusiva, nessuna apertura per sovrascrittura, pulizia solo del file posseduto; test JVM e Android, incluso file vuoto e race fra publisher. |
| R2 | Output validato soltanto tramite esistenza | Controlli di tracce, durata e geometria visualizzata; fixture strumentali e analisi indipendente del file salvato. |
| R3 | Media mancante provoca rollback della cronologia | Manifest valido conservato esattamente; avviso esplicito, senza ripristino di una vecchia modifica. Regression test archivio passato. |
| R4 | Modifica divergente obsoleta con timestamp più recente | Identità e ascendenza delle revisioni persistite; rifiuto verificato anche con orologio successivo. |
| R5 | Arresto dopo spostamento della sorgente perde l'aggiunta | Ricevute durevoli, replay una volta sola e recupero in app; test sui checkpoint di interruzione. |
| R6 | Impossibilità di salvare un avviso nasconde progetti validi | Avviso mantenuto in memoria e isolamento del progetto difettoso; test di scrittura negata. |
| R7 | Revisioni complete senza limite di ritenzione | Cinque revisioni valide conservate; originali e prove di corruzione preservati. |
| R8 | Errori asincroni del player o delle sorgenti possono terminare la UI | Risoluzione delle sole sorgenti attive e listener di errore visibile; prove dei flussi e input mancanti. |
| R9 | Avvio/quota del servizio o ricevuta fallita lascia job ambiguo | Stato terminale visibile e tipo foreground corretto per API; export, annullamento e retry pubblici su Android. |
| R10 | Export non collegato alla revisione montata | Ricevuta con job UUID e revisione congelata; risultato distinguibile dopo modifiche. |
| R11 | REC anticipato e Finalize interrotto gestito come successo generico | REC sull'evento Start; classificazione dei soli errori recuperabili, probe e frame decodificato prima dell'aggiunta. Test unitari e CameraX reale su emulatore. |
| R12 | Writer produce manifest oltre i limiti del reader | Limiti di complessità applicati prima della pubblicazione; il test conserva il progetto precedente leggibile. |
| R13 | Backup può sovrascrivere una destinazione creata durante la copia | Pubblicazione esclusiva con sync e pulizia conservativa; collisioni, concorrenza e backup/ripristino testati. |
| R14 | Back durante preparazione avvia una ripresa non desiderata | Annullamento della preparazione e controllo della navigazione, verificati nei flussi lifecycle. |
| R15 | Ricevute asincrone obsolete sovrascrivono lavori nuovi; notifica fallita scarta export valido | Aggiornamenti limitati a job e revisione correnti, terminali protetti; notifiche best effort separate dall'output. |
| R16 | Risultato picker perde il progetto destinatario dopo ricreazione | ID esplicito salvato in SavedStateHandle e ripristinato; test lifecycle passato. |
| R17 | Payload nascosto in directory ZIP aggira limiti di espansione | Auditor indipendente ha riprodotto 256 MiB compressi in una voce directory; payload respinto senza drenaggio illimitato. Test di regressione passato. |

L'audit indipendente Standards/Spec ha poi rilevato cinque ulteriori problemi, tutti corretti e ricontrollati: [REVIEW.md](REVIEW.md). La prima verifica integrata comprendeva 58 test JVM e 18 test su emulatori. Le suite sono state ampliate dopo i riscontri su Linux e Samsung; il conteggio aggiornato è in [EVIDENCE.md](EVIDENCE.md).

## Pubblicazione dei file su Android

L'emulatore API 36 rifiuta `Files.createLink` nell'archivio dell'app con un diniego SELinux. Il successo JVM dei hardlink non era sufficiente. Export e backup usano quindi creazione esclusiva (`CREATE_NEW` / `O_EXCL`), copia dal temporaneo completato e sincronizzazione del file posseduto prima del successo. Le destinazioni esistenti non vengono sostituite.

La CI Linux ha poi riprodotto il riuso di un inode dopo la chiusura dell'ultimo descrittore: il solo confronto della chiave del file non provava più la proprietà della destinazione. Il commit `0977242` mantiene aperta una lease del file fino a commit/rollback e disabilita la pulizia prima della chiusura dell'ultima lease. Su Android la lease usa `Os.dup`; il writer chiude esplicitamente anche il descrittore nativo, perché `FileOutputStream(FileDescriptor)` non ne assume la proprietà. La perdita di quel descrittore aveva nascosto il riuso dell'inode nei primi test Android.

Le regressioni verificano sostituzione della destinazione, riuso ripetuto dell'inode, doppia chiusura e conteggio dei descrittori. Sono passate su JVM Linux, emulatori e Samsung; il backup verifica anche 48 pubblicazioni consecutive senza crescita dei descrittori. Il revisore indipendente ha ricontrollato la chiusura. La garanzia riguarda le directory private controllate dall'app: confronto dell'identità e cancellazione non costituiscono una primitiva atomica contro un processo ostile che modifica arbitrariamente la directory.

Un arresto del processo durante la copia finale può lasciare un file privato incompleto. Non è dichiarato un export completato, non è pubblicato in galleria e non è condiviso. Il manifest del progetto usa invece sostituzione atomica e revisioni conservate.

## Limiti della chiusura

Il primo test Samsung ha trovato REC fuori dallo schermo; i test di layout e la revisione successiva hanno inoltre trovato il reset della frontale alla rotazione e finestre fra Finalize e commit nelle quali poteva entrare una nuova registrazione. Il recorder adattivo mantiene REC fisso, conserva la scelta frontale e serializza il lavoro fino al salvataggio durevole. Sono passate le regressioni su viewport Samsung, font 100/150/200%, rotazione durante REC, due tap ravvicinati su Pausa, cancellazione prima del dispatch e ViewModel già cancellato. Il revisore ha confermato la chiusura dopo l'ultimo helper di prenotazione. [Revisione](REVIEW.md), [evidenza rotazione](evidence/recorder-rotation.json).

Test ed audit non dimostrano assenza di ogni difetto. Il profilo posteriore del Samsung è stato misurato nella prima ripresa; qualità delle altre combinazioni, A/V delle riprese reali, mirroring, blocchi fuoco/esposizione, temperatura e sessioni lunghe restano aperti in [SAMSUNG-ACCEPTANCE.md](SAMSUNG-ACCEPTANCE.md). Le ultime correzioni recorder richiedono un nuovo controllo fisico. Le prove locali API 36/37 non certificano tutte le versioni Android ammesse da `minSdk`.
