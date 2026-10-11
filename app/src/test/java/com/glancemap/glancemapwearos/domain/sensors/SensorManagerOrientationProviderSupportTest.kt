package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.SensorManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("LargeClass") // Keeps production-boundary freshness and generation regressions together.
class SensorManagerOrientationProviderSupportTest {
    @Test
    fun initialHeadingHasNoTimestampAndIsStale() {
        val freshness = SensorHeadingSampleFreshness()

        assertNull(freshness.sampleAtElapsedRealtimeMs)
        assertTrue(freshness.stale)
    }

    @Test
    fun publishedHeadingCarriesItsElapsedRealtimeAndIsFresh() {
        val freshness =
            SensorHeadingSampleFreshness.afterPublish(
                sampleAtElapsedRealtimeMs = 12_345L,
                arrivalAtElapsedRealtimeMs = 12_350L,
                sequenceId = 7L,
                heldOutput = true,
            )

        assertEquals(12_345L, freshness.sampleAtElapsedRealtimeMs)
        assertEquals(12_350L, freshness.arrivalAtElapsedRealtimeMs)
        assertEquals(7L, freshness.sequenceId)
        assertTrue(freshness.heldOutput)
        assertFalse(freshness.stale)
    }

    @Test
    fun equalNumericSensorEventsRetainDistinctSequenceIdentity() =
        runBlocking {
            val flow = MutableSharedFlow<SensorRawHeadingSample>(extraBufferCapacity = 8)
            val received = mutableListOf<SensorRawHeadingSample>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    flow.take(3).toList(received)
                }

            repeat(3) { index ->
                flow.emit(
                    SensorRawHeadingSample(
                        headingDeg = 42f,
                        sourceMeasurementAtElapsedRealtimeMs = 1_000L + index,
                        callbackArrivalAtElapsedRealtimeMs = 1_010L + index,
                        sequenceId = index + 1L,
                    ),
                )
            }
            collector.join()

