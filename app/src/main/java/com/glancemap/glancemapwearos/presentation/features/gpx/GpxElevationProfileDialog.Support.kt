package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.formatting.DurationFormatter
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal const val SWIPE_DISMISS_THRESHOLD_PX = 48f
internal const val ROTARY_ZOOM_THRESHOLD_PX = 36f
internal const val PROFILE_ZOOM_STEP_FACTOR = 0.82
internal const val PROFILE_MIN_VISIBLE_SAMPLE_COUNT = 24
internal const val PROFILE_MIN_VISUAL_ELEVATION_RANGE_METERS = 50.0
internal const val PROFILE_FLAT_SEGMENT_ELEVATION_NOISE_METERS = 2.0

private enum class ProfileSegmentType {
    FLAT,
    UPHILL,
    CLIMB,
    DOWNHILL,
    DESCENT,
}

internal data class ElevationProgressUi(
    val doneDistance: Double,
    val doneAscent: Double,
    val doneDescent: Double,
    val doneDurationSec: Double?,
)

internal data class ElevationViewport(
    val startDistance: Double,
    val endDistance: Double,
) {
    val span: Double
        get() = (endDistance - startDistance).coerceAtLeast(0.0)
}

internal data class ElevationVisualBounds(
    val minElevation: Double,
    val maxElevation: Double,
) {
    val range: Double
        get() = (maxElevation - minElevation).coerceAtLeast(1.0)
}

@Composable
internal fun SwipeHandleHint() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(26.dp)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.42f), RoundedCornerShape(50)),
        )
    }
}

@Composable
internal fun CurrentElevationLabel(
    value: String,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize),
        color = Color.White.copy(alpha = 0.92f),
        maxLines = 1,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
