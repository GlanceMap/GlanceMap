package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.poi.PoiType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTerrainPreviewDataTest {
    private val camera = PhoneMapCameraSnapshot(46.1, 7.2, 13.0, 42f)

    @Test
    fun payloadKeepsSeparateTrackSegmentsAndSkipsGapConnectorsAndSinglePoints() {
        val first = GeoPoint(46.1, 7.2)
        val last = GeoPoint(46.2, 7.3)
        val segment = PhoneMapRouteSegment(listOf(first, last))
        val data =
            PhoneTerrainPreviewData(
                camera,
                PhoneOnlineMapSource.OPEN_TOPO,
                listOf(segment, segment.copy(isGap = true), segment.copy(points = listOf(first)), segment),
                location = first,
            )
        val json = JsonParser.parseString(data.toPreviewJson()).asJsonObject
        val features = json["features"].asJsonObject["features"].asJsonArray

        assertEquals(3, features.size())
        assertEquals("LineString", features[0].asJsonObject["geometry"].asJsonObject["type"].asString)
        assertEquals(7.2, json["center"].asJsonArray[0].asDouble, 0.0)
        assertEquals(42f, json["bearing"].asFloat, 0f)
        assertEquals("location", features[2].asJsonObject["properties"].asJsonObject["kind"].asString)
        assertEquals(2, segment.points.size)
    }

    @Test
    fun poiNamesAreEncodedAsDataAndNeverInsertedAsHtml() {
        val label = "</script><script>alert('route')</script>\u2028"
        val poi = PhoneMapPoi("id", "source", GeoPoint(46.1, 7.2), PoiType.GENERIC, label, null)
        val payload =
            PhoneTerrainPreviewData(camera, PhoneOnlineMapSource.OPEN_TOPO, emptyList(), listOf(poi))
                .toPreviewJson()

        assertFalse(payload.contains("</script>"))
        val feature =
            JsonParser
                .parseString(payload)
                .asJsonObject["features"]
                .asJsonObject["features"]
                .asJsonArray[0]
        assertEquals(label, feature.asJsonObject["properties"].asJsonObject["label"].asString)
    }

    @Test
    fun terrainStylesAreKeylessAndSatelliteUsesEsriWithTerrariumElevation() {
        val json =
            JsonParser
                .parseString(
                    PhoneTerrainPreviewData(
                        camera,
                        PhoneOnlineMapSource.ESRI_WORLD_IMAGERY,
                        emptyList(),
                    ).toPreviewJson(),
                ).asJsonObject
        val styles = json["styles"].asJsonObject
        val satellite = styles["SATELLITE"].asJsonObject

        assertEquals("SATELLITE", json["initialBasemap"].asString)
        assertEquals("https://styles.maptoolkit.org/hiking-3d.json", styles["HIKING"].asString)
        assertEquals("https://styles.maptoolkit.org/cycling-3d.json", styles["CYCLING"].asString)
        assertEquals("terrarium", satellite["sources"].asJsonObject["terrain"].asJsonObject["encoding"].asString)
        assertEquals(1, satellite["terrain"].asJsonObject["exaggeration"].asInt)
        assertTrue(
            satellite["sources"]
                .asJsonObject["imagery"]
                .asJsonObject["attribution"]
                .asString
                .contains("Esri"),
        )
        assertFalse(json.toString().contains("key="))
    }

    @Test
    fun initialCyclingSelectionAndPolarCameraAreHandledWithoutMutatingTheNativeCamera() {
        val polar = camera.copy(latitude = 90.0, zoom = 25.0)
        val data = PhoneTerrainPreviewData(polar, PhoneOnlineMapSource.MAPTOOLKIT_CYCLING, emptyList())
        val json = JsonParser.parseString(data.toPreviewJson()).asJsonObject

        assertEquals(PhoneTerrainPreviewBasemap.CYCLING, data.initialBasemap)
        assertEquals(85.05112878, json["center"].asJsonArray[1].asDouble, 0.0)
        assertEquals(18.0, json["zoom"].asDouble, 0.0)
        assertEquals(90.0, polar.latitude, 0.0)
        assertEquals(25.0, polar.zoom, 0.0)
    }

    @Test
    fun localResourcesAreLimitedToTheBundledPreviewFiles() {
        assertEquals("index.html", terrainPreviewAssetName(PHONE_TERRAIN_PREVIEW_URL))
        assertEquals(
            "maplibre-gl-csp-worker.js",
            terrainPreviewAssetName(
                PHONE_TERRAIN_PREVIEW_URL.replace("index.html", "maplibre-gl-csp-worker.js?v=5.24.0"),
            ),
        )
        listOf(
            "../private.properties",
            "%2e%2e/private.properties",
            "package.json",
            "maplibre-gl.mjs",
            "maplibre-gl-worker.mjs",
        ).forEach {
            assertNull(terrainPreviewAssetName(PHONE_TERRAIN_PREVIEW_URL.replace("index.html", it)))
        }
        assertNull(terrainPreviewAssetName(PHONE_TERRAIN_PREVIEW_URL.replace("https:", "file:")))
        assertNull(
            terrainPreviewAssetName(
                PHONE_TERRAIN_PREVIEW_URL.replace(
                    "appassets.androidplatform.net",
                    "appassets.androidplatform.net.evil.example",
                ),
            ),
        )
    }

    @Test
    fun previewResourcesCannotUsePlainHttpPrivateFilesOrUnexpectedHosts() {
        assertTrue(terrainPreviewAllowsRemoteResource("https://tiles.mapterhorn.com/12/2/3.webp"))
        assertTrue(terrainPreviewAllowsRemoteResource("https://server.arcgisonline.com/ArcGIS/rest/tile/1/2/3"))
        listOf(
            "http://tiles.mapterhorn.com/0/0/0.webp",
            "file:///secret",
            "content://provider/private",
            "https://tiles.mapterhorn.com.evil.example/tiles",
            "https://tiles.mapterhorn.com:9443/tiles",
            "https://user@tiles.mapterhorn.com/tiles",
            "https://evil.example/",
            "not a URL",
        ).forEach {
            assertFalse(it, terrainPreviewAllowsRemoteResource(it))
        }
    }
}