            assertEquals(listOf(1L, 2L, 3L), received.map { it.sequenceId })
            assertEquals(
                listOf(1_000L, 1_001L, 1_002L),
                received.map { it.sourceMeasurementAtElapsedRealtimeMs },
            )
        }

    @Test
    fun sensorHeadingStalenessUsesSourceMeasurementTime() {
        assertFalse(isSensorHeadingSampleStale(sampleAtElapsedRealtimeMs = 1_000L, nowElapsedRealtimeMs = 2_499L))
        assertTrue(isSensorHeadingSampleStale(sampleAtElapsedRealtimeMs = 1_000L, nowElapsedRealtimeMs = 2_500L))
        assertTrue(isSensorHeadingSampleStale(sampleAtElapsedRealtimeMs = 1_000L, nowElapsedRealtimeMs = 999L))
    }

    @Test
    fun cachedSettingsRefreshKeepsAStoppedSensorOutputStale() {
        val sensorSample =
            SensorRawHeadingSample(
                headingDeg = 42f,
                sourceMeasurementAtElapsedRealtimeMs = 1_000L,
                callbackArrivalAtElapsedRealtimeMs = 1_000L,
                sequenceId = 1L,
            )
        val staleBeforeRefresh =
            sensorHeadingSampleFreshnessAfterPublish(
                sample = sensorSample,
                nowElapsedRealtimeMs = 2_500L,
            )
        val cachedRefresh =
            sensorHeadingSampleFreshnessAfterPublish(
                sample =
                    sensorSample.copy(
                        callbackArrivalAtElapsedRealtimeMs = 2_600L,
                        sequenceId = 2L,
                    ),
                nowElapsedRealtimeMs = 2_600L,
            )

        assertTrue(staleBeforeRefresh.stale)
        assertTrue(cachedRefresh.stale)
        assertEquals(1_000L, cachedRefresh.sampleAtElapsedRealtimeMs)
        assertEquals(2_600L, cachedRefresh.arrivalAtElapsedRealtimeMs)
    }

    @Test
    fun northReferenceConversionPreservesTheOriginalMeasurementTime() {
        val convertedSample =
            SensorRawHeadingSample(
                headingDeg = 97f,
                sourceMeasurementAtElapsedRealtimeMs = 4_000L,
                callbackArrivalAtElapsedRealtimeMs = 4_250L,
                sequenceId = 8L,
            )

        val freshness =
            sensorHeadingSampleFreshnessAfterPublish(
                sample = convertedSample,
                nowElapsedRealtimeMs = 4_250L,
            )

        assertEquals(4_000L, freshness.sampleAtElapsedRealtimeMs)
        assertEquals(4_250L, freshness.arrivalAtElapsedRealtimeMs)
        assertFalse(freshness.stale)
    }

    @Test
    fun equalHeadingFromANewSensorEventRefreshesFreshness() {
        val previousSample =
            SensorRawHeadingSample(
                headingDeg = 42f,
                sourceMeasurementAtElapsedRealtimeMs = 1_000L,
                callbackArrivalAtElapsedRealtimeMs = 1_000L,
                sequenceId = 1L,
            )
        val newSensorSample =
            previousSample.copy(
                sourceMeasurementAtElapsedRealtimeMs = 2_500L,
                callbackArrivalAtElapsedRealtimeMs = 2_500L,
                sequenceId = 2L,
            )

        val freshness =
            sensorHeadingSampleFreshnessAfterPublish(
                sample = newSensorSample,
                nowElapsedRealtimeMs = 2_500L,
            )

        assertEquals(previousSample.headingDeg, newSensorSample.headingDeg)
        assertEquals(2_500L, freshness.sampleAtElapsedRealtimeMs)
        assertEquals(2L, freshness.sequenceId)
        assertFalse(freshness.stale)
    }

    @Test
    fun queuedOldRegistrationSampleCannotConsumeTheNewResetOrPublish() =
        runBlocking {
            val rawHeadingFlow = MutableSharedFlow<SensorRawHeadingSample>(extraBufferCapacity = 8)
            val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sampleEntered = CountDownLatch(1)
            val releaseSample = CountDownLatch(1)
            val published = CompletableDeferred<SensorRawHeadingSample>()
            var currentGeneration = 1L
            var firstCurrentCheck = true
            var resetRequested = false
            var resetConsumed = 0
            val processor =
                launchTestHeadingProcessor(
                    scope = processingScope,
                    rawHeadingFlow = rawHeadingFlow,
                    isRawSampleCurrent = { sample ->
                        if (firstCurrentCheck) {
                            firstCurrentCheck = false
                            sampleEntered.countDown()
                            assertTrue(releaseSample.await(2, TimeUnit.SECONDS))
                        }
                        sample.registrationGeneration == currentGeneration
                    },
                    consumeResetSmoothingRequested = { _ ->
                        if (!resetRequested) {
                            false
                        } else {
                            resetRequested = false
                            resetConsumed += 1
                            true
                        }
                    },
                    pendingStartupHeadingPublishesToMask = 1,
                    publishDisplayedHeading = { _, sample -> published.complete(sample) },
                )

            try {
                rawHeadingFlow.emit(sample(headingDeg = 10f, registrationGeneration = 1L))
                assertTrue(sampleEntered.await(2, TimeUnit.SECONDS))
                currentGeneration = 2L
                resetRequested = true
                releaseSample.countDown()

                rawHeadingFlow.emit(sample(headingDeg = 20f, registrationGeneration = 2L))
                val currentSample = withTimeout(2_000L) { published.await() }

                assertEquals(2L, currentSample.registrationGeneration)
                assertEquals(1, resetConsumed)
            } finally {
                processor.cancel()
                processingScope.cancel()
            }
        }

    @Test
    fun inFlightSampleCannotPublishAfterStop() =
        runBlocking {
            val rawHeadingFlow = MutableSharedFlow<SensorRawHeadingSample>(extraBufferCapacity = 8)
            val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val finalOwnershipCheck = CountDownLatch(1)
            var started = true
            var currentCheck = 0
            var published = false
            val processor =
                launchTestHeadingProcessor(
                    scope = processingScope,
                    rawHeadingFlow = rawHeadingFlow,
                    isRawSampleCurrent = { sample ->
                        currentCheck += 1
                        if (currentCheck == 2) {
                            started = false
                            finalOwnershipCheck.countDown()
                        }
                        started && sample.registrationGeneration == 1L
                    },
                    publishDisplayedHeading = { _, _ -> published = true },
                )

            try {
                rawHeadingFlow.emit(sample(headingDeg = 10f, registrationGeneration = 1L))
                assertTrue(finalOwnershipCheck.await(2, TimeUnit.SECONDS))
                assertFalse(published)
            } finally {
                processor.cancel()
                processingScope.cancel()
            }
        }

    @Test
    fun generationChangeAfterEntryCheckCannotConsumeNewBootstrapBudget() =
        runBlocking {
            val rawHeadingFlow = MutableSharedFlow<SensorRawHeadingSample>(extraBufferCapacity = 8)
            val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var currentGeneration = 1L
            var bootstrapRemaining = 0
            val processor =
                launchTestHeadingProcessor(
                    scope = processingScope,
                    rawHeadingFlow = rawHeadingFlow,
                    isRawSampleCurrent = { sample ->
                        sample.registrationGeneration == currentGeneration
                    },
                    getNowElapsedMs = {
                        currentGeneration = 2L
                        bootstrapRemaining = 3
                        1_000L
                    },
                    getPendingBootstrapRawSamplesToIgnore = { bootstrapRemaining },
                    setPendingBootstrapRawSamplesToIgnore = { _, remaining ->
                        bootstrapRemaining = remaining
                    },
                    publishDisplayedHeading = { _, _ ->
                        error("An old registration sample must not publish")
                    },
                )

            try {
                rawHeadingFlow.emit(sample(headingDeg = 10f, registrationGeneration = 1L))
                withTimeout(2_000L) {
                    while (currentGeneration == 1L) kotlinx.coroutines.yield()
                }
                assertEquals(3, bootstrapRemaining)
            } finally {
                processor.cancel()
                processingScope.cancel()
            }
        }

    @Test
    fun currentGenerationRateTransitionPublishesWithContinuity() =
        runBlocking {
            val rawHeadingFlow = MutableSharedFlow<SensorRawHeadingSample>(extraBufferCapacity = 8)
            val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val firstPublished = CompletableDeferred<SensorRawHeadingSample>()
            val secondPublished = CompletableDeferred<SensorRawHeadingSample>()
            var currentGeneration = 1L
            var displayedHeading = 0f
            var resetRequested = false
            val processor =
                launchTestHeadingProcessor(
                    scope = processingScope,
                    rawHeadingFlow = rawHeadingFlow,
                    isRawSampleCurrent = { sample ->
                        sample.registrationGeneration == currentGeneration
                    },
                    consumeResetSmoothingRequested = { _ ->
                        if (!resetRequested) {
                            false
                        } else {
                            resetRequested = false
                            true
                        }
                    },
                    getDisplayedHeading = { displayedHeading },
                    pendingStartupHeadingPublishesToMask = 1,
                    publishDisplayedHeading = { heading, sample ->
                        displayedHeading = heading
                        if (!firstPublished.isCompleted) {
                            firstPublished.complete(sample)
                        } else {
                            secondPublished.complete(sample)
                        }
                    },
                )

            try {
                rawHeadingFlow.emit(sample(headingDeg = 10f, registrationGeneration = 1L))
                assertEquals(1L, withTimeout(2_000L) { firstPublished.await() }.registrationGeneration)

                currentGeneration = 2L
                resetRequested = true
                rawHeadingFlow.emit(sample(headingDeg = 12f, registrationGeneration = 2L))
                val currentSample = withTimeout(2_000L) { secondPublished.await() }

                assertEquals(2L, currentSample.registrationGeneration)
                assertTrue(displayedHeading.isFinite())
            } finally {
                processor.cancel()
                processingScope.cancel()
            }
        }

    @Test
    fun poorRotationVectorUncertaintyImmediatelyCapsCombinedAccuracy() {
        val update =
            computeCompassRotationVectorUpdate(
                previousUncertaintyDeg = 8f,
                values = floatArrayOf(0f, 0f, 0f, 0f, Math.toRadians(45.0).toFloat()),
                sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = true,
                usingHeadingSensor = false,
                hasMagneticInterference = false,
            )

        assertTrue(update.uncertaintyDeg >= 40f)
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, update.combinedAccuracy)
    }

    @Test
    fun lifecycleStopRetainsTimestampButMarksHeadingStale() {
        val stoppedFreshness =
            SensorHeadingSampleFreshness
                .afterPublish(sampleAtElapsedRealtimeMs = 12_345L)
                .markStale()

        assertEquals(12_345L, stoppedFreshness.sampleAtElapsedRealtimeMs)
        assertTrue(stoppedFreshness.stale)
    }

    @Test
    fun shutdownSensorThreadIsNeverReusedForFallbackRegistration() {
        assertFalse(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = true,
            ),
        )
        assertTrue(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = false,
            ),
        )
    }

    @Test
    fun rapidStopStartRejectsBothTheOldTimeoutAndStoppingHandler() {
        assertFalse(
            isCurrentFusedReadyTimeout(
                timeoutIsCurrent = false,
                started = true,
                usingFallback = false,
                awaitingFusedReady = true,
                timeoutRequestGeneration = 10L,
                activeRequestGeneration = 12L,
            ),
        )
        assertFalse(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = true,
            ),
        )
    }

    @Test
    fun freshAccelAndMagnetometerPairIsAcceptedFromSourceTimestamps() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 9_900L, magnetometerAtMs = 10_000L),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertTrue(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCEPTED, validity.reason)
        assertEquals(100L, validity.pairAgeMs)
        assertEquals(100L, validity.pairSkewMs)
    }

    @Test
    fun futureAccelerometerCannotPairWithCurrentMagnetometer() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 10_001L, magnetometerAtMs = 10_000L),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCELEROMETER_FUTURE, validity.reason)
    }

    @Test
    fun currentAccelerometerCannotPairWithFutureMagnetometer() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 10_000L, magnetometerAtMs = 10_001L),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_FUTURE, validity.reason)
    }

    @Test
    fun componentsAtCurrentElapsedRealtimeRemainValid() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 10_000L, magnetometerAtMs = 10_000L),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertTrue(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCEPTED, validity.reason)
    }

    @Test
    fun freshAccelerometerCannotPairWithStaleMagnetometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 10_000L,
                        magnetometerAtMs = 10_000L - SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
                    ),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_STALE, validity.reason)
    }

    @Test
    fun freshMagnetometerCannotPairWithStaleAccelerometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 10_000L - SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
                        magnetometerAtMs = 10_000L,
                    ),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCELEROMETER_STALE, validity.reason)
    }

    @Test
    fun currentComponentsWithExcessiveSourceTimeSkewAreRejected() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 9_500L,
                        magnetometerAtMs = 10_000L,
                    ),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.EXCESSIVE_SKEW, validity.reason)
    }

    @Test
    fun componentSilenceMakesTheFallbackPairStale() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 12_000L,
                        magnetometerAtMs = 10_500L,
                    ),
                nowElapsedRealtimeMs = 12_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_STALE, validity.reason)
    }

    @Test
    fun stationaryFreshEventsRemainAValidPair() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 20_000L, magnetometerAtMs = 20_000L),
                nowElapsedRealtimeMs = 20_000L,
                registrationGeneration = 7L,
            )

        assertTrue(validity.accepted)
    }

    @Test
    fun reregistrationClearsOldFallbackComponentsBeforeNewSamplesArrive() {
        val previousGeneration = pairState(accelerometerAtMs = 30_000L, magnetometerAtMs = 30_000L)
        val afterReregistration =
            updateSensorMagAccelPairState(
                state = SensorMagAccelPairState(),
                component = SensorMagAccelComponent.ACCELEROMETER,
                sourceTimestampElapsedRealtimeMs = 31_000L,
                registrationGeneration = 8L,
            )

        assertTrue(
            validateSensorMagAccelPair(
                state = previousGeneration,
                nowElapsedRealtimeMs = 30_000L,
                registrationGeneration = 7L,
            ).accepted,
        )
        assertEquals(
            SensorMagAccelPairReason.MAGNETOMETER_MISSING,
            validateSensorMagAccelPair(
                state = afterReregistration,
                nowElapsedRealtimeMs = 31_000L,
                registrationGeneration = 8L,
            ).reason,
        )
    }

    @Test
    fun oldGenerationAccelerometerCannotPairWithNewGenerationMagnetometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 40_000L,
                        magnetometerAtMs = 40_000L,
                        accelerometerGeneration = 7L,
                        magnetometerGeneration = 8L,
                    ),
                nowElapsedRealtimeMs = 40_000L,
                registrationGeneration = 8L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCELEROMETER_GENERATION_MISMATCH, validity.reason)
    }

    @Test
    fun oldGenerationMagnetometerCannotPairWithNewGenerationAccelerometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 40_000L,
                        magnetometerAtMs = 40_000L,
                        accelerometerGeneration = 8L,
                        magnetometerGeneration = 7L,
                    ),
                nowElapsedRealtimeMs = 40_000L,
                registrationGeneration = 8L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_GENERATION_MISMATCH, validity.reason)
    }

    @Test
    fun failedRequiredFallbackRegistrationsAreNotOperational() {
        val accelerometerFailed =
            CompassSensorRegistrationResult(
                accelerometerRegistered = false,
                magnetometerRegistered = true,
            )
        val magnetometerFailed =
            CompassSensorRegistrationResult(
                accelerometerRegistered = true,
                magnetometerRegistered = false,
            )

        assertFalse(accelerometerFailed.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
        assertFalse(magnetometerFailed.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
        assertTrue(
            buildCompassSensorRegistrationTrace(
                registrationGeneration = 9L,
                pipeline = HeadingPipeline.MAG_ACCEL_FALLBACK,
                result = magnetometerFailed,
            ).contains("operational=false"),
        )
    }

    @Test
    fun partialFallbackRegistrationCannotClaimOperationalAndSuccessfulReregistrationRecovers() {
        val partial =
            CompassSensorRegistrationResult(
                accelerometerRegistered = true,
                magnetometerRegistered = false,
            )
        val recovered =
            CompassSensorRegistrationResult(
                accelerometerRegistered = true,
                magnetometerRegistered = true,
            )

        assertFalse(partial.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
        assertTrue(recovered.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
    }

    @Test
    fun optionalMagnetometerRegistrationDoesNotDisableHeadingSensorPipeline() {
        val result =
            CompassSensorRegistrationResult(
                headingSensorRegistered = true,
                magnetometerRegistered = false,
            )

        assertTrue(result.isOperational(HeadingPipeline.HEADING_SENSOR))
    }

    @Test
    fun deepTracePairEvidenceIncludesTheRejectionReasonAndSourceTiming() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 50_000L,
                        magnetometerAtMs = 50_000L - SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
                    ),
                nowElapsedRealtimeMs = 50_000L,
                registrationGeneration = 7L,
            )

        val trace = buildSensorMagAccelPairTrace(registrationGeneration = 7L, validity = validity)

        assertTrue(trace.contains("reason=magnetometer_stale"))
        assertTrue(trace.contains("accelAtMs=50000"))
        assertTrue(trace.contains("magAtMs=48500"))
    }

    private fun pairState(
        accelerometerAtMs: Long,
        magnetometerAtMs: Long,
        accelerometerGeneration: Long = 7L,
        magnetometerGeneration: Long = 7L,
    ): SensorMagAccelPairState =
        SensorMagAccelPairState(
            accelerometer =
                SensorMagAccelComponentSample(
                    sourceTimestampElapsedRealtimeMs = accelerometerAtMs,
                    registrationGeneration = accelerometerGeneration,
                ),
            magnetometer =
                SensorMagAccelComponentSample(
                    sourceTimestampElapsedRealtimeMs = magnetometerAtMs,
                    registrationGeneration = magnetometerGeneration,
                ),
        )

    private fun sample(
        headingDeg: Float,
        registrationGeneration: Long,
    ): SensorRawHeadingSample =
        SensorRawHeadingSample(
            headingDeg = headingDeg,
            sourceMeasurementAtElapsedRealtimeMs = 1_000L,
            callbackArrivalAtElapsedRealtimeMs = 1_000L,
            sequenceId = registrationGeneration,
            registrationGeneration = registrationGeneration,
        )

    @Suppress("LongParameterList") // Mirrors the production processor's independent ownership seams.
    private fun launchTestHeadingProcessor(
        scope: CoroutineScope,
        rawHeadingFlow: MutableSharedFlow<SensorRawHeadingSample>,
        isRawSampleCurrent: (SensorRawHeadingSample) -> Boolean,
        consumeResetSmoothingRequested: (SensorRawHeadingSample) -> Boolean = { false },
        getDisplayedHeading: () -> Float = { 0f },
        pendingStartupHeadingPublishesToMask: Int = 0,
        getNowElapsedMs: () -> Long = { 1_000L },
        getPendingBootstrapRawSamplesToIgnore: () -> Int = { 0 },
        setPendingBootstrapRawSamplesToIgnore: (SensorRawHeadingSample, Int) -> Unit = { _, _ -> },
        publishDisplayedHeading: (Float, SensorRawHeadingSample) -> Unit,
    ): Job {
        var pendingMask = pendingStartupHeadingPublishesToMask
        return CompassHeadingProcessor().launch(
            scope = scope,
            rawHeadingFlow = rawHeadingFlow,
            settleWindowMs = 0L,
            getStartAtMs = { 0L },
            getNowElapsedMs = getNowElapsedMs,
            getHeadingRelockUntilElapsedMs = { 0L },
            consumeResetSmoothingRequested = consumeResetSmoothingRequested,
            getDisplayedHeading = getDisplayedHeading,
            isRawSampleCurrent = isRawSampleCurrent,
            publishDisplayedHeading = publishDisplayedHeading,
            getPendingBootstrapRawSamplesToIgnore = getPendingBootstrapRawSamplesToIgnore,
            setPendingBootstrapRawSamplesToIgnore = setPendingBootstrapRawSamplesToIgnore,
            getPendingStartupBogusSamplesToIgnore = { 0 },
            setPendingStartupBogusSamplesToIgnore = { _, _ -> },
            getPendingStartupHeadingPublishesToMask = { pendingMask },
            setPendingStartupHeadingPublishesToMask = { _, remaining -> pendingMask = remaining },
            getStartupStabilizationUntilElapsedMs = { 0L },
            getStartupHeadingPublishMaskUntilElapsedMs = { 0L },
            isUsingRotationVector = { false },
            isUsingHeadingSensor = { false },
            updateInferredHeadingAccuracy = { _, _ -> },
            logDiagnostics = {},
        )
    }
}
