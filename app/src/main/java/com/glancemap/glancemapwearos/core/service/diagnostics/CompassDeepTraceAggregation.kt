package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingProvenance
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

internal data class CompassDeepTraceProviderSample(
    val provider: String,
    val headingDeg: Float,
    val headingErrorDeg: Float?,
    val liveHeadingErrorDeg: Float? = null,
    val conservativeHeadingErrorDeg: Float? = null,
    val accuracy: Int,
    val startupWarmup: Boolean,
    val usable: Boolean,
    val trackingState: CompassTrackingState? = null,
    val trackingReason: CompassTrackingReason? = null,
    val northBasis: CompassNorthBasis? = null,
    val magneticQuality: CompassMagneticQuality? = null,
    val magneticFieldUt: Float? = null,
    val relativeHeadingDeg: Float? = null,
    val relativeWitnessAvailable: Boolean = false,
    val relativeWitnessSuppressed: Boolean = false,
    val relativeWitnessSupportsHighRate: Boolean = false,
    val relativeHorizontalProjection: Float? = null,
    val fusedRelativeDisagreementDeg: Float? = null,
    val targetHeadingDeg: Float? = null,
    val quarantineActive: Boolean = false,
    val recoveryActive: Boolean = false,
    val sourceSampleId: Long? = null,
    val sourceMeasurementAtElapsedMs: Long? = null,
    val callbackArrivalAtElapsedMs: Long? = null,
    val processingAtElapsedMs: Long? = null,
    val measurementDisposition: String? = null,
    val heldOutput: Boolean = false,
    val trusted: Boolean = false,
    val provenance: CompassHeadingProvenance? = null,
    val atElapsedMs: Long,
)

internal data class CompassDeepTraceRenderSample(
    val targetHeadingDeg: Float,
    val renderedHeadingDeg: Float,
    val mapRotationDeg: Float,
    val continuityActive: Boolean,
    val continuityOffsetDeg: Float,
    val sourceSampleId: Long? = null,
    val heldOutput: Boolean = false,
    val provenance: CompassHeadingProvenance? = null,
    val atElapsedMs: Long,
)

internal sealed interface CompassDeepTraceEvent {
    val atElapsedMs: Long

    data class ProviderMeasurement(
        override val atElapsedMs: Long,
        val provider: String,
        val headingDeg: Float,
        val sourceSampleId: Long?,
        val sourceMeasurementAtElapsedMs: Long?,
        val callbackArrivalAtElapsedMs: Long?,
        val processingAtElapsedMs: Long?,
        val measurementDisposition: String?,
        val accuracy: Int,
        val usable: Boolean,
        val provenance: CompassHeadingProvenance?,
    ) : CompassDeepTraceEvent

    data class IntegrityDecision(
        override val atElapsedMs: Long,
        val provider: String,
        val sourceSampleId: Long?,
        val headingDeg: Float,
        val liveHeadingErrorDeg: Float?,
        val conservativeHeadingErrorDeg: Float?,
        val trackingState: CompassTrackingState?,
        val trackingReason: CompassTrackingReason?,
        val relativeHeadingDeg: Float?,
        val fusedRelativeDisagreementDeg: Float?,
        val targetHeadingDeg: Float?,
        val trusted: Boolean,
        val quarantineActive: Boolean,
        val recoveryActive: Boolean,
        val heldOutput: Boolean = false,
        val provenance: CompassHeadingProvenance?,
    ) : CompassDeepTraceEvent

    data class Render(
        override val atElapsedMs: Long,
        val sourceSampleId: Long?,
        val targetHeadingDeg: Float,
        val renderedHeadingDeg: Float,
        val mapRotationDeg: Float,
        val heldOutput: Boolean = false,
        val provenance: CompassHeadingProvenance?,
    ) : CompassDeepTraceEvent

    data class Telemetry(
        override val atElapsedMs: Long,
        val line: String,
    ) : CompassDeepTraceEvent

    data class Marker(
        override val atElapsedMs: Long,
        val type: String,
        val detail: String,
    ) : CompassDeepTraceEvent

