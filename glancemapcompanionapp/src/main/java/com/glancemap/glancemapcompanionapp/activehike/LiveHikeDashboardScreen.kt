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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.routes.LiveHikeWeatherContext
import com.glancemap.glancemapcompanionapp.routes.MissionDayWeatherSampleUi
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
                    contentDescription = "Back to home",
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (recording) "Live Recording" else "Live Hike",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = snapshot.routeTitle ?: "Watch session",
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
                    text = "Watch update ${snapshot.recordedAtEpochMillis.toLiveTimeText()}",
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "WEATHER ALONG THE HIKE",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            when {
                weather == null -> {
                    Text(
                        text = "No selected Mission Plan day matches this watch route.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weather.plannedDate == null -> {
                    Text(
                        text = "Add a date to ${weather.dayName} in Mission Plan before loading its forecast.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weather.plannedStartTime == null -> {
                    Text(
                        text = "Add a planned start time in Mission Plan to align weather with the GPX locations.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    onLoadWeather?.let { load ->
                        TextButton(onClick = { load(false) }) {
                            Text("Load daily forecast")
                        }
                    }
                }

                weather.isLoading -> {
                    Text(
                        text = "Loading planned weather…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weather.samples.isEmpty() -> {
                    Text(
                        text = "Prepare weather for ${weather.plannedDate} • start ${weather.plannedStartTime} before you lose signal.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    onLoadWeather?.let { load ->
                        TextButton(onClick = { load(false) }) {
                            Text("Load planned weather")
                        }
                    }
                }

                else -> {
                    Text(
                        text = "${weather.plannedDate} • planned start ${weather.plannedStartTime}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    nearestWeatherSample(weather.samples, snapshot)?.let { sample ->
                        Text(
                            text = "Nearest planned point: ${sample.target.position.label.lowercase()}",
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
                                "Updated ${updatedAt.toLiveDateTimeText()}" +
                                    if (weather.samples.any(MissionDayWeatherSampleUi::isStale)) {
                                        " • cached, unable to update"
                                    } else if (weather.samples.any(MissionDayWeatherSampleUi::isCached)) {
                                        " • cached"
                                    } else {
                                        ""
                                    },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    onLoadWeather?.let { load ->
                        TextButton(onClick = { load(true) }) {
                            Text("Refresh planned weather")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHikeWeatherSampleRow(sample: MissionDayWeatherSampleUi) {
    val scheduled = sample.scheduledOutlook
    val daily = sample.dailyOutlook
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text =
                "${sample.target.position.label.uppercase()} • " +
                    "${sample.scheduledTimeIso8601?.toLivePlannedTimeText() ?: "time unavailable"} • " +
                    sample.target.location.label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        when {
            scheduled != null -> {
                Text(
                    text = weatherConditionText(scheduled.weatherCode),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
            }

            daily != null -> {
                Text(
                    text = "Daily outlook • ${weatherConditionText(daily.weatherCode)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                Text(
                    text = sample.message ?: "Forecast unavailable at this GPX location.",
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
fun LiveHikeDashboardWaitingScreen(onBack: () -> Unit) {
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
                    contentDescription = "Back to home",
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Live Hike",
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
                    text = "Waiting for a watch update",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Progress will appear here as soon as the watch sends an active hike snapshot.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            text = "Start turn-by-turn navigation or recording on the watch, then keep the companion open while it connects.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "If this stays empty, install a watch build that supports Live Hike sync. The companion never starts or estimates a watch hike on its own.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RoutedHikeDashboard(snapshot: ActiveHikeSnapshot) {
    val completed = snapshot.distanceFromStartMeters
    val remaining = snapshot.distanceRemainingMeters
    val eta = snapshot.estimatedRemainingSeconds?.let { duration -> snapshot.recordedAtEpochMillis + duration * 1_000L }
    DashboardMetricRow(
        DashboardMetric("DONE", completed?.toLiveDistanceText() ?: "—"),
        DashboardMetric("LEFT", remaining?.toLiveDistanceText() ?: "—"),
    )
    DashboardMetricRow(
        DashboardMetric("TIME LEFT", snapshot.estimatedRemainingSeconds?.toLiveDurationText() ?: "—"),
        DashboardMetric("ETA", eta?.toLiveTimeText() ?: "—"),
    )
    DashboardMetricRow(
        DashboardMetric("CLIMB LEFT", snapshot.remainingAscentMeters?.toLiveElevationText() ?: "—"),
        DashboardMetric("DESCENT LEFT", snapshot.remainingDescentMeters?.toLiveElevationText() ?: "—"),
    )
    snapshot.progressFraction?.let { progress ->
        Text(
            text = "${(progress * 100).roundToInt()}% of route complete",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RecordingDashboard(snapshot: ActiveHikeSnapshot) {
    DashboardMetricRow(
        DashboardMetric("DISTANCE", snapshot.distanceFromStartMeters?.toLiveDistanceText() ?: "—"),
        DashboardMetric("ACTIVE TIME", snapshot.activeDurationSeconds?.toLiveDurationText() ?: "—"),
    )
    DashboardMetricRow(
        DashboardMetric("SPEED", snapshot.currentSpeedMetersPerSecond?.toLiveSpeedText() ?: "—"),
        DashboardMetric("ALTITUDE", snapshot.currentAltitudeMeters?.toLiveElevationText() ?: "—"),
    )
    Text(
        text = "No planned finish is set for this recording.",
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

private fun ActiveHikeSnapshot.liveStatusText(): String =
    when {
        offRoute -> "Off route — check the watch"
        phase == ActiveHikePhase.RECORDING_PAUSED -> "Recording paused"
        phase == ActiveHikePhase.RECORDING -> "Recording"
        phase == ActiveHikePhase.PAUSED -> "Navigation paused"
        phase == ActiveHikePhase.FOLLOWING_ROUTE -> "On route"
        phase == ActiveHikePhase.TO_START -> "Heading to the start"
        phase == ActiveHikePhase.WAITING_FOR_LOCATION -> "Waiting for GPS"
        phase == ActiveHikePhase.FINISHED -> "Route complete"
        phase == ActiveHikePhase.IDLE -> "No active hike"
        else -> "Active hike"
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
