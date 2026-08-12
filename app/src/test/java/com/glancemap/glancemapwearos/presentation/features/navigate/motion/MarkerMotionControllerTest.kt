package com.glancemap.glancemapwearos.presentation.features.navigate.motion

import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.moveLatLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Suppress("LargeClass")
class MarkerMotionControllerTest {
    @Before
    fun setUpTelemetry() {
        MarkerMotionTelemetry.setCollectionEnabledForTests(true)
        MarkerMotionTelemetry.clear()
    }

    @After
    fun tearDownTelemetry() {
        MarkerMotionTelemetry.clear()
        MarkerMotionTelemetry.setCollectionEnabledForTests(false)
    }

    @Test
    fun predictionCanRenderOnFirstRegularTickAfterFix() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 8f,
            rawSpeedMps = 1f,
            rawBearingDeg = 90f,
        )

        val guarded =
            controller.predict(
                nowElapsedMs = 10_050L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base
        val firstRegularTick =
            controller.predict(
                nowElapsedMs = 10_250L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, guarded) < 0.01f)
        assertTrue(distanceMeters(base, firstRegularTick) > 0.15f)
        assertTrue(shouldRenderMarkerMotion(base, firstRegularTick))
    }

    @Test
    fun renderThresholdIsDirectionIndependent() {
        val base = LatLong(48.8566, 2.3522)

        assertFalse(
            shouldRenderMarkerMotion(
                previous = base,
                candidate = moveLatLong(base, bearing = 0f, distanceMeters = 0.11f),
            ),
        )
        assertFalse(
            shouldRenderMarkerMotion(
                previous = base,
                candidate = moveLatLong(base, bearing = 90f, distanceMeters = 0.11f),
            ),
        )
        assertTrue(
            shouldRenderMarkerMotion(
                previous = base,
                candidate = moveLatLong(base, bearing = 0f, distanceMeters = 0.13f),
            ),
        )
        assertTrue(
            shouldRenderMarkerMotion(
                previous = base,
                candidate = moveLatLong(base, bearing = 90f, distanceMeters = 0.13f),
            ),
        )
    }

    @Test
    fun serviceVelocityIsUsedWithoutASecondUiSmoothingPass() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 8f,
            rawSpeedMps = 1f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 11_000L,
            fixElapsedMs = 11_000L,
            accuracyM = 8f,
            rawSpeedMps = 2f,
            rawBearingDeg = 90f,
        )
        val acceleratedSpeed = MarkerMotionTelemetry.latestSnapshot().speedMps ?: 0f

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 12_000L,
            fixElapsedMs = 12_000L,
            accuracyM = 8f,
            rawSpeedMps = 1f,
            rawBearingDeg = 90f,
        )
        val deceleratedSpeed = MarkerMotionTelemetry.latestSnapshot().speedMps ?: 0f

        assertEquals(2f, acceleratedSpeed, 0.01f)
        assertEquals(1f, deceleratedSpeed, 0.01f)
    }

    @Test
    fun telemetryAggregatesFirstRenderDelayAndActiveRenderIntervals() {
        MarkerMotionTelemetry.clear()

        MarkerMotionTelemetry.recordFixAwaitingFirstRender(nowElapsedMs = 10_000L)
        MarkerMotionTelemetry.recordMotionRendered(nowElapsedMs = 10_250L)
        MarkerMotionTelemetry.recordMotionRendered(nowElapsedMs = 10_500L)
        MarkerMotionTelemetry.recordMotionRendered(nowElapsedMs = 10_750L)
        MarkerMotionTelemetry.recordMotionRendered(nowElapsedMs = 30_000L)

        val summary = MarkerMotionTelemetry.summary()
        assertEquals(1, summary.firstRenderDelaySamples)
        assertEquals(250L, summary.firstRenderDelayMeanMs)
        assertEquals(250L, summary.firstRenderDelayMaxMs)
        assertEquals(2, summary.activeRenderIntervalSamples)
        assertEquals(250L, summary.activeRenderIntervalMeanMs)
        assertEquals(250L, summary.activeRenderIntervalMaxMs)
        assertEquals(4, summary.renderedMotionUpdates)
    }

    @Test
    fun predictsForwardFromDerivedMotionWhenRawSpeedIsMissing() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val walkingFix = moveLatLong(base, bearing = 90f, distanceMeters = 3f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 8f,
            rawSpeedMps = null,
            rawBearingDeg = null,
        )
        controller.onGpsFix(
            latLong = walkingFix,
            nowElapsedMs = 13_000L,
            fixElapsedMs = 13_000L,
            accuracyM = 8f,
            rawSpeedMps = null,
            rawBearingDeg = null,
        )
        val predicted =
            controller.predict(
                nowElapsedMs = 14_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: walkingFix

        assertTrue(distanceMeters(walkingFix, predicted) > 0.4f)
        assertTrue(predicted.longitude > base.longitude)
        assertEquals(MarkerMotionMode.BLEND, MarkerMotionTelemetry.latestSnapshot().mode)
    }

    @Test
    fun acceptedFixExposesResolvedDerivedCourseForNavigationRotation() {
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val east = moveLatLong(base, bearing = 90f, distanceMeters = 12f)

        controller.onGpsFix(
            MarkerMotionGpsFix(
                latLong = base,
                nowElapsedMs = 10_000L,
                reading =
                    MarkerMotionReading(
                        fixElapsedMs = 10_000L,
                        accuracyM = 6f,
                        speedMps = null,
                        bearingDeg = null,
                    ),
            ),
        )
        val update =
            controller.onGpsFix(
                MarkerMotionGpsFix(
                    latLong = east,
                    nowElapsedMs = 20_000L,
                    reading =
                        MarkerMotionReading(
                            fixElapsedMs = 20_000L,
                            accuracyM = 6f,
                            speedMps = null,
                            bearingDeg = null,
                        ),
                ),
            )

        assertTrue(update.fixAccepted)
        assertTrue(update.resolvedSpeedMps > 1f)
        assertEquals(90f, update.resolvedBearingDeg ?: Float.NaN, 5f)
    }

    @Test
    fun reliableZeroSpeedStopsBikePredictionImmediately() {
        val controller =
            MarkerMotionController(
                predictionFreshnessMaxAgeMs = 12_000L,
                maxAcceptedFixAgeMs = 24_000L,
            ).also { it.updateActivityProfile(isBikeActivityProfile = true) }
        val base = LatLong(48.8566, 2.3522)
        val movingFix = moveLatLong(base, bearing = 90f, distanceMeters = 3f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 6f,
            rawSpeedMps = 6f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = movingFix,
            nowElapsedMs = 13_000L,
            fixElapsedMs = 13_000L,
            accuracyM = 6f,
            rawSpeedMps = 0f,
            rawBearingDeg = 90f,
            speedAccuracyMps = 0.3f,
        )

        val settled =
            controller.predict(
                nowElapsedMs = 17_000L,
                serviceFreshnessMaxAgeMs = 12_000L,
                watchGpsDegraded = false,
            ) ?: movingFix
        val later =
            controller.predict(
                nowElapsedMs = 18_000L,
                serviceFreshnessMaxAgeMs = 12_000L,
                watchGpsDegraded = false,
            ) ?: settled

        assertTrue(distanceMeters(settled, later) < 0.2f)
        assertEquals("slow", MarkerMotionTelemetry.latestSnapshot().reason)
    }

    @Test
    fun bikeCanDeriveMotionWhenGpsSpeedIsAbsent() {
        val controller =
            MarkerMotionController(
                predictionFreshnessMaxAgeMs = 12_000L,
                maxAcceptedFixAgeMs = 24_000L,
            ).also { it.updateActivityProfile(isBikeActivityProfile = true) }
        val base = LatLong(48.8566, 2.3522)
        val bikeFix = moveLatLong(base, bearing = 90f, distanceMeters = 30f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 6f,
            rawSpeedMps = null,
            rawBearingDeg = null,
        )
        controller.onGpsFix(
            latLong = bikeFix,
            nowElapsedMs = 13_000L,
            fixElapsedMs = 13_000L,
            accuracyM = 6f,
            rawSpeedMps = null,
            rawBearingDeg = null,
        )

        val predicted =
            controller.predict(
                nowElapsedMs = 16_500L,
                serviceFreshnessMaxAgeMs = 12_000L,
                watchGpsDegraded = false,
            ) ?: bikeFix

        assertTrue(distanceMeters(bikeFix, predicted) > 5f)
        assertTrue(predicted.longitude > bikeFix.longitude)
    }

    @Test
    fun poorBearingAccuracyDoesNotDrivePrediction() {
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 6f,
            rawSpeedMps = 2f,
            rawBearingDeg = 90f,
            bearingAccuracyDeg = 80f,
        )

        val predicted =
            controller.predict(
                nowElapsedMs = 11_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) < 0.2f)
        assertEquals("no_bearing", MarkerMotionTelemetry.latestSnapshot().reason)
    }

    @Test
    fun activeCorrectionKeepsMovingForwardWhileConverging() {
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val nextFix = moveLatLong(base, bearing = 90f, distanceMeters = 8f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 6f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
        )
        val atRebase =
            controller.onGpsFix(
                latLong = nextFix,
                nowElapsedMs = 13_000L,
                fixElapsedMs = 13_000L,
                accuracyM = 6f,
                rawSpeedMps = 1.5f,
                rawBearingDeg = 90f,
            )
        val partial =
            controller.predict(
                nowElapsedMs = 13_100L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base
        assertTrue(distanceMeters(base, partial) > distanceMeters(base, atRebase))
        assertTrue(distanceMeters(partial, nextFix) < distanceMeters(atRebase, nextFix))
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 13_100L))
    }

    @Test
    fun telemetryAggregatesFixGapAndVisualInnovation() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 6f,
            rawSpeedMps = 1f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = moveLatLong(base, bearing = 90f, distanceMeters = 5f),
            nowElapsedMs = 13_000L,
            fixElapsedMs = 13_000L,
            accuracyM = 6f,
            rawSpeedMps = 1f,
            rawBearingDeg = 90f,
        )

        val summary = MarkerMotionTelemetry.summary()
        assertEquals(1, summary.fixGapSamples)
        assertEquals(3_000L, summary.fixGapMeanMs)
        assertEquals(1, summary.innovationSamples)
        assertTrue((summary.innovationMeanM ?: 0f) > 4f)
    }

    @Test
    fun timingUpdatePreservesAcceptedMotionState() {
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 10_000L,
            fixElapsedMs = 10_000L,
            accuracyM = 6f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
        )

        controller.updateTiming(
            predictionFreshnessMaxAgeMs = 12_000L,
            maxAcceptedFixAgeMs = 120_000L,
        )
        val predicted =
            controller.predict(
                nowElapsedMs = 11_000L,
                serviceFreshnessMaxAgeMs = 120_000L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) > 0.5f)
    }

    @Test
    fun suppressesPredictionWhenAccuracyIsPoor() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 20_000L,
            fixElapsedMs = 20_000L,
            accuracyM = 70f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
        )

        val predicted =
            controller.predict(
                nowElapsedMs = 21_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) < 0.2f)
        val snapshot = MarkerMotionTelemetry.latestSnapshot()
        assertEquals(MarkerMotionMode.FIXED, snapshot.mode)
        assertEquals("bad_accuracy", snapshot.reason)
    }

    @Test
    fun suppressesPredictionFromWeakAutoFusedAccuracy() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val walkingFix = moveLatLong(base, bearing = 90f, distanceMeters = 8f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 30_000L,
            fixElapsedMs = 30_000L,
            accuracyM = 38f,
            rawSpeedMps = 0f,
            rawBearingDeg = null,
        )
        controller.onGpsFix(
            latLong = walkingFix,
            nowElapsedMs = 38_000L,
            fixElapsedMs = 38_000L,
            accuracyM = 38f,
            rawSpeedMps = 0f,
            rawBearingDeg = null,
        )
        assertEquals("weak_accuracy_hold", MarkerMotionTelemetry.latestSnapshot().reason)

        val predicted =
            controller.predict(
                nowElapsedMs = 39_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: walkingFix

        assertTrue(distanceMeters(base, predicted) < 0.2f)
        val snapshot = MarkerMotionTelemetry.latestSnapshot()
        assertEquals(MarkerMotionMode.FIXED, snapshot.mode)
        assertEquals("stale", snapshot.reason)
    }

    @Test
    fun freshWeakNonWatchFixKeepsTrustedVisualMotionWithoutUsingWeakCoordinate() {
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val weakCoordinate = moveLatLong(base, bearing = 90f, distanceMeters = 20f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 30_000L,
            fixElapsedMs = 30_000L,
            accuracyM = 8f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
        )
        val beforeWeakFix =
            controller.predict(
                nowElapsedMs = 32_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base
        val held =
            controller.onGpsFix(
                latLong = weakCoordinate,
                nowElapsedMs = 33_000L,
                fixElapsedMs = 33_000L,
                accuracyM = 50f,
                rawSpeedMps = 1.5f,
                rawBearingDeg = 90f,
                sourceMode = LocationSourceMode.PASSIVE_EXTERNAL,
            )
        assertEquals("weak_accuracy_hold", MarkerMotionTelemetry.latestSnapshot().reason)
        val continued =
            controller.predict(
                nowElapsedMs = 34_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: held

        assertTrue(distanceMeters(beforeWeakFix, held) < 0.02f)
        assertTrue(distanceMeters(held, weakCoordinate) > 10f)
        assertTrue(distanceMeters(held, continued) > 0.5f)
    }

    @Test
    fun predictsFromKnownWatchGpsAccuracyFloorWhenRawMotionIsAvailable() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 24_000L,
            fixElapsedMs = 24_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )

        val predicted =
            controller.predict(
                nowElapsedMs = 25_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) > 0.6f)
        assertTrue(predicted.longitude > base.longitude)
        assertEquals(MarkerMotionMode.PREDICT, MarkerMotionTelemetry.latestSnapshot().mode)
    }

    @Test
    fun autoFusedAccuracyFloorStillBlocksPrediction() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 26_000L,
            fixElapsedMs = 26_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.AUTO_FUSED,
        )

        val predicted =
            controller.predict(
                nowElapsedMs = 27_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) < 0.2f)
        assertEquals("bad_accuracy", MarkerMotionTelemetry.latestSnapshot().reason)
    }

    @Test
    fun freezesStationaryJitterWithinAccuracyBubble() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val jitter = moveLatLong(base, bearing = 90f, distanceMeters = 3f)

        val first =
            controller.onGpsFix(
                latLong = base,
                nowElapsedMs = 30_000L,
                fixElapsedMs = 30_000L,
                accuracyM = 8f,
                rawSpeedMps = 0.1f,
                rawBearingDeg = null,
            )
        val second =
            controller.onGpsFix(
                latLong = jitter,
                nowElapsedMs = 31_000L,
                fixElapsedMs = 31_000L,
                accuracyM = 8f,
                rawSpeedMps = 0.1f,
                rawBearingDeg = null,
            )

        assertTrue(distanceMeters(first, second) < 0.2f)
        assertEquals(2, MarkerMotionTelemetry.summary().acceptedFixes)
    }

    @Test
    fun correctionConvergesWithoutStoppingForwardMotion() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val target = moveLatLong(base, bearing = 0f, distanceMeters = 20f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 40_000L,
            fixElapsedMs = 40_000L,
            accuracyM = 8f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 0f,
        )
        val blendStart =
            controller.onGpsFix(
                latLong = target,
                nowElapsedMs = 42_000L,
                fixElapsedMs = 42_000L,
                accuracyM = 8f,
                rawSpeedMps = 1.2f,
                rawBearingDeg = 0f,
            )

        assertTrue(distanceMeters(blendStart, base) < 0.2f)
        assertTrue(distanceMeters(blendStart, target) > 15f)

        val partial =
            controller.predict(
                nowElapsedMs = 42_400L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: target
        val settled =
            controller.predict(
                nowElapsedMs = 43_400L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: target

        assertTrue(distanceMeters(base, partial) > distanceMeters(base, blendStart))
        assertTrue(distanceMeters(partial, target) > distanceMeters(settled, target))
        assertTrue(distanceMeters(settled, target) < distanceMeters(blendStart, target))
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 43_400L))
        assertEquals(1, MarkerMotionTelemetry.summary().blendStarts)
    }

    @Test
    fun duplicateFixDoesNotRestartCorrectionBlend() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val target = moveLatLong(base, bearing = 90f, distanceMeters = 20f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 45_000L,
            fixElapsedMs = 45_000L,
            accuracyM = 8f,
            rawSpeedMps = 4f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = target,
            nowElapsedMs = 47_000L,
            fixElapsedMs = 47_000L,
            accuracyM = 10f,
            rawSpeedMps = 4f,
            rawBearingDeg = 90f,
        )
        val duplicateDisplay =
            controller.onGpsFix(
                latLong = target,
                nowElapsedMs = 47_050L,
                fixElapsedMs = 47_080L,
                accuracyM = 10f,
                rawSpeedMps = 0f,
                rawBearingDeg = 90f,
            )

        assertTrue(distanceMeters(duplicateDisplay, base) < 0.2f)
        assertTrue(distanceMeters(duplicateDisplay, target) > 19f)
        assertEquals(2, MarkerMotionTelemetry.summary().acceptedFixes)
        assertEquals(1, MarkerMotionTelemetry.summary().blendStarts)
        assertEquals("duplicate_fix", MarkerMotionTelemetry.latestSnapshot().reason)

        val settled =
            controller.predict(
                nowElapsedMs = 48_400L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: target

        assertTrue(distanceMeters(settled, target) < distanceMeters(duplicateDisplay, target))
        assertEquals(1, MarkerMotionTelemetry.summary().blendStarts)
    }

    @Test
    fun rejectsChangedFixWhenReliableTimestampDoesNotAdvance() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 80_000L,
            fixElapsedMs = 80_000L,
            accuracyM = 16f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 90f,
        )
        val rejectedDisplay =
            controller.onGpsFix(
                latLong = moveLatLong(base, bearing = 90f, distanceMeters = 14f),
                nowElapsedMs = 80_040L,
                fixElapsedMs = 80_000L,
                accuracyM = 22f,
                rawSpeedMps = 1.2f,
                rawBearingDeg = 90f,
            )

        assertTrue(distanceMeters(base, rejectedDisplay) < 0.2f)
        assertEquals(1, MarkerMotionTelemetry.summary().acceptedFixes)
        assertEquals("non_forward_fix", MarkerMotionTelemetry.latestSnapshot().reason)
    }

    @Test
    fun movingDeadbandConvergesWithoutTeleporting() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 90_000L,
            fixElapsedMs = 90_000L,
            accuracyM = 20f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 90f,
        )
        val target = moveLatLong(base, bearing = 90f, distanceMeters = 1f)
        val display =
            controller.onGpsFix(
                latLong = target,
                nowElapsedMs = 93_000L,
                fixElapsedMs = 93_000L,
                accuracyM = 20f,
                rawSpeedMps = 1.2f,
                rawBearingDeg = 90f,
            )

        assertTrue(distanceMeters(target, display) > 0.2f)
        assertEquals("deadband_snap", MarkerMotionTelemetry.latestSnapshot().reason)
        val settled =
            controller.predict(
                nowElapsedMs = 93_500L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: target
        assertTrue(distanceMeters(settled, target) < distanceMeters(display, target))
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 93_500L))
    }

    @Test
    fun watchGpsDoesNotQualityWeightSyntheticAccuracyFloor() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val target = moveLatLong(base, bearing = 90f, distanceMeters = 10f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 94_000L,
            fixElapsedMs = 94_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        val display =
            controller.onGpsFix(
                latLong = target,
                nowElapsedMs = 97_000L,
                fixElapsedMs = 97_000L,
                accuracyM = 125f,
                rawSpeedMps = 1.2f,
                rawBearingDeg = 90f,
                sourceMode = LocationSourceMode.WATCH_GPS,
            )

        assertTrue(distanceMeters(base, display) < 0.2f)
        assertTrue(distanceMeters(display, target) > 9f)
        assertEquals("gps_correction", MarkerMotionTelemetry.latestSnapshot().reason)
        val settled =
            controller.predict(
                nowElapsedMs = 97_600L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: target
        assertTrue(distanceMeters(settled, target) < distanceMeters(display, target))
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 97_600L))
    }

    @Test
    fun watchGpsKeepsRawSpeedWhenSpeedAccuracyMetadataIsWeak() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 98_000L,
            fixElapsedMs = 98_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
            speedAccuracyMps = 5f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        val predicted =
            controller.predict(
                nowElapsedMs = 99_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) > 0.6f)
        assertEquals(MarkerMotionMode.PREDICT, MarkerMotionTelemetry.latestSnapshot().mode)
    }

    @Test
    fun retainedWakeAnchorKeepsPositionButClearsStaleMotion() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 100_000L,
            fixElapsedMs = 100_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        controller.requireFreshFixForPrediction(reason = "screen_non_interactive")
        val retained = controller.retainedAnchorSeed ?: error("Expected retained visual anchor")

        assertTrue(distanceMeters(base, retained.latLong) < 0.01f)
        assertEquals(0f, retained.reading.speedMps)
        assertEquals(null, retained.reading.bearingDeg)
        assertEquals(LocationSourceMode.WATCH_GPS, retained.sourceMode)
        assertEquals(MarkerMotionAnchorOrigin.RETAINED_VISUAL, retained.origin)
    }

    @Test
    fun dropsOutlierJump() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val outlier = moveLatLong(base, bearing = 45f, distanceMeters = 120f)

        val first =
            controller.onGpsFix(
                latLong = base,
                nowElapsedMs = 50_000L,
                fixElapsedMs = 50_000L,
                accuracyM = 6f,
                rawSpeedMps = 1f,
                rawBearingDeg = 90f,
            )
        val second =
            controller.onGpsFix(
                latLong = outlier,
                nowElapsedMs = 51_500L,
                fixElapsedMs = 51_500L,
                accuracyM = 25f,
                rawSpeedMps = 0.5f,
                rawBearingDeg = 45f,
            )

        assertTrue(distanceMeters(first, second) < 0.5f)
        val summary = MarkerMotionTelemetry.summary()
        assertEquals(1, summary.outlierDrops)
        assertEquals("outlier_drop", MarkerMotionTelemetry.latestSnapshot().reason)
    }

    @Test
    fun knownWatchGpsAccuracyFloorDoesNotHideOutlierJump() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val outlier = moveLatLong(base, bearing = 45f, distanceMeters = 120f)

        val first =
            controller.onGpsFix(
                latLong = base,
                nowElapsedMs = 55_000L,
                fixElapsedMs = 55_000L,
                accuracyM = 125f,
                rawSpeedMps = 1f,
                rawBearingDeg = 90f,
                sourceMode = LocationSourceMode.WATCH_GPS,
            )
        val second =
            controller.onGpsFix(
                latLong = outlier,
                nowElapsedMs = 56_500L,
                fixElapsedMs = 56_500L,
                accuracyM = 125f,
                rawSpeedMps = 0.5f,
                rawBearingDeg = 45f,
                sourceMode = LocationSourceMode.WATCH_GPS,
            )

        assertTrue(distanceMeters(first, second) < 0.5f)
        assertEquals(1, MarkerMotionTelemetry.summary().outlierDrops)
        assertEquals("outlier_drop", MarkerMotionTelemetry.latestSnapshot().reason)
    }

    @Test
    fun sourceModeSwitchAllowsReanchorWithoutOutlierDropOrClamp() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val watchFix = LatLong(48.8566, 2.3522)
        val phoneFix = moveLatLong(watchFix, bearing = 70f, distanceMeters = 76f)

        controller.onGpsFix(
            latLong = watchFix,
            nowElapsedMs = 100_000L,
            fixElapsedMs = 100_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.0f,
            rawBearingDeg = 0f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        val reanchored =
            controller.onGpsFix(
                latLong = phoneFix,
                nowElapsedMs = 102_200L,
                fixElapsedMs = 102_200L,
                accuracyM = 16f,
                rawSpeedMps = 1.1f,
                rawBearingDeg = 33f,
                sourceMode = LocationSourceMode.AUTO_FUSED,
            )
        assertEquals("source_switch", MarkerMotionTelemetry.latestSnapshot().reason)
        assertTrue(distanceMeters(reanchored, watchFix) < 5f)
        assertTrue(distanceMeters(reanchored, phoneFix) > 50f)

        val settled =
            controller.predict(
                nowElapsedMs = 102_800L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: watchFix

        val summary = MarkerMotionTelemetry.summary()
        assertTrue(distanceMeters(settled, phoneFix) < distanceMeters(reanchored, phoneFix))
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 102_800L))
        assertEquals(0, summary.outlierDrops)
        assertEquals(0, summary.clampedCorrections)
    }

    @Test
    fun clampsLargeCorrectionDuringNormalInteractiveWalking() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val farTarget = moveLatLong(base, bearing = 90f, distanceMeters = 40f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 60_000L,
            fixElapsedMs = 60_000L,
            accuracyM = 10f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 90f,
        )
        val initialDisplay =
            controller.onGpsFix(
                latLong = farTarget,
                nowElapsedMs = 63_000L,
                fixElapsedMs = 63_000L,
                accuracyM = 22f,
                rawSpeedMps = 1.4f,
                rawBearingDeg = 90f,
            )

        val settled =
            controller.predict(
                nowElapsedMs = 63_400L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: farTarget

        assertTrue(distanceMeters(initialDisplay, settled) < 20f)
        assertTrue(distanceMeters(settled, farTarget) > 10f)
        assertEquals(1, MarkerMotionTelemetry.summary().clampedCorrections)
    }

    @Test
    fun autoFusedRepeatedClampCatchesUpToGoodWalkingFixes() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val target1 = moveLatLong(base, bearing = 20f, distanceMeters = 40f)
        val target2 = moveLatLong(base, bearing = 20f, distanceMeters = 75f)
        val target3 = moveLatLong(base, bearing = 20f, distanceMeters = 110f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 110_000L,
            fixElapsedMs = 110_000L,
            accuracyM = 8f,
            rawSpeedMps = 1.4f,
            rawBearingDeg = 20f,
        )
        controller.onGpsFix(
            latLong = target1,
            nowElapsedMs = 113_000L,
            fixElapsedMs = 113_000L,
            accuracyM = 8f,
            rawSpeedMps = 1.4f,
            rawBearingDeg = 20f,
        )
        controller.onGpsFix(
            latLong = target2,
            nowElapsedMs = 116_000L,
            fixElapsedMs = 116_000L,
            accuracyM = 8f,
            rawSpeedMps = 1.4f,
            rawBearingDeg = 20f,
        )
        val catchUpDisplay =
            controller.onGpsFix(
                latLong = target3,
                nowElapsedMs = 119_000L,
                fixElapsedMs = 119_000L,
                accuracyM = 8f,
                rawSpeedMps = 1.4f,
                rawBearingDeg = 20f,
            )
        assertEquals("auto_fused_catch_up", MarkerMotionTelemetry.latestSnapshot().reason)
        assertTrue(distanceMeters(catchUpDisplay, target3) > 20f)

        val settled =
            controller.predict(
                nowElapsedMs = 119_600L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(settled, target3) < distanceMeters(catchUpDisplay, target3))
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 119_600L))
        assertEquals(2, MarkerMotionTelemetry.summary().clampedCorrections)
    }

    @Test
    fun gpsFixRebasesFromLastDisplayedPointWhenPredictionLoopDoesNotRun() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val bearing = 180f
        var display = base

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 130_000L,
            fixElapsedMs = 130_000L,
            accuracyM = 8f,
            rawSpeedMps = 1.3f,
            rawBearingDeg = bearing,
        )
        for (index in 1..8) {
            val target = moveLatLong(base, bearing = bearing, distanceMeters = index * 5f)
            display =
                controller.onGpsFix(
                    latLong = target,
                    nowElapsedMs = 130_000L + index * 3_000L,
                    fixElapsedMs = 130_000L + index * 3_000L,
                    accuracyM = 8f,
                    rawSpeedMps = 1.3f,
                    rawBearingDeg = bearing,
                )
        }

        assertTrue(distanceMeters(display, base) < 0.2f)
        assertEquals(9, MarkerMotionTelemetry.summary().acceptedFixes)
    }

    @Test
    fun screenOffFixesKeepHiddenAnchorCurrentWithoutRendering() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val firstTarget = moveLatLong(base, bearing = 90f, distanceMeters = 45f)
        val latestTarget = moveLatLong(base, bearing = 90f, distanceMeters = 90f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 130_000L,
            fixElapsedMs = 130_000L,
            accuracyM = 8f,
            rawSpeedMps = 15f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = firstTarget,
            nowElapsedMs = 133_000L,
            fixElapsedMs = 133_000L,
            accuracyM = 10f,
            rawSpeedMps = 15f,
            rawBearingDeg = 90f,
            isMarkerVisible = false,
        )
        val hiddenDisplay =
            controller.onGpsFix(
                latLong = latestTarget,
                nowElapsedMs = 136_000L,
                fixElapsedMs = 136_000L,
                accuracyM = 9f,
                rawSpeedMps = 15f,
                rawBearingDeg = 90f,
                isMarkerVisible = false,
            )

        assertTrue(distanceMeters(hiddenDisplay, latestTarget) < 0.2f)
        assertTrue(
            distanceMeters(
                controller.retainedAnchorSeed?.latLong ?: base,
                latestTarget,
            ) < 0.2f,
        )
        assertEquals("screen_off_anchor", MarkerMotionTelemetry.latestSnapshot().reason)
        assertEquals(0, MarkerMotionTelemetry.summary().clampedCorrections)
        assertEquals(2, MarkerMotionTelemetry.summary().screenOffNextFixPredictionResidualM.samples)
    }

    @Test
    fun recentMovingWakeAnchorCanPredictBeforeFreshFix() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)

        controller.seedAnchor(
            seed =
                MarkerMotionSeed(
                    latLong = base,
                    reading =
                        MarkerMotionReading(
                            fixElapsedMs = 140_000L,
                            accuracyM = 8f,
                            speedMps = 15f,
                            bearingDeg = 90f,
                        ),
                ),
            nowElapsedMs = 141_000L,
            allowPredictionUntilFreshFix = true,
        )
        val predicted =
            controller.predict(
                nowElapsedMs = 142_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(base, predicted) > 20f)
    }

    @Test
    fun autoFusedConfirmedHighSpeedLagUsesFastCatchUp() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val target1 = moveLatLong(base, bearing = 90f, distanceMeters = 60f)
        val target2 = moveLatLong(base, bearing = 90f, distanceMeters = 120f)
        val target3 = moveLatLong(base, bearing = 90f, distanceMeters = 180f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 150_000L,
            fixElapsedMs = 150_000L,
            accuracyM = 18f,
            rawSpeedMps = 15f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = target1,
            nowElapsedMs = 153_000L,
            fixElapsedMs = 153_000L,
            accuracyM = 18f,
            rawSpeedMps = 15f,
            rawBearingDeg = 90f,
        )
        controller.onGpsFix(
            latLong = target2,
            nowElapsedMs = 156_000L,
            fixElapsedMs = 156_000L,
            accuracyM = 18f,
            rawSpeedMps = 15f,
            rawBearingDeg = 90f,
        )
        val catchUpDisplay =
            controller.onGpsFix(
                latLong = target3,
                nowElapsedMs = 159_000L,
                fixElapsedMs = 159_000L,
                accuracyM = 18f,
                rawSpeedMps = 15f,
                rawBearingDeg = 90f,
            )

        assertEquals("auto_fused_high_speed_catch_up", MarkerMotionTelemetry.latestSnapshot().reason)
        val caughtUp =
            controller.predict(
                nowElapsedMs = 160_000L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(catchUpDisplay, target3) > 150f)
        assertTrue(distanceMeters(caughtUp, target3) < 25f)
        assertEquals(2, MarkerMotionTelemetry.summary().clampedCorrections)
    }

    @Test
    fun watchGpsSustainedBikeLagCatchesUpAfterRepeatedClamps() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val firstLaggedTarget = moveLatLong(base, bearing = 90f, distanceMeters = 54f)
        val secondLaggedTarget = moveLatLong(base, bearing = 90f, distanceMeters = 108f)
        val catchUpTarget = moveLatLong(base, bearing = 90f, distanceMeters = 162f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 100_000L,
            fixElapsedMs = 100_000L,
            accuracyM = 125f,
            rawSpeedMps = 6f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        controller.onGpsFix(
            latLong = firstLaggedTarget,
            nowElapsedMs = 103_000L,
            fixElapsedMs = 103_000L,
            accuracyM = 125f,
            rawSpeedMps = 6f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        controller.onGpsFix(
            latLong = secondLaggedTarget,
            nowElapsedMs = 106_000L,
            fixElapsedMs = 106_000L,
            accuracyM = 125f,
            rawSpeedMps = 6f,
            rawBearingDeg = 90f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        val catchUpDisplay =
            controller.onGpsFix(
                latLong = catchUpTarget,
                nowElapsedMs = 109_000L,
                fixElapsedMs = 109_000L,
                accuracyM = 125f,
                rawSpeedMps = 6f,
                rawBearingDeg = 90f,
                sourceMode = LocationSourceMode.WATCH_GPS,
            )
        assertTrue(
            MarkerMotionTelemetry.latestSnapshot().reason in
                setOf("watch_gps_catch_up", "correction_clamped"),
        )

        val settled =
            controller.predict(
                nowElapsedMs = 109_600L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: base

        assertTrue(distanceMeters(settled, catchUpTarget) < distanceMeters(catchUpDisplay, catchUpTarget))
        assertTrue(MarkerMotionTelemetry.summary().clampedCorrections >= 2)
    }

    @Test
    fun bypassesClampForWakeStyleCatchUpCorrection() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val farTarget = moveLatLong(base, bearing = 0f, distanceMeters = 40f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 70_000L,
            fixElapsedMs = 70_000L,
            accuracyM = 10f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 0f,
        )
        val atRebase =
            controller.onGpsFix(
                latLong = farTarget,
                nowElapsedMs = 78_000L,
                fixElapsedMs = 78_000L,
                accuracyM = 22f,
                rawSpeedMps = 1.4f,
                rawBearingDeg = 0f,
                allowLargeCorrection = true,
            )

        val settled =
            controller.predict(
                nowElapsedMs = 78_600L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: farTarget

        assertTrue(distanceMeters(settled, farTarget) < distanceMeters(atRebase, farTarget))
        assertEquals(0, MarkerMotionTelemetry.summary().clampedCorrections)
    }

    @Test
    fun clampsModerateAccuracyLargeCorrection() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val farTarget = moveLatLong(base, bearing = 180f, distanceMeters = 28f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 90_000L,
            fixElapsedMs = 90_000L,
            accuracyM = 10f,
            rawSpeedMps = 1.1f,
            rawBearingDeg = 180f,
        )
        controller.onGpsFix(
            latLong = farTarget,
            nowElapsedMs = 93_200L,
            fixElapsedMs = 93_200L,
            accuracyM = 15f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 180f,
        )

        val settled =
            controller.predict(
                nowElapsedMs = 93_600L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: farTarget

        assertTrue(distanceMeters(settled, farTarget) > 5f)
        assertEquals(1, MarkerMotionTelemetry.summary().clampedCorrections)
    }

    @Test
    fun watchGpsCorrectionStartsWithoutTeleportingOnAcceptedFix() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val target = moveLatLong(base, bearing = 80f, distanceMeters = 70f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 110_000L,
            fixElapsedMs = 110_000L,
            accuracyM = 125f,
            rawSpeedMps = 1.5f,
            rawBearingDeg = 80f,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
        val displayed =
            controller.onGpsFix(
                latLong = target,
                nowElapsedMs = 116_000L,
                fixElapsedMs = 116_000L,
                accuracyM = 125f,
                rawSpeedMps = 1.5f,
                rawBearingDeg = 80f,
                sourceMode = LocationSourceMode.WATCH_GPS,
            )

        assertTrue(distanceMeters(base, displayed) < 0.2f)
        assertTrue(distanceMeters(displayed, target) > 69f)
        assertEquals(1, MarkerMotionTelemetry.summary().clampedCorrections)
        assertTrue(controller.hasPendingVisualCorrection(nowElapsedMs = 116_000L))
    }

    @Test
    fun clampsVeryLargeCorrectionEvenWhenReportedAccuracyLooksGood() {
        MarkerMotionTelemetry.clear()
        val controller = MarkerMotionController(predictionFreshnessMaxAgeMs = 4_500L, maxAcceptedFixAgeMs = 6_000L)
        val base = LatLong(48.8566, 2.3522)
        val farTarget = moveLatLong(base, bearing = 180f, distanceMeters = 40f)

        controller.onGpsFix(
            latLong = base,
            nowElapsedMs = 95_000L,
            fixElapsedMs = 95_000L,
            accuracyM = 10f,
            rawSpeedMps = 1.2f,
            rawBearingDeg = 180f,
        )
        controller.onGpsFix(
            latLong = farTarget,
            nowElapsedMs = 98_100L,
            fixElapsedMs = 98_100L,
            accuracyM = 13.1f,
            rawSpeedMps = 1.33f,
            rawBearingDeg = 188.2f,
        )

        val settled =
            controller.predict(
                nowElapsedMs = 98_500L,
                serviceFreshnessMaxAgeMs = 4_500L,
                watchGpsDegraded = false,
            ) ?: farTarget

        assertTrue(distanceMeters(settled, farTarget) > 15f)
        assertEquals(1, MarkerMotionTelemetry.summary().clampedCorrections)
    }
}

@Suppress("LongParameterList")
private fun MarkerMotionController.onGpsFix(
    latLong: LatLong,
    nowElapsedMs: Long,
    fixElapsedMs: Long,
    accuracyM: Float,
    rawSpeedMps: Float?,
    rawBearingDeg: Float?,
    speedAccuracyMps: Float? = null,
    bearingAccuracyDeg: Float? = null,
    allowLargeCorrection: Boolean = false,
    sourceMode: LocationSourceMode = LocationSourceMode.AUTO_FUSED,
    isMarkerVisible: Boolean = true,
): LatLong =
    onGpsFix(
        fix =
            MarkerMotionGpsFix(
                latLong = latLong,
                nowElapsedMs = nowElapsedMs,
                reading =
                    MarkerMotionReading(
                        fixElapsedMs = fixElapsedMs,
                        accuracyM = accuracyM,
                        speedMps = rawSpeedMps,
                        bearingDeg = rawBearingDeg,
                        speedAccuracyMps = speedAccuracyMps,
                        bearingAccuracyDeg = bearingAccuracyDeg,
                    ),
                allowLargeCorrection = allowLargeCorrection,
                sourceMode = sourceMode,
                isMarkerVisible = isMarkerVisible,
            ),
    ).displayedLatLong

private fun distanceMeters(
    from: LatLong,
    to: LatLong,
): Float {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a =
        sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
    val c = 2.0 * asin(sqrt(a))
    return (6_371_000.0 * c).toFloat()
}
