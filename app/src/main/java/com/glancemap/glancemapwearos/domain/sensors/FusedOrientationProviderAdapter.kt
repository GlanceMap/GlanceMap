package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassHeadingDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
internal class FusedOrientationProviderAdapter(
    context: Context,
    private val fallbackProvider: CompassOrientationProvider,
) : CompassOrientationProvider {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fusedOrientationClient by lazy(LazyThreadSafetyMode.NONE) {
        LocationServices.getFusedOrientationProviderClient(appContext)
    }
    private val integritySensorMonitor = FusedOrientationIntegritySensorMonitor(appContext)
    private val headingIntegrityEngine =
        FusedHeadingIntegrityEngine(
            relativeSensorAvailable = integritySensorMonitor.relativeSensorAvailable,
            magnetometerAvailable = integritySensorMonitor.magnetometerAvailable,
        )

    override val providerType: CompassProviderType = CompassProviderType.GOOGLE_FUSED

    private val _heading = MutableStateFlow(0f)
    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_UNRELIABLE)
    private val _headingErrorDeg = MutableStateFlow<Float?>(null)
    private val _conservativeHeadingErrorDeg = MutableStateFlow<Float?>(null)
    private val _headingSampleElapsedRealtimeMs = MutableStateFlow<Long?>(null)
    private val _headingSampleStale = MutableStateFlow(false)
    private val _headingSource = MutableStateFlow(HeadingSource.NONE)
    private val _headingSourceStatus =
        MutableStateFlow(
            HeadingSourceStatus(
                requestedMode = CompassHeadingSourceMode.AUTO,
                activeSource = HeadingSource.NONE,
                headingSensorAvailable = false,
                rotationVectorAvailable = false,
                magAccelFallbackAvailable = false,
            ),
        )
    private val _northReferenceStatus =
        MutableStateFlow(
            NorthReferenceStatus(
                requestedMode = NorthReferenceMode.TRUE,
                effectiveMode = NorthReferenceMode.MAGNETIC,
                declinationAvailable = false,
                waitingForDeclination = true,
                pipeline = HeadingPipeline.NONE,
                automaticByProvider = true,
            ),
        )
    private val _magneticInterference = MutableStateFlow(false)
    private val _useFallbackProvider = MutableStateFlow(false)
    private val ownRenderState =
        MutableStateFlow(initialCompassRenderState(providerType = providerType))

    override val renderState: StateFlow<CompassRenderState> =
        combine(
            ownRenderState,
            fallbackProvider.renderState,
            _useFallbackProvider,
        ) { ownState, fallbackState, useFallback ->
            if (useFallback) fallbackState else ownState
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(0),
            initialValue = ownRenderState.value,
        )

    @Volatile private var started = false

    @Volatile private var lowPowerMode = false

    @Volatile private var fusedWarmupActive = false

    @Volatile private var latestIntegritySnapshot = headingIntegrityEngine.snapshot()

    @Volatile private var northReferenceMode = NorthReferenceMode.TRUE

    @Volatile private var fallbackDeclinationSeed: FusedFallbackDeclinationSeed? = null

    @Volatile private var fallbackDeclinationLocation: Location? = null

    @Volatile private var orientationUpdatesRegistered = false

    @Volatile private var orientationRequestGeneration = 0L

    @Volatile private var activeOrientationListener: DeviceOrientationListener? = null

    @Volatile private var dispatchedOrientationRequestGeneration = 0L

    // Cached once per start() — GoogleApiAvailability.isGooglePlayServicesAvailable() is a
    // cross-process binder call; caching avoids IPC overhead on every heading update.
    @Volatile private var googleOrientationAvailable = false

    @Volatile private var lastOrientationRequestAtElapsedMs = 0L

    @Volatile private var lastOrientationRequestReason: String = "idle"

    @Volatile private var fusedReadyTimeoutMs = FUSED_READY_TIMEOUT_MS

    @Volatile private var awaitingFusedReady = false

    @Volatile private var firstOrientationSampleLogged = false

    @Volatile private var lastFusedSampleLogAtElapsedMs = 0L

    @Volatile private var fusedPerfWindowStartElapsedMs = 0L

    @Volatile private var fusedPerfCallbackCount = 0

    @Volatile private var fusedPerfConfirmedCount = 0

    @Volatile private var fusedPerfUnusableCount = 0

    @Volatile private var fusedPerfHeadingPublishCount = 0

    @Volatile private var fusedPerfActiveTurnPublishCount = 0

    @Volatile private var consecutiveUnusableFusedSamples = 0

    @Volatile private var firstUnusableFusedSampleAtElapsedMs = 0L

    @Volatile private var recalibrationBoostUntilElapsedMs = 0L

    @Volatile private var recalibrationBoostGeneration = 0L

    @Volatile private var lastFusedHeadingPublishAtElapsedMs = 0L

    private val activeTurnPublicationTracker =
        HeadingTurnRateHysteresis(
            enterRateDegPerSec = FUSED_ACTIVE_TURN_ENTER_RATE_DEG_PER_SEC,
            exitRateDegPerSec = FUSED_ACTIVE_TURN_EXIT_RATE_DEG_PER_SEC,
            exitHoldMs = FUSED_ACTIVE_TURN_EXIT_HOLD_MS,
            minimumEntryStepDeg = FUSED_ACTIVE_TURN_MIN_STEP_DEG,
            maximumSampleGapMs = FUSED_ACTIVE_TURN_MAX_SAMPLE_GAP_MS,
        )

    @Volatile private var lastConfirmedFusedSampleElapsedRealtimeMs = 0L

    @Volatile private var fusedStaleRecoveryAttempted = false

    @Volatile private var fusedStaleRecoveryStartedAtElapsedMs = 0L

    @Volatile private var fusedFreshnessCheckScheduled = false

    @Volatile private var callbackThread: HandlerThread? = null

    @Volatile private var callbackHandler: Handler? = null

    private val fusedSampleFreshnessRunnable: Runnable =
        Runnable {
            fusedFreshnessCheckScheduled = false
            if (!started || _useFallbackProvider.value) return@Runnable
            val sampleAtElapsedMs = lastConfirmedFusedSampleElapsedRealtimeMs
            if (sampleAtElapsedMs <= 0L) return@Runnable
            val sampleAgeMs =
                (SystemClock.elapsedRealtime() - sampleAtElapsedMs).coerceAtLeast(0L)
            if (sampleAgeMs < FUSED_ORIENTATION_SAMPLE_STALE_MS) {
                fusedFreshnessCheckScheduled = true
                callbackHandler?.postDelayed(
                    fusedSampleFreshnessRunnable,
                    FUSED_ORIENTATION_SAMPLE_STALE_MS - sampleAgeMs,
                )
                return@Runnable
            }
            _headingSampleStale.value = true
            if (fusedStaleRecoveryAttempted) {
                _accuracy.value = SensorManager.SENSOR_STATUS_UNRELIABLE
            }
            publishOwnRenderState()
            logDiagnostics(
                "google_fused sample_stale ageMs=$sampleAgeMs " +
                    "recoveryAttempted=$fusedStaleRecoveryAttempted",
            )
            if (!fusedStaleRecoveryAttempted) {
                fusedStaleRecoveryAttempted = true
                fusedStaleRecoveryStartedAtElapsedMs = SystemClock.elapsedRealtime()
                requestOrientationUpdates(
                    forceRestart = true,
                    reason = FUSED_STALE_SAMPLE_RETRY_REASON,
                )
            } else {
                startFallbackProvider(reason = "sample_stale")
            }
        }

    private val fusedReadyTimeoutRunnable: Runnable =
        Runnable {
            if (!started || _useFallbackProvider.value || !awaitingFusedReady) {
                return@Runnable
            }
            val requestAgeMs =
                (SystemClock.elapsedRealtime() - lastOrientationRequestAtElapsedMs)
                    .coerceAtLeast(0L)
            val timeoutMs = fusedReadyTimeoutMs
            if (requestAgeMs < timeoutMs) {
                callbackHandler?.postDelayed(
                    fusedReadyTimeoutRunnable,
                    timeoutMs - requestAgeMs,
                )
                return@Runnable
            }
            logDiagnostics(
                "google_fused ready_timeout reason=$lastOrientationRequestReason " +
                    "ageMs=$requestAgeMs timeoutMs=$timeoutMs " +
                    "trackingState=${latestIntegritySnapshot.state.telemetryToken} " +
                    "trackingReason=${latestIntegritySnapshot.reason.telemetryToken}",
            )
            startFallbackProvider(reason = "ready_timeout")
        }
    private val callbackExecutor: Executor =
        Executor { runnable ->
            val h = callbackHandler
            if (h == null || !h.post(runnable)) runnable.run()
        }

    private fun ensureCallbackHandler() {
        if (callbackHandler?.looper?.thread?.isAlive == true) return
        val t = HandlerThread(FUSED_ORIENTATION_THREAD_NAME).apply { start() }
        callbackThread = t
        callbackHandler = Handler(t.looper)
    }

    override fun start(lowPower: Boolean) {
        if (started) {
            if (lowPowerMode == lowPower) return
            lowPowerMode = lowPower
            if (_useFallbackProvider.value) {
                fallbackProvider.start(lowPower = lowPower)
            } else {
                requestOrientationUpdates(
                    forceRestart = true,
                    reason = "low_power_mode_change",
                )
            }
            return
        }

        lowPowerMode = lowPower
        started = true
        val startElapsedMs = SystemClock.elapsedRealtime()
        fusedStaleRecoveryAttempted = false
        fusedStaleRecoveryStartedAtElapsedMs = 0L
        fusedFreshnessCheckScheduled = false
        fusedWarmupActive = false
        publishNorthReferenceStatus()
        _magneticInterference.value = false

        googleOrientationAvailable = isGoogleOrientationAvailable()
        if (!googleOrientationAvailable) {
            logDiagnostics("google_fused unavailable; using sensor backup")
            startFallbackProvider(reason = "google_unavailable")
            return
        }

        _useFallbackProvider.value = false
        if (recentUsableFusedHeadingAgeMs(startElapsedMs) == null) {
            updateHeadingSourceState(HeadingSource.NONE)
        }
        logDiagnostics("google_fused state transition=starting_fused from=idle reason=start")
        requestOrientationUpdates(forceRestart = true, reason = "start")
    }

    override fun stop() {
        val preserveRecentFusedHeading =
            recentUsableFusedHeadingAgeMs(SystemClock.elapsedRealtime()) != null
        started = false
        stopOrientationUpdates()
        callbackThread?.quitSafely()
        callbackThread = null
        callbackHandler = null
        stopFallbackProvider()
        _useFallbackProvider.value = false
        fusedWarmupActive = false
        markHeadingPendingRestart(preserveRecentFusedHeading = preserveRecentFusedHeading)
        _magneticInterference.value = false
        headingIntegrityEngine.reset(
            seedHeadingDeg = _heading.value.takeIf { preserveRecentFusedHeading },
            atElapsedMs = SystemClock.elapsedRealtime(),
            clearSensorEvidence = true,
        )
        latestIntegritySnapshot = headingIntegrityEngine.snapshot()
        lastFusedHeadingPublishAtElapsedMs = 0L
        activeTurnPublicationTracker.reset()
        lastConfirmedFusedSampleElapsedRealtimeMs = 0L
        fusedStaleRecoveryAttempted = false
        fusedStaleRecoveryStartedAtElapsedMs = 0L
        fusedFreshnessCheckScheduled = false
        recalibrationBoostUntilElapsedMs = 0L
        recalibrationBoostGeneration += 1L
        lastOrientationRequestAtElapsedMs = 0L
        lastOrientationRequestReason = "idle"
        fusedReadyTimeoutMs = FUSED_READY_TIMEOUT_MS
        awaitingFusedReady = false
        firstOrientationSampleLogged = false
        lastFusedSampleLogAtElapsedMs = 0L
        resetUnusableFusedSampleState()
        resetFusedPerfCounters()
        CompassHeadingDiagnostics.flush(reason = "provider_stop")
        publishNorthReferenceStatus()
    }

    override fun recalibrate() {
        if (_useFallbackProvider.value) {
            if (started && googleOrientationAvailable) {
                fallbackProvider.stop()
                _useFallbackProvider.value = false
                logDiagnostics(
                    "google_fused state transition=starting_fused from=active_fallback " +
                        "reason=recalibrate",
                )
                activateTemporaryHighPowerBoost()
                requestOrientationUpdates(forceRestart = true, reason = "recalibrate")
            } else {
                fallbackProvider.recalibrate()
            }
            return
        }
        if (started) {
            activateTemporaryHighPowerBoost()
            requestOrientationUpdates(forceRestart = true, reason = "recalibrate")
        }
        logDiagnostics("recalibrate requested")
    }

    /**
     * The map rejected a large, unverified fused-heading change after a screen wake. Use the
     * existing sensor-manager provider for the remainder of this compass session.
     */
    fun startIntegrityFallback() {
        if (!started || _useFallbackProvider.value) return
        logDiagnostics("google_fused integrity_guard fallback")
        startFallbackProvider(reason = "integrity_guard")
    }

    override fun setNorthReferenceMode(
        mode: NorthReferenceMode,
        forceRefresh: Boolean,
    ) {
        if (northReferenceMode == mode && !forceRefresh) return
        northReferenceMode = mode
        publishNorthReferenceStatus()
    }

    override fun setHeadingSourceMode(
        mode: CompassHeadingSourceMode,
        forceRefresh: Boolean,
    ) = Unit

    override fun primeDeclinationFromApproximateLocation(
        latitude: Double,
        longitude: Double,
        altitudeM: Float,
    ) {
        fallbackDeclinationSeed =
            FusedFallbackDeclinationSeed(
                latitude = latitude,
                longitude = longitude,
                altitudeM = altitudeM,
            )
        if (_useFallbackProvider.value) {
            fallbackProvider.primeDeclinationFromApproximateLocation(
                latitude = latitude,
                longitude = longitude,
                altitudeM = altitudeM,
            )
        }
    }

    override fun updateDeclinationFromLocation(location: Location) {
        val snapshot = Location(location)
        fallbackDeclinationLocation = snapshot
        if (_useFallbackProvider.value) {
            fallbackProvider.updateDeclinationFromLocation(snapshot)
        }
    }

    private fun configureFallbackProvider() {
        fallbackProvider.setLowPowerMode(lowPowerMode)
        fallbackProvider.setNorthReferenceMode(
            mode = NorthReferenceMode.TRUE,
            forceRefresh = true,
        )
        fallbackProvider.setHeadingSourceMode(
            mode = CompassHeadingSourceMode.AUTO,
            forceRefresh = true,
        )
        fallbackDeclinationSeed?.let { seed ->
            fallbackProvider.primeDeclinationFromApproximateLocation(
                latitude = seed.latitude,
                longitude = seed.longitude,
                altitudeM = seed.altitudeM,
            )
        }
        fallbackDeclinationLocation?.let(fallbackProvider::updateDeclinationFromLocation)
    }

    override fun setLowPowerMode(enabled: Boolean) {
        lowPowerMode = enabled
        if (!started) return
        if (_useFallbackProvider.value) {
            fallbackProvider.setLowPowerMode(enabled)
        } else {
            requestOrientationUpdates(
                forceRestart = true,
                reason = "set_low_power_mode",
            )
        }
    }

    private fun requestOrientationUpdates(
        forceRestart: Boolean,
        reason: String,
    ) {
        if (!started || _useFallbackProvider.value) return
        if (!forceRestart && orientationUpdatesRegistered) return

        stopOrientationUpdates()
        ensureCallbackHandler()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val cachedHeadingAgeMs = recentUsableFusedHeadingAgeMs(nowElapsedMs)

        prepareOrientationRequestState(
            reason = reason,
            preserveRecentFusedHeading = cachedHeadingAgeMs != null,
        )
        startIntegritySensorMonitor()

        val samplingPeriodMicros = currentSamplingPeriodMicros()
        val usingBoost = isRecalibrationBoostActive()
        logDiagnostics(
            "google_fused request reason=$reason forceRestart=$forceRestart " +
                "samplingMicros=$samplingPeriodMicros lowPower=$lowPowerMode " +
                "boostActive=$usingBoost cachedHeadingAgeMs=${cachedHeadingAgeMs ?: "na"} " +
                "state=starting_fused",
        )
        registerOrientationRequest(
            samplingPeriodMicros = samplingPeriodMicros,
            usingBoost = usingBoost,
            reason = reason,
        )
    }

    private fun registerOrientationRequest(
        samplingPeriodMicros: Long,
        usingBoost: Boolean,
        reason: String,
    ) {
        val request = DeviceOrientationRequest.Builder(samplingPeriodMicros).build()
        val requestGeneration = orientationRequestGeneration + 1L
        orientationRequestGeneration = requestGeneration
        val requestListener =
            DeviceOrientationListener { orientation ->
                if (requestGeneration == orientationRequestGeneration) {
                    dispatchedOrientationRequestGeneration = requestGeneration
                    handleDeviceOrientationWithIntegrity(orientation)
                }
            }
        activeOrientationListener = requestListener
        fusedOrientationClient
            .requestOrientationUpdates(
                request,
                callbackExecutor,
                requestListener,
            ).addOnSuccessListener {
                handleOrientationRequestStarted(
                    requestGeneration = requestGeneration,
                    requestListener = requestListener,
                    samplingPeriodMicros = samplingPeriodMicros,
                    usingBoost = usingBoost,
                    reason = reason,
                )
            }.addOnFailureListener { error ->
                handleOrientationRequestFailed(
                    requestGeneration = requestGeneration,
                    requestListener = requestListener,
                    error = error,
                )
            }
    }

    private fun handleOrientationRequestStarted(
        requestGeneration: Long,
        requestListener: DeviceOrientationListener,
        samplingPeriodMicros: Long,
        usingBoost: Boolean,
        reason: String,
    ) {
        if (!isCurrentOrientationRequest(requestGeneration, requestListener)) {
            fusedOrientationClient.removeOrientationUpdates(requestListener)
            return
        }
        if (!started || _useFallbackProvider.value) {
            activeOrientationListener = null
            fusedOrientationClient.removeOrientationUpdates(requestListener)
            return
        }
        orientationUpdatesRegistered = true
        logDiagnostics(
            "google_fused started reason=$reason samplingMicros=$samplingPeriodMicros " +
                "boostActive=$usingBoost",
        )
    }

    private fun handleOrientationRequestFailed(
        requestGeneration: Long,
        requestListener: DeviceOrientationListener,
        error: Exception,
    ) {
        if (!isCurrentOrientationRequest(requestGeneration, requestListener)) return
        activeOrientationListener = null
        orientationUpdatesRegistered = false
        callbackHandler?.removeCallbacks(fusedReadyTimeoutRunnable)
        logDiagnostics(
            "google_fused start failed ${error.javaClass.simpleName}: ${error.message ?: "unknown"}",
        )
        startFallbackProvider(reason = "start_failed")
    }

    private fun isCurrentOrientationRequest(
        requestGeneration: Long,
        requestListener: DeviceOrientationListener,
    ): Boolean =
        requestGeneration == orientationRequestGeneration &&
            activeOrientationListener === requestListener

    private fun prepareOrientationRequestState(
        reason: String,
        preserveRecentFusedHeading: Boolean,
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        lastFusedHeadingPublishAtElapsedMs = 0L
        activeTurnPublicationTracker.reset()
        markHeadingPendingRestart(
            preserveRecentFusedHeading = preserveRecentFusedHeading,
        )
        headingIntegrityEngine.reset(
            seedHeadingDeg = _heading.value.takeIf { preserveRecentFusedHeading },
            atElapsedMs = nowElapsedMs,
            clearSensorEvidence = false,
        )
        latestIntegritySnapshot = headingIntegrityEngine.snapshot()
        lastOrientationRequestAtElapsedMs = nowElapsedMs
        lastOrientationRequestReason = reason
        fusedReadyTimeoutMs =
            resolveFusedReadyTimeoutMs(
                requestReason = reason,
                lowPowerMode = lowPowerMode,
                recalibrationBoostActive = isRecalibrationBoostActive(),
            )
        awaitingFusedReady = true
        firstOrientationSampleLogged = false
        lastFusedSampleLogAtElapsedMs = 0L
        fusedWarmupActive = true
        resetUnusableFusedSampleState()
        callbackHandler?.removeCallbacks(fusedReadyTimeoutRunnable)
        callbackHandler?.postDelayed(
            fusedReadyTimeoutRunnable,
            fusedReadyTimeoutMs,
        )
        publishOwnRenderState()
    }

    private fun startIntegritySensorMonitor() {
        val handler = callbackHandler ?: return
        integritySensorMonitor.start(
            handler = handler,
            lowPower = lowPowerMode && !isRecalibrationBoostActive(),
            // Keep the relative game-rotation vector alive for the whole interactive compass
            // session. It never supplies north, but it lets Navigate reject a large unverified
            // Google Fused turn after the display wakes.
            enableRelativeWitness = true,
            onRelativeHeading = { witness, atElapsedMs ->
                val headingDeg = witness.headingDeg
                if (headingDeg == null) {
                    latestIntegritySnapshot =
                        headingIntegrityEngine.onRelativeWitnessUnavailable(
                            horizontalProjection = witness.horizontalProjection,
                        )
                } else {
                    latestIntegritySnapshot =
                        headingIntegrityEngine.onRelativeHeading(
                            headingDeg = headingDeg,
                            horizontalProjection = witness.horizontalProjection,
                            atElapsedMs = atElapsedMs,
                        )
                }
            },
            onMagneticField = { strengthUt, atElapsedMs ->
                updateIntegritySnapshot(
                    next =
                        headingIntegrityEngine.onMagneticField(
                            strengthUt = strengthUt,
                            atElapsedMs = atElapsedMs,
                        ),
                    origin = "magnetic_field",
                )
            },
        )
    }

    @Suppress("LongMethod", "ReturnCount")
    private fun handleDeviceOrientationWithIntegrity(orientation: DeviceOrientation) {
        val requestGeneration = dispatchedOrientationRequestGeneration
        if (!isActiveOrientationRequest(requestGeneration)) return
        val arrivalElapsedMs = SystemClock.elapsedRealtime()
        val sampleAtElapsedMs =
            (orientation.elapsedRealtimeNs / NANOS_PER_MILLISECOND)
                .takeIf { it > 0L } ?: arrivalElapsedMs
        recordFusedPerfCallback(arrivalElapsedMs)

        val liveHeadingErrorDeg = orientation.headingErrorDegrees
        val conservativeHeadingErrorDeg =
            if (orientation.hasConservativeHeadingErrorDegrees()) {
                orientation.conservativeHeadingErrorDegrees
            } else {
                Float.NaN
            }
        val headingErrorDeg = resolveHeadingErrorDegrees(orientation)
        val absoluteHeadingDeg = normalize360Deg(orientation.headingDegrees)
        val mappedAccuracy = headingAccuracyFromUncertainty(headingErrorDeg)
        val usableHeading =
            isUsableGoogleFusedOrientationSample(
                headingDeg = absoluteHeadingDeg,
                headingErrorDeg = headingErrorDeg,
            )

        if (!firstOrientationSampleLogged) {
            firstOrientationSampleLogged = true
            logDiagnostics(
                "google_fused first_sample reason=$lastOrientationRequestReason " +
                    "latencyMs=${(arrivalElapsedMs - lastOrientationRequestAtElapsedMs).coerceAtLeast(0L)} " +
                    "heading=${absoluteHeadingDeg.format(1)} " +
                    "liveErrorDeg=${liveHeadingErrorDeg.formatOrNA(1)} " +
                    "conservativeErrorDeg=${conservativeHeadingErrorDeg.formatOrNA(1)} " +
                    "acc=$mappedAccuracy northBasis=${CompassNorthBasis.GOOGLE_AUTOMATIC.telemetryToken}",
            )
        }
        logFusedSample(
            nowElapsedMs = arrivalElapsedMs,
            displayHeading = absoluteHeadingDeg,
            headingErrorDeg = headingErrorDeg,
            liveHeadingErrorDeg = liveHeadingErrorDeg,
            conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
            mappedAccuracy = mappedAccuracy,
        )

        if (!isActiveOrientationRequest(requestGeneration)) return
        if (!usableHeading) {
            val snapshot =
                headingIntegrityEngine.onAbsoluteHeading(
                    FusedAbsoluteHeadingSample(
                        headingDeg = Float.NaN,
                        liveErrorDeg = liveHeadingErrorDeg.takeIf(Float::isFinite),
                        conservativeErrorDeg =
                            conservativeHeadingErrorDeg.takeIf(Float::isFinite),
                        atElapsedMs = sampleAtElapsedMs,
                    ),
                )
            updateIntegritySnapshot(next = snapshot, origin = "absolute_unusable")
            recordHeadingEngineSample(
                absoluteHeadingDeg = absoluteHeadingDeg,
                resolvedHeadingErrorDeg = headingErrorDeg,
                liveHeadingErrorDeg = liveHeadingErrorDeg,
                conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
                mappedAccuracy = mappedAccuracy,
                usable = false,
                snapshot = snapshot,
                atElapsedMs = arrivalElapsedMs,
            )
            publishUnusableFusedSampleState(
                headingErrorDeg = headingErrorDeg,
                conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
            )
            handleSustainedUnusableHeading(
                nowElapsedMs = arrivalElapsedMs,
                headingErrorDeg = headingErrorDeg,
                conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
            )
            return
        }

        resetUnusableFusedSampleState()
        recordConfirmedFusedSample(nowElapsedMs = arrivalElapsedMs)
        val firstUsableForRequest = awaitingFusedReady
        if (firstUsableForRequest) {
            awaitingFusedReady = false
            callbackHandler?.removeCallbacks(fusedReadyTimeoutRunnable)
            fusedWarmupActive = false
            logDiagnostics(
                "google_fused state transition=active_fused from=starting_fused " +
                    "reason=first_usable_transport " +
                    "latencyMs=${(arrivalElapsedMs - lastOrientationRequestAtElapsedMs).coerceAtLeast(0L)}",
            )
        }

        val snapshot =
            headingIntegrityEngine.onAbsoluteHeading(
                FusedAbsoluteHeadingSample(
                    headingDeg = absoluteHeadingDeg,
                    liveErrorDeg = liveHeadingErrorDeg.takeIf(Float::isFinite),
                    conservativeErrorDeg = conservativeHeadingErrorDeg.takeIf(Float::isFinite),
                    atElapsedMs = sampleAtElapsedMs,
                ),
            )
        updateIntegritySnapshot(next = snapshot, origin = "absolute")
        recordHeadingEngineSample(
            absoluteHeadingDeg = absoluteHeadingDeg,
            resolvedHeadingErrorDeg = headingErrorDeg,
            liveHeadingErrorDeg = liveHeadingErrorDeg,
            conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
            mappedAccuracy = mappedAccuracy,
            usable = true,
            snapshot = snapshot,
            atElapsedMs = arrivalElapsedMs,
        )
        val renderHeadingDeg = snapshot.renderHeadingDeg ?: return
        activeTurnPublicationTracker.update(
            headingDeg = renderHeadingDeg,
            atElapsedMs = arrivalElapsedMs,
        )
        if (firstUsableForRequest) {
            forcePublishFusedHeading(
                displayHeading = renderHeadingDeg,
                nowElapsedMs = arrivalElapsedMs,
                mappedAccuracy = mappedAccuracy,
                headingErrorDeg = headingErrorDeg,
                conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
            )
        } else {
            publishFusedHeadingIfDue(
                displayHeading = renderHeadingDeg,
                nowElapsedMs = arrivalElapsedMs,
                mappedAccuracy = mappedAccuracy,
                headingErrorDeg = headingErrorDeg,
                conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
            )
        }
    }

    private fun handleSustainedUnusableHeading(
        nowElapsedMs: Long,
        headingErrorDeg: Float,
        conservativeHeadingErrorDeg: Float,
    ) {
        val update =
            computeFusedUnusableHeadingUpdate(
                nowElapsedMs = nowElapsedMs,
                consecutiveUnusableSamples = consecutiveUnusableFusedSamples,
                firstUnusableSampleAtElapsedMs = firstUnusableFusedSampleAtElapsedMs,
                minSamples = FUSED_UNUSABLE_HEADING_FALLBACK_MIN_SAMPLES,
                minDurationMs = FUSED_UNUSABLE_HEADING_FALLBACK_MIN_DURATION_MS,
            )
        consecutiveUnusableFusedSamples = update.state.consecutiveSamples
        firstUnusableFusedSampleAtElapsedMs = update.state.firstSampleAtElapsedMs
        if (!update.shouldFallback) return
        logDiagnostics(
            "google_fused unusable_heading fallback samples=${update.state.consecutiveSamples} " +
                "durationMs=${update.durationMs} errorDeg=${headingErrorDeg.formatOrNA(1)} " +
                "conservativeErrorDeg=${conservativeHeadingErrorDeg.formatOrNA(1)}",
        )
        startFallbackProvider(reason = "unusable_heading")
    }

    private fun updateIntegritySnapshot(
        next: FusedHeadingIntegritySnapshot,
        origin: String,
    ) {
        val previous = latestIntegritySnapshot
        latestIntegritySnapshot = next
        if (origin == "magnetic_field") {
            CompassHeadingDiagnostics.recordIntegritySnapshot(
                snapshot = next,
                northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
                atElapsedMs = SystemClock.elapsedRealtime(),
            )
        }
        val interference = next.magneticQuality == CompassMagneticQuality.INTERFERENCE
        val interferenceChanged = _magneticInterference.value != interference
        _magneticInterference.value = interference
        val transition =
            previous.state != next.state ||
                previous.reason != next.reason ||
                previous.magneticQuality != next.magneticQuality ||
                previous.quarantineActive != next.quarantineActive ||
                previous.recoveryActive != next.recoveryActive
        if (transition) {
            logDiagnostics(
                "google_fused integrity origin=$origin state=${next.state.telemetryToken} " +
                    "reason=${next.reason.telemetryToken} renderable=${next.renderable} " +
                    "trusted=${next.trusted} magnetic=${next.magneticQuality.telemetryToken} " +
                    "fieldUt=${next.magneticFieldUt.formatOrNA(1)} " +
                    "disagreementDeg=${next.absoluteRelativeDisagreementDeg.formatOrNA(1)} " +
                    "spreadDeg=${next.residualSpreadDeg.formatOrNA(1)} " +
                    "quarantine=${next.quarantineActive} recovery=${next.recoveryActive}",
            )
        }
        if (transition || interferenceChanged) publishOwnRenderState()
    }

    @Suppress("LongParameterList")
    private fun recordHeadingEngineSample(
        absoluteHeadingDeg: Float,
        resolvedHeadingErrorDeg: Float,
        liveHeadingErrorDeg: Float,
        conservativeHeadingErrorDeg: Float,
        mappedAccuracy: Int,
        usable: Boolean,
        snapshot: FusedHeadingIntegritySnapshot,
        atElapsedMs: Long,
    ) {
        CompassHeadingDiagnostics.recordEngineSample(
            provider = HeadingSource.FUSED_ORIENTATION,
            providerHeadingDeg = absoluteHeadingDeg,
            resolvedHeadingErrorDeg = resolvedHeadingErrorDeg.takeIf(Float::isFinite),
            liveHeadingErrorDeg = liveHeadingErrorDeg.takeIf(Float::isFinite),
            conservativeHeadingErrorDeg = conservativeHeadingErrorDeg.takeIf(Float::isFinite),
            accuracy = mappedAccuracy,
            startupWarmup = snapshot.state == CompassTrackingState.ACQUIRING,
            usable = usable,
            snapshot = snapshot,
            northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
            atElapsedMs = atElapsedMs,
        )
    }

    private fun publishFusedHeadingIfDue(
        displayHeading: Float,
        nowElapsedMs: Long,
        mappedAccuracy: Int,
        headingErrorDeg: Float,
        conservativeHeadingErrorDeg: Float,
    ) {
        val activeTurn =
            !lowPowerMode &&
                activeTurnPublicationTracker.active
        if (
            !shouldPublishFusedHeading(
                nowElapsedMs = nowElapsedMs,
                lastPublishAtElapsedMs = lastFusedHeadingPublishAtElapsedMs,
                lowPowerMode = lowPowerMode,
                activeTurn = activeTurn,
                force = false,
            )
        ) {
            return
        }
        _heading.value = displayHeading
        _accuracy.value = mappedAccuracy
        _headingErrorDeg.value = headingErrorDeg.takeIf { it.isFinite() && it >= 0f }
        _conservativeHeadingErrorDeg.value =
            conservativeHeadingErrorDeg.takeIf { it.isFinite() && it >= 0f }
        _headingSampleElapsedRealtimeMs.value = nowElapsedMs
        _headingSampleStale.value = false
        fusedWarmupActive = false
        updateHeadingSourceState(HeadingSource.FUSED_ORIENTATION)
        lastFusedHeadingPublishAtElapsedMs = nowElapsedMs
        recordFusedPerfHeadingPublish(nowElapsedMs, activeTurn)
    }

    private fun forcePublishFusedHeading(
        displayHeading: Float,
        nowElapsedMs: Long,
        mappedAccuracy: Int,
        headingErrorDeg: Float,
        conservativeHeadingErrorDeg: Float,
    ) {
        lastFusedHeadingPublishAtElapsedMs = 0L
        publishFusedHeadingIfDue(
            displayHeading = displayHeading,
            nowElapsedMs = nowElapsedMs,
            mappedAccuracy = mappedAccuracy,
            headingErrorDeg = headingErrorDeg,
            conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
        )
    }

    private fun publishNorthReferenceStatus() {
        val status =
            NorthReferenceStatus(
                requestedMode = northReferenceMode,
                effectiveMode = northReferenceMode,
                declinationAvailable = false,
                waitingForDeclination = false,
                pipeline = HeadingPipeline.NONE,
                automaticByProvider = true,
            )
        if (_northReferenceStatus.value != status) {
            _northReferenceStatus.value = status
            logDiagnostics(
                "north_reference_status automaticByProvider=true " +
                    "northBasis=${CompassNorthBasis.GOOGLE_AUTOMATIC.telemetryToken}",
            )
        }
        publishOwnRenderState()
    }

    private fun updateHeadingSourceState(activeSource: HeadingSource) {
        if (_headingSource.value != activeSource) {
            _headingSource.value = activeSource
            logDiagnostics("heading_source ${activeSource.telemetryToken}")
        }
        val nextStatus =
            HeadingSourceStatus(
                requestedMode = CompassHeadingSourceMode.AUTO,
                activeSource = activeSource,
                headingSensorAvailable = googleOrientationAvailable,
                rotationVectorAvailable = false,
                magAccelFallbackAvailable = false,
            )
        if (_headingSourceStatus.value != nextStatus) {
            _headingSourceStatus.value = nextStatus
            logDiagnostics(
                "heading_source_status requested=${nextStatus.requestedMode.name} " +
                    "active=${nextStatus.activeSource.telemetryToken} " +
                    "headingAvailable=${nextStatus.headingSensorAvailable} " +
                    "rotVecAvailable=${nextStatus.rotationVectorAvailable} " +
                    "magFallbackAvailable=${nextStatus.magAccelFallbackAvailable}",
            )
        }
        publishOwnRenderState()
    }

    private fun publishOwnRenderState() {
        ownRenderState.value =
            CompassRenderState(
                providerType = providerType,
                headingDeg = _heading.value,
                accuracy = _accuracy.value,
                headingErrorDeg = _headingErrorDeg.value,
                conservativeHeadingErrorDeg = _conservativeHeadingErrorDeg.value,
                headingSampleElapsedRealtimeMs = _headingSampleElapsedRealtimeMs.value,
                headingSampleStale = _headingSampleStale.value,
                headingSource = _headingSource.value,
                headingSourceStatus = _headingSourceStatus.value,
                northReferenceStatus = _northReferenceStatus.value,
                magneticInterference = _magneticInterference.value,
                trackingState = latestIntegritySnapshot.state,
                trackingReason = latestIntegritySnapshot.reason,
                headingRenderable = latestIntegritySnapshot.renderable,
                headingTrusted = latestIntegritySnapshot.trusted,
                northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
                magneticQuality = latestIntegritySnapshot.magneticQuality,
                relativeHeadingDeg = latestIntegritySnapshot.relativeHeadingDeg,
            )
    }

    private fun startFallbackProvider(reason: String) {
        val previousState =
            if (awaitingFusedReady || lastOrientationRequestAtElapsedMs <= 0L) {
                "starting_fused"
            } else {
                "active_fused"
            }
        fusedWarmupActive = false
        if (_useFallbackProvider.value) {
            logDiagnostics("google_fused fallback refresh reason=$reason")
            configureFallbackProvider()
            if (started) {
                fallbackProvider.start(lowPower = lowPowerMode)
            }
            return
        }
        stopOrientationUpdates()
        awaitingFusedReady = false
        _useFallbackProvider.value = true
        logDiagnostics(
            "google_fused state transition=active_fallback from=$previousState " +
                "reason=$reason",
        )
        configureFallbackProvider()
        if (started) {
            fallbackProvider.start(lowPower = lowPowerMode)
        }
    }

    private fun stopFallbackProvider() {
        if (!_useFallbackProvider.value) return
        fallbackProvider.stop()
    }

    private fun stopOrientationUpdates() {
        integritySensorMonitor.stop()
        orientationRequestGeneration += 1L
        dispatchedOrientationRequestGeneration = 0L
        val listenerToRemove = activeOrientationListener
        activeOrientationListener = null
        awaitingFusedReady = false
        callbackHandler?.removeCallbacks(fusedSampleFreshnessRunnable)
        fusedFreshnessCheckScheduled = false
        callbackHandler?.removeCallbacks(fusedReadyTimeoutRunnable)
        orientationUpdatesRegistered = false
        if (listenerToRemove != null) {
            fusedOrientationClient.removeOrientationUpdates(listenerToRemove)
        }
    }

    private fun resetUnusableFusedSampleState() {
        consecutiveUnusableFusedSamples = 0
        firstUnusableFusedSampleAtElapsedMs = 0L
    }

    private fun currentSamplingPeriodMicros(): Long =
        if (lowPowerMode && !isRecalibrationBoostActive()) {
            FUSED_ORIENTATION_LOW_POWER_SAMPLING_MICROS
        } else {
            FUSED_ORIENTATION_HIGH_POWER_SAMPLING_MICROS
        }

    private fun isActiveOrientationRequest(requestGeneration: Long): Boolean =
        started &&
            !_useFallbackProvider.value &&
            requestGeneration == orientationRequestGeneration

    private fun isGoogleOrientationAvailable(): Boolean =
        GoogleApiAvailability
            .getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    private fun logDiagnostics(message: String) {
        if (!DebugTelemetry.isEnabled()) return
        DebugTelemetry.log(COMPASS_TELEMETRY_TAG, message)
    }

    private fun markHeadingPendingRestart(preserveRecentFusedHeading: Boolean) {
        callbackHandler?.removeCallbacks(fusedSampleFreshnessRunnable)
        fusedFreshnessCheckScheduled = false
        _headingSampleStale.value = _headingSampleElapsedRealtimeMs.value != null
        if (!preserveRecentFusedHeading) {
            _accuracy.value = SensorManager.SENSOR_STATUS_UNRELIABLE
            _headingErrorDeg.value = null
            _conservativeHeadingErrorDeg.value = null
            updateHeadingSourceState(HeadingSource.NONE)
        } else {
            publishOwnRenderState()
        }
    }

    private fun scheduleFusedSampleFreshnessTimeout(sampleAtElapsedMs: Long) {
        val handler = callbackHandler
        if (
            handler != null &&
            lastConfirmedFusedSampleElapsedRealtimeMs == sampleAtElapsedMs &&
            !fusedFreshnessCheckScheduled
        ) {
            fusedFreshnessCheckScheduled = true
            handler.postDelayed(
                fusedSampleFreshnessRunnable,
                FUSED_ORIENTATION_SAMPLE_STALE_MS,
            )
        }
    }

    private fun recentUsableFusedHeadingAgeMs(nowElapsedMs: Long): Long? {
        val sampleAtElapsedMs = _headingSampleElapsedRealtimeMs.value
        val hasUsableFusedState =
            !_useFallbackProvider.value &&
                !fusedWarmupActive &&
                _headingSource.value == HeadingSource.FUSED_ORIENTATION &&
                latestIntegritySnapshot.renderable
        return if (
            hasUsableFusedState && sampleAtElapsedMs != null
        ) {
            (nowElapsedMs - sampleAtElapsedMs)
                .coerceAtLeast(0L)
                .takeIf { it <= FUSED_WARM_RESTART_CACHED_HEADING_MAX_AGE_MS }
        } else {
            null
        }
    }

    private fun activateTemporaryHighPowerBoost() {
        if (!lowPowerMode) return
        val now = SystemClock.elapsedRealtime()
        recalibrationBoostUntilElapsedMs = now + FUSED_RECALIBRATION_HIGH_POWER_WINDOW_MS
        recalibrationBoostGeneration += 1L
        val generation = recalibrationBoostGeneration
        ensureCallbackHandler()
        val handler = callbackHandler ?: return
        logDiagnostics(
            "google_fused high_power_boost start durationMs=$FUSED_RECALIBRATION_HIGH_POWER_WINDOW_MS",
        )
        handler.postDelayed({
            if (generation != recalibrationBoostGeneration) return@postDelayed
            if (!started || _useFallbackProvider.value || !lowPowerMode) return@postDelayed
            if (isRecalibrationBoostActive()) return@postDelayed
            logDiagnostics("google_fused high_power_boost end")
            requestOrientationUpdates(forceRestart = true, reason = "high_power_boost_end")
        }, FUSED_RECALIBRATION_HIGH_POWER_WINDOW_MS)
    }

    private fun isRecalibrationBoostActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean = nowElapsedMs < recalibrationBoostUntilElapsedMs

    private fun resolveHeadingErrorDegrees(orientation: DeviceOrientation): Float {
        val liveErrorDeg = orientation.headingErrorDegrees
        if (liveErrorDeg.isFinite() && liveErrorDeg in 0f..<FUSED_INVALID_HEADING_ERROR_DEG) {
            return liveErrorDeg
        }
        if (orientation.hasConservativeHeadingErrorDegrees()) {
            val conservativeErrorDeg = orientation.conservativeHeadingErrorDegrees
            if (conservativeErrorDeg.isFinite() && conservativeErrorDeg >= 0f) {
                return conservativeErrorDeg
            }
        }
        return liveErrorDeg
    }

    private fun recordConfirmedFusedSample(nowElapsedMs: Long) {
        // Keep freshness tied to the full fused callback stream, but only publish render state
        // alongside the rate-limited heading in publishFusedHeadingIfDue(). This prevents
        // over-delivering devices from invalidating the 25 Hz UI publication cap.
        lastConfirmedFusedSampleElapsedRealtimeMs = nowElapsedMs
        val staleRecoveryHealthyMs =
            (nowElapsedMs - fusedStaleRecoveryStartedAtElapsedMs).coerceAtLeast(0L)
        if (
            fusedStaleRecoveryAttempted &&
            fusedStaleRecoveryStartedAtElapsedMs > 0L &&
            staleRecoveryHealthyMs >= FUSED_STALE_RECOVERY_HEALTHY_RESET_MS
        ) {
            fusedStaleRecoveryAttempted = false
            fusedStaleRecoveryStartedAtElapsedMs = 0L
            logDiagnostics(
                "google_fused stale_recovery_healthy durationMs=$staleRecoveryHealthyMs",
            )
        }
        recordFusedPerfConfirmed(nowElapsedMs)
        scheduleFusedSampleFreshnessTimeout(sampleAtElapsedMs = nowElapsedMs)
    }

    private fun publishUnusableFusedSampleState(
        headingErrorDeg: Float,
        conservativeHeadingErrorDeg: Float,
    ) {
        _accuracy.value = SensorManager.SENSOR_STATUS_UNRELIABLE
        _headingErrorDeg.value = headingErrorDeg.takeIf { it.isFinite() && it >= 0f }
        _conservativeHeadingErrorDeg.value =
            conservativeHeadingErrorDeg.takeIf { it.isFinite() && it >= 0f }
        _headingSampleStale.value = _headingSampleElapsedRealtimeMs.value != null
        publishOwnRenderState()
        recordFusedPerfUnusable(SystemClock.elapsedRealtime())
    }

    private fun logFusedSample(
        nowElapsedMs: Long,
        displayHeading: Float,
        headingErrorDeg: Float,
        liveHeadingErrorDeg: Float,
        conservativeHeadingErrorDeg: Float,
        mappedAccuracy: Int,
    ) {
        if (nowElapsedMs - lastFusedSampleLogAtElapsedMs < HEADING_DEBUG_SAMPLE_MS) return
        lastFusedSampleLogAtElapsedMs = nowElapsedMs
        val northStatus = _northReferenceStatus.value
        logDiagnostics(
            "google_fused sample heading=${displayHeading.format(1)} " +
                "errorDeg=${headingErrorDeg.formatOrNA(1)} " +
                "liveErrorDeg=${liveHeadingErrorDeg.formatOrNA(1)} " +
                "conservativeErrorDeg=${conservativeHeadingErrorDeg.formatOrNA(1)} " +
                "acc=$mappedAccuracy automaticRef=${northStatus.automaticByProvider} " +
                "northBasis=${CompassNorthBasis.GOOGLE_AUTOMATIC.telemetryToken}",
        )
    }

    private fun recordFusedPerfCallback(nowElapsedMs: Long) {
        if (!DebugTelemetry.isEnabled()) return
        ensureFusedPerfWindow(nowElapsedMs)
        fusedPerfCallbackCount += 1
        maybeLogFusedPerf(nowElapsedMs)
    }

    private fun recordFusedPerfConfirmed(nowElapsedMs: Long) {
        if (!DebugTelemetry.isEnabled()) return
        ensureFusedPerfWindow(nowElapsedMs)
        fusedPerfConfirmedCount += 1
        maybeLogFusedPerf(nowElapsedMs)
    }

    private fun recordFusedPerfUnusable(nowElapsedMs: Long) {
        if (!DebugTelemetry.isEnabled()) return
        ensureFusedPerfWindow(nowElapsedMs)
        fusedPerfUnusableCount += 1
        maybeLogFusedPerf(nowElapsedMs)
    }

    private fun recordFusedPerfHeadingPublish(
        nowElapsedMs: Long,
        activeTurn: Boolean,
    ) {
        if (!DebugTelemetry.isEnabled()) return
        ensureFusedPerfWindow(nowElapsedMs)
        fusedPerfHeadingPublishCount += 1
        if (activeTurn) fusedPerfActiveTurnPublishCount += 1
        maybeLogFusedPerf(nowElapsedMs)
    }

    private fun ensureFusedPerfWindow(nowElapsedMs: Long) {
        if (fusedPerfWindowStartElapsedMs == 0L) {
            fusedPerfWindowStartElapsedMs = nowElapsedMs
        }
    }

    private fun maybeLogFusedPerf(nowElapsedMs: Long) {
        val windowStart = fusedPerfWindowStartElapsedMs
        if (windowStart == 0L) return
        val windowMs = (nowElapsedMs - windowStart).coerceAtLeast(0L)
        if (windowMs < FUSED_PERF_LOG_WINDOW_MS) return
        val seconds = (windowMs / 1000f).coerceAtLeast(0.001f)
        logDiagnostics(
            "google_fused perf windowMs=$windowMs " +
                "callbacks=$fusedPerfCallbackCount callbackHz=${(fusedPerfCallbackCount / seconds).format(1)} " +
                "confirmed=$fusedPerfConfirmedCount confirmedHz=${(fusedPerfConfirmedCount / seconds).format(1)} " +
                "unusable=$fusedPerfUnusableCount " +
                "headingPublishes=$fusedPerfHeadingPublishCount " +
                "activeTurnPublishes=$fusedPerfActiveTurnPublishCount " +
                "publishHz=${(fusedPerfHeadingPublishCount / seconds).format(1)}",
        )
        resetFusedPerfCounters(nowElapsedMs)
    }

    private fun resetFusedPerfCounters(windowStartElapsedMs: Long = 0L) {
        fusedPerfWindowStartElapsedMs = windowStartElapsedMs
        fusedPerfCallbackCount = 0
        fusedPerfConfirmedCount = 0
        fusedPerfUnusableCount = 0
        fusedPerfHeadingPublishCount = 0
        fusedPerfActiveTurnPublishCount = 0
    }
}

