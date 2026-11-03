package com.glancemap.trailcore.recording

/** Geometric adjacent-segment maximum without a Doppler plausibility guard (files written before v2). */
const val FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1 = "continuous_segment_geometry_v1"

/** Geometric adjacent-segment maximum, skipping segments the endpoint Doppler speeds cannot explain. */
const val FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V2 = "continuous_segment_geometry_v2"

/**
 * A segment whose geometric speed exceeds `max(endpoint Doppler) * ratio + margin` is treated as
 * position error (sparse fixes cutting corners, 1 Hz jitter) and cannot become the fastest speed.
 * The ratio absorbs fused-provider Doppler under-reporting and mid-segment speed peaks.
 */
const val FASTEST_SPEED_DOPPLER_GUARD_RATIO = 1.3
const val FASTEST_SPEED_DOPPLER_GUARD_MARGIN_MPS = 0.5

data class RecordingContinuousSpeedSample(
    val location: com.glancemap.trailcore.geo.GeoPoint,
    val timeMillis: Long?,
    val startsNewSegment: Boolean = false,
    val segmentStartReason: String? = null,
    /** Provider (Doppler) speed; only bounds the geometric speed and is never reported itself. */
    val speedMps: Float? = null,
)

/** Returns the fastest finite positive speed between valid adjacent recorded samples. */
fun calculateFastestContinuousSegmentSpeedMps(samples: List<RecordingContinuousSpeedSample>): Double? {
    var fastestSpeedMps: Double? = null
    for (index in 1..samples.lastIndex) {
        continuousSegmentSpeedMps(samples[index - 1], samples[index])?.let { speedMps ->
            fastestSpeedMps = maxOf(fastestSpeedMps ?: speedMps, speedMps)
        }
    }
    return fastestSpeedMps
}

fun List<RecordingPoint>.fastestRecordingPointSegmentSpeedMps(): Double? =
    calculateFastestContinuousSegmentSpeedMps(
        map { point ->
            RecordingContinuousSpeedSample(
                location = point.location,
                timeMillis = point.timeMillis,
                startsNewSegment = point.startsNewSegment,
                segmentStartReason = point.segmentStartReason,
                speedMps = point.speedMps,
            )
        },
    )

private fun continuousSegmentSpeedMps(
    previous: RecordingContinuousSpeedSample,
    current: RecordingContinuousSpeedSample,
): Double? {
    val elapsedMillis =
        if (current.startsNewSegment || !current.segmentStartReason.isNullOrBlank()) {
            null
        } else {
            previous.timeMillis?.let { previousTime -> current.timeMillis?.minus(previousTime) }
        }
    return elapsedMillis
        ?.takeIf { it > 0L }
        ?.let { validElapsedMillis ->
            recordingHaversineMeters(previous.location, current.location)
                .takeIf(Double::isFinite)
                ?.div(validElapsedMillis / 1_000.0)
        }?.takeIf { it.isFinite() && it > 0.0 && it <= dopplerSpeedBoundMps(previous, current) }
}

private fun dopplerSpeedBoundMps(
    previous: RecordingContinuousSpeedSample,
    current: RecordingContinuousSpeedSample,
): Double {
    val endpointSpeedMps =
        listOfNotNull(previous.speedMps, current.speedMps)
            .filter { it.isFinite() && it >= 0f }
            .maxOrNull()
            ?: return Double.POSITIVE_INFINITY
    return endpointSpeedMps * FASTEST_SPEED_DOPPLER_GUARD_RATIO + FASTEST_SPEED_DOPPLER_GUARD_MARGIN_MPS
}
