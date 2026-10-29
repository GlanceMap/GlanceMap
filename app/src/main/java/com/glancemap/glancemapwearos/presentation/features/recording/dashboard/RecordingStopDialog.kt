package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.buildRecordingTitle
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.DeleteConfirmationDialog
import com.glancemap.glancemapwearos.presentation.ui.RenameValueDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton

@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun RecordingStopPromptCard(
    state: TraceRecordingUiState,
    snapshot: RecordingDashboardSnapshot?,
    isMetric: Boolean,
    visible: Boolean,
    onDiscard: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val metrics =
        remember(snapshot, isMetric) {
            snapshot?.let { recordingRecapMetricsForSnapshot(it, isMetric) }.orEmpty()
        }
    val defaultTitle =
        remember(state.startedAtMillis) {
            val stoppedAtMillis = System.currentTimeMillis()
            buildRecordingTitle(
                startedAtMillis = state.startedAtMillis ?: stoppedAtMillis,
                endedAtMillis = stoppedAtMillis,
            )
        }
    var draftTitle by remember(defaultTitle) { mutableStateOf(defaultTitle) }
    val shortRecording = snapshot?.let { isShortRecording(it, state) } == true
    var showRenameDialog by remember(defaultTitle) { mutableStateOf(false) }
    var showDiscardConfirmation by remember(defaultTitle) { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameValueDialog(
            visible = visible,
            title = stringResource(R.string.recording_rename_activity),
            initialValue = draftTitle,
            isSaving = false,
            error = null,
            autoFocusInput = false,
            onDismiss = { showRenameDialog = false },
            onConfirm = { title ->
                val sanitizedTitle = title.trim().take(MAX_RECORDING_TITLE_LENGTH)
                draftTitle = sanitizedTitle.ifBlank { defaultTitle }
                showRenameDialog = false
            },
        )
    }

    DeleteConfirmationDialog(
        visible = visible && showDiscardConfirmation,
        title = stringResource(R.string.recording_discard_recording),
        message = stringResource(R.string.recording_this_recording_will_be_permanently_deleted_and_cannot_be_recovered),
        confirmText = stringResource(R.string.recording_discard),
        onConfirm = {
            showDiscardConfirmation = false
            onDiscard()
        },
        onDismiss = { showDiscardConfirmation = false },
    )

    WearActionDialog(
        visible = visible && !showDiscardConfirmation && snapshot != null,
        title =
            if (shortRecording) {
                stringResource(
                    R.string.recording_short_recording,
                )
            } else {
                stringResource(R.string.recording_save_recording)
            },
        onDismissRequest = onCancel,
        backgroundColor = Color.Black,
        buttons =
            listOf(
                WearActionDialogButton(
                    text = stringResource(R.string.common_save),
                    onClick = { onSave(draftTitle.ifBlank { defaultTitle }) },
                ),
                WearActionDialogButton(
                    text = stringResource(R.string.recording_discard),
                    onClick = {
                        showRenameDialog = false
                        showDiscardConfirmation = true
                    },
                    role = WearActionButtonRole.Destructive,
                ),
                WearActionDialogButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    role = WearActionButtonRole.Secondary,
                ),
            ),
    ) {
        RecordingDialogTitleRow(
            title = draftTitle,
            onRename = { showRenameDialog = true },
        )

        RecordingRecapMetricsGrid(metrics = metrics)
    }
}

internal data class RecordingRecapMetric(
    @StringRes val labelRes: Int,
    val valueText: String,
)

@Composable
private fun RecordingDialogTitleRow(
    title: String,
    onRename: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title.ifBlank { stringResource(R.string.common_recording) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        CompactIconHitTargetButton(
            onClick = onRename,
            visualSize = 34.dp,
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.recording_rename_activity),
            )
        }
    }
}

