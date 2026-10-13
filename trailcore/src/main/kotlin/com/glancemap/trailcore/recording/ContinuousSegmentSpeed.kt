package com.glancemap.trailcore.recording

const val FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1 = "continuous_segment_geometry_v1"

data class RecordingContinuousSpeedSample(
    val location: com.glancemap.trailcore.geo.GeoPoint,
    val timeMillis: Long?,
    val startsNewSegment: Boolean = false,
    val segmentStartReason: String? = null,
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

fun List<RecordingPoint>.fastestRecordingPointSegmentSpeedMps(): Double? {
    var fastestSpeedMps: Double? = null
    for (index in 1..lastIndex) {
        val previous = this[index - 1]
        val current = this[index]
        val speed =
            continuousSegmentSpeedMps(
                previous = previous.location,
                previousTimeMillis = previous.timeMillis,
                current = current.location,
                currentTimeMillis = current.timeMillis,
                startsNewSegment = current.startsNewSegment,
                segmentStartReason = current.segmentStartReason,
            ) ?: continue
        fastestSpeedMps = maxOf(fastestSpeedMps ?: speed, speed)
    }
    return fastestSpeedMps
}

private fun continuousSegmentSpeedMps(
    previous: RecordingContinuousSpeedSample,
    current: RecordingContinuousSpeedSample,
): Double? =
    continuousSegmentSpeedMps(
        previous = previous.location,
        previousTimeMillis = previous.timeMillis,
        current = current.location,
        currentTimeMillis = current.timeMillis,
        startsNewSegment = current.startsNewSegment,
        segmentStartReason = current.segmentStartReason,
    )

private fun continuousSegmentSpeedMps(
    previous: com.glancemap.trailcore.geo.GeoPoint,
    previousTimeMillis: Long?,
    current: com.glancemap.trailcore.geo.GeoPoint,
    currentTimeMillis: Long?,
    startsNewSegment: Boolean,
    segmentStartReason: String?,
): Double? {
    val elapsedMillis =
        if (startsNewSegment || !segmentStartReason.isNullOrBlank()) {
            null
        } else {
            previousTimeMillis?.let { previousTime -> currentTimeMillis?.minus(previousTime) }
        }
    return elapsedMillis
        ?.takeIf { it > 0L }
        ?.let { validElapsedMillis ->
            recordingHaversineMeters(previous, current)
                .takeIf(Double::isFinite)
                ?.div(validElapsedMillis / 1_000.0)
        }?.takeIf { it.isFinite() && it > 0.0 }
}
