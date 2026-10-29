# Datenschutzerklärung für GlanceMap

Zuletzt aktualisiert: 2026-05-31

Diese Datenschutzerklärung gilt für GlanceMap einschließlich der Wear-OS-Uhr-App und der Android-Begleit-App, die unter dem Namen GlanceMap und dem Paket `com.glancemap.glancemapwearos` vertrieben werden.

Datenschutzkontakt: `Glancemap@protonmail.com`

## Zusammenfassung

GlanceMap arbeitet hauptsächlich auf deinen eigenen Geräten. Navigation, Kartendarstellung, Dateispeicherung, Einstellungen und Übertragungen zwischen Telefon und Uhr erfolgen größtenteils lokal auf diesen Geräten.

Du brauchst kein Konto, um die App zu nutzen. Wir verkaufen deine personenbezogenen Daten nicht. Wir verwenden deinen Standort oder deine Dateien nicht für Werbung.

## Welche Daten wir nutzen und warum

### 1. Standortdaten auf Uhr und Begleit-App

- Genauer oder ungefährer Standort, wenn du die Berechtigung erteilst.
- Für Live-Navigation, Kartenzentrierung, Zielführung, Ankunftszeit, Kompass-/Standortfunktionen und zugehörige GPS-Funktionen im Vordergrund.
- Bei Nutzung von Live-Tracking in der Begleit-App wird der Telefonstandort an Arkluz gesendet, um deinen Live-Track für die eingerichtete Gruppe zu veröffentlichen.
- In der aktuell geprüften Konfiguration fordert GlanceMap `ACCESS_BACKGROUND_LOCATION` nicht an. Die Standortberechtigung wird für die Nutzung im Vordergrund angefragt.

### 2. Dateien und Nutzerinhalte

- Dateien, die du importierst, öffnest, erzeugst, speicherst, überträgst oder exportierst, darunter `.gpx`, `.map`, `.poi`, `.rd5` und Diagnosedateien.
- Für Karten, Routen, POI, Routendaten, Übertragungen zwischen Telefon und Uhr und optionale Diagnoseexporte.
- Diese Dateien können je nach Inhalt personenbezogene oder sensible Informationen enthalten.

### 3. Geräte-, App- und Verbindungsdaten

- App-Version, Gerätemodell, Android-/Wear-OS-Version, Benachrichtigungsstatus, Standort-/Bluetooth-/Benachrichtigungsberechtigungen, Bluetooth-/WLAN-/Netzwerkstatus, Uhren in der Nähe, Übertragungsstatus, ausgewählte Uhrenkennungen und lokale Dateimetadaten wie Namen und Größen.
- Für Kopplung, Übertragungen, Kompatibilität, Benachrichtigungen, Fehlerdiagnose und Leistung.

### 4. Diagnose- und Absturzinformationen

- Die App kann Absturznotizen und Diagnoseprotokolle lokal auf dem Gerät speichern.
- Bei manuellem Diagnoseexport kann der Bericht Geräte- und App-Details, Protokollzeilen, Absturz-Stacktraces, Übertragungsdaten, Zeitstempel, ausgewählte Dateinamen und bei aktiver Diagnoseerfassung Standort- oder GNSS-Telemetrie enthalten.
- Diagnosen dienen der Fehlerbehebung und verlassen das Gerät nur, wenn du sie ausdrücklich sendest oder exportierst.

### 5. Einstellungen und lokale Präferenzen

- Präferenzen wie Einheiten, GPS-Intervall, Kartenoptionen, letzte Import-/Downloadparameter, Übertragungsverlauf und Themenwahl.
- Live-Tracking-Einstellungen wie Gruppenname, Teilnehmer- und Zuschauerpasswort, Teilnehmername, optionale Benachrichtigungs- und Alarm-E-Mail-Adressen, Stillstandsalarm-Intervall, Kommentarentwürfe und ausgewählte GPX-Referenz.
- Zum Speichern deiner Einrichtung und für einfachere spätere Nutzung.

## Wann Daten dein Gerät verlassen

Daten können dein Gerät in folgenden Fällen verlassen:

### 1. Vom Nutzer angeforderte Downloads von Drittanbietern

Wenn du Online-POI, Routen-, Höhen-, Kartenvorschaudaten oder Ähnliches anforderst, sendet die App die nötigen Angaben an den gewählten Anbieter, etwa den ausgewählten geografischen Bereich (Begrenzungsrahmen), Routenkachelnamen, Kartenkachelkoordinaten oder angeforderte Dateien.

Wie bei üblichen Internetanfragen können Anbieter auch Standardmetadaten wie IP-Adresse, Anfragezeit, User-Agent und ähnliche Verbindungsdaten erhalten.

Von der aktuellen App-Konfiguration genutzte Anbieter:

- `refuges.info`
- `overpass-api.de`
- `tile.openstreetmap.org`
- `brouter.de`
- `download.mapsforge.org`
- `arkluz.com`

Externe Karten- oder GPX-Downloadseiten, die du aus der Begleit-App öffnest, haben eigene Datenschutzrichtlinien.

### 2. Optionales Live-Tracking über Arkluz

Beim Start von Live-Tracking in der Begleit-App werden GPS-Position, Zeitstempel, Genauigkeit, optionale Höhe und Geschwindigkeit, Akkustand, Gruppenname, Teilnehmerpasswort und -name, optionale Benachrichtigungs-/Alarm-E-Mails und Stillstandsalarm-Einstellungen an Arkluz gesendet.

Hinzugefügte GPX-Routen oder Kommentare werden ebenfalls für die Gruppe an Arkluz hochgeladen. Bei Netzwerkfehlern kann die App GPS-Punkte vorübergehend lokal puffern und später erneut senden. Arkluz soll Live-Tracks und Positionen nach 7 Tagen automatisch löschen.

