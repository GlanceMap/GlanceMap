package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import kotlinx.coroutines.CancellationException

@Composable
@Suppress("FunctionNaming")
internal fun ActivityElevationProfileSection(
    filePath: String,
    isMetric: Boolean,
    trackColor: Color,
    loadProfile: suspend (String) -> ActivityElevationProfile?,
) {
    var profile by remember(filePath) { mutableStateOf<ActivityElevationProfile?>(null) }
    var loading by remember(filePath) { mutableStateOf(true) }
    var failed by remember(filePath) { mutableStateOf(false) }
    LaunchedEffect(filePath) {
        try {
            profile = loadProfile(filePath)
            failed = profile == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
        loading = false
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Elevation profile", style = MaterialTheme.typography.titleSmall)
        val loaded = profile
        when {
            loading -> Text("Loading elevation profile…", style = MaterialTheme.typography.labelSmall)
            failed -> Text("Unable to load elevation profile", style = MaterialTheme.typography.labelSmall)
            loaded == null || loaded.samples.size < 2 ->
                Text("No elevation data", style = MaterialTheme.typography.labelSmall)
            else -> ActivityElevationProfileChart(profile = loaded, isMetric = isMetric, trackColor = trackColor)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ActivityElevationProfileChart(
    profile: ActivityElevationProfile,
    isMetric: Boolean,
    trackColor: Color,
) {
    val bounds = remember(profile) { elevationVisualBounds(profile.samples) }
    val adaptive = rememberWearAdaptiveSpec()
    val distanceText = formatDistanceScaleText(profile.totalDistance, isMetric)
    val gapDescription = if (profile.samples.any { it.gapBefore }) "; grey lines indicate gaps" else ""
    val description =
        "Elevation profile, $distanceText, " +
            "${formatElevationText(profile.samples.minOf { it.elevation }, isMetric)} to " +
            formatElevationText(profile.samples.maxOf { it.elevation }, isMetric) + gapDescription
    Row(
        modifier = Modifier.fillMaxWidth().height(if (adaptive.isCompact) 100.dp else 120.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ElevationLeftScale(
                minElevationMeters = bounds.minElevation,
                maxElevationMeters = bounds.maxElevation,
                isMetric = isMetric,
                textSize = MaterialTheme.typography.labelSmall.fontSize,
                modifier = Modifier.weight(1f),
            )
            Text(text = if (isMetric) "m" else "ft", style = MaterialTheme.typography.labelSmall)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ActivityElevationProfilePlot(
                profile = profile,
                bounds = bounds,
                description = description,
                trackColor = trackColor,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = if (isMetric) "0 km" else "0 mi", style = MaterialTheme.typography.labelSmall)
                Text(text = distanceText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (profile.samples.any { it.gapBefore }) {
        Text("Grey lines: gaps", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ActivityElevationProfilePlot(
    profile: ActivityElevationProfile,
    bounds: ElevationVisualBounds,
    description: String,
    trackColor: Color,
    modifier: Modifier,
) {
    Canvas(
        modifier =
            modifier
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                .testTag(ACTIVITY_ELEVATION_CHART_TAG)
                .semantics { contentDescription = description },
    ) {
        val inset = 2.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(1f)
        val height = (size.height - 2 * inset).coerceAtLeast(1f)
        val distanceRange = profile.totalDistance.takeIf { it > 0.0 } ?: 1.0
        val points =
            profile.samples.map { sample ->
                Offset(
                    x = inset + (sample.distance / distanceRange).toFloat().coerceIn(0f, 1f) * width,
                    y = inset + height - ((sample.elevation - bounds.minElevation) / bounds.range).toFloat() * height,
                )
            }
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(inset, size.height / 2),
            end = Offset(size.width - inset, size.height / 2),
        )
        for (index in 1..points.lastIndex) {
            drawLine(
                color = elevationProfileSegmentColor(profile.samples[index - 1], profile.samples[index], trackColor),
                start = points[index - 1],
                end = points[index],
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

internal const val ACTIVITY_ELEVATION_CHART_TAG = "activity_elevation_profile_chart"
