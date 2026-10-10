package com.glancemap.glancemapwearos.presentation.features.navigate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class NavigatePresentationStateTest {
    private val anchor = RetainedLocationAnchor(LatLong(45.0, 6.0), 1_000L, 8f, 1L, "WATCH_GPS")
    private val initial = NavigateUiState(retainedLocationAnchor = anchor)

    @Test
    fun coordinateOnlyRenderingDoesNotPublishAnotherScreenState() =
        runTest {
            val states = MutableStateFlow(initial)
            val presented = mutableListOf<NavigateUiState>()
            val collector =
                launch {
                    states.distinctUntilChanged(::sameNavigatePresentationState).collect(presented::add)
                }
            testScheduler.runCurrent()
            repeat(120) { index ->
                states.value =
                    initial.copy(retainedLocationAnchor = anchor.copy(latLong = LatLong(45.0 + index * 0.00001, 6.0)))
                testScheduler.runCurrent()
            }
            assertEquals(listOf(initial), presented)
            assertEquals(
                45.00119,
                states.value.retainedLocationAnchor
                    ?.latLong
                    ?.latitude ?: 0.0,
                0.000001,
            )
            val accepted = states.value.copy(retainedLocationAnchor = anchor.copy(fixElapsedRealtimeMs = 2_000L))
            states.value = accepted
            testScheduler.runCurrent()
            assertEquals(listOf(initial, accepted), presented)
            collector.cancel()
        }

    @Test
    fun everyTrustMetadataChangeStillInvalidatesPresentation() {
        listOf(
            null,
            anchor.copy(fixElapsedRealtimeMs = 2_000L),
            anchor.copy(accuracyM = 80f),
            anchor.copy(sourceEpoch = 2L),
            anchor.copy(sourceModeName = "PHONE_GPS"),
            anchor.copy(isAcceptedFix = false),
        ).forEach { changed ->
            assertFalse(sameNavigatePresentationState(initial, initial.copy(retainedLocationAnchor = changed)))
        }
        assertTrue(sameNavigatePresentationState(initial, initial))
        assertFalse(sameNavigatePresentationState(NavigateUiState(), initial))
    }

    @Test
    fun navigationChangesRemainVisible() {
        listOf(
            initial.copy(navMode = NavMode.PANNING),
            initial.copy(currentZoomLevel = 16),
            initial.copy(showCalibrationDialog = true),
            initial.copy(lastKnownLocation = LatLong(46.0, 6.0)),
            initial.copy(startupMapFallbackState = StartupMapFallbackState.CANCELLED),
        ).forEach { changed -> assertFalse(sameNavigatePresentationState(initial, changed)) }
    }
}
