# Attribuzione dati e risorse

Ultima revisione: 2026-05-07

Questo file documenta attribuzione e licenze di risorse cartografiche non software, insiemi di dati e fonti POI importate usati da GlanceMap.

## 1) Dati OpenStreetMap

- Testo di attribuzione consigliato:
  - `Map data (c) OpenStreetMap contributors`
- Fonti:
  - https://www.openstreetmap.org/copyright
  - https://opendatacommons.org/licenses/odbl/1-0/

## 2) Tema cartografico Elevate (ecosistema OpenAndroMaps)

- File tema locale:
- `app/src/main/assets/Elevate.xml`
- `app/src/main/assets/theme/elevate/`
- `app/src/main/assets/theme/elevate-winter/`
- `app/src/main/assets/theme/elevate-winter-white/`
- Dichiarazione di licenza inclusa nel file:
  - CC BY-NC-SA 4.0 per lo stile cartografico.
  - La nota sull'uso commerciale rimanda a BY-ND 4.0.
- Riferimenti originali:
  - https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme
  - https://www.openandromaps.org/en/license

Importante:
- `Elevate.xml` rimanda a `read_me_elevate.txt` per le licenze di riuso delle risorse.
- Le copie dei temi versionate includono `read_me_elevate.txt`; le compilazioni normali non scaricano risorse dei temi.
- Il sorgente di riserva nella radice mantiene `app/src/main/assets/read_me_elevate.txt` accanto a `Elevate.xml`.
- La pagina licenza OpenAndroMaps include vincoli di inclusione e autorizzazione.
- Il responsabile del progetto dichiara di aver ricevuto da OpenAndroMaps e Tobias Kuehn il permesso d'uso e integrazione di OpenAndroMaps/Elevate in GlanceMap.
- Registrare date esatte dell'accordo e ambito approvato fuori dal repository pubblico prima di cambiare visibilità.

## 3) Tema Mapsforge OpenHiking

- File locali del tema incluso:
  - `app/src/main/assets/theme/openhiking/OpenHiking.xml`
  - Albero risorse `app/src/main/assets/theme/openhiking/`
- Versione originale fissata nella configurazione:
  - `2026-02-10`
- Archivio opzionale di aggiornamento in compilazione:
  - `https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus`
- Contenuto estratto del tema osservato il 2026-03-14:
  - `OpenHiking/OpenHiking.xml`
  - Sottocartelle SVG/motivi/simboli incluse nello ZIP.
- Riferimenti originali:
  - https://openhiking.eu/en/downloads/mapsforge-maps
  - https://openhiking.eu/locus/Hungary.xml

Importante:
- Lo ZIP scaricato non includeva un evidente file licenza/readme separato.
- Il responsabile del progetto ha confermato il 2026-04-26 l'approvazione di OpenHiking.
- Il responsabile del progetto ha confermato che la licenza del tema OpenHiking è `CC BY-SA`.
- Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
- Archiviare privatamente pagina/versione originale esatta e dettagli dell'approvazione, quando disponibili.
- Nota per gli utenti disponibile in `licenses/OPENHIKING_THEME.md`.

## 4) Tema Mapsforge Tiramisu

- File locali del tema incluso:
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
  - Albero risorse `app/src/main/assets/theme/tiramisu/res/`
- Origine della copia inclusa:
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4
- Contenuto del rilascio osservato il 2026-03-18:
  - `Tiramisu.xml`
  - Albero risorse SVG `res/`
  - `LICENSE`
  - `README.md`
- Licenza dichiarata nel pacchetto originale:
  - CC BY-NC-SA 3.0

Importante:
- La licenza originale non consente usi commerciali.
- Il responsabile ha confermato il 2026-04-26 il modello non commerciale di GlanceMap; il piano attuale di repository/app pubblici è quindi considerato compatibile.
- Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
- Rivedere se la distribuzione del progetto diventa commerciale.
- Nota per gli utenti disponibile in `licenses/TIRAMISU_THEME.md`.

## 5) Tema Mapsforge French Kiss

- File locali del tema incluso:
  - `app/src/main/assets/theme/frenchkiss/frenchkiss.xml`
  - `app/src/main/assets/theme/frenchkiss/motifs/`
- Origine della copia usata nel pacchetto locale attuale:
  - `xctrack-beta-latest.apk` fornito dall'utente
- Contenuto della copia osservato il 2026-03-18:
  - `frenchkiss.xml`
  - Albero risorse PNG `motifs/`
- Dichiarazione di provenienza inclusa nell'intestazione XML:
  - `IGN TOP25 theme for XCTrack by Pascal Cochet`

Importante:
- Non è stato osservato un file licenza separato accanto alle risorse estratte del tema.
- Il responsabile del progetto ha confermato il 2026-04-27 l'approvazione dello sviluppatore French Kiss.
- Conservare l'attribuzione attuale nei crediti, salvo richiesta successiva più specifica dell'autore originale.
- Archiviare privatamente i dettagli esatti dell'approvazione.
- Nota per gli utenti disponibile in `licenses/FRENCH_KISS_THEME.md`.

## 6) Tema Mapsforge Hike, Ride & Sight

