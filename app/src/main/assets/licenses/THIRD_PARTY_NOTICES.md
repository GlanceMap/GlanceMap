# Third-Party Notices (Open Source)

Last reviewed: 2026-05-06

This document covers open-source software and assets used by the watch app (`app`) and companion app (`glancemapcompanionapp`).

## Core open-source components

| Project / family | Used artifacts (examples) | License | Source |
|---|---|---|---|
| AndroidX + Jetpack Compose + Wear Compose | `androidx.*` (Compose UI/Foundation/Material, Navigation, Lifecycle, DataStore, Wear) | Apache License 2.0 | https://github.com/androidx/androidx |
| Kotlin | `org.jetbrains.kotlin:*` | Apache License 2.0 | https://github.com/JetBrains/kotlin |
| Kotlin Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | Apache License 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Ktor | `io.ktor:*` | Apache License 2.0 | https://github.com/ktorio/ktor |
| OkHttp | `com.squareup.okhttp3:okhttp*` | Apache License 2.0 | https://github.com/square/okhttp |
| Okio | `com.squareup.okio:okio*` | Apache License 2.0 | https://github.com/square/okio |
| Gson | `com.google.code.gson:gson` | Apache License 2.0 | https://github.com/google/gson |
| Accompanist | `com.google.accompanist:*` | Apache License 2.0 | https://github.com/google/accompanist |
| Horologist | `com.google.android.horologist:*` | Apache License 2.0 | https://github.com/google/horologist |
| AndroidSVG | `com.caverock:androidsvg` | Apache License 2.0 | https://github.com/BigBadaboom/androidsvg |
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes`, etc.) | LGPL-3.0-or-later (with Mapsforge stated static-linking/derivative waiver in project README) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Vendored modules `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` from `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | BSD-style / very permissive (per project and POM metadata) | https://kobjects.org/kxml/ |
| Maki icons | SVG POI icons from Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Temaki icons | SVG POI icons from Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Important non-open-source runtime dependency

- Google Play services artifacts (for example `com.google.android.gms:play-services-wearable`, `play-services-location`) are distributed under Google/Android SDK terms, not standard OSS licenses.
- Terms references:
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Inventory

A resolved runtime coordinate inventory is included in:

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

This file helps internal review of transitive dependencies beyond the main families listed above.

## Compliance notes

- Keep Apache-2.0 attributions and license references available in distributed app documentation.
- For Mapsforge (LGPL family), keep notices and review obligations for your distribution model.
- For MapLibre Native (BSD 2-Clause), keep the copyright/license notice available with binary distribution documentation.
- Re-check upstream license changes when upgrading dependency versions.
