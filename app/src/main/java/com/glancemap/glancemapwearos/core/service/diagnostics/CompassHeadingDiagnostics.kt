package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.FusedHeadingIntegritySnapshot
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import kotlin.math.abs

/**
 * Low-overhead compass diagnostics shared by normal and deep diagnostic captures.
 *
 * Sensor samples are reduced into one in-memory window. Only a window summary and state changes
 * are written to [DebugTelemetry]; no line is written for each sensor callback.
 */
internal object CompassHeadingDiagnostics {
    private const val WINDOW_DURATION_MS = 10_000L
    private const val RENDER_SAMPLE_MIN_INTERVAL_MS = 1_000L
    private const val TAG = "CompassTelemetry"

    private val lock = Any()

    @Volatile private var lightweightCaptureActive = false
    private var window = WindowAccumulator()
    private val transitionTelemetry = CompassTransitionTelemetry()

    @Volatile private var lastRenderSampleAtElapsedMs = 0L
    @Volatile private var latestWakeProviderSample: CompassWakeProviderSample? = null
    @Volatile private var latestWakeRenderedSample: CompassWakeRenderedSample? = null
    @Volatile private var lastRenderedHeadingDeg = Float.NaN
    @Volatile private var lastRenderedHeadingAtElapsedMs = 0L
    @Volatile private var lastRenderedStepDeg = Float.NaN

    fun wakeHeadingSnapshot(): CompassWakeHeadingSnapshot =
        CompassWakeHeadingSnapshot(
            provider = latestWakeProviderSample,
            rendered = latestWakeRenderedSample,
        )

    fun reset() {
        synchronized(lock) {
            resetLocked()
        }
    }

    /**
     * Records one accepted absolute-provider callback and its integrity-engine decision.
     * This method is safe to call at the full provider rate.
     */
    @Suppress("LongParameterList")
    fun recordEngineSample(
        provider: HeadingSource,
        providerHeadingDeg: Float,
        resolvedHeadingErrorDeg: Float?,
        liveHeadingErrorDeg: Float?,
        conservativeHeadingErrorDeg: Float?,
        accuracy: Int,
        startupWarmup: Boolean,
        usable: Boolean,
        snapshot: FusedHeadingIntegritySnapshot,
        northBasis: CompassNorthBasis,
        pitchDeg: Float?,
        rollDeg: Float?,
        atElapsedMs: Long,
    ) {
        val previousTargetHeadingDeg = latestWakeProviderSample?.targetHeadingDeg
        if (provider == HeadingSource.FUSED_ORIENTATION) {
            latestWakeProviderSample =
                CompassWakeProviderSample(
                    providerHeadingDeg = providerHeadingDeg,
                    targetHeadingDeg = snapshot.renderHeadingDeg,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    projection = snapshot.relativeHorizontalProjection,
                    atElapsedMs = atElapsedMs,
                )
        }
        if (CompassDeepTraceDiagnostics.state.value.active) {
            CompassDeepTraceDiagnostics.recordProviderSample(
                CompassDeepTraceProviderSample(
                    provider = provider.telemetryToken,
                    headingDeg = providerHeadingDeg,
                    headingErrorDeg = resolvedHeadingErrorDeg,
                    liveHeadingErrorDeg = liveHeadingErrorDeg,
                    conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
                    accuracy = accuracy,
                    startupWarmup = startupWarmup,
                    usable = usable,
                    trackingState = snapshot.state,
                    trackingReason = snapshot.reason,
                    northBasis = northBasis,
                    magneticQuality = snapshot.magneticQuality,
                    magneticFieldUt = snapshot.magneticFieldUt,
                    relativeHeadingDeg = snapshot.relativeHeadingDeg,
                    relativeWitnessAvailable = snapshot.relativeWitnessAvailable,
                    relativeWitnessSuppressed = snapshot.relativeWitnessSuppressed,
                    relativeWitnessSupportsHighRate = snapshot.relativeWitnessSupportsHighRate,
                    relativeHorizontalProjection = snapshot.relativeHorizontalProjection,
                    fusedRelativeDisagreementDeg = snapshot.absoluteRelativeDisagreementDeg,
                    targetHeadingDeg = snapshot.renderHeadingDeg,
                    quarantineActive = snapshot.quarantineActive,
                    recoveryActive = snapshot.recoveryActive,
                    atElapsedMs = atElapsedMs,
                ),
            )
        }

        if (!DebugTelemetry.isEnabled()) {
            if (lightweightCaptureActive) markLightweightCaptureInactive()
            return
        }

        significantProviderStepLine(
            provider = provider,
            providerHeadingDeg = providerHeadingDeg,
            snapshot = snapshot,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            previousTargetHeadingDeg = previousTargetHeadingDeg,
            atElapsedMs = atElapsedMs,
        )?.let(::logLine)

        val lines =
            synchronized(lock) {
                ensureLightweightCaptureLocked()
                buildList {
                    flushWindowIfDueLocked(atElapsedMs)?.let(::add)
                    addAll(transitionTelemetry.record(snapshot, northBasis, atElapsedMs))
                    window.recordEngineSample(
                        provider = provider,
                        providerHeadingDeg = providerHeadingDeg,
                        liveHeadingErrorDeg = liveHeadingErrorDeg,
                        conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
                        snapshot = snapshot,
                        northBasis = northBasis,
                        atElapsedMs = atElapsedMs,
                    )
                }
            }
        lines.forEach(::logLine)
    }

