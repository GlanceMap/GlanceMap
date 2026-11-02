package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics

@Composable
internal fun phoneActivityStatisticsGrid(
    statistics: RecordedActivityStatistics,
    isMetric: Boolean,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val metrics = remember(statistics, isMetric, configuration) { phoneActivityMetrics(context, statistics, isMetric) }
    Column(Modifier.fillMaxWidth().testTag("activity-statistics"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { metric ->
                    Surface(
                        modifier = Modifier.weight(1f).testTag("activity-metric-${metric.label}"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(metric.label), style = MaterialTheme.typography.labelMedium)
                            Text(metric.value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}