### 3. Dateiübertragung zwischen Telefon und Uhr

Gewählte Dateien werden über Google-Wear-OS-Kommunikationskanäle und teilweise über eine token-geschützte lokale WLAN-HTTP-Verbindung in deinem Netzwerk zwischen Telefon und Uhr übertragen.

Wir betreiben keinen eigenen Cloud-Dienst für diese Übertragungen.

### 4. Vom Nutzer gestarteter Diagnoseexport

Beim Exportieren oder Versenden von Diagnosen werden ausgewählte Inhalte einem E-Mail-Entwurf beigefügt oder über deine gewählte App geteilt. E-Mail-Anbieter oder anderes gewähltes Ziel behandeln die Übermittlung nach eigenen Richtlinien.

### 5. Optionale Firebase-Dienste, falls in einer Release-Version eingerichtet

Die GlanceMap-Codebasis enthält einen optionalen zukünftigen Integrationsweg für Firebase Analytics und Firebase Crashlytics.

In der aktuell geprüften Konfiguration ist Firebase nicht aktiviert; GlanceMap sendet keine Analyse- oder Absturzberichte an Firebase.

Falls Firebase künftig aktiviert wird, können Google oder Firebase Analyseereignisse, Absturzdiagnosen, Geräte-/App-Metadaten und Leistungsdaten nach Googles Bedingungen und Datenschutzpraxis verarbeiten. Google-Play-Datensicherheitsformular und diese Erklärung sollten dann vor Veröffentlichung erneut geprüft werden.

## Datenweitergabe

Wir dürfen Daten nur weitergeben, soweit dies für die von dir gewählten Funktionen nötig ist:

- Mit Wear-OS-/Google-Play-services-Komponenten zur Verbindung und Datenübertragung zwischen Telefon und Uhr.
- Mit direkt genutzten Drittanbietern für Karten-, POI-, Routen- oder Gelände-Downloads.
- Mit Arkluz bei optionalem Live-Tracking.
- Mit der gewählten E-Mail-/Teilen-App und deren Anbieter beim manuellen Senden von Diagnosen oder exportierten Dateien.
- Mit Firebase oder Google nur, falls Firebase in deiner installierten Version aktiviert ist.

Wir verkaufen deine personenbezogenen Daten nicht.

## Speicherung, Aufbewahrung und Löschung

- Importierte Karten, GPX-Dateien, POI-Datenbanken, Routenpakete, Einstellungen und ähnliche Daten bleiben in der Regel lokal auf deinen Geräten, bis du sie oder die App-Daten löschst oder die App deinstallierst.
- Temporäre Caches können automatisch oder über die integrierten Bereinigungsaktionen entfernt werden.
- Übertragungsverlauf, letzte Download-/Importparameter und Hilfestatus können lokal bleiben, bis sie gelöscht, überschrieben oder mit den App-Daten entfernt werden.
- Live-Tracking-Gruppeneinstellungen, Passwörter, E-Mail-Einstellungen, Kommentarentwürfe, GPX-Referenz und gepufferte Positionen können lokal bleiben, bis sie gelöscht, überschrieben, gesendet, durch Abmelden, Bereinigung oder mit den App-Daten entfernt werden.
- Lokale Absturz- und Diagnosedateien können bis zum Überschreiben, Löschen oder Deinstallieren auf dem Gerät bleiben.
- Für E-Mails vorbereitete Diagnosedateien können im App-Cache bleiben, bis App, System oder du sie bereinigen.
- GlanceMap benötigt kein Konto; daher gibt es kein kontobasiertes Cloud-Profil zu löschen.

## Sicherheit

- Die App speichert Daten überwiegend im privaten App-Speicher und nutzt bei Bedarf Android-`FileProvider` für kontrollierte Dateifreigabe.
- Anfragen an externe Datenanbieter verwenden in der aktuellen Konfiguration in der Regel HTTPS.
- Live-Tracking-Anfragen an Arkluz nutzen in der Release-Konfiguration HTTPS.
- Lokale WLAN-Übertragung vom Telefon zur Uhr nutzt derzeit eine token-geschützte lokale HTTP-Verbindung in deinem Netzwerk und ist kein Ende-zu-Ende-verschlüsselter Internetspeicher.
- Keine Speicherung oder Übertragung ist vollkommen sicher; gewählte Dienste und Apps Dritter haben eigene Sicherheitspraktiken.

## Deine Wahlmöglichkeiten

Du kannst:

- Berechtigungen etwa für Standort, Benachrichtigungen und Bluetooth erteilen oder verweigern.
- Entscheiden, welche Dateien du importierst, erzeugst, überträgst oder exportierst.
- Entscheiden, ob du eine Live-Tracking-Gruppe erstellst oder ihr beitrittst, Live-Tracking startest/stoppst, GPX/Kommentare hinzufügst oder Benachrichtigungs-/Alarm-E-Mails einrichtest.
- Entscheiden, ob du Diagnoseerfassung startest oder Diagnosen per E-Mail sendest.
- Lokale Dateien, Caches und App-Daten in der App oder in Android-Einstellungen verwalten oder löschen.
- Die App deinstallieren, um lokale App-Daten auf diesem Gerät zu entfernen, vorbehaltlich üblicher Android-Regeln und anderweitig exportierter Dateien.

## Kinder

GlanceMap ist nicht als Kinder-App konzipiert.

## Änderungen dieser Erklärung

Wir können diese Datenschutzerklärung bei App-Änderungen aktualisieren. Bei wesentlichen Änderungen ändern wir das Datum „Zuletzt aktualisiert“.

## Kontakt

Datenschutzfragen oder -anfragen können gesendet werden an:

`Glancemap@protonmail.com`
