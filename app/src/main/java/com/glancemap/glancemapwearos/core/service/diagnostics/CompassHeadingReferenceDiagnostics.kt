package com.glancemap.glancemapwearos.core.service.diagnostics

import android.content.Context
import android.hardware.GeomagneticField
import android.location.Location
import android.os.SystemClock
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.normalize360Deg
import com.glancemap.glancemapwearos.domain.sensors.shortestAngleDiffDeg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

private const val MAX_PROVIDER_SAMPLE_AGE_MS = 2_000L
private const val MAX_RENDER_SAMPLE_AGE_MS = 2_000L
private const val MAX_RENDER_TARGET_DELTA_DEG = 5f
private const val MAX_INDEPENDENT_SAMPLE_AGE_MS = 2_000L

/** Debug-only captures for tester-supplied absolute-heading references. */
internal object CompassHeadingReferenceDiagnostics {
    private const val TAG = "CompassTelemetry"
    private val lock = Any()
    private val _active = MutableStateFlow(false)
    private val _referenceBasis = MutableStateFlow(CompassHeadingReferenceBasis.UNKNOWN)
    private var accumulator: CompassHeadingReferenceAccumulator? = null
    private var latestProvider: CompassHeadingReferenceProviderSample? = null
    private var latestRender: CompassHeadingReferenceRenderSample? = null
    private var latestIndependent = CompassHeadingReferenceIndependentSamples()
    private var latestDeclinationLocation: Location? = null
    private var latestDeclinationLocationReceivedAtElapsedMs = 0L
    private val navigateSession = CompassHeadingReferenceNavigateSession()
    private var sensorRegistration: CompassHeadingReferenceSensorRegistration? = null

    val active: StateFlow<Boolean> = _active.asStateFlow()
    val referenceBasis: StateFlow<CompassHeadingReferenceBasis> = _referenceBasis.asStateFlow()

    fun toggle(): Boolean = if (_active.value) stop() else start()

    /** The basis is fixed for each test so a summary never mixes reference frames. */
    fun selectReferenceBasis(referenceBasis: CompassHeadingReferenceBasis): Boolean =
        synchronized(lock) {
            if (_active.value) {
                false
            } else {
                _referenceBasis.value = referenceBasis
                true
            }
        }

    fun start(): Boolean =
        synchronized(lock) {
            if (_active.value || !isCompassTelemetryCaptureActive()) return false
            val selectedReferenceBasis = _referenceBasis.value
            accumulator = CompassHeadingReferenceAccumulator(referenceBasis = selectedReferenceBasis)
            latestProvider = null
            latestRender = null
            latestIndependent = CompassHeadingReferenceIndependentSamples()
            latestDeclinationLocation = null
            latestDeclinationLocationReceivedAtElapsedMs = 0L
            _active.value = true
            FieldMarkerDiagnostics.recordMarker(type = "heading_reference_test_start", note = "debug_settings")
            DebugTelemetry.log(
                TAG,
                "heading_reference_test stage=start providerNorthBasis=google_automatic " +
                    "referenceBasis=${selectedReferenceBasis.telemetryToken}",
            )
            true
        }

    fun stop(): Boolean {
        val stopped =
            synchronized(lock) {
                val activeAccumulator = accumulator ?: return false
                val registration = sensorRegistration
                sensorRegistration = null
                navigateSession.detach()
                accumulator = null
                latestProvider = null
                latestRender = null
                latestIndependent = CompassHeadingReferenceIndependentSamples()
                latestDeclinationLocation = null
                latestDeclinationLocationReceivedAtElapsedMs = 0L
                _active.value = false
                HeadingReferenceStopResult(
                    summary = activeAccumulator.summary(),
                    sensorRegistration = registration,
                )
            }
        stopped.sensorRegistration?.stop()
        FieldMarkerDiagnostics.recordMarker(type = "heading_reference_test_end", note = "debug_settings")
        DebugTelemetry.log(TAG, stopped.summary.toTelemetryLine())
        return true
    }

