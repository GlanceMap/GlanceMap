package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.net.URI

internal const val PHONE_TERRAIN_PREVIEW_URL =
    "https://appassets.androidplatform.net/assets/terrain-preview/index.html"

internal enum class PhoneTerrainPreviewStatus {
    LOADING,
    READY,
    ERROR,
    UNSUPPORTED,
    ;

    val isFailure: Boolean get() = this == ERROR || this == UNSUPPORTED
}

internal enum class PhoneTerrainPreviewBasemap { HIKING, CYCLING, SATELLITE }

/** A display-only snapshot; exploring the preview never publishes a camera or navigation command. */
internal data class PhoneTerrainPreviewData(
    val camera: PhoneMapCameraSnapshot,
    val source: PhoneOnlineMapSource,
    val tracks: List<PhoneMapRouteSegment>,
    val pois: List<PhoneMapPoi> = emptyList(),
    val location: GeoPoint? = null,
) {
    val initialBasemap: PhoneTerrainPreviewBasemap
        get() =
            when (source) {
                PhoneOnlineMapSource.MAPTOOLKIT_CYCLING -> PhoneTerrainPreviewBasemap.CYCLING
                PhoneOnlineMapSource.ESRI_WORLD_IMAGERY -> PhoneTerrainPreviewBasemap.SATELLITE
                else -> PhoneTerrainPreviewBasemap.HIKING
            }

    fun toPreviewJson(): String {
        val features = JsonArray()
        tracks.filter { !it.isGap && it.points.size >= 2 }.forEach { segment ->
            features.add(
                terrainFeature(
                    "track",
                    "LineString",
                    JsonArray().apply {
                        segment.points.forEach { add(it.terrainCoordinate()) }
                    },
                ),
            )
        }
        pois.forEach { poi ->
            features.add(terrainFeature("poi", "Point", poi.location.terrainCoordinate(), poi.label))
        }
        location?.let { features.add(terrainFeature("location", "Point", it.terrainCoordinate())) }
        return Gson().toJson(
            JsonObject().apply {
                add("center", GeoPoint(camera.latitude, camera.longitude).terrainCoordinate())
                addProperty("zoom", camera.zoom.coerceIn(2.0, 18.0))
                addProperty("bearing", camera.bearingDegrees)
                addProperty("initialBasemap", initialBasemap.name)
                add("styles", terrainPreviewStyles())
                add(
                    "features",
                    JsonObject().apply {
                        addProperty("type", "FeatureCollection")
                        add("features", features)
                    },
                )
            },
        )
    }
}

private fun GeoPoint.terrainCoordinate() =
    JsonArray().apply {
        add(longitude)
        add(latitude.coerceIn(-85.05112878, 85.05112878))
    }

private fun terrainFeature(
    kind: String,
    type: String,
    coordinates: JsonArray,
    label: String? = null,
) = JsonObject().apply {
    addProperty("type", "Feature")
    add(
        "geometry",
        JsonObject().apply {
            addProperty("type", type)
            add("coordinates", coordinates)
        },
    )
    add(
        "properties",
        JsonObject().apply {
            addProperty("kind", kind)
            addProperty("label", label.orEmpty())
        },
    )
}

private fun terrainPreviewStyles(): JsonObject {
    val satellite =
        requireNotNull(PhoneMapRendererCatalog.providerForOnlineSource(PhoneOnlineMapSource.ESRI_WORLD_IMAGERY))
    return JsonObject().apply {
        add("HIKING", JsonPrimitive("https://styles.maptoolkit.org/hiking-3d.json"))
        add("CYCLING", JsonPrimitive("https://styles.maptoolkit.org/cycling-3d.json"))
        add(
            "SATELLITE",
            JsonObject().apply {
                addProperty("version", 8)
                add(
                    "sources",
                    JsonObject().apply {
                        add(
                            "imagery",
                            JsonObject().apply {
                                addProperty("type", "raster")
                                add("tiles", JsonArray().apply { add(satellite.rasterTileUrlTemplate) })
                                addProperty("tileSize", 256)
                                addProperty("maxzoom", satellite.maximumZoom)
                                addProperty("attribution", satellite.attribution)
                            },
                        )
                        add(
                            "terrain",
                            JsonObject().apply {
                                addProperty("type", "raster-dem")
                                addProperty("url", "https://tiles.mapterhorn.com/tilejson.json")
                                addProperty("encoding", "terrarium")
                            },
                        )
                    },
                )
                add(
                    "layers",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("id", "imagery")
                                addProperty("type", "raster")
                                addProperty("source", "imagery")
                            },
                        )
                    },
                )
                add(
                    "terrain",
                    JsonObject().apply {
                        addProperty("source", "terrain")
                        addProperty("exaggeration", 1)
                    },
                )
            },
        )
    }
}

internal fun terrainPreviewAssetName(url: String): String? =
    runCatching { URI(url) }
        .getOrNull()
        ?.takeIf {
            it.scheme == "https" && it.host == "appassets.androidplatform.net" && it.port == -1 && it.userInfo == null
        }?.path
        ?.removePrefix("/assets/terrain-preview/")
        ?.takeIf {
            it in
                setOf(
                    "index.html",
                    "terrain-preview.js",
                    "terrain-preview-bootstrap.js",
                    "terrain-preview.css",
                    "maplibre-gl-csp.js",
                    "maplibre-gl-csp-worker.js",
                    "maplibre-gl.css",
                )
        }

internal fun terrainPreviewAllowsRemoteResource(url: String): Boolean =
    runCatching { URI(url) }.getOrNull()?.let {
        it.scheme == "https" &&
            it.port == -1 &&
            it.userInfo == null &&
            it.host in
            setOf(
                "styles.maptoolkit.org",
                "tiles.maptoolkit.org",
                "fonts.maptoolkit.org",
                "icons.maptoolkit.org",
                "tiles.mapterhorn.com",
                "server.arcgisonline.com",
            )
    } ?: false
