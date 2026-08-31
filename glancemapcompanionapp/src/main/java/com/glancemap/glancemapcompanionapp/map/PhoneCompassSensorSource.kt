@file:Suppress("TooManyFunctions") // Sensor callbacks and lifecycle methods map one-to-one to Android APIs.

package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.sqrt

internal enum class PhoneCompassPipeline {
    NONE,
    GOOGLE_FUSED,
    ROTATION_VECTOR,
    HEADING_SENSOR,
    MAG_ACCEL_FALLBACK,

    ;

    val label: String
        get() =
            when (this) {
                NONE -> "Unavailable"
                GOOGLE_FUSED -> "Google Fused"
                ROTATION_VECTOR -> "Rotation vector"
                HEADING_SENSOR -> "Heading sensor"
                MAG_ACCEL_FALLBACK -> "Magnetometer + accelerometer"
            }
}

internal data class PhoneCompassState(
    val headingDegrees: Float? = null,
    val isRenderable: Boolean = false,
    val sensorAvailable: Boolean = false,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val pipeline: PhoneCompassPipeline = PhoneCompassPipeline.NONE,
    val providerMode: PhoneCompassProviderMode = PhoneCompassProviderMode.GOOGLE_FUSED,
    val hasSample: Boolean = false,
    val calibrationRecommended: Boolean = false,
    val magneticInterference: Boolean = false,
)

internal fun resolvePhoneCompassPipeline(
    hasRotationVector: Boolean,
    hasHeadingSensor: Boolean,
    hasMagAccelerometerFallback: Boolean,
): PhoneCompassPipeline =
    when {
        hasRotationVector -> PhoneCompassPipeline.ROTATION_VECTOR
        hasHeadingSensor -> PhoneCompassPipeline.HEADING_SENSOR
        hasMagAccelerometerFallback -> PhoneCompassPipeline.MAG_ACCEL_FALLBACK
        else -> PhoneCompassPipeline.NONE
    }

internal fun resolvePhoneCompassPipeline(
    mode: PhoneCompassHeadingSourceMode,
    hasRotationVector: Boolean,
    hasHeadingSensor: Boolean,
    hasMagAccelerometerFallback: Boolean,
): PhoneCompassPipeline =
    when (mode) {
        PhoneCompassHeadingSourceMode.AUTO ->
            resolvePhoneCompassPipeline(
                hasRotationVector = hasRotationVector,
                hasHeadingSensor = hasHeadingSensor,
                hasMagAccelerometerFallback = hasMagAccelerometerFallback,
            )
        PhoneCompassHeadingSourceMode.TYPE_HEADING ->
            if (hasHeadingSensor) PhoneCompassPipeline.HEADING_SENSOR else PhoneCompassPipeline.NONE
        PhoneCompassHeadingSourceMode.ROTATION_VECTOR ->
            if (hasRotationVector) PhoneCompassPipeline.ROTATION_VECTOR else PhoneCompassPipeline.NONE
        PhoneCompassHeadingSourceMode.MAGNETOMETER ->
            if (hasMagAccelerometerFallback) PhoneCompassPipeline.MAG_ACCEL_FALLBACK else PhoneCompassPipeline.NONE
    }

@Suppress("LongParameterList") // Explicit fields keep diagnostics and test fixtures readable.
internal fun phoneCompassStateForSample(
    pipeline: PhoneCompassPipeline,
    headingDegrees: Float?,
    accuracy: Int,
    providerMode: PhoneCompassProviderMode = PhoneCompassProviderMode.SENSOR_MANAGER,
    hasSample: Boolean = headingDegrees != null,
    calibrationRecommended: Boolean = false,
    magneticInterference: Boolean = false,
): PhoneCompassState {
    val available = pipeline != PhoneCompassPipeline.NONE
    val effectiveAccuracy =
        phoneCompassEffectiveAccuracy(
            pipeline = pipeline,
            accuracy = accuracy,
            hasSample = hasSample,
        )
    val normalizedHeading =
        headingDegrees
            ?.takeIf { available && it.isFinite() }
            ?.let(::normalizePhoneHeadingDegrees)
    return PhoneCompassState(
        headingDegrees = normalizedHeading,
        isRenderable =
            available &&
                normalizedHeading != null &&
                effectiveAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE,
        sensorAvailable = available,
        accuracy = effectiveAccuracy,
        pipeline = pipeline,
        providerMode = providerMode,
        hasSample = hasSample,
        calibrationRecommended = calibrationRecommended,
        magneticInterference = magneticInterference,
    )
}

