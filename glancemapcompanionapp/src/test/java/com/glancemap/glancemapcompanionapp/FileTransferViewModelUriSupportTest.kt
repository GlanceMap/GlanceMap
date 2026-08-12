package com.glancemap.glancemapcompanionapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferViewModelUriSupportTest {
    @Test
    fun `recognizes geojson file names`() {
        assertTrue(isGeoJsonFileName("refuges.geojson"))
        assertTrue(isGeoJsonFileName("refuges.geo.json"))
        assertTrue(isGeoJsonFileName("REFUGES.GEOJSON"))
        assertFalse(isGeoJsonFileName("refuges.json"))
        assertFalse(isGeoJsonFileName("refuges.gpx"))
    }

    @Test
    fun `recognizes common gpx mime aliases`() {
        assertTrue(isGpxMimeType("application/gpx+xml"))
        assertTrue(isGpxMimeType("application/x-gpx+xml"))
        assertTrue(isGpxMimeType("application/vnd.gpx+xml"))
        assertTrue(isGpxMimeType("application/gpx"))
        assertTrue(isGpxMimeType("application/x-gpx"))
        assertFalse(isGpxMimeType("application/octet-stream"))
        assertFalse(isGpxMimeType("application/xml"))
    }

    @Test
    fun `keeps normal gpx display name`() {
        assertEquals(
            "tour-du-lac.gpx",
            chooseGpxTransferFileName(
                displayName = "tour-du-lac.gpx",
                uriCandidates = emptyList(),
                gpxText = null,
                preferFallbackName = false,
            ),
        )
    }

    @Test
    fun `keeps a meaningful source name without a gpx extension ahead of waypoint metadata`() {
        assertEquals(
            "Rando RotWand all.gpx",
            chooseGpxTransferFileName(
                displayName = "Rando RotWand all",
                uriCandidates = emptyList(),
                gpxText =
                    "<gpx><metadata><desc>Imported</desc></metadata>" +
                        "<wpt><name>Guidepost</name></wpt><trk><name>Coordinates</name></trk></gpx>",
                preferFallbackName = false,
            ),
        )
    }

    @Test
    fun `prefers extensionless document filename over provider waypoint label`() {
        assertEquals(
            "Rando RotWand all.gpx",
            chooseGpxTransferFileName(
                displayName = "Guidepost",
                uriCandidates = listOf("primary:Download/Rando RotWand all"),
                gpxText = "<gpx><wpt><name>Guidepost</name></wpt></gpx>",
                preferFallbackName = false,
            ),
        )
    }

    @Test
    fun `does not replace display name with opaque provider id`() {
        assertEquals(
            "Rando RotWand all.gpx",
            chooseGpxTransferFileName(
                displayName = "Rando RotWand all",
                uriCandidates = listOf("c477ae71-30af-4dd6-b9b0-4657d728598a"),
                gpxText = "<gpx><wpt><name>Guidepost</name></wpt></gpx>",
                preferFallbackName = false,
            ),
        )
    }

    @Test
    fun `copies a source without an extension so the chosen gpx name reaches the watch`() {
        assertTrue(
            shouldCopyGpxToPreserveTransferName(
                sourceDisplayName = "Rando RotWand all",
                preferredName = "Rando RotWand all.gpx",
            ),
        )
        assertFalse(
            shouldCopyGpxToPreserveTransferName(
                sourceDisplayName = "Rando RotWand all.gpx",
                preferredName = "Rando RotWand all.gpx",
            ),
        )
    }

    @Test
    fun `recovers gpx file name from uri candidates`() {
        assertEquals(
            "Tour du lac.gpx",
            chooseGpxTransferFileName(
                displayName = "document",
                uriCandidates = listOf("primary:Download/Tour%20du%20lac.gpx"),
                gpxText = null,
                preferFallbackName = false,
            ),
        )
    }

    @Test
    fun `uses gpx metadata name for whatsapp generic display name`() {
        assertEquals(
            "Tour du lac.gpx",
            chooseGpxTransferFileName(
                displayName = "document.gpx",
                uriCandidates = emptyList(),
                gpxText = "<gpx><trk><name>Tour du lac</name><trkseg /></trk></gpx>",
                preferFallbackName = true,
            ),
        )
    }

    @Test
    fun `does not use a waypoint name when metadata has no title`() {
        assertEquals(
            "Rotwand route.gpx",
            chooseGpxTransferFileName(
                displayName = "document",
                uriCandidates = emptyList(),
                gpxText =
                    "<gpx><metadata><desc>Imported</desc></metadata>" +
                        "<wpt><name>Guidepost</name></wpt>" +
                        "<trk><name>Rotwand route</name><trkseg /></trk></gpx>",
                preferFallbackName = false,
            ),
        )
    }

    @Test
    fun `waypoint folder name follows resolved gpx filename`() {
        assertEquals("Tour_du_lac__waypoints.poi", suggestPoiFileNameForGpxWaypoints("Tour du lac.gpx"))
        assertEquals("Tour du lac", suggestPoiCategoryNameForGpx("Tour du lac.gpx"))
    }

    @Test
    fun `recognizes gpx document prefix with xml declaration`() {
        assertTrue(
            isLikelyGpxTextPrefix(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="WhatsApp">
                    <trk><name>Tour du lac</name></trk>
                </gpx>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `does not recognize non gpx xml prefix`() {
        assertFalse(
            isLikelyGpxTextPrefix(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml><Document /></kml>
                """.trimIndent(),
            ),
        )
    }
}
