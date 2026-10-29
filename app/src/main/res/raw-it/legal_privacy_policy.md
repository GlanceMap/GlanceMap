# Informativa sulla privacy di GlanceMap

Ultimo aggiornamento: 2026-05-31

Questa informativa si applica a GlanceMap, comprese l'app Wear OS per l'orologio e l'app companion Android, distribuite con il nome GlanceMap e il pacchetto `com.glancemap.glancemapwearos`.

Contatto privacy: `Glancemap@protonmail.com`

## Riepilogo

GlanceMap è progettato per funzionare principalmente sui tuoi dispositivi. Navigazione, visualizzazione mappe, archiviazione, impostazioni e trasferimenti telefono/orologio avvengono per lo più localmente sui due dispositivi.

Non richiediamo un account per usare l'app. Non vendiamo i tuoi dati personali. Non usiamo posizione o file per la pubblicità.

## Dati a cui accediamo e perché

### 1. Dati posizione sull'orologio e nell'app companion

- Posizione precisa o approssimativa, se concedi il permesso.
- Usata per navigazione in tempo reale, centratura mappa, guida percorsi, arrivo stimato, funzioni bussola/posizione e relative funzioni GPS in primo piano.
- Se scegli Tracciamento live nell'app companion, la posizione del telefono viene inviata ad Arkluz per pubblicare la traccia in tempo reale per il gruppo configurato.
- Nella configurazione attuale esaminata, GlanceMap non richiede `ACCESS_BACKGROUND_LOCATION`. Il permesso posizione è richiesto per l'uso in primo piano.

### 2. File e contenuti dell'utente

- File che scegli di importare, aprire, generare, conservare, trasferire o esportare, compresi `.gpx`, `.map`, `.poi`, `.rd5` e file diagnostici.
- Usati per mostrare mappe, percorsi, punti d'interesse e dati percorsi, trasferimenti telefono/orologio ed esportazioni diagnostiche opzionali.
- Questi file possono contenere informazioni personali o sensibili a seconda di ciò che inserisci.

### 3. Informazioni su dispositivo, app e connessione

- Versione app, modello dispositivo, versione Android/Wear OS, stato notifiche e permessi posizione/Bluetooth/notifiche, stato Bluetooth/Wi-Fi/rete, orologi vicini, stato trasferimenti, identificativi degli orologi selezionati e metadati locali come nomi e dimensioni dei file.
- Usata per associazione, trasferimenti, compatibilità, notifiche, debug e prestazioni.

### 4. Diagnostica e informazioni sugli arresti anomali

- L'app può conservare note sugli arresti anomali e log diagnostici localmente sul dispositivo.
- Se esporti manualmente la diagnostica, il rapporto può includere dettagli di dispositivo e app, righe di log, tracce degli arresti, trasferimenti, date e ore, nomi dei file selezionati e, se l'acquisizione diagnostica è attiva, telemetria posizione o GNSS.
- La diagnostica serve a risolvere problemi e viene condivisa fuori dal dispositivo solo quando scegli esplicitamente di inviarla o esportarla.

### 5. Impostazioni e preferenze locali

- Preferenze come unità, intervallo GPS, opzioni mappa, ultimi parametri d'importazione o download, cronologia trasferimenti e temi scelti.
- Impostazioni Tracciamento live come nome gruppo, password partecipante e spettatore, nome partecipante, email opzionali per notifiche e allarmi, intervallo allarme assenza di movimento, bozze commenti e riferimento GPX selezionato.
- Usate per ricordare la configurazione e facilitare gli avvii successivi.

## Quando i dati lasciano il dispositivo

I dati possono lasciare il dispositivo nei casi seguenti:

### 1. Download richiesti dall'utente da fornitori esterni

Quando richiedi POI, percorsi, altimetria, anteprime mappa o dati simili online, l'app invia al fornitore scelto quanto necessario, come l'area geografica selezionata (riquadro), nomi delle tessere percorsi, coordinate delle tessere mappa o file richiesti.

Come per le normali richieste internet, i fornitori possono ricevere anche metadati standard come indirizzo IP, ora della richiesta, user agent e informazioni di connessione simili.

Fornitori usati dalla configurazione attuale dell'app:

- `refuges.info`
- `overpass-api.de`
- `tile.openstreetmap.org`
- `brouter.de`
- `download.mapsforge.org`
- `arkluz.com`

I siti esterni per scaricare mappe o GPX aperti dall'app companion seguono le proprie informative sulla privacy.

### 2. Tracciamento live opzionale tramite Arkluz

Avviando Tracciamento live dall'app companion vengono inviati ad Arkluz posizione GPS, data e ora, precisione, quota e velocità opzionali, batteria, nome gruppo, password e nome partecipante, email opzionali di notifica e allarme e impostazioni allarmi assenza di movimento.

