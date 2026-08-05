@file:Suppress("FunctionNaming", "LongMethod", "MaxLineLength", "TooManyFunctions")

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun LiveHikeDashboardScreen(
    update: PhoneActiveHikeSnapshot,
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
