# Daten- und Quellenangaben

Zuletzt geprüft: 2026-05-07

Diese Datei dokumentiert Quellen- und Lizenzangaben für von GlanceMap genutzte Kartenressourcen ohne Programmcode, Datensätze und importierte POI-Quellen.

## 1) OpenStreetMap-Daten

- Empfohlener Quellenhinweis:
  - `Map data (c) OpenStreetMap contributors`
- Quellen:
  - https://www.openstreetmap.org/copyright
  - https://opendatacommons.org/licenses/odbl/1-0/

## 2) Elevate-Kartenthema (OpenAndroMaps-Umfeld)

- Lokale Themendatei:
- `app/src/main/assets/Elevate.xml`
- `app/src/main/assets/theme/elevate/`
- `app/src/main/assets/theme/elevate-winter/`
- `app/src/main/assets/theme/elevate-winter-white/`
- Enthaltene Lizenzerklärung in der Datei:
  - CC BY-NC-SA 4.0 für den Kartenstil.
  - Der Hinweis zur kommerziellen Nutzung verweist auf BY-ND 4.0.
- Ursprüngliche Verweise:
  - https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme
  - https://www.openandromaps.org/en/license

Wichtig:
- `Elevate.xml` verweist für Ressourcen-Wiederverwendungslizenzen auf `read_me_elevate.txt`.
- Die versionierten Themenstände enthalten `read_me_elevate.txt`; normale Builds laden keine Themenressourcen herunter.
- Die Quell-Ersatzdatei im Stammverzeichnis führt `app/src/main/assets/read_me_elevate.txt` neben `Elevate.xml`.
- Die OpenAndroMaps-Lizenzseite enthält Beigabe-/Genehmigungsvorgaben.
- Laut Projektverantwortlichem wurde die Erlaubnis zur OpenAndroMaps/Elevate-Nutzung und -Integration durch GlanceMap von OpenAndroMaps und Tobias Kuehn erteilt.
- Genaue Zustimmungsdaten und genehmigten Umfang vor Änderung der Sichtbarkeit außerhalb des öffentlichen Repositorys festhalten.

## 3) OpenHiking-Mapsforge-Thema

- Lokale enthaltene Themendateien:
  - `app/src/main/assets/theme/openhiking/OpenHiking.xml`
  - Ressourcenbaum `app/src/main/assets/theme/openhiking/`
- In der Konfiguration festgelegte Ursprungsversion:
  - `2026-02-10`
- Optionales Aktualisierungsarchiv zur Build-Zeit:
  - `https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus`
- Am 2026-03-14 festgestellter extrahierter Themeninhalt:
  - `OpenHiking/OpenHiking.xml`
  - Im ZIP enthaltene SVG-/Muster-/Symbol-Unterordner.
- Ursprüngliche Verweise:
  - https://openhiking.eu/en/downloads/mapsforge-maps
  - https://openhiking.eu/locus/Hungary.xml

Wichtig:
- Das heruntergeladene ZIP enthielt keine erkennbare separate Lizenz-/Readme-Datei.
- Der Projektverantwortliche bestätigte am 2026-04-26 die Genehmigung von OpenHiking.
- Der Projektverantwortliche bestätigte `CC BY-SA` als Lizenz des OpenHiking-Themas.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Genaue ursprüngliche Seite/Version und Genehmigungsdetails privat archivieren, sobald verfügbar.
- Nutzerhinweis in `licenses/OPENHIKING_THEME.md` verfügbar.

## 4) Tiramisu-Mapsforge-Thema

- Lokale enthaltene Themendateien:
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
  - Ressourcenbaum `app/src/main/assets/theme/tiramisu/res/`
- Quelle des enthaltenen Stands:
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4
- Am 2026-03-18 festgestellter Veröffentlichungsinhalt:
  - `Tiramisu.xml`
  - SVG-Ressourcenbaum `res/`
  - `LICENSE`
  - `README.md`
- Im Ursprungspaket erklärte Lizenz:
  - CC BY-NC-SA 3.0

