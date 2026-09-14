package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ElevationTrackSegment(
    val points: List<LatLong>,
    val color: Int,
)

internal enum class GpxElevationSegmentType {
    FLAT,
    UPHILL,
    CLIMB,
    DOWNHILL,
    DESCENT,
}

internal fun buildElevationTrackSegments(
    points: List<TrackPoint>,
    opacityPercent: Int,
): List<ElevationTrackSegment> {
    if (points.size < 2) return emptyList()

    val segments = mutableListOf<ElevationTrackSegment>()
    var currentColor: Int? = null
    var currentPoints = mutableListOf<LatLong>()

    fun flushCurrentSegment() {
        val color = currentColor
        if (currentPoints.size >= 2 && color != null) {
            segments +=
                ElevationTrackSegment(
                    points = currentPoints.toList(),
                    color = color,
                )
        }
        currentColor = null
        currentPoints = mutableListOf()
    }

    for (index in 1..points.lastIndex) {
        val from = points[index - 1]
        val to = points[index]
        if (to.startsNewSegment) {
            flushCurrentSegment()
            continue
        }
        val color =
            applyOpacityToColor(
                color = elevationSegmentColor(classifyElevationSegment(from, to)),
                opacityPercent = opacityPercent,
            )

        if (currentColor != color) {
            flushCurrentSegment()
            currentColor = color
            currentPoints = mutableListOf(from.latLong, to.latLong)
        } else {
            currentPoints += to.latLong
        }
    }

    flushCurrentSegment()

    return segments
}

internal fun classifyElevationSegment(
    from: TrackPoint,
    to: TrackPoint,
): GpxElevationSegmentType {
    val gradePercent =
        elevationGradePercent(
            from = from,
            to = to,
        )
    return when {
        gradePercent == null -> GpxElevationSegmentType.FLAT
        gradePercent >= 8.0 -> GpxElevationSegmentType.CLIMB
        gradePercent >= 2.0 -> GpxElevationSegmentType.UPHILL
        gradePercent <= -8.0 -> GpxElevationSegmentType.DESCENT
        gradePercent <= -2.0 -> GpxElevationSegmentType.DOWNHILL
        else -> GpxElevationSegmentType.FLAT
    }
}

private fun elevationGradePercent(
    from: TrackPoint,
    to: TrackPoint,
): Double? {
    val fromElevation = from.elevation
    val toElevation = to.elevation

    return when {
        fromElevation == null || toElevation == null -> null
        else -> {
            val distanceMeters =
                haversineMeters(
                    lat1 = from.latLong.latitude,
                    lon1 = from.latLong.longitude,
                    lat2 = to.latLong.latitude,
                    lon2 = to.latLong.longitude,
                ).coerceAtLeast(0.0)
            if (distanceMeters > 0.0) {
                ((toElevation - fromElevation) / distanceMeters) * 100.0
            } else {
                null
            }
        }
    }
}

internal fun elevationSegmentColor(type: GpxElevationSegmentType): Int =
    when (type) {
        GpxElevationSegmentType.FLAT -> 0xFFD9E3EA.toInt()
        GpxElevationSegmentType.UPHILL -> 0xFFFFC857.toInt()
        GpxElevationSegmentType.CLIMB -> 0xFFFF8A3C.toInt()
        GpxElevationSegmentType.DOWNHILL -> 0xFF73C2FB.toInt()
        GpxElevationSegmentType.DESCENT -> 0xFF3B82F6.toInt()
    }

internal fun createGpxTrackPaint(
    color: Int,
    strokeWidth: Float,
) = AndroidGraphicFactory.INSTANCE.createPaint().apply {
    setStyle(Style.STROKE)
    this.color = color
    this.strokeWidth = strokeWidth
}

private fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val radiusMeters = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return radiusMeters * c
}
