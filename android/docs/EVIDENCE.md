# Verifiche della consegna personale 0.1.0

Data: 16 novembre 2026. Ambito: codice Android e correzioni successive alla prima implementazione `29d0f15`. Due emulatori dedicati API 36 arm64, senza dati personali; JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, SDK 36, CameraX 1.6.2 e Media3 1.11.1. Nessun Samsung fisico collegato durante queste prove.

## Suite finale

| Ambiente | Modulo | Passati | Falliti / errori / saltati |
| --- | --- | --- | --- |
| JVM | domain | 9 | 0 / 0 / 0 |
| JVM | data | 32 | 0 / 0 / 0 |
| JVM | media | 12 | 0 / 0 / 0 |
| JVM | app | 5 | 0 / 0 / 0 |
| API 36 arm64 | media | 8 | 0 / 0 / 0 |
| API 36 arm64 | app | 10 | 0 / 0 / 0 |
| **Totale** | | **76** | **0 / 0 / 0** |

[Riepilogo estratto dai report XML](evidence/final-test-summary.json). Le verifiche sono state eseguite dopo le ultime modifiche di produzione. I successivi aggiornamenti documentali non modificano l'APK. Build debug, APK strumentali, release firmata e lint sono passati. La variante diagnostica CameraX 1.7.0-rc02 ha superato la sola compilazione debug; non eredita i risultati runtime della baseline.

I test coprono i flussi pubblici di registrazione A/Pausa/B, uscita/riapertura/C, creazione/rinomina/cestino/ripristino, import, split al playhead, trim, riordino, volume, Undo/Redo, export, annullamento/retry, backup e ripristino, galleria e URI di condivisione. I selettori esterni e il chooser sono intercettati dove necessario; vengono provati stream reali e concessioni URI, senza inviare media a destinatari esterni. La permanenza del destinatario del picker è verificata tramite stato ripristinato; non equivale a provare ogni provider o ogni interruzione possibile del sistema.

L'archivio è verificato contro corruzione, schema futuro, media mancanti, revisioni concorrenti, errori di pubblicazione, replay delle ricevute, backup ostili e recupero distinto di importazioni/riprese. SHA-256 degli originali controllati dopo montaggio ed export.

## Media e sincronizzazione

- Composizione di input misti: tagli fuori keyframe, video ruotato, segmento senza audio, volume ridotto e muto. Il file risultante è H.264/AAC, geometria visualizzata 1080×1920 e durata video 5.133.333 µs rispetto a 5.133.334 µs attesi. La traccia AAC include padding finale del codec; la sola durata del contenitore non è stata usata come misura di sync. Il rapporto di ampiezza misurato è circa 0,25235 per un gain richiesto di 0,25.
- Sei marcatori audio/video sono stati analizzati sul file decodificato con un checker Python/FFmpeg indipendente dall'implementazione. Il problema iniziale raggiungeva circa 42 ms; dopo la correzione lo scarto A/V è fra 30 e 30,011 ms, entro la soglia fissata di 33,334 ms. La soglia non è stata allargata. [Prima](evidence/sync-baseline.json), [dopo](evidence/sync-fixed.json).
- Un ritardo audio aggiunto intenzionalmente di 50 ms viene rifiutato dal checker: [controllo negativo](evidence/sync-negative-control.json). La fixture sorgente calibrata ha scarto zero nel medesimo controllo.
- La preview è stata misurata sui campioni PCM realmente inoltrati all'AudioSink, anche dopo seek. Gli errori rispetto ai marcatori attesi sono circa 16–26 ms; differenza preview/export entro 14 ms. Non è una misura della latenza acustica dell'altoparlante. [Misure preview](evidence/preview-pcm.json).
- Taglio di 1,5 s vicino alla fine di un input da 60 s: avvio decodifica a 57 s per un in-point a 58,35 s, export in 1,171 s sull'emulatore della prova; cancellazione durante preroll in 73 ms. Sono misure di questo ambiente, non benchmark promessi per il telefono. [Dati](evidence/late-trim.json), [sync indipendente](evidence/late-trim-check.json).
- Registrazione CameraX → montaggio → export provata su camere emulate. Sorgenti H.264 1280×720 ruotate 90°, audio AMR-NB 8 kHz mono; export H.264/AAC 48 kHz stereo, 5.205.500 µs, display 1080×1920 (coded 1920×1080 con rotazione 90°). Il profilo effettivo differisce dal richiesto: questo conferma perché va misurato anche sul Samsung. [Report](evidence/camera-export.json).

Le misure strumentali e il checker indipendente possono individuare il primo campione del marcatore con una piccola differenza di soglia; i JSON conservano i valori effettivi. Il margine sintetico A/V è di circa 3 ms: il test non prova sync perfetto o comportamento identico per ogni codec. Per audio non AAC-LC riconosciuto, il preroll può decodificare dall'inizio della sorgente, con costo maggiore sui tagli tardivi.

## APK, aggiornamento e interfaccia

APK personale `Creator-0.1.0.apk`: 56.365.196 byte, application ID `it.panebarco.creator`, versione 0.1.0/code 1, non debuggabile. SHA-256:

```text
da8138d73801339690745cfd0086fe4439b56e02788a52b383f1bbdd9c3cf738
```

Firma APK v2 verificata; certificato pubblico SHA-256:

```text
74de6ac4b1fb59e51b1f985a2192dc462a1b8120587dd2bc3f2c8a5ccd88b689
```

Verificati allineamento ZIP e segmenti LOAD delle tre librerie native arm64 a 16 KiB. Questo controllo strutturale non sostituisce l'esecuzione su un dispositivo con pagine da 16 KiB. Il manifest non contiene il permesso INTERNET; FileProvider non esportato, percorsi di condivisione limitati agli export. Le attribuzioni sono incluse nell'app: [dipendenze e licenze](DEPENDENCIES.md).

Installazione in-place della build firmata sopra una precedente candidata firmata con la stessa chiave: un progetto presente con un originale e due revisioni è rimasto identico byte per byte. [Risultato](evidence/upgrade-check.json). La prova dimostra continuità della firma e conservazione nel caso verificato; non è una migrazione di schema fra versioni pubbliche diverse.

Ispezione visiva dell'editor in portrait/landscape e testo al 150%: controlli leggibili, Undo/Redo sempre nella barra superiore, pulsanti di import/registrazione adattati alla larghezza. Non è una certificazione TalkBack completa. Screenshot, APK, video sintetici, log completi e XML di esecuzione restano locali sotto `.qa/evidence/` e nelle directory `build/`, esclusi da Git.

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

## Verifiche ancora necessarie sul telefono

Camera posteriore/frontale, mirroring, orientamento, profilo effettivo, focus/esposizione, ingresso audio, sync delle riprese reali e dell'export, interruzioni firmware, temperatura e sessioni da 30/60 minuti. Anche il flusso con Galleria Samsung e provider scelti dall'utente deve essere provato sul dispositivo. Sequenza e criteri: [SAMSUNG-ACCEPTANCE.md](SAMSUNG-ACCEPTANCE.md).
