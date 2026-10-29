# Mentions des tiers (open source)

Dernière révision : 2026-05-06

Ce document couvre les logiciels et ressources libres utilisés par l’appli montre (`app`) et le compagnon (`glancemapcompanionapp`).

## Principaux composants libres

| Projet / famille | Composants utilisés (exemples) | Licence | Source |
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
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes`, etc.) | LGPL-3.0-or-later (avec dérogation annoncée par Mapsforge pour liaison statique/œuvres dérivées dans le README) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Modules intégrés `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` de `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | De type BSD / très permissive (selon le projet et les métadonnées POM) | https://kobjects.org/kxml/ |
| Icônes Maki | Icônes POI SVG de Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Icônes Temaki | Icônes POI SVG de Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Dépendance d’exécution importante non libre

- Les composants Google Play services (par exemple `com.google.android.gms:play-services-wearable`, `play-services-location`) sont distribués selon les conditions Google/SDK Android, et non des licences libres classiques.
- Références :
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Inventaire

Un inventaire des coordonnées des dépendances d’exécution résolues figure dans :

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

Ce fichier aide à examiner les dépendances transitives au-delà des familles principales ci-dessus.

## Notes de conformité

- Garder les attributions Apache-2.0 et références de licence dans la documentation distribuée.
- Pour Mapsforge (famille LGPL), conserver les mentions et examiner les obligations du modèle de distribution.
- Pour MapLibre Native (BSD 2-Clause), conserver la mention de droits d’auteur/licence dans la documentation des binaires distribués.
- Revérifier les licences d’origine lors des mises à jour de dépendances.
