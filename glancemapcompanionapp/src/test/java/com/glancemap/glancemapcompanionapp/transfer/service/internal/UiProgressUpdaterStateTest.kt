package com.glancemap.glancemapcompanionapp.transfer.service.internal

import com.glancemap.glancemapcompanionapp.FileTransferUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiProgressUpdaterStateTest {
    @Test
    fun `active HTTP progress clears an automatic reconnect pause`() {
        val state = FileTransferUiState(isPaused = true, canResume = false)

        assertTrue(clearsReconnectPauseOnActiveTransferProgress(state, isActiveTransferProgress = true))
    }

    @Test
    fun `active HTTP progress does not clear a user pause`() {
        val state = FileTransferUiState(isPaused = true, canResume = true)

        assertFalse(clearsReconnectPauseOnActiveTransferProgress(state, isActiveTransferProgress = true))
    }

    @Test
    fun `waiting for the HTTP request does not clear a reconnect pause`() {
        val state = FileTransferUiState(isPaused = true, canResume = false)

        assertFalse(clearsReconnectPauseOnActiveTransferProgress(state, isActiveTransferProgress = false))
    }
}
