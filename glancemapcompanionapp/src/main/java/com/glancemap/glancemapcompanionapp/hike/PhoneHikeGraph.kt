package com.glancemap.glancemapcompanionapp.hike

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class ProfilePoint(
    val seconds: Double,
    val elevation: Double?,
    val breakBefore: Boolean,
)

private fun conditionColor(condition: PhoneHikeCondition?): Color? =
    when (condition) {
        PhoneHikeCondition.CLEAR -> Color(0xFFE3BD54)
        PhoneHikeCondition.CLOUDY -> Color(0xFF8795A5)
        PhoneHikeCondition.RAIN -> Color(0xFF5787AE)
        PhoneHikeCondition.SNOW -> Color(0xFF82ABC0)
        PhoneHikeCondition.STORM -> Color(0xFF8B6B99)
        null -> null
    }

@Suppress("FunctionNaming")
@Composable
internal fun PhoneHikeWeatherLegend(bands: List<PhoneHikeWeatherBand>) {
    if (bands.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        bands.map { it.condition }.distinct().forEach { condition ->
            val label =
                stringResource(
                    when (condition) {
                        PhoneHikeCondition.CLEAR -> R.string.hike_condition_clear
                        PhoneHikeCondition.CLOUDY -> R.string.hike_condition_cloudy
                        PhoneHikeCondition.RAIN -> R.string.hike_event_rain
                        PhoneHikeCondition.SNOW -> R.string.hike_event_snow
                        PhoneHikeCondition.STORM -> R.string.hike_event_storm
                        null -> R.string.hike_condition_unknown
                    },
                )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = conditionColor(condition) ?: MaterialTheme.colorScheme.outline
                Canvas(Modifier.size(8.dp)) { drawCircle(color.copy(alpha = if (condition == null) .25f else .6f)) }
                Spacer(Modifier.width(3.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun profilePoints(
    model: PhoneHikeRouteModel,
    timeline: PhoneHikeTimeline,
): List<ProfilePoint> {
    val route =
        model.graphPoints.mapNotNull { point ->
            model.atDistance(point.distanceMeters)?.let { position ->
                ProfilePoint(timeline.secondsAt(position), point.elevationMeters, point.startsNewSegment)
            }
        }
    return (
        route +
            timeline.stops.flatMap { stop ->
                listOf(
                    ProfilePoint(stop.start, stop.position.elevationMeters, false),
                    ProfilePoint(stop.end, stop.position.elevationMeters, false),
                )
            }
    ).sortedBy(ProfilePoint::seconds)
}

internal data class PhoneHikeGraphInputs(
    val model: PhoneHikeRouteModel,
    val plan: PhoneHikePlan,
    val timeline: PhoneHikeTimeline,
    val selectedSeconds: Double,
    val current: PhoneHikePosition?,
    val departure: Instant?,
    val zone: ZoneId,
    val metric: Boolean,
    val bands: List<PhoneHikeWeatherBand>,
)

// The scroll, cursor and chart share one coordinate system and are kept together.
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun PhoneHikeGraph(
    inputs: PhoneHikeGraphInputs,
    onInspect: (Double) -> Unit,
) {
    val model = inputs.model
    val plan = inputs.plan
    val timeline = inputs.timeline
    val selectedSeconds = inputs.selectedSeconds
    val current = inputs.current
    val departure = inputs.departure
    val zone = inputs.zone
    val metric = inputs.metric
    val bands = inputs.bands
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val latestInspect by rememberUpdatedState(onInspect)
    var programmatic by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val profileDescription = stringResource(R.string.hike_profile_description)
    val rainLabel = stringResource(R.string.hike_event_rain)
    val snowLabel = stringResource(R.string.hike_event_snow)
    val stormLabel = stringResource(R.string.hike_event_storm)
    val checkpoints = remember(model, plan.checkpoints) { model.orderedCheckpoints(plan) }
    val points = remember(model, timeline) { profilePoints(model, timeline) }
    val elevations = remember(points) { points.mapNotNull { it.elevation?.takeIf(Double::isFinite) } }
    val minimum = elevations.minOrNull() ?: 0.0
    val maximum = elevations.maxOrNull() ?: minimum
    val minLabel = if (metric) "${minimum.roundToInt()} m" else "${(minimum * 3.28084).roundToInt()} ft"
    val maxLabel = if (metric) "${maximum.roundToInt()} m" else "${(maximum * 3.28084).roundToInt()} ft"
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(154.dp).testTag("hike-profile").semantics {
            contentDescription = profileDescription
            progressBarRangeInfo = ProgressBarRangeInfo(selectedSeconds.toFloat(), 0f..timeline.totalSeconds.toFloat())
            setProgress { value ->
                latestInspect(value.toDouble().coerceIn(0.0, timeline.totalSeconds))
                true
            }
        },
    ) {
        val viewport = with(LocalDensity.current) { maxWidth.toPx() }
        val contentWidth = maxWidth * 3f
        val graphWidth = viewport * 2f
        LaunchedEffect(model, timeline, scroll, graphWidth) {
            var userScroll = false
            snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, dragged) }
                .distinctUntilChanged()
                .collect { (value, scrolling, dragging) ->
                    if (dragging) userScroll = true
                    if (userScroll && !programmatic) {
                        latestInspect((value / graphWidth * timeline.totalSeconds).coerceIn(0.0, timeline.totalSeconds))
                    }
                    if (!scrolling) userScroll = false
                }
        }
        LaunchedEffect(model, selectedSeconds, scroll.isScrollInProgress, graphWidth, timeline) {
            if (!scroll.isScrollInProgress) {
                programmatic = true
                try {
                    val offset = selectedSeconds / timeline.totalSeconds.coerceAtLeast(1.0) * graphWidth
                    scroll.scrollTo(offset.roundToInt())
                } finally {
                    programmatic = false
                }
            }
        }
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Canvas(Modifier.width(contentWidth).fillMaxHeight()) {
                val chartTop = 12.dp.toPx()
                val chartBottom = size.height - 27.dp.toPx()
                val span = (maximum - minimum).coerceAtLeast(1.0)

                fun x(seconds: Double): Float {
                    val fraction = (seconds / timeline.totalSeconds.coerceAtLeast(1.0)).toFloat()
                    return viewport / 2f + graphWidth * fraction
                }

                fun y(elevation: Double): Float {
                    val fraction = (1.0 - (elevation - minimum) / span).toFloat()
                    return chartTop + (chartBottom - chartTop) * fraction
                }
                bands.forEach { band ->
                    val color = conditionColor(band.condition)
                    if (color != null) {
                        drawRect(
                            color.copy(alpha = .12f),
                            Offset(x(band.startSeconds), chartTop),
                            Size(x(band.endSeconds) - x(band.startSeconds), chartBottom - chartTop),
                        )
                    }
                }
                val path = Path()
                var drawing = false
                points.forEach { point ->
                    val elevation = point.elevation
                    if (elevation == null || !elevation.isFinite()) {
                        drawing = false
                    } else {
                        if (!drawing || point.breakBefore) {
                            path.moveTo(x(point.seconds), y(elevation))
                        } else {
                            path.lineTo(x(point.seconds), y(elevation))
                        }
                        drawing = true
                    }
                }
                drawPath(path, primary, style = Stroke(2.dp.toPx()))
                drawLine(
                    labelColor.copy(alpha = .5f),
                    Offset(x(0.0), chartBottom),
                    Offset(x(timeline.totalSeconds), chartBottom),
                )
                val markerPaint =
                    Paint().apply {
                        color = labelColor.toArgb()
                        textSize = 10.dp.toPx()
                        isAntiAlias = true
                    }
                var lastCheckpointEnd = Float.NEGATIVE_INFINITY
                checkpoints.forEach { (checkpoint, point) ->
                    val checkpointX = x(timeline.secondsAt(point))
                    drawCircle(primary, 4.dp.toPx(), Offset(checkpointX, chartBottom))
                    val label = checkpoint.name.take(18)
                    if (checkpointX > lastCheckpointEnd) {
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            checkpointX + 5.dp.toPx(),
                            chartBottom - 7.dp.toPx(),
                            markerPaint,
                        )
                        lastCheckpointEnd = checkpointX + markerPaint.measureText(label) + 12.dp.toPx()
                    }
                }
                var lastMarkerEnd = Float.NEGATIVE_INFINITY
                bands.zipWithNext().forEach { (before, after) ->
                    val label =
                        when (after.condition) {
                            PhoneHikeCondition.RAIN -> rainLabel
                            PhoneHikeCondition.SNOW -> snowLabel
                            PhoneHikeCondition.STORM -> stormLabel
                            else -> null
                        }
                    val markerX = x(after.startSeconds)
                    val conditionChanged = before.condition != null && before.condition != after.condition
                    if (conditionChanged && label != null && markerX > lastMarkerEnd) {
                        drawCircle(labelColor, 3.dp.toPx(), Offset(markerX, chartTop + 4.dp.toPx()))
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            markerX + 5.dp.toPx(),
                            chartTop + 8.dp.toPx(),
                            markerPaint,
                        )
                        lastMarkerEnd = markerX + markerPaint.measureText(label) + 12.dp.toPx()
                    }
                }
                current?.let {
                    val currentX = x(timeline.secondsAt(it))
                    drawLine(Color(0xFF0284C7), Offset(currentX, chartTop), Offset(currentX, chartBottom), 2.dp.toPx())
                }
                if (departure != null) {
                    val paint =
                        Paint().apply {
                            color = labelColor.toArgb()
                            textSize = 10.dp.toPx()
                            isAntiAlias = true
                            textAlign = Paint.Align.CENTER
                        }
                    val formatter = DateTimeFormatter.ofPattern("HH:mm")
                    for (tick in 0..4) {
                        val seconds = timeline.totalSeconds * tick / 4.0
                        val label = departure.plusSeconds(seconds.toLong()).atZone(zone).format(formatter)
                        drawContext.canvas.nativeCanvas.drawText(label, x(seconds), size.height - 6.dp.toPx(), paint)
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                Color(0xFF9333EA),
                Offset(size.width / 2f, 0f),
                Offset(size.width / 2f, size.height - 22.dp.toPx()),
                2.dp.toPx(),
            )
        }
        if (elevations.isEmpty()) {
            Text(
                stringResource(R.string.hike_elevation_unavailable),
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                maxLabel,
                Modifier.align(Alignment.TopStart),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Text(
                minLabel,
                Modifier.align(Alignment.BottomStart).padding(bottom = 23.dp),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }
    }
}