    data class UiConfidence(
        override val atElapsedMs: Long,
        val provider: String,
        val quality: String,
        val accuracyColorsEnabled: Boolean,
        val provenance: CompassHeadingProvenance?,
    ) : CompassDeepTraceEvent
}

internal data class CompassDeepTraceEventRecord(
    val eventId: Long,
    val event: CompassDeepTraceEvent,
)

/** Bounded ordered decision history; consecutive identical render records are intentionally coalesced. */
internal class CompassDeepTraceEventRing(
    private val capacity: Int,
) {
    init {
        require(capacity > 0)
    }

    private val records = ArrayDeque<CompassDeepTraceEventRecord>()
    private var nextEventId = 0L

    var droppedEvents: Int = 0
        private set

    fun record(event: CompassDeepTraceEvent) {
        if (shouldCoalesce(event, records.lastOrNull()?.event)) return
        records.addLast(CompassDeepTraceEventRecord(eventId = ++nextEventId, event = event))
        while (records.size > capacity) {
            records.removeFirst()
            droppedEvents += 1
        }
    }

    fun snapshot(): List<CompassDeepTraceEventRecord> = records.toList()

    fun clear() {
        records.clear()
        nextEventId = 0L
        droppedEvents = 0
    }

    private fun shouldCoalesce(
        event: CompassDeepTraceEvent,
        previous: CompassDeepTraceEvent?,
    ): Boolean =
        when {
            event is CompassDeepTraceEvent.Render && previous is CompassDeepTraceEvent.Render ->
                event.sourceSampleId == previous.sourceSampleId &&
                    event.targetHeadingDeg == previous.targetHeadingDeg &&
                    event.renderedHeadingDeg == previous.renderedHeadingDeg &&
                    event.mapRotationDeg == previous.mapRotationDeg &&
                    event.heldOutput == previous.heldOutput &&
                    event.provenance == previous.provenance
            event is CompassDeepTraceEvent.UiConfidence && previous is CompassDeepTraceEvent.UiConfidence ->
                event.provider == previous.provider &&
                    event.quality == previous.quality &&
                    event.accuracyColorsEnabled == previous.accuracyColorsEnabled &&
                    event.provenance == previous.provenance
            else -> false
        }
}

internal enum class CompassDeepTraceRawSensor {
    GYROSCOPE,
    ACCELEROMETER,
    MAGNETOMETER,
}

