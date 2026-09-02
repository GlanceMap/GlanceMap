@file:Suppress(
    "FunctionNaming",
    "TooManyFunctions",
)

package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

internal data class PhoneMapGpxElevationSample(
    val distanceMeters: Double,
    val elevationMeters: Double,
    val cumulativeAscentMeters: Double,
    val cumulativeDescentMeters: Double,
    val cumulativeDurationSeconds: Double,
)

internal data class PhoneMapGpxAnalysis(
    val pointCount: Int,
    val totalDistanceMeters: Double,
    val totalAscentMeters: Double,
    val totalDescentMeters: Double,
    val estimatedDurationSeconds: Double,
    val flatSpeedMetersPerSecond: Double,
    val samples: List<PhoneMapGpxElevationSample>,
    val minElevationMeters: Double?,
    val maxElevationMeters: Double?,
)

internal fun buildPhoneMapGpxAnalysis(
    item: PhoneMapGpxItem,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
    maxSamples: Int = MAX_PHONE_MAP_GPX_ANALYSIS_SAMPLES,
): PhoneMapGpxAnalysis {
    require(maxSamples > 0) { "The elevation profile sample limit must be positive." }

    val points = item.track.points
    val pacing = settings.toTrailPacingConfig(points, generalSettings)
    val profile = buildTrailRouteProfile(points, pacing)
    val rawSamples =
        profile.points.mapIndexedNotNull { index, point ->
            val elevation = point.elevationMeters ?: return@mapIndexedNotNull null
            PhoneMapGpxElevationSample(
                distanceMeters = profile.cumulativeDistanceMeters[index],
                elevationMeters = elevation,
                cumulativeAscentMeters = profile.cumulativeAscentMeters[index],
                cumulativeDescentMeters = profile.cumulativeDescentMeters[index],
                cumulativeDurationSeconds = profile.cumulativeEstimatedDurationSeconds[index],
            )
        }
    val samples = rawSamples.downsamplePhoneMapGpxAnalysis(maxSamples)
    return PhoneMapGpxAnalysis(
        pointCount = points.size,
        totalDistanceMeters = profile.totalDistanceMeters,
        totalAscentMeters = profile.totalAscentMeters,
        totalDescentMeters = profile.totalDescentMeters,
        estimatedDurationSeconds = profile.estimatedDurationSeconds,
        flatSpeedMetersPerSecond = pacing.flatSpeedMetersPerSecond,
        samples = samples,
        minElevationMeters = samples.minOfOrNull(PhoneMapGpxElevationSample::elevationMeters),
        maxElevationMeters = samples.maxOfOrNull(PhoneMapGpxElevationSample::elevationMeters),
    )
}

private fun List<PhoneMapGpxElevationSample>.downsamplePhoneMapGpxAnalysis(
    maxSamples: Int,
): List<PhoneMapGpxElevationSample> =
    when {
        size <= maxSamples -> this
        maxSamples == 1 -> listOf(first())
        else -> {
            val step = lastIndex.toDouble() / (maxSamples - 1).toDouble()
            List(maxSamples) { index -> this[(index * step).toInt().coerceIn(0, lastIndex)] }
        }
    }

