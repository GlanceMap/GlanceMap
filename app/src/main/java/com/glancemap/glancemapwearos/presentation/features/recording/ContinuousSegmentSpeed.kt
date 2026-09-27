package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.haversineMeters
import org.mapsforge.core.model.LatLong
import kotlin.jvm.JvmName

internal const val FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1 =
    "continuous_segment_geometry_v1"

internal data class ContinuousSpeedSample(
    val latLong: LatLong,
    val timeMillis: Long?,
    val startsNewSegment: Boolean = false,
    val segmentStartReason: String? = null,
)

/** Returns the fastest finite positive speed between valid adjacent recorded samples. */
internal fun calculateFastestContinuousSegmentSpeedMps(samples: List<ContinuousSpeedSample>): Double? {
    var fastestSpeedMps: Double? = null
    for (index in 1..samples.lastIndex) {
        continuousSegmentSpeedMps(samples[index - 1], samples[index])?.let { speedMps ->
            fastestSpeedMps = maxOf(fastestSpeedMps ?: speedMps, speedMps)
        }
    }
    return fastestSpeedMps
}

private fun continuousSegmentSpeedMps(
    previous: ContinuousSpeedSample,
    current: ContinuousSpeedSample,
): Double? {
    val elapsedMillis =
        if (current.startsNewSegment || !current.segmentStartReason.isNullOrBlank()) {
            null
        } else {
            previous.timeMillis?.let { previousTimeMillis ->
                current.timeMillis?.minus(previousTimeMillis)
            }
        }
    return elapsedMillis
        ?.takeIf { it > 0L }
        ?.let { validElapsedMillis ->
            haversineMeters(previous.latLong, current.latLong)
                .takeIf(Double::isFinite)
                ?.div(validElapsedMillis / 1_000.0)
        }?.takeIf { it.isFinite() && it > 0.0 }
}

@JvmName("fastestRecordedTraceSegmentSpeedMps")
internal fun List<RecordedTracePoint>.fastestContinuousSegmentSpeedMps(): Double? =
    calculateFastestContinuousSegmentSpeedMps(
        map { point ->
            ContinuousSpeedSample(
                latLong = point.latLong,
                timeMillis = point.timeMillis,
                startsNewSegment = point.startsNewSegment,
                segmentStartReason = point.segmentStartReason,
            )
        },
    )

@JvmName("fastestTrackSegmentSpeedMps")
internal fun List<TrackPoint>.fastestContinuousSegmentSpeedMps(): Double? =
    calculateFastestContinuousSegmentSpeedMps(
        map { point ->
            ContinuousSpeedSample(
                latLong = point.latLong,
                timeMillis = point.timeMillis,
                startsNewSegment = point.startsNewSegment,
                segmentStartReason = point.segmentStartReason,
            )
        },
    )