/** Map-screen compass adapter with fused orientation and the Wear-compatible sensor fallback order. */
internal class PhoneCompassSensorSource(
    context: Context,
) : SensorEventListener,
    CompassEngine {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val headingSensor = resolvePhoneHeadingSensor(sensorManager)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val hasRotationVector = rotationVector != null
    private val hasHeadingSensor = headingSensor != null
    private val hasMagAccelerometerFallback = magnetometer != null && accelerometer != null
    private val _state =
        MutableStateFlow(
            phoneCompassStateForSample(
                pipeline = PhoneCompassPipeline.NONE,
                headingDegrees = null,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            ),
        )
    val state: StateFlow<PhoneCompassState> = _state.asStateFlow()
    private val listeners = CopyOnWriteArraySet<CompassListener>()
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var started = false
    private var activePipeline = PhoneCompassPipeline.NONE
    private var providerMode = PhoneCompassProviderMode.GOOGLE_FUSED
    private var headingSourceMode = PhoneCompassHeadingSourceMode.AUTO
    private var fusedListener: DeviceOrientationListener? = null
    private var fusedRequestGeneration = 0L
    private var fusedFirstSampleReceived = false
    private var fusedReadyTimeoutRunnable: Runnable? = null
    private var hasSample = false
    private var calibrationRecommended = false
    private var magneticInterference = false
    private val fusedOrientationClient by lazy(LazyThreadSafetyMode.NONE) {
        LocationServices.getFusedOrientationProviderClient(appContext)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var smoothedHeading: Float? = null
    private var rawHeading: Float? = null
    private var northReferenceMode = PhoneMapNorthReferenceMode.TRUE
    private var declinationDegrees: Float? = null

    fun configure(settings: PhoneCompassSettings) {
        val normalized = settings.normalized()
        val providerChanged = providerMode != normalized.providerMode
        val sourceChanged = headingSourceMode != normalized.headingSourceMode
        providerMode = normalized.providerMode
        headingSourceMode = normalized.headingSourceMode
        if (started && (providerChanged || sourceChanged)) {
            restart()
        } else {
            publish(rawHeading)
        }
    }

    fun recalibrate() {
        smoothedHeading = null
        calibrationRecommended = false
        if (started && providerMode == PhoneCompassProviderMode.GOOGLE_FUSED) {
            requestFusedOrientation()
        } else {
            publish(rawHeading)
        }
    }

    fun setNorthReferenceMode(mode: PhoneMapNorthReferenceMode) {
        if (northReferenceMode == mode) return
        northReferenceMode = mode
        publish(rawHeading)
    }

    fun updateLocation(location: PhoneMapLocation?) {
        val nextDeclination =
            location
                ?.let {
                    runCatching {
                        GeomagneticField(
                            it.latitude.toFloat(),
                            it.longitude.toFloat(),
                            (it.altitudeMeters ?: 0.0).toFloat(),
                            System.currentTimeMillis(),
                        ).declination
                    }.getOrNull()
                }
        if (nextDeclination == declinationDegrees) return
        declinationDegrees = nextDeclination
        publish(rawHeading)
    }

    fun start() {
        if (started) return
        started = true
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        smoothedHeading = null
        rawHeading = null
        hasSample = false
        calibrationRecommended = false
        magneticInterference = false
        if (providerMode == PhoneCompassProviderMode.GOOGLE_FUSED) requestFusedOrientation() else startSensorPipeline()
    }

    fun stop() {
        if (!started) return
        started = false
        fusedRequestGeneration += 1L
        cancelFusedReadyTimeout()
        fusedListener?.let { listener -> fusedOrientationClient.removeOrientationUpdates(listener) }
        fusedListener = null
        fusedFirstSampleReceived = false
        sensorManager.unregisterListener(this)
        activePipeline = PhoneCompassPipeline.NONE
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        smoothedHeading = null
        rawHeading = null
        hasSample = false
        calibrationRecommended = false
        magneticInterference = false
        publish(headingDegrees = null)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Sensor-source dispatch mirrors the Android callback contract.
    override fun onSensorChanged(event: SensorEvent) {
        if (started) {
            when (activePipeline) {
                PhoneCompassPipeline.ROTATION_VECTOR -> {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        publish(
                            headingFromPhoneRotationMatrix(
                                matrix = rotationMatrix,
                                remappedMatrix = remappedRotationMatrix,
                                orientationAngles = orientationAngles,
                                displayRotation = phoneDisplayRotation(windowManager),
                            ),
                        )
                    }
                }
                PhoneCompassPipeline.HEADING_SENSOR -> {
                    if (event.sensor.type == PHONE_HEADING_SENSOR_TYPE) publish(event.values.firstOrNull())
                }
                PhoneCompassPipeline.MAG_ACCEL_FALLBACK -> {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            event.values.copyInto(gravity)
                            hasGravity = true
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            event.values.copyInto(geomagnetic)
                            hasGeomagnetic = true
                            val fieldStrength =
                                sqrt(
                                    (
                                        (event.values.getOrNull(0) ?: 0f) * (event.values.getOrNull(0) ?: 0f) +
                                            (event.values.getOrNull(1) ?: 0f) * (event.values.getOrNull(1) ?: 0f) +
                                            (event.values.getOrNull(2) ?: 0f) * (event.values.getOrNull(2) ?: 0f)
                                    ).toDouble(),
                                ).toFloat()
                            magneticInterference = fieldStrength !in 15f..85f
                        }
                    }
                    if (
                        hasGravity &&
                        hasGeomagnetic &&
                        SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
                    ) {
                        publish(
                            headingFromPhoneRotationMatrix(
                                matrix = rotationMatrix,
                                remappedMatrix = remappedRotationMatrix,
                                orientationAngles = orientationAngles,
                                displayRotation = phoneDisplayRotation(windowManager),
                            ),
                        )
                    }
                }
                PhoneCompassPipeline.NONE,
                PhoneCompassPipeline.GOOGLE_FUSED,
                -> Unit
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) {
        if (!started || !sensorBelongsToPhoneCompassPipeline(sensor, activePipeline)) return
        this.accuracy = accuracy
        publish(rawHeading)
    }

    override fun addCompassListener(compassListener: CompassListener) {
        listeners += compassListener
    }

    override fun removeCompassListener(compassListener: CompassListener) {
        listeners -= compassListener
    }

    override fun getLastHeading(): Float = _state.value.headingDegrees ?: 0f

    override fun getLastAccuracySensorStatus(): Int = _state.value.accuracy

    private fun publish(headingDegrees: Float?) {
        rawHeading = headingDegrees?.takeIf(Float::isFinite)?.let(::normalizePhoneHeadingDegrees)
        if (rawHeading != null) hasSample = true
        val effectiveAccuracy =
            phoneCompassEffectiveAccuracy(
                pipeline = activePipeline,
                accuracy = accuracy,
                hasSample = hasSample,
            )
        if (activePipeline != PhoneCompassPipeline.GOOGLE_FUSED) {
            calibrationRecommended =
                hasSample &&
                (effectiveAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW || magneticInterference)
        }
        val normalized =
            rawHeading?.let { heading ->
                if (activePipeline == PhoneCompassPipeline.GOOGLE_FUSED) {
                    applyFusedNorthReference(heading)
                } else {
                    applyNorthReference(heading)
                }
            }
        smoothedHeading = normalized?.let { heading -> smoothPhoneHeading(smoothedHeading, heading) }
        val next =
            phoneCompassStateForSample(
                pipeline = activePipeline,
                headingDegrees = smoothedHeading,
                accuracy = effectiveAccuracy,
                providerMode = providerMode,
                hasSample = hasSample,
                calibrationRecommended = calibrationRecommended,
                magneticInterference = magneticInterference,
            )
        if (next == _state.value) return
        _state.value = next
        listeners.forEach { listener ->
            listener.onCompassAccuracyChange(next.accuracy)
            next.headingDegrees?.takeIf { next.isRenderable }?.let(listener::onCompassChanged)
        }
    }

    private fun applyNorthReference(headingDegrees: Float): Float {
        val correction = declinationDegrees ?: 0f
        return when (northReferenceMode) {
            PhoneMapNorthReferenceMode.TRUE -> normalizePhoneHeadingDegrees(headingDegrees + correction)
            PhoneMapNorthReferenceMode.MAGNETIC -> normalizePhoneHeadingDegrees(headingDegrees)
        }
    }

    private fun applyFusedNorthReference(headingDegrees: Float): Float =
        when (northReferenceMode) {
            PhoneMapNorthReferenceMode.TRUE -> normalizePhoneHeadingDegrees(headingDegrees)
            PhoneMapNorthReferenceMode.MAGNETIC ->
                normalizePhoneHeadingDegrees(headingDegrees - (declinationDegrees ?: 0f))
        }

    private fun restart() {
        if (!started) return
        stop()
        start()
    }

    private fun startSensorPipeline() {
        cancelFusedReadyTimeout()
        fusedListener?.let { listener -> fusedOrientationClient.removeOrientationUpdates(listener) }
        fusedListener = null
        sensorManager.unregisterListener(this)
        activePipeline =
            resolvePhoneCompassPipeline(
                mode = headingSourceMode,
                hasRotationVector = hasRotationVector,
                hasHeadingSensor = hasHeadingSensor,
                hasMagAccelerometerFallback = hasMagAccelerometerFallback,
            )
        hasGravity = false
        hasGeomagnetic = false
        when (activePipeline) {
            PhoneCompassPipeline.ROTATION_VECTOR ->
                registerPhoneCompassSensor(sensorManager, this, rotationVector)
            PhoneCompassPipeline.HEADING_SENSOR ->
                registerPhoneCompassSensor(sensorManager, this, headingSensor)
            PhoneCompassPipeline.MAG_ACCEL_FALLBACK -> {
                registerPhoneCompassSensor(sensorManager, this, accelerometer)
                registerPhoneCompassSensor(sensorManager, this, magnetometer)
            }
            PhoneCompassPipeline.NONE,
            PhoneCompassPipeline.GOOGLE_FUSED,
            -> Unit
        }
        publish(rawHeading)
    }

    private fun requestFusedOrientation() {
        cancelFusedReadyTimeout()
        sensorManager.unregisterListener(this)
        activePipeline = PhoneCompassPipeline.GOOGLE_FUSED
        val generation = ++fusedRequestGeneration
        fusedFirstSampleReceived = false
        fusedListener?.let { listener -> fusedOrientationClient.removeOrientationUpdates(listener) }
        val listener =
            DeviceOrientationListener { orientation ->
                if (started && generation == fusedRequestGeneration) {
                    handleFusedOrientation(orientation)
                }
            }
        fusedListener = listener
        publish(rawHeading)
        scheduleFusedReadyTimeout(generation)
        val request = DeviceOrientationRequest.Builder(DeviceOrientationRequest.OUTPUT_PERIOD_DEFAULT).build()
        fusedOrientationClient
            .requestOrientationUpdates(
                request,
                ContextCompat.getMainExecutor(appContext),
                listener,
            ).addOnFailureListener { _ ->
                if (started && generation == fusedRequestGeneration) {
                    PhoneDebugCapture.log(
                        PHONE_COMPASS_DIAGNOSTICS_TAG,
                        "event=phone_compass_fused_fallback reason=request_failed",
                    )
                    startSensorPipeline()
                }
            }
    }

    private fun handleFusedOrientation(orientation: DeviceOrientation) {
        val heading = orientation.headingDegrees
        if (!heading.isFinite()) return
        fusedFirstSampleReceived = true
        cancelFusedReadyTimeout()
        val error = orientation.headingErrorDegrees
        accuracy = phoneCompassAccuracyFromError(error)
        calibrationRecommended = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        hasSample = true
        publish(heading)
    }

    private fun scheduleFusedReadyTimeout(generation: Long) {
        val timeout =
            Runnable {
                if (shouldFallbackFromFused(generation)) {
                    PhoneDebugCapture.log(
                        PHONE_COMPASS_DIAGNOSTICS_TAG,
                        "event=phone_compass_fused_fallback reason=ready_timeout " +
                            "timeoutMs=$PHONE_COMPASS_FUSED_READY_TIMEOUT_MS",
                    )
                    startSensorPipeline()
                }
            }
        fusedReadyTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, PHONE_COMPASS_FUSED_READY_TIMEOUT_MS)
    }

    private fun cancelFusedReadyTimeout() {
        fusedReadyTimeoutRunnable?.let(mainHandler::removeCallbacks)
        fusedReadyTimeoutRunnable = null
    }

    private fun shouldFallbackFromFused(generation: Long): Boolean =
        started &&
            activePipeline == PhoneCompassPipeline.GOOGLE_FUSED &&
            generation == fusedRequestGeneration &&
            !fusedFirstSampleReceived
}

