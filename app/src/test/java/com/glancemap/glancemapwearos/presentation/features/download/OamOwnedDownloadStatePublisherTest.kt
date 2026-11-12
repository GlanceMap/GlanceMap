package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OamOwnedDownloadStatePublisherTest {
    @Test
    fun `progress published after extraction starts retains extraction state`() {
        var state = OamOwnedDownloadState(status = OamOwnedDownloadStatus.RUNNING)
        val publisher = OamOwnedDownloadStatePublisher(currentState = { state }, publish = { state = it })
        val staleProgress = state.copy(phase = "DOWNLOADING", isExtracting = false)

        publisher.updateExtractionState(isExtracting = true)
        publisher.publishProgress(staleProgress)

        assertTrue(state.isExtracting)
    }

    @Test
    fun `progress published after extraction ends clears stale extraction state`() {
        var state = OamOwnedDownloadState(status = OamOwnedDownloadStatus.RUNNING, isExtracting = true)
        val publisher = OamOwnedDownloadStatePublisher(currentState = { state }, publish = { state = it })
        val staleProgress = state.copy(phase = "DOWNLOADING", isExtracting = true)

        publisher.updateExtractionState(isExtracting = false)
        publisher.publishProgress(staleProgress)

        assertFalse(state.isExtracting)
    }
}
