package com.glancemap.glancemapwearos.core.service.diagnostics

import kotlin.math.abs
import kotlin.math.sqrt

/** Latest independent magnetic vectors, sampled into the existing bounded, opt-in trace. */
internal class CompassMagneticCalibrationTrace {
    private var calibrated: MagneticCalibrationSample? = null
    private var uncalibrated: MagneticCalibrationSample? = null
    private var lastPublishedAtMs: Long? = null

    fun clear() {
        calibrated = null
        uncalibrated = null
        lastPublishedAtMs = null
    }

    fun record(
        sensor: CompassDeepTraceRawSensor,
        values: FloatArray,
        accuracy: Int,
        sourceAtMs: Long,
        arrivalAtMs: Long,
    ): String? {
        val previous = if (sensor == CompassDeepTraceRawSensor.MAGNETOMETER) calibrated else uncalibrated
        val sample =
            decodeMagneticCalibrationSample(sensor, values, accuracy, sourceAtMs, arrivalAtMs)
                ?.takeIf { it.sourceAtMs > (previous?.sourceAtMs ?: 0L) } ?: return null
        if (sensor == CompassDeepTraceRawSensor.MAGNETOMETER) calibrated = sample else uncalibrated = sample
        return if (lastPublishedAtMs?.let { arrivalAtMs - it < MAGNETIC_CALIBRATION_TRACE_INTERVAL_MS } == true) {
            null
        } else {
            lastPublishedAtMs = arrivalAtMs
            buildString {
                append("magnetic_calibration atMs=").append(arrivalAtMs)
                append(calibrated.toTraceFields("calibrated", arrivalAtMs))
                append(uncalibrated.toTraceFields("uncalibrated", arrivalAtMs))
                append(" pairSkewMs=")
                append(calibrated?.let { cal -> uncalibrated?.let { abs(cal.sourceAtMs - it.sourceAtMs) } } ?: "na")
            }
        }
    }
}

private data class MagneticCalibrationSample(
    val values: FloatArray,
    val accuracy: Int,
    val sourceAtMs: Long,
    val arrivalAtMs: Long,
)

private fun decodeMagneticCalibrationSample(
    sensor: CompassDeepTraceRawSensor,
    values: FloatArray,
    accuracy: Int,
    sourceAtMs: Long,
    arrivalAtMs: Long,
): MagneticCalibrationSample? {
    val count =
        when (sensor) {
            CompassDeepTraceRawSensor.MAGNETOMETER -> 3
            CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER -> 6
            else -> 0
        }
    if (count == 0) return null
    return if (sourceAtMs <= 0L || values.size < count || (0 until count).any { !values[it].isFinite() }) {
        null
    } else {
        MagneticCalibrationSample(values.copyOf(count), accuracy, sourceAtMs, arrivalAtMs)
    }
}

private fun MagneticCalibrationSample?.toTraceFields(
    prefix: String,
    nowElapsedMs: Long,
): String =
    if (this == null) {
        " ${prefix}VectorUt=na"
    } else {
        buildString {
            append(" ${prefix}VectorUt=").append(values.take(3).toTraceVector())
            append(" ${prefix}MagnitudeUt=").append(values.take(3).magnitude().traceDecimal())
            append(" ${prefix}Accuracy=").append(accuracy)
            append(" ${prefix}SourceAtMs=").append(sourceAtMs)
            append(" ${prefix}ArrivalAtMs=").append(arrivalAtMs)
            append(" ${prefix}AgeMs=").append(nowElapsedMs - sourceAtMs)
            if (values.size == 6) {
                val bias = values.drop(3)
                val compensated = (0..2).map { values[it] - values[it + 3] }
                append(" biasVectorUt=").append(bias.toTraceVector())
                append(" biasMagnitudeUt=").append(bias.magnitude().traceDecimal())
                append(" compensatedVectorUt=").append(compensated.toTraceVector())
                append(" compensatedMagnitudeUt=").append(compensated.magnitude().traceDecimal())
            }
        }
    }

private fun List<Float>.magnitude(): Float = sqrt(sumOf { it.toDouble() * it }.toFloat())

private fun List<Float>.toTraceVector(): String = joinToString(",") { it.traceDecimal() }

private fun Float.traceDecimal(): String = TelemetryFormatters.decimal(this, 1)

private const val MAGNETIC_CALIBRATION_TRACE_INTERVAL_MS = 1_000L
