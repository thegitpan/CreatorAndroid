# Verifica fisica sul Samsung

Stato: **da eseguire**. L'emulatore verifica controlli, salvataggi e media sintetici; non verifica sensori, microfono, sincronizzazione della ripresa, temperatura o comportamento del firmware Samsung. La regola deriva anche da [CAMERA-QA.md](../../docs/CAMERA-QA.md): «Synthetic simulator frames validate layout only».

## Intervento minimo richiesto a Fabrizio

Collegare il Samsung S26 via USB, mantenerlo sbloccato, attivare Debug USB e autorizzare questo computer sul telefono. Dopo la connessione, installazione, lettura della diagnostica e controlli automatici possono essere eseguiti dall'agente. Per confrontare camera reale e file salvato serviranno brevi riprese di una scena riconoscibile e un riferimento audio/visivo.

Non disinstallare l'app personale per aggiornarla. Prima di prove o pulizie su un'installazione già usata, conservare backup completi dei progetti, cronologia e originali. Le prove automatiche usano `Creator · test`, con ID distinto; i test strumentali possono rimuovere questa sola installazione.

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
| Aggiornamento | Installare sopra la versione personale precedente con la stessa firma e verificare inventario e originali |
| Durata | 30 minuti per l'alpha, poi 60 minuti per la beta: temperatura, memoria, frame e deriva audio/video |
| Accessibilità | Orientamenti, testo ingrandito, TalkBack, controlli raggiungibili e nessuna sovrapposizione con barre di sistema |

Il cambio camera è disponibile fra segmenti. Il confronto CameraX 1.6.2 / 1.7.0-rc02 resta un esperimento separato; non autorizza a presentare cambio continuo, HDR o 4K come funzioni già validate.

Per ogni prova registrare build, impostazioni, risultato, screenshot e file campione. Dopo una modifica rilevante, ripetere le prove fisiche interessate. Un test fallito mantiene aperta l'accettazione; una build successiva riuscita non la sostituisce.