internal fun phoneCompassEffectiveAccuracy(
    pipeline: PhoneCompassPipeline,
    accuracy: Int,
    hasSample: Boolean,
): Int =
    when {
        accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE -> accuracy
        hasSample && pipeline != PhoneCompassPipeline.NONE && pipeline != PhoneCompassPipeline.GOOGLE_FUSED ->
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        else -> accuracy
    }

internal fun phoneCompassAccuracyLabel(accuracy: Int): String =
    when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
        else -> "Unavailable"
    }

private fun phoneCompassAccuracyFromError(errorDegrees: Float): Int =
    when {
        !errorDegrees.isFinite() -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        errorDegrees <= 10f -> SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        errorDegrees <= 25f -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        errorDegrees <= 45f -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
        else -> SensorManager.SENSOR_STATUS_UNRELIABLE
    }

private fun registerPhoneCompassSensor(
    sensorManager: SensorManager,
    listener: SensorEventListener,
    sensor: Sensor?,
) {
    sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
}

private fun headingFromPhoneRotationMatrix(
    matrix: FloatArray,
    remappedMatrix: FloatArray,
    orientationAngles: FloatArray,
    displayRotation: Int,
): Float {
    remapForPhoneDisplayRotation(matrix, remappedMatrix, displayRotation)
    SensorManager.getOrientation(remappedMatrix, orientationAngles)
    return Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
}

