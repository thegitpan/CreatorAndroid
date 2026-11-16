# Recorder 0.1.1: camera con comandi sovrapposti

Richiesta del 16 novembre 2026, successiva alla candidata 0.1.0 (`73eeafa`). L'utente ha rifiutato la UI con video in un riquadro, comandi esterni e REC raggiungibile soltanto scorrendo. Il riferimento visivo fornito è Samsung Camera, modalità Video Pro: immagine dominante, piccole icone bianche, controlli su sfondo traslucido e grande pulsante circolare di registrazione.

## Comportamento richiesto

- La camera occupa l'intera schermata del recorder, senza titolo progetto, card, cornici o colonne di impostazioni esterne. Tutti i comandi sono sovrapposti all'anteprima. Nessuno scorrimento per trovare REC, Pausa o cambiare camera.
- REC è un cerchio bianco con centro rosso, centrale in basso in verticale; durante la ripresa offre Pausa e salva, con stato e timer distinti. Avvio e salvataggio restano distinguibili e impediscono tocchi concorrenti.
- Icone e brevi etichette compatte per microfono, profilo effettivo, camera, zoom e blocchi AF/AE quando supportati. Target di tocco almeno 48 dp e descrizioni accessibili, anche se la grafica è piccola. Il pannello zoom si apre sopra il video senza cambiarne dimensioni.
- Nessun controllo fittizio ISO, shutter, WB, HDR, LOG o selezione di lenti non implementata. Il riferimento guida disposizione e stile, non promette nuove funzioni hardware.
- Mantenere l'intera inquadratura e le proporzioni: FIT_CENTER su viewport massimo, senza crop o deformazione per riempire display più lunghi del fotogramma. Le sole bande necessarie al rapporto d'aspetto sono ammesse; i comandi principali vanno collocati sul video come nel riferimento.
- Verticale e orizzontale, testo ingrandito, errori e permessi non devono far sparire REC né introdurre scorrimento della schermata camera. Rispettare ritaglio del display e navigazione di sistema; ripristinare il comportamento delle barre uscendo dalla camera.
- Conservare camera selezionata alla rotazione, una sola finalizzazione, attesa del commit, originali e progetti. Nessun riavvio automatico della ripresa dopo ricreazione.

## Verifica e consegna

Partire dalla baseline indicata, aggiornare le regressioni al requisito overlay (la vecchia asserzione di non sovrapposizione è ora errata). Testare gesti reali REC/Pausa e apertura dei comandi senza helper che scorrano per recuperarli. Conservare screenshot emulatori idle/REC, verticale/orizzontale e testo 150%, controlli aperti, permessi/errori. Il coordinatore esamina le immagini, non soltanto l'esito dei test.

La versione diventa 0.1.1/code 2, con la stessa firma e application ID per aggiornamento in-place. L'installazione Samsung esistente contiene una ripresa: non disinstallarla né usarla per la suite strumentale. L'accettazione hardware resta aperta finché il nuovo recorder non è confrontato sul dispositivo con il file salvato, come richiesto da CAMERA-QA.md. Il telefono non è attualmente disponibile; continuare tutte le verifiche indipendenti.

## Risultato della revisione e prove

Implementazione nel commit `8793f59`: recorder fuori dallo Scaffold dell'editor, PreviewView sull'intera schermata, comandi bianchi su sfondo traslucido, REC bianco/rosso e accento giallo sui controlli attivi. Il rapporto dell'anteprima deriva dal crop negoziato e dalla rotazione di CameraX; non viene ricavato da etichette o dal formato del progetto. Le impostazioni di acquisizione e codifica restano quelle della baseline. Le bande FIT non ricevono tap di messa a fuoco.

I revisori Standards e Spec hanno chiuso i rilievi su spazio per permessi e zoom, dimensioni dei target e chiarezza degli avvisi. Il test a 600×220 dp/font 200% verifica target da almeno 48 dp, trascinamento reale dello zoom con e senza permesso microfono, scelta Muto, REC, azioni dei permessi e chiusura dell'avviso. Il testo completo di un avviso troncato è accessibile con il pulsante Info; il dialogo si apre soltanto su richiesta. Queste correzioni sono state riprodotte prima del risultato verde. I test del recorder usano tocchi diretti senza scorrimento; lo scroll dell'editor resta ammesso nel suo flusso di export.

Le prime immagini della finestra forzata in landscape non costituivano una prova di orientamento del sensore: CameraX conservava target 0 e rotazione 90°, coerenti con il sensore virtuale ancora verticale. Una prova separata ha allineato accelerometro e display. Le quattro riprese finali mostrano 1280×720/rotazione 0° in orizzontale e 1280×720/rotazione 90° in verticale, visualizzato 720×1280. Tutti gli originali coincidono con gli hash nel progetto; FFmpeg ha decodificato i fotogrammi confrontati con la preview. La risoluzione 720p è quella effettiva dell'emulatore. Le tracce audio presenti non dimostrano il funzionamento del microfono Samsung. Posa e preferenze dell'emulatore sono state ripristinate.

Build debug/release firmata, 68 test JVM e lint sono passati. Le suite Android complete, eseguite in sequenza su API 36 arm64, passano con 14 test app e 10 media: **92 casi distinti**, zero fallimenti, errori o esclusioni. La ricerca del testo nel nuovo test dei dettagli è stata limitata al dialogo, perché la stessa frase esiste anche nell'avviso sottostante; è una correzione del test, senza modifiche all'APK.

L'aggiornamento personale 0.1.0 → 0.1.1 su emulatore mantiene identici tutti i 15 file confrontati subito dopo l'installazione; dopo l'avvio gli 11 file dei progetti restano identici e i due progetti sono visibili. Cambiano soltanto due metadati di profilazione AndroidX, come previsto all'aggiornamento del pacchetto. Entrambe le firme, personale e test, corrispondono alle precedenti; gli APK superano il controllo di allineamento ZIP a 16 KiB.

Risultati finali delle suite Android, commit verificato e CI sono registrati in [recorder-pro.json](evidence/recorder-pro.json). I report completi, i video e gli screenshot restano locali in `.qa/evidence/recorder-pro/`. La consegna è in `outputs/Creator-0.1.1/`, con APK test per aggiornare l'installazione esistente e APK personale per l'archivio separato. Il Samsung non è stato aggiornato durante questo lavoro.

La [CI finale](https://github.com/thegitpan/CreatorAndroid/actions/runs/43905842061), commit `8b15315`, ha completato entrambi i job con successo: build, JVM e lint su Linux, più 14 test app e 10 media su Android API 35 x86_64. Il commit successivo alla produzione modifica soltanto il selettore del test dei dettagli. Gli APK consegnati restano quelli della produzione `8793f59`.

Verificata inoltre la negazione reale del permesso microfono nel dialogo Android: scelta esplicita Muto, REC dal comando pubblico e ripresa salvata con una sola voce nel registro, originale integro e nessuna traccia audio. Il permesso è stato ripristinato dopo la prova. Questa verifica sul runtime dell'emulatore completa la prova di layout simulata dei permessi.
