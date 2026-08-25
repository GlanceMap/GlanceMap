package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlin.math.abs
import kotlin.math.pow

internal const val RECORDING_ELEVATION_SOURCE_HYBRID = "HYBRID_DEM_BAROMETER"
internal const val RECORDING_ELEVATION_FILTER_VERSION = 2

internal data class RecordingHybridElevationInput(
    val demElevationMeters: Double?,
    val demAxisLen: Int?,
    val gpsElevationMeters: Double?,
    val gpsVerticalAccuracyMeters: Float?,
    val horizontalAccuracyMeters: Float?,
    val absoluteElevationMeters: Double?,
    val absoluteElevationSource: String,
    val elapsedRealtimeMillis: Long,
    val enabled: Boolean,
    val startsNewSegment: Boolean,
    val activityProfile: String,
)

internal data class RecordingHybridElevationResult(
    val elevationMeters: Double?,
    val elevationSource: String,
    val pressureUsed: Boolean,
    val pressureDeltaMeters: Double,
    val absoluteAnchorCorrectionMeters: Double,
    val barometerReason: String,
)

internal data class RecordingSmartElevationDiagnostics(
    val pressure: RecordingPressureDiagnostics,
    val pressureDeltaCount: Long,
    val plausibilityLimitedCount: Long,
    val pressureWarmupSuppressedCount: Long,
    val pressureWarmupDownWeightedCount: Long,
    val stalePressurePointCount: Long,
    val gapRebaseCount: Long,
    val demAnchorCount: Long,
    val gpsAnchorCount: Long,
    val lastPressureDeltaMeters: Double,
    val lastAcceptedPressureDeltaMeters: Double,
    val lastAnchorResidualMeters: Double?,
)

/**
 * Smart elevation v2: pressure changes supply relative movement while DEM (or GPS only when
 * DEM is unavailable) slowly anchors long-term drift. Sensor samples enter independently of
 * GPS cadence, and all state is guarded because sensor callbacks use a background thread.
 */
internal class RecordingHybridElevationFilter {
    private val pressureFilter = RecordingPressureFilter()
    private var lastPressureForFusion: RecordingPressureSnapshot? = null
    private var lastPressureRestartCount = 0L
    private var pressureRecoveryActive = true
    private var lastFusedElevationMeters: Double? = null
    private var lastUpdateElapsedRealtimeMillis: Long? = null
    private var lastElevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO
    private var pressureDeltaCount = 0L
    private var plausibilityLimitedCount = 0L
    private var pressureWarmupSuppressedCount = 0L
    private var pressureWarmupDownWeightedCount = 0L
    private var stalePressurePointCount = 0L
    private var gapRebaseCount = 0L
    private var demAnchorCount = 0L
    private var gpsAnchorCount = 0L
    private var lastPressureDeltaMeters = 0.0
    private var lastAcceptedPressureDeltaMeters = 0.0
    private var lastAnchorResidualMeters: Double? = null

    @Synchronized
    fun observePressure(sample: RecordingPressureSample) {
        pressureFilter.observe(sample)
    }

    @Suppress("CyclomaticComplexMethod")
    @Synchronized
    fun update(input: RecordingHybridElevationInput): RecordingHybridElevationResult {
        if (!input.enabled) return directRecordingElevationResult(input, pressureReason = "disabled")
        if (input.startsNewSegment || hasLocationGap(input.elapsedRealtimeMillis)) rebasePressure()
        val anchor = recordAnchor(resolveElevationAnchor(input))
        val elapsedSeconds = elapsedSecondsSinceLastUpdate(input.elapsedRealtimeMillis)
        val pressureDelta =
            resolvePressureDelta(
                pressure = pressureFilter.snapshotAt(input.elapsedRealtimeMillis),
                activityProfile = input.activityProfile,
            )
        val prediction = lastFusedElevationMeters?.plus(pressureDelta.acceptedDeltaMeters)
        val fused =
            when {
                prediction != null ->
                    anchorFusedElevation(
                        prediction = prediction,
                        anchor = anchor,
                        elapsedSeconds = elapsedSeconds,
                        pressureTrusted = pressureDelta.hasNormalAuthority,
                    )
                anchor != null -> anchor.elevationMeters
                else -> lastFusedElevationMeters
            }
        val anchorCorrection =
            if (prediction != null && fused != null) fused - prediction else 0.0
        val source =
            when {
                pressureDelta.used && fused != null -> RECORDING_ELEVATION_SOURCE_HYBRID
                anchor != null -> anchor.source
                fused != null -> lastElevationSource
                else -> input.absoluteElevationSource
            }
        if (fused != null) {
            lastFusedElevationMeters = fused
            lastElevationSource = source
        }
        lastUpdateElapsedRealtimeMillis = input.elapsedRealtimeMillis.takeIf { it > 0L }
        lastPressureDeltaMeters = pressureDelta.rawDeltaMeters
        lastAcceptedPressureDeltaMeters = pressureDelta.acceptedDeltaMeters
        return RecordingHybridElevationResult(
            elevationMeters = fused,
            elevationSource = source,
            pressureUsed = pressureDelta.used,
            pressureDeltaMeters = pressureDelta.acceptedDeltaMeters,
            absoluteAnchorCorrectionMeters = anchorCorrection,
            barometerReason = pressureDelta.reason,
        )
    }