    /** Records integrity changes that can happen between absolute-provider callbacks. */
    fun recordIntegritySnapshot(
        snapshot: FusedHeadingIntegritySnapshot,
        northBasis: CompassNorthBasis,
        atElapsedMs: Long,
    ) {
        if (!DebugTelemetry.isEnabled()) {
            if (lightweightCaptureActive) markLightweightCaptureInactive()
            return
        }
        if (transitionTelemetry.matches(snapshot, northBasis)) return
        val lines =
            synchronized(lock) {
                ensureLightweightCaptureLocked()
                transitionTelemetry.record(snapshot, northBasis, atElapsedMs)
            }
        lines.forEach(::logLine)
    }

    /** Stores a sparse renderer snapshot without producing a line for every display frame. */
    fun recordRenderSample(
        targetHeadingDeg: Float,
        renderedHeadingDeg: Float,
        mapRotationDeg: Float,
        atElapsedMs: Long,
    ) {
        if (DebugTelemetry.isEnabled()) {
            val previousRenderedHeadingDeg = lastRenderedHeadingDeg
            lastRenderedStepDeg =
                if (previousRenderedHeadingDeg.isFinite() && renderedHeadingDeg.isFinite()) {
                    shortestSignedAngleDeg(renderedHeadingDeg, previousRenderedHeadingDeg)
                } else {
                    Float.NaN
                }
            lastRenderedHeadingDeg = renderedHeadingDeg
            lastRenderedHeadingAtElapsedMs = atElapsedMs
            latestWakeRenderedSample =
                CompassWakeRenderedSample(
                    targetHeadingDeg = targetHeadingDeg,
                    renderedHeadingDeg = renderedHeadingDeg,
                    mapRotationDeg = mapRotationDeg,
                    atElapsedMs = atElapsedMs,
                )
        }
        CompassHeadingReferenceDiagnostics.recordRender(
            CompassHeadingReferenceRenderSample(
                targetHeadingDeg = targetHeadingDeg,
                renderedHeadingDeg = renderedHeadingDeg,
                mapsforgeMapRotationDeg = mapRotationDeg,
                atElapsedMs = atElapsedMs,
            ),
        )
        if (!DebugTelemetry.isEnabled()) {
            if (lightweightCaptureActive) markLightweightCaptureInactive()
        } else if (atElapsedMs - lastRenderSampleAtElapsedMs >= RENDER_SAMPLE_MIN_INTERVAL_MS) {
            synchronized(lock) {
                ensureLightweightCaptureLocked()
                if (atElapsedMs - lastRenderSampleAtElapsedMs >= RENDER_SAMPLE_MIN_INTERVAL_MS) {
                    lastRenderSampleAtElapsedMs = atElapsedMs
                    window.recordRenderSample(targetHeadingDeg, renderedHeadingDeg, mapRotationDeg)
                }
            }
        }
    }

