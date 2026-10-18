package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

@OptIn(ExperimentalCoroutinesApi::class)
class StartupLocationWaitMessageTest {
    private val startup =
        OnlineStartupMapFallbackInput(
            offlineMode = false,
            shouldTrackLocation = true,
            locationMarkerLatLong = null,
            retainedLocationAnchor = null,
            lastKnownLocation = null,
            startupMapFallbackState = StartupMapFallbackState.WAITING,
            navigateTarget = null,
            pendingPoiFocusTarget = null,
        )

    @Test
    fun slowFirstFixShowsAfterFiveSecondsAndCannotShowAgainAfterLeaving() =
        runTest {
            val message = StartupLocationWaitMessage()
            val changes = mutableListOf<Boolean>()
            val firstVisit = launch { message.showOnce(changes::add) }
            runCurrent()
            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals(emptyList<Boolean>(), changes)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(true), changes)

            firstVisit.cancelAndJoin()
            assertEquals(listOf(true, false), changes)
            message.showOnce(changes::add)
            assertEquals(listOf(true, false), changes)
        }

    @Test
    fun fastFixOrEarlyMenuVisitCancelsTheDelayPermanently() =
        runTest {
            val message = StartupLocationWaitMessage()
            val changes = mutableListOf<Boolean>()
            val firstVisit = launch { message.showOnce(changes::add) }
            runCurrent()
            advanceTimeBy(2_000L)
            firstVisit.cancelAndJoin()
            message.showOnce(changes::add)
            advanceTimeBy(10_000L)
            assertEquals(listOf(false), changes)
        }

    @Test
    fun leavingBeforeGpsStartsAlsoPreventsLaterMessages() =
        runTest {
            val message = StartupLocationWaitMessage()
            message.dismiss()
            val changes = mutableListOf<Boolean>()
            message.showOnce(changes::add)
            assertTrue(changes.isEmpty())
        }

    @Test
    fun waitingContinuesUntilFallbackCenteringActuallyCompletes() {
        assertTrue(eligible())
        assertTrue(eligible(startup.copy(startupMapFallbackState = StartupMapFallbackState.READY)))
        assertFalse(eligible(startup.copy(startupMapFallbackState = StartupMapFallbackState.COMPLETED)))
        assertFalse(eligible(startup.copy(startupMapFallbackState = StartupMapFallbackState.CANCELLED)))
    }

    @Test
    fun existingPositionOrViewportNeedsNoWaitingMessage() {
        val position = LatLong(45.0, 6.0)
        assertFalse(eligible(startup.copy(locationMarkerLatLong = position)))
        assertFalse(eligible(startup.copy(lastKnownLocation = position)))
        assertFalse(
            eligible(startup.copy(retainedLocationAnchor = RetainedLocationAnchor(position, 1_000L, 8f, 1L))),
        )
        assertFalse(eligible(hasRestoredViewport = true))
    }

    @Test
    fun offlineStoppedTrackingPanningAndPoiFocusStaySilent() {
        assertFalse(eligible(startup.copy(offlineMode = true)))
        assertFalse(eligible(startup.copy(shouldTrackLocation = false)))
        assertFalse(eligible(startup.copy(shouldFollowPosition = false)))
        assertFalse(eligible(isScreenInteractive = false))
        val target = PoiNavigateTarget(lat = 45.0, lon = 6.0, label = "test", type = PoiType.GENERIC)
        assertFalse(eligible(startup.copy(navigateTarget = target)))
        assertFalse(eligible(startup.copy(pendingPoiFocusTarget = target)))
    }

    @Test
    fun disabledOrUnavailableGpsIsNotReportedAsSearching() {
        assertFalse(eligible(warning = GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED))
        assertFalse(eligible(warning = GpsEnvironmentWarning.WATCH_GPS_UNAVAILABLE))
        assertFalse(eligible(warning = GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_NO_WATCH_GPS))
        assertTrue(eligible(warning = GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_USING_WATCH_GPS))
    }

    private fun eligible(
        input: OnlineStartupMapFallbackInput = startup,
        isScreenInteractive: Boolean = true,
        hasRestoredViewport: Boolean = false,
        warning: GpsEnvironmentWarning = GpsEnvironmentWarning.NONE,
    ): Boolean = shouldWaitForStartupLocation(input, isScreenInteractive, hasRestoredViewport, warning)
}
