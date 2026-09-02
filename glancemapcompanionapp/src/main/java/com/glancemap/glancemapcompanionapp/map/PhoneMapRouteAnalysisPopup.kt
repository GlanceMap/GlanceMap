@file:Suppress("FunctionNaming") // Compose components intentionally use PascalCase names.

package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.PhoneMapRouteAnalysisPopup(
    analysis: PhoneMapRouteAnalysis,
    isMetric: Boolean,
    selectingPointB: Boolean,
    onDismiss: () -> Unit,
    onSelectPointB: () -> Unit,
) {
    if (selectingPointB) {
        PhoneMapPopupCard(
            title = stringResource(R.string.map_route_analyzer_select_point_b),
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
        ) {
            Text(stringResource(R.string.map_route_analyzer_select_point_b_hint))
        }
        return
    }

    PhoneMapPopupCard(
        title =
            stringResource(R.string.map_route_analyzer_title) +
                " · " +
                analysis.displayName,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
    ) {
        val rows =
            if (analysis.pointB == null) {
                listOf(
                    stringResource(R.string.map_route_analyzer_start_to_a) to analysis.startToA,
                    stringResource(R.string.map_route_analyzer_a_to_end) to analysis.aToEnd,
                )
            } else {
                listOf(
                    stringResource(R.string.map_route_analyzer_start_to_a) to analysis.startToA,
                    stringResource(R.string.map_route_analyzer_a_to_b) to requireNotNull(analysis.aToB),
                    stringResource(R.string.map_route_analyzer_b_to_end) to requireNotNull(analysis.bToEnd),
                )
            }
        PhoneMapRouteAnalysisTable(rows = rows, isMetric = isMetric)
        if (analysis.pointB == null) {
            Button(
                onClick = onSelectPointB,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.map_route_analyzer_add_second_point))
            }
        }
    }
}

@Composable
private fun PhoneMapRouteAnalysisTable(
    rows: List<Pair<String, PhoneMapRouteAnalysisLeg>>,
    isMetric: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AnalysisCell(stringResource(R.string.map_route_analyzer_segment_header), 1.2f, true)
            AnalysisCell(stringResource(R.string.map_route_analyzer_distance_header), 1f, true)
            AnalysisCell(stringResource(R.string.map_route_analyzer_ascent_header), 0.8f, true)
            AnalysisCell(stringResource(R.string.map_route_analyzer_descent_header), 0.8f, true)
            AnalysisCell(stringResource(R.string.map_route_analyzer_eta_header), 0.9f, true)
        }
        rows.forEach { (label, leg) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                AnalysisCell(label, 1.2f)
                AnalysisCell(formatPhoneMapMeasuredDistance(leg.distanceMeters, isMetric), 1f)
                AnalysisCell(formatRouteAnalysisElevation(leg.ascentMeters, isMetric), 0.8f)
                AnalysisCell(formatRouteAnalysisElevation(leg.descentMeters, isMetric), 0.8f)
                AnalysisCell(formatRouteAnalysisDuration(leg.durationSeconds), 0.9f)
            }
        }
    }
}

@Composable
private fun RowScope.AnalysisCell(
    text: String,
    weight: Float,
    header: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(end = 4.dp),
        fontWeight = if (header) FontWeight.Bold else null,
    )
}

private fun formatRouteAnalysisElevation(
    meters: Double,
    isMetric: Boolean,
): String =
    if (isMetric) {
        "${meters.roundToInt()} m"
    } else {
        "${(meters * 3.28084).roundToInt()} ft"
    }

private fun formatRouteAnalysisDuration(seconds: Double): String {
    if (!seconds.isFinite()) return "—"
    val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) {
        "$minutes min"
    } else {
        "$hours h ${minutes.toString().padStart(2, '0')} min"
    }
}