    fun flush(reason: String) {
        val line =
            synchronized(lock) {
                if (!lightweightCaptureActive || !window.hasSamples) return
                val endedAtElapsedMs = window.lastSampleAtElapsedMs
                window
                    .toTelemetryLine(endedAtElapsedMs = endedAtElapsedMs, reason = reason)
                    .also { window = WindowAccumulator(startedAtElapsedMs = endedAtElapsedMs) }
            }
        logLine(line)
    }

    private fun markLightweightCaptureInactive() {
        synchronized(lock) {
            if (lightweightCaptureActive) resetLocked()
        }
    }

    private fun ensureLightweightCaptureLocked() {
        if (lightweightCaptureActive) return
        lightweightCaptureActive = true
        window = WindowAccumulator()
        transitionTelemetry.reset()
        lastRenderSampleAtElapsedMs = 0L
    }

    private fun resetLocked() {
        lightweightCaptureActive = false
        window = WindowAccumulator()
        transitionTelemetry.reset()
        lastRenderSampleAtElapsedMs = 0L
    }

    private fun flushWindowIfDueLocked(atElapsedMs: Long): String? =
        when {
            window.startedAtElapsedMs <= 0L -> {
                window = WindowAccumulator(startedAtElapsedMs = atElapsedMs)
                null
            }
            atElapsedMs - window.startedAtElapsedMs < WINDOW_DURATION_MS -> null
            else -> {
                val line =
                    window.takeIf(WindowAccumulator::hasSamples)?.toTelemetryLine(
                        endedAtElapsedMs = atElapsedMs,
                        reason = "periodic",
                    )
                window = WindowAccumulator(startedAtElapsedMs = atElapsedMs)
                line
            }
        }

    private fun logLine(line: String) {
        DebugTelemetry.log(TAG, line)
    }

