package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.diagnostics.phoneDiagnosticsAdditionalSections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneOfflineMapRendererDiagnosticsTest {
    @After
    fun clearDiagnostics() {
        PhoneOfflineMapImportDiagnostics.clear()
        PhoneOfflineMapRendererDiagnostics.clear()
        PhoneDebugCapture.stop()
    }

    @Test
    fun readyRendererReportsTheAppliedThemeAndFirstCamera() {
        val attempt =
            PhoneOfflineMapRendererAttempt(
                outcome = PhoneOfflineMapRendererOutcome.READY,
                displayName = "alps.map",
                lastSuccessfulStage = PhoneOfflineMapRendererStage.READY,
                failureStage = null,
                themeId = "elevate",
                styleId = "elv-hiking",
                mapFileOpened = true,
                tileCacheCreated = true,
                tileLayerAttached = true,
                themeObjectConstructed = true,
                themeApplied = true,
                viewAttached = true,
                firstCameraPublished = true,
                boundingBoxAvailable = true,
                initialCameraInsideBounds = true,
                initialCameraFallbackUsed = false,
            )

        PhoneDebugCapture.start()
        PhoneOfflineMapRendererDiagnostics.record(attempt)

        val report = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
        assertTrue(report.contains("Result: READY"))
        assertTrue(report.contains("Last stage: READY"))
        assertTrue(report.contains("Theme: elevate / elv-hiking"))
        assertTrue(report.contains("First camera published: true"))
        assertTrue(PhoneDebugCapture.snapshot().single().contains("result=READY"))
    }

    @Test
    fun failuresKeepTheirExactStageAndSanitizedException() {
        listOf(
            PhoneOfflineMapRendererStage.TILE_CACHE_CREATE,
            PhoneOfflineMapRendererStage.THEME_APPLY,
            PhoneOfflineMapRendererStage.LAYER_ATTACH,
        ).forEach { stage ->
            val exception =
                IOException("Could not open content://media/external/42 from /storage/emulated/0/Maps/alps.map")
                    .toPhoneOfflineMapRendererException()
            val attempt =
                PhoneOfflineMapRendererAttempt(
                    outcome = PhoneOfflineMapRendererOutcome.FAILED,
                    displayName = "alps.map",
                    lastSuccessfulStage = PhoneOfflineMapRendererStage.MAPVIEW_CREATE,
                    failureStage = stage,
                    themeId = "elevate",
                    styleId = "elv-hiking",
                    exceptionClass = exception.className,
                    exceptionMessage = exception.message,
                )

            PhoneOfflineMapRendererDiagnostics.record(attempt)

            val report = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
            assertEquals(stage, requireNotNull(PhoneOfflineMapRendererDiagnostics.latestAttempt()).failureStage)
            assertTrue(report.contains("Failure stage: $stage"))
            assertFalse(report.contains("content://"))
            assertFalse(report.contains("/storage/"))
        }
    }

    @Test
    fun exportedDiagnosticsKeepBothImportAndRendererSections() {
        PhoneOfflineMapImportDiagnostics.record(
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.SUCCESS,
                failureStage = null,
                displayName = "alps.map",
                mimeType = "application/octet-stream",
                sourceSizeBytes = 1L,
                streamOpened = true,
                bytesCopied = 1L,
                destinationSizeBytes = 1L,
                candidateValid = true,
                mapFileOpened = true,
                metadata = null,
                finalError = null,
                exceptionClass = null,
                exceptionMessage = null,
            ),
        )
        PhoneOfflineMapRendererDiagnostics.record(
            PhoneOfflineMapRendererAttempt(
                outcome = PhoneOfflineMapRendererOutcome.READY,
                displayName = "alps.map",
                lastSuccessfulStage = PhoneOfflineMapRendererStage.READY,
                failureStage = null,
                themeId = "elevate",
                styleId = "elv-hiking",
            ),
        )

        val sections = phoneDiagnosticsAdditionalSections()

        assertEquals(2, sections.size)
        assertTrue(sections[0].startsWith("Latest offline map import"))
        assertTrue(sections[1].startsWith("Latest offline map renderer"))
    }
}