    @Synchronized
    fun reset() {
        pressureFilter.reset()
        lastPressureForFusion = null
        lastPressureRestartCount = 0L
        pressureRecoveryActive = true
        lastFusedElevationMeters = null
        lastUpdateElapsedRealtimeMillis = null
        lastElevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO
        pressureDeltaCount = 0L
        plausibilityLimitedCount = 0L
        pressureWarmupSuppressedCount = 0L
        pressureWarmupDownWeightedCount = 0L
        stalePressurePointCount = 0L
        gapRebaseCount = 0L
        demAnchorCount = 0L
        gpsAnchorCount = 0L
        lastPressureDeltaMeters = 0.0
        lastAcceptedPressureDeltaMeters = 0.0
        lastAnchorResidualMeters = null
    }

    @Synchronized
    fun diagnostics(): RecordingSmartElevationDiagnostics =
        RecordingSmartElevationDiagnostics(
            pressure = pressureFilter.diagnostics(),
            pressureDeltaCount = pressureDeltaCount,
            plausibilityLimitedCount = plausibilityLimitedCount,
            pressureWarmupSuppressedCount = pressureWarmupSuppressedCount,
            pressureWarmupDownWeightedCount = pressureWarmupDownWeightedCount,
            stalePressurePointCount = stalePressurePointCount,
            gapRebaseCount = gapRebaseCount,
            demAnchorCount = demAnchorCount,
            gpsAnchorCount = gpsAnchorCount,
            lastPressureDeltaMeters = lastPressureDeltaMeters,
            lastAcceptedPressureDeltaMeters = lastAcceptedPressureDeltaMeters,
            lastAnchorResidualMeters = lastAnchorResidualMeters,
        )

    private fun hasLocationGap(nowElapsedRealtimeMillis: Long): Boolean {
        val previous = lastUpdateElapsedRealtimeMillis ?: return false
        return nowElapsedRealtimeMillis - previous > MAX_CONTINUOUS_LOCATION_GAP_MS
    }

    private fun rebasePressure() {
        beginPressureRecovery()
        gapRebaseCount += 1
    }

    private fun beginPressureRecovery() {
        lastPressureForFusion = null
        pressureRecoveryActive = true
    }

    private fun elapsedSecondsSinceLastUpdate(nowElapsedRealtimeMillis: Long): Double {
        val previous = lastUpdateElapsedRealtimeMillis ?: return DEFAULT_UPDATE_INTERVAL_SECONDS
        return ((nowElapsedRealtimeMillis - previous).coerceAtLeast(0L) / 1_000.0)
            .coerceIn(MIN_UPDATE_INTERVAL_SECONDS, MAX_UPDATE_INTERVAL_SECONDS)
    }

