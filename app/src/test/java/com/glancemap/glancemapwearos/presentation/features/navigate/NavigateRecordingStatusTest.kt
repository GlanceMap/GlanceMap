package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.recording.RECORDING_START_PENDING_MESSAGE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateRecordingStatusTest {
    @Test
    fun `pending REC start status remains visible until the start flow clears it`() {
        assertTrue(isPersistentRecordingStatusMessage(RECORDING_START_PENDING_MESSAGE))
    }

    @Test
    fun `ordinary recording statuses remain transient`() {
        assertFalse(isPersistentRecordingStatusMessage("REC paused"))
    }
}
