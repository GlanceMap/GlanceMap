# Dienstbedingungen und API-Nutzung

Zuletzt geprüft: 2026-05-06

Dieses Dokument erfasst rechtliche und Nutzungsvorgaben außerhalb von Bibliotheken für externe Dienste und Anbieter, auf die App und Build verweisen.

## 1) OpenAndroMaps und Elevate

Verwendet für:
- Karten-Downloadempfehlungen in der Begleit-App.
- Elevate-Themendownload und lokale Ersatzressourcen im Uhr-Build und zur Laufzeit.

Verweise:
- https://www.openandromaps.org/en/license
- https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme

Wichtige Bedingungen (laut ursprünglicher Lizenzseite):
- Direkte Kartenlinks sind laut Angabe verboten.
- Kartenverkauf oder Beigabe von Karten zu einer App erfordert schriftliche Genehmigung des Anbieters.
- Die Integration von „andromaps_“-Themen ist für kostenlose Aktualisierungsdienste allgemein erlaubt.
- Die Integration von Tobias' Themen einschließlich Elevate erfordert seine schriftliche Genehmigung.

Projektstatus:
- Laut Projektverantwortlichem wurde die Erlaubnis zur OpenAndroMaps/Elevate-Nutzung und -Integration durch GlanceMap von OpenAndroMaps und Tobias Kuehn erteilt.
- Originale der Zustimmungsnachrichten außerhalb des öffentlichen Repositorys aufbewahren.
- Vor Änderung der GitHub-Sichtbarkeit Anfrage-/Antwortdaten und genehmigten Umfang in privaten Versionsnotizen oder einem internen Issue festhalten.

## 2) OpenHiking-Mapsforge-Thema

Verwendet für:
- Lokal enthaltene OpenHiking-Themenressourcen in der Uhr-App.
- Optionaler OpenHiking-Aktualisierungsdownload im Uhr-Build.
- Verweis auf die OpenHiking-Karten-/Themenwebsite in der Begleit-App.

Verweise:
- https://openhiking.eu/en/downloads/mapsforge-maps
- https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus

Hinweise zu Bedingungen:
- Die öffentliche Website bietet direkte Download-/Installationslinks, jedoch wurde keine dokumentierte allgemeine API gefunden.
- Das am 2026-03-14 untersuchte heruntergeladene Themen-ZIP enthielt keine erkennbare separate Lizenz-/Readme-Datei.

Projektstatus:
- Der Projektverantwortliche bestätigte am 2026-04-26 die Genehmigung von OpenHiking.
- Der Projektverantwortliche bestätigte `CC BY-SA` als Lizenz des OpenHiking-Themas.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Details des aktuell enthaltenen Stands in `licenses/OPENHIKING_THEME.md` dokumentieren.

## 3) Voluntary-Mapsforge-Thema

Verwendet für:
- Lokal enthaltene Voluntary-Themenressourcen in der Uhr-App.
- Verweise auf Website und Legende des Voluntary-Themas in der Begleit-App.

Verweise:
- https://voluntary.nichesite.org/
- https://voluntary.nichesite.org/downloads.html
- https://voluntary.nichesite.org/key.html
- https://voluntary.nichesite.org/credits.html

Hinweise zu Bedingungen:
- Die öffentliche Website bietet direkte Links zu manuellen Downloads und Legenden, jedoch wurde keine dokumentierte allgemeine API gefunden.
- Das am 2026-04-29 untersuchte heruntergeladene Themen-ZIP enthielt keine erkennbare separate Lizenz-/Readme-Datei.
- XML-Kopf und Quellenangabenseite nennen CC BY-NC-SA 3.0 als Themenlizenz.

Projektstatus:
- Der Projektverantwortliche bestätigte am 2026-04-29 die Zustimmung des Urhebers zur GlanceMap-Integration.
- Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
- Details des aktuell enthaltenen Stands in `licenses/VOLUNTARY_THEME.md` dokumentieren.

## 4) Refuges.info-API und -Daten