internal class CompassDeepTraceWindowAccumulator(
    val startedAtElapsedMs: Long,
) {
    private val fusedHeading = HeadingTraceStats()
    private val sensorManagerHeading = HeadingTraceStats()
    private val fusedError = ScalarTraceStats()
    private val fusedLiveError = ScalarTraceStats()
    private val fusedConservativeError = ScalarTraceStats()
    private val sensorManagerError = ScalarTraceStats()
    private val gyroMagnitude = ScalarTraceStats()
    private val gyroZ = ScalarTraceStats()
    private val accelerometerMagnitude = ScalarTraceStats()
    private val magnetometerMagnitude = ScalarTraceStats()
    private var magnetometerOutsideNormalSamples = 0
    private val relativeHeading = HeadingTraceStats()
    private val relativeHorizontalProjection = ScalarTraceStats()
    private val fusedRelativeDisagreement = ScalarTraceStats()
    private val targetHeading = HeadingTraceStats()
    private val renderedHeading = HeadingTraceStats()
    private val mapRotation = HeadingTraceStats()
    private val targetRenderDelta = ScalarTraceStats()
    private var unusableProviderSamples = 0
    private var startupWarmupProviderSamples = 0
    private var continuityActiveRenderSamples = 0
    private val continuityOffset = ScalarTraceStats()
    private val providerAccuracyCounts = IntArray(4)
    private val trackingStateCounts = IntArray(CompassTrackingState.entries.size)
    private val magneticQualityCounts = IntArray(CompassMagneticQuality.entries.size)
    private var quarantineProviderSamples = 0
    private var recoveryProviderSamples = 0
    private val relativeWitness = RelativeWitnessTraceStats()
    private var lastTrackingReason: CompassTrackingReason? = null
    private var lastNorthBasis: CompassNorthBasis? = null

    val hasSamples: Boolean
        get() =
            fusedHeading.count > 0 ||
                sensorManagerHeading.count > 0 ||
                gyroMagnitude.count > 0 ||
                accelerometerMagnitude.count > 0 ||
                magnetometerMagnitude.count > 0 ||
                renderedHeading.count > 0

    fun recordProvider(sample: CompassDeepTraceProviderSample) {
        when (sample.provider) {
            "google_fused", "fused_orientation" -> {
                fusedHeading.add(sample.headingDeg, sample.atElapsedMs)
                sample.headingErrorDeg?.takeIf(Float::isFinite)?.let(fusedError::add)
                sample.liveHeadingErrorDeg?.takeIf(Float::isFinite)?.let(fusedLiveError::add)
                sample.conservativeHeadingErrorDeg
                    ?.takeIf(Float::isFinite)
                    ?.let(fusedConservativeError::add)
            }
            "sensor_manager" -> {
                sensorManagerHeading.add(sample.headingDeg, sample.atElapsedMs)
                sample.headingErrorDeg?.takeIf(Float::isFinite)?.let(sensorManagerError::add)
            }
        }
        if (!sample.usable) unusableProviderSamples += 1
        if (sample.startupWarmup) startupWarmupProviderSamples += 1
        if (sample.accuracy in providerAccuracyCounts.indices) {
            providerAccuracyCounts[sample.accuracy] += 1
        }
        sample.relativeHeadingDeg?.let { relativeHeading.add(it, sample.atElapsedMs) }
        sample.relativeHorizontalProjection?.let(relativeHorizontalProjection::add)
        sample.fusedRelativeDisagreementDeg?.let(fusedRelativeDisagreement::add)
        sample.targetHeadingDeg?.let { targetHeading.add(it, sample.atElapsedMs) }
        sample.trackingState?.let { trackingStateCounts[it.ordinal] += 1 }
        sample.magneticQuality?.let { magneticQualityCounts[it.ordinal] += 1 }
        if (sample.quarantineActive) quarantineProviderSamples += 1
        if (sample.recoveryActive) recoveryProviderSamples += 1
        relativeWitness.record(sample)
        lastTrackingReason = sample.trackingReason ?: lastTrackingReason
        lastNorthBasis = sample.northBasis ?: lastNorthBasis
    }

    fun recordRawSensor(
        sensor: CompassDeepTraceRawSensor,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val magnitude = sqrt(x * x + y * y + z * z)
        when (sensor) {
            CompassDeepTraceRawSensor.GYROSCOPE -> {
                gyroMagnitude.add(magnitude)
                gyroZ.add(z)
            }
            CompassDeepTraceRawSensor.ACCELEROMETER -> accelerometerMagnitude.add(magnitude)
            CompassDeepTraceRawSensor.MAGNETOMETER -> {
                magnetometerMagnitude.add(magnitude)
                if (magnitude !in TRACE_MAGNETIC_NORMAL_MIN_UT..TRACE_MAGNETIC_NORMAL_MAX_UT) {
                    magnetometerOutsideNormalSamples += 1
                }
            }
        }
    }

    fun recordRender(sample: CompassDeepTraceRenderSample) {
        renderedHeading.add(sample.renderedHeadingDeg, sample.atElapsedMs)
        mapRotation.add(sample.mapRotationDeg, sample.atElapsedMs)
        targetRenderDelta.add(abs(angleDeltaDeg(sample.targetHeadingDeg, sample.renderedHeadingDeg)))
        if (sample.continuityActive) continuityActiveRenderSamples += 1
        continuityOffset.add(abs(sample.continuityOffsetDeg))
    }

    fun toTelemetryLine(
        index: Int,
        endedAtElapsedMs: Long,
    ): String {
        val durationMs = (endedAtElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
        return buildString {
            appendWindowHeader(index, durationMs)
            appendFusedProviderStats()
            appendFallbackProviderStats()
            appendRawSensorStats()
            appendIntegrityStats()
            appendRenderStats()
        }
    }

    private fun StringBuilder.appendWindowHeader(
        index: Int,
        durationMs: Long,
    ) {
        append("window index=").append(index)
        append(" startElapsedMs=").append(startedAtElapsedMs)
        append(" durationMs=").append(durationMs)
        append(" providerSamples=").append(fusedHeading.count + sensorManagerHeading.count)
    }

    private fun StringBuilder.appendFusedProviderStats() {
        append(" fusedSamples=").append(fusedHeading.count)
        append(" fusedIntervalAvgMs=").append(fusedHeading.averageIntervalMs.formatTrace(1))
        append(" fusedIntervalMaxMs=").append(fusedHeading.maximumIntervalMs.formatTrace(1))
        append(" fusedStepAvgDeg=").append(fusedHeading.averageStepDeg.formatTrace(1))
        append(" fusedStepMaxDeg=").append(fusedHeading.maximumStepDeg.formatTrace(1))
        append(" fusedRateMaxDegPerSec=").append(fusedHeading.maximumRateDegPerSec.formatTrace(1))
        append(" fusedReversals=").append(fusedHeading.reversalCount)
        append(" fusedErrorAvgDeg=").append(fusedError.average.formatTrace(1))
        append(" fusedErrorMaxDeg=").append(fusedError.maximum.formatTrace(1))
        append(" fusedLiveErrorAvgDeg=").append(fusedLiveError.average.formatTrace(1))
        append(" fusedLiveErrorMaxDeg=").append(fusedLiveError.maximum.formatTrace(1))
        append(" fusedConservativeErrorAvgDeg=").append(fusedConservativeError.average.formatTrace(1))
        append(" fusedConservativeErrorMaxDeg=").append(fusedConservativeError.maximum.formatTrace(1))
    }

    private fun StringBuilder.appendFallbackProviderStats() {
        append(" sensorManagerSamples=").append(sensorManagerHeading.count)
        append(" sensorManagerIntervalAvgMs=").append(sensorManagerHeading.averageIntervalMs.formatTrace(1))
        append(" sensorManagerStepAvgDeg=").append(sensorManagerHeading.averageStepDeg.formatTrace(1))
        append(" sensorManagerStepMaxDeg=").append(sensorManagerHeading.maximumStepDeg.formatTrace(1))
        append(" sensorManagerReversals=").append(sensorManagerHeading.reversalCount)
        append(" sensorManagerErrorAvgDeg=").append(sensorManagerError.average.formatTrace(1))
        append(" unusableProviderSamples=").append(unusableProviderSamples)
        append(" warmupProviderSamples=").append(startupWarmupProviderSamples)
        append(" accuracyUnreliable=").append(providerAccuracyCounts[0])
        append(" accuracyLow=").append(providerAccuracyCounts[1])
        append(" accuracyMedium=").append(providerAccuracyCounts[2])
        append(" accuracyHigh=").append(providerAccuracyCounts[3])
    }

    private fun StringBuilder.appendRawSensorStats() {
        append(" gyroSamples=").append(gyroMagnitude.count)
        append(" gyroMagnitudeAvgRadPerSec=").append(gyroMagnitude.average.formatTrace(3))
        append(" gyroMagnitudeMaxRadPerSec=").append(gyroMagnitude.maximum.formatTrace(3))
        append(" gyroZAvgRadPerSec=").append(gyroZ.average.formatTrace(3))
        append(" accelSamples=").append(accelerometerMagnitude.count)
        append(" accelMagnitudeAvg=").append(accelerometerMagnitude.average.formatTrace(2))
        append(" accelMagnitudeMax=").append(accelerometerMagnitude.maximum.formatTrace(2))
        append(" magSamples=").append(magnetometerMagnitude.count)
        append(" magMagnitudeAvgUt=").append(magnetometerMagnitude.average.formatTrace(1))
        append(" magMagnitudeMinUt=").append(magnetometerMagnitude.minimum.formatTrace(1))
        append(" magMagnitudeMaxUt=").append(magnetometerMagnitude.maximum.formatTrace(1))
        append(" magOutsideNormalSamples=").append(magnetometerOutsideNormalSamples)
    }

    private fun StringBuilder.appendIntegrityStats() {
        append(" relativeSamples=").append(relativeHeading.count)
        append(" relativeStepAvgDeg=").append(relativeHeading.averageStepDeg.formatTrace(1))
        append(" relativeStepMaxDeg=").append(relativeHeading.maximumStepDeg.formatTrace(1))
        append(" relativeWitnessAvailableSamples=").append(relativeWitness.availableSamples)
        append(" relativeWitnessSuppressedSamples=").append(relativeWitness.suppressedSamples)
        append(" relativeWitnessHighRateSamples=").append(relativeWitness.highRateSamples)
        append(" relativeProjectionAvg=").append(relativeHorizontalProjection.average.formatTrace(2))
        append(" relativeProjectionMin=").append(relativeHorizontalProjection.minimum.formatTrace(2))
        append(" disagreementAvgDeg=").append(fusedRelativeDisagreement.average.formatTrace(1))
        append(" disagreementMaxDeg=").append(fusedRelativeDisagreement.maximum.formatTrace(1))
        append(" acquiringSamples=").append(trackingStateCounts[CompassTrackingState.ACQUIRING.ordinal])
        append(" trackingSamples=").append(trackingStateCounts[CompassTrackingState.TRACKING.ordinal])
        append(" degradedSamples=").append(trackingStateCounts[CompassTrackingState.DEGRADED.ordinal])
        append(" magGoodSamples=").append(magneticQualityCounts[CompassMagneticQuality.GOOD.ordinal])
        append(" magInterferenceSamples=")
            .append(magneticQualityCounts[CompassMagneticQuality.INTERFERENCE.ordinal])
        append(" quarantineProviderSamples=").append(quarantineProviderSamples)
        append(" recoveryProviderSamples=").append(recoveryProviderSamples)
        append(" lastTrackingReason=").append(lastTrackingReason?.telemetryToken ?: "na")
        append(" lastNorthBasis=").append(lastNorthBasis?.telemetryToken ?: "na")
        append(" fusedLastHeadingDeg=").append(fusedHeading.latest.formatTrace(1))
        append(" relativeLastHeadingDeg=").append(relativeHeading.latest.formatTrace(1))
        append(" targetLastHeadingDeg=").append(targetHeading.latest.formatTrace(1))
    }

    private fun StringBuilder.appendRenderStats() {
        append(" renderSamples=").append(renderedHeading.count)
        append(" renderStepAvgDeg=").append(renderedHeading.averageStepDeg.formatTrace(1))
        append(" renderStepMaxDeg=").append(renderedHeading.maximumStepDeg.formatTrace(1))
        append(" renderRateMaxDegPerSec=").append(renderedHeading.maximumRateDegPerSec.formatTrace(1))
        append(" renderReversals=").append(renderedHeading.reversalCount)
        append(" mapStepMaxDeg=").append(mapRotation.maximumStepDeg.formatTrace(1))
        append(" mapReversals=").append(mapRotation.reversalCount)
        append(" renderLastHeadingDeg=").append(renderedHeading.latest.formatTrace(1))
        append(" mapLastRotationDeg=").append(mapRotation.latest.formatTrace(1))
        append(" targetRenderDeltaAvgDeg=").append(targetRenderDelta.average.formatTrace(1))
        append(" targetRenderDeltaMaxDeg=").append(targetRenderDelta.maximum.formatTrace(1))
        append(" continuityRenderSamples=").append(continuityActiveRenderSamples)
        append(" continuityOffsetAvgDeg=").append(continuityOffset.average.formatTrace(1))
        append(" continuityOffsetMaxDeg=").append(continuityOffset.maximum.formatTrace(1))
    }
}

private class RelativeWitnessTraceStats {
    var availableSamples = 0
        private set
    var suppressedSamples = 0
        private set
    var highRateSamples = 0
        private set

    fun record(sample: CompassDeepTraceProviderSample) {
        if (sample.relativeWitnessAvailable) availableSamples += 1
        if (sample.relativeWitnessSuppressed) suppressedSamples += 1
        if (sample.relativeWitnessSupportsHighRate) highRateSamples += 1
    }
}

private class ScalarTraceStats {
    var count: Int = 0
        private set
    private var total = 0.0
    private var maxValue: Float? = null
    private var minValue: Float? = null

    val average: Float?
        get() = if (count > 0) (total / count).toFloat() else null

    val maximum: Float?
        get() = maxValue

    val minimum: Float?
        get() = minValue

    fun add(value: Float) {
        if (!value.isFinite()) return
        count += 1
        total += value.toDouble()
        maxValue = maxOf(maxValue ?: value, value)
        minValue = minOf(minValue ?: value, value)
    }
}

private class HeadingTraceStats {
    var count: Int = 0
        private set
    var reversalCount: Int = 0
        private set
    private var lastHeadingDeg: Float? = null
    private var lastAtElapsedMs: Long = 0L
    private var previousDirection = 0
    private var intervalCount = 0
    private var intervalTotalMs = 0L
    private var intervalMaxMs = 0L
    private var stepCount = 0
    private var stepTotalDeg = 0.0
    private var stepMaxDeg = 0f
    private var rateMaxDegPerSec = 0f

    val latest: Float?
        get() = lastHeadingDeg

    val averageIntervalMs: Float?
        get() = if (intervalCount > 0) intervalTotalMs.toFloat() / intervalCount else null

    val maximumIntervalMs: Float?
        get() = intervalMaxMs.takeIf { intervalCount > 0 }?.toFloat()

    val averageStepDeg: Float?
        get() = if (stepCount > 0) (stepTotalDeg / stepCount).toFloat() else null

    val maximumStepDeg: Float?
        get() = stepMaxDeg.takeIf { stepCount > 0 }

    val maximumRateDegPerSec: Float?
        get() = rateMaxDegPerSec.takeIf { stepCount > 0 }

    fun add(
        headingDeg: Float,
        atElapsedMs: Long,
    ) {
        if (!headingDeg.isFinite()) return
        val previousHeading = lastHeadingDeg
        if (previousHeading != null && lastAtElapsedMs > 0L) {
            val intervalMs = (atElapsedMs - lastAtElapsedMs).coerceAtLeast(0L)
            val signedStep = angleDeltaDeg(headingDeg, previousHeading)
            val step = abs(signedStep)
            intervalCount += 1
            intervalTotalMs += intervalMs
            intervalMaxMs = maxOf(intervalMaxMs, intervalMs)
            stepCount += 1
            stepTotalDeg += step.toDouble()
            stepMaxDeg = maxOf(stepMaxDeg, step)
            if (intervalMs > 0L) {
                rateMaxDegPerSec = maxOf(rateMaxDegPerSec, step * 1_000f / intervalMs)
            }
            val direction =
                when {
                    signedStep >= TRACE_REVERSAL_MIN_STEP_DEG -> 1
                    signedStep <= -TRACE_REVERSAL_MIN_STEP_DEG -> -1
                    else -> 0
                }
            if (direction != 0) {
                if (previousDirection != 0 && direction != previousDirection) reversalCount += 1
                previousDirection = direction
            }
        }
        count += 1
        lastHeadingDeg = headingDeg
        lastAtElapsedMs = atElapsedMs
    }
}

private fun angleDeltaDeg(
    targetDeg: Float,
    currentDeg: Float,
): Float = ((targetDeg - currentDeg + 540f) % 360f) - 180f

private fun Float?.formatTrace(decimals: Int): String = this?.let { TelemetryFormatters.decimal(it, decimals) } ?: "na"

private const val TRACE_REVERSAL_MIN_STEP_DEG = 1f
private const val TRACE_MAGNETIC_NORMAL_MIN_UT = 15f
private const val TRACE_MAGNETIC_NORMAL_MAX_UT = 85f
