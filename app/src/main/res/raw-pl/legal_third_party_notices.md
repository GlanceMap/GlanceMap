# Informacje o stronach trzecich (Open Source)

Ostatni przegląd: 2026-05-06

Ten dokument obejmuje oprogramowanie i zasoby open source używane przez aplikację na zegarek (`app`) i aplikację towarzyszącą (`glancemapcompanionapp`).

## Główne składniki open source

| Projekt / rodzina | Używane artefakty (przykłady) | Licencja | Źródło |
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
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes` itd.) | LGPL-3.0-or-later (z deklarowanym w README projektu Mapsforge odstępstwem dotyczącym łączenia statycznego/utworów zależnych) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Dołączone moduły `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` z `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | Typu BSD / bardzo liberalna (według projektu i metadanych POM) | https://kobjects.org/kxml/ |
| Ikony Maki | Ikony POI SVG z Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Ikony Temaki | Ikony POI SVG z Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Ważna zależność czasu działania bez otwartej licencji

- Artefakty usług Google Play (np. `com.google.android.gms:play-services-wearable`, `play-services-location`) są rozpowszechniane na warunkach Google/Android SDK, a nie standardowych licencjach OSS.
- Odnośniki do warunków:
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Spis

Spis rozwiązanych współrzędnych zależności czasu działania znajduje się w:

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

Ten plik pomaga w wewnętrznym przeglądzie zależności przechodnich poza głównymi rodzinami wymienionymi wyżej.

## Uwagi dotyczące zgodności

- Zachowaj uznanie autorstwa Apache-2.0 i odnośniki licencyjne w dokumentacji rozpowszechnianej aplikacji.
- Dla Mapsforge (rodzina LGPL) zachowaj informacje i sprawdź obowiązki dla swojego modelu dystrybucji.
- Dla MapLibre Native (BSD 2-Clause) zachowaj informację o prawach autorskich/licencji w dokumentacji dystrybucji binarnej.
- Sprawdzaj zmiany oryginalnych licencji przy aktualizacji wersji zależności.
