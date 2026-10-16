@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp.routes

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.abs
import kotlin.math.cos

private data class StageGraphPoint(
    val distance: Double,
    val x: Double,
    val y: Double,
    val elevation: Double?,
    val startsNewSegment: Boolean,
)

@Composable
internal fun MissionStageGraphs(
    route: RouteLibraryRouteDetails,
    stops: List<Double>,
    selectedStop: Int,
    enabled: Boolean,
    onMove: (Double) -> Unit,
) {
    val points = remember(route, stops) { stageGraphPoints(route, stops) }
    val colours =
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            Color(0xFF187AA3),
            Color(0xFFBC670E),
        )
    val boundaries = listOf(0.0) + stops + route.profile.totalDistanceMeters
    val allowed = boundaries[selectedStop]..boundaries[(selectedStop + 2).coerceAtMost(boundaries.lastIndex)]
    Column {
        Text(stringResource(R.string.stage_route_overview), style = MaterialTheme.typography.labelMedium)
        StageGraph(points, stops, selectedStop, allowed, colours, false, enabled, onMove)
        Text(stringResource(R.string.stage_elevation), style = MaterialTheme.typography.labelMedium)
        StageGraph(points, stops, selectedStop, allowed, colours, true, enabled, onMove)
        if (points.none { it.elevation != null }) {
            Text(stringResource(R.string.stage_no_elevation), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Suppress(
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
) // Both small previews share the same point projection, selection, and stop markers.
@Composable
private fun StageGraph(
    points: List<StageGraphPoint>,
    stops: List<Double>,
    selectedStop: Int,
    allowed: ClosedFloatingPointRange<Double>,
    colours: List<Color>,
    elevation: Boolean,
    enabled: Boolean,
    onMove: (Double) -> Unit,
) {
    val xs = points.map { if (elevation) it.distance else it.x }
    val minimumElevation = points.mapNotNull { it.elevation }.minOrNull() ?: 0.0
    val ys = points.map { if (elevation) -(it.elevation ?: minimumElevation) else it.y }
    val xMin = xs.minOrNull() ?: 0.0
    val yMin = ys.minOrNull() ?: 0.0
    val xSpan = ((xs.maxOrNull() ?: 0.0) - xMin).coerceAtLeast(0.000001)
    val ySpan = ((ys.maxOrNull() ?: 0.0) - yMin).coerceAtLeast(0.000001)
    val ink = MaterialTheme.colorScheme.onSurface
    val fill = MaterialTheme.colorScheme.surface

    fun project(
        index: Int,
        width: Float,
        height: Float,
    ): Offset {
        val inset = 16f
        val usableWidth = (width - 2 * inset).coerceAtLeast(1f)
        val usableHeight = (height - 2 * inset).coerceAtLeast(1f)
        val scale = minOf(usableWidth / xSpan, usableHeight / ySpan)
        val graphWidth = if (elevation) usableWidth.toDouble() else xSpan * scale
        val graphHeight = if (elevation) usableHeight.toDouble() else ySpan * scale
        return Offset(
            ((width - graphWidth) / 2 + (xs[index] - xMin) / xSpan * graphWidth).toFloat(),
            ((height - graphHeight) / 2 + (ys[index] - yMin) / ySpan * graphHeight).toFloat(),
        )
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (elevation) 130.dp else 180.dp)
            .pointerInput(points, selectedStop, allowed, enabled) {
                detectTapGestures { position ->
                    if (enabled) {
                        val index =
                            points.indices
                                .filter {
                                    points[it].distance > allowed.start &&
                                        points[it].distance < allowed.endInclusive
                                }.minByOrNull {
                                    (
                                        project(it, size.width.toFloat(), size.height.toFloat()) - position
                                    ).getDistanceSquared()
                                }
                        index?.let { onMove(points[it].distance) }
                    }
                }
            },
    ) {
        if (elevation && points.isNotEmpty()) {
            drawLine(
                ink.copy(alpha = 0.25f),
                Offset(16f, size.height / 2),
                Offset(size.width - 16f, size.height / 2),
                strokeWidth = 1.dp.toPx(),
            )
        }
        points.indices.drop(1).forEach { index ->
            val missingElevation =
                elevation &&
                    (points[index - 1].elevation == null || points[index].elevation == null)
            if (!points[index].startsNewSegment && !missingElevation) {
                val stage = stops.count { it <= points[index - 1].distance }
                drawLine(
                    colours[stage % colours.size],
                    project(index - 1, size.width, size.height),
                    project(index, size.width, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 12.dp.toPx()
            }
        stops.forEachIndexed { index, distance ->
            val pointIndex = points.indices.minByOrNull { abs(points[it].distance - distance) } ?: return@forEachIndexed
            val position = project(pointIndex, size.width, size.height)
            drawCircle(if (index == selectedStop) ink else colours[index % colours.size], 10.dp.toPx(), position)
            drawCircle(fill, 8.dp.toPx(), position)
            drawContext.canvas.nativeCanvas.drawText(
                (index + 1).toString(),
                position.x,
                position.y - (paint.ascent() + paint.descent()) / 2,
                paint,
            )
        }
    }
}

private fun stageGraphPoints(
    route: RouteLibraryRouteDetails,
    stops: List<Double>,
): List<StageGraphPoint> {
    val profile = route.profile
    val step = (profile.points.size / 800).coerceAtLeast(1)
    val stopIndices =
        stops
            .map { stop ->
                profile.cumulativeDistanceMeters.indices.minBy {
                    abs(
                        profile.cumulativeDistanceMeters[it] - stop,
                    )
                }
            }.toSet()
    var longitude =
        profile.points
            .firstOrNull()
            ?.location
            ?.longitude ?: 0.0
    var previous = longitude
    val latitudeScale =
        cos(
            Math.toRadians(
                profile.points
                    .firstOrNull()
                    ?.location
                    ?.latitude ?: 0.0,
            ),
        )
    return profile.points.mapIndexedNotNull { index, point ->
        var delta = point.location.longitude - previous
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        longitude += delta
        previous = point.location.longitude
        if (!keepStagePoint(
                index,
                profile.points.lastIndex,
                step,
                stopIndices,
                point.startsNewSegment || profile.points.getOrNull(index + 1)?.startsNewSegment == true,
            )
        ) {
            null
        } else {
            StageGraphPoint(
                profile.cumulativeDistanceMeters[index],
                longitude * latitudeScale,
                -point.location.latitude,
                point.elevationMeters,
                point.startsNewSegment,
            )
        }
    }
}

private fun keepStagePoint(
    index: Int,
    last: Int,
    step: Int,
    stops: Set<Int>,
    segmentBoundary: Boolean,
): Boolean =
    when {
        index == last -> true
        index in stops -> true
        segmentBoundary -> true
        else -> index % step == 0
    }