internal fun MovingAxisChip(
    text: String,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize),
        color = Color.White.copy(alpha = 0.94f),
        maxLines = 1,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.54f), RoundedCornerShape(7.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
internal fun ElevationDeltaSummaryRow(
    ascentText: String,
    descentText: String,
    iconSize: Dp,
    textSize: TextUnit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElevationDeltaRow(
            icon = Icons.Filled.ArrowDropUp,
            valueText = ascentText,
            iconTint = Color(0xFFFFC857),
            iconSize = (iconSize - 2.dp).coerceAtLeast(10.dp),
            textSize = textSize,
            modifier = Modifier.weight(1f),
        )
        ElevationDeltaRow(
            icon = Icons.Filled.ArrowDropDown,
            valueText = descentText,
            iconTint = Color(0xFF73C2FB),
            iconSize = (iconSize - 2.dp).coerceAtLeast(10.dp),
            textSize = textSize,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ElevationDeltaRow(
    icon: ImageVector,
    valueText: String,
    iconTint: Color,
    iconSize: Dp,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ElevationLeftScale(
    minElevationMeters: Double,
    maxElevationMeters: Double,
    isMetric: Boolean,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val top =
        remember(minElevationMeters, maxElevationMeters, isMetric) {
            formatElevationScaleTick(maxElevationMeters, isMetric)
        }
    val bottom =
        remember(minElevationMeters, maxElevationMeters, isMetric) {
            formatElevationScaleTick(minElevationMeters, isMetric)
        }
    val labelColor = Color.White.copy(alpha = 0.74f)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = top,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize),
            color = labelColor,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
        Text(
            text = bottom,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize),
            color = labelColor,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

// Existing chart gesture and rendering state is intentionally co-located so their coordinate system stays aligned.
@Suppress("LongParameterList", "LongMethod", "FunctionNaming")
@Composable
internal fun ElevationProfileChart(
    samples: List<ElevationSample>,
    selectedIndex: Int,
    locationDistanceMeters: Double?,
    onSelectIndex: (Int) -> Unit,
    viewport: ElevationViewport,
    plotTopInset: Dp,
    plotBottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = Color.White.copy(alpha = 0.22f)
    val gridColor = Color.White.copy(alpha = 0.14f)
    val baselineColor = Color.White.copy(alpha = 0.24f)
    val axisColor = Color.White.copy(alpha = 0.30f)
    val drawingRange =
        remember(samples, viewport) {
            drawingSampleIndexRange(samples = samples, viewport = viewport)
        }
    val selectionRange =
        remember(samples, viewport) {
            selectionSampleIndexRange(samples = samples, viewport = viewport)
        }

    Canvas(
        modifier =
            modifier
                .background(backgroundColor, RoundedCornerShape(10.dp))
                .pointerInput(samples, viewport, selectionRange) {
                    detectTapGestures { offset ->
                        onSelectIndex(
                            indexFromX(
                                x = offset.x,
                                width = size.width.toFloat(),
                                samples = samples,
                                viewport = viewport,
                                selectionRange = selectionRange,
                            ),
                        )
                    }
                }.pointerInput(samples, viewport, selectionRange) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onSelectIndex(
                                indexFromX(
                                    x = offset.x,
                                    width = size.width.toFloat(),
                                    samples = samples,
                                    viewport = viewport,
                                    selectionRange = selectionRange,
                                ),
                            )
                        },
                    ) { change, _ ->
                        change.consume()
                        onSelectIndex(
                            indexFromX(
                                x = change.position.x,
                                width = size.width.toFloat(),
                                samples = samples,
                                viewport = viewport,
                                selectionRange = selectionRange,
                            ),
                        )
                    }
                },
    ) {
        if (samples.size < 2) return@Canvas

        val minDistance = viewport.startDistance
        val maxDistance = viewport.endDistance
        val elevationBounds = elevationVisualBounds(samples)
        val minElevation = elevationBounds.minElevation
        val elevationRange = elevationBounds.range

        val distanceRange = (maxDistance - minDistance).takeIf { it > 0.0 } ?: 1.0
        val topInsetPx = plotTopInset.toPx()
        val bottomInsetPx = plotBottomInset.toPx()
        val plotTopY = topInsetPx.coerceAtLeast(0f)
        val plotBottomY = (size.height - bottomInsetPx).coerceAtLeast(plotTopY + 1f)
        val plotHeight = (plotBottomY - plotTopY).coerceAtLeast(1f)

        val points =
            (drawingRange.first..drawingRange.last).map { index ->
                val sample = samples[index]
                val x = (((sample.distance - minDistance) / distanceRange).toFloat()) * size.width
                val y = plotBottomY - (((sample.elevation - minElevation) / elevationRange).toFloat() * plotHeight)
                Offset(x, y)
            }

        val fillPath =
            Path().apply {
                moveTo(points.first().x, plotBottomY)
                lineTo(points.first().x, points.first().y)
                for (i in 1..points.lastIndex) {
                    lineTo(points[i].x, points[i].y)
                }
                lineTo(points.last().x, plotBottomY)
                close()
            }

        listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
            val y = plotTopY + (plotHeight * fraction)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val yAxisX = 1.5.dp.toPx()
        drawLine(
            color = axisColor,
            start = Offset(yAxisX, plotTopY),
            end = Offset(yAxisX, plotBottomY),
            strokeWidth = 1.dp.toPx(),
        )

        drawLine(
            color = baselineColor,
            start = Offset(0f, plotBottomY),
            end = Offset(size.width, plotBottomY),
            strokeWidth = 1.dp.toPx(),
        )
        drawPath(path = fillPath, color = Color.White.copy(alpha = 0.05f))

        for (i in 1..points.lastIndex) {
            val from = samples[drawingRange.first + i - 1]
            val to = samples[drawingRange.first + i]
            val segmentType = classifySegment(from, to)
            drawLine(
                color = segmentColor(segmentType),
                start = points[i - 1],
                end = points[i],
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val selectedVisibleIndex = (selectedIndex - drawingRange.first).coerceIn(0, points.lastIndex)
        val cursorPoint = points[selectedVisibleIndex]
        val locationPoint =
            locationDistanceMeters
                ?.takeIf { distance -> distance.isFinite() && distance in minDistance..maxDistance }
                ?.let { distance ->
                    elevationAtDistance(samples = samples, distance = distance)?.let { elevation ->
                        Offset(
                            x = (((distance - minDistance) / distanceRange).toFloat()) * size.width,
                            y =
                                plotBottomY -
                                    (((elevation - minElevation) / elevationRange).toFloat() * plotHeight),
                        )
                    }
                }
        val axisTickColor = Color.White.copy(alpha = 0.84f)
        drawLine(
            color = Color.White.copy(alpha = 0.56f),
            start = Offset(cursorPoint.x, plotTopY),
            end = Offset(cursorPoint.x, plotBottomY),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = axisTickColor,
            start = Offset(yAxisX, cursorPoint.y),
            end = Offset((yAxisX + 7.dp.toPx()).coerceAtMost(size.width), cursorPoint.y),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = axisTickColor,
            start = Offset(cursorPoint.x, plotBottomY),
            end = Offset(cursorPoint.x, (plotBottomY - 7.dp.toPx()).coerceAtLeast(plotTopY)),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.7f),
            radius = 3.4.dp.toPx(),
            center = cursorPoint,
        )
        drawCircle(
            color = Color.White,
            radius = 2.2.dp.toPx(),
            center = cursorPoint,
        )
        locationPoint?.let { point ->
            drawCircle(
                color = Color.Black.copy(alpha = 0.84f),
                radius = 5.4.dp.toPx(),
                center = point,
            )
            drawCircle(
                color = Color.White,
                radius = 4.2.dp.toPx(),
                center = point,
            )
            drawCircle(
                color = Color(0xFF0A84FF),
                radius = 3.1.dp.toPx(),
                center = point,
            )
        }
    }
}

internal fun elevationAtDistance(
    samples: List<ElevationSample>,
    distance: Double,
): Double? =
    when {
        samples.isEmpty() || !distance.isFinite() -> null
        else -> {
            val afterIndex = samples.indexOfFirst { sample -> sample.distance >= distance }
            when {
                afterIndex <= 0 -> samples.first().elevation
                afterIndex < 0 -> samples.last().elevation
                else -> interpolateElevation(samples[afterIndex - 1], samples[afterIndex], distance)
            }
        }
    }

private fun interpolateElevation(
    before: ElevationSample,
    after: ElevationSample,
    distance: Double,
): Double {
    val span = after.distance - before.distance
    if (span <= 0.0) return after.elevation
    val fraction = ((distance - before.distance) / span).coerceIn(0.0, 1.0)
    return before.elevation + (after.elevation - before.elevation) * fraction
}

private fun classifySegment(
    from: ElevationSample,
    to: ElevationSample,
): ProfileSegmentType {
    val distanceDelta = (to.distance - from.distance).coerceAtLeast(0.0)
    val elevationDelta = to.elevation - from.elevation
    val isFlat =
        distanceDelta <= 0.0 ||
            kotlin.math.abs(elevationDelta) < PROFILE_FLAT_SEGMENT_ELEVATION_NOISE_METERS
    val gradePercent = if (distanceDelta > 0.0) (elevationDelta / distanceDelta) * 100.0 else 0.0
    return when {
        isFlat -> ProfileSegmentType.FLAT
        gradePercent >= 8.0 -> ProfileSegmentType.CLIMB
        gradePercent >= 2.0 -> ProfileSegmentType.UPHILL
        gradePercent <= -8.0 -> ProfileSegmentType.DESCENT
        gradePercent <= -2.0 -> ProfileSegmentType.DOWNHILL
        else -> ProfileSegmentType.FLAT
    }
}

private fun segmentColor(type: ProfileSegmentType): Color =
    when (type) {
        ProfileSegmentType.FLAT -> Color(0xFFD9E3EA)
        ProfileSegmentType.UPHILL -> Color(0xFFFFC857)
        ProfileSegmentType.CLIMB -> Color(0xFFFF8A3C)
        ProfileSegmentType.DOWNHILL -> Color(0xFF73C2FB)
        ProfileSegmentType.DESCENT -> Color(0xFF3B82F6)
    }

internal fun selectedElevationBiasY(
    samples: List<ElevationSample>,
    selectedIndex: Int,
    chartHeightPx: Float,
    plotTopInsetPx: Float,
    plotBottomInsetPx: Float,
): Float {
    if (samples.isEmpty() || chartHeightPx <= 0f) return 0f

    val elevationBounds = elevationVisualBounds(samples)
    val minElevation = elevationBounds.minElevation
    val range = elevationBounds.range
    val selected = samples[selectedIndex.coerceIn(0, samples.lastIndex)].elevation

    val plotTopY = plotTopInsetPx.coerceAtLeast(0f)
    val plotBottomY = (chartHeightPx - plotBottomInsetPx).coerceAtLeast(plotTopY + 1f)
    val plotHeight = (plotBottomY - plotTopY).coerceAtLeast(1f)

    val normalized = ((selected - minElevation) / range).toFloat().coerceIn(0f, 1f)
    val y =
        (plotBottomY - (normalized * plotHeight)).coerceIn(
            minimumValue = plotTopY + 6f,
            maximumValue = plotBottomY - 6f,
        )

    return ((y / chartHeightPx) * 2f - 1f).coerceIn(-1f, 1f)
}

internal fun elevationVisualBounds(samples: List<ElevationSample>): ElevationVisualBounds {
    if (samples.isEmpty()) {
        return ElevationVisualBounds(minElevation = 0.0, maxElevation = PROFILE_MIN_VISUAL_ELEVATION_RANGE_METERS)
    }
    val minElevation = samples.minOf { it.elevation }
    val maxElevation = samples.maxOf { it.elevation }
    val actualRange = (maxElevation - minElevation).coerceAtLeast(0.0)
    val visualRange = actualRange.coerceAtLeast(PROFILE_MIN_VISUAL_ELEVATION_RANGE_METERS)
    val center = (minElevation + maxElevation) / 2.0
    val halfRange = visualRange / 2.0
    return ElevationVisualBounds(
        minElevation = center - halfRange,
        maxElevation = center + halfRange,
    )
}

internal fun computeElevationProgress(
    profile: GpxElevationProfileUiState,
    selectedIndex: Int,
): ElevationProgressUi? {
    if (profile.samples.isEmpty()) return null

    val sample = profile.samples[selectedIndex.coerceIn(0, profile.samples.lastIndex)]
    val doneDistance = sample.distance.coerceIn(0.0, profile.totalDistance)
    val doneDurationSec = sample.cumulativeDurationSec?.coerceAtLeast(0.0)

    return ElevationProgressUi(
        doneDistance = doneDistance,
        doneAscent = sample.cumulativeAscent.coerceAtLeast(0.0),
        doneDescent = sample.cumulativeDescent.coerceAtLeast(0.0),
        doneDurationSec = doneDurationSec,
    )
}

internal fun computeMinimumVisibleDistance(
    samples: List<ElevationSample>,
    totalDistance: Double,
): Double {
    if (samples.size < 2 || totalDistance <= 0.0) return totalDistance.coerceAtLeast(0.0)

    val averageSampleSpacing = (totalDistance / (samples.size - 1)).coerceAtLeast(1.0)
    val sampleDrivenMinimum = averageSampleSpacing * PROFILE_MIN_VISIBLE_SAMPLE_COUNT
    val fractionDrivenMinimum = totalDistance * 0.06
    return maxOf(sampleDrivenMinimum, fractionDrivenMinimum).coerceAtMost(totalDistance)
}

internal fun zoomViewportAroundDistance(
    current: ElevationViewport,
    anchorDistance: Double,
    totalDistance: Double,
    minimumSpan: Double,
    zoomIn: Boolean,
): ElevationViewport {
    if (totalDistance <= 0.0) return current

    val clampedCurrent =
        clampViewport(
            viewport = current,
            totalDistance = totalDistance,
            minimumSpan = minimumSpan,
        )
    val currentSpan = clampedCurrent.span.takeIf { it > 0.0 } ?: return clampedCurrent
    val targetSpan =
        if (zoomIn) {
            (currentSpan * PROFILE_ZOOM_STEP_FACTOR).coerceAtLeast(minimumSpan)
        } else {
            (currentSpan / PROFILE_ZOOM_STEP_FACTOR).coerceAtMost(totalDistance)
        }
    if (kotlin.math.abs(targetSpan - currentSpan) < 0.5) {
        return clampedCurrent
    }

    val anchorRatio =
        if (currentSpan > 0.0) {
            ((anchorDistance - clampedCurrent.startDistance) / currentSpan).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
    var start = anchorDistance - (targetSpan * anchorRatio)
    var end = start + targetSpan
    if (start < 0.0) {
        end -= start
        start = 0.0
    }
    if (end > totalDistance) {
        val overflow = end - totalDistance
        start -= overflow
        end = totalDistance
    }

    return clampViewport(
        viewport = ElevationViewport(startDistance = start, endDistance = end),
        totalDistance = totalDistance,
        minimumSpan = minimumSpan,
    )
}

internal fun clampViewport(
    viewport: ElevationViewport,
    totalDistance: Double,
    minimumSpan: Double,
): ElevationViewport {
    if (totalDistance <= 0.0) return ElevationViewport(0.0, 0.0)

    val targetSpan =
        viewport.span
            .coerceAtLeast(minimumSpan.coerceAtMost(totalDistance))
            .coerceAtMost(totalDistance)
    val maxStart = (totalDistance - targetSpan).coerceAtLeast(0.0)
    val start = viewport.startDistance.coerceIn(0.0, maxStart)
    return ElevationViewport(
        startDistance = start,
        endDistance = (start + targetSpan).coerceIn(0.0, totalDistance),
    )
}

private fun selectionSampleIndexRange(
    samples: List<ElevationSample>,
    viewport: ElevationViewport,
): IntRange {
    if (samples.isEmpty()) return 0..0
    if (samples.size == 1) return 0..0

    val firstInside = findFirstSampleIndexAtOrAfter(samples, viewport.startDistance)
    val lastInside = findLastSampleIndexAtOrBefore(samples, viewport.endDistance)

    if (firstInside <= lastInside) {
        return firstInside..lastInside
    }

    val anchorIndex = findNearestSampleIndex(samples, (viewport.startDistance + viewport.endDistance) / 2.0)
    return anchorIndex..anchorIndex
}

private fun drawingSampleIndexRange(
    samples: List<ElevationSample>,
    viewport: ElevationViewport,
): IntRange {
    if (samples.isEmpty()) return 0..0
    if (samples.size == 1) return 0..0

    val selectionRange = selectionSampleIndexRange(samples = samples, viewport = viewport)
    val startIndex = (selectionRange.first - 1).coerceAtLeast(0)
    val endIndex = (selectionRange.last + 1).coerceAtMost(samples.lastIndex)

    return startIndex..endIndex
}

private fun indexFromX(
    x: Float,
    width: Float,
    samples: List<ElevationSample>,
    viewport: ElevationViewport,
    selectionRange: IntRange,
): Int {
    if (samples.isEmpty() || width <= 0f) return 0
    if (selectionRange.first >= selectionRange.last) {
        return selectionRange.first.coerceIn(0, samples.lastIndex)
    }

    val normalized = (x / width).coerceIn(0f, 1f)
    val targetDistance = viewport.startDistance + ((viewport.endDistance - viewport.startDistance) * normalized)
    return findNearestSampleIndexInRange(
        samples = samples,
        targetDistance = targetDistance,
        startIndex = selectionRange.first,
        endIndex = selectionRange.last,
    )
}

private fun findFirstSampleIndexAtOrAfter(
    samples: List<ElevationSample>,
    distance: Double,
): Int {
    val index = samples.binarySearchBy(distance) { it.distance }
    return if (index >= 0) index else (-index - 1).coerceIn(0, samples.lastIndex)
}

private fun findLastSampleIndexAtOrBefore(
    samples: List<ElevationSample>,
    distance: Double,
): Int {
    val index = samples.binarySearchBy(distance) { it.distance }
    return if (index >= 0) index else (-index - 2).coerceIn(0, samples.lastIndex)
}

private fun findNearestSampleIndex(
    samples: List<ElevationSample>,
    targetDistance: Double,
): Int =
    findNearestSampleIndexInRange(
        samples = samples,
        targetDistance = targetDistance,
        startIndex = 0,
        endIndex = samples.lastIndex,
    )

private fun findNearestSampleIndexInRange(
    samples: List<ElevationSample>,
    targetDistance: Double,
    startIndex: Int,
    endIndex: Int,
): Int {
    if (samples.isEmpty()) return 0
    val safeStart = startIndex.coerceIn(0, samples.lastIndex)
    val safeEnd = endIndex.coerceIn(safeStart, samples.lastIndex)
    if (safeStart == safeEnd) return safeStart

    val window = samples.subList(safeStart, safeEnd + 1)
    val localIndex = window.binarySearchBy(targetDistance) { it.distance }
    if (localIndex >= 0) return safeStart + localIndex

    val insertionPoint = -localIndex - 1
    val rightIndex = (safeStart + insertionPoint).coerceIn(safeStart, safeEnd)
    val leftIndex = (rightIndex - 1).coerceIn(safeStart, safeEnd)
    val leftDistanceDelta = kotlin.math.abs(samples[leftIndex].distance - targetDistance)
    val rightDistanceDelta = kotlin.math.abs(samples[rightIndex].distance - targetDistance)
    return if (rightDistanceDelta < leftDistanceDelta) rightIndex else leftIndex
}

internal fun formatDistanceScaleText(
    meters: Double,
    isMetric: Boolean,
): String {
    val value =
        if (isMetric) {
            meters / 1000.0
        } else {
            meters * 0.000621371
        }
    val unit = if (isMetric) "km" else "mi"

    if (value < 0.05) {
        return "0 $unit"
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, unit)
}

internal fun formatElevationText(
    meters: Double,
    isMetric: Boolean,
): String {
    val (value, unit) = UnitFormatter.formatElevation(meters, isMetric)
    return "$value $unit"
}

internal fun formatElevationCompactText(
    meters: Double,
    isMetric: Boolean,
): String {
    val (value, unit) = UnitFormatter.formatElevation(meters, isMetric)
    return "$value$unit"
}

internal fun formatElevationScaleTick(
    meters: Double,
    isMetric: Boolean,
): String {
    val value =
        if (isMetric) {
            meters
        } else {
            meters * 3.28084
        }
    return String.format(Locale.getDefault(), "%,d", value.roundToInt())
}

internal fun formatDurationText(seconds: Double?): String = DurationFormatter.formatDurationShort(seconds)
