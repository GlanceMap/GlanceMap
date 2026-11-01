package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun phoneRecordingLiveStatisticsGrid(
    recording: PhoneRecordingUiState,
    settings: PhoneGeneralSettings,
    nowMillis: Long,
) {
    var track by remember(recording.session.generation) { mutableStateOf(RecordedActivityStatistics()) }
    val requests =
        remember(recording.session.generation) {
            Channel<Pair<PhoneRecordingUiState, PhoneGeneralSettings>>(Channel.CONFLATED)
        }
    val calculationLock = remember { Mutex() }
    DisposableEffect(requests) {
        onDispose { requests.close() }
    }
    LaunchedEffect(recording.session.generation, recording.pointsRevision, recording.activityProfile, settings) {
        requests.trySend(recording to settings)
    }
    LaunchedEffect(requests) {
        for ((snapshot, currentSettings) in requests) {
            track =
                withContext(Dispatchers.Default) {
                    calculationLock.withLock { phoneRecordingTrackStatistics(snapshot, currentSettings) }
                }
        }
    }
    val steps by PhoneRecordingSessionStore.steps.collectAsState()
    val stepCount = steps.count.takeIf { steps.generation == recording.session.generation }
    val statistics = phoneRecordingLiveStatistics(recording, track, nowMillis, stepCount)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val metrics =
        remember(statistics, settings.isMetric, configuration) {
            phoneActivityMetrics(context, statistics, settings.isMetric).filterNot {
                it.label in
                    setOf(R.string.activity_average_power, R.string.activity_max_power, R.string.activity_max_cadence)
            }
        }
    Column(
        Modifier.fillMaxWidth().testTag("recording-live-statistics"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        metrics.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { metric ->
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("recording-metric-${metric.label}"),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(metric.label), style = MaterialTheme.typography.labelSmall)
                            Text(metric.value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