private fun sensorBelongsToPhoneCompassPipeline(
    sensor: Sensor,
    pipeline: PhoneCompassPipeline,
): Boolean =
    when (pipeline) {
        PhoneCompassPipeline.ROTATION_VECTOR -> sensor.type == Sensor.TYPE_ROTATION_VECTOR
        PhoneCompassPipeline.HEADING_SENSOR -> sensor.type == PHONE_HEADING_SENSOR_TYPE
        PhoneCompassPipeline.MAG_ACCEL_FALLBACK ->
            sensor.type == Sensor.TYPE_ACCELEROMETER || sensor.type == Sensor.TYPE_MAGNETIC_FIELD
        PhoneCompassPipeline.NONE,
        PhoneCompassPipeline.GOOGLE_FUSED,
        -> false
    }

@Suppress("DEPRECATION")
private fun phoneDisplayRotation(
    windowManager: WindowManager,
): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

internal fun remapForPhoneDisplayRotation(
    input: FloatArray,
    output: FloatArray,
    displayRotation: Int,
) {
    when (displayRotation) {
        Surface.ROTATION_0 -> input.copyInto(output)
        Surface.ROTATION_90 ->
            SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, output)
        Surface.ROTATION_180 ->
            SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, output)
        Surface.ROTATION_270 ->
            SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, output)
        else -> input.copyInto(output)
    }
}

private const val PHONE_HEADING_SENSOR_TYPE = 42
private const val PHONE_HEADING_SENSOR_STRING_TYPE = "android.sensor.heading"
private const val PHONE_COMPASS_FUSED_READY_TIMEOUT_MS = 2_500L
private const val PHONE_COMPASS_DIAGNOSTICS_TAG = "PhoneCompass"

internal fun resolvePhoneHeadingSensor(sensorManager: SensorManager): Sensor? =
    sensorManager.getDefaultSensor(PHONE_HEADING_SENSOR_TYPE)
        ?: runCatching { sensorManager.getDefaultSensor(PHONE_HEADING_SENSOR_TYPE, true) }.getOrNull()
        ?: runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }
            .getOrDefault(emptyList())
            .firstOrNull { sensor ->
                sensor.type == PHONE_HEADING_SENSOR_TYPE || sensor.stringType == PHONE_HEADING_SENSOR_STRING_TYPE
            }
