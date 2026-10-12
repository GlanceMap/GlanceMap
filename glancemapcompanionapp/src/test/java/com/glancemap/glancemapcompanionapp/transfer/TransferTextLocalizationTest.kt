package com.glancemap.glancemapcompanionapp.transfer

import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.testsupport.englishCompanionText
import com.glancemap.glancemapcompanionapp.transfer.presentation.TransferTextFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTextLocalizationTest {
    @Test
    fun translatedLabelsPreserveReconnectClassificationAndFileName() {
        val presentation =
            TransferTextFormatter(::translatedText).buildNotificationPresentation(
                rawText = "File 1/1: tour.gpx\nWaiting for watch reconnect…",
                progress = 45,
                isPaused = true,
                showTechnicalDetails = false,
            )

        assertTrue(presentation.waitingForReconnect)
        assertEquals("Attente… 45%", presentation.title)
        assertEquals("Fichier 1/1: tour.gpx", presentation.contentText)
        assertEquals("Fichier 1/1: tour.gpx\nAttente…", presentation.expandedText)
    }

    @Test
    fun verificationAndPauseUseTranslatedStatusWithoutChangingRawProgressParsing() {
        val card =
            TransferTextFormatter(::translatedText).formatCardText(
                rawProgressText = "File 2/2: alps.map\nChecksum: 80%",
                statusMessage = "Validating checksum",
                isPaused = false,
                canResume = false,
                showTechnicalDetails = false,
            )
        assertEquals("Fichier 2/2: alps.map\nVérification…\nSomme: 80%", card)
        val paused =
            TransferTextFormatter(::translatedText).buildNotificationPresentation(
                rawText = "Paused by user",
                progress = -1,
                isPaused = true,
                showTechnicalDetails = false,
            )
        assertEquals("En pause", paused.title)
        assertFalse(paused.waitingForReconnect)
    }

    @Test
    fun diagnosticDetailsStayRawWhileNotificationTitleUsesResources() {
        val raw = "File 1/1: tour.gpx\nHTTP: waiting for watch reconnect"
        val presentation =
            TransferTextFormatter(::translatedText).buildNotificationPresentation(raw, -1, false, true)
        assertTrue(presentation.waitingForReconnect)
        assertEquals("Reconnexion à la montre…", presentation.title)
        assertEquals(raw, presentation.expandedText)
    }

    private fun translatedText(id: Int): String =
        when (id) {
            R.string.transfer_file_line -> "Fichier %1\$s"
            R.string.transfer_checksum_progress -> "Somme:%1\$s"
            else ->
                when (val value = englishCompanionText(id)) {
                    "Waiting…" -> "Attente…"
                    "Verifying…" -> "Vérification…"
                    "Paused" -> "En pause"
                    "Waiting for watch reconnect…" -> "Reconnexion à la montre…"
                    else -> value
                }
        }
}
