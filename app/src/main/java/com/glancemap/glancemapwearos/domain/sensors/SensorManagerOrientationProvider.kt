package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceProviderSample
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicBoolean

internal data class SensorHeadingSampleFreshness(
    val sampleAtElapsedRealtimeMs: Long? = null,
    val stale: Boolean = true,
) {
    fun markStale(): SensorHeadingSampleFreshness = copy(stale = true)

    companion object {
        fun afterPublish(sampleAtElapsedRealtimeMs: Long): SensorHeadingSampleFreshness =
            SensorHeadingSampleFreshness(
                sampleAtElapsedRealtimeMs = sampleAtElapsedRealtimeMs,
                stale = false,
            )
    }
}

private data class SensorPublishedHeadingSample(
    val headingDeg: Float = 0f,
    val freshness: SensorHeadingSampleFreshness = SensorHeadingSampleFreshness(),
)

internal class SensorManagerOrientationProvider(
    context: Context,
) : CompassOrientationProvider,
    SensorEventListener {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    private val sensorRegistrar = CompassSensorRegistrar(appContext)
    private val headingProcessor = CompassHeadingProcessor()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val providerType: CompassProviderType = CompassProviderType.SENSOR_MANAGER

    private val _publishedHeadingSample = MutableStateFlow(SensorPublishedHeadingSample())
    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_UNRELIABLE)
    private val _headingSource = MutableStateFlow(HeadingSource.NONE)
    private val _headingSourceStatus =
        MutableStateFlow(
            HeadingSourceStatus(
                requestedMode = CompassHeadingSourceMode.AUTO,
                activeSource = HeadingSource.NONE,
                headingSensorAvailable = sensorRegistrar.availability.headingSensorAvailable,
                rotationVectorAvailable = sensorRegistrar.availability.rotationVectorAvailable,
                magAccelFallbackAvailable = sensorRegistrar.availability.magAccelFallbackAvailable,
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
            ),
        )

    private val declinationController =
        CompassDeclinationController(
            appContext = appContext,
            locationManager = locationManager,
            onStatusChanged = ::publishNorthReferenceStatus,
            logDiagnostics = ::logDiagnostics,
        )

    private val _magneticInterference = MutableStateFlow(false)

    private val baseRenderState =
        combine(
            _publishedHeadingSample,
            _accuracy,
            _headingSource,
            _headingSourceStatus,
            _northReferenceStatus,
        ) { headingSample, accuracy, headingSource, headingSourceStatus, northReferenceStatus ->
            CompassRenderState(
                providerType = providerType,
                headingDeg = headingSample.headingDeg,
                accuracy = accuracy,
                headingErrorDeg = null,
                conservativeHeadingErrorDeg = null,
                headingSampleElapsedRealtimeMs =
                    headingSample.freshness.sampleAtElapsedRealtimeMs,
                headingSampleStale = headingSample.freshness.stale,
                headingSource = headingSource,
                headingSourceStatus = headingSourceStatus,
                northReferenceStatus = northReferenceStatus,
                magneticInterference = false,
            )
        }

    override val renderState: StateFlow<CompassRenderState> =
        combine(
            baseRenderState,
            _magneticInterference,
        ) { baseState, magneticInterference ->
            val renderable =
                baseState.headingSource != HeadingSource.NONE &&
                    !baseState.headingSampleStale &&
                    baseState.headingDeg.isFinite()
            baseState.copy(
                magneticInterference = magneticInterference,
                trackingState =
                    when {
                        magneticInterference -> CompassTrackingState.DEGRADED
                        renderable -> CompassTrackingState.TRACKING
                        else -> CompassTrackingState.ACQUIRING
                    },
                trackingReason =
                    when {
                        magneticInterference -> CompassTrackingReason.MAGNETIC_INTERFERENCE
                        renderable -> CompassTrackingReason.STABLE
                        else -> CompassTrackingReason.STARTUP
                    },
                headingRenderable = renderable,
                headingTrusted =
                    renderable &&
                        !magneticInterference &&
                        baseState.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                northBasis =
                    if (baseState.northReferenceStatus.effectiveMode == NorthReferenceMode.TRUE) {
                        CompassNorthBasis.TRUE_APP_DECLINATION
                    } else {
                        CompassNorthBasis.MAGNETIC
                    },
                magneticQuality =
                    when {
                        magneticInterference -> CompassMagneticQuality.INTERFERENCE
                        renderable -> CompassMagneticQuality.GOOD
                        else -> CompassMagneticQuality.UNKNOWN
                    },
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(0),
            initialValue =
                initialCompassRenderState(
                    providerType = providerType,
                    headingSensorAvailable = sensorRegistrar.availability.headingSensorAvailable,
                    rotationVectorAvailable = sensorRegistrar.availability.rotationVectorAvailable,
                    magAccelFallbackAvailable = sensorRegistrar.availability.magAccelFallbackAvailable,
                ).copy(headingSampleStale = true),
        )

    // Raw heading pushed from sensor callbacks
    private val rawHeadingFlow = MutableStateFlow<Float?>(null)

    // --- Fallback fusion buffers (accel + mag) ---
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    // Matrices/orientation
    private val rotationMatrix = FloatArray(9)
    private val rotationMatrixRemapped = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var smoothingJob: Job? = null
    private val resetSmoothingRequested = AtomicBoolean(false)

    @Volatile private var usingHeadingSensor = false

    @Volatile private var usingRotationVector = false

    @Volatile private var usingMagAccelFallback = false

    @Volatile private var started = false
    private var startAtMs = 0L

    @Volatile private var sensorRateMode = SensorRateMode.HIGH

    @Volatile private var cachedDisplayRotation: Int = Surface.ROTATION_0
    private var lastDisplayRotationSampleAtMs: Long = 0L
    private var lastHeadingDebugLogAtMs: Long = 0L

    @Volatile private var headingRelockUntilElapsedMs: Long = 0L

    @Volatile private var startupStabilizationUntilElapsedMs: Long = 0L

    // Prevent “crazy” first readings after start/wake
    private val settleWindowMs = 350L

    // Track accuracies
    private var headingAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var headingUncertaintyDeg: Float = Float.NaN
    private var magAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var rotVecAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var rotVecHeadingUncertaintyDeg: Float = Float.NaN

    @Volatile private var inferredHeadingAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    @Volatile private var activeHeadingSource: HeadingSource = HeadingSource.NONE

    @Volatile private var pendingBootstrapRawSamplesToIgnore: Int = 0

    @Volatile private var pendingStartupBogusSamplesToIgnore: Int = 0

    @Volatile private var pendingStartupHeadingPublishesToMask: Int = 0

    @Volatile private var startupHeadingPublishMaskUntilElapsedMs: Long = 0L
    private var magneticFieldStrengthUt: Float = Float.NaN
    private var magneticFieldStrengthEmaUt: Float = Float.NaN
    private var magneticInterferenceHoldUntilElapsedMs: Long = 0L
    private var magneticInterferenceStartupGraceUntilElapsedMs: Long = 0L
    private var magneticInterferenceDetected: Boolean = false
    private var hasPublishedHeading = false

    // Magnetic declination (degrees) used to convert magnetic north -> true north.
    @Volatile private var northReferenceMode: NorthReferenceMode = NorthReferenceMode.TRUE

    @Volatile private var headingSourceMode: CompassHeadingSourceMode = CompassHeadingSourceMode.AUTO

    @Volatile private var sensorCallbackThread: HandlerThread? = null

    @Volatile private var sensorCallbackHandler: Handler? = null

    private var sensorCallbackThreadStopping = false

    private var sensorThreadQuitRequested = false

    private var sensorThreadGeneration = 0L

    private var sensorRegistrationGeneration = 0L

    private fun ensureSensorCallbackHandler(): Handler {
        sensorCallbackHandler
            ?.takeIf {
                shouldReuseSensorCallbackHandler(
                    callbackThreadAlive = it.looper.thread.isAlive,
                    callbackThreadStopping = sensorCallbackThreadStopping,
                )
            }?.let { return it }
        val t = HandlerThread(COMPASS_SENSOR_THREAD_NAME).apply { start() }
        sensorCallbackThread = t
        val h = Handler(t.looper)
        sensorCallbackHandler = h
        sensorCallbackThreadStopping = false
        sensorThreadQuitRequested = false
        sensorThreadGeneration += 1L
        logSensorThreadLifecycle(event = "created", thread = t)
        return h
    }

    @Synchronized
    override fun start(lowPower: Boolean) {
        val requestedMode = if (lowPower) SensorRateMode.LOW else SensorRateMode.HIGH
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (started) {
            if (sensorRateMode != requestedMode) {
                sensorRateMode = requestedMode
                // Treat mode-driven rate changes (north-up <-> compass follow) like a soft relock.
                // This prevents transient first samples from leaking into visible heading.
                registerSensorsForCurrentMode(resetHeadingState = true)
            }
            return
        }

        applyHeadingPipeline(resolveHeadingPipeline())
        started = true
        startAtMs = nowElapsedMs
        sensorRateMode = requestedMode
        cachedDisplayRotation = queryDisplayRotation(windowManager)
        lastDisplayRotationSampleAtMs = startAtMs
        declinationController.maybeInitializeFromCache()
        declinationController.maybeInitializeFromLastKnownLocation()

        // Reset init so we snap to first good value cleanly
        resetSmoothingRequested.set(false)
        rawHeadingFlow.value = null

        // Reset fallback flags
        hasGravity = false
        hasGeomagnetic = false

        // Reset accuracies
        headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        headingUncertaintyDeg = Float.NaN
        magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotVecHeadingUncertaintyDeg = Float.NaN
        inferredHeadingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        magneticFieldStrengthUt = Float.NaN
        magneticFieldStrengthEmaUt = Float.NaN
        magneticInterferenceHoldUntilElapsedMs = 0L
        magneticInterferenceStartupGraceUntilElapsedMs = 0L
        magneticInterferenceDetected = false
        _magneticInterference.value = false
        publishAccuracyFromCurrentSignals()
        publishHeadingSourceFromCurrentMode()
        publishNorthReferenceStatus()
        lastHeadingDebugLogAtMs = 0L
        armHeadingRelockWindow(nowElapsedMs = nowElapsedMs, reason = "start")
        armMagneticInterferenceStartupGraceWindow(
            nowElapsedMs = nowElapsedMs,
            reason = "start",
        )

        registerSensorsForCurrentMode(resetHeadingState = true)
        logDiagnostics(
            "start mode=$sensorRateMode usingHeadingSensor=$usingHeadingSensor " +
                "usingRotationVector=$usingRotationVector " +
                "usingMagAccel=$usingMagAccelFallback " +
                "northReference=$northReferenceMode sourceMode=$headingSourceMode",
        )

        startSmoothing()
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        _publishedHeadingSample.value =
            _publishedHeadingSample.value.copy(
                freshness = _publishedHeadingSample.value.freshness.markStale(),
            )

        sensorRegistrar.unregister(this)
        val callbackThreadToStop = sensorCallbackThread
        sensorCallbackThreadStopping = true
        sensorThreadQuitRequested = true
        sensorCallbackHandler = null
        sensorCallbackThread = null
        logSensorThreadLifecycle(event = "quit_requested", thread = callbackThreadToStop)
        callbackThreadToStop?.quitSafely()

        smoothingJob?.cancel()
        smoothingJob = null

        hasGravity = false
        hasGeomagnetic = false

        rawHeadingFlow.value = null

        headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        headingUncertaintyDeg = Float.NaN
        magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotVecHeadingUncertaintyDeg = Float.NaN
        inferredHeadingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        magneticFieldStrengthUt = Float.NaN
        magneticFieldStrengthEmaUt = Float.NaN
        magneticInterferenceHoldUntilElapsedMs = 0L
        magneticInterferenceStartupGraceUntilElapsedMs = 0L
        magneticInterferenceDetected = false
        _magneticInterference.value = false
        activeHeadingSource = HeadingSource.NONE
        _headingSource.value = HeadingSource.NONE
        usingHeadingSensor = false
        usingRotationVector = false
        usingMagAccelFallback = false
        headingRelockUntilElapsedMs = 0L
        startupStabilizationUntilElapsedMs = 0L
        pendingBootstrapRawSamplesToIgnore = 0
        pendingStartupBogusSamplesToIgnore = 0
        pendingStartupHeadingPublishesToMask = 0
        startupHeadingPublishMaskUntilElapsedMs = 0L
        publishAccuracyFromCurrentSignals()
        publishHeadingSourceFromCurrentMode()
        publishNorthReferenceStatus()
        logDiagnostics("stop")
    }

    override fun recalibrate() {
        // Request smoothing reset on sensor-processing thread (not hardware calibration).
        resetSmoothingRequested.set(true)
        logDiagnostics("recalibrate requested")
    }

    override fun setNorthReferenceMode(
        mode: NorthReferenceMode,
        forceRefresh: Boolean,
    ) {
        val previousMode = northReferenceMode
        val modeChanged = previousMode != mode
        if (!modeChanged && !forceRefresh) return

        val previousPipeline = currentHeadingPipeline()
        if (modeChanged) {
            northReferenceMode = mode
            val remappedHeading =
                remapHeadingForNorthReferenceSwitch(
                    currentHeadingDeg = _publishedHeadingSample.value.headingDeg,
                    fromMode = previousMode,
                    toMode = mode,
                    declinationDeg = declinationController.currentDeclination,
                )
            if (remappedHeading.isFinite()) {
                _publishedHeadingSample.value =
                    _publishedHeadingSample.value.copy(headingDeg = remappedHeading)
                rawHeadingFlow.value = remappedHeading
            }
        }
        if (started) {
            val resolvedPipeline = resolveHeadingPipeline()
            val sourceChanged = resolvedPipeline != previousPipeline
            if (sourceChanged) {
                applyHeadingPipeline(resolvedPipeline)
                headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
                headingUncertaintyDeg = Float.NaN
                rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
                rotVecHeadingUncertaintyDeg = Float.NaN
                registerSensorsForCurrentMode(resetHeadingState = true)
            }
            if (modeChanged || sourceChanged) {
                // Re-snap smoothing only when the effective heading basis changed.
                resetSmoothingRequested.set(true)
            } else if (forceRefresh && !modeChanged) {
                rawHeadingFlow.value = _publishedHeadingSample.value.headingDeg
            }
        }
        publishNorthReferenceStatus()
        logDiagnostics(
            "north reference mode=$mode changed=$modeChanged forceRefresh=$forceRefresh " +
                "usingHeadingSensor=$usingHeadingSensor usingRotationVector=$usingRotationVector " +
                "usingMagAccel=$usingMagAccelFallback " +
                "sourceMode=$headingSourceMode decl=${declinationController.currentDeclination.formatOrNA(2)} " +
                "heading=${_publishedHeadingSample.value.headingDeg.format(1)}",
        )
    }

    override fun setHeadingSourceMode(
        mode: CompassHeadingSourceMode,
        forceRefresh: Boolean,
    ) {
        val modeChanged = headingSourceMode != mode
        if (!modeChanged && !forceRefresh) return
        val previousPipeline = currentHeadingPipeline()
        headingSourceMode = mode
        if (!started) {
            publishHeadingSourceFromCurrentMode()
        }

        if (started) {
            val resolvedPipeline = resolveHeadingPipeline()
            val sourceChanged = resolvedPipeline != previousPipeline
            if (sourceChanged) {
                applyHeadingPipeline(resolvedPipeline)
                headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
                headingUncertaintyDeg = Float.NaN
                rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
                rotVecHeadingUncertaintyDeg = Float.NaN
                registerSensorsForCurrentMode(resetHeadingState = true)
            }
            if (sourceChanged) {
                // Re-snap only when the effective sensor pipeline changed.
                resetSmoothingRequested.set(true)
            } else if (forceRefresh && !sourceChanged) {
                rawHeadingFlow.value = _publishedHeadingSample.value.headingDeg
            }
        }
        publishHeadingSourceFromCurrentMode()
        publishNorthReferenceStatus()
        logDiagnostics(
            "heading source mode=$mode changed=$modeChanged forceRefresh=$forceRefresh " +
                "usingHeadingSensor=$usingHeadingSensor usingRotationVector=$usingRotationVector " +
                "usingMagAccel=$usingMagAccelFallback " +
                "northReference=$northReferenceMode",
        )
    }

    override fun primeDeclinationFromApproximateLocation(
        latitude: Double,
        longitude: Double,
        altitudeM: Float,
    ) {
        declinationController.primeFromApproximateLocation(
            latitude = latitude,
            longitude = longitude,
            altitudeM = altitudeM,
        )
    }

    override fun updateDeclinationFromLocation(location: Location) {
        declinationController.updateFromLocation(location)
    }

    override fun setLowPowerMode(enabled: Boolean) {
        val requestedMode = if (enabled) SensorRateMode.LOW else SensorRateMode.HIGH
        if (sensorRateMode == requestedMode) return
        sensorRateMode = requestedMode
        if (started) {
            registerSensorsForCurrentMode(resetHeadingState = true)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started) return
        maybeRefreshDisplayRotation()
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            updateMagneticInterference(values = event.values)
        }

        if (usingHeadingSensor) {
            if (event.sensor.type != HEADING_SENSOR_TYPE) return
            val headingDeg = event.values.firstOrNull()
            if (headingDeg == null || !headingDeg.isFinite()) return
            if (event.values.size > 1) {
                headingUncertaintyDeg = event.values[1]
                publishAccuracyFromCurrentSignals()
            }
            val normalized =
                declinationController.headingSensorHeadingWithNorthReference(
                    northReferenceMode = northReferenceMode,
                    headingDeg = headingDeg,
                )
            rawHeadingFlow.value = normalized
            recordDeepTraceHeading(normalized)
            maybeLogHeadingSample(normalized)
            return
        }

        if (usingRotationVector) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

            updateRotationVectorUncertainty(values = event.values)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            remapForDisplayRotation(
                rotation = cachedDisplayRotation,
                inR = rotationMatrix,
                outR = rotationMatrixRemapped,
            )
            SensorManager.getOrientation(rotationMatrixRemapped, orientationAngles)

            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val normalized =
                headingWithNorthReference(
                    azimuthDeg = azimuthDeg,
                    declinationDeg = declinationController.resolveCorrection(northReferenceMode),
                    northReferenceMode = northReferenceMode,
                )
            rawHeadingFlow.value = normalized
            recordDeepTraceHeading(normalized)
            maybeLogHeadingSample(normalized)
            return
        }

        if (!usingMagAccelFallback) return

        // ---- Fallback heading: accel + mag ----
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = event.values[0]
                geomagnetic[1] = event.values[1]
                geomagnetic[2] = event.values[2]
                hasGeomagnetic = true
            }
            else -> return
        }

        if (!hasGravity || !hasGeomagnetic) return

        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
        if (!success) return

        remapForDisplayRotation(
            rotation = cachedDisplayRotation,
            inR = rotationMatrix,
            outR = rotationMatrixRemapped,
        )
        SensorManager.getOrientation(rotationMatrixRemapped, orientationAngles)

        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        val normalized =
            headingWithNorthReference(
                azimuthDeg = azimuthDeg,
                declinationDeg = declinationController.resolveCorrection(northReferenceMode),
                northReferenceMode = northReferenceMode,
            )
        rawHeadingFlow.value = normalized
        recordDeepTraceHeading(normalized)
        maybeLogHeadingSample(normalized)
    }

    private fun recordDeepTraceHeading(headingDeg: Float) {
        if (!CompassDeepTraceDiagnostics.state.value.active) return
        val accuracy = _accuracy.value
        val errorDeg =
            when {
                usingHeadingSensor -> headingUncertaintyDeg
                usingRotationVector -> rotVecHeadingUncertaintyDeg
                else -> Float.NaN
            }
        CompassDeepTraceDiagnostics.recordProviderSample(
            CompassDeepTraceProviderSample(
                provider = "sensor_manager",
                headingDeg = headingDeg,
                headingErrorDeg = errorDeg.takeIf(Float::isFinite),
                accuracy = accuracy,
                startupWarmup = false,
                usable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE,
                atElapsedMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) {
        if (!started) return

        when (sensor.type) {
            HEADING_SENSOR_TYPE -> {
                val previous = headingAccuracy
                headingAccuracy = accuracy
                publishAccuracyFromCurrentSignals()
                if (previous != accuracy) {
                    logDiagnostics("accuracy heading=$accuracy uncertaintyDeg=${headingUncertaintyDeg.formatOrNA(1)}")
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val previousMagAccuracy = magAccuracy
                magAccuracy = accuracy
                publishAccuracyFromCurrentSignals()
                if (previousMagAccuracy != accuracy) {
                    logDiagnostics("accuracy magnetometer=$accuracy")
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val previousRotVecAccuracy = rotVecAccuracy
                // Some devices report this, many don't.
                rotVecAccuracy = accuracy
                publishAccuracyFromCurrentSignals()
                if (previousRotVecAccuracy != accuracy) {
                    logDiagnostics("accuracy rotationVector=$accuracy")
                }
            }
        }
    }

    private fun startSmoothing() {
        if (smoothingJob?.isActive == true) return
        smoothingJob =
            headingProcessor.launch(
                scope = scope,
                rawHeadingFlow = rawHeadingFlow,
                settleWindowMs = settleWindowMs,
                getStartAtMs = { startAtMs },
                getHeadingRelockUntilElapsedMs = { headingRelockUntilElapsedMs },
                consumeResetSmoothingRequested = { resetSmoothingRequested.getAndSet(false) },
                getDisplayedHeading = { _publishedHeadingSample.value.headingDeg },
                publishDisplayedHeading = { heading ->
                    _publishedHeadingSample.value =
                        SensorPublishedHeadingSample(
                            headingDeg = heading,
                            freshness =
                                SensorHeadingSampleFreshness.afterPublish(
                                    sampleAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                                ),
                        )
                    hasPublishedHeading = true
                },
                getPendingBootstrapRawSamplesToIgnore = { pendingBootstrapRawSamplesToIgnore },
                setPendingBootstrapRawSamplesToIgnore = { pendingBootstrapRawSamplesToIgnore = it },
                getPendingStartupBogusSamplesToIgnore = { pendingStartupBogusSamplesToIgnore },
                setPendingStartupBogusSamplesToIgnore = { pendingStartupBogusSamplesToIgnore = it },
                getPendingStartupHeadingPublishesToMask = { pendingStartupHeadingPublishesToMask },
                setPendingStartupHeadingPublishesToMask = { pendingStartupHeadingPublishesToMask = it },
                getStartupStabilizationUntilElapsedMs = { startupStabilizationUntilElapsedMs },
                getStartupHeadingPublishMaskUntilElapsedMs = { startupHeadingPublishMaskUntilElapsedMs },
                isUsingRotationVector = { usingRotationVector },
                isUsingHeadingSensor = { usingHeadingSensor },
                updateInferredHeadingAccuracy = ::updateInferredHeadingAccuracy,
                logDiagnostics = ::logDiagnostics,
            )
    }

    private fun maybeRefreshDisplayRotation() {
        val update =
            computeCompassDisplayRotationUpdate(
                windowManager = windowManager,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                lastSampleAtMs = lastDisplayRotationSampleAtMs,
            ) ?: return
        cachedDisplayRotation = update.rotation
        lastDisplayRotationSampleAtMs = update.sampledAtMs
    }

    private fun maybeLogHeadingSample(rawHeading: Float) {
        val update =
            buildCompassHeadingLogUpdate(
                rawHeading = rawHeading,
                pendingBootstrapRawSamplesToIgnore = pendingBootstrapRawSamplesToIgnore,
                lastHeadingDebugLogAtMs = lastHeadingDebugLogAtMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                smoothedHeading = _publishedHeadingSample.value.headingDeg,
                combinedAccuracy = _accuracy.value,
                sensorReportedAccuracy = resolveSensorReportedAccuracy(),
                inferredHeadingAccuracy = inferredHeadingAccuracy,
                declinationDeg = declinationController.currentDeclination,
                northReferenceMode = northReferenceMode,
                sensorRateMode = sensorRateMode,
                northStatus = _northReferenceStatus.value,
                activeHeadingSource = activeHeadingSource,
                headingSourceMode = headingSourceMode,
                magneticFieldStrengthEmaUt = magneticFieldStrengthEmaUt,
                magneticInterferenceDetected = magneticInterferenceDetected,
            ) ?: return
        lastHeadingDebugLogAtMs = update.sampledAtMs
        logDiagnostics(update.message)
    }

    private fun logDiagnostics(message: String) {
        if (!DebugTelemetry.isEnabled()) return
        DebugTelemetry.log(COMPASS_TELEMETRY_TAG, message)
    }

    private fun logSensorThreadLifecycle(
        event: String,
        thread: HandlerThread?,
    ) {
        @Suppress("DEPRECATION")
        val threadId = thread?.id ?: -1L
        logDiagnostics(
            "sensor_thread event=$event " +
                "sensorThreadCreated=${event == "created"} " +
                "sensorThreadId=$threadId " +
                "sensorThreadAlive=${thread?.isAlive ?: false} " +
                "sensorLooperQuitting=$sensorThreadQuitRequested " +
                "sensorThreadQuitRequested=$sensorThreadQuitRequested " +
                "sensorThreadGeneration=$sensorThreadGeneration " +
                "sensorRegistrationGeneration=$sensorRegistrationGeneration",
        )
    }

    private fun updateMagneticInterference(values: FloatArray) {
        val update =
            computeCompassMagneticInterferenceUpdate(
                values = values,
                magneticFieldStrengthUt = magneticFieldStrengthUt,
                magneticFieldStrengthEmaUt = magneticFieldStrengthEmaUt,
                magneticInterferenceHoldUntilElapsedMs = magneticInterferenceHoldUntilElapsedMs,
                magneticInterferenceDetected = magneticInterferenceDetected,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                startupGraceUntilElapsedMs = magneticInterferenceStartupGraceUntilElapsedMs,
                sensorAccuracy = resolveSensorReportedAccuracy(),
                inferredAccuracy = inferredHeadingAccuracy,
                usingRotationVector = usingRotationVector,
                usingHeadingSensor = usingHeadingSensor,
            ) ?: return
        magneticFieldStrengthUt = update.state.strengthUt
        magneticFieldStrengthEmaUt = update.state.emaUt
        magneticInterferenceHoldUntilElapsedMs = update.state.holdUntilElapsedMs
        magneticInterferenceDetected = update.state.detected
        _magneticInterference.value = update.state.detected
        if (_accuracy.value != update.combinedAccuracy) {
            _accuracy.value = update.combinedAccuracy
        }
        update.logMessage?.let(::logDiagnostics)
    }

    @Synchronized
    private fun registerSensorsForCurrentMode(resetHeadingState: Boolean) {
        sensorRegistrar.unregister(this)
        if (!started) return
        val pipeline = currentHeadingPipeline()
        if (started && resetHeadingState) {
            _publishedHeadingSample.value =
                _publishedHeadingSample.value.copy(
                    freshness = _publishedHeadingSample.value.freshness.markStale(),
                )
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val prep =
                prepareCompassRegistrationResetState(
                    nowElapsedMs = nowElapsedMs,
                    pipeline = pipeline,
                    hasPreviousPublishedHeading = hasPublishedHeading,
                    currentHeadingRelockUntilElapsedMs = headingRelockUntilElapsedMs,
                    currentMagneticInterferenceStartupGraceUntilElapsedMs =
                    magneticInterferenceStartupGraceUntilElapsedMs,
                )
            headingRelockUntilElapsedMs = prep.headingRelockUntilElapsedMs
            magneticInterferenceStartupGraceUntilElapsedMs =
                prep.magneticInterferenceStartupGraceUntilElapsedMs
            resetSmoothingRequested.set(true)
            pendingBootstrapRawSamplesToIgnore = prep.pendingBootstrapRawSamplesToIgnore
            startupStabilizationUntilElapsedMs = prep.startupStabilizationUntilElapsedMs
            pendingStartupBogusSamplesToIgnore = prep.pendingStartupBogusSamplesToIgnore
            pendingStartupHeadingPublishesToMask = prep.pendingStartupHeadingPublishesToMask
            startupHeadingPublishMaskUntilElapsedMs = prep.startupHeadingPublishMaskUntilElapsedMs
            if (magneticInterferenceDetected) {
                magneticInterferenceDetected = false
                _magneticInterference.value = false
                publishAccuracyFromCurrentSignals()
            }
            magneticInterferenceHoldUntilElapsedMs = 0L
            magneticFieldStrengthUt = Float.NaN
            magneticFieldStrengthEmaUt = Float.NaN
            logDiagnostics(
                "heading_relock armed reason=register_$sensorRateMode " +
                    "windowMs=$HEADING_RELOCK_WINDOW_MS until=$headingRelockUntilElapsedMs",
            )
            logDiagnostics(
                "magnetic_interference_grace armed reason=register_$sensorRateMode " +
                    "windowMs=$MAG_INTERFERENCE_STARTUP_GRACE_MS " +
                    "until=$magneticInterferenceStartupGraceUntilElapsedMs",
            )
        } else if (started) {
            // Rate-only re-register should not reset heading smoothing state.
            pendingBootstrapRawSamplesToIgnore = 0
            pendingStartupBogusSamplesToIgnore = 0
            pendingStartupHeadingPublishesToMask = 0
            startupStabilizationUntilElapsedMs = 0L
            startupHeadingPublishMaskUntilElapsedMs = 0L
        }
        val callbackHandler = ensureSensorCallbackHandler()
        sensorRegistrationGeneration += 1L
        logSensorThreadLifecycle(event = "register", thread = sensorCallbackThread)
        sensorRegistrar.register(
            listener = this,
            callbackHandler = callbackHandler,
            pipeline = pipeline,
            rateMode = sensorRateMode,
        )
        publishHeadingSourceFromCurrentMode()
        logDiagnostics(
            "register sensors mode=$sensorRateMode " +
                "heading=${sensorRegistrar.headingSensor != null} useHeading=$usingHeadingSensor " +
                "rotVec=${sensorRegistrar.rotationVector != null} useRotVec=$usingRotationVector " +
                "mag=${sensorRegistrar.magnetometer != null} accel=${sensorRegistrar.accelerometer != null} " +
                "useMagAccel=$usingMagAccelFallback pref=$headingSourceMode " +
                "resetHeading=$resetHeadingState bootstrapIgnore=$pendingBootstrapRawSamplesToIgnore " +
                "startupBogusIgnore=$pendingStartupBogusSamplesToIgnore " +
                "startupPublishMask=$pendingStartupHeadingPublishesToMask",
        )
    }

    private fun armHeadingRelockWindow(
        nowElapsedMs: Long,
        reason: String,
    ) {
        val update =
            computeCompassHeadingRelockUpdate(
                currentHeadingRelockUntilElapsedMs = headingRelockUntilElapsedMs,
                nowElapsedMs = nowElapsedMs,
                reason = reason,
            )
        headingRelockUntilElapsedMs = update.headingRelockUntilElapsedMs
        logDiagnostics(update.logMessage)
    }

    private fun armMagneticInterferenceStartupGraceWindow(
        nowElapsedMs: Long,
        reason: String,
    ) {
        val reset =
            computeCompassMagneticGraceReset(
                currentMagneticInterferenceStartupGraceUntilElapsedMs =
                magneticInterferenceStartupGraceUntilElapsedMs,
                nowElapsedMs = nowElapsedMs,
                reason = reason,
            )
        magneticInterferenceStartupGraceUntilElapsedMs =
            reset.magneticInterferenceStartupGraceUntilElapsedMs
        if (magneticInterferenceDetected) {
            magneticInterferenceDetected = false
            _magneticInterference.value = false
            publishAccuracyFromCurrentSignals()
        }
        magneticInterferenceHoldUntilElapsedMs = 0L
        magneticFieldStrengthUt = Float.NaN
        magneticFieldStrengthEmaUt = Float.NaN
        logDiagnostics(reset.logMessage)
    }

    private fun updateInferredHeadingAccuracy(accuracy: Int) {
        if (inferredHeadingAccuracy == accuracy) return
        inferredHeadingAccuracy = accuracy
        publishAccuracyFromCurrentSignals()
    }

    private fun resolveSensorReportedAccuracy(): Int =
        resolveSensorReportedAccuracy(
            pipeline = currentHeadingPipeline(),
            headingAccuracy = headingAccuracy,
            headingUncertaintyDeg = headingUncertaintyDeg,
            magAccuracy = magAccuracy,
            rotVecAccuracy = rotVecAccuracy,
            rotVecHeadingUncertaintyDeg = rotVecHeadingUncertaintyDeg,
        )

    private fun updateRotationVectorUncertainty(values: FloatArray) {
        val update =
            computeCompassRotationVectorUpdate(
                previousUncertaintyDeg = rotVecHeadingUncertaintyDeg,
                values = values,
                sensorAccuracy = resolveSensorReportedAccuracy(),
                inferredAccuracy = inferredHeadingAccuracy,
                usingRotationVector = usingRotationVector,
                usingHeadingSensor = usingHeadingSensor,
                hasMagneticInterference = magneticInterferenceDetected,
            )
        if (!update.changed) return
        rotVecHeadingUncertaintyDeg = update.uncertaintyDeg
        if (_accuracy.value != update.combinedAccuracy) {
            _accuracy.value = update.combinedAccuracy
        }
        update.logMessage?.let(::logDiagnostics)
    }

    private fun publishAccuracyFromCurrentSignals() {
        val combined =
            computeCompassCombinedAccuracy(
                sensorAccuracy = resolveSensorReportedAccuracy(),
                inferredAccuracy = inferredHeadingAccuracy,
                usingRotationVector = usingRotationVector,
                usingHeadingSensor = usingHeadingSensor,
                hasMagneticInterference = magneticInterferenceDetected,
            )
        if (_accuracy.value != combined) {
            _accuracy.value = combined
        }
    }

    private fun publishHeadingSourceFromCurrentMode() {
        val publication =
            computeCompassHeadingSourcePublication(
                headingSourceMode = headingSourceMode,
                headingSensor = sensorRegistrar.headingSensor,
                rotationVector = sensorRegistrar.rotationVector,
                accelerometer = sensorRegistrar.accelerometer,
                magnetometer = sensorRegistrar.magnetometer,
                usingHeadingSensor = usingHeadingSensor,
                usingRotationVector = usingRotationVector,
                usingMagAccelFallback = usingMagAccelFallback,
                activeHeadingSource = activeHeadingSource,
                currentHeadingSource = _headingSource.value,
                currentStatus = _headingSourceStatus.value,
            )
        if (!publication.changed) return
        activeHeadingSource = publication.activeSource
        _headingSource.value = publication.activeSource
        _headingSourceStatus.value = publication.status
        publishNorthReferenceStatus()
        publication.logMessages.forEach(::logDiagnostics)
    }

    private fun publishNorthReferenceStatus() {
        val status =
            computeCompassNorthReferenceStatus(
                currentPipeline = currentHeadingPipeline(),
                resolvedPipeline = resolveHeadingPipeline(),
                northReferenceMode = northReferenceMode,
                declinationAvailable = declinationController.hasDeclination,
            )
        if (_northReferenceStatus.value == status) return
        _northReferenceStatus.value = status
        logDiagnostics(
            "north_reference_status requested=${status.requestedMode.name} " +
                "effective=${status.effectiveMode.name} declReady=${status.declinationAvailable} " +
                "waitingDecl=${status.waitingForDeclination} pipeline=${status.pipeline.name}",
        )
    }

    private fun resolveHeadingPipeline(): HeadingPipeline =
        resolveCompassManagerHeadingPipeline(
            headingSourceMode = headingSourceMode,
            headingSensor = sensorRegistrar.headingSensor,
            rotationVector = sensorRegistrar.rotationVector,
            accelerometer = sensorRegistrar.accelerometer,
            magnetometer = sensorRegistrar.magnetometer,
        )

    private fun applyHeadingPipeline(pipeline: HeadingPipeline) {
        val flags = applyHeadingPipelineFlags(pipeline)
        usingHeadingSensor = flags.usingHeadingSensor
        usingRotationVector = flags.usingRotationVector
        usingMagAccelFallback = flags.usingMagAccelFallback
    }

    private fun currentHeadingPipeline(): HeadingPipeline =
        resolveCurrentHeadingPipeline(
            usingHeadingSensor = usingHeadingSensor,
            usingRotationVector = usingRotationVector,
            usingMagAccelFallback = usingMagAccelFallback,
        )
}
