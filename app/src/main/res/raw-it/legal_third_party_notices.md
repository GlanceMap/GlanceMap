# Avvisi di terze parti (open source)

Ultima revisione: 2026-05-06

Questo documento riguarda software e risorse open source usati dall'app orologio (`app`) e dall'app companion (`glancemapcompanionapp`).

## Componenti open source principali

| Progetto / famiglia | Componenti usati (esempi) | Licenza | Fonte |
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
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes`, ecc.) | LGPL-3.0-or-later (con deroga al collegamento statico/opere derivate dichiarata da Mapsforge nel README) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Moduli inclusi `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` da `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | Stile BSD / molto permissiva (secondo progetto e metadati POM) | https://kobjects.org/kxml/ |
| Icone Maki | Icone SVG POI da Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Icone Temaki | Icone SVG POI da Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Dipendenza d'esecuzione importante non open source

- I componenti Google Play services, ad esempio `com.google.android.gms:play-services-wearable` e `play-services-location`, sono distribuiti secondo condizioni Google/Android SDK, non licenze open source standard.
- Riferimenti alle condizioni:
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Inventario

L'inventario risolto delle coordinate delle dipendenze d'esecuzione è incluso in:

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

Questo file aiuta la revisione interna delle dipendenze transitive oltre alle famiglie principali sopra elencate.

## Note di conformità

- Mantenere attribuzioni Apache-2.0 e riferimenti licenza nella documentazione distribuita con l'app.
- Per Mapsforge (famiglia LGPL), conservare gli avvisi e rivedere gli obblighi del modello di distribuzione.
- Per MapLibre Native (BSD 2-Clause), conservare l'avviso copyright/licenza nella documentazione distribuita con i binari.
- Ricontrollare le modifiche alle licenze originali aggiornando le dipendenze.