    /** Starts independent sensors only while this debug test is visibly active in Navigate. */
    fun attachNavigate(context: Context): Boolean {
        val generation =
            synchronized(lock) {
                if (!_active.value || !isCompassTelemetryCaptureActive()) return false
                if (navigateSession.active) return true
                latestRender = null
                latestIndependent = CompassHeadingReferenceIndependentSamples()
                navigateSession.attach()
            }
        val registration =
            startCompassHeadingReferenceSensorRegistration(context) { sample ->
                recordIndependent(sample = sample, generation = generation)
            }
        val accepted =
            synchronized(lock) {
                if (!_active.value || !navigateSession.accepts(generation)) {
                    false
                } else {
                    sensorRegistration = registration
                    if (latestIndependent == CompassHeadingReferenceIndependentSamples()) {
                        latestIndependent = registration.initialSamples
                    }
                    true
                }
            }
        if (!accepted) registration.stop()
        return accepted
    }

    /** Removes all test-only sensor listeners when Navigate leaves the foreground. */
    fun detachNavigate() {
        val registration =
            synchronized(lock) {
                navigateSession.detach()
                latestRender = null
                latestIndependent = CompassHeadingReferenceIndependentSamples()
                sensorRegistration.also { sensorRegistration = null }
            }
        registration?.stop()
    }

