package com.glancemap.glancemapwearos.presentation.features.navigate

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.selectWakeAnchorSeed
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionAnchorOrigin
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionReading
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong

@RunWith(AndroidJUnit4::class)
class NavigateLocationContinuityInstrumentedTest {
    @Test
    fun wakeRestorationSelectsRetainedDisplayedBOverSameTimestampCachedA() {
        val viewModel = newNavigateViewModel()
        val fixA = LatLong(48.0, 11.0)
        val renderedB = LatLong(48.001, 11.001)

        viewModel.onAcceptedLocationUpdate(
            latLong = fixA,
            fixElapsedRealtimeMs = 100_000L,
            accuracyM = 4f,
            sourceEpoch = 7L,
            sourceModeName = "AUTO_FUSED",
        )
        viewModel.onRenderedLocationUpdate(renderedB)

        val selectedAnchor =
            requireNotNull(
                selectWakeAnchorSeed(
                    listOf(
                        wakeAnchor(
                            latLong = fixA,
                            origin = MarkerMotionAnchorOrigin.CACHED_LOCATION,
                            isAcceptedFix = false,
                        ),
                        wakeAnchor(
                            latLong = renderedB,
                            origin = MarkerMotionAnchorOrigin.RETAINED_VISUAL,
                            isAcceptedFix = true,
                        ),
                    ),
                ),
            )
        assertEquals(renderedB, selectedAnchor.latLong)

        viewModel.onDisplayedLocationAnchor(
            RetainedLocationAnchor(
                latLong = selectedAnchor.latLong,
                fixElapsedRealtimeMs = selectedAnchor.reading.fixElapsedMs,
                accuracyM = selectedAnchor.reading.accuracyM,
                sourceEpoch = 7L,
                sourceModeName = "AUTO_FUSED",
                isAcceptedFix = selectedAnchor.isAcceptedFix,
            ),
        )

        val anchor = requireNotNull(viewModel.uiState.value.retainedLocationAnchor)
        assertEquals(renderedB, anchor.latLong)
        assertEquals(100_000L, anchor.fixElapsedRealtimeMs)
        assertEquals(4f, anchor.accuracyM)
        assertEquals(7L, anchor.sourceEpoch)
        assertEquals("AUTO_FUSED", anchor.sourceModeName)
        assertEquals(true, anchor.isAcceptedFix)
    }

    @Test
    fun restoredPositionCancelsReadyFallbackBeforeMetadataCanCenterTheMap() {
        val viewModel = newNavigateViewModel()
        viewModel.onStartupMapFallbackEvent(StartupMapFallbackEvent.TIMER_EXPIRED)
        assertEquals(StartupMapFallbackState.READY, viewModel.uiState.value.startupMapFallbackState)

        viewModel.onDisplayedLocationAnchor(
            RetainedLocationAnchor(
                latLong = LatLong(48.0, 11.0),
                fixElapsedRealtimeMs = 100_000L,
                accuracyM = 5f,
                sourceEpoch = 1L,
                sourceModeName = "AUTO_FUSED",
                isAcceptedFix = false,
            ),
        )
        viewModel.onStartupMapFallbackEvent(StartupMapFallbackEvent.CENTERING_APPLIED)

        assertEquals(StartupMapFallbackState.CANCELLED, viewModel.uiState.value.startupMapFallbackState)
    }

    @Test
    fun productionMarkerBitmapsKeepHistoricalReplacementGreyBeforeAttachment() {
        val current =
            createNavigationMarkerBitmap(
                style = NavigationMarkerStyle.DOT,
                fillColor = NAVIGATION_MARKER_BLUE_ARGB,
            )
        val historical =
            createNavigationMarkerBitmap(
                style = NavigationMarkerStyle.DOT,
                fillColor = NAVIGATION_MARKER_HISTORICAL_ARGB,
            )

        assertSame(
            historical,
            navigationMarkerBitmapForTrustState(
                trustState = LocationMarkerTrustState.HISTORICAL,
                currentBitmap = current,
                historicalBitmap = historical,
            ),
        )
        assertEquals(NAVIGATION_MARKER_HISTORICAL_ARGB, historical.getPixel(12, 12))
    }

    private fun newNavigateViewModel(): NavigateViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return NavigateViewModel(context.applicationContext as Application)
    }

    private fun wakeAnchor(
        latLong: LatLong,
        origin: MarkerMotionAnchorOrigin,
        isAcceptedFix: Boolean,
    ): MarkerMotionSeed =
        MarkerMotionSeed(
            latLong = latLong,
            reading =
                MarkerMotionReading(
                    fixElapsedMs = 100_000L,
                    accuracyM = 4f,
                    speedMps = 0f,
                    bearingDeg = null,
                ),
            origin = origin,
            isAcceptedFix = isAcceptedFix,
        )
}
