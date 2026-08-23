package com.glancemap.glancemapwearos.presentation.features.navigate

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardMetricTile
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingMetricValue
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingDashboardMetricTileHeight
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import java.util.Date
import kotlin.math.roundToInt

internal data class TurnByTurnMetricDefinition(
    val id: String,
    val label: String,
)

internal val turnByTurnMetricDefinitions =
    listOf(
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_CURRENT_ALTITUDE, "Current altitude"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_DISTANCE_COVERED, "Distance covered"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_ETA, "ETA"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_PROGRESS, "Progress"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_ASCENT, "Remaining ascent"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DESCENT, "Remaining descent"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DISTANCE, "Remaining distance"),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_TIME, "Remaining time"),
    )

internal val turnByTurnMetricPickerOptions =
    turnByTurnMetricDefinitions.sortedBy { it.label.lowercase() }.map { it.id to it.label }

internal fun turnByTurnMetricLabel(metricId: String): String = turnByTurnMetricDefinitions.firstOrNull { it.id == metricId }?.label ?: "Remaining distance"

@Composable
internal fun TurnByTurnMetricDashboardPage(
    state: TurnByTurnGuidanceState,
    slots: List<String>,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    onSlotLongPress: (Int) -> Unit,
) {
    val context = LocalContext.current
    TurnByTurnMetricDashboardGrid(
        metrics =
            listOf(
                formattedTurnByTurnMetric(slots[0], state, isMetric, context),
                formattedTurnByTurnMetric(slots[1], state, isMetric, context),
                formattedTurnByTurnMetric(slots[2], state, isMetric, context),
                formattedTurnByTurnMetric(slots[3], state, isMetric, context),
            ),
        header = "REMAINING",
        screenSize = screenSize,
        onSlotLongPress = onSlotLongPress,
    )
}

@Composable
internal fun TurnByTurnMetricDashboardGrid(
    metrics: List<RecordingMetricValue>,
    header: String?,
    screenSize: WearScreenSize,
    onSlotLongPress: (Int) -> Unit,
) {
    val tileHeight = recordingDashboardMetricTileHeight(screenSize) - 4.dp
    cappedFontScale(maxFontScale = 1f) {
        Column(
            modifier = Modifier.fillMaxWidth(0.70f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Box(modifier = Modifier.height(10.dp), contentAlignment = Alignment.Center) {
                header?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            RecordingDashboardMetricTile(
                metric = metrics[0],
                height = tileHeight,
                onLongPress = { onSlotLongPress(0) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RecordingDashboardMetricTile(
                    metric = metrics[1],
                    height = tileHeight,
                    onLongPress = { onSlotLongPress(1) },
                    modifier = Modifier.weight(1f),
                )
                RecordingDashboardMetricTile(
                    metric = metrics[2],
                    height = tileHeight,
                    onLongPress = { onSlotLongPress(2) },
                    modifier = Modifier.weight(1f),
                )
            }
            RecordingDashboardMetricTile(
                metric = metrics[3],
                height = tileHeight,
                onLongPress = { onSlotLongPress(3) },
                modifier = Modifier.fillMaxWidth(0.82f),
            )
        }
    }
}

private fun formattedTurnByTurnMetric(
    metricId: String,
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    context: android.content.Context,
): RecordingMetricValue =
    when (metricId) {
        SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_ASCENT ->
            elevationMetric("Ascent ↑", state.remainingAscentMeters, isMetric)
        SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DESCENT ->
            elevationMetric("Descent ↓", state.remainingDescentMeters, isMetric)
        SettingsRepository.TURN_BY_TURN_METRIC_ETA -> {
            val eta =
                state.estimatedRemainingSeconds?.let { seconds ->
                    DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + seconds * 1_000L))
                } ?: "--"
            RecordingMetricValue("ETA", eta)
        }
        SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_TIME ->
            RecordingMetricValue("Time", formatRemainingDuration(state.estimatedRemainingSeconds))
        SettingsRepository.TURN_BY_TURN_METRIC_PROGRESS ->
            RecordingMetricValue(
                "Progress",
                state.routeProgressFraction?.let { "${(it * 100).roundToInt()}%" } ?: "--",
            )
        SettingsRepository.TURN_BY_TURN_METRIC_DISTANCE_COVERED ->
            distanceMetric("Covered", state.distanceFromStartMeters, isMetric)
        SettingsRepository.TURN_BY_TURN_METRIC_CURRENT_ALTITUDE ->
            elevationMetric("Altitude", state.currentAltitudeMeters, isMetric)
        else -> {
            distanceMetric("Distance", state.distanceRemainingMeters, isMetric)
        }
    }

private fun distanceMetric(
    label: String,
    meters: Double?,
    isMetric: Boolean,
): RecordingMetricValue {
    val (value, unit) = meters?.let { UnitFormatter.formatDistance(it, isMetric) } ?: ("--" to null)
    return RecordingMetricValue(label, value, unit)
}

private fun elevationMetric(
    label: String,
    meters: Double?,
    isMetric: Boolean,
): RecordingMetricValue {
    val (value, unit) = meters?.let { UnitFormatter.formatElevation(it, isMetric) } ?: ("--" to null)
    return RecordingMetricValue(label, value, unit)
}

private fun formatRemainingDuration(seconds: Long?): String {
    val totalMinutes = seconds?.let { (it.coerceAtLeast(0L) + 30L) / 60L } ?: return "--"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
