package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import com.glancemap.trailcore.guidance.TrailGuidanceTuning
import com.glancemap.trailcore.guidance.computeTrailGuidanceState
import com.glancemap.trailcore.profile.TrailPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneGuidanceLocationFreshnessTest {
    @Test
    fun delayedButNewerFixCannotRestoreGuidanceAndFreshFixCan() {
        val lastAcceptedMillis = 80_000L
        val delayedFixMillis = 90_000L
        val nowMillis = 100_000L

        assertTrue(delayedFixMillis > lastAcceptedMillis)
        assertFalse(isPhoneGuidanceFixFresh(delayedFixMillis, nowMillis, 3_000L))
        assertTrue(isPhoneGuidanceFixFresh(nowMillis - 100L, nowMillis, 3_000L))
        assertTrue(isPhoneGuidanceFixFresh(nowMillis - 6_000L, nowMillis, 3_000L))
        assertFalse(isPhoneGuidanceFixFresh(nowMillis - 6_001L, nowMillis, 3_000L))
        assertFalse(isPhoneGuidanceFixFresh(nowMillis + 1L, nowMillis, 3_000L))
        assertFalse(isPhoneGuidanceFixFresh(null, nowMillis, 3_000L))
        assertFalse(isPhoneGuidanceFixFresh(0L, nowMillis, 3_000L))
    }

    @Test
    fun missingCallbacksClearTheStaleManeuverButKeepProgress() =
        runTest {
            val session =
                buildPhoneGuidanceSession(
                    PhoneGuidanceRouteDraft(
                        trackId = "freshness-test",
                        trackTitle = "Route",
                        points =
                            listOf(
                                TrailPoint(GeoPoint(46.0, 11.0), null),
                                TrailPoint(GeoPoint(46.0, 11.001), null),
                            ),
                        routeOffsetMeters = 0.0,
                        activityProfile = "HIKE",
                        etaConfig = TrailGuidanceEtaConfig(flatSpeedMps = 1.2),
                    ),
                    PhoneGuidanceProgressDraft(startReached = true),
                )
            val guidance =
                computeTrailGuidanceState(session, session.trackPoints.first().location, TrailGuidanceTuning())
                    .copy(offRoute = true)
            var ui = PhoneGuidanceUiState(session = session, guidance = guidance, message = PhoneGuidanceMessage.ACTIVE)
            schedulePhoneGuidanceLocationExpiry(
                scope = this,
                referenceElapsedRealtimeMillis = 100_000L,
                deliveryIntervalMillis = 3_000L,
                nowElapsedRealtimeMillis = { 100_000L + testScheduler.currentTime },
            ) {
                ui = ui.withLocationUnavailable(PhoneGuidanceMessage.GPS_UNAVAILABLE)
            }
            runCurrent()
            advanceTimeBy(6_000L)
            runCurrent()
            assertEquals(PhoneGuidanceMessage.ACTIVE, ui.message)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(PhoneGuidanceMessage.GPS_UNAVAILABLE, ui.message)
            assertEquals(TrailGuidanceMode.WAITING_FOR_LOCATION, ui.guidance?.mode)
            assertNull(ui.guidance?.nextInstruction)
            assertFalse(requireNotNull(ui.guidance).offRoute)
            assertEquals(session, ui.session)
            assertEquals(guidance.distanceFromStartMeters, ui.guidance?.distanceFromStartMeters)
        }

    @Test
    fun normalSparseFixesMoveTheDeadlineAndMissingFixesEventuallyExpire() =
        runTest {
            var expired = false
            var pending: Job? = null
            repeat(3) {
                pending?.cancel()
                pending =
                    schedulePhoneGuidanceLocationExpiry(
                        scope = this,
                        referenceElapsedRealtimeMillis = 100_000L + testScheduler.currentTime,
                        deliveryIntervalMillis = 10_000L,
                        nowElapsedRealtimeMillis = { 100_000L + testScheduler.currentTime },
                    ) { expired = true }
                runCurrent()
                advanceTimeBy(10_000L)
                runCurrent()
                assertFalse(expired)
            }
            advanceTimeBy(10_000L)
            runCurrent()
            assertFalse(expired)
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(expired)
        }

    @Test
    fun subscriptionChangesDoNotExtendAnOldFixAndCancelledExpiryCannotFire() =
        runTest {
            var expirations = 0

            fun schedule() =
                schedulePhoneGuidanceLocationExpiry(
                    scope = this,
                    referenceElapsedRealtimeMillis = 100_000L,
                    deliveryIntervalMillis = 3_000L,
                    nowElapsedRealtimeMillis = { 100_000L + testScheduler.currentTime },
                ) { expirations++ }

            val previous = schedule()
            runCurrent()
            advanceTimeBy(5_000L)
            previous.cancel()
            schedule()
            runCurrent()
            advanceTimeBy(1_001L)
            runCurrent()
            assertEquals(1, expirations)

            val stopped = schedule()
            stopped.cancel()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(1, expirations)
        }
}
