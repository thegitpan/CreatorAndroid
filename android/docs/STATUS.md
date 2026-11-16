# Stato della versione personale 0.1

Aggiornato il 16 novembre 2026. Il percorso iniziale registrazione → montaggio → export è implementato e verificato con media sintetici ed emulatori. L'APK personale firmato è pronto per l'accettazione sul Samsung S26. **La verifica fisica è ancora da eseguire**; questo stato non certifica camera, microfono, temperatura o durata sul telefono.

## Consegna

- Fork: <https://github.com/thegitpan/CreatorAndroid>, ramo predefinito `android/main`.
- Upstream conservato: `antirez/YouRecApp`, commit `faf1815e82e1f2d5f03034a583363d3c3af464a2`.
- App: **Creator 0.1.0**, versione interna 1, ID personale `it.panebarco.creator`.
- APK locale: `android/outputs/Creator-0.1.0/Creator-0.1.0.apk`, escluso da Git insieme a build, campioni e segreti.
- Firma personale stabile; chiave e credenziali fuori dal repository. La build di test usa l'ID separato `it.panebarco.creator.debug`.
- Nessuna pubblicazione su Google Play o release pubblica dell'APK effettuata.

## Funzioni implementate

Registrazione a segmenti, pausa/ripresa e continuazione dopo riapertura; cambio camera fra segmenti; controlli di zoom e fuoco/esposizione secondo capacità. Progetti, importazione, editor sequenziale con miniature e waveform, scrub, split al playhead, trim, riordino, volume/muto, Undo/Redo persistenti. Formati 9:16 e 16:9 con fit, anteprima ed export MP4 H.264/AAC SDR, annullamento, galleria e condivisione. Originali immutabili, cestino ripristinabile, backup ZIP completo e recupero delle operazioni interrotte con avvisi espliciti.

Camera e render pesanti si escludono prima dell'avvio. I temporanei conservano l'origine ripresa/importazione: recuperare un import non lo trasforma in una ripresa non annullabile. Le ricevute di export identificano lavoro e revisione del progetto.

## Verifiche completate

| Verifica finale | Risultato |
| --- | --- |
| Test JVM: dominio, archivio, media, app | 58 superati |
| Test su due emulatori dedicati API 36 arm64 | 18 superati |
| Totale | **76 superati, 0 errori, 0 fallimenti, 0 saltati** |
| Build debug, test APK, release firmata e lint | Superati |
| Audit indipendenti Standards e Spec | 5 rilievi verificati e chiusi |
| Confronto indipendente sync su sei marcatori | Scarto A/V massimo 30,011 ms, soglia 33,334 ms |
| Aggiornamento in-place dell'APK personale | Originale, manifest e revisioni identici byte per byte |
| Firma, allineamento APK/native library a 16 KiB | Verificati |

Il report [EVIDENCE.md](EVIDENCE.md) distingue misure, test, limiti e comandi ripetibili. [AUDIT.md](AUDIT.md) registra le correzioni; [REVIEW.md](REVIEW.md) contiene la chiusura dei due audit indipendenti. La pipeline GitHub è configurata per build e test API 35 x86_64; il suo risultato va letto separatamente dalle verifiche locali.

## Decisioni finali rispetto al piano iniziale

- Tempo persistito in microsecondi interi `Long`, con conversioni e confini testati; non i 240.000 tick/s proposti inizialmente.
- Pausa finalizza un segmento; cambio camera consentito fra segmenti. Nessuna promessa di cambio continuo durante REC.
- CameraX 1.6.2 è la baseline; 1.7.0-rc02 rimane una variante diagnostica. Media3 1.11.1 serve anteprima ed export.
- Il preroll di decodifica evita il disallineamento dei tagli AAC. Per AAC-LC si usa un punto di avvio calcolato dai campioni; codec non riconosciuti usano prudentemente la decodifica dall'inizio, che può costare tempo su clip lunghe.
- Pubblicazione degli output con creazione esclusiva e sincronizzazione del file: i hardlink non funzionano nel percorso Android provato. Un arresto durante la copia finale può lasciare un file privato incompleto, mai dichiarato completato o condiviso.

## Prossimo passaggio necessario

Seguire [SAMSUNG-ACCEPTANCE.md](SAMSUNG-ACCEPTANCE.md). Servono il telefono sbloccato, collegamento USB e autorizzazione Debug USB. Il coordinatore può poi installare, raccogliere diagnostica ed eseguire le prove tecniche; alcune riprese di una scena reale e del riferimento audio/visivo richiedono la presenza dell'utente.

Il codice è stato scritto dagli agenti Sol 6.1 Max `domain_data`, `media_engine` e `app_ui`, revisionato da `audit_storage`, `audit_standards` e `audit_spec`, integrato e verificato dal coordinatore. Non risultano rilievi bloccanti aperti nel perimetro verificabile senza telefono. Titoli, musica separata, PiP, 4K/HDR e sottotitoli restano nelle fasi successive del [piano](PLAN.md).
