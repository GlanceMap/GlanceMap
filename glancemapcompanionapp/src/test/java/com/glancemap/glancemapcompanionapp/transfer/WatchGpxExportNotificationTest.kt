package com.glancemap.glancemapcompanionapp.transfer

import com.glancemap.glancemapcompanionapp.testsupport.englishCompanionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchGpxExportNotificationTest {
    @Test
    fun `one imported route says where it went and keeps explicit actions`() {
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = "Tour",
                addedRoutes = 1,
                text = ::englishCompanionText,
            )

        assertEquals("Added to Routes. Tour", description.contentText)
        assertEquals("Added to Routes\nTour", description.expandedText)
        assertEquals("Save copy", description.saveCopyActionLabel)
        assertEquals("Open", description.openActionLabel)
        assertEquals("Share", description.shareActionLabel)
        assertFalse(description.contentText.contains("Downloads"))
    }

    @Test
    fun `several routes share one notification that counts them`() {
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = "Morning hike",
                addedRoutes = 3,
                text = ::englishCompanionText,
            )

        assertEquals("Routes added from your watch: 3. Morning hike", description.contentText)
    }

    @Test
    fun `failed import stays truthful about a received file`() {
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = "tour.gpx",
                addedRoutes = 0,
                text = ::englishCompanionText,
            )

        assertEquals("Received from watch. tour.gpx", description.contentText)
    }
}