Se aggiungi un percorso GPX o un commento, vengono caricati su Arkluz per il gruppo. Se la rete non consegna i dati, l'app può accodare temporaneamente punti GPS locali e riprovare più tardi. Si prevede che Arkluz elimini automaticamente tracce e posizioni live dopo 7 giorni.

### 3. Trasferimento file telefono/orologio

I file scelti vengono trasferiti tra telefono e orologio tramite i canali Google Wear OS e, in alcuni casi, tramite HTTP locale su Wi-Fi protetto da token nella tua rete locale.

Non gestiamo un servizio cloud dedicato a questi trasferimenti.

### 4. Esportazione diagnostica avviata dall'utente

Se esporti o invii la diagnostica via email, i contenuti scelti vengono allegati a una bozza o condivisi con l'app selezionata. Il tuo provider email o altra destinazione gestisce l'invio secondo le proprie politiche.

### 5. Servizi Firebase opzionali, se configurati in una versione di rilascio

Il codice GlanceMap contiene una possibile integrazione futura opzionale con Firebase Analytics e Firebase Crashlytics.

Nella configurazione attuale esaminata, Firebase non è attivo e GlanceMap non invia a Firebase analisi o rapporti sugli arresti.

Se Firebase viene attivato in futuro, Google o Firebase possono trattare eventi di analisi, diagnostica arresti, metadati di dispositivo/app e dati prestazioni secondo termini e pratiche privacy di Google. Prima del rilascio andrebbero rivisti il modulo Sicurezza dei dati Google Play e questa informativa.

## Condivisione

Possiamo condividere dati solo quando necessario per le funzioni che scegli di usare:

- Con i componenti di comunicazione Wear OS / Google Play services per collegare telefono e orologio e trasferire dati.
- Con i fornitori esterni che usi direttamente per scaricare mappe, POI, percorsi o terreno.
- Con Arkluz quando usi Tracciamento live opzionale.
- Con l'app email o condivisione scelta e il relativo fornitore quando invii manualmente diagnostica o file esportati.
- Con Firebase o Google solo se Firebase è attivo nella versione che installi.

Non vendiamo i tuoi dati personali.

## Archiviazione, conservazione ed eliminazione

- Mappe importate, file GPX, database POI, pacchetti percorsi, impostazioni e dati simili restano in genere sui tuoi dispositivi finché li elimini, cancelli i dati dell'app o la disinstalli.
- Le cache temporanee possono essere rimosse automaticamente o con le funzioni di pulizia integrate.
- Cronologia trasferimenti, ultimi parametri download/importazione e stato dell'aiuto possono restare locali finché cancellati, sovrascritti o rimossi con i dati dell'app.
- Impostazioni gruppo, password, email, bozze commenti, riferimento GPX e posizioni in attesa del Tracciamento live possono restare locali finché cancellati, sovrascritti, inviati, rimossi dal logout, dalla pulizia o con i dati dell'app.
- File locali di arresti e diagnostica possono restare sul dispositivo finché sovrascritti, cancellati o finché l'app viene disinstallata.
- I file diagnostici preparati per email possono restare nella cache finché puliti dall'app, dal sistema o da te.
- GlanceMap non richiede un account, quindi non esiste un profilo cloud associato da eliminare.

## Sicurezza

- L'app conserva i dati principalmente in aree private e usa `FileProvider` di Android per la condivisione controllata dei file quando necessario.
- Le richieste ai fornitori esterni nella configurazione attuale usano generalmente HTTPS.
- Le richieste Tracciamento live ad Arkluz usano HTTPS nella configurazione di rilascio.
- Il trasferimento Wi-Fi locale telefono/orologio usa attualmente HTTP locale protetto da token nella tua rete e non va considerato archiviazione internet cifrata end-to-end.
- Nessun metodo di archiviazione o invio è perfettamente sicuro; servizi e app di terzi scelti hanno proprie pratiche di sicurezza.

## Le tue scelte

Puoi:

- Concedere o negare permessi come posizione, notifiche e accesso Bluetooth.
- Decidere quali file importare, generare, trasferire o esportare.
- Decidere se creare o unirti a un gruppo Tracciamento live, avviarlo/fermarlo, aggiungere GPX/commenti o configurare email di notifica e allarme.
- Decidere se avviare l'acquisizione diagnostica o inviarla via email.
- Gestire o eliminare file locali, cache e dati dell'app dall'app o dalle impostazioni Android.
- Disinstallare l'app per rimuovere i dati locali dal dispositivo, secondo il normale comportamento Android e tenendo conto dei file esportati altrove.

## Minori

GlanceMap non è progettato come app per bambini.

## Modifiche a questa informativa

Possiamo aggiornare questa informativa quando l'app cambia. Aggiorneremo la data «Ultimo aggiornamento» per modifiche sostanziali.

## Contatti

Domande o richieste sulla privacy possono essere inviate a:

`Glancemap@protonmail.com`
