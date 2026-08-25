package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class RecordingHybridElevationFilterTest {
    @Test
    fun onlySmartModeUsesTheHybridElevationFilter() {
        assertTrue(SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO.usesHybridRecordingElevation())
        assertFalse(SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM.usesHybridRecordingElevation())
        assertFalse(SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS.usesHybridRecordingElevation())
    }

    @Test
    fun nonSmartDemModeDoesNotFallbackToGpsWhenDemIsMissing() {
        val result =
            RecordingHybridElevationFilter().update(
                RecordingHybridElevationInput(
                    demElevationMeters = null,
                    demAxisLen = null,
                    gpsElevationMeters = 123.0,
                    gpsVerticalAccuracyMeters = 10f,
                    horizontalAccuracyMeters = 10f,
                    absoluteElevationMeters = null,
                    absoluteElevationSource = RECORDING_ELEVATION_SOURCE_DEM_MISSING,
                    elapsedRealtimeMillis = 0L,
                    enabled = false,
                    startsNewSegment = false,
                    activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                ),
            )

        assertEquals(null, result.elevationMeters)
        assertEquals(RECORDING_ELEVATION_SOURCE_DEM_MISSING, result.elevationSource)
    }

    @Test
    fun nonSmartGpsModeKeepsTheGpsAbsoluteElevationInsteadOfDem() {
        val result =
            RecordingHybridElevationFilter().update(
                RecordingHybridElevationInput(
                    demElevationMeters = 100.0,
                    demAxisLen = 1_201,
                    gpsElevationMeters = 123.0,
                    gpsVerticalAccuracyMeters = 10f,
                    horizontalAccuracyMeters = 10f,
                    absoluteElevationMeters = 123.0,
                    absoluteElevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
                    elapsedRealtimeMillis = 0L,
                    enabled = false,
                    startsNewSegment = false,
                    activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                ),
            )

        assertEquals(123.0, result.elevationMeters ?: Double.NaN, 0.01)
        assertEquals(SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS, result.elevationSource)
    }

    @Test
    fun stablePressureNoiseStaysCloseToDemAnchor() {
        val replay = RecordingSmartElevationReplay()
        var result = replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        repeat(30) { index ->
            val noise =
                when (index % 3) {
                    0 -> -0.03
                    1 -> 0.02
                    else -> 0.01
                }
            val time = (index + 1) * 1_000L
            result = replay.apply(frame(time, 100.0, pressureAt(time, 1_000.0 + noise)))
        }

        assertEquals(100.0, result.elevationMeters ?: Double.NaN, 1.0)
        assertTrue(replay.diagnostics().pressureDeltaCount > 10)
    }

    @Test
    fun pressureEventsWithinOneGpsIntervalAreConsumedIndependentlyOfGpsCadence() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        val result =
            replay.apply(
                RecordingSmartElevationReplayFrame(
                    elapsedRealtimeMillis = 10_000L,
                    pressureSamples =
                        (1..10).map { second ->
                            pressureAt(second * 1_000L, 1_000.0 - second * 0.02)
                        },
                    demElevationMeters = 100.0,
                    demAxisLen = 1_201,
                    horizontalAccuracyMeters = 10f,
                ),
            )

        assertTrue(result.pressureUsed)
        assertTrue((result.elevationMeters ?: Double.NEGATIVE_INFINITY) > 100.5)
        assertEquals(11L, replay.diagnostics().pressure.observationCount)
    }

    @Test
    fun pressureIirUsesElapsedTimeRatherThanAFixedSampleRate() {
        val oneSecond = RecordingPressureFilter()
        val fiveSeconds = RecordingPressureFilter()
        oneSecond.observe(pressureAt(0L, 1_000.0))
        oneSecond.observe(pressureAt(1_000L, 999.0))
        fiveSeconds.observe(pressureAt(0L, 1_000.0))
        fiveSeconds.observe(pressureAt(5_000L, 999.0))

        val oneSecondDelta = 1_000.0 - checkNotNull(oneSecond.snapshotAt(1_000L)).filteredPressureHpa
        val fiveSecondDelta = 1_000.0 - checkNotNull(fiveSeconds.snapshotAt(5_000L)).filteredPressureHpa

        assertTrue(fiveSecondDelta > oneSecondDelta * 2.0)
    }

    @Test
    fun isolatedPressureSpikeIsRejectedBeforeFusion() {
        val replay = RecordingSmartElevationReplay()
        repeat(5) { index -> replay.apply(frame(index * 1_000L, 100.0, pressureAt(index * 1_000L, 1_000.0))) }
        val afterSpike = replay.apply(frame(5_000L, 100.0, pressureAt(5_000L, 990.0)))

        assertTrue(replay.diagnostics().pressure.outlierCount >= 1)
        assertEquals(100.0, afterSpike.elevationMeters ?: Double.NaN, 1.0)
    }

    @Test
    fun sustainedAscentProducesResponsivePositiveAltitudeChange() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, pressureForAltitude(0.0))))
        var result = replay.apply(frame(0L, 100.0))
        repeat(30) { index ->
            val time = (index + 1) * 1_000L
            result = replay.apply(frame(time, 100.0, pressureAt(time, pressureForAltitude((index + 1) * 0.6))))
        }

        assertTrue((result.elevationMeters ?: Double.NEGATIVE_INFINITY) > 110.0)
        assertEquals(RECORDING_ELEVATION_SOURCE_HYBRID, result.elevationSource)
    }

    @Test
    fun sustainedDescentProducesNegativeAltitudeChange() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 120.0, pressureAt(0L, pressureForAltitude(0.0))))
        var result = replay.apply(frame(0L, 120.0))
        repeat(25) { index ->
            val time = (index + 1) * 1_000L
            result = replay.apply(frame(time, 120.0, pressureAt(time, pressureForAltitude(-(index + 1) * 0.5))))
        }

        assertTrue((result.elevationMeters ?: Double.POSITIVE_INFINITY) < 114.0)
    }

    @Test
    fun steadySteepHikeTrendIsNotMistakenForAOneOffSpike() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, pressureForAltitude(0.0))))
        var result = replay.apply(frame(0L, 100.0))
        repeat(35) { index ->
            val time = (index + 1) * 1_000L
            result = replay.apply(frame(time, 100.0, pressureAt(time, pressureForAltitude((index + 1) * 0.75))))
        }

        assertTrue((result.elevationMeters ?: Double.NEGATIVE_INFINITY) > 115.0)
        assertEquals(0L, replay.diagnostics().pressure.outlierCount)
    }

    @Test
    fun weatherLikePressureDriftIsPulledBackByDem() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        var result = replay.apply(frame(0L, 100.0))
        repeat(60) { index ->
            val time = (index + 1) * 10_000L
            result = replay.apply(frame(time, 100.0, pressureAt(time, 1_000.0 - (index + 1) * 0.03)))
        }

        assertTrue((result.elevationMeters ?: Double.POSITIVE_INFINITY) < 108.0)
    }

    @Test
    fun higherResolutionAndBetterHorizontalAccuracyIncreaseDemAnchorInfluence() {
        val highConfidence = RecordingSmartElevationReplay()
        val lowConfidence = RecordingSmartElevationReplay()
        highConfidence.apply(frame(0L, 100.0, demAxisLen = 3_601, horizontalAccuracyMeters = 5f))
        lowConfidence.apply(frame(0L, 100.0, demAxisLen = 1_201, horizontalAccuracyMeters = 120f))

        val high = highConfidence.apply(frame(10_000L, 104.0, demAxisLen = 3_601, horizontalAccuracyMeters = 5f))
        val low = lowConfidence.apply(frame(10_000L, 104.0, demAxisLen = 1_201, horizontalAccuracyMeters = 120f))

        assertTrue((high.elevationMeters ?: 0.0) > (low.elevationMeters ?: 0.0))
    }

    @Test
    fun missingDemFallsBackToGpsWithAVisibleSource() {
        val result =
            RecordingSmartElevationReplay()
                .apply(
                    frame(
                        0L,
                        demElevationMeters = null,
                        gpsElevationMeters = 123.0,
                        gpsVerticalAccuracyMeters = 12f,
                    ),
                )

        assertEquals(123.0, result.elevationMeters ?: Double.NaN, 0.01)
        assertEquals(SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS, result.elevationSource)
    }

    @Test
    fun missingPressureContinuesUsingDemWithoutFabricatingBarometerEvidence() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0))
        val result = replay.apply(frame(10_000L, 104.0))

        assertFalse(result.pressureUsed)
        assertEquals(SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM, result.elevationSource)
        assertTrue((result.elevationMeters ?: Double.NEGATIVE_INFINITY) > 100.0)
    }

    @Test
    fun goodGpsVerticalAccuracyHasMoreAnchorInfluenceThanPoorAccuracy() {
        val good = RecordingSmartElevationReplay()
        val poor = RecordingSmartElevationReplay()
        good.apply(frame(0L, gpsElevationMeters = 100.0, gpsVerticalAccuracyMeters = 5f))
        poor.apply(frame(0L, gpsElevationMeters = 100.0, gpsVerticalAccuracyMeters = 100f))

        val goodResult = good.apply(frame(10_000L, gpsElevationMeters = 104.0, gpsVerticalAccuracyMeters = 5f))
        val poorResult = poor.apply(frame(10_000L, gpsElevationMeters = 104.0, gpsVerticalAccuracyMeters = 100f))

        assertTrue((goodResult.elevationMeters ?: 0.0) > (poorResult.elevationMeters ?: 0.0))
    }

    @Test
    fun pauseBoundaryRebasesPressureInsteadOfApplyingACrossPauseDelta() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        replay.apply(frame(1_000L, 100.0, pressureAt(1_000L, 999.9)))
        val resumed =
            replay.apply(
                frame(120_000L, 100.0, pressureAt(120_000L, 980.0), startsNewSegment = true),
            )

        assertFalse(resumed.pressureUsed)
        assertEquals(100.0, resumed.elevationMeters ?: Double.NaN, 1.0)
    }

    @Test
    fun longGpsGapRebasesPressureAndDoesNotTeleport() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        replay.apply(frame(1_000L, 100.0, pressureAt(1_000L, 999.9)))
        val afterGap = replay.apply(frame(70_000L, 100.0, pressureAt(70_000L, 995.0)))

        assertFalse(afterGap.pressureUsed)
        assertTrue((afterGap.elevationMeters ?: Double.NaN) < 102.0)
        assertTrue(replay.diagnostics().gapRebaseCount >= 1)
    }

    @Test
    fun stalePressureIsNotReusedAtLaterGpsFixes() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        val stale = replay.apply(frame(8_000L, 100.0))

        assertFalse(stale.pressureUsed)
        assertTrue(replay.diagnostics().stalePressurePointCount >= 1)
    }

    @Test
    fun demToGpsFallbackTransitionIsGradual() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        val before = replay.apply(frame(1_000L, 100.0, pressureAt(1_000L, 999.95)))
        val after = replay.apply(frame(2_000L, gpsElevationMeters = 140.0, gpsVerticalAccuracyMeters = 20f))

        assertTrue(kotlin.math.abs((after.elevationMeters ?: 0.0) - (before.elevationMeters ?: 0.0)) <= 1.1)
    }

    @Test
    fun pressureRecoveryAfterDemOnlyPeriodIsRebasedBeforeUse() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 100.0, pressureAt(0L, 1_000.0)))
        replay.apply(frame(10_000L, 100.0))
        val recovered = replay.apply(frame(11_000L, 100.0, pressureAt(11_000L, 999.0)))

        assertFalse(recovered.pressureUsed)
    }

    @Test
    fun pressureRestartResetsItsShortHistory() {
        val replay = RecordingSmartElevationReplay()
        repeat(5) { index -> replay.apply(frame(index * 1_000L, 100.0, pressureAt(index * 1_000L, 1_000.0))) }
        val afterRestart = replay.apply(frame(20_000L, 100.0, pressureAt(20_000L, 999.0)))

        assertTrue(replay.diagnostics().pressure.restartCount >= 1)
        assertFalse(afterRestart.pressureUsed)
    }

    @Test
    fun restartFollowedByOneLargeBadPressureSampleIsSuppressed() {
        val replay = healthyReplay()
        replay.apply(frame(20_000L, 100.0, pressureAt(20_000L, 1_000.0)))
        val afterBadSample = replay.apply(frame(21_000L, 100.0, pressureAt(21_000L, 990.0)))

        assertFalse(afterBadSample.pressureUsed)
        assertTrue((afterBadSample.elevationMeters ?: Double.NaN) < 102.0)
        assertTrue(replay.diagnostics().pressureWarmupSuppressedCount >= 2)
    }

    @Test
    fun sparsePressureCadenceRemainsGuardedAfterRestart() {
        val replay = healthyReplay()
        replay.apply(frame(20_000L, 100.0, pressureAt(20_000L, 1_000.0)))
        val afterThreeSecondGap = replay.apply(frame(23_000L, 100.0, pressureAt(23_000L, 990.0)))
        val afterTenSecondGap = replay.apply(frame(33_000L, 100.0, pressureAt(33_000L, 1_000.0)))

        assertTrue((afterThreeSecondGap.elevationMeters ?: Double.NaN) < 103.0)
        assertTrue((afterTenSecondGap.elevationMeters ?: Double.NaN) < 103.0)
        assertTrue(replay.diagnostics().pressureWarmupSuppressedCount >= 2)
    }

    @Test
    fun denseOneHertzPressureRecoveryRegainsNormalAuthority() {
        val replay = healthyReplay()
        replay.apply(frame(20_000L, 100.0, pressureAt(20_000L, 1_000.0)))
        var result = replay.apply(frame(21_000L, 100.0, pressureAt(21_000L, 1_000.0)))
        repeat(3) { index ->
            val time = (index + 22) * 1_000L
            result = replay.apply(frame(time, 100.0, pressureAt(time, 1_000.0)))
        }

        assertEquals("accepted", result.barometerReason)
        assertTrue(replay.diagnostics().pressureWarmupDownWeightedCount >= 2)
    }

    @Test
    fun sustainedClimbStartingDuringRecoveryIsNotFlattened() {
        val replay = healthyReplay()
        replay.apply(frame(20_000L, 100.0, pressureAt(20_000L, pressureForAltitude(0.0))))
        var result = replay.apply(frame(21_000L, 100.0, pressureAt(21_000L, pressureForAltitude(1.0))))
        repeat(24) { index ->
            val time = (index + 22) * 1_000L
            result = replay.apply(frame(time, 100.0, pressureAt(time, pressureForAltitude(index + 2.0))))
        }

        assertTrue((result.elevationMeters ?: Double.NEGATIVE_INFINITY) > 112.0)
        assertTrue(replay.diagnostics().pressureWarmupDownWeightedCount >= 1)
    }

    @Test
    fun recoveryWithoutDemKeepsFusedElevationContinuous() {
        val replay = healthyReplay(demElevationMeters = null, gpsElevationMeters = 100.0)
        replay.apply(
            frame(
                20_000L,
                demElevationMeters = null,
                gpsElevationMeters = null,
                pressureSample = pressureAt(20_000L, 1_000.0),
            ),
        )
        replay.apply(
            frame(
                21_000L,
                demElevationMeters = null,
                gpsElevationMeters = null,
                pressureSample = pressureAt(21_000L, 990.0),
            ),
        )
        val afterBadSample =
            replay.apply(
                frame(
                    22_000L,
                    demElevationMeters = null,
                    gpsElevationMeters = null,
                    pressureSample = pressureAt(22_000L, 989.8),
                ),
            )

        assertTrue((afterBadSample.elevationMeters ?: Double.NaN) < 102.0)
        assertEquals(RECORDING_ELEVATION_SOURCE_HYBRID, afterBadSample.elevationSource)
    }

    @Test
    fun repeatedStaleAndRestartCyclesDoNotAccumulateAltitudeJumps() {
        val replay = healthyReplay()
        replay.apply(frame(12_000L, 100.0))
        val afterStaleRecovery = replay.apply(frame(13_000L, 100.0, pressureAt(13_000L, 990.0)))
        replay.apply(frame(30_000L, 100.0, pressureAt(30_000L, 1_000.0)))
        val afterRestartRecovery = replay.apply(frame(31_000L, 100.0, pressureAt(31_000L, 990.0)))

        assertTrue((afterStaleRecovery.elevationMeters ?: Double.NaN) < 102.0)
        assertTrue((afterRestartRecovery.elevationMeters ?: Double.NaN) < 103.0)
        assertTrue(replay.diagnostics().pressure.restartCount >= 1)
        assertTrue(replay.diagnostics().pressureWarmupSuppressedCount >= 3)
    }

    @Test
    fun hybridResumptionAfterRebaseDoesNotCauseAnAltitudeJump() {
        val replay = healthyReplay()
        val beforeRebase = replay.apply(frame(19_000L, 100.0, pressureAt(19_000L, 1_000.0)))
        replay.apply(
            frame(
                20_000L,
                100.0,
                pressureAt(20_000L, 1_000.0),
                startsNewSegment = true,
            ),
        )
        var previous = replay.apply(frame(21_000L, 100.0, pressureAt(21_000L, 1_000.0)))
        repeat(3) { index ->
            val time = (index + 22) * 1_000L
            val current = replay.apply(frame(time, 100.0, pressureAt(time, 1_000.0)))
            assertTrue(abs((current.elevationMeters ?: Double.NaN) - (previous.elevationMeters ?: Double.NaN)) < 1.0)
            previous = current
        }

        assertTrue(abs((previous.elevationMeters ?: Double.NaN) - (beforeRebase.elevationMeters ?: Double.NaN)) < 1.0)
        assertEquals("accepted", previous.barometerReason)
    }

    @Test
    fun syntheticRegressionSustainedClimbSurvivesNoisyPressureAndDemAnchoring() {
        val replay = RecordingSmartElevationReplay()
        replay.apply(frame(0L, 300.0, pressureAt(0L, pressureForAltitude(0.0))))
        var result = replay.apply(frame(0L, 300.0))
        repeat(120) { index ->
            val time = (index + 1) * 1_000L
            val altitude = (index + 1) * 0.35
            val noise =
                when (index % 5) {
                    0 -> 0.03
                    1 -> -0.02
                    else -> 0.0
                }
            result = replay.apply(frame(time, 300.0, pressureAt(time, pressureForAltitude(altitude) + noise)))
        }

        assertTrue((result.elevationMeters ?: Double.NEGATIVE_INFINITY) > 325.0)
        assertTrue(replay.diagnostics().pressureDeltaCount > 100)
    }

    private fun healthyReplay(
        demElevationMeters: Double? = 100.0,
        gpsElevationMeters: Double? = null,
    ): RecordingSmartElevationReplay {
        val replay = RecordingSmartElevationReplay()
        repeat(5) { index ->
            val time = index * 1_000L
            replay.apply(
                frame(
                    elapsedRealtimeMillis = time,
                    demElevationMeters = demElevationMeters,
                    gpsElevationMeters = gpsElevationMeters,
                    pressureSample = pressureAt(time, 1_000.0),
                ),
            )
        }
        return replay
    }

    @Suppress("LongParameterList")
    private fun frame(
        elapsedRealtimeMillis: Long,
        demElevationMeters: Double? = null,
        pressureSample: RecordingPressureSample? = null,
        demAxisLen: Int? = 1_201,
        horizontalAccuracyMeters: Float? = 15f,
        gpsElevationMeters: Double? = null,
        gpsVerticalAccuracyMeters: Float? = null,
        startsNewSegment: Boolean = false,
    ): RecordingSmartElevationReplayFrame =
        RecordingSmartElevationReplayFrame(
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            pressureSamples = listOfNotNull(pressureSample),
            demElevationMeters = demElevationMeters,
            demAxisLen = demAxisLen,
            gpsElevationMeters = gpsElevationMeters,
            gpsVerticalAccuracyMeters = gpsVerticalAccuracyMeters,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            startsNewSegment = startsNewSegment,
        )

    private fun pressureAt(
        elapsedRealtimeMillis: Long,
        pressureHpa: Double,
    ): RecordingPressureSample = RecordingPressureSample(pressureHpa, elapsedRealtimeMillis)

    private fun pressureForAltitude(relativeAltitudeMeters: Double): Double = 1_000.0 *
        (1.0 - relativeAltitudeMeters / 44_330.0).pow(1.0 / 0.190294957)
}
