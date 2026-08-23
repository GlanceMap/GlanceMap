package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressVisibilityTest {
    @Test
    fun `skipped bundle progress is hidden`() {
        val progress =
            OamDownloadProgress(
                phase = "SKIPPED",
                detail = "N45E006 DEM unavailable",
            )

        assertFalse(progress.shouldShowInBundleProgress())
    }

    @Test
    fun `download bundle progress is shown`() {
        val progress =
            OamDownloadProgress(
                phase = "DOWNLOADING",
                detail = "alps.map.zip",
            )

        assertTrue(progress.shouldShowInBundleProgress())
    }

    @Test
    fun `automatic reconnect progress remains visible`() {
        assertTrue(
            OamDownloadProgress(
                phase = "RECONNECTING",
                detail = "alps.map.zip reconnecting",
            ).shouldShowInBundleProgress(),
        )
    }
}
