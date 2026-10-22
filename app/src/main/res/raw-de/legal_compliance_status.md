# Konformitätsstatus

Zuletzt geprüft: 2026-05-07

Statuslegende:
- `DONE`: umgesetzt und geprüft.
- `PENDING`: Nachverfolgung vor/nach Veröffentlichung nötig.
- `BLOCKER`: enthaltene Ressource ohne Klärung nicht ausliefern.

## Aktuelle Prüfliste

1. Rechtliche Hinweise aus den Uhreinstellungen erreichbar
   - Status: `DONE`
   - Nachweis: Ansicht `Settings -> Licenses` in der App verfügbar.

2. Verständliche Seite mit Quellenangaben
   - Status: `DONE`
   - Nachweis: `licenses/CREDITS_AND_THANKS.md`.

3. Open-Source-Hinweise zu Kernbibliotheken
   - Status: `DONE`
   - Nachweis: `licenses/THIRD_PARTY_NOTICES.md`.

4. Daten- und Quellenangaben
   - Status: `DONE`
   - Nachweis: `licenses/DATA_AND_ASSET_ATTRIBUTION.md`.

5. Seite zu Dienst-/API-Bedingungen
   - Status: `DONE`
   - Nachweis: `licenses/SERVICE_TERMS_AND_API_USAGE.md`.

6. Transparenz externer Quellen der Begleit-App
   - Status: `DONE`
   - Nachweis: `licenses/COMPANION_EXTERNAL_SOURCES.md`.

7. Weiterverteilungserlaubnis für enthaltenes OpenAndroMaps/Elevate
   - Status: `DONE`
   - Nachweis:
     - Der Projektverantwortliche bestätigte am 2026-04-20, dass OpenAndroMaps und Tobias Kuehn der Nutzung/Integration durch GlanceMap zugestimmt haben.
   - Hinweise:
     - Originale der Zustimmungsnachrichten außerhalb des öffentlichen Repositorys aufbewahren.
     - Vor Änderung der Sichtbarkeit Anfrage-/Antwortdaten und genehmigten Umfang in privaten Versionsnotizen oder einem internen Issue festhalten.
     - Der Umfang sollte öffentliche Quellverteilung, APK/AAB-Beigabe sowie von GlanceMap genutzte geänderte/angepasste Elevate-Themen und -Ressourcen abdecken.

8. Von Elevate referenzierte Ressourcenlizenzdatei (`read_me_elevate.txt`)
   - Status: `DONE`
   - Nachweis:
     - `app/src/main/assets/read_me_elevate.txt` ist neben der Quell-Ersatzdatei `Elevate.xml` im Stammverzeichnis versioniert.
   - Hinweise:
     - Von `app/src/main/assets/Elevate.xml` referenziert.
     - Versionierte Elevate-Stände enthalten `read_me_elevate.txt`; normale Builds bündeln diese lokalen Ressourcen ohne Themen-Downloads.
     - Die versionierte Quell-Ersatzdatei bewahrt die ursprünglichen Lizenzinformationen für die öffentliche Quellprüfung.

9. Nutzungsgrenzen der öffentlichen Overpass-Instanz
   - Status: `PENDING`
   - Hinweise:
     - Die öffentliche Hauptrichtlinie nennt vorsichtige Grenzen (10000 Abfragen/Tag, <1 GB/Tag).
     - Abfrage-/Importdrosselung weiterhin vorsichtig halten.

10. Lizenz-/Weiterverteilungsprüfung des enthaltenen OpenHiking-Themas
   - Status: `DONE`
   - Nachweis:
     - Der Projektverantwortliche bestätigte am 2026-04-26 die Genehmigung von OpenHiking.
     - Der Projektverantwortliche bestätigte `CC BY-SA` als Lizenz des OpenHiking-Themas.
   - Hinweise:
     - Der OpenHiking-Stand ist jetzt in `app/src/main/assets/theme/openhiking` enthalten.
     - Die ZIP-Integration des OpenHiking-Themas bleibt als optionaler Aktualisierungsweg im Build verfügbar.
     - Das untersuchte ZIP enthielt keine erkennbare separate Lizenz-/Readme-Datei.
     - Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
     - Genaue ursprüngliche Seite/Version und Genehmigungsdetails privat archivieren, sobald verfügbar.

11. Lizenz-/Weiterverteilungsprüfung des enthaltenen French-Kiss-Themas
   - Status: `DONE`
   - Nachweis:
     - Der French-Kiss-Stand ist jetzt in `app/src/main/assets/theme/frenchkiss` enthalten.
     - Der aktuelle Stand wurde aus dem vom Nutzer bereitgestellten XCTrack-Beta-APK extrahiert.
     - Das enthaltene XML bezeichnet es als IGN-TOP25-Thema für XCTrack von Pascal Cochet.
     - Der Projektverantwortliche bestätigte am 2026-04-27 die Genehmigung des French-Kiss-Entwicklers.
   - Hinweise:
     - Neben den Themenressourcen wurde keine separate Lizenzdatei gefunden.
     - Aktuelle Quellenangabe beibehalten, sofern der Urheber später keine genauere Formulierung verlangt.
     - Originale Genehmigungsnachricht außerhalb des öffentlichen Repositorys aufbewahren; Daten und genehmigten Umfang privat archivieren.

