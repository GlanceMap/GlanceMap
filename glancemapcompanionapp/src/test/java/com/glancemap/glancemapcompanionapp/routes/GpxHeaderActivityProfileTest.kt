package com.glancemap.glancemapcompanionapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GpxHeaderActivityProfileTest {
    @Test
    fun readsTheRecordedProfileFromTheMetadataHeader() {
        val gpx =
            gpxFile(
                "<metadata><extensions><gmap:activityType>recording</gmap:activityType>" +
                    "<gmap:activityProfile>BIKE</gmap:activityProfile></extensions></metadata>",
            )
        assertEquals("BIKE", gpxHeaderActivityProfile(gpx))
    }

    @Test
    fun plannedRoutesAndMissingFilesHaveNoProfile() {
        assertNull(gpxHeaderActivityProfile(gpxFile("<metadata><name>Planned</name></metadata>")))
        assertNull(gpxHeaderActivityProfile(File("missing-route.gpx")))
    }

    private fun gpxFile(metadata: String): File =
        File.createTempFile("route", ".gpx").apply {
            deleteOnExit()
            writeText(
                "<?xml version=\"1.0\"?><gpx xmlns:gmap=\"https://glancemap.app/gpx/extensions/1\">" +
                    "$metadata<trk><trkseg></trkseg></trk></gpx>",
            )
        }
}