Wichtig:
- Die ursprüngliche Lizenz ist nichtkommerziell.
- Der Projektverantwortliche bestätigte am 2026-04-26 das nichtkommerzielle Vertriebsmodell; der aktuelle Plan für öffentliches Repository und App wird daher als vereinbar betrachtet.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Bei kommerziellem Vertriebsmodell erneut prüfen.
- Nutzerhinweis in `licenses/TIRAMISU_THEME.md` verfügbar.

## 5) French-Kiss-Mapsforge-Thema

- Lokale enthaltene Themendateien:
  - `app/src/main/assets/theme/frenchkiss/frenchkiss.xml`
  - `app/src/main/assets/theme/frenchkiss/motifs/`
- Quelle des aktuellen lokalen Paketstands:
  - Vom Nutzer bereitgestelltes `xctrack-beta-latest.apk`
- Am 2026-03-18 festgestellter Standinhalt:
  - `frenchkiss.xml`
  - PNG-Ressourcenbaum `motifs/`
- Enthaltene Herkunftsangabe im XML-Kopf:
  - `IGN TOP25 theme for XCTrack by Pascal Cochet`

Wichtig:
- Neben den extrahierten Themenressourcen wurde keine separate Lizenzdatei gefunden.
- Der Projektverantwortliche bestätigte am 2026-04-27 die Genehmigung des French-Kiss-Entwicklers.
- Aktuelle Quellenangabe beibehalten, sofern der Urheber später keine genauere Formulierung verlangt.
- Genaue Genehmigungsdetails privat archivieren.
- Nutzerhinweis in `licenses/FRENCH_KISS_THEME.md` verfügbar.

## 6) Hike, Ride & Sight-Mapsforge-Thema

- Lokale enthaltene Themendateien:
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`
- Themenseite:
  - http://j.seydoux.free.fr/locus/hrs.html
- Manueller ZIP-Download:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Festgestellte ursprüngliche Lizenzerklärung:
  - Im XML-Kopfkommentar als CC BY-NC-SA 3.0 angegeben

Wichtig:
- Im heruntergeladenen ZIP wurde keine separate Lizenzdatei gefunden.
- Die angegebene Lizenz ist nichtkommerziell.
- Der Projektverantwortliche bestätigte am 2026-04-26 die Genehmigung zur Nutzung/Weiterverteilung durch GlanceMap.
- Das aktuelle nichtkommerzielle Vertriebsmodell von GlanceMap wird als vereinbar betrachtet.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Erforderliche Nutzerlinks werden in `licenses/CREDITS_AND_THANKS.md` beibehalten.
- Nutzerhinweis in `licenses/HIKE_RIDE_SIGHT_THEME.md` verfügbar.

## 7) Voluntary-Mapsforge-Thema

- Lokale enthaltene Themendateien:
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
  - `app/src/main/assets/theme/voluntary/vol_res/`
- Themenwebsite:
  - https://voluntary.nichesite.org/
- Manueller ZIP-Download:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Legende:
  - https://voluntary.nichesite.org/key.html
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf
- Festgestellte ursprüngliche Lizenzerklärung:
  - Im XML-Kopfkommentar und auf der ursprünglichen Quellenangabenseite als CC BY-NC-SA 3.0 angegeben

Wichtig:
- Im heruntergeladenen ZIP wurde keine separate Lizenzdatei gefunden.
- Die angegebene Lizenz ist nichtkommerziell.
- Der Projektverantwortliche bestätigte am 2026-04-29 die Zustimmung des Urhebers zur GlanceMap-Integration.
- Das aktuelle nichtkommerzielle Vertriebsmodell von GlanceMap wird als vereinbar betrachtet.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Nutzerhinweis in `licenses/VOLUNTARY_THEME.md` verfügbar.

## 8) OS-Map-Mapsforge-Thema

- Lokale enthaltene Themendateien:
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
  - `app/src/main/assets/theme/os-map/os_res/`
- Themendiskussion:
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- Veröffentlichungs-ZIP:
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE
- Legenden:
  - https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view
- Festgestellte ursprüngliche Lizenzerklärung:
  - Im XML-Kopfkommentar als CC BY-NC-SA 3.0 angegeben

Wichtig:
- Die angegebene Lizenz ist nichtkommerziell.
- Das aktuelle nichtkommerzielle Vertriebsmodell von GlanceMap wird als vereinbar betrachtet.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Nutzerhinweis in `licenses/OS_MAP_THEME.md` verfügbar.

## 9) Refuges.info-Datenimporte

- Die Begleit-App importiert POI-Daten von:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`
- Lizenzverweis:
  - https://www.refuges.info/wiki/licence