    private fun significantProviderStepLine(
        provider: HeadingSource,
        providerHeadingDeg: Float,
        snapshot: FusedHeadingIntegritySnapshot,
        pitchDeg: Float?,
        rollDeg: Float?,
        previousTargetHeadingDeg: Float?,
        atElapsedMs: Long,
    ): String? {
        val providerStepDeg = snapshot.absoluteStepDeg ?: return null
        if (provider != HeadingSource.FUSED_ORIENTATION || abs(providerStepDeg) < SIGNIFICANT_PROVIDER_STEP_DEG) {
            return null
        }
        val intervalMs = snapshot.absoluteStepIntervalMs?.coerceAtLeast(1L) ?: return null
        val gyroMotion =
            CompassDeepTraceDiagnostics.gyroMotionForInterval(
                endElapsedMs = atElapsedMs,
                intervalMs = intervalMs,
            )
        val targetStepDeg =
            snapshot.renderHeadingDeg?.let { target ->
                previousTargetHeadingDeg
                    ?.let { previous -> shortestSignedAngleDeg(target, previous) }
            }
        val integrityDecision =
            when {
                snapshot.quarantineActive -> "quarantined"
                snapshot.state == CompassTrackingState.DEGRADED -> "degraded"
                snapshot.relativeWitnessSuppressed -> "accepted_without_witness"
                else -> "accepted_with_witness"
            }
        return "heading_engine provider_step " +
            "providerHeadingDeg=${providerHeadingDeg.formatOrNa(1)} " +
            "providerStepDeg=${providerStepDeg.formatOrNa(1)} " +
            "providerStepIntervalMs=$intervalMs " +
            "providerRateDegPerSec=${(abs(providerStepDeg) * 1_000f / intervalMs).formatOrNa(1)} " +
            "gyroIntegratedRotationDeg=${gyroMotion.integratedRotationDeg.formatOrNa(1)} " +
            "gyroPeakDegPerSec=${gyroMotion.peakDegPerSec.formatOrNa(1)} " +
            "pitchDeg=${pitchDeg.formatOrNa(1)} " +
            "rollDeg=${rollDeg.formatOrNa(1)} " +
            "relativeStepDeg=${snapshot.relativeStepDeg.formatOrNa(1)} " +
            "relativeWitnessSuppressed=${snapshot.relativeWitnessSuppressed} " +
            "projection=${snapshot.relativeHorizontalProjection.formatOrNa(2)} " +
            "targetStepDeg=${targetStepDeg.formatOrNa(1)} " +
            "renderStepDeg=${lastRenderedStepDeg.formatOrNa(1)} " +
            "integrityDecision=$integrityDecision " +
            "renderAgeMs=${
                (atElapsedMs - lastRenderedHeadingAtElapsedMs)
                    .takeIf { lastRenderedHeadingAtElapsedMs > 0L } ?: -1L
            }"
    }

    private class WindowAccumulator(
        val startedAtElapsedMs: Long = 0L,
    ) {
        private var sampleCount = 0
        private val liveError = RunningStats()
        private val conservativeError = RunningStats()
        private val magneticField = RunningStats()
        private val disagreement = RunningStats()
        private val relativeProjection = RunningStats()
        private val stateCounts = IntArray(CompassTrackingState.entries.size)
        private val magneticQualityCounts = IntArray(CompassMagneticQuality.entries.size)
        private var quarantineSamples = 0
        private var recoverySamples = 0
        private var relativeWitnessAvailableSamples = 0
        private var relativeWitnessSuppressedSamples = 0
        private var relativeWitnessHighRateSamples = 0
        private var provider = HeadingSource.NONE
        private var northBasis = CompassNorthBasis.UNKNOWN
        private var lastReason = CompassTrackingReason.STARTUP
        private var providerHeadingDeg: Float? = null
        private var relativeHeadingDeg: Float? = null
        private var targetHeadingDeg: Float? = null
        private var renderedHeadingDeg: Float? = null
        private var mapRotationDeg: Float? = null
        var lastSampleAtElapsedMs: Long = startedAtElapsedMs
            private set

        val hasSamples: Boolean
            get() = sampleCount > 0

        @Suppress("LongParameterList")
        fun recordEngineSample(
            provider: HeadingSource,
            providerHeadingDeg: Float,
            liveHeadingErrorDeg: Float?,
            conservativeHeadingErrorDeg: Float?,
            snapshot: FusedHeadingIntegritySnapshot,
            northBasis: CompassNorthBasis,
            atElapsedMs: Long,
        ) {
            sampleCount += 1
            liveHeadingErrorDeg?.let(liveError::add)
            conservativeHeadingErrorDeg?.let(conservativeError::add)
            snapshot.magneticFieldUt?.let(magneticField::add)
            snapshot.relativeHorizontalProjection?.let(relativeProjection::add)
            snapshot.absoluteRelativeDisagreementDeg?.let(disagreement::add)
            stateCounts[snapshot.state.ordinal] += 1
            magneticQualityCounts[snapshot.magneticQuality.ordinal] += 1
            if (snapshot.quarantineActive) quarantineSamples += 1
            if (snapshot.recoveryActive) recoverySamples += 1
            if (snapshot.relativeWitnessAvailable) relativeWitnessAvailableSamples += 1
            if (snapshot.relativeWitnessSuppressed) relativeWitnessSuppressedSamples += 1
            if (snapshot.relativeWitnessSupportsHighRate) relativeWitnessHighRateSamples += 1
            this.provider = provider
            this.northBasis = northBasis
            lastReason = snapshot.reason
            this.providerHeadingDeg = providerHeadingDeg.takeIf(Float::isFinite)
            relativeHeadingDeg = snapshot.relativeHeadingDeg?.takeIf(Float::isFinite)
            targetHeadingDeg = snapshot.renderHeadingDeg?.takeIf(Float::isFinite)
            lastSampleAtElapsedMs = atElapsedMs
        }

        fun recordRenderSample(
            targetHeadingDeg: Float,
            renderedHeadingDeg: Float,
            mapRotationDeg: Float,
        ) {
            this.targetHeadingDeg = targetHeadingDeg.takeIf(Float::isFinite)
            this.renderedHeadingDeg = renderedHeadingDeg.takeIf(Float::isFinite)
            this.mapRotationDeg = mapRotationDeg.takeIf(Float::isFinite)
        }

        fun toTelemetryLine(
            endedAtElapsedMs: Long,
            reason: String,
        ): String =
            buildString {
                append("heading_engine window")
                append(" reason=").append(reason)
                append(" windowMs=").append((endedAtElapsedMs - startedAtElapsedMs).coerceAtLeast(0L))
                append(" samples=").append(sampleCount)
                append(" provider=").append(provider.telemetryToken)
                append(" northBasis=").append(northBasis.telemetryToken)
                append(" lastReason=").append(lastReason.telemetryToken)
                append(" liveErrorAvgDeg=").append(liveError.average.formatOrNa(1))
                append(" liveErrorMaxDeg=").append(liveError.maximum.formatOrNa(1))
                append(" liveErrorSamples=").append(liveError.count)
                append(" conservativeErrorAvgDeg=").append(conservativeError.average.formatOrNa(1))
                append(" conservativeErrorMaxDeg=").append(conservativeError.maximum.formatOrNa(1))
                append(" conservativeErrorSamples=").append(conservativeError.count)
                append(" magneticAvgUt=").append(magneticField.average.formatOrNa(1))
                append(" magneticMinUt=").append(magneticField.minimum.formatOrNa(1))
                append(" magneticMaxUt=").append(magneticField.maximum.formatOrNa(1))
                append(" magneticSamples=").append(magneticField.count)
                append(" disagreementAvgDeg=").append(disagreement.average.formatOrNa(1))
                append(" disagreementMaxDeg=").append(disagreement.maximum.formatOrNa(1))
                append(" disagreementSamples=").append(disagreement.count)
                append(" relativeWitnessAvailableSamples=").append(relativeWitnessAvailableSamples)
                append(" relativeWitnessSuppressedSamples=").append(relativeWitnessSuppressedSamples)
                append(" relativeWitnessHighRateSamples=").append(relativeWitnessHighRateSamples)
                append(" relativeProjectionAvg=").append(relativeProjection.average.formatOrNa(2))
                append(" relativeProjectionMin=").append(relativeProjection.minimum.formatOrNa(2))
                append(" acquiringSamples=").append(stateCounts[CompassTrackingState.ACQUIRING.ordinal])
                append(" trackingSamples=").append(stateCounts[CompassTrackingState.TRACKING.ordinal])
                append(" degradedSamples=").append(stateCounts[CompassTrackingState.DEGRADED.ordinal])
                append(" magGoodSamples=").append(magneticQualityCounts[CompassMagneticQuality.GOOD.ordinal])
                append(" magInterferenceSamples=")
                    .append(magneticQualityCounts[CompassMagneticQuality.INTERFERENCE.ordinal])
                append(" quarantineSamples=").append(quarantineSamples)
                append(" recoverySamples=").append(recoverySamples)
                append(" providerHeading=").append(providerHeadingDeg.formatOrNa(1))
                append(" relativeHeading=").append(relativeHeadingDeg.formatOrNa(1))
                append(" targetHeading=").append(targetHeadingDeg.formatOrNa(1))
                append(" renderedHeading=").append(renderedHeadingDeg.formatOrNa(1))
                append(" mapRotation=").append(mapRotationDeg.formatOrNa(1))
            }
    }

    private class RunningStats {
        var count = 0
            private set
        private var total = 0.0
        var minimum: Float? = null
            private set
        var maximum: Float? = null
            private set

        val average: Float?
            get() = if (count > 0) (total / count).toFloat() else null

        fun add(value: Float) {
            if (!value.isFinite()) return
            count += 1
            total += value.toDouble()
            minimum = minOf(minimum ?: value, value)
            maximum = maxOf(maximum ?: value, value)
        }
    }
}

