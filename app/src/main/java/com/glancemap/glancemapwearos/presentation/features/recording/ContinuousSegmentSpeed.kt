package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.RecordingContinuousSpeedSample
import org.mapsforge.core.model.LatLong
import kotlin.jvm.JvmName
import com.glancemap.trailcore.recording.FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1 as FASTEST_METHOD
import com.glancemap.trailcore.recording.calculateFastestContinuousSegmentSpeedMps as sharedCalculateSegmentSpeed
import com.glancemap.trailcore.recording.fastestRecordingPointSegmentSpeedMps as sharedFastestSpeed

internal const val FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1 = FASTEST_METHOD

internal data class ContinuousSpeedSample(
    val latLong: LatLong,
    val timeMillis: Long?,
    val startsNewSegment: Boolean = false,
    val segmentStartReason: String? = null,
)

internal fun calculateFastestContinuousSegmentSpeedMps(samples: List<ContinuousSpeedSample>): Double? =
    sharedCalculateSegmentSpeed(
        samples.map { sample ->
            RecordingContinuousSpeedSample(
                location = GeoPoint(sample.latLong.latitude, sample.latLong.longitude),
                timeMillis = sample.timeMillis,
                startsNewSegment = sample.startsNewSegment,
                segmentStartReason = sample.segmentStartReason,
            )
        },
    )

@JvmName("fastestRecordedTraceSegmentSpeedMps")
internal fun List<RecordedTracePoint>.fastestContinuousSegmentSpeedMps(): Double? = sharedFastestSpeed()

@JvmName("fastestTrackSegmentSpeedMps")
internal fun List<TrackPoint>.fastestContinuousSegmentSpeedMps(): Double? =
    sharedCalculateSegmentSpeed(
        map { point ->
            RecordingContinuousSpeedSample(
                location = GeoPoint(point.latLong.latitude, point.latLong.longitude),
                timeMillis = point.timeMillis,
                startsNewSegment = point.startsNewSegment,
                segmentStartReason = point.segmentStartReason,
            )
        },
    )
