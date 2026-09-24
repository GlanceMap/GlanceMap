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
        val previous = samples[index - 1]
        val current = samples[index]
        if (current.startsNewSegment || !current.segmentStartReason.isNullOrBlank()) continue
        val previousTimeMillis = previous.timeMillis ?: continue
        val currentTimeMillis = current.timeMillis ?: continue
        val elapsedMillis = currentTimeMillis - previousTimeMillis
        if (elapsedMillis <= 0L) continue
        val distanceMeters = haversineMeters(previous.latLong, current.latLong)
        if (!distanceMeters.isFinite()) continue
        val speedMps = distanceMeters / (elapsedMillis / 1_000.0)
        if (speedMps.isFinite() && speedMps > 0.0 && (fastestSpeedMps == null || speedMps > fastestSpeedMps)) {
            fastestSpeedMps = speedMps
        }
    }
    return fastestSpeedMps
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
