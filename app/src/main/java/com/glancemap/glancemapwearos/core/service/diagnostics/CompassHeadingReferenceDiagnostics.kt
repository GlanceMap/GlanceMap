package com.glancemap.glancemapwearos.core.service.diagnostics

import android.hardware.GeomagneticField
import android.location.Location
import android.os.SystemClock
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.shortestAngleDiffDeg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

private const val MAX_PROVIDER_SAMPLE_AGE_MS = 2_000L
private const val MAX_RENDER_SAMPLE_AGE_MS = 2_000L

/** Debug-only captures for tester-supplied absolute-heading references. */
internal object CompassHeadingReferenceDiagnostics {
    private const val TAG = "CompassTelemetry"
    private val lock = Any()
    private val _active = MutableStateFlow(false)
    private var accumulator: CompassHeadingReferenceAccumulator? = null
    private var latestProvider: CompassHeadingReferenceProviderSample? = null
    private var latestRender: CompassHeadingReferenceRenderSample? = null
    private var latestDeclinationLocation: Location? = null
    private var latestDeclinationLocationReceivedAtElapsedMs = 0L

    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun toggle(): Boolean = if (_active.value) stop() else start()

    fun start(): Boolean =
        synchronized(lock) {
            if (_active.value || !DebugTelemetry.isEnabled()) return false
            accumulator = CompassHeadingReferenceAccumulator()
            latestProvider = null
            latestRender = null
            latestDeclinationLocation = null
            latestDeclinationLocationReceivedAtElapsedMs = 0L
            _active.value = true
            FieldMarkerDiagnostics.recordMarker(type = "heading_reference_test_start", note = "debug_settings")
            DebugTelemetry.log(TAG, "heading_reference_test stage=start providerNorthBasis=google_automatic")
            true
        }