    fun recordProvider(
        sample: CompassHeadingReferenceProviderSample,
        declinationLocation: Location?,
    ) {
        if (!_active.value) return
        synchronized(lock) {
            latestProvider = sample
            declinationLocation?.let { location ->
                latestDeclinationLocation = Location(location)
                latestDeclinationLocationReceivedAtElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }

    fun recordRender(sample: CompassHeadingReferenceRenderSample) {
        if (!_active.value) return
        synchronized(lock) {
            if (navigateSession.active) latestRender = sample
        }
    }

    private fun recordIndependent(
        sample: CompassHeadingReferenceIndependentSamples,
        generation: Long,
    ) {
        if (!_active.value) return
        synchronized(lock) {
            if (navigateSession.accepts(generation)) {
                latestIndependent = sample
            }
        }
    }

    fun recordReference(referenceHeadingDeg: Float): CompassHeadingReferenceMarkResult {
        if (!_active.value || !isCompassTelemetryCaptureActive()) {
            return CompassHeadingReferenceMarkResult.TEST_INACTIVE
        }
        val capturedAtElapsedMs = SystemClock.elapsedRealtime()
        val attempt =
            synchronized(lock) {
                val result =
                    validateHeadingReferenceMark(
                        active = _active.value,
                        navigateActive = navigateSession.active,
                        provider = latestProvider,
                        render = latestRender,
                        capturedAtElapsedMs = capturedAtElapsedMs,
                    )
                val marker =
                    if (result == CompassHeadingReferenceMarkResult.RECORDED) {
                        val activeAccumulator = requireNotNull(accumulator)
                        CompassHeadingReferenceMarker(
                            referenceHeadingDeg = referenceHeadingDeg,
                            provider = requireNotNull(latestProvider),
                            render = requireNotNull(latestRender),
                            independent = latestIndependent,
                            capturedAtElapsedMs = capturedAtElapsedMs,
                            referenceBasis = activeAccumulator.referenceBasis,
                            declination =
                                expectedDeclination(
                                    location = latestDeclinationLocation,
                                    locationReceivedAtElapsedMs = latestDeclinationLocationReceivedAtElapsedMs,
                                    nowElapsedMs = capturedAtElapsedMs,
                                ),
                        ).also(activeAccumulator::record)
                    } else {
                        null
                    }
                HeadingReferenceMarkAttempt(result = result, marker = marker)
            }
        val marker = attempt.marker
        if (marker == null) {
            DebugTelemetry.log(
                TAG,
                "heading_reference_marker rejected reason=${attempt.result.telemetryToken}",
            )
            return attempt.result
        }
        FieldMarkerDiagnostics.recordMarker(
            type = "heading_reference_marker",
            note = "reference_${referenceLabel(referenceHeadingDeg)}",
        )
        DebugTelemetry.log(TAG, marker.toTelemetryLine())
        return CompassHeadingReferenceMarkResult.RECORDED
    }
}

private data class HeadingReferenceStopResult(
    val summary: CompassHeadingReferenceSummary,
    val sensorRegistration: CompassHeadingReferenceSensorRegistration?,
)

private fun expectedDeclination(
    location: Location?,
    locationReceivedAtElapsedMs: Long,
    nowElapsedMs: Long,
): CompassHeadingReferenceDeclination {
    val validLocation = location?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        ?: return CompassHeadingReferenceDeclination()
    val expectedDeclinationDeg =
        runCatching {
            GeomagneticField(
                validLocation.latitude.toFloat(),
                validLocation.longitude.toFloat(),
                if (validLocation.hasAltitude()) validLocation.altitude.toFloat() else 0f,
                System.currentTimeMillis(),
            ).declination
        }.getOrNull()
    val locationAtElapsedMs =
        (validLocation.elapsedRealtimeNanos / NANOS_PER_MILLISECOND)
            .takeIf { it > 0L } ?: locationReceivedAtElapsedMs
    val locationAgeMs =
        when {
            validLocation.elapsedRealtimeNanos > 0L ->
                (nowElapsedMs - locationAtElapsedMs).coerceAtLeast(0L)
            validLocation.time > 0L ->
                (System.currentTimeMillis() - validLocation.time).coerceAtLeast(0L)
            locationAtElapsedMs > 0L ->
                (nowElapsedMs - locationAtElapsedMs).coerceAtLeast(0L)
            else -> null
        }
    return CompassHeadingReferenceDeclination(
        expectedGeomagneticDeclinationDeg = expectedDeclinationDeg,
        locationAgeMs = locationAgeMs,
    )
}

internal data class CompassHeadingReferenceProviderSample(
    val googleFusedHeadingDeg: Float,
    val targetHeadingDeg: Float?,
    val usable: Boolean,
    val northBasis: CompassNorthBasis,
    val magneticFieldUt: Float?,
    val integrityState: CompassTrackingState,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val atElapsedMs: Long,
)

internal enum class CompassHeadingReferenceMarkResult(
    val telemetryToken: String,
    val userMessage: String,
) {
    RECORDED(
        telemetryToken = "recorded",
        userMessage = "Heading marked",
    ),
    TEST_INACTIVE(
        telemetryToken = "test_inactive",
        userMessage = "Start heading test first",
    ),
    NAVIGATE_UNAVAILABLE(
        telemetryToken = "navigate_unavailable",
        userMessage = "Open Navigate before marking",
    ),
    PROVIDER_UNAVAILABLE(
        telemetryToken = "provider_unavailable",
        userMessage = "Waiting for compass — keep Navigate open",
    ),
    PROVIDER_UNUSABLE(
        telemetryToken = "provider_unusable",
        userMessage = "Waiting for compass — keep Navigate open",
    ),
    PROVIDER_STALE(
        telemetryToken = "provider_stale",
        userMessage = "Waiting for compass — keep Navigate open",
    ),
    TARGET_UNAVAILABLE(
        telemetryToken = "target_unavailable",
        userMessage = "Waiting for compass — keep Navigate open",
    ),
    RENDER_UNAVAILABLE(
        telemetryToken = "render_unavailable",
        userMessage = "Waiting for compass — keep Navigate open",
    ),
    RENDER_STALE(
        telemetryToken = "render_stale",
        userMessage = "Waiting for compass — keep Navigate open",
    ),
}

internal fun validateHeadingReferenceMark(
    active: Boolean,
    navigateActive: Boolean = true,
    provider: CompassHeadingReferenceProviderSample?,
    render: CompassHeadingReferenceRenderSample?,
    capturedAtElapsedMs: Long,
): CompassHeadingReferenceMarkResult =
    when {
        !active -> CompassHeadingReferenceMarkResult.TEST_INACTIVE
        !navigateActive -> CompassHeadingReferenceMarkResult.NAVIGATE_UNAVAILABLE
        provider == null -> CompassHeadingReferenceMarkResult.PROVIDER_UNAVAILABLE
        !provider.usable || !provider.googleFusedHeadingDeg.isFinite() ->
            CompassHeadingReferenceMarkResult.PROVIDER_UNUSABLE
        capturedAtElapsedMs - provider.atElapsedMs > MAX_PROVIDER_SAMPLE_AGE_MS ->
            CompassHeadingReferenceMarkResult.PROVIDER_STALE
        provider.targetHeadingDeg?.isFinite() != true ->
            CompassHeadingReferenceMarkResult.TARGET_UNAVAILABLE
        render == null ||
            !render.targetHeadingDeg.isFinite() ||
            !render.renderedHeadingDeg.isFinite() ||
            !render.mapsforgeMapRotationDeg.isFinite() ->
            CompassHeadingReferenceMarkResult.RENDER_UNAVAILABLE
        capturedAtElapsedMs - render.atElapsedMs > MAX_RENDER_SAMPLE_AGE_MS &&
            !isCurrentStationaryRender(provider = provider, render = render) ->
            CompassHeadingReferenceMarkResult.RENDER_STALE
        else -> CompassHeadingReferenceMarkResult.RECORDED
    }

/** A stopped map animation is current when it remains aligned with the fresh provider target. */
internal fun isCurrentStationaryRender(
    provider: CompassHeadingReferenceProviderSample,
    render: CompassHeadingReferenceRenderSample,
): Boolean {
    val providerTarget = provider.targetHeadingDeg ?: return false
    return abs(shortestAngleDiffDeg(render.targetHeadingDeg, providerTarget)) <=
        MAX_RENDER_TARGET_DELTA_DEG &&
        abs(shortestAngleDiffDeg(render.renderedHeadingDeg, providerTarget)) <=
        MAX_RENDER_TARGET_DELTA_DEG
}

private data class HeadingReferenceMarkAttempt(
    val result: CompassHeadingReferenceMarkResult,
    val marker: CompassHeadingReferenceMarker?,
)

internal data class CompassHeadingReferenceRenderSample(
    val targetHeadingDeg: Float,
    val renderedHeadingDeg: Float,
    val mapsforgeMapRotationDeg: Float,
    val atElapsedMs: Long,
)

/** The north basis used by the person supplying the physical reference direction. */
internal enum class CompassHeadingReferenceBasis(
    val telemetryToken: String,
    val displayLabel: String,
) {
    MAGNETIC_NORTH(
        telemetryToken = "magnetic_north",
        displayLabel = "Magnetic north",
    ),
    TRUE_NORTH(
        telemetryToken = "true_north",
        displayLabel = "True north",
    ),
    UNKNOWN(
        telemetryToken = "unknown",
        displayLabel = "Unknown",
    ),
    ;

    fun next(): CompassHeadingReferenceBasis =
        when (this) {
            UNKNOWN -> MAGNETIC_NORTH
            MAGNETIC_NORTH -> TRUE_NORTH
            TRUE_NORTH -> UNKNOWN
        }

    fun expectedTrueHeadingDeg(
        referenceHeadingDeg: Float,
        declinationDeg: Float?,
    ): Float? =
        when (this) {
            MAGNETIC_NORTH ->
                declinationDeg?.takeIf(Float::isFinite)?.let {
                    normalize360Deg(referenceHeadingDeg + it)
                }
            TRUE_NORTH -> referenceHeadingDeg.takeIf(Float::isFinite)
            UNKNOWN -> null
        }
}

internal data class CompassHeadingReferenceMarker(
    val referenceHeadingDeg: Float,
    val provider: CompassHeadingReferenceProviderSample,
    val render: CompassHeadingReferenceRenderSample,
    val independent: CompassHeadingReferenceIndependentSamples = CompassHeadingReferenceIndependentSamples(),
    val capturedAtElapsedMs: Long,
    val referenceBasis: CompassHeadingReferenceBasis = CompassHeadingReferenceBasis.UNKNOWN,
    val declination: CompassHeadingReferenceDeclination = CompassHeadingReferenceDeclination(),
) {
    val signedProviderErrorDeg: Float? =
        provider.googleFusedHeadingDeg.takeIf(Float::isFinite)?.let { heading ->
            shortestAngleDiffDeg(target = heading, current = referenceHeadingDeg)
        }
    val signedRenderedErrorDeg: Float? =
        render.renderedHeadingDeg.takeIf(Float::isFinite)?.let { heading ->
            shortestAngleDiffDeg(target = heading, current = referenceHeadingDeg)
        }
    val expectedTrueReferenceHeadingDeg: Float? =
        referenceBasis.expectedTrueHeadingDeg(
            referenceHeadingDeg = referenceHeadingDeg,
            declinationDeg = declination.expectedGeomagneticDeclinationDeg,
        )
    val signedProviderErrorAgainstExpectedTrueDeg: Float? =
        expectedTrueReferenceHeadingDeg?.let { expectedTrueHeadingDeg ->
            shortestAngleDiffDeg(
                target = provider.googleFusedHeadingDeg,
                current = expectedTrueHeadingDeg,
            )
        }
    val signedRenderedErrorAgainstExpectedTrueDeg: Float? =
        expectedTrueReferenceHeadingDeg?.let { expectedTrueHeadingDeg ->
            shortestAngleDiffDeg(
                target = render.renderedHeadingDeg,
                current = expectedTrueHeadingDeg,
            )
        }

    fun toTelemetryLine(): String =
        "heading_reference_marker " +
            "referenceHeadingDeg=${referenceHeadingDeg.formatHeadingReference(1)} " +
            "googleFusedHeadingDeg=${provider.googleFusedHeadingDeg.formatHeadingReference(1)} " +
            "targetHeadingDeg=${provider.targetHeadingDeg.formatHeadingReference(1)} " +
            "renderedHeadingDeg=${render.renderedHeadingDeg.formatHeadingReference(1)} " +
            "mapsforgeMapRotationDeg=${render.mapsforgeMapRotationDeg.formatHeadingReference(1)} " +
            "signedProviderErrorDeg=${signedProviderErrorDeg.formatHeadingReference(1)} " +
            "signedRenderedErrorDeg=${signedRenderedErrorDeg.formatHeadingReference(1)} " +
            "rawProviderErrorAgainstReferenceDeg=${signedProviderErrorDeg.formatHeadingReference(1)} " +
            "rawRenderedErrorAgainstReferenceDeg=${signedRenderedErrorDeg.formatHeadingReference(1)} " +
            "providerNorthBasis=${provider.northBasis.telemetryToken} " +
            "magneticFieldUt=${provider.magneticFieldUt.formatHeadingReference(1)} " +
            "integrityState=${provider.integrityState.telemetryToken} " +
            "pitchDeg=${provider.pitchDeg.formatHeadingReference(1)} " +
            "rollDeg=${provider.rollDeg.formatHeadingReference(1)} " +
            independent.toTelemetryFields(
                referenceHeadingDeg = referenceHeadingDeg,
                googleFusedHeadingDeg = provider.googleFusedHeadingDeg,
                capturedAtElapsedMs = capturedAtElapsedMs,
            ) +
            "referenceBasis=${referenceBasis.telemetryToken} " +
            "expectedGeomagneticDeclinationDeg=${declination.expectedGeomagneticDeclinationDeg.formatHeadingReference(2)} " +
            "expectedTrueReferenceHeadingDeg=${expectedTrueReferenceHeadingDeg.formatHeadingReference(1)} " +
            "providerErrorAgainstExpectedTrueDeg=${signedProviderErrorAgainstExpectedTrueDeg.formatHeadingReference(1)} " +
            "renderedErrorAgainstExpectedTrueDeg=${signedRenderedErrorAgainstExpectedTrueDeg.formatHeadingReference(1)} " +
            "declinationLocationAgeMs=${declination.locationAgeMs ?: "na"} " +
            "geomagneticDeclinationDeg=${declination.expectedGeomagneticDeclinationDeg.formatHeadingReference(2)} " +
            "appDeclinationCorrectionApplied=false " +
            "providerSampleAgeMs=${(capturedAtElapsedMs - provider.atElapsedMs).coerceAtLeast(0L)} " +
            "renderSampleAgeMs=${(capturedAtElapsedMs - render.atElapsedMs).coerceAtLeast(0L)}"
}

internal data class CompassHeadingReferenceDeclination(
    val expectedGeomagneticDeclinationDeg: Float? = null,
    val locationAgeMs: Long? = null,
)

internal data class CompassHeadingReferenceIndependentSource(
    val available: Boolean = false,
    val headingDeg: Float? = null,
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val atElapsedMs: Long? = null,
    val northBasis: CompassNorthBasis = CompassNorthBasis.MAGNETIC,
) {
    fun statusAt(nowElapsedMs: Long): String =
        when {
            !available -> "unavailable"
            headingDeg?.isFinite() != true || atElapsedMs == null -> "awaiting"
            nowElapsedMs - atElapsedMs > MAX_INDEPENDENT_SAMPLE_AGE_MS -> "stale"
            else -> "available"
        }

    fun ageAt(nowElapsedMs: Long): Long? =
        atElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
}

internal data class CompassHeadingReferenceMagneticFieldSample(
    val available: Boolean = false,
    val strengthUt: Float? = null,
    val atElapsedMs: Long? = null,
) {
    fun statusAt(nowElapsedMs: Long): String =
        when {
            !available -> "unavailable"
            strengthUt?.isFinite() != true || atElapsedMs == null -> "awaiting"
            nowElapsedMs - atElapsedMs > MAX_INDEPENDENT_SAMPLE_AGE_MS -> "stale"
            else -> "available"
        }

    fun ageAt(nowElapsedMs: Long): Long? =
        atElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
}

internal data class CompassHeadingReferenceIndependentSamples(
    val rotationVector: CompassHeadingReferenceIndependentSource = CompassHeadingReferenceIndependentSource(),
    val geomagneticRotationVector: CompassHeadingReferenceIndependentSource =
        CompassHeadingReferenceIndependentSource(),
    val accelMag: CompassHeadingReferenceIndependentSource = CompassHeadingReferenceIndependentSource(),
    val magneticField: CompassHeadingReferenceMagneticFieldSample =
        CompassHeadingReferenceMagneticFieldSample(),
)

private fun CompassHeadingReferenceIndependentSamples.toTelemetryFields(
    referenceHeadingDeg: Float,
    googleFusedHeadingDeg: Float,
    capturedAtElapsedMs: Long,
): String =
    buildString {
        appendIndependentSource(
            label = "rotationVector",
            source = rotationVector,
            referenceHeadingDeg = referenceHeadingDeg,
            googleFusedHeadingDeg = googleFusedHeadingDeg,
            capturedAtElapsedMs = capturedAtElapsedMs,
        )
        appendIndependentSource(
            label = "geomagneticRotationVector",
            source = geomagneticRotationVector,
            referenceHeadingDeg = referenceHeadingDeg,
            googleFusedHeadingDeg = googleFusedHeadingDeg,
            capturedAtElapsedMs = capturedAtElapsedMs,
        )
        appendIndependentSource(
            label = "accelMag",
            source = accelMag,
            referenceHeadingDeg = referenceHeadingDeg,
            googleFusedHeadingDeg = googleFusedHeadingDeg,
            capturedAtElapsedMs = capturedAtElapsedMs,
        )
        append("directMagneticFieldUt=${magneticField.strengthUt.formatHeadingReference(1)} ")
        append("directMagneticFieldStatus=${magneticField.statusAt(capturedAtElapsedMs)} ")
        append("directMagneticFieldSampleAgeMs=${magneticField.ageAt(capturedAtElapsedMs) ?: "na"} ")
    }

private fun StringBuilder.appendIndependentSource(
    label: String,
    source: CompassHeadingReferenceIndependentSource,
    referenceHeadingDeg: Float,
    googleFusedHeadingDeg: Float,
    capturedAtElapsedMs: Long,
) {
    val headingDeg = source.headingDeg
    val referenceErrorDeg =
        headingDeg?.takeIf(Float::isFinite)?.let {
            shortestAngleDiffDeg(target = it, current = referenceHeadingDeg)
        }
    val googleFusedDeltaDeg =
        headingDeg?.takeIf(Float::isFinite)?.let {
            shortestAngleDiffDeg(target = it, current = googleFusedHeadingDeg)
        }
    append("${label}HeadingDeg=${headingDeg.formatHeadingReference(1)} ")
    append("${label}NorthBasis=${source.northBasis.telemetryToken} ")
    append("${label}ReferenceErrorDeg=${referenceErrorDeg.formatHeadingReference(1)} ")
    append("${label}GoogleFusedDeltaDeg=${googleFusedDeltaDeg.formatHeadingReference(1)} ")
    append("${label}PitchDeg=${source.pitchDeg.formatHeadingReference(1)} ")
    append("${label}RollDeg=${source.rollDeg.formatHeadingReference(1)} ")
    append("${label}Status=${source.statusAt(capturedAtElapsedMs)} ")
    append("${label}SampleAgeMs=${source.ageAt(capturedAtElapsedMs) ?: "na"} ")
}

internal class CompassHeadingReferenceNavigateSession {
    private var generation = 0L
    var active: Boolean = false
        private set

    fun attach(): Long {
        if (!active) {
            generation += 1L
            active = true
        }
        return generation
    }

    fun detach() {
        if (active) {
            active = false
            generation += 1L
        }
    }

    fun accepts(sampleGeneration: Long): Boolean = active && generation == sampleGeneration
}

internal class CompassHeadingReferenceAccumulator(
    val referenceBasis: CompassHeadingReferenceBasis = CompassHeadingReferenceBasis.UNKNOWN,
) {
    private val providerErrors = CompassHeadingReferenceStats()
    private val renderedErrors = CompassHeadingReferenceStats()
    private val providerExpectedTrueErrors = CompassHeadingReferenceStats()
    private val rotationVectorRawReferenceErrors = CompassHeadingReferenceStats()
    private val geomagneticRotationVectorRawReferenceErrors = CompassHeadingReferenceStats()
    private val accelMagRawReferenceErrors = CompassHeadingReferenceStats()
    private val fusedMinusRotationVector = CompassHeadingReferenceStats()
    private val expectedGeomagneticDeclination = CompassHeadingReferenceStats()
    private val byReference = linkedMapOf<Float, CompassHeadingReferenceBucket>()
    private var sampleCount = 0

    fun record(marker: CompassHeadingReferenceMarker) {
        sampleCount += 1
        providerErrors.add(marker.signedProviderErrorDeg)
        renderedErrors.add(marker.signedRenderedErrorDeg)
        providerExpectedTrueErrors.add(marker.signedProviderErrorAgainstExpectedTrueDeg)
        expectedGeomagneticDeclination.add(marker.declination.expectedGeomagneticDeclinationDeg)
        rotationVectorRawReferenceErrors.addRawReferenceError(marker, marker.independent.rotationVector)
        geomagneticRotationVectorRawReferenceErrors.addRawReferenceError(
            marker,
            marker.independent.geomagneticRotationVector,
        )
        accelMagRawReferenceErrors.addRawReferenceError(marker, marker.independent.accelMag)
        fusedMinusRotationVector.addFusedMinusSource(marker, marker.independent.rotationVector)
        byReference.getOrPut(marker.referenceHeadingDeg) { CompassHeadingReferenceBucket() }.add(marker)
    }

    fun summary(): CompassHeadingReferenceSummary =
        CompassHeadingReferenceSummary(
            referenceBasis = referenceBasis,
            referenceSampleCount = sampleCount,
            providerErrorAverageDeg = providerErrors.average,
            providerErrorMinDeg = providerErrors.minimum,
            providerErrorMaxDeg = providerErrors.maximum,
            renderedErrorAverageDeg = renderedErrors.average,
            renderedErrorMinDeg = renderedErrors.minimum,
            renderedErrorMaxDeg = renderedErrors.maximum,
            providerErrorAgainstExpectedTrue = providerExpectedTrueErrors.asRange(),
            rotationVectorRawReferenceError = rotationVectorRawReferenceErrors.asRange(),
            geomagneticRotationVectorRawReferenceError =
                geomagneticRotationVectorRawReferenceErrors.asRange(),
            accelMagRawReferenceError = accelMagRawReferenceErrors.asRange(),
            fusedMinusRotationVector = fusedMinusRotationVector.asRange(),
            expectedGeomagneticDeclination = expectedGeomagneticDeclination.asRange(),
            errorByReferenceHeading =
                byReference.entries.joinToString(separator = "|") { (reference, bucket) ->
                    "${referenceLabel(reference)}:p${bucket.provider.average.formatHeadingReference(1)}" +
                        "/r${bucket.rendered.average.formatHeadingReference(1)}" +
                        "/n${bucket.count}"
                }.ifBlank { "na" },
        )
}

internal data class CompassHeadingReferenceSummary(
    val referenceBasis: CompassHeadingReferenceBasis,
    val referenceSampleCount: Int,
    val providerErrorAverageDeg: Float?,
    val providerErrorMinDeg: Float?,
    val providerErrorMaxDeg: Float?,
    val renderedErrorAverageDeg: Float?,
    val renderedErrorMinDeg: Float?,
    val renderedErrorMaxDeg: Float?,
    val providerErrorAgainstExpectedTrue: CompassHeadingReferenceErrorRange,
    val rotationVectorRawReferenceError: CompassHeadingReferenceErrorRange,
    val geomagneticRotationVectorRawReferenceError: CompassHeadingReferenceErrorRange,
    val accelMagRawReferenceError: CompassHeadingReferenceErrorRange,
    val fusedMinusRotationVector: CompassHeadingReferenceErrorRange,
    val expectedGeomagneticDeclination: CompassHeadingReferenceErrorRange,
    val errorByReferenceHeading: String,
) {
    fun toTelemetryLine(): String =
        buildString {
            append("heading_reference_test stage=summary ")
            append("referenceBasis=${referenceBasis.telemetryToken} ")
            append("referenceSampleCount=$referenceSampleCount ")
            append("providerErrorAverageDeg=${providerErrorAverageDeg.formatHeadingReference(1)} ")
            append("providerErrorMinDeg=${providerErrorMinDeg.formatHeadingReference(1)} ")
            append("providerErrorMaxDeg=${providerErrorMaxDeg.formatHeadingReference(1)} ")
            append("renderedErrorAverageDeg=${renderedErrorAverageDeg.formatHeadingReference(1)} ")
            append("renderedErrorMinDeg=${renderedErrorMinDeg.formatHeadingReference(1)} ")
            append("renderedErrorMaxDeg=${renderedErrorMaxDeg.formatHeadingReference(1)} ")
            appendErrorRange("providerErrorAgainstExpectedTrue", providerErrorAgainstExpectedTrue)
            appendErrorRange("rotationVectorRawReferenceError", rotationVectorRawReferenceError)
            appendErrorRange(
                "geomagneticRotationVectorRawReferenceError",
                geomagneticRotationVectorRawReferenceError,
            )
            appendErrorRange("accelMagRawReferenceError", accelMagRawReferenceError)
            appendErrorRange("fusedMinusRotationVector", fusedMinusRotationVector)
            appendErrorRange("expectedGeomagneticDeclination", expectedGeomagneticDeclination)
            append("errorByReferenceHeading=$errorByReferenceHeading")
        }
}

internal data class CompassHeadingReferenceErrorRange(
    val averageDeg: Float?,
    val minimumDeg: Float?,
    val maximumDeg: Float?,
)

private fun StringBuilder.appendErrorRange(
    name: String,
    range: CompassHeadingReferenceErrorRange,
) {
    append("${name}AverageDeg=${range.averageDeg.formatHeadingReference(1)} ")
    append("${name}MinDeg=${range.minimumDeg.formatHeadingReference(1)} ")
    append("${name}MaxDeg=${range.maximumDeg.formatHeadingReference(1)} ")
}

private class CompassHeadingReferenceBucket {
    val provider = CompassHeadingReferenceStats()
    val rendered = CompassHeadingReferenceStats()
    var count = 0
        private set

    fun add(marker: CompassHeadingReferenceMarker) {
        count += 1
        provider.add(marker.signedProviderErrorDeg)
        rendered.add(marker.signedRenderedErrorDeg)
    }
}

private class CompassHeadingReferenceStats {
    private var total = 0.0
    private var count = 0
    var minimum: Float? = null
        private set
    var maximum: Float? = null
        private set

    val average: Float?
        get() = if (count == 0) null else (total / count).toFloat()

    fun add(value: Float?) {
        val finiteValue = value?.takeIf(Float::isFinite) ?: return
        total += finiteValue
        count += 1
        minimum = minimum?.let { minOf(it, finiteValue) } ?: finiteValue
        maximum = maximum?.let { maxOf(it, finiteValue) } ?: finiteValue
    }

    fun asRange(): CompassHeadingReferenceErrorRange =
        CompassHeadingReferenceErrorRange(
            averageDeg = average,
            minimumDeg = minimum,
            maximumDeg = maximum,
        )
}

private fun CompassHeadingReferenceStats.addRawReferenceError(
    marker: CompassHeadingReferenceMarker,
    source: CompassHeadingReferenceIndependentSource,
) {
    val headingDeg = source.headingAt(marker.capturedAtElapsedMs) ?: return
    add(shortestAngleDiffDeg(target = headingDeg, current = marker.referenceHeadingDeg))
}

private fun CompassHeadingReferenceStats.addFusedMinusSource(
    marker: CompassHeadingReferenceMarker,
    source: CompassHeadingReferenceIndependentSource,
) {
    val headingDeg = source.headingAt(marker.capturedAtElapsedMs) ?: return
    add(shortestAngleDiffDeg(target = marker.provider.googleFusedHeadingDeg, current = headingDeg))
}

private fun CompassHeadingReferenceIndependentSource.headingAt(nowElapsedMs: Long): Float? =
    headingDeg?.takeIf(Float::isFinite)?.takeIf { statusAt(nowElapsedMs) == "available" }

private fun referenceLabel(referenceHeadingDeg: Float): String =
    when (referenceHeadingDeg.toInt().mod(360)) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        270 -> "W"
        else -> referenceHeadingDeg.formatHeadingReference(0)
    }

private fun Float?.formatHeadingReference(decimals: Int): String =
    this?.takeIf(Float::isFinite)?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "na"

private const val NANOS_PER_MILLISECOND = 1_000_000L