    @Suppress("ReturnCount")
    private fun resolvePressureDelta(
        pressure: RecordingPressureSnapshot?,
        activityProfile: String,
    ): PressureDelta {
        if (pressure == null) return PressureDelta(reason = "missing")
        if (!pressure.isFresh) {
            stalePressurePointCount += 1
            beginPressureRecovery()
            return PressureDelta(reason = "stale")
        }
        if (pressure.restartCount > lastPressureRestartCount) {
            lastPressureRestartCount = pressure.restartCount
            beginPressureRecovery()
            pressureWarmupSuppressedCount += 1
            return PressureDelta(reason = "sensor_restart")
        }
        val previous = lastPressureForFusion
        lastPressureForFusion = pressure
        if (previous == null || pressure.elapsedRealtimeMillis <= previous.elapsedRealtimeMillis) {
            pressureWarmupSuppressedCount += 1
            return PressureDelta(reason = if (pressureRecoveryActive) "warmup_rebase" else "rebase")
        }
        if (
            pressure.elapsedRealtimeMillis - previous.elapsedRealtimeMillis > PRESSURE_RECOVERY_GAP_MS &&
            !hasCoherentPressureHistory(pressure)
        ) {
            beginPressureRecovery()
            lastPressureForFusion = pressure
            pressureWarmupSuppressedCount += 1
            return PressureDelta(reason = "pressure_gap")
        }
        val rawDelta = barometricDeltaMeters(previous.filteredPressureHpa, pressure.filteredPressureHpa)
        val elapsedSeconds = (pressure.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1_000.0
        val cap = maxVerticalDeltaMeters(elapsedSeconds, activityProfile)
        val plausibilityLimitedDelta = rawDelta.coerceIn(-cap, cap)
        val recoveryConfidence = pressureRecoveryConfidence(pressure)
        val acceptedDelta = plausibilityLimitedDelta * recoveryConfidence
        if (abs(rawDelta) > cap) plausibilityLimitedCount += 1
        if (recoveryConfidence < 1.0) pressureWarmupDownWeightedCount += 1
        pressureDeltaCount += 1
        return PressureDelta(
            rawDeltaMeters = rawDelta,
            acceptedDeltaMeters = acceptedDelta,
            used = true,
            hasNormalAuthority = recoveryConfidence == 1.0,
            reason =
                when {
                    recoveryConfidence < 1.0 -> "warmup_downweighted"
                    plausibilityLimitedDelta != rawDelta -> "limited"
                    else -> "accepted"
                },
        )
    }

    private fun pressureRecoveryConfidence(pressure: RecordingPressureSnapshot): Double {
        if (!pressureRecoveryActive) return 1.0
        val sampleEvidence =
            (pressure.recentSampleCount.toDouble() / RECOVERY_HEALTHY_SAMPLE_COUNT).coerceIn(0.0, 1.0)
        val spanEvidence =
            (pressure.recentSpanMillis.toDouble() / RECOVERY_HEALTHY_HISTORY_SPAN_MS).coerceIn(0.0, 1.0)
        val cadenceEvidence =
            when {
                pressure.maximumRecentGapMillis == 0L -> 1.0
                else ->
                    (RECOVERY_MAX_COHERENT_GAP_MS.toDouble() / pressure.maximumRecentGapMillis)
                        .coerceIn(0.0, 1.0)
            }
        val evidence = minOf(sampleEvidence, spanEvidence) * cadenceEvidence
        if (evidence >= 1.0) pressureRecoveryActive = false
        return MIN_PRESSURE_RECOVERY_CONFIDENCE +
            (1.0 - MIN_PRESSURE_RECOVERY_CONFIDENCE) * evidence
    }

    private fun hasCoherentPressureHistory(pressure: RecordingPressureSnapshot): Boolean =
        pressure.recentSampleCount >= RECOVERY_HEALTHY_SAMPLE_COUNT.toInt() &&
            pressure.recentSpanMillis >= RECOVERY_HEALTHY_HISTORY_SPAN_MS.toLong() &&
            pressure.maximumRecentGapMillis <= RECOVERY_MAX_COHERENT_GAP_MS

    private fun anchorFusedElevation(
        prediction: Double,
        anchor: ElevationAnchor?,
        elapsedSeconds: Double,
        pressureTrusted: Boolean,
    ): Double {
        if (anchor == null) return prediction
        val residual = anchor.elevationMeters - prediction
        lastAnchorResidualMeters = residual
        val timeConstant =
            if (pressureTrusted) ANCHOR_TIME_CONSTANT_SECONDS else ANCHOR_WITHOUT_PRESSURE_TIME_CONSTANT_SECONDS
        val residualPenalty = if (abs(residual) > LARGE_ANCHOR_RESIDUAL_METERS) 0.5 else 1.0
        val weight =
            (elapsedSeconds / timeConstant * anchor.confidence * residualPenalty)
                .coerceIn(MIN_ANCHOR_WEIGHT, MAX_ANCHOR_WEIGHT)
        val correction =
            (residual * weight).coerceIn(-MAX_ANCHOR_CORRECTION_PER_FIX_METERS, MAX_ANCHOR_CORRECTION_PER_FIX_METERS)
        return prediction + correction
    }

    private fun recordAnchor(anchor: ElevationAnchor?): ElevationAnchor? {
        when (anchor?.source) {
            SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM -> demAnchorCount += 1
            SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS -> gpsAnchorCount += 1
        }
        return anchor
    }
}

private data class ElevationAnchor(
    val elevationMeters: Double,
    val source: String,
    val confidence: Double,
)

private data class PressureDelta(
    val rawDeltaMeters: Double = 0.0,
    val acceptedDeltaMeters: Double = 0.0,
    val used: Boolean = false,
    val hasNormalAuthority: Boolean = false,
    val reason: String,
)

private fun directRecordingElevationResult(
    input: RecordingHybridElevationInput,
    pressureReason: String,
): RecordingHybridElevationResult {
    val absoluteElevation = input.absoluteElevationMeters?.takeIf(Double::isFinite)
    return RecordingHybridElevationResult(
        elevationMeters = absoluteElevation,
        elevationSource = input.absoluteElevationSource,
        pressureUsed = false,
        pressureDeltaMeters = 0.0,
        absoluteAnchorCorrectionMeters = 0.0,
        barometerReason = pressureReason,
    )
}

private fun resolveElevationAnchor(input: RecordingHybridElevationInput): ElevationAnchor? =
    input.demElevationMeters?.takeIf(Double::isFinite)?.let { dem ->
        ElevationAnchor(
            elevationMeters = dem,
            source = SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM,
            confidence = demConfidence(input.demAxisLen, input.horizontalAccuracyMeters),
        )
    } ?: input.gpsElevationMeters?.takeIf(Double::isFinite)?.let { gps ->
        ElevationAnchor(
            elevationMeters = gps,
            source = SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
            confidence = gpsConfidence(input.gpsVerticalAccuracyMeters),
        )
    }

private fun demConfidence(
    axisLen: Int?,
    horizontalAccuracyMeters: Float?,
): Double {
    val cellMeters = 111_000.0 / (axisLen?.takeIf { it > 0 } ?: DEFAULT_DEM_AXIS_LENGTH)
    val horizontalMeters =
        horizontalAccuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: 50.0
    return (1.0 / (1.0 + (cellMeters / 90.0).pow(2) + (horizontalMeters / 70.0).pow(2)))
        .coerceIn(MIN_DEM_CONFIDENCE, MAX_DEM_CONFIDENCE)
}

private fun gpsConfidence(verticalAccuracyMeters: Float?): Double {
    val accuracy = verticalAccuracyMeters?.takeIf { it.isFinite() && it > 0f }?.toDouble()
    return when {
        accuracy == null -> DEFAULT_GPS_CONFIDENCE
        accuracy <= 8.0 -> 0.32
        accuracy <= 20.0 -> 0.20
        accuracy <= 50.0 -> 0.10
        else -> 0.04
    }
}

private fun barometricDeltaMeters(
    previousPressureHpa: Double,
    currentPressureHpa: Double,
): Double =
    STANDARD_ATMOSPHERE_HEIGHT_METERS *
        (1.0 - (currentPressureHpa / previousPressureHpa).pow(BAROMETRIC_EXPONENT))

private fun maxVerticalDeltaMeters(
    elapsedSeconds: Double,
    activityProfile: String,
): Double {
    val speed =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            MAX_BIKE_VERTICAL_SPEED_MPS
        } else {
            MAX_HIKE_VERTICAL_SPEED_MPS
        }
    return (VERTICAL_DELTA_GRACE_METERS + elapsedSeconds * speed).coerceAtMost(MAX_VERTICAL_DELTA_METERS)
}

