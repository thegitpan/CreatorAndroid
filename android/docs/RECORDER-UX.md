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
