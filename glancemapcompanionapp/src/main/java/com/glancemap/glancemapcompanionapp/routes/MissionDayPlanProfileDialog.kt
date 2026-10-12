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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import kotlin.math.roundToInt

@Composable
internal fun MissionDayPlanProfileDialog(
    dayUi: MissionPlanDayUi,
    weather: MissionDayWeatherUiState?,
    onDismiss: () -> Unit,
) {
    val textResources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResources.getString(R.string.mission_profile_day_value_plan_profile, dayUi.day.dayNumber)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = dayUi.day.name ?: dayUi.route.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text =
                        textResources.getString(R.string.mission_profile_elevation_comes_from_the_gpx_weather_is),
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
                Text(textResources.getString(R.string.common_action_done))
            }
        },
    )
}

@Composable
private fun MissionDayPlanGraph(
    profile: MissionDayPlanProfile,
    weatherSamples: List<MissionDayWeatherSampleUi>,
) {
    val textResources = LocalResources.current
    if (profile.points.size < 2) {
        Text(
            text = textResources.getString(R.string.mission_profile_this_gpx_day_does_not_contain_enough),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val scrollState = rememberScrollState()
    val elevations = profile.points.mapNotNull(MissionDayPlanProfilePoint::elevationMeters)
    val elevationRange = elevations.minOrNull()?.let { minimum -> elevations.maxOrNull()?.let { maximum -> minimum to maximum } }

    Text(
        text = textResources.getString(R.string.mission_profile_elevation_distance_planned_time),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    elevationRange?.let { (minimum, maximum) ->
        Text(
            text =
                textResources.getString(
                    R.string.mission_profile_value_value_m_scroll_sideways_to_inspect,
                    minimum.roundToInt(),
                    maximum.roundToInt(),
                ),
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
                text = textResources.getString(R.string.mission_profile_start_0_km),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    textResources.getString(
                        R.string.mission_profile_finish_value,
                        profile.totalDistanceMeters.toProfileDistance(),
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (elevations.size < 2) {
        Text(
            text = textResources.getString(R.string.mission_profile_the_gpx_has_insufficient_elevation_values_so),
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
    val textResources = LocalResources.current
    Text(
        text = textResources.getString(R.string.mission_profile_weather_markers),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    when {
        dayUi.day.plannedDate == null || dayUi.day.plannedStartTime == null -> {
            Text(
                text = textResources.getString(R.string.mission_profile_add_a_date_and_planned_start_time),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        weather?.isLoading == true -> {
            Text(
                text = textResources.getString(R.string.mission_profile_loading_planned_weather),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        weather?.samples?.isEmpty() != false -> {
            Text(
                text = textResources.getString(R.string.mission_profile_load_day_weather_to_add_start_midpoint),
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
    val textResources = LocalResources.current
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
                    "${textResources.getString(sample.target.position.labelResource()).uppercase()} • " +
                        "${sample.target.distanceFromDayStartMeters.toProfileDistance()} • " +
                        (
                            sample.scheduledTimeIso8601?.toProfileTime()
                                ?: textResources.getString(R.string.mission_profile_planned_time_unavailable)
                        ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            val scheduled = sample.scheduledOutlook
            val daily = sample.dailyOutlook
            if (scheduled != null) {
                Text(
                    text = weatherConditionText(textResources, scheduled.weatherCode),
                    fontWeight = FontWeight.SemiBold,
                )
                val metrics =
                    listOfNotNull(
                        scheduled.temperatureCelsius?.let { value -> "${value.roundToInt()}°C" },
                        scheduled.precipitationProbabilityPercent?.let { value ->
                            textResources.getString(R.string.mission_plan_rain_value, value.roundToInt())
                        },
                        scheduled.windGustKilometersPerHour?.let { value ->
                            textResources.getString(R.string.mission_plan_gusts_value_km_h, value.roundToInt())
                        },
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
                    text =
                        textResources.getString(
                            R.string.mission_profile_daily_outlook_value,
                            weatherConditionText(textResources, daily.weatherCode),
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text =
                        sample.message
                            ?: textResources.getString(
                                R.string.mission_profile_forecast_unavailable_at_this_gpx_location,
                            ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Double.toProfileDistance(): String {
    val textResources = LocalResources.current
    return if (this < 1_000.0) {
        "${roundToInt()} m"
    } else {
        textResources.getString(R.string.route_library_1f_km).format(this / 1_000.0)
    }
}

private fun String.toProfileTime(): String = substringAfter('T', missingDelimiterValue = this).take(5)

private val PROFILE_CHART_WIDTH = 760.dp
private val PROFILE_CHART_HEIGHT = 220.dp
