package com.glancemap.glancemapcompanionapp.livetracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTrackingRecoveryPersistenceTest {
    // Use the test package's preferences, never an installed user's tracking state.
    private val context = InstrumentationRegistry.getInstrumentation().context

    @After
    fun clearTestSession() {
        LiveTrackingActiveSessionStore.clear(context)
    }

    @Test
    fun pausedSessionRestoresTheControlPositionAndPendingControls() {
        val session = session(isStopping = false)
        LiveTrackingActiveSessionStore.save(context, session)
        val restored = requireNotNull(LiveTrackingActiveSessionStore.load(context))
        assertEquals(session, restored)
        assertTrue(restored.resumePending)
        assertTrue(restored.pausePending)
        assertEquals(LiveTrackingRecoveryMode.PAUSED, liveTrackingRecoveryMode(restored.isPaused, restored.isStopping))
    }

    @Test
    fun stoppingSessionRestoresAsStoppingWithItsLastPosition() {
        val session = session(isStopping = true)
        LiveTrackingActiveSessionStore.save(context, session)
        val restored = requireNotNull(LiveTrackingActiveSessionStore.load(context))
        assertEquals(session.lastPosition, restored.lastPosition)
        assertEquals(LiveTrackingRecoveryMode.STOPPING, liveTrackingRecoveryMode(restored.isPaused, restored.isStopping))
    }

    @Test
    fun pauseRequestedBeforeControlQueueInsertionRestoresAsPaused() {
        val session = session(isStopping = false).copy(isPaused = false, resumePending = false)
        LiveTrackingActiveSessionStore.save(context, session)
        val restored = requireNotNull(LiveTrackingActiveSessionStore.load(context))
        assertEquals(
            LiveTrackingRecoveryMode.PAUSED,
            liveTrackingRecoveryMode(restored.isPaused, restored.isStopping, restored.pausePending),
        )
    }

    private fun session(isStopping: Boolean): LiveTrackingActiveSessionStore.Session {
        val settings =
            LiveTrackingSettings(
                trackingUrl = "https://example.invalid/trk",
                group = "test-group",
                participantPassword = "placeholder",
                followerPassword = "placeholder",
                userName = "test-user",
                notificationEmails = "",
                alertEmails = "",
                stuckAlarmMinutes = "10",
                comments = "",
                gpxUri = null,
                gpxName = "",
            )
        val point =
            ArkluzLocationUpdate(
                trackingUrl = settings.trackingUrl,
                latitude = 1.0,
                longitude = 2.0,
                altitudeMeters = null,
                speedMetersPerSecond = null,
                accuracyMeters = 10f,
                epochMilliseconds = 1_790_000_000_000L,
                batteryPercent = 80,
                gsmSignalPercent = 100,
                group = settings.group,
                participantPassword = settings.participantPassword,
                userName = settings.userName,
                notificationEmails = "",
                alertEmails = "",
                stuckAlarmMinutes = "10",
                start = false,
                stop = false,
                pointId = "test-point-id",
            )
        return LiveTrackingActiveSessionStore.Session(
            settings,
            isPaused = true,
            sentStart = true,
            dateId = "session-1",
            isStopping = isStopping,
            resumePending = true,
            pausePending = true,
            lastPosition = point,
            savedAtEpochMilliseconds = 1_790_000_000_001L,
        )
    }
}