@Composable
internal fun RecordingRecapMetricsGrid(metrics: List<RecordingRecapMetric>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        metrics.chunked(RECORDING_RECAP_COLUMNS).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowMetrics.forEach { metric ->
                    RecordingDialogStatTile(
                        modifier = Modifier.weight(1f),
                        metric = metric,
                    )
                }
                repeat(RECORDING_RECAP_COLUMNS - rowMetrics.size) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun RecordingDialogStatTile(
    modifier: Modifier,
    metric: RecordingRecapMetric,
) {
    Column(
        modifier =
            modifier
                .background(
                    Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = stringResource(metric.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = metric.valueText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun recordingRecapMetric(
    @StringRes label: Int,
    value: String,
    unit: String? = null,
): RecordingRecapMetric =
    RecordingRecapMetric(
        labelRes = label,
        valueText = "$value${unit?.let { " $it" }.orEmpty()}".trim(),
    )

private fun RecordingMetricValue.toRecordingRecapMetric(): RecordingRecapMetric = recordingRecapMetric(label = labelRes, value = value, unit = unit)

internal fun recordingRecapMetricsForSnapshot(
    snapshot: RecordingDashboardSnapshot,
    isMetric: Boolean,
): List<RecordingRecapMetric> {
    val metrics =
        listOf(
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_DISTANCE, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_TOTAL_TIME, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_DURATION, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_ELEVATION_GAIN, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_ELEVATION_LOSS, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_AVERAGE_SPEED, snapshot, isMetric)
                .toRecordingRecapMetric(),
            speedMetricValue(R.string.recording_max_speed, snapshot.fastestSpeedMps, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_AVERAGE_PACE, snapshot, isMetric)
                .toRecordingRecapMetric(),
            paceMetricValue(R.string.recording_max_pace, snapshot.fastestSpeedMps, isMetric)
                .toRecordingRecapMetric(),
            integerRecapMetric(R.string.gpx_hr_avg, snapshot.averageHeartRateBpm ?: snapshot.heartRateBpm, "bpm"),
            integerRecapMetric(R.string.gpx_max_hr, snapshot.maxHeartRateBpm ?: snapshot.heartRateBpm, "bpm"),
            integerRecapMetric(R.string.recording_power_avg, snapshot.averagePowerWatts ?: snapshot.powerWatts, "W"),
            integerRecapMetric(R.string.recording_max_power, snapshot.maxPowerWatts ?: snapshot.powerWatts, "W"),
            integerRecapMetric(
                R.string.recording_cadence_avg,
                snapshot.averageCadenceSpm ?: snapshot.cadenceSpm,
                "spm",
            ),
            integerRecapMetric(R.string.recording_max_cad, snapshot.maxCadenceSpm ?: snapshot.cadenceSpm, "spm"),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_STEPS, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_CALORIES, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_ACTIVE_CALORIES, snapshot, isMetric)
                .toRecordingRecapMetric(),
            formattedRecordingMetric(SettingsRepository.RECORDING_METRIC_RESTING_CALORIES, snapshot, isMetric)
                .toRecordingRecapMetric(),
        )
    return metrics.orderedForActivityProfile(snapshot.activityProfile)
}

private fun List<RecordingRecapMetric>.orderedForActivityProfile(activityProfile: String): List<RecordingRecapMetric> =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        inLabelOrder(
            R.string.common_distance,
            R.string.recording_cal_total,
            R.string.recording_cal_active,
            R.string.recording_cal_rest,
            R.string.recording_time_active,
            R.string.recording_time_total,
            R.string.recording_elev,
            R.string.recording_elev_2,
            R.string.gpx_hr_avg,
            R.string.gpx_max_hr,
            R.string.recording_speed_avg,
            R.string.recording_max_speed,
            R.string.recording_power_avg,
            R.string.recording_max_power,
        )
    } else {
        moveLabelAfter(
            label = R.string.settings_steps,
            afterLabel = R.string.common_distance,
        )
    }

private fun integerRecapMetric(
    @StringRes label: Int,
    value: Int?,
    unit: String,
): RecordingRecapMetric =
    recordingRecapMetric(
        label = label,
        value = value?.takeIf { it >= 0 }?.toString() ?: "--",
        unit = unit,
    )

private fun isShortRecording(
    snapshot: RecordingDashboardSnapshot,
    state: TraceRecordingUiState,
): Boolean =
    state.points.size < MIN_SAVE_POINT_COUNT ||
        snapshot.distanceMeters < SHORT_RECORDING_DISTANCE_METERS ||
        snapshot.durationSeconds < SHORT_RECORDING_DURATION_SECONDS

private const val MIN_SAVE_POINT_COUNT = 2
private const val SHORT_RECORDING_DISTANCE_METERS = 20.0
private const val SHORT_RECORDING_DURATION_SECONDS = 10.0
private const val MAX_RECORDING_TITLE_LENGTH = 64
private const val RECORDING_RECAP_COLUMNS = 2
