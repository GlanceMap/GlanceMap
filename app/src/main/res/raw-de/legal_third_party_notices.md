# Hinweise zu Drittanbietern (Open Source)

Zuletzt geprüft: 2026-05-06

Dieses Dokument behandelt Open-Source-Software und -Ressourcen der Uhr-App (`app`) und Begleit-App (`glancemapcompanionapp`).

## Wichtige Open-Source-Komponenten

| Projekt / Familie | Genutzte Artefakte (Beispiele) | Lizenz | Quelle |
|---|---|---|---|
| AndroidX + Jetpack Compose + Wear Compose | `androidx.*` (Compose UI/Foundation/Material, Navigation, Lifecycle, DataStore, Wear) | Apache License 2.0 | https://github.com/androidx/androidx |
| Kotlin | `org.jetbrains.kotlin:*` | Apache License 2.0 | https://github.com/JetBrains/kotlin |
| Kotlin Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | Apache License 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Ktor | `io.ktor:*` | Apache License 2.0 | https://github.com/ktorio/ktor |
| OkHttp | `com.squareup.okhttp3:okhttp*` | Apache License 2.0 | https://github.com/square/okhttp |
| Okio | `com.squareup.okio:okio*` | Apache License 2.0 | https://github.com/square/okio |
| Gson | `com.google.code.gson:gson` | Apache License 2.0 | https://github.com/google/gson |
| Accompanist | `com.google.accompanist:*` | Apache License 2.0 | https://github.com/google/accompanist |
| AndroidSVG | `com.caverock:androidsvg` | Apache License 2.0 | https://github.com/BigBadaboom/androidsvg |
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes` usw.) | LGPL-3.0-or-later (mit von Mapsforge im Projekt-README erklärter Ausnahme für statisches Linken/abgeleitete Werke) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Eingebundene Module `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` aus `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | BSD-artig / sehr freizügig (laut Projekt und POM-Metadaten) | https://kobjects.org/kxml/ |
| Maki-Symbole | SVG-POI-Symbole von Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Temaki-Symbole | SVG-POI-Symbole von Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Wichtige nicht quelloffene Laufzeitabhängigkeit

- Google-Play-services-Artefakte, etwa `com.google.android.gms:play-services-wearable` und `play-services-location`, stehen unter Google-/Android-SDK-Bedingungen, nicht unter üblichen Open-Source-Lizenzen.
- Verweise auf Bedingungen:
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Bestandsverzeichnis

Ein Verzeichnis der aufgelösten Laufzeit-Abhängigkeitskoordinaten ist enthalten in:

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

Diese Datei unterstützt die interne Prüfung transitiver Abhängigkeiten über die oben genannten Hauptfamilien hinaus.

## Konformitätshinweise

- Apache-2.0-Quellenangaben und Lizenzverweise in der verteilten App-Dokumentation zugänglich halten.
- Für Mapsforge (LGPL-Familie) Hinweise beibehalten und Pflichten des Vertriebsmodells prüfen.
- Für MapLibre Native (BSD 2-Clause) Urheberrechts-/Lizenzhinweis in der Dokumentation zur Binärverteilung beibehalten.
- Bei Abhängigkeitsaktualisierungen ursprüngliche Lizenzänderungen erneut prüfen.