    fun stop(): Boolean {
        val summary =
            synchronized(lock) {
                val activeAccumulator = accumulator ?: return false
                accumulator = null
                latestProvider = null
                latestRender = null
                latestDeclinationLocation = null
                latestDeclinationLocationReceivedAtElapsedMs = 0L
                _active.value = false
                activeAccumulator.summary()
            }
        FieldMarkerDiagnostics.recordMarker(type = "heading_reference_test_end", note = "debug_settings")
        DebugTelemetry.log(TAG, summary.toTelemetryLine())
        return true
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
            latestRender = sample
        }
    }

    fun recordReference(referenceHeadingDeg: Float): CompassHeadingReferenceMarkResult {
        if (!_active.value || !DebugTelemetry.isEnabled()) {
            return CompassHeadingReferenceMarkResult.TEST_INACTIVE
        }
        val capturedAtElapsedMs = SystemClock.elapsedRealtime()
        val attempt =
            synchronized(lock) {
                val result =
                    validateHeadingReferenceMark(
                        active = _active.value,
                        provider = latestProvider,
                        render = latestRender,
                        capturedAtElapsedMs = capturedAtElapsedMs,
                    )
                val marker =
                    if (result == CompassHeadingReferenceMarkResult.RECORDED) {
                        CompassHeadingReferenceMarker(
                            referenceHeadingDeg = referenceHeadingDeg,
                            provider = requireNotNull(latestProvider),
                            render = requireNotNull(latestRender),
                            capturedAtElapsedMs = capturedAtElapsedMs,
                            declination =
                                expectedDeclination(
                                    location = latestDeclinationLocation,
                                    locationReceivedAtElapsedMs = latestDeclinationLocationReceivedAtElapsedMs,
                                    nowElapsedMs = capturedAtElapsedMs,
                                ),
                        ).also { accumulator?.record(it) }
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
    provider: CompassHeadingReferenceProviderSample?,
    render: CompassHeadingReferenceRenderSample?,
    capturedAtElapsedMs: Long,
): CompassHeadingReferenceMarkResult =
    when {
        !active -> CompassHeadingReferenceMarkResult.TEST_INACTIVE
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
        capturedAtElapsedMs - render.atElapsedMs > MAX_RENDER_SAMPLE_AGE_MS ->
            CompassHeadingReferenceMarkResult.RENDER_STALE
        else -> CompassHeadingReferenceMarkResult.RECORDED
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

internal data class CompassHeadingReferenceMarker(
    val referenceHeadingDeg: Float,
    val provider: CompassHeadingReferenceProviderSample,
    val render: CompassHeadingReferenceRenderSample,
    val capturedAtElapsedMs: Long,
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

    fun toTelemetryLine(): String =
        "heading_reference_marker " +
            "referenceHeadingDeg=${referenceHeadingDeg.formatHeadingReference(1)} " +
            "googleFusedHeadingDeg=${provider.googleFusedHeadingDeg.formatHeadingReference(1)} " +
            "targetHeadingDeg=${provider.targetHeadingDeg.formatHeadingReference(1)} " +
            "renderedHeadingDeg=${render.renderedHeadingDeg.formatHeadingReference(1)} " +
            "mapsforgeMapRotationDeg=${render.mapsforgeMapRotationDeg.formatHeadingReference(1)} " +
            "signedProviderErrorDeg=${signedProviderErrorDeg.formatHeadingReference(1)} " +
            "signedRenderedErrorDeg=${signedRenderedErrorDeg.formatHeadingReference(1)} " +
            "providerNorthBasis=${provider.northBasis.telemetryToken} " +
            "magneticFieldUt=${provider.magneticFieldUt.formatHeadingReference(1)} " +
            "integrityState=${provider.integrityState.telemetryToken} " +
            "pitchDeg=${provider.pitchDeg.formatHeadingReference(1)} " +
            "rollDeg=${provider.rollDeg.formatHeadingReference(1)} " +
            "referenceBasis=unknown " +
            "expectedGeomagneticDeclinationDeg=${declination.expectedGeomagneticDeclinationDeg.formatHeadingReference(2)} " +
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

internal class CompassHeadingReferenceAccumulator {
    private val providerErrors = CompassHeadingReferenceStats()
    private val renderedErrors = CompassHeadingReferenceStats()
    private val byReference = linkedMapOf<Float, CompassHeadingReferenceBucket>()
    private var sampleCount = 0

    fun record(marker: CompassHeadingReferenceMarker) {
        sampleCount += 1
        providerErrors.add(marker.signedProviderErrorDeg)
        renderedErrors.add(marker.signedRenderedErrorDeg)
        byReference.getOrPut(marker.referenceHeadingDeg) { CompassHeadingReferenceBucket() }.add(marker)
    }

    fun summary(): CompassHeadingReferenceSummary =
        CompassHeadingReferenceSummary(
            referenceSampleCount = sampleCount,
            providerErrorAverageDeg = providerErrors.average,
            providerErrorMinDeg = providerErrors.minimum,
            providerErrorMaxDeg = providerErrors.maximum,
            renderedErrorAverageDeg = renderedErrors.average,
            renderedErrorMinDeg = renderedErrors.minimum,
            renderedErrorMaxDeg = renderedErrors.maximum,
            errorByReferenceHeading =
                byReference.entries.joinToString(separator = "|") { (reference, bucket) ->
                    "${referenceLabel(reference)}:p${bucket.provider.average.formatHeadingReference(1)}" +
                        "/r${bucket.rendered.average.formatHeadingReference(1)}" +
                        "/n${bucket.count}"
                }.ifBlank { "na" },
        )
}

internal data class CompassHeadingReferenceSummary(
    val referenceSampleCount: Int,
    val providerErrorAverageDeg: Float?,
    val providerErrorMinDeg: Float?,
    val providerErrorMaxDeg: Float?,
    val renderedErrorAverageDeg: Float?,
    val renderedErrorMinDeg: Float?,
    val renderedErrorMaxDeg: Float?,
    val errorByReferenceHeading: String,
) {
    fun toTelemetryLine(): String =
        "heading_reference_test stage=summary " +
            "referenceSampleCount=$referenceSampleCount " +
            "providerErrorAverageDeg=${providerErrorAverageDeg.formatHeadingReference(1)} " +
            "providerErrorMinDeg=${providerErrorMinDeg.formatHeadingReference(1)} " +
            "providerErrorMaxDeg=${providerErrorMaxDeg.formatHeadingReference(1)} " +
            "renderedErrorAverageDeg=${renderedErrorAverageDeg.formatHeadingReference(1)} " +
            "renderedErrorMinDeg=${renderedErrorMinDeg.formatHeadingReference(1)} " +
            "renderedErrorMaxDeg=${renderedErrorMaxDeg.formatHeadingReference(1)} " +
            "errorByReferenceHeading=$errorByReferenceHeading"
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
}

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