@Composable
@Suppress("LongMethod") // The full-screen analysis keeps loading, stats, and graph transitions together.
internal fun PhoneMapGpxAnalysisDialog(
    item: PhoneMapGpxItem,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
    onDismiss: () -> Unit,
) {
    var analysis by remember(item.id) { mutableStateOf<PhoneMapGpxAnalysis?>(null) }
    LaunchedEffect(item.id, settings, generalSettings) {
        analysis = null
        analysis =
            withContext(Dispatchers.Default) {
                buildPhoneMapGpxAnalysis(
                    item = item,
                    settings = settings,
                    generalSettings = generalSettings,
                )
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_action_close),
                        )
                    }
                }
                if (analysis == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.map_gpx_analysis_loading))
                        }
                    }
                } else {
                    PhoneMapGpxAnalysisContent(
                        analysis = requireNotNull(analysis),
                        isMetric = generalSettings.isMetric,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // The analysis screen presents related route statistics before the graph.
private fun ColumnScope.PhoneMapGpxAnalysisContent(
    analysis: PhoneMapGpxAnalysis,
    isMetric: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneMapGpxStatCard(
                label = stringResource(R.string.map_gpx_analysis_distance),
                value = formatPhoneMapMeasuredDistance(analysis.totalDistanceMeters, isMetric),
                modifier = Modifier.weight(1f),
            )
            PhoneMapGpxStatCard(
                label = stringResource(R.string.map_gpx_analysis_time),
                value = formatPhoneMapGpxDuration(analysis.estimatedDurationSeconds),
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneMapGpxStatCard(
                label = stringResource(R.string.map_gpx_analysis_ascent),
                value = formatPhoneMapGpxElevation(analysis.totalAscentMeters, isMetric),
                modifier = Modifier.weight(1f),
            )
            PhoneMapGpxStatCard(
                label = stringResource(R.string.map_gpx_analysis_descent),
                value = formatPhoneMapGpxElevation(analysis.totalDescentMeters, isMetric),
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneMapGpxStatCard(
                label = stringResource(R.string.map_gpx_analysis_lowest),
                value = formatOptionalPhoneMapGpxElevation(analysis.minElevationMeters, isMetric),
                modifier = Modifier.weight(1f),
            )
            PhoneMapGpxStatCard(
                label = stringResource(R.string.map_gpx_analysis_highest),
                value = formatOptionalPhoneMapGpxElevation(analysis.maxElevationMeters, isMetric),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text =
                stringResource(
                    R.string.map_gpx_analysis_speed,
                    formatPhoneMapGpxSpeed(analysis.flatSpeedMetersPerSecond, isMetric),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.map_gpx_analysis_points, analysis.pointCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhoneMapGpxElevationGraph(
            samples = analysis.samples,
            minElevationMeters = analysis.minElevationMeters,
            maxElevationMeters = analysis.maxElevationMeters,
            isMetric = isMetric,
        )
    }
}

@Composable
private fun PhoneMapGpxStatCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PhoneMapGpxElevationGraph(
    samples: List<PhoneMapGpxElevationSample>,
    minElevationMeters: Double?,
    maxElevationMeters: Double?,
    isMetric: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.map_gpx_analysis_elevation_profile),
            style = MaterialTheme.typography.titleMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            if (samples.size < 2 || minElevationMeters == null || maxElevationMeters == null) {
                Text(
                    text = stringResource(R.string.map_gpx_analysis_no_elevation),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(240.dp).padding(16.dp)) {
                    PhoneMapGpxElevationCanvas(
                        samples = samples,
                        minElevationMeters = minElevationMeters,
                        maxElevationMeters = maxElevationMeters,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = formatPhoneMapGpxElevation(maxElevationMeters, isMetric),
                        modifier = Modifier.align(Alignment.TopStart),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatPhoneMapGpxElevation(minElevationMeters, isMetric),
                        modifier = Modifier.align(Alignment.BottomStart),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneMapGpxElevationCanvas(
    samples: List<PhoneMapGpxElevationSample>,
    minElevationMeters: Double,
    maxElevationMeters: Double,
    modifier: Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val horizontalPadding = 34f
        val verticalPadding = 8f
        val plotWidth = (size.width - horizontalPadding).coerceAtLeast(1f)
        val plotHeight = (size.height - verticalPadding * 2f).coerceAtLeast(1f)
        val elevationSpan = (maxElevationMeters - minElevationMeters).takeIf { it > 0.0 } ?: 1.0
        val firstDistance = samples.first().distanceMeters
        val distanceSpan =
            (samples.last().distanceMeters - firstDistance).takeIf { it > 0.0 }
                ?: (samples.lastIndex).toDouble().coerceAtLeast(1.0)
        val path = Path()

        repeat(4) { index ->
            val y = verticalPadding + plotHeight * index.toFloat() / 3f
            drawLine(
                color = gridColor,
                start = Offset(horizontalPadding, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }
        samples.forEachIndexed { index, sample ->
            val x =
                horizontalPadding +
                    plotWidth *
                    if (samples.size == 1) 0f else ((sample.distanceMeters - firstDistance) / distanceSpan).toFloat()
            val y =
                verticalPadding +
                    plotHeight *
                    (1f - ((sample.elevationMeters - minElevationMeters) / elevationSpan).toFloat().coerceIn(0f, 1f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )
    }
}

private fun formatPhoneMapGpxElevation(
    meters: Double,
    isMetric: Boolean,
): String =
    if (isMetric) {
        "${meters.roundToInt()} m"
    } else {
        "${(meters * METERS_TO_FEET).roundToInt()} ft"
    }

private fun formatOptionalPhoneMapGpxElevation(
    meters: Double?,
    isMetric: Boolean,
): String = meters?.let { formatPhoneMapGpxElevation(it, isMetric) } ?: "—"

private fun formatPhoneMapGpxDuration(seconds: Double): String {
    if (!seconds.isFinite()) return "—"
    val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) "$minutes min" else "$hours h ${minutes.toString().padStart(2, '0')} min"
}

private fun formatPhoneMapGpxSpeed(
    metersPerSecond: Double,
    isMetric: Boolean,
): String {
    val speed = if (isMetric) metersPerSecond * 3.6 else metersPerSecond * 2.236936
    val unit = if (isMetric) "km/h" else "mph"
    return String.format(Locale.getDefault(), "%.1f %s", speed, unit)
}

private const val MAX_PHONE_MAP_GPX_ANALYSIS_SAMPLES = 120
private const val METERS_TO_FEET = 3.28084