Verwendet für:
- API-Aufrufe der Begleit-App an:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`

Verweise:
- API-Stamm: https://www.refuges.info/api
- Lizenzseite: https://www.refuges.info/wiki/licence

Hinweise zu Bedingungen:
- Inhalte der Refuges.info-Punktseiten stehen unter Creative Commons BY-SA.
- Refuges.info verwendet gegebenenfalls auch OpenStreetMap-Daten unter ODbL.

## 5) OpenStreetMap und Overpass

Verwendet für:
- OSM-Quellenangabe in der App.
- Hintergrund-Rasterkacheln der Kartenauswahl in der Begleit-App:
  - `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- Ergänzung von OSM-POI über den Overpass-API-Endpunkt:
  - `https://overpass-api.de/api/interpreter`

Verweise:
- OSM-Urheberrecht und Quellenangabe: https://www.openstreetmap.org/copyright
- OSM-Kachelnutzungsrichtlinie: https://operations.osmfoundation.org/policies/tiles/
- ODbL: https://opendatacommons.org/licenses/odbl/1-0/
- Overpass-API-Dokumentation: https://wiki.openstreetmap.org/wiki/Overpass_API

Hinweis zur Nutzungsrichtlinie:
- OSM-Kacheln nur für übliche interaktive Anzeige nutzen. Keine Vorababrufe, Massendownloads oder Offline-Kachelcaches für `tile.openstreetmap.org` hinzufügen.
- OSM-Kacheln erfordern sichtbare Quellenangabe, offizielle HTTPS-URL, klaren App-User-Agent und HTTP-Caching unter Beachtung der Server-Cache-Header.
- Die Hinweise der öffentlichen Overpass-Instanz empfehlen vorsichtige Nutzung von ungefähr:
  - weniger als 10000 Abfragen/Tag
  - weniger als 1 GB Download/Tag

## 6) BRouter-Routing-Engine und Routensegmente

Verwendet für:
- Offline-Routenberechnung in der Uhr-App mit eingebundenen BRouter-Modulen.
- Download von `.rd5`-Routensegmentdateien durch die Begleit-App von:
  - `https://brouter.de/brouter/segments4`

Verweise:
- Projekt: https://github.com/abrensch/brouter
- Offizielle Website: https://brouter.de/brouter/
- Segmentverzeichnis: https://brouter.de/brouter/segments4/

Hinweise zu Bedingungen und Quellenangaben:
- Laut Ursprungsprojekt wird BRouter unter der MIT-Lizenz veröffentlicht.
- Laut Ursprungsprojekt werden `.rd5`-Segmentdateien aus OpenStreetMap-Daten erzeugt und wöchentlich aktualisiert.
- Daher bleiben OpenStreetMap-Quellenangabe und ODbL-Kontext für BRouter-Routendaten relevant.

Projekthinweis:
- Auf den am 2026-03-20 geprüften ursprünglichen BRouter-Seiten wurde keine separate öffentliche Dienst- oder Ratenlimit-Richtlinie gefunden.
- Schlussfolgerung des Projekts: Downloads der Begleit-App vorsichtig halten und ursprüngliche Bedingungen vor großflächiger Automatisierung erneut prüfen.

## 7) DEM- und Geländequellen

Verwendet für:
- DEM3-Downloads von:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Optionale DEM-Downloads mit 1 Bogensekunde von:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`

Verweise:
- https://download.mapsforge.org/maps/dem/
- https://download.mapsforge.org/maps/dem/ReadMe.md
- https://www.mapzen.com/blog/terrain-tile-service/
- https://www.mapzen.com/rights/

## 8) POI-Symbolquelle zur Build-Zeit

Verwendet für:
- Download von Maki-SVG-Symbolen während des Builds.

Verweise:
- https://github.com/mapbox/maki
- Lizenz: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/)

## 9) Bedingungen von Google Play services

Verwendet für:
- Laufzeitabhängigkeiten `play-services-wearable` und `play-services-location`.

Verweise:
- Android-SDK-Lizenzvereinbarung: https://developer.android.com/studio/terms
- Hinweise zu Open-Source-Lizenzen von Google Play services: https://developers.google.com/android/guides/opensource

Hinweis:
- Dies sind Google-SDK-/Dienstbedingungen, keine üblichen Open-Source-Lizenzen wie Apache/LGPL.

## 10) Erklärung zur Unabhängigkeit

Die App verweist zur Nutzerfreundlichkeit auf externe Anbieter und Websites. Sofern diese nichts ausdrücklich anderes erklären, ist GlanceMap weder offiziell mit ihnen verbunden noch von ihnen unterstützt.
