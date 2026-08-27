package com.glancemap.glancemapcompanionapp.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchGpxExportNotificationTest {
    @Test
    fun `saved success notification describes storage and keeps explicit actions`() {
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = "tour.gpx",
                downloadsUriAvailable = true,
            )

        assertEquals("Saved to Downloads/GlanceMap. tour.gpx", description.contentText)
        assertEquals("Saved to Downloads/GlanceMap\ntour.gpx", description.expandedText)
        assertEquals("Save copy", description.saveCopyActionLabel)
        assertEquals("Open", description.openActionLabel)
        assertEquals("Share", description.shareActionLabel)
        assertFalse(description.contentText.contains("Tap to save a copy"))
        assertTrue(description.contentText.contains("Downloads/GlanceMap"))
    }

    @Test
    fun `fallback success notification stays truthful when downloads copy is unavailable`() {
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = "tour.gpx",
                downloadsUriAvailable = false,
            )

        assertEquals("Received from watch. tour.gpx", description.contentText)
        assertFalse(description.contentText.contains("Downloads/GlanceMap"))
        assertFalse(description.contentText.contains("Tap to save a copy"))
    }
}