internal data class FusedUnusableHeadingState(
    val consecutiveSamples: Int,
    val firstSampleAtElapsedMs: Long,
)

internal data class FusedUnusableHeadingUpdate(
    val state: FusedUnusableHeadingState,
    val durationMs: Long,
    val shouldFallback: Boolean,
)

private data class FusedFallbackDeclinationSeed(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
)

internal fun isUsableGoogleFusedHeadingError(
    headingErrorDeg: Float,
): Boolean = headingErrorDeg.isFinite() && headingErrorDeg in 0f..<FUSED_INVALID_HEADING_ERROR_DEG

internal fun isUsableGoogleFusedOrientationSample(
    headingDeg: Float,
    headingErrorDeg: Float,
): Boolean = headingDeg.isFinite() && isUsableGoogleFusedHeadingError(headingErrorDeg)

internal fun computeFusedUnusableHeadingUpdate(
    nowElapsedMs: Long,
    consecutiveUnusableSamples: Int,
    firstUnusableSampleAtElapsedMs: Long,
    minSamples: Int,
    minDurationMs: Long,
): FusedUnusableHeadingUpdate {
    val nextFirstSampleAtElapsedMs =
        if (consecutiveUnusableSamples <= 0 || firstUnusableSampleAtElapsedMs <= 0L) {
            nowElapsedMs
        } else {
            firstUnusableSampleAtElapsedMs
        }
    val nextConsecutiveSamples = consecutiveUnusableSamples + 1
    val durationMs = (nowElapsedMs - nextFirstSampleAtElapsedMs).coerceAtLeast(0L)
    return FusedUnusableHeadingUpdate(
        state =
            FusedUnusableHeadingState(
                consecutiveSamples = nextConsecutiveSamples,
                firstSampleAtElapsedMs = nextFirstSampleAtElapsedMs,
            ),
        durationMs = durationMs,
        shouldFallback = nextConsecutiveSamples >= minSamples && durationMs >= minDurationMs,
    )
}

