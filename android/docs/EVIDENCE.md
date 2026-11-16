# Verifiche della consegna personale 0.1.0

Data: 16 novembre 2026. Ambito: codice Android e correzioni successive alla prima implementazione `29d0f15`. Emulatori dedicati API 36 arm64 e Samsung SM-S948B con Android 17 / API 37; JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, target SDK 36, CameraX 1.6.2 e Media3 1.11.1. Sul telefono i test automatici del motore usano soltanto campioni sintetici e un pacchetto isolato. La ripresa fisica e la sua accettazione sono descritte separatamente.

## Suite finale

| Ambiente | Modulo | Passati | Falliti / errori / saltati |
| --- | --- | --- | --- |
| JVM | domain | 9 | 0 / 0 / 0 |
| JVM | data | 36 | 0 / 0 / 0 |
| JVM | media | 16 | 0 / 0 / 0 |
| JVM | app | 7 | 0 / 0 / 0 |
| API 35 arm64, target 36 | media, fixture sintetiche | 10 | 0 / 0 / 0 |
| API 36 arm64 | app | 14 | 0 / 0 / 0 |
| **Totale** | | **92** | **0 / 0 / 0** |

[Riepilogo estratto dai report XML](evidence/final-test-summary.json). Le suite interessate sono state eseguite dopo l'ultima modifica del rispettivo modulo. I 10 test media sono passati anche sull'emulatore API 36; ripetizioni ed esecuzioni CI non sono sommate al totale dei casi distinti. Gli aggiornamenti documentali non modificano l'APK. Build debug, APK strumentali, release firmata e lint sono passati. La variante diagnostica CameraX 1.7.0-rc02 ha superato la sola compilazione debug; non eredita i risultati runtime della baseline.

I test coprono i flussi pubblici di registrazione A/Pausa/B, uscita/riapertura/C, creazione/rinomina/cestino/ripristino, import, split al playhead, trim, riordino, volume, Undo/Redo, export, annullamento/retry, backup e ripristino, galleria e URI di condivisione. I selettori esterni e il chooser sono intercettati dove necessario; vengono provati stream reali e concessioni URI, senza inviare media a destinatari esterni. La permanenza del destinatario del picker è verificata tramite stato ripristinato; non equivale a provare ogni provider o ogni interruzione possibile del sistema.

L'archivio è verificato contro corruzione, schema futuro, media mancanti, revisioni concorrenti, errori di pubblicazione, replay delle ricevute, backup ostili e recupero distinto di importazioni/riprese. SHA-256 degli originali controllati dopo montaggio ed export. Le nuove regressioni coprono riuso degli inode e lease dei file, chiusura dei descrittori nativi, frontale dopo rotazione, segmento finalizzato una sola volta, due tap su Pausa prima del nuovo frame UI e rilascio della prenotazione anche con coroutine o ViewModel già cancellati.

## Correzione dell'ambiente di test API 35

