# Avisos de terceros (código abierto)

Última revisión: 2026-05-06

Este documento cubre el software y los recursos de código abierto de la app del reloj (`app`) y la app del teléfono (`glancemapcompanionapp`).

## Componentes principales de código abierto

| Proyecto / familia | Componentes usados (ejemplos) | Licencia | Fuente |
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
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes`, etc.) | LGPL-3.0-or-later (con la exención de enlace estático y obras derivadas declarada por Mapsforge en el README) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Módulos incorporados `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` desde `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | Estilo BSD / muy permisiva (según el proyecto y los metadatos POM) | https://kobjects.org/kxml/ |
| Iconos Maki | Iconos SVG POI de Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Iconos Temaki | Iconos SVG POI de Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Dependencia de ejecución importante no abierta

- Los componentes Google Play services, por ejemplo `com.google.android.gms:play-services-wearable` y `play-services-location`, se distribuyen bajo condiciones de Google y del SDK Android, no licencias estándar de código abierto.
- Referencias de condiciones:
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Inventario

El inventario resuelto de coordenadas de dependencias de ejecución está en:

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

Este archivo facilita la revisión interna de dependencias transitivas, además de las familias principales anteriores.

## Notas de cumplimiento

- Mantener atribuciones Apache-2.0 y referencias de licencia en la documentación distribuida con la app.
- Para Mapsforge (familia LGPL), conservar los avisos y revisar las obligaciones de tu modelo de distribución.
- Para MapLibre Native (BSD 2-Clause), conservar el aviso de derechos y licencia en la documentación distribuida con los binarios.
- Revisar cambios de licencia originales al actualizar dependencias.