private const val SIGNIFICANT_PROVIDER_STEP_DEG = 40f

internal data class CompassWakeHeadingSnapshot(
    val provider: CompassWakeProviderSample?,
    val rendered: CompassWakeRenderedSample?,
)

internal data class CompassWakeProviderSample(
    val providerHeadingDeg: Float,
    val targetHeadingDeg: Float?,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val projection: Float?,
    val atElapsedMs: Long,
)

private fun shortestSignedAngleDeg(
    target: Float,
    current: Float,
): Float {
    var difference = (target - current) % 360f
    if (difference > 180f) difference -= 360f
    if (difference < -180f) difference += 360f
    return difference
}

internal data class CompassWakeRenderedSample(
    val targetHeadingDeg: Float,
    val renderedHeadingDeg: Float,
    val mapRotationDeg: Float,
    val atElapsedMs: Long,
)

private data class TransitionSnapshot(
    val state: CompassTrackingState,
    val reason: CompassTrackingReason,
    val northBasis: CompassNorthBasis,
    val magneticQuality: CompassMagneticQuality,
    val relativeWitnessAvailable: Boolean,
    val relativeWitnessSuppressed: Boolean,
    val quarantineActive: Boolean,
    val recoveryActive: Boolean,
)

private class CompassTransitionTelemetry {
    @Volatile private var previous: TransitionSnapshot? = null
    private var quarantineStartedAtElapsedMs = 0L
    private var recoveryStartedAtElapsedMs = 0L
    private var recoveryCorrectionTotalDeg = 0f

