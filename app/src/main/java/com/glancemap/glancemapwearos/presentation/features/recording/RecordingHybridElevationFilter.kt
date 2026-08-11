package com.glancemap.glancemapwearos.presentation.features.recording

import kotlin.math.pow

internal const val RECORDING_ELEVATION_SOURCE_HYBRID = "HYBRID_DEM_BAROMETER"

internal data class RecordingHybridElevationResult(
    val elevationMeters: Double?,
    val elevationSource: String,
    val pressureUsed: Boolean,
    val pressureDeltaMeters: Double,
    val absoluteAnchorCorrectionMeters: Double,
)

/**
 * Uses pressure changes for responsive relative altitude and slowly anchors the result to
 * DEM/GPS altitude. Pressure is already sampled for recording metrics, so this adds no sensor
 * request and only constant-time arithmetic per accepted location.
 */
internal class RecordingHybridElevationFilter {
    private var lastTimeMillis: Long? = null
    private var lastPressureHpa: Double? = null
    private var lastFusedElevationMeters: Double? = null

    fun reset() {
        lastTimeMillis = null
        lastPressureHpa = null
        lastFusedElevationMeters = null
    }

    @Suppress("ReturnCount", "LongParameterList", "ComplexCondition")
    fun update(
        absoluteElevationMeters: Double?,
        absoluteElevationSource: String,
        pressureHpa: Double?,
        timeMillis: Long,
        enabled: Boolean,
        startsNewSegment: Boolean,
    ): RecordingHybridElevationResult {
        val absolute = absoluteElevationMeters?.takeIf(Double::isFinite)
        val pressure = pressureHpa?.takeIf { it.isFinite() && it in MIN_VALID_PRESSURE_HPA..MAX_VALID_PRESSURE_HPA }
        if (!enabled) {
            reset()
            return baseResult(absolute, absoluteElevationSource)
        }
        if (startsNewSegment) reset()

        val previousTime = lastTimeMillis
        val previousPressure = lastPressureHpa
        val previousFused = lastFusedElevationMeters
        if (previousTime == null || previousPressure == null || previousFused == null || pressure == null) {
            if (absolute != null && pressure != null) {
                updateState(timeMillis, pressure, absolute)
            }
            return baseResult(absolute, absoluteElevationSource)
        }

        val elapsedMillis = timeMillis - previousTime
        if (elapsedMillis !in 1..MAX_CONTINUOUS_PRESSURE_GAP_MS) {
            reset()
            if (absolute != null) {
                updateState(timeMillis, pressure, absolute)
            }
            return baseResult(absolute, absoluteElevationSource)
        }

        val elapsedSeconds = elapsedMillis / 1_000.0
        val rawPressureDeltaMeters =
            STANDARD_ATMOSPHERE_HEIGHT_METERS *
                (1.0 - (pressure / previousPressure).pow(BAROMETRIC_EXPONENT))
        val maximumPressureDelta =
            (MIN_PRESSURE_DELTA_CAP_METERS + elapsedSeconds * MAX_VERTICAL_SPEED_MPS)
                .coerceAtMost(MAX_PRESSURE_DELTA_CAP_METERS)
        val pressureDeltaMeters =
            rawPressureDeltaMeters.coerceIn(-maximumPressureDelta, maximumPressureDelta)
        val pressurePrediction = previousFused + pressureDeltaMeters
        val anchorCorrection =
            absolute
                ?.let { anchor ->
                    val anchorWeight =
                        (elapsedSeconds / ABSOLUTE_ANCHOR_TIME_CONSTANT_SECONDS)
                            .coerceIn(MIN_ABSOLUTE_ANCHOR_WEIGHT, MAX_ABSOLUTE_ANCHOR_WEIGHT)
                    ((anchor - pressurePrediction) * anchorWeight)
                        .coerceIn(-MAX_ANCHOR_CORRECTION_PER_FIX_METERS, MAX_ANCHOR_CORRECTION_PER_FIX_METERS)
                } ?: 0.0
        val fused = pressurePrediction + anchorCorrection
        updateState(timeMillis, pressure, fused)
        return RecordingHybridElevationResult(
            elevationMeters = fused,
            elevationSource = RECORDING_ELEVATION_SOURCE_HYBRID,
            pressureUsed = true,
            pressureDeltaMeters = pressureDeltaMeters,
            absoluteAnchorCorrectionMeters = anchorCorrection,
        )
    }

    private fun updateState(
        timeMillis: Long,
        pressureHpa: Double,
        fusedElevationMeters: Double,
    ) {
        lastTimeMillis = timeMillis
        lastPressureHpa = pressureHpa
        lastFusedElevationMeters = fusedElevationMeters
    }

    private fun baseResult(
        elevationMeters: Double?,
        elevationSource: String,
    ): RecordingHybridElevationResult =
        RecordingHybridElevationResult(
            elevationMeters = elevationMeters,
            elevationSource = elevationSource,
            pressureUsed = false,
            pressureDeltaMeters = 0.0,
            absoluteAnchorCorrectionMeters = 0.0,
        )
}

private const val MIN_VALID_PRESSURE_HPA = 300.0
private const val MAX_VALID_PRESSURE_HPA = 1_100.0
private const val MAX_CONTINUOUS_PRESSURE_GAP_MS = 60_000L
private const val STANDARD_ATMOSPHERE_HEIGHT_METERS = 44_330.0
private const val BAROMETRIC_EXPONENT = 0.190294957
private const val MIN_PRESSURE_DELTA_CAP_METERS = 1.5
private const val MAX_VERTICAL_SPEED_MPS = 1.5
private const val MAX_PRESSURE_DELTA_CAP_METERS = 20.0
private const val ABSOLUTE_ANCHOR_TIME_CONSTANT_SECONDS = 300.0
private const val MIN_ABSOLUTE_ANCHOR_WEIGHT = 0.01
private const val MAX_ABSOLUTE_ANCHOR_WEIGHT = 0.08
private const val MAX_ANCHOR_CORRECTION_PER_FIX_METERS = 1.0
