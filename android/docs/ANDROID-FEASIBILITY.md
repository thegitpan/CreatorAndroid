# Fattibilità tecnica Android per il porting di YouRec

Verifica documentale: **16 novembre 2026**. Destinazione iniziale: uso personale sul Samsung S26 dell'utente. Questa ricerca riguarda le API Android; **non è una certificazione delle capacità del telefono**, sul quale non sono state eseguite prove. Le indicazioni marcate «Proposta» sono scelte progettuali da validare.

## Versioni da verificare nel primo esperimento

**Fatto verificato.** Le tabelle ufficiali indicano CameraX **1.6.2** stabile e **1.7.0-rc02** candidata. Le note 1.7.0-alpha03 riportano correzioni per pausa/ripresa durante cambio camera persistente e registrazione HDR sui Galaxy S25/S26/Fold7. L'aggiornamento dinamico `ConcurrentCamera.setCompositionSettings` e la scelta esplicita dei MIME sono aggiunte del ramo 1.7; non vanno attribuite automaticamente alla 1.6.2. La rc02 corregge inoltre una perdita di thread durante unbind di `VideoCapture`. [Release CameraX](https://developer.android.com/jetpack/androidx/releases/camera)

**Fatto verificato.** La versione Media3 stabile riportata è **1.11.1**. [Release Media3](https://developer.android.com/jetpack/androidx/releases/media3)

**Proposta.** Confrontare CameraX 1.6.2 e 1.7.0-rc02 in un APK diagnostico isolato, soprattutto sulle funzioni interessate dalle correzioni. La scelta iniziale va bloccata dopo questa prova; la documentazione API corrente può includere funzioni non presenti nell'artefatto stabile. Non introdurre una prerelease nell'app principale senza evidenza del beneficio e regressioni controllate.

## Registrazione e cambio fotocamera

**Fatto verificato.** CameraX espone registrazione con audio, pausa, ripresa e arresto. Risoluzioni, gamma dinamica e stabilizzazione vanno interrogate sul dispositivo. Il nome commerciale del telefono non determina quali combinazioni siano utilizzabili dall'app. Per HDR si interroga `Recorder.getVideoCapabilities(cameraInfo)`, poi le gamme e le qualità supportate per quella gamma. [CameraX video capture](https://developer.android.com/media/camera/camerax/video-capture)

**Fatto verificato.** `PendingRecording.asPersistentRecording()` è annotato `@ExperimentalPersistentRecording`. La documentazione descrive il cambio camera tramite unbind/rebind dello stesso `VideoCapture` mantenendo il recording e dichiara che l'audio continua quando il use case video è scollegato. Durante l'assenza della camera mancano nuovi dati video; una nuova combinazione di superfici incompatibile può far fallire il bind. Un recording persistente non mantiene automaticamente la camera attiva quando il lifecycle passa in background. [PendingRecording](https://developer.android.com/reference/androidx/camera/video/PendingRecording)

**Proposta.** Iniziare con CameraX, 1080p/30 fps SDR, camera anteriore/posteriore selezionabile, pausa/ripresa e più riprese nello stesso progetto. Verificare presto il percorso persistente prima di progettare un motore Camera2/audio autonomo. Trattare continuità dell'audio, durata del fermo immagine, orientamento e sincronizzazione come criteri misurati, non come proprietà garantite dal nome dell'API. Se il cambio in corsa fallisce, offrire cambio mentre si è in pausa o una nuova ripresa nello stesso progetto; un eventuale motore custom costituisce una fase aggiuntiva.

## Due camere e qualità effettiva

**Fatto verificato.** `ProcessCameraProvider.availableConcurrentCameraInfos` elenca le combinazioni simultanee supportate. CameraX distingue flussi separati da modalità di composizione: usando le stesse istanze preview/video e `CompositionSettings` può registrare un unico flusso composto e impostare la posizione dei due contributi. Questa modalità supporta preview/video, ma non `ImageCapture`; ignora il `mirrorMode` di `VideoCapture` e il risultato segue il mirroring della preview. Le combinazioni non presenti nell'elenco possono produrre eccezioni. [ProcessCameraProvider](https://developer.android.com/reference/androidx/camera/lifecycle/ProcessCameraProvider)

**Conseguenza progettuale.** Una registrazione PiP già composta non conserva automaticamente due sorgenti indipendenti con cui cambiare il layout in montaggio. Il layout rieditabile richiede un percorso distinto, da provare per carico, sincronizzazione e spazio.

**Proposta.** La doppia camera è una fase successiva: prima inventario sullo specifico S26, poi preview e file salvato. Accettare un profilo diverso dalla camera singola, mostrando quello realmente attivo. Non promettere 4K, HDR, 60 fps e doppia camera contemporaneamente.

## Editing, preview ed esportazione

**Fatto verificato.** Media3 usa `Composition`, `EditedMediaItemSequence` e `EditedMediaItem` per combinare video, immagini e audio. Sono descritti montaggio sequenziale, video sovrapposti, musica di fondo ed effetti per clip o composizione. [Composition](https://developer.android.com/media/media3/transformer/composition)

**Fatto verificato.** `CompositionPlayer` riproduce una `Composition` che può poi essere esportata con `Transformer`, ma la documentazione corrente lo indica ancora come early preview con `@ExperimentalApi`. Elenca come supportati una singola sequenza e una sequenza video più una audio; il multi-video/PiP è in sviluppo. Chiede durate esplicite degli elementi e accesso da un singolo thread applicativo. [CompositionPlayer](https://developer.android.com/media/media3/transformer/compositionplayer)

**Fatto verificato.** Media3 offre trimming, crop, scala/rotazione, overlay di testo/immagini e catene di `AudioProcessor` sul PCM. Queste primitive permettono un editor essenziale, ma non costituiscono l'interfaccia di una timeline pronta. [Guida ufficiale per un editor](https://developer.android.com/media/implement/editing-app)

**Proposta.** Tenere il formato persistente del progetto indipendente dalle classi Media3. Un adattatore traduce il modello in `Composition` per preview/export. Bloccare le versioni delle dipendenze e isolare le API sperimentali. Prima versione: una sequenza video, audio collegato, tagli netti, trim, riordino e volume. Prima di aggiungere multi-traccia, transizioni o velocità, verificare gli stessi risultati audiovisivi in preview ed export con la versione scelta. Non assumere che una dissolvenza fra due clip sia un'operazione già pronta solo perché esiste il compositor.

## HDR, Log e codec

**Fatto verificato.** Transformer usa `MediaCodec` per l'encoding; il supporto dipende dai codec disponibili. Produce normalmente MP4 e consente altri muxer. L'editing HDR richiede Android 13+ e capacità di encoding adeguate; gli effetti GL devono gestire BT.2020 e componenti floating point a 16 bit. Sono previsti tone mapping HDR→SDR e fallback. [Formati supportati da Transformer](https://developer.android.com/media/media3/transformer/supported-formats)

**Proposta.** Un unico percorso SDR ben verificato per la prima release. Per import HDR: tone mapping dichiarato e confronto con un riferimento, oppure messaggio esplicito se la combinazione non funziona. HDR di acquisizione e mantenimento HDR in esportazione sono due gate distinti. Non confondere un filtro visivo con una vera acquisizione Log. L'accesso pubblico a eventuali funzioni Samsung Log sul modello e firmware dell'utente **non è stato stabilito** da questa ricerca; Apple Log non è una specifica da trasferire automaticamente.

## Permessi e lavoro in background

**Fatto verificato.** Da Android 14 i foreground service richiedono tipo e permesso appropriati. I tipi `camera` e `microphone` richiedono rispettivamente i permessi runtime `CAMERA` e `RECORD_AUDIO`; l'avvio da background è soggetto a limitazioni while-in-use. `mediaProcessing` è il tipo descritto per conversioni lunghe: normalmente dispone di sei ore su ventiquattro condivise tra i servizi di quel tipo, e deve gestire fine, errore e timeout. [Tipi di foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types)

**Fatto verificato.** WorkManager può eseguire worker lunghi tramite foreground service, ma da Android 16 questi possono consumare la quota JobScheduler; la documentazione propone, quando appropriato, l'avvio diretto del foreground service. [Long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

**Proposta.** Registrazione iniziale mentre la schermata è visibile, con arresto/finalizzazione espliciti nelle transizioni previste. Registrazione a schermo bloccato solo come requisito separato con service/lifecycle dedicato. Export avviato dall'utente, notifica con avanzamento e annullamento, gestione delle API/limiti della versione Android. WorkManager per attività differibili o retry coerenti, non come promessa che un render sopravviverà senza limiti. Salvare uno stato di export che distingua successo, annullamento, errore e interruzione; il riavvio del render è accettabile nella prima versione.

## File, progetti e recupero

**Fatto verificato.** Photo Picker concede accesso ai media scelti senza aprire l'intera libreria; il contratto AndroidX può ricadere su `ACTION_OPEN_DOCUMENT`. Una selezione può anche provenire da un provider cloud. [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)

**Fatto verificato.** `MediaStore.IS_PENDING` consente di tenere un media nascosto alle altre app mentre viene scritto, pubblicandolo alla fine. I media nello storage condiviso restano anche dopo disinstallazione; per documenti e archivi di progetto è appropriato lo Storage Access Framework. [Shared media storage](https://developer.android.com/training/data-storage/shared/media)

**Fatto verificato.** `VideoRecordEvent.Finalize` distingue successo e più classi di errore. Alcuni errori lasciano un file utilizzabile, altri un output malformato o assente. Ricevere un errore non implica sempre perdita, ma neppure la sola presenza del file prova che sia riproducibile. [Finalize](https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize)

**Fatto verificato.** CameraX 1.6 integra il muxer Media3 e le note dichiarano miglioramenti contro corruzione in caso di crash/interruzione. Questo giustifica test di recupero sullo stack scelto, anziché presumere che ogni MP4 interrotto sia perso. [Release CameraX 1.6](https://developer.android.com/jetpack/androidx/releases/camera#1.6.0)

**Proposta.** Copiare i media importati nel progetto in modo transazionale, con controllo spazio/cancellazione, preservando gli originali. Salvare manifest versionati e cronologia con commit atomici. Registrare un nuovo segmento soltanto dopo finalizzazione e verifica dei metadati. Conservare il registro delle operazioni in corso e riconciliare i residui all'avvio. Garantire recupero dei progetti già salvati e delle riprese finalizzate; **non promettere il recupero integrale di un MP4 ancora aperto dopo un kill del processo**. Verificare separatamente eventuale recupero o registrazione frammentata. Pubblicare l'export in galleria solo dopo il completamento verificato. Import/export degli archivi deve testare checksum e ripristino effettivo.

**Distinzione per il piano.** Un pulsante Pausa può chiudere e verificare un segmento, poi creare un segmento nuovo alla ripresa: è un checkpoint del progetto. La sola chiamata `Recording.pause()` sospende il recording corrente e non equivale, da sé, a un segmento finalizzato. Il primo esperimento deve misurare quanta parte dell'ultima ripresa resta leggibile dopo interruzione forzata.

## Installazione personale e distribuzione

**Fatto verificato.** Google dichiara che le installazioni tramite ADB per sviluppo/test restano utilizzabili senza registrazione dell'app né verifica sviluppatore; il periodo di attesa dell'advanced flow non si applica ad ADB. [Developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)

**Fatto verificato.** La pagina del programma indica un'introduzione delle protezioni dal 30 settembre 2026 in regioni selezionate, con estensione globale nel 2027. Le regole di distribuzione pubblica e i percorsi di verifica vanno ricontrollati al rilascio. [Programma Android developer verification](https://developer.android.com/developer-verification)

**Proposta.** Primo circuito: APK di sviluppo installato via Android Studio/ADB sul telefono autorizzato. Poi APK firmato con identità stabile, aggiornabile mantenendo i progetti. Play Store o distribuzione pubblica richiedono una fase distinta per account, firma, policy e verifiche vigenti. Non serve questa fase per iniziare i test personali via ADB.

## Prove che sbloccano il piano

Tutti i seguenti sono **criteri proposti**, non risultati già ottenuti:

1. Rilevare modello preciso, versione Android/One UI, camere, profili, codec e combinazioni. Registrare ciò che è richiesto e ciò che è negoziato realmente.
2. Acquisizione SDR: controllare focus, zoom, preview/file, mirroring, orientamento, pausa/ripresa, clip brevi e sessioni di 30–60 minuti. Ripetere cambio camera con segnale audio/visivo di riferimento; misurare gap, drift e blocchi video.
3. Audio: microfono interno, eventuale USB dell'utente, disconnessione e recupero. La selezione di un dispositivo fisico è un requisito da provare, non deducibile dall'esistenza di un ingresso Android.
4. Montaggio: tagli all'inizio/fine, reorder, input con orientamenti e frame rate diversi, undo/redo dopo riapertura; file originali identici prima e dopo.
5. Export: confrontare durata, confini dei tagli, framing, testo, audio e colore fra preview e MP4; verificare con un secondo player. Ripetere dopo annullamento, storage quasi pieno, cambio app e interruzione del processo.
6. Ripristino: progetti precedenti intatti dopo errore; aggiornamento APK conserva i dati; archivio esportato si importa in una nuova installazione di test. Nessuna pulizia dei progetti reali dell'utente durante i test.

Non esiste in questa ricerca evidenza sul comportamento effettivo del Samsung S26 dell'utente. Le API individuate rendono plausibile il progetto e permettono esperimenti mirati; il primo APK diagnostico deve risolvere le incertezze hardware prima di fissare le funzioni avanzate.
