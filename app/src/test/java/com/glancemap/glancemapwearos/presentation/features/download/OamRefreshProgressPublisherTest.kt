package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OamRefreshProgressPublisherTest {
    @Test
    fun `refresh progress retains the latest extraction state in its notification`() {
        val state = MutableStateFlow(DownloadUiState())
        val notifications = mutableListOf<OamForegroundProgress>()
        val publisher = OamRefreshProgressPublisher(state) { progress -> notifications.add(progress) }

        publisher.publishExtractionState(
            isExtracting = true,
            title = "Refreshing",
            fallbackDetail = "Map",
        )
        publisher.publishProgress(
            progress = OamDownloadProgress("DOWNLOADING", "POI", 50L, 100L),
            title = "Refreshing",
            detail = "POI",
        )

        assertTrue(notifications.last().isExtracting)

        publisher.publishExtractionState(
            isExtracting = false,
            title = "Refreshing",
            fallbackDetail = "POI",
        )
        publisher.publishProgress(
            progress = OamDownloadProgress("DOWNLOADING", "DEM", 75L, 100L),
            title = "Refreshing",
            detail = "DEM",
        )

        assertFalse(notifications.last().isExtracting)
    }
}
