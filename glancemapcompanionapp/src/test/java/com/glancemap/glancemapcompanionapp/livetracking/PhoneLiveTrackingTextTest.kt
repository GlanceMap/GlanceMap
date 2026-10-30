package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneLiveTrackingTextTest {
    @Test
    fun translatesRetryErrorsWithoutChangingUnknownServerMessages() {
        val resources: (Int) -> String = { id ->
            when (id) {
                R.string.phone_tracking_stored_error -> "Stored: %1\$s"
                R.string.phone_arkluz_timeout -> "Localized timeout"
                R.string.phone_tracking_processing_error -> "Processing: %1\$s"
                R.string.phone_tracking_stopped_positions_retained -> "Retained: %1\$d"
                else -> error("Unexpected resource: $id")
            }
        }
        assertEquals(
            "Stored: Localized timeout",
            localizedLiveTrackingMessage("GPS stored for retry: Arkluz did not respond in time. Try again.", resources),
        )
        assertEquals("Server detail", localizedLiveTrackingMessage("Server detail", resources))
        assertEquals(
            "Processing: Localized timeout",
            localizedLiveTrackingMessage("Tracking data could not be processed: Arkluz did not respond in time. Try again.", resources),
        )
        assertEquals("Retained: 3", localizedLiveTrackingMessage("Stopped; 3 positions retained for retry", resources))
    }

    @Test
    fun mapsKnownStatusesWithoutAlteringTheInternalStatus() {
        assertEquals(R.string.tracking_notification_waiting_for_gps_fix, liveTrackingMessageResource("Waiting for GPS fix"))
        assertEquals(R.string.tracking_notification_waiting_for_gps_confirmation, liveTrackingMessageResource("Waiting for GPS confirmation"))
        assertEquals(R.string.tracking_notification_tracking_active, liveTrackingMessageResource("Tracking active"))
        assertNull(liveTrackingMessageResource("A message from the server"))
    }
}