    fun reset() {
        previous = null
        quarantineStartedAtElapsedMs = 0L
        recoveryStartedAtElapsedMs = 0L
        recoveryCorrectionTotalDeg = 0f
    }

    fun matches(
        snapshot: FusedHeadingIntegritySnapshot,
        northBasis: CompassNorthBasis,
    ): Boolean = previous == snapshot.toTransitionSnapshot(northBasis)

    fun record(
        snapshot: FusedHeadingIntegritySnapshot,
        northBasis: CompassNorthBasis,
        atElapsedMs: Long,
    ): List<String> {
        val current = snapshot.toTransitionSnapshot(northBasis)
        val prior = previous
        if (prior == current) {
            accumulateRecoveryCorrection(snapshot)
            return emptyList()
        }

        val lines =
            listOfNotNull(
                stateTransitionLine(prior, current),
                magneticTransitionLine(prior, current, snapshot),
                quarantineTransitionLine(prior, current, snapshot, atElapsedMs),
                recoveryTransitionLine(prior, current, snapshot, atElapsedMs),
                relativeWitnessTransitionLine(prior, current, snapshot),
            )
        previous = current
        return lines
    }

    private fun stateTransitionLine(
        previous: TransitionSnapshot?,
        current: TransitionSnapshot,
    ): String? {
        val changed = previous == null || previous.state != current.state || previous.reason != current.reason
        return if (changed) {
            "heading_engine transition " +
                "fromState=${previous?.state?.telemetryToken ?: "none"} " +
                "fromReason=${previous?.reason?.telemetryToken ?: "none"} " +
                "toState=${current.state.telemetryToken} " +
                "toReason=${current.reason.telemetryToken} " +
                "northBasis=${current.northBasis.telemetryToken}"
        } else {
            null
        }
    }

    private fun magneticTransitionLine(
        previous: TransitionSnapshot?,
        current: TransitionSnapshot,
        snapshot: FusedHeadingIntegritySnapshot,
    ): String? =
        if (previous == null || previous.magneticQuality != current.magneticQuality) {
            "heading_engine magnetic transition " +
                "from=${previous?.magneticQuality?.telemetryToken ?: "none"} " +
                "to=${current.magneticQuality.telemetryToken} " +
                "strengthUt=${snapshot.magneticFieldUt.formatOrNa(1)}"
        } else {
            null
        }

    private fun quarantineTransitionLine(
        previous: TransitionSnapshot?,
        current: TransitionSnapshot,
        snapshot: FusedHeadingIntegritySnapshot,
        atElapsedMs: Long,
    ): String? =
        when {
            previous?.quarantineActive != true && current.quarantineActive -> {
                quarantineStartedAtElapsedMs = atElapsedMs
                "heading_engine quarantine stage=start reason=${current.reason.telemetryToken} " +
                    "heading=${snapshot.quarantinedAbsoluteHeadingDeg.formatOrNa(1)} " +
                    "disagreementDeg=${snapshot.absoluteRelativeDisagreementDeg.formatOrNa(1)} " +
                    "magneticQuality=${current.magneticQuality.telemetryToken}"
            }
            previous?.quarantineActive == true && !current.quarantineActive -> {
                val durationMs = elapsedSince(atElapsedMs, quarantineStartedAtElapsedMs)
                quarantineStartedAtElapsedMs = 0L
                "heading_engine quarantine stage=end durationMs=$durationMs " +
                    "state=${current.state.telemetryToken} reason=${current.reason.telemetryToken}"
            }
            else -> null
        }