- File locali del tema incluso:
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`
- Pagina del tema:
  - http://j.seydoux.free.fr/locus/hrs.html
- Download ZIP manuale:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Dichiarazione di licenza originale osservata:
  - Dichiarata nel commento dell'intestazione XML come CC BY-NC-SA 3.0

Importante:
- Non è stato osservato un file licenza separato nello ZIP scaricato.
- La licenza dichiarata non consente usi commerciali.
- Il responsabile del progetto ha confermato il 2026-04-26 l'approvazione all'uso/ridistribuzione da parte di GlanceMap.
- L'attuale modello di distribuzione non commerciale di GlanceMap è considerato compatibile.
- Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
- I collegamenti richiesti per gli utenti sono conservati in `licenses/CREDITS_AND_THANKS.md`.
- Nota per gli utenti disponibile in `licenses/HIKE_RIDE_SIGHT_THEME.md`.

## 7) Tema Mapsforge Voluntary

- File locali del tema incluso:
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
  - `app/src/main/assets/theme/voluntary/vol_res/`
- Sito del tema:
  - https://voluntary.nichesite.org/
- Download ZIP manuale:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Legenda:
  - https://voluntary.nichesite.org/key.html
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf
- Dichiarazione di licenza originale osservata:
  - Dichiarata nel commento dell'intestazione XML e nei crediti originali come CC BY-NC-SA 3.0

Importante:
- Non è stato osservato un file licenza separato nello ZIP scaricato.
- La licenza dichiarata non consente usi commerciali.
- Il responsabile del progetto ha confermato il 2026-04-29 l'approvazione dell'autore all'integrazione in GlanceMap.
- L'attuale modello di distribuzione non commerciale di GlanceMap è considerato compatibile.
- Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
- Nota per gli utenti disponibile in `licenses/VOLUNTARY_THEME.md`.

## 8) Tema Mapsforge OS Map

- File locali del tema incluso:
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
  - `app/src/main/assets/theme/os-map/os_res/`
- Discussione del tema:
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- ZIP di rilascio:
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE
- Legende:
  - https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view
- Dichiarazione di licenza originale osservata:
  - Dichiarata nel commento dell'intestazione XML come CC BY-NC-SA 3.0

Importante:
- La licenza dichiarata non consente usi commerciali.
- L'attuale modello di distribuzione non commerciale di GlanceMap è considerato compatibile.
- Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
- Nota per gli utenti disponibile in `licenses/OS_MAP_THEME.md`.

## 9) Importazioni dati Refuges.info

- L'app companion importa dati POI da:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`
- Riferimento licenza:
  - https://www.refuges.info/wiki/licence

Note:
- Refuges.info indica che i contenuti delle pagine dei punti sono sotto CC BY-SA.
- Refuges.info rimanda anche ai dati OpenStreetMap e ai vincoli ODbL ove applicabili.

## 9) Arricchimento POI OSM tramite Overpass API

- Endpoint usato dall'app companion:
  - `https://overpass-api.de/api/interpreter`
- Documentazione:
  - https://wiki.openstreetmap.org/wiki/Overpass_API

Nota d'uso:
- Le indicazioni dell'istanza pubblica prevedono limiti prudenti di richieste e banda giornaliere.

## 10) Dati altimetrici DEM (visualizzazione pendenze/rilievi)

- Compilazione ed esecuzione usano tessere `.hgt.zip` di tipo DEM3 e tessere opzionali `.hgt.gz` da 1 secondo d'arco.
- Fonte predefinita nell'app/compilazione:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Fonte opzionale da 1 secondo d'arco:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`
- Riferimenti:
  - https://download.mapsforge.org/maps/dem/
  - https://download.mapsforge.org/maps/dem/ReadMe.md
  - https://www.mapzen.com/blog/terrain-tile-service/
  - https://www.mapzen.com/rights/

## 11) Risorse icone POI

- Le icone POI locali derivano dalle raccolte SVG Maki e Temaki:
  - https://github.com/mapbox/maki
  - https://github.com/rapideditor/temaki
- La compilazione può anche scaricare icone SVG Maki di riserva da:
  - https://github.com/mapbox/maki
- Licenza:
  - CC0 1.0 Universal
  - https://creativecommons.org/publicdomain/zero/1.0/

## 12) Risorse e segmenti percorsi BRouter

- Risorse profili percorsi incluse:
  - `app/src/main/assets/brouter/profiles2/lookups.dat`
  - `app/src/main/assets/brouter/profiles2/hiking-mountain.brf`
- Fonte download segmenti percorsi nell'app companion:
  - `https://brouter.de/brouter/segments4`
- Riferimenti originali:
  - https://github.com/abrensch/brouter
  - https://brouter.de/brouter/

Note:
- BRouter dichiara che i segmenti percorsi `.rd5` sono generati da dati OpenStreetMap e aggiornati ogni settimana.
- L'attribuzione OpenStreetMap resta pertinente per i dati dei segmenti percorsi.
- L'app orologio include separatamente il codice BRouter sotto licenza MIT; vedi `THIRD_PARTY_NOTICES.md` per l'avviso sulla licenza del codice.

## 13) File importati dall'utente

- L'app supporta file mappa/POI/GPX importati dall'utente.
- Licenze e diritti di ridistribuzione dei file forniti restano responsabilità dell'utente.

## 14) Lista pratica di conformità

- Mantenere una schermata legale accessibile dalle impostazioni dell'orologio.
- Mantenere `CREDITS_AND_THANKS.md` breve e comprensibile.
- Mantenere la documentazione legale dettagliata allineata ai collegamenti e alle API esterne dell'app companion.
- Ricontrollare le condizioni quando cambiano URL dei fornitori o fonti d'importazione.
