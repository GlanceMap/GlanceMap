package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.toRecordingLocalMeters
import org.mapsforge.core.model.LatLong
import com.glancemap.trailcore.recording.recordingCanonicalPathDistance as pathDistance
import com.glancemap.trailcore.recording.recordingTrajectoryRelevantBarrierCenterRange as sharedRelevantBarrierRange
import com.glancemap.trailcore.recording.recordingTrajectoryTailStartIndex as sharedRecordingTrajectoryTailStartIndex
import com.glancemap.trailcore.recording.recordingTrajectoryTurnBarriers as sharedRecordingTrajectoryTurnBarriers

internal fun recordingCanonicalPathDistance(points: List<RecordedTracePoint>): Double = pathDistance(points)

internal fun recordingTrajectoryTailStartIndex(
    points: List<RecordedTracePoint>,
    options: RecordingPointSmoothingOptions,
): Int = sharedRecordingTrajectoryTailStartIndex(points, options)

internal fun recordingTrajectoryRelevantBarrierCenterRange(
    firstFinalizeIndex: Int,
    endFinalizeExclusive: Int,
    options: RecordingPointSmoothingOptions,
): IntRange = sharedRelevantBarrierRange(firstFinalizeIndex, endFinalizeExclusive, options)

internal fun recordingTrajectoryTurnBarriers(
    points: List<RecordedTracePoint>,
    options: RecordingPointSmoothingOptions,
    centerRange: IntRange = 2..(points.lastIndex - 2),
): Set<Int> = sharedRecordingTrajectoryTurnBarriers(points, options, centerRange)

internal fun GeoPoint.toLocalMeters(origin: GeoPoint): LocalMeters = toRecordingLocalMeters(origin)

internal fun LocalMeters.toLatLong(origin: GeoPoint): GeoPoint = toGeoPoint(origin)

internal fun LatLong.toLocalMeters(origin: LatLong): LocalMeters {
    val point = GeoPoint(latitude, longitude)
    val originPoint = GeoPoint(origin.latitude, origin.longitude)
    return point.toRecordingLocalMeters(originPoint)
}

internal fun LocalMeters.toLatLong(origin: LatLong): LatLong {
    val point = toGeoPoint(GeoPoint(origin.latitude, origin.longitude))
    return LatLong(point.latitude, point.longitude)
}

internal fun angleDegrees(
    first: LocalMeters,
    second: LocalMeters,
): Double {
    val denominator = first.length() * second.length()
    if (denominator <= 0.0) return 180.0
    val cosine = ((first.x * second.x + first.y * second.y) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(kotlin.math.acos(cosine))
}

internal fun Float?.validAccuracyOr(fallbackMeters: Double): Double =
    this
        ?.takeIf { it.isFinite() && it >= 0f }
        ?.toDouble()
        ?: fallbackMeters