La CI della candidata ha inizialmente trovato un timeout nel solo test PCM. Il caso è stato riprodotto isolatamente su API 35 arm64: il sistema rifiutava l'audio focus all'instrumentation senza Activity visibile e il player restava pronto, fermo al tempo zero. Per target 35 o successivi, Android richiede app in primo piano o foreground service per concedere il focus. [Documentazione Android](https://developer.android.com/about/versions/15/behavior-changes-15)

Il test ora apre una Activity dedicata nel solo APK androidTest, attende RESUMED e WindowFocus e la chiude dopo il player. Motore di produzione, AudioTrack reale, timeout di 30 s e tolleranza di 33.334 µs restano invariati. Prova mirata rossa prima e verde dopo; suite completa 10/10 verde, massimo errore PCM 26.007 µs e seek verificato. [Evidenza](evidence/preview-focus-api35.json). Il workflow usa inoltre due invocazioni Gradle sequenziali per app e media. La conferma Linux x86_64 è registrata nel riepilogo delle prove.

## Media e sincronizzazione

- Composizione di input misti: tagli fuori keyframe, video ruotato, segmento senza audio, volume ridotto e muto. Il file risultante è H.264/AAC, geometria visualizzata 1080×1920 e durata video 5.133.333 µs rispetto a 5.133.334 µs attesi. La traccia AAC include padding finale del codec; la sola durata del contenitore non è stata usata come misura di sync. Il rapporto di ampiezza misurato è circa 0,25235 per un gain richiesto di 0,25.
- Sul Samsung tutti i 10 test del motore sono passati con target 36. Il controllo indipendente dell'export sintetico ottenuto sul telefono misura ancora 30–30,011 ms su sei marcatori: [risultati Samsung](evidence/samsung-synthetic-sync.json). Questo misura il montaggio/export, non il sensore e il microfono.
- Sei marcatori audio/video sono stati analizzati sul file decodificato con un checker Python/FFmpeg indipendente dall'implementazione. Il problema iniziale raggiungeva circa 42 ms; dopo la correzione lo scarto A/V è fra 30 e 30,011 ms, entro la soglia fissata di 33,334 ms. La soglia non è stata allargata. [Prima](evidence/sync-baseline.json), [dopo](evidence/sync-fixed.json).
- Un ritardo audio aggiunto intenzionalmente di 50 ms viene rifiutato dal checker: [controllo negativo](evidence/sync-negative-control.json). La fixture sorgente calibrata ha scarto zero nel medesimo controllo.
- La preview è stata misurata sui campioni PCM realmente inoltrati all'AudioSink, anche dopo seek. Gli errori rispetto ai marcatori attesi sono circa 16–26 ms; differenza preview/export entro 14 ms. Non è una misura della latenza acustica dell'altoparlante. [Misure preview](evidence/preview-pcm.json).
- Taglio di 1,5 s vicino alla fine di un input da 60 s: avvio decodifica a 57 s per un in-point a 58,35 s, export in 1,171 s sull'emulatore della prova; cancellazione durante preroll in 73 ms. Sono misure di questo ambiente, non benchmark promessi per il telefono. [Dati](evidence/late-trim.json), [sync indipendente](evidence/late-trim-check.json).
- Registrazione CameraX → montaggio → export provata su camere emulate. Sorgenti H.264 1280×720 ruotate 90°, audio AMR-NB 8 kHz mono; export H.264/AAC 48 kHz stereo, 5.205.500 µs, display 1080×1920 (coded 1920×1080 con rotazione 90°). Il profilo effettivo differisce dal richiesto: questo conferma perché va misurato anche sul Samsung. [Report](evidence/camera-export.json).

Le misure strumentali e il checker indipendente possono individuare il primo campione del marcatore con una piccola differenza di soglia; i JSON conservano i valori effettivi. Il margine sintetico A/V è di circa 3 ms: il test non prova sync perfetto o comportamento identico per ogni codec. Per audio non AAC-LC riconosciuto, il preroll può decodificare dall'inizio della sorgente, con costo maggiore sui tagli tardivi.

## APK, aggiornamento e interfaccia

APK personale `Creator-0.1.0.apk`: 56.397.964 byte, application ID `it.panebarco.creator`, versione 0.1.0/code 1, non debuggabile. SHA-256:

```text
3d70b62c265c8f29953a327a82943bca41c67e66d39f2258620096cee9d0138d
```

Firma APK v2 verificata; certificato pubblico SHA-256:

```text
74de6ac4b1fb59e51b1f985a2192dc462a1b8120587dd2bc3f2c8a5ccd88b689
```

Verificati allineamento ZIP e segmenti LOAD delle tre librerie native arm64 a 16 KiB. Questo controllo strutturale non sostituisce l'esecuzione su un dispositivo con pagine da 16 KiB. Il manifest non contiene il permesso INTERNET; FileProvider non esportato, percorsi di condivisione limitati agli export. Le attribuzioni sono incluse nell'app: [dipendenze e licenze](DEPENDENCIES.md).

Installazione in-place della build firmata sopra una precedente candidata firmata con la stessa chiave: tutti i 9 file del progetto di prova, compresi originale, manifest e revisioni, sono rimasti identici byte per byte. Dopo avvio a freddo, il gesto pubblico Redo ha ripristinato una modifica rimasta annullata prima dell'aggiornamento; i tre snapshot di cronologia e l'originale sono rimasti integri. [Risultato](evidence/upgrade-check.json). La prova dimostra continuità della firma e conservazione nel caso verificato; non è una migrazione di schema fra versioni pubbliche diverse. Sulla stessa release sono riusciti export, salvataggio MediaStore, backup e ripristino con i veri Photo Picker e Documents Provider: il progetto ripristinato ha un nuovo ID e conserva sorgente e cronologia. L'MP4 è stato decodificato integralmente con FFmpeg: H.264 1920×1080, video 3 s, AAC 48 kHz stereo con padding finale. La prova riguarda l'emulatore, non la Galleria Samsung.

Ispezione visiva dell'editor in portrait/landscape e testo al 150%: controlli leggibili, Undo/Redo sempre nella barra superiore, pulsanti di import/registrazione adattati alla larghezza. Il recorder è verificato a 1080×2340/densità 450, portrait/landscape e testo 100/150%, più un viewport 600×220 dp con font 200% ed errore su tre righe. REC/Pausa resta raggiungibile senza scorrere e la preview FIT_CENTER è separata dai controlli. REC fuori schermo e reset della frontale sono stati prima riprodotti sulle versioni precedenti. Non è una certificazione TalkBack completa. Screenshot, APK, video sintetici, log completi e XML di esecuzione restano locali sotto `.qa/evidence/` e nelle directory `build/`, esclusi da Git.

## Comandi principali per ripetere le prove

Dalla cartella `android`, con JDK 17, SDK configurato e FFmpeg disponibile:

```sh
./gradlew :domain:test :data:test :media:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest \
  :media:assembleDebugAndroidTest
ANDROID_SERIAL=SERIALE ./gradlew -Pandroid.injected.device.serial=SERIALE \
  :app:connectedDebugAndroidTest :media:connectedDebugAndroidTest
CREATOR_SIGNING_PROPERTIES=/percorso/privato/release.properties \
  ./gradlew :app:assembleRelease
```

Usare un dispositivo di test dedicato. La build debug ha archivio separato dall'app personale. Il checker è in `media/src/test/python/check_sync_markers.py`; i campioni sono generati da `scripts/generate-fixtures.sh`. I report originali sono in `*/build/test-results/`, `*/build/outputs/androidTest-results/` e `*/build/reports/`.

## Stato delle prove fisiche

La prima ripresa posteriore sul Samsung è riuscita: 31,986 s, H.264 1080p ruotato correttamente in verticale, circa 30,013 fps medi e AAC-LC 48 kHz stereo. Preview e fotogrammi salvati mostrano la scena riconoscibile orientata correttamente. File e dati di test sono stati copiati integralmente sul Mac prima che l'utente scollegasse il telefono.

Questa prova ha anche rivelato REC fuori dalla schermata. La nuova UI e le correzioni di rotazione sono verificate negli emulatori ma **non ancora installate e ricontrollate sul Samsung**. Restano frontale/posteriore nei due orientamenti, mirroring, focus/esposizione, sync delle riprese reali e dell'export, interruzioni firmware, temperatura e sessioni da 30/60 minuti. Anche Galleria Samsung e provider scelti dall'utente vanno provati sul dispositivo. Sequenza e criteri: [SAMSUNG-ACCEPTANCE.md](SAMSUNG-ACCEPTANCE.md).
