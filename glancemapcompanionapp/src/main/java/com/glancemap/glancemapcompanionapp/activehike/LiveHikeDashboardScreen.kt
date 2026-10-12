@file:Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package com.glancemap.glancemapcompanionapp.activehike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.LiveHikeWeatherContext
import com.glancemap.glancemapcompanionapp.routes.MissionDayWeatherSampleUi
import com.glancemap.glancemapcompanionapp.routes.labelResource
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LiveHikeDashboardScreen(
    update: PhoneActiveHikeSnapshot,
    weather: LiveHikeWeatherContext?,
    onLoadWeather: ((Boolean) -> Unit)?,
    onBack: () -> Unit,
) {
    val textResources = LocalResources.current
    val snapshot = update.snapshot
    val recording = snapshot.phase.isRecording()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = textResources.getString(R.string.common_back_to_home_content_description),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (recording) {
                            textResources.getString(
                                R.string.live_hike_live_recording,
                            )
                        } else {
                            textResources.getString(R.string.menu_live_hike)
                        },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = snapshot.routeTitle ?: textResources.getString(R.string.live_hike_watch_session),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (snapshot.offRoute) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = snapshot.liveStatusText(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        textResources.getString(
                            R.string.live_hike_watch_update_value,
                            snapshot.recordedAtEpochMillis.toLiveTimeText(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (recording) {
            RecordingDashboard(snapshot)
        } else {
            RoutedHikeDashboard(snapshot)
        }
        LiveHikeWeatherCard(
            weather = weather,
            snapshot = snapshot,
            onLoadWeather = onLoadWeather,
        )
    }
}

@Composable
private fun LiveHikeWeatherCard(
    weather: LiveHikeWeatherContext?,
    snapshot: ActiveHikeSnapshot,
    onLoadWeather: ((Boolean) -> Unit)?,
) {
    val textResources = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = textResources.getString(R.string.live_hike_weather_along_the_hike),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            when {
                weather == null -> {
                    Text(
                        text = textResources.getString(R.string.live_hike_no_selected_mission_plan_day_matches_this),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weather.plannedDate == null -> {
                    Text(
                        text =
                            textResources.getString(
                                R.string.live_hike_add_a_date_to_value_in_mission,
                                weather.dayName,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weather.plannedStartTime == null -> {
                    Text(
                        text = textResources.getString(R.string.live_hike_add_a_planned_start_time_in_mission),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    onLoadWeather?.let { load ->
                        TextButton(onClick = { load(false) }) {
                            Text(textResources.getString(R.string.live_hike_load_daily_forecast))
                        }
                    }
                }

                weather.isLoading -> {
                    Text(
                        text = textResources.getString(R.string.mission_profile_loading_planned_weather),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weather.samples.isEmpty() -> {
                    Text(
                        text =
                            textResources.getString(
                                R.string.live_hike_prepare_weather_for_value_start_value_before,
                                weather.plannedDate,
                                weather.plannedStartTime,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    onLoadWeather?.let { load ->
                        TextButton(onClick = { load(false) }) {
                            Text(textResources.getString(R.string.live_hike_load_planned_weather))
                        }
                    }
                }

                else -> {
                    Text(
                        text =
                            textResources.getString(
                                R.string.live_hike_value_planned_start_value,
                                weather.plannedDate,
                                weather.plannedStartTime,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    nearestWeatherSample(weather.samples, snapshot)?.let { sample ->
                        Text(
                            text =
                                textResources.getString(
                                    R.string.live_hike_nearest_planned_point_value,
                                    textResources
                                        .getString(sample.target.position.labelResource())
                                        .lowercase(),
                                ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    weather.samples.forEach { sample ->
                        LiveHikeWeatherSampleRow(sample)
                    }
                    weather.message?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val fetchedAt = weather.samples.mapNotNull { sample -> sample.forecast?.fetchedAtEpochMillis }.maxOrNull()
                    fetchedAt?.let { updatedAt ->
                        Text(
                            text =
                                textResources.getString(
                                    R.string.mission_plan_updated_value,
                                    updatedAt.toLiveDateTimeText(),
                                ) +
                                    if (weather.samples.any(MissionDayWeatherSampleUi::isStale)) {
                                        textResources.getString(R.string.mission_plan_cached_unable_to_update)
                                    } else if (weather.samples.any(MissionDayWeatherSampleUi::isCached)) {
                                        textResources.getString(R.string.mission_plan_cached)
                                    } else {
                                        ""
                                    },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    onLoadWeather?.let { load ->
                        TextButton(onClick = { load(true) }) {
                            Text(textResources.getString(R.string.live_hike_refresh_planned_weather))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHikeWeatherSampleRow(sample: MissionDayWeatherSampleUi) {
    val textResources = LocalResources.current
    val scheduled = sample.scheduledOutlook
    val daily = sample.dailyOutlook
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text =
                "${textResources.getString(sample.target.position.labelResource()).uppercase()} • " +
                    "${sample.scheduledTimeIso8601?.toLivePlannedTimeText() ?: "time unavailable"} • " +
                    sample.target.location.label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        when {
            scheduled != null -> {
                Text(
                    text = weatherConditionText(textResources, scheduled.weatherCode),
                    style = MaterialTheme.typography.bodyMedium,
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
            }

            daily != null -> {
                Text(
                    text =
                        textResources.getString(
                            R.string.mission_profile_daily_outlook_value,
                            weatherConditionText(textResources, daily.weatherCode),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
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

private fun nearestWeatherSample(
    samples: List<MissionDayWeatherSampleUi>,
    snapshot: ActiveHikeSnapshot,
): MissionDayWeatherSampleUi? {
    val routeDistance = samples.maxOfOrNull { sample -> sample.target.distanceFromDayStartMeters } ?: return null
    val activeDistance = snapshot.progressFraction?.times(routeDistance) ?: return samples.firstOrNull()
    return samples.minByOrNull { sample -> abs(sample.target.distanceFromDayStartMeters - activeDistance) }
}

@Composable
fun LiveHikeDashboardWaitingScreen(
    syncEnabled: Boolean,
    onEnableSync: () -> Unit,
    onBack: () -> Unit,
) {
    val textResources = LocalResources.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = textResources.getString(R.string.common_back_to_home_content_description),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = textResources.getString(R.string.menu_live_hike),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text =
                        if (syncEnabled) {
                            textResources.getString(
                                R.string.live_hike_waiting_for_a_watch_update,
                            )
                        } else {
                            textResources.getString(R.string.live_hike_live_hike_sync_is_off)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        if (syncEnabled) {
                            textResources.getString(R.string.live_hike_progress_will_appear_here_as_soon_as)
                        } else {
                            textResources.getString(R.string.live_hike_turn_it_on_to_receive_watch_progress)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!syncEnabled) {
                    Button(onClick = onEnableSync) {
                        Text(textResources.getString(R.string.live_hike_turn_on_live_hike_sync))
                    }
                }
            }
        }

        if (syncEnabled) {
            Text(
                text = textResources.getString(R.string.live_hike_start_turn_by_turn_navigation_or_recording),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = textResources.getString(R.string.live_hike_if_this_stays_empty_install_a_watch),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RoutedHikeDashboard(snapshot: ActiveHikeSnapshot) {
    val textResources = LocalResources.current
    val completed = snapshot.distanceFromStartMeters
    val remaining = snapshot.distanceRemainingMeters
    val eta = snapshot.estimatedRemainingSeconds?.let { duration -> snapshot.recordedAtEpochMillis + duration * 1_000L }
    DashboardMetricRow(
        DashboardMetric(textResources.getString(R.string.live_hike_done), completed?.toLiveDistanceText() ?: "—"),
        DashboardMetric(textResources.getString(R.string.live_hike_left), remaining?.toLiveDistanceText() ?: "—"),
    )
    DashboardMetricRow(
        DashboardMetric(
            textResources.getString(R.string.live_hike_time_left),
            snapshot.estimatedRemainingSeconds?.toLiveDurationText() ?: "—",
        ),
        DashboardMetric(textResources.getString(R.string.map_route_analyzer_eta_header), eta?.toLiveTimeText() ?: "—"),
    )
    DashboardMetricRow(
        DashboardMetric(
            textResources.getString(R.string.live_hike_climb_left),
            snapshot.remainingAscentMeters?.toLiveElevationText() ?: "—",
        ),
        DashboardMetric(
            textResources.getString(R.string.live_hike_descent_left),
            snapshot.remainingDescentMeters?.toLiveElevationText() ?: "—",
        ),
    )
    snapshot.progressFraction?.let { progress ->
        Text(
            text = textResources.getString(R.string.live_hike_value_of_route_complete, (progress * 100).roundToInt()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RecordingDashboard(snapshot: ActiveHikeSnapshot) {
    val textResources = LocalResources.current
    DashboardMetricRow(
        DashboardMetric(
            textResources.getString(R.string.live_hike_distance),
            snapshot.distanceFromStartMeters?.toLiveDistanceText() ?: "—",
        ),
        DashboardMetric(
            textResources.getString(R.string.live_hike_active_time),
            snapshot.activeDurationSeconds?.toLiveDurationText() ?: "—",
        ),
    )
    DashboardMetricRow(
        DashboardMetric(
            textResources.getString(R.string.live_hike_speed),
            snapshot.currentSpeedMetersPerSecond?.toLiveSpeedText() ?: "—",
        ),
        DashboardMetric(
            textResources.getString(R.string.live_hike_altitude),
            snapshot.currentAltitudeMeters?.toLiveElevationText() ?: "—",
        ),
    )
    Text(
        text = textResources.getString(R.string.live_hike_no_planned_finish_is_set_for_this),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DashboardMetricRow(
    first: DashboardMetric,
    second: DashboardMetric,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DashboardMetricCard(first, Modifier.weight(1f))
        DashboardMetricCard(second, Modifier.weight(1f))
    }
}

@Composable
private fun DashboardMetricCard(
    metric: DashboardMetric,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = metric.label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = metric.value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private data class DashboardMetric(
    val label: String,
    val value: String,
)

private fun ActiveHikePhase.isRecording(): Boolean = this == ActiveHikePhase.RECORDING || this == ActiveHikePhase.RECORDING_PAUSED

@Composable
private fun ActiveHikeSnapshot.liveStatusText(): String {
    val textResources = LocalResources.current
    return when {
        offRoute -> textResources.getString(R.string.live_hike_off_route_check_the_watch)
        phase == ActiveHikePhase.RECORDING_PAUSED -> textResources.getString(R.string.live_hike_recording_paused)
        phase == ActiveHikePhase.RECORDING -> textResources.getString(R.string.live_hike_recording)
        phase == ActiveHikePhase.PAUSED -> textResources.getString(R.string.live_hike_navigation_paused)
        phase == ActiveHikePhase.FOLLOWING_ROUTE -> textResources.getString(R.string.live_hike_on_route)
        phase == ActiveHikePhase.TO_START -> textResources.getString(R.string.live_hike_heading_to_the_start)
        phase == ActiveHikePhase.WAITING_FOR_LOCATION -> textResources.getString(R.string.live_hike_waiting_for_gps)
        phase == ActiveHikePhase.FINISHED -> textResources.getString(R.string.live_hike_route_complete)
        phase == ActiveHikePhase.IDLE -> textResources.getString(R.string.live_hike_no_active_hike)
        else -> textResources.getString(R.string.live_hike_active_hike)
    }
}

private fun Double.toLiveDistanceText(): String = if (this < 1_000.0) "${roundToInt()} m" else "%.1f km".format(this / 1_000.0)

private fun Double.toLiveElevationText(): String = "${roundToInt()} m"

private fun Double.toLiveSpeedText(): String = "%.1f km/h".format(this * 3.6)

private fun Long.toLiveDurationText(): String {
    val totalMinutes = (this / 60L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours == 0L) "$minutes min" else "$hours h ${minutes.toString().padStart(2, '0')} min"
}

private fun Long.toLiveTimeText(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(this))

private fun Long.toLiveDateTimeText(): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))

private fun String.toLivePlannedTimeText(): String = substringAfter('T', missingDelimiterValue = this).take(5)