    private fun recoveryTransitionLine(
        previous: TransitionSnapshot?,
        current: TransitionSnapshot,
        snapshot: FusedHeadingIntegritySnapshot,
        atElapsedMs: Long,
    ): String? =
        when {
            previous?.recoveryActive != true && current.recoveryActive -> startRecovery(snapshot, current, atElapsedMs)
            previous?.recoveryActive == true && !current.recoveryActive -> endRecovery(snapshot, current, atElapsedMs)
            current.recoveryActive -> {
                accumulateRecoveryCorrection(snapshot)
                null
            }
            else -> null
        }

    private fun startRecovery(
        snapshot: FusedHeadingIntegritySnapshot,
        current: TransitionSnapshot,
        atElapsedMs: Long,
    ): String {
        recoveryStartedAtElapsedMs = atElapsedMs
        recoveryCorrectionTotalDeg = abs(snapshot.recoveryCorrectionDeg)
        return "heading_engine recovery stage=start reason=${current.reason.telemetryToken} " +
            "heading=${snapshot.renderHeadingDeg.formatOrNa(1)}"
    }

    private fun endRecovery(
        snapshot: FusedHeadingIntegritySnapshot,
        current: TransitionSnapshot,
        atElapsedMs: Long,
    ): String {
        recoveryCorrectionTotalDeg += abs(snapshot.recoveryCorrectionDeg)
        val durationMs = elapsedSince(atElapsedMs, recoveryStartedAtElapsedMs)
        val correctionDeg = TelemetryFormatters.decimal(recoveryCorrectionTotalDeg, 1)
        recoveryStartedAtElapsedMs = 0L
        recoveryCorrectionTotalDeg = 0f
        return "heading_engine recovery stage=end durationMs=$durationMs correctionDeg=$correctionDeg " +
            "state=${current.state.telemetryToken} reason=${current.reason.telemetryToken}"
    }

    private fun accumulateRecoveryCorrection(snapshot: FusedHeadingIntegritySnapshot) {
        if (snapshot.recoveryActive) {
            recoveryCorrectionTotalDeg += abs(snapshot.recoveryCorrectionDeg)
        }
    }
}

private fun FusedHeadingIntegritySnapshot.toTransitionSnapshot(northBasis: CompassNorthBasis): TransitionSnapshot =
    TransitionSnapshot(
        state = state,
        reason = reason,
        northBasis = northBasis,
        magneticQuality = magneticQuality,
        relativeWitnessAvailable = relativeWitnessAvailable,
        relativeWitnessSuppressed = relativeWitnessSuppressed,
        quarantineActive = quarantineActive,
        recoveryActive = recoveryActive,
    )

private fun relativeWitnessTransitionLine(
    previous: TransitionSnapshot?,
    current: TransitionSnapshot,
    snapshot: FusedHeadingIntegritySnapshot,
): String? {
    val availabilityChanged =
        previous == null ||
            previous.relativeWitnessAvailable != current.relativeWitnessAvailable
    val suppressionChanged =
        previous == null ||
            previous.relativeWitnessSuppressed != current.relativeWitnessSuppressed
    val witnessChanged = availabilityChanged || suppressionChanged
    return if (witnessChanged) {
        "heading_engine witness transition " +
            "available=${current.relativeWitnessAvailable} " +
            "suppressed=${current.relativeWitnessSuppressed} " +
            "projection=${snapshot.relativeHorizontalProjection.formatOrNa(2)} " +
            "disagreementDeg=${snapshot.absoluteRelativeDisagreementDeg.formatOrNa(1)}"
    } else {
        null
    }
}

private fun elapsedSince(
    nowElapsedMs: Long,
    startedAtElapsedMs: Long,
): Long = if (startedAtElapsedMs > 0L) (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L) else 0L

private fun Float?.formatOrNa(decimals: Int): String =
    this?.takeIf(Float::isFinite)?.let {
        TelemetryFormatters.decimal(it, decimals)
    } ?: "na"