Hinweise:
- Laut Refuges.info stehen Inhalte der Punktseiten unter CC BY-SA.
- Refuges.info verweist gegebenenfalls auch auf OpenStreetMap-Daten und ODbL-Vorgaben.

## 9) Ergänzung von OSM-POI über Overpass API

- Von der Begleit-App genutzter Endpunkt:
  - `https://overpass-api.de/api/interpreter`
- Dokumentation:
  - https://wiki.openstreetmap.org/wiki/Overpass_API

Nutzungshinweis:
- Die Hinweise zur öffentlichen Instanz nennen vorsichtige Grenzen für Abfragen und Bandbreite pro Tag.

## 10) DEM-Höhendaten (Neigungs-/Geländedarstellung)

- Build und Laufzeit nutzen DEM3-artige `.hgt.zip`-Kacheln und optional `.hgt.gz`-Kacheln mit 1 Bogensekunde.
- Standardquelle in App/Build:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Optionale Quelle mit 1 Bogensekunde:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`
- Verweise:
  - https://download.mapsforge.org/maps/dem/
  - https://download.mapsforge.org/maps/dem/ReadMe.md
  - https://www.mapzen.com/blog/terrain-tile-service/
  - https://www.mapzen.com/rights/

## 11) POI-Symbolressourcen

- Lokale POI-Symbole stammen aus den SVG-Symbolsätzen Maki und Temaki:
  - https://github.com/mapbox/maki
  - https://github.com/rapideditor/temaki
- Der Build kann außerdem Maki-SVG-Ersatzsymbole herunterladen von:
  - https://github.com/mapbox/maki
- Lizenz:
  - CC0 1.0 Universal
  - https://creativecommons.org/publicdomain/zero/1.0/

## 12) BRouter-Routenressourcen und -segmente

- Enthaltene Routenprofilressourcen:
  - `app/src/main/assets/brouter/profiles2/lookups.dat`
  - `app/src/main/assets/brouter/profiles2/hiking-mountain.brf`
- Downloadquelle der Routensegmente in der Begleit-App:
  - `https://brouter.de/brouter/segments4`
- Ursprüngliche Verweise:
  - https://github.com/abrensch/brouter
  - https://brouter.de/brouter/

Hinweise:
- Laut BRouter werden `.rd5`-Routensegmente aus OpenStreetMap-Daten erzeugt und wöchentlich aktualisiert.
- Die OpenStreetMap-Quellenangabe bleibt für Routensegmentdaten relevant.
- Die Uhr-App bindet BRouter-Code separat unter der MIT-Lizenz ein; Lizenzhinweis zum Code in `THIRD_PARTY_NOTICES.md`.

## 13) Vom Nutzer importierte Dateien

- Die App unterstützt vom Nutzer importierte Karten-, POI- und GPX-Dateien.
- Lizenzen und Weiterverteilungsrechte bereitgestellter Dateien bleiben in der Verantwortung des Nutzers.

## 14) Praktische Konformitätsprüfliste

- Rechtliche Hinweise aus den Uhreinstellungen erreichbar halten.
- `CREDITS_AND_THANKS.md` verständlich und kurz halten.
- Ausführliche rechtliche Dokumentation mit externen Links und APIs der Begleit-App abstimmen.
- Bedingungen bei Änderungen an Anbieter-URLs oder Importquellen erneut prüfen.
