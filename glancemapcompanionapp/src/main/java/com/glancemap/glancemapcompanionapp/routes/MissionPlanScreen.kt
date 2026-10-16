@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
    "TooManyFunctions",
)

package com.glancemap.glancemapcompanionapp.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun MissionDayWeatherSampleRow(
    sample: MissionDayWeatherSampleUi,
    plannedDate: String,
) {
    val textResources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text =
                if (sample.target.position ==
                    MissionDayWeatherSamplePosition.STAY
                ) {
                    sample.target.location.label
                } else {
                    "${textResources.getString(
                        sample.target.position.labelResource(),
                    ).uppercase()} • ${sample.target.distanceFromDayStartMeters.toMissionPlanDistance()}"
                },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        when {
            sample.forecast == null -> {
                Text(
                    text = sample.message ?: textResources.getString(R.string.mission_plan_forecast_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            sample.dailyOutlook == null && sample.scheduledOutlook == null -> {
                Text(
                    text =
                        textResources.getString(
                            R.string.mission_plan_no_forecast_is_available_for_value_at,
                            plannedDate,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                val outlook = sample.dailyOutlook
                sample.scheduledOutlook?.let { scheduled ->
                    Text(
                        text =
                            textResources.getString(
                                R.string.mission_plan_around_value,
                                sample.scheduledTimeIso8601?.toMissionWeatherTimeText()
                                    ?: textResources.getString(R.string.mission_plan_planned_time),
                            ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = weatherConditionText(textResources, scheduled.weatherCode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val scheduledMetrics =
                        listOfNotNull(
                            scheduled.precipitationProbabilityPercent?.let { value ->
                                textResources.getString(R.string.mission_plan_rain_value, value.roundToInt())
                            },
                            scheduled.windGustKilometersPerHour?.let { value ->
                                textResources.getString(R.string.mission_plan_gusts_value_km_h, value.roundToInt())
                            },
                            scheduled.visibilityMeters?.let { value ->
                                textResources.getString(
                                    R.string.mission_plan_visibility_value,
                                    value.toMissionPlanDistance(),
                                )
                            },
                        )
                    if (scheduledMetrics.isNotEmpty()) {
                        Text(
                            text = scheduledMetrics.joinToString("  •  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                outlook?.let { daily ->
                    Text(
                        text =
                            if (sample.scheduledOutlook ==
                                null
                            ) {
                                textResources.getString(R.string.mission_plan_daily_outlook)
                            } else {
                                textResources.getString(R.string.mission_plan_day_range)
                            },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (sample.scheduledOutlook == null) {
                        Text(
                            text = weatherConditionText(textResources, daily.weatherCode),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val dailyMetrics =
                        listOfNotNull(
                            daily.minimumTemperatureCelsius?.let { value ->
                                textResources.getString(
                                    R.string.mission_plan_low_value,
                                    value.toMissionWeatherTemperature(),
                                )
                            },
                            daily.maximumTemperatureCelsius?.let { value ->
                                textResources.getString(
                                    R.string.mission_plan_high_value,
                                    value.toMissionWeatherTemperature(),
                                )
                            },
                            daily.precipitationProbabilityPercent?.let { value ->
                                textResources.getString(R.string.mission_plan_rain_value, value.roundToInt())
                            },
                            daily.windGustKilometersPerHour?.let { value ->
                                textResources.getString(R.string.mission_plan_gusts_value_km_h, value.roundToInt())
                            },
                        )
                    if (dailyMetrics.isNotEmpty()) {
                        Text(
                            text = dailyMetrics.joinToString("  •  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text =
                        textResources.getString(
                            R.string.mission_plan_updated_value,
                            sample.forecast.fetchedAtEpochMillis.toMissionWeatherUpdatedText(),
                        ) +
                            textResources.getQuantityString(
                                R.plurals.weather_saved_snapshots,
                                sample.savedSnapshotCount,
                                sample.savedSnapshotCount,
                            ) +
                            when {
                                sample.isStale -> textResources.getString(R.string.mission_plan_cached_unable_to_update)
                                sample.isCached -> textResources.getString(R.string.mission_plan_cached)
                                else -> ""
                            },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun MissionDayTimelineDialog(
    dayUi: MissionPlanDayUi,
    onDismiss: () -> Unit,
) {
    val textResources = LocalResources.current
    val timeline = dayUi.timeline
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResources.getString(R.string.mission_plan_day_value_journey, dayUi.day.dayNumber)) },
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
                        textResources.getString(R.string.mission_plan_from_gpx_route_distance_elevation_and_waypoints),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                timeline.events.forEach { event ->
                    MissionDayTimelineEventRow(event)
                }
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
private fun MissionDayTimelineEventRow(event: MissionDayTimelineEvent) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = event.type.timelineLabel(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(text = event.localizedTitle(), style = MaterialTheme.typography.bodyLarge)
        event.detail?.let { detail ->
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text =
                listOf(
                    event.distanceFromDayStartMeters.toMissionPlanDistance(),
                    event.estimatedOffsetSeconds.toMissionPlanOffset(stringResource(R.string.mission_timeline_start)),
                ).joinToString("  •  "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

fun MissionPlanDayUi.missionPlanTodaySummary(): String = briefing.missionPlanBriefingSummary()

private fun com.glancemap.trailcore.profile.TrailWindow.missionPlanBriefingSummary(): String =
    listOf(
        distanceMeters.toMissionPlanDistance(),
        "+${ascentMeters.roundToInt()} m",
        estimatedDurationSeconds.toMissionPlanDuration(),
    ).joinToString("  •  ")

internal fun Double.toMissionPlanDistance(): String =
    if (
        this < 1_000.0
    ) {
        "${roundToInt()} m"
    } else {
        "%.1f km".format(this / 1_000.0)
    }

internal fun Double.toMissionPlanDuration(): String {
    val totalMinutes = (this / 60.0).roundToInt().coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) "$minutes min" else "$hours h ${minutes.toString().padStart(2, '0')} min"
}

private fun Double.toMissionPlanOffset(label: String) = if (this <= 0.0) label else "+${toMissionPlanDuration()}"

internal fun Double.toMissionPlanKilometers(): String = "%.2f".format(Locale.ROOT, this / 1_000.0)

private fun String.toMissionPlanDate(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

internal fun String.missionPlanDateLabel(): String? = toMissionPlanDate()?.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

@Composable
private fun MissionDayTimelineEventType.timelineLabel(): String {
    val textResources = LocalResources.current
    return when (this) {
        MissionDayTimelineEventType.START -> textResources.getString(R.string.mission_plan_start)
        MissionDayTimelineEventType.CLIMB -> textResources.getString(R.string.mission_plan_climb)
        MissionDayTimelineEventType.WAYPOINT -> textResources.getString(R.string.mission_plan_gpx_waypoint)
        MissionDayTimelineEventType.FINISH -> textResources.getString(R.string.mission_plan_finish)
    }
}

private fun Double.toMissionWeatherTemperature(): String = "${roundToInt()}°C"

private fun Long.toMissionWeatherUpdatedText(): String =
    DateFormat
        .getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
        ).format(Date(this))

private fun String.toMissionWeatherTimeText(): String =
    runCatching {
        java.time.LocalDateTime
            .parse(this)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(this)

@Composable
private fun MissionDayTimelineEvent.localizedTitle(): String =
    when (type) {
        MissionDayTimelineEventType.START -> stringResource(R.string.mission_timeline_start)
        MissionDayTimelineEventType.CLIMB -> stringResource(R.string.mission_timeline_climb)
        MissionDayTimelineEventType.FINISH -> stringResource(R.string.mission_timeline_finish)
        MissionDayTimelineEventType.WAYPOINT -> title
    }
