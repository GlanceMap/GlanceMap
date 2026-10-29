package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
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
    @StringRes val labelRes: Int,
)

internal val turnByTurnMetricDefinitions =
    listOf(
        TurnByTurnMetricDefinition(
            SettingsRepository.TURN_BY_TURN_METRIC_CURRENT_ALTITUDE,
            R.string.navigation_current_altitude,
        ),
        TurnByTurnMetricDefinition(
            SettingsRepository.TURN_BY_TURN_METRIC_DISTANCE_COVERED,
            R.string.navigation_distance_covered,
        ),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_ETA, R.string.navigation_eta),
        TurnByTurnMetricDefinition(SettingsRepository.TURN_BY_TURN_METRIC_PROGRESS, R.string.navigation_progress),
        TurnByTurnMetricDefinition(
            SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_ASCENT,
            R.string.navigation_remaining_ascent,
        ),
        TurnByTurnMetricDefinition(
            SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DESCENT,
            R.string.navigation_remaining_descent,
        ),
        TurnByTurnMetricDefinition(
            SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DISTANCE,
            R.string.navigation_remaining_distance,
        ),
        TurnByTurnMetricDefinition(
            SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_TIME,
            R.string.navigation_remaining_time,
        ),
    )

internal val turnByTurnMetricPickerOptions =
    turnByTurnMetricDefinitions.map { it.id to it.labelRes }

internal fun turnByTurnMetricLabel(
    metricId: String,
    resources: Resources,
): String =
    resources.getString(
        turnByTurnMetricDefinitions.firstOrNull { it.id == metricId }?.labelRes
            ?: R.string.navigation_remaining_distance,
    )

@Composable
internal fun TurnByTurnMetricDashboardPage(
    state: TurnByTurnGuidanceState,
    slots: List<String>,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    onSlotLongPress: (Int) -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    TurnByTurnMetricDashboardGrid(
        metrics =
            listOf(
                formattedTurnByTurnMetric(slots[0], state, isMetric, context, resources = resources),
                formattedTurnByTurnMetric(slots[1], state, isMetric, context, resources = resources),
                formattedTurnByTurnMetric(slots[2], state, isMetric, context, resources = resources),
                formattedTurnByTurnMetric(slots[3], state, isMetric, context, resources = resources),
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
    resources: Resources,
): RecordingMetricValue =
    when (metricId) {
        SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_ASCENT ->
            elevationMetric(R.string.navigation_ascent, state.remainingAscentMeters, isMetric)
        SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DESCENT ->
            elevationMetric(R.string.navigation_descent, state.remainingDescentMeters, isMetric)
        SettingsRepository.TURN_BY_TURN_METRIC_ETA -> {
            val eta =
                state.estimatedRemainingSeconds?.let { seconds ->
                    DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + seconds * 1_000L))
                } ?: "--"
            RecordingMetricValue(R.string.navigation_eta, eta)
        }
        SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_TIME ->
            RecordingMetricValue(
                R.string.common_time,
                formatRemainingDuration(state.estimatedRemainingSeconds, resources = resources),
            )
        SettingsRepository.TURN_BY_TURN_METRIC_PROGRESS ->
            RecordingMetricValue(
                R.string.navigation_progress,
                state.routeProgressFraction?.let { "${(it * 100).roundToInt()}%" } ?: "--",
            )
        SettingsRepository.TURN_BY_TURN_METRIC_DISTANCE_COVERED ->
            distanceMetric(R.string.navigation_covered, state.distanceFromStartMeters, isMetric)
        SettingsRepository.TURN_BY_TURN_METRIC_CURRENT_ALTITUDE ->
            elevationMetric(R.string.recording_altitude, state.currentAltitudeMeters, isMetric)
        else -> {
            distanceMetric(R.string.common_distance, state.distanceRemainingMeters, isMetric)
        }
    }

private fun distanceMetric(
    @StringRes label: Int,
    meters: Double?,
    isMetric: Boolean,
): RecordingMetricValue {
    val (value, unit) = meters?.let { UnitFormatter.formatDistance(it, isMetric) } ?: ("--" to null)
    return RecordingMetricValue(label, value, unit)
}

private fun elevationMetric(
    @StringRes label: Int,
    meters: Double?,
    isMetric: Boolean,
): RecordingMetricValue {
    val (value, unit) = meters?.let { UnitFormatter.formatElevation(it, isMetric) } ?: ("--" to null)
    return RecordingMetricValue(label, value, unit)
}

private fun formatRemainingDuration(
    seconds: Long?,
    resources: Resources,
): String {
    val totalMinutes = seconds?.let { (it.coerceAtLeast(0L) + 30L) / 60L } ?: return "--"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours >
        0
    ) {
        resources.getString(R.string.navigation_h_m, hours, minutes)
    } else {
        resources.getString(R.string.navigation_m, minutes)
    }
}
