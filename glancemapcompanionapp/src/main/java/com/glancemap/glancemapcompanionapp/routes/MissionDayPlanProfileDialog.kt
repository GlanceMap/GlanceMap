@file:Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "MaxLineLength", "TooManyFunctions")

package com.glancemap.glancemapcompanionapp.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import kotlin.math.roundToInt

@Composable
internal fun MissionDayPlanProfileDialog(
    dayUi: MissionPlanDayUi,
    weather: MissionDayWeatherUiState?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day ${dayUi.day.dayNumber} plan profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = dayUi.day.name ?: dayUi.route.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text =
                        "Elevation comes from the GPX. Weather is shown only at prepared GPX start, " +
                            "midpoint, and finish samples—there is no invented weather between them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                MissionDayPlanGraph(
                    profile = dayUi.profile,
                    weatherSamples = weather?.samples.orEmpty(),
                )
                MissionDayProfileWeatherSummary(
                    dayUi = dayUi,
                    weather = weather,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun MissionDayPlanGraph(
    profile: MissionDayPlanProfile,
    weatherSamples: List<MissionDayWeatherSampleUi>,
) {
    if (profile.points.size < 2) {
        Text(
            text = "This GPX day does not contain enough points for an elevation profile.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val scrollState = rememberScrollState()
    val elevations = profile.points.mapNotNull(MissionDayPlanProfilePoint::elevationMeters)
    val elevationRange = elevations.minOrNull()?.let { minimum -> elevations.maxOrNull()?.let { maximum -> minimum to maximum } }

    Text(
        text = "ELEVATION • DISTANCE • PLANNED TIME",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    elevationRange?.let { (minimum, maximum) ->
        Text(
            text = "${minimum.roundToInt()}–${maximum.roundToInt()} m • scroll sideways to inspect the route",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
        Canvas(
            modifier =
                Modifier
                    .width(PROFILE_CHART_WIDTH)
                    .height(PROFILE_CHART_HEIGHT),
        ) {
            val horizontalPadding = 18.dp.toPx()
            val verticalPadding = 18.dp.toPx()
            val graphWidth = (size.width - horizontalPadding * 2).coerceAtLeast(1f)
            val graphHeight = (size.height - verticalPadding * 2).coerceAtLeast(1f)
            val minElevation = elevations.minOrNull() ?: 0.0
            val maxElevation = elevations.maxOrNull() ?: minElevation + 1.0
            val elevationSpan = (maxElevation - minElevation).takeIf { span -> span > 0.0 } ?: 1.0
            val xForDistance = { distanceMeters: Double ->
                horizontalPadding +
                    graphWidth * (distanceMeters / profile.totalDistanceMeters).coerceIn(0.0, 1.0).toFloat()
            }
            val yForElevation = { elevationMeters: Double ->
                verticalPadding +
                    graphHeight * (1.0 - (elevationMeters - minElevation) / elevationSpan).toFloat()
            }
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start =
                    androidx.compose.ui.geometry
                        .Offset(horizontalPadding, size.height - verticalPadding),
                end =
                    androidx.compose.ui.geometry
                        .Offset(size.width - horizontalPadding, size.height - verticalPadding),
                strokeWidth = 1.dp.toPx(),
            )
            val elevationPoints = profile.points.filter { point -> point.elevationMeters != null }
            if (elevationPoints.size >= 2) {
                val path = Path()
                elevationPoints.forEachIndexed { index, point ->
                    val x = xForDistance(point.distanceFromDayStartMeters)
                    val y = yForElevation(requireNotNull(point.elevationMeters))
                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(
                    path = path,
                    color = Color(0xFF2E7D32),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            weatherSamples.forEach { sample ->
                val x = xForDistance(sample.target.distanceFromDayStartMeters)
                drawLine(
                    color = Color(0xFF1565C0).copy(alpha = 0.55f),
                    start =
                        androidx.compose.ui.geometry
                            .Offset(x, verticalPadding),
                    end =
                        androidx.compose.ui.geometry
                            .Offset(x, size.height - verticalPadding),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(
                    color = Color(0xFF1565C0),
                    radius = 5.dp.toPx(),
                    center =
                        androidx.compose.ui.geometry
                            .Offset(x, size.height - verticalPadding),
                )
            }
        }
        Row(
            modifier = Modifier.width(PROFILE_CHART_WIDTH),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Start • 0 km",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Finish • ${profile.totalDistanceMeters.toProfileDistance()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (elevations.size < 2) {
        Text(
            text = "The GPX has insufficient elevation values, so only the distance and weather markers are shown.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MissionDayProfileWeatherSummary(
    dayUi: MissionPlanDayUi,
    weather: MissionDayWeatherUiState?,
) {
    Text(
        text = "WEATHER MARKERS",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    when {
        dayUi.day.plannedDate == null || dayUi.day.plannedStartTime == null -> {
            Text(
                text = "Add a date and planned start time, then load Day weather to place weather on the timeline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        weather?.isLoading == true -> {
            Text(
                text = "Loading planned weather…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        weather?.samples?.isEmpty() != false -> {
            Text(
                text = "Load Day weather to add start, midpoint, and finish conditions to this profile.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        else -> {
            weather.samples.forEach { sample ->
                MissionDayProfileWeatherRow(sample)
            }
        }
    }
}

@Composable
private fun MissionDayProfileWeatherRow(sample: MissionDayWeatherSampleUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text =
                    "${sample.target.position.label.uppercase()} • " +
                        "${sample.target.distanceFromDayStartMeters.toProfileDistance()} • " +
                        (sample.scheduledTimeIso8601?.toProfileTime() ?: "planned time unavailable"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            val scheduled = sample.scheduledOutlook
            val daily = sample.dailyOutlook
            if (scheduled != null) {
                Text(text = weatherConditionText(scheduled.weatherCode), fontWeight = FontWeight.SemiBold)
                val metrics =
                    listOfNotNull(
                        scheduled.temperatureCelsius?.let { value -> "${value.roundToInt()}°C" },
                        scheduled.precipitationProbabilityPercent?.let { value -> "rain ${value.roundToInt()}%" },
                        scheduled.windGustKilometersPerHour?.let { value -> "gusts ${value.roundToInt()} km/h" },
                    )
                if (metrics.isNotEmpty()) {
                    Text(
                        text = metrics.joinToString("  •  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (daily != null) {
                Text(
                    text = "Daily outlook • ${weatherConditionText(daily.weatherCode)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = sample.message ?: "Forecast unavailable at this GPX location.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun Double.toProfileDistance(): String =
    if (this < 1_000.0) {
        "${roundToInt()} m"
    } else {
        "%.1f km".format(this / 1_000.0)
    }

private fun String.toProfileTime(): String = substringAfter('T', missingDelimiterValue = this).take(5)

private val PROFILE_CHART_WIDTH = 760.dp
private val PROFILE_CHART_HEIGHT = 220.dp