private const val FUSED_ORIENTATION_THREAD_NAME = "FusedOrientationThread"
private const val FUSED_ORIENTATION_HIGH_POWER_SAMPLING_MICROS = 20_000L // 50 Hz
private const val FUSED_ORIENTATION_LOW_POWER_SAMPLING_MICROS = 200_000L // 5 Hz
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val FUSED_INVALID_HEADING_ERROR_DEG = 180f
private const val FUSED_ORIENTATION_SAMPLE_STALE_MS = 1_500L
private const val FUSED_STALE_RECOVERY_HEALTHY_RESET_MS = 5_000L
private const val FUSED_PERF_LOG_WINDOW_MS = 5_000L
private const val FUSED_NORMAL_PUBLISH_MIN_INTERVAL_MS = 40L // 25 Hz
private const val FUSED_ACTIVE_TURN_PUBLISH_MIN_INTERVAL_MS = 20L // 50 Hz
private const val FUSED_LOW_POWER_PUBLISH_MIN_INTERVAL_MS = 180L
private const val FUSED_ACTIVE_TURN_MIN_STEP_DEG = 0.4f
private const val FUSED_ACTIVE_TURN_ENTER_RATE_DEG_PER_SEC = 30f
private const val FUSED_ACTIVE_TURN_EXIT_RATE_DEG_PER_SEC = 15f
private const val FUSED_ACTIVE_TURN_EXIT_HOLD_MS = 300L
private const val FUSED_ACTIVE_TURN_MAX_SAMPLE_GAP_MS = 300L
private const val FUSED_RECALIBRATION_HIGH_POWER_WINDOW_MS = 6_000L
private const val FUSED_WARM_RESTART_CACHED_HEADING_MAX_AGE_MS = 5_000L
private const val FUSED_UNUSABLE_HEADING_FALLBACK_MIN_SAMPLES = 5
private const val FUSED_UNUSABLE_HEADING_FALLBACK_MIN_DURATION_MS = 1_200L

internal fun shouldPublishFusedHeading(
    nowElapsedMs: Long,
    lastPublishAtElapsedMs: Long,
    lowPowerMode: Boolean,
    activeTurn: Boolean = false,
    force: Boolean,
): Boolean {
    if (force || lastPublishAtElapsedMs <= 0L) return true
    val minimumIntervalMs =
        if (lowPowerMode) {
            FUSED_LOW_POWER_PUBLISH_MIN_INTERVAL_MS
        } else if (activeTurn) {
            FUSED_ACTIVE_TURN_PUBLISH_MIN_INTERVAL_MS
        } else {
            FUSED_NORMAL_PUBLISH_MIN_INTERVAL_MS
        }
    return nowElapsedMs - lastPublishAtElapsedMs >= minimumIntervalMs
}
