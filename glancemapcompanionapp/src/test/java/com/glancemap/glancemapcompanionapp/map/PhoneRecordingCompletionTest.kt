package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import com.glancemap.trailcore.recording.RecordingSessionLifecycleState
import com.glancemap.trailcore.recording.RecordingSessionPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhoneRecordingCompletionTest {
    private val original = PhoneRecordingSessionStore.state.value

    @After fun restoreState() = PhoneRecordingSessionStore.publish(original)

    @Test
    fun dismissalAcknowledgesOnlyTheDisplayedRecording() {
        val current =
            PhoneRecordingUiState(
                message = PhoneRecordingMessage.SAVED,
                completedRecording = PhoneCompletedRecording("new", "Recording", RecordedActivityStatistics()),
            )
        PhoneRecordingSessionStore.publish(current)
        PhoneRecordingSessionStore.dismissCompletedRecording("old")
        assertSame(current, PhoneRecordingSessionStore.state.value)
        PhoneRecordingSessionStore.dismissCompletedRecording("new")
        assertNull(PhoneRecordingSessionStore.state.value.completedRecording)
        assertEquals(PhoneRecordingMessage.SAVED, PhoneRecordingSessionStore.state.value.message)
    }

    @Test
    fun aLateDismissalCannotChangeAnActiveSession() {
        val current =
            PhoneRecordingUiState(
                session = RecordingSessionLifecycleState(generation = 3, phase = RecordingSessionPhase.RECORDING),
                distanceMeters = 123.0,
            )
        PhoneRecordingSessionStore.publish(current)
        PhoneRecordingSessionStore.dismissCompletedRecording("old")
        assertSame(current, PhoneRecordingSessionStore.state.value)
    }
}
