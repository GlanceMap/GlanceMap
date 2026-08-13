@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale

@Composable
internal fun ExpandedRecordingDashboard(
    state: TraceRecordingUiState,
    slots: List<String>,
    pageIndex: Int,
    pageCount: Int,
    snapshot: RecordingDashboardSnapshot,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    onSlotLongPress: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onShowActions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val contentWidthFraction =
        when (screenSize) {
            WearScreenSize.LARGE -> 0.72f
            WearScreenSize.MEDIUM -> 0.68f
            WearScreenSize.SMALL -> 0.64f
        }
    val tileHeight = recordingDashboardMetricTileHeight(screenSize)
    val statusRowHeight =
        when (screenSize) {
            WearScreenSize.LARGE -> 14.dp
            WearScreenSize.MEDIUM -> 12.dp
            WearScreenSize.SMALL -> 10.dp
        }

    RecordingFullscreenPageShell(
        pageIndex = pageIndex,
        pageCount = pageCount,
        dragKey = state.active to state.paused,
        recordingActive = state.active,
        recordingPaused = state.paused,
        recordingSaving = state.saving,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        onShowActions = onShowActions,
        onDismiss = onDismiss,
    ) {
        cappedFontScale(maxFontScale = 1f) {
            Column(
                modifier = Modifier.fillMaxWidth(contentWidthFraction),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(modifier = Modifier.height(statusRowHeight))
                RecordingDashboardMetricTile(
                    metric = formattedRecordingMetric(slots[0], snapshot, isMetric),
                    height = tileHeight,
                    onLongPress = { onSlotLongPress(0) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RecordingDashboardMetricTile(
                        metric = formattedRecordingMetric(slots[1], snapshot, isMetric),
                        height = tileHeight,
                        onLongPress = { onSlotLongPress(1) },
                        modifier = Modifier.weight(1f),
                    )
                    RecordingDashboardMetricTile(
                        metric = formattedRecordingMetric(slots[2], snapshot, isMetric),
                        height = tileHeight,
                        onLongPress = { onSlotLongPress(2) },
                        modifier = Modifier.weight(1f),
                    )
                }
                RecordingDashboardMetricTile(
                    metric = formattedRecordingMetric(slots[3], snapshot, isMetric),
                    height = tileHeight,
                    onLongPress = { onSlotLongPress(3) },
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
            }
        }
    }
}