private const val STANDARD_ATMOSPHERE_HEIGHT_METERS = 44_330.0
private const val BAROMETRIC_EXPONENT = 0.190294957
private const val MAX_CONTINUOUS_LOCATION_GAP_MS = 60_000L
private const val PRESSURE_RECOVERY_GAP_MS = 7_000L
private const val RECOVERY_HEALTHY_SAMPLE_COUNT = 5.0
private const val RECOVERY_HEALTHY_HISTORY_SPAN_MS = 4_000.0
private const val RECOVERY_MAX_COHERENT_GAP_MS = 4_500L
private const val MIN_PRESSURE_RECOVERY_CONFIDENCE = 0.10
private const val DEFAULT_UPDATE_INTERVAL_SECONDS = 1.0
private const val MIN_UPDATE_INTERVAL_SECONDS = 0.1
private const val MAX_UPDATE_INTERVAL_SECONDS = 30.0
private const val MAX_HIKE_VERTICAL_SPEED_MPS = 0.85
private const val MAX_BIKE_VERTICAL_SPEED_MPS = 2.4
private const val VERTICAL_DELTA_GRACE_METERS = 1.2
private const val MAX_VERTICAL_DELTA_METERS = 20.0
private const val ANCHOR_TIME_CONSTANT_SECONDS = 120.0
private const val ANCHOR_WITHOUT_PRESSURE_TIME_CONSTANT_SECONDS = 35.0
private const val MIN_ANCHOR_WEIGHT = 0.002
private const val MAX_ANCHOR_WEIGHT = 0.12
private const val MAX_ANCHOR_CORRECTION_PER_FIX_METERS = 1.0
private const val LARGE_ANCHOR_RESIDUAL_METERS = 35.0
private const val DEFAULT_DEM_AXIS_LENGTH = 1_201
private const val MIN_DEM_CONFIDENCE = 0.08
private const val MAX_DEM_CONFIDENCE = 0.75
private const val DEFAULT_GPS_CONFIDENCE = 0.08

internal fun String.usesHybridRecordingElevation(): Boolean = this == SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO
