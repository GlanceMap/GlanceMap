package com.glancemap.glancemapcompanionapp.transfer

import com.glancemap.glancemapcompanionapp.testsupport.englishCompanionText
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
                text = ::englishCompanionText,
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
                text = ::englishCompanionText,
            )

        assertEquals("Received from watch. tour.gpx", description.contentText)
        assertFalse(description.contentText.contains("Downloads/GlanceMap"))
        assertFalse(description.contentText.contains("Tap to save a copy"))
    }

    @Test
    fun translatedReceiptCanReorderTheFilenameAndRetainsAllActions() {
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = "tour.gpx",
                downloadsUriAvailable = false,
                text = { id ->
                    when (englishCompanionText(id)) {
                        "Received from watch" -> "Reçu de la montre"
                        "%1\$s. %2\$s" -> "%2\$s — %1\$s"
                        "Save copy" -> "Enregistrer une copie"
                        "Open" -> "Ouvrir"
                        "Share" -> "Partager"
                        else -> englishCompanionText(id)
                    }
                },
            )
        assertEquals("tour.gpx — Reçu de la montre", description.contentText)
        assertEquals("Enregistrer une copie", description.saveCopyActionLabel)
        assertEquals("Ouvrir", description.openActionLabel)
        assertEquals("Partager", description.shareActionLabel)
    }
}
