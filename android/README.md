# Creator per Android

Applicazione Android indipendente ispirata al flusso di registrazione e montaggio di [YouRec](https://github.com/antirez/YouRecApp). Mantiene nel fork il codice iOS e la sua licenza; l'app Android usa Kotlin, Compose, CameraX e Media3. Nome e icona sono distinti dall'app originale.

La candidata **0.1.2** mantiene il recorder sul riferimento Samsung Video Pro: anteprima a tutta schermata, comandi sovrapposti e REC sempre raggiungibile senza scorrere. Interviene sul percorso dell’anteprima frontale non specchiata; 100 test locali passati. **La verifica fisica della candidata finale resta aperta:** la candidata C è ora installata in-place sul Samsung, con checksum dell’APK e originali verificati; la matrice preview/file dopo l’ultima modifica è in corso. Le versioni precedenti hanno riprodotto l’inversione della preview frontale in orizzontale, pur con file salvato diritto. Stato esatto in [STATUS.md](docs/STATUS.md), requisito e diagnosi in [RECORDER-UX.md](docs/RECORDER-UX.md), [matrice fisica](docs/evidence/samsung-camera-matrix.json).

## Flusso disponibile

- Creare un progetto, registrare segmenti e continuare in seguito; cambiare camera fra segmenti.
- Importare video, selezionare clip, dividere, rifilare, spostare, eliminare, regolare il volume e annullare/ripristinare le modifiche.
- Usare un formato verticale 9:16 o orizzontale 16:9, con anteprima, miniature e forma d'onda audio.
- Esportare MP4 H.264/AAC SDR, annullare il lavoro, salvare in galleria e condividere il risultato.
- Conservare originali e cronologia, spostare progetti nel cestino, ripristinarli e creare/importare backup ZIP.

L'elaborazione è locale. L'app non richiede account e non dichiara il permesso Internet. Il selettore di Android può recuperare un file remoto tramite il relativo provider. La disinstallazione rimuove i progetti privati: il backup del progetto conserva anche montaggio e originali, mentre l'MP4 esportato contiene il solo risultato.

## Compilazione

Servono JDK 17 e Android SDK 36. Impostare `ANDROID_HOME` oppure `sdk.dir` nel file locale, ignorato da Git, `local.properties`. Dalla cartella `android`:

```sh
./gradlew :domain:test :data:test :media:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

La build debug si chiama **Creator · test** e usa `it.panebarco.creator.debug`. L'identità personale stabile è `it.panebarco.creator`. Le due installazioni hanno archivi separati: i test strumentali possono installare/disinstallare la build di test senza rimuovere quella personale.

Per i test strumentali serve anche `ffmpeg`. I campioni sintetici vengono generati automaticamente sotto `build/`, senza filmati personali o file binari versionati:

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew -Pandroid.injected.device.serial=emulator-5580 :app:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5580 ./gradlew -Pandroid.injected.device.serial=emulator-5580 :media:connectedDebugAndroidTest
```

Sostituire il seriale con quello del dispositivo di test dedicato. Eseguire i test dell'app e del motore in sequenza sullo stesso emulatore, avviato con `-camera-front emulated -camera-back emulated` per verificare entrambe le camere. La suite dell'app modifica temporaneamente risoluzione, densità e dimensione del testo per le regressioni di layout e le ripristina al termine: non eseguirla sul telefono personale con progetti da conservare. I report sono nelle rispettive cartelle `build/reports/`.

## APK personale e firma

La chiave privata resta fuori dal repository. La variabile `CREATOR_SIGNING_PROPERTIES` indica un file Java properties privato con `storeFile`, `storePassword`, `keyAlias` e `keyPassword`:

```sh
CREATOR_SIGNING_PROPERTIES=/percorso/privato/release.properties ./gradlew :app:assembleRelease
```

Conservare chiave e credenziali per poter aggiornare la stessa installazione. Senza configurazione di firma, la build release è unsigned; non viene firmata automaticamente con la chiave debug. Nessuna credenziale deve essere inserita in Git.

L'APK prodotto si trova in `app/build/outputs/apk/release/`. L'installazione e gli aggiornamenti via ADB usano `adb -s SERIALE install -r PERCORSO_APK`, senza disinstallare la versione personale precedente.

## Struttura e limiti

| Modulo | Responsabilità |
| --- | --- |
| `domain` | Timeline, coordinate temporali, operazioni, cronologia e registro delle riprese |
| `data` | Originali, salvataggi, revisione dei progetti, recupero, cestino e backup |
| `media` | Camera, metadati, miniature, audio, anteprima ed esportazione |
| `app` | Interfaccia, permessi, selettori Android, notifiche e servizio di esportazione |

La pausa finalizza un segmento; Riprendi ne crea un altro. Il cambio camera durante REC, 4K/HDR, PiP, titoli, musica separata e sottotitoli non appartengono alla prima consegna personale. Una ripresa interrotta prima della finalizzazione può essere incompleta: l'app conserva il file e propone il recupero, ma non garantisce che un MP4 non finalizzato sia leggibile.

Il piano completo è in [PLAN.md](docs/PLAN.md), le interfacce condivise in [CONTRACT.md](docs/CONTRACT.md) e la provenienza in [UPSTREAM.md](../UPSTREAM.md). La 0.1.1 è verificata con CameraX 1.6.2. La variante diagnostica `-PcameraXVersion=1.7.0-rc02` ha superato la sola compilazione della precedente 0.1.0; non eredita le verifiche runtime della baseline. Le dipendenze sono fissate nei file Gradle e descritte in [DEPENDENCIES.md](docs/DEPENDENCIES.md).