12. Prüfung der nichtkommerziellen Lizenz des enthaltenen Tiramisu-Themas
   - Status: `DONE`
   - Nachweis:
     - Der Tiramisu-Stand ist in `app/src/main/assets/theme/tiramisu` enthalten.
     - Das untersuchte Veröffentlichungsarchiv enthält eine CC-BY-NC-SA-3.0-Lizenz.
     - Der Projektverantwortliche bestätigte am 2026-04-26 das nichtkommerzielle Vertriebsmodell von GlanceMap.
   - Hinweise:
     - Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
     - Vor jeder Änderung zu kommerzieller Verteilung erneut prüfen.

13. Prüfung der nichtkommerziellen Lizenz des enthaltenen Hike, Ride & Sight-Themas
   - Status: `DONE`
   - Nachweis:
     - Der Hike, Ride & Sight-Stand ist in `app/src/main/assets/theme/hike-ride-sight` enthalten.
     - Der untersuchte XML-Kopf nennt CC BY-NC-SA 3.0.
     - Der Projektverantwortliche bestätigte am 2026-04-26 die Genehmigung zur Nutzung/Weiterverteilung durch GlanceMap.
     - Erforderliche Quellenlinks sind in `licenses/CREDITS_AND_THANKS.md` enthalten.
   - Hinweise:
     - Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
     - Vor jeder Änderung zu kommerzieller Verteilung erneut prüfen.

14. Prüfung der nichtkommerziellen Lizenz des enthaltenen Voluntary-Themas
   - Status: `DONE`
   - Nachweis:
     - Der Voluntary-Stand ist in `app/src/main/assets/theme/voluntary` enthalten.
     - Der untersuchte XML-Kopf und die ursprüngliche Quellenangabenseite nennen CC BY-NC-SA 3.0.
     - Der Projektverantwortliche bestätigte am 2026-04-29 die Zustimmung des Urhebers zur GlanceMap-Integration.
     - Legende und Referenzlinks sind in der Begleit-App enthalten.
   - Hinweise:
     - Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
     - Originale Genehmigungsnachricht außerhalb des öffentlichen Repositorys aufbewahren; Daten und genehmigten Umfang privat archivieren.
     - Vor jeder Änderung zu kommerzieller Verteilung erneut prüfen.

15. Prüfung der nichtkommerziellen Lizenz des enthaltenen OS-Map-Themas
   - Status: `DONE`
   - Nachweis:
     - OS-Map-V4-Stände für Tag/Nacht sind in `app/src/main/assets/theme/os-map` enthalten.
     - Der untersuchte XML-Kopf nennt CC BY-NC-SA 3.0.
     - Legende und Diskussionslinks sind in der Begleit-App enthalten.
   - Hinweise:
     - Quellenangaben und Weitergabe-unter-gleichen-Bedingungen-Pflichten in der Projektdokumentation beibehalten.
     - Vor jeder Änderung zu kommerzieller Verteilung erneut prüfen.

## Freigabekriterien für öffentliches Repository und Veröffentlichung

Für ein öffentliches Repository oder eine öffentliche App-Veröffentlichung alle `BLOCKER`-Punkte lösen.
Offene `PENDING`-Punkte zu Lizenz/Weiterverteilung enthaltener Ressourcen gelten als
blockierend, sofern betroffene Ressourcen nicht aus dem öffentlichen Branch entfernt werden oder
das Restrisiko ausdrücklich akzeptiert wird. Eine Veröffentlichung dieses Repositorys
veröffentlicht auch die enthaltenen Ressourcen Dritter.

Aktuelles Freigabeergebnis:
- `READY FROM A BUNDLED-ASSET LICENSING STANDPOINT`, ohne verbleibende dokumentierte Weiterverteilungsblocker für die aktuellen Themen.
- OpenAndroMaps/Elevate-Erlaubnis und Lizenz-/Readme-Nachweise sind laut Bestätigung des Projektverantwortlichen und versionierter Quell-Readme keine Blocker mehr.
- OpenHiking, French Kiss, Tiramisu, Hike, Ride & Sight, Voluntary und OS Map sind aufgrund dokumentierter Bestätigungen und des aktuellen nichtkommerziellen Modells, soweit zutreffend, keine Blocker mehr.
- Verbleibende Aufgaben vor Veröffentlichung sind operative Punkte in `docs/PUBLIC_REPO_CHECKLIST.md`, etwa GitHub-Metadaten, Pages-Aktivierung und Sicherheitsmeldungen.
