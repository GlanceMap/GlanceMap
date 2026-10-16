@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.profile.windowBetweenDistances
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // Draft stops, selection, Undo, and preview share state until Apply succeeds.
@Composable
internal fun MissionStageEditor(
    source: MissionRouteStageSource,
    focusDayId: String?,
    enabled: Boolean,
    onApply: (List<String>, List<Double>) -> Unit,
) {
    val hikes = source.days.filterNot { it.isRestDay }
    val groupId = source.days.firstOrNull { it.id == focusDayId }?.stageGroupId
    var selectedIds by rememberSaveable(source.route.route.id, focusDayId) {
        mutableStateOf(hikes.filter { groupId == null || it.stageGroupId == groupId }.map { it.id })
    }
    val selected = hikes.filter { it.id in selectedIds }
    val profile = source.route.profile
    val initial =
        remember(source, selectedIds) {
            val existingGroup = selected.firstOrNull()?.stageGroupId
            val linked =
                existingGroup != null &&
                    selected.all {
                        it.stageGroupId == existingGroup && it.routeId == source.route.route.id
                    } &&
                    source.days.count { it.stageGroupId == existingGroup } == selected.size
            if (linked) {
                selected.dropLast(1).map { requireNotNull(it.endDistanceMeters) }
            } else {
                runCatching { profile.suggestStageStops(selected.size) }.getOrNull()
            }
        }
    var history by rememberSaveable(source.route.route.id, selectedIds) { mutableStateOf(listOfNotNull(initial)) }
    val stops = history.lastOrNull()
    var stopIndex by rememberSaveable(source.route.route.id, selectedIds) {
        mutableStateOf(selected.indexOfFirst { it.id == focusDayId }.coerceIn(0, (selected.size - 2).coerceAtLeast(0)))
    }
    var showDays by rememberSaveable { mutableStateOf(false) }
    val start = hikes.indexOfFirst { it.id in selectedIds }
    val consecutive = selected.isNotEmpty() && hikes.drop(start).take(selected.size) == selected
    TextButton(onClick = { showDays = !showDays }, enabled = enabled) {
        Text(stringResource(R.string.stage_selected_days, selected.size))
    }
    if (showDays) {
        source.days.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = day.id in selectedIds,
                    onCheckedChange = { checked ->
                        selectedIds =
                            hikes
                                .filter {
                                    (
                                        it.id in selectedIds && it.id != day.id
                                    ) ||
                                        (checked && it.id == day.id)
                                }.map { it.id }
                    },
                    enabled = enabled && !day.isRestDay,
                )
                Text(
                    stringResource(
                        R.string.stage_day_date,
                        day.dayNumber,
                        day.plannedDate?.missionPlanDateLabel().orEmpty(),
                    ) +
                        if (day.isRestDay) " · " + stringResource(R.string.adventure_day_off) else "",
                )
            }
        }
    }
    if (!consecutive || stops == null) {
        Text(stringResource(R.string.stage_days_invalid), color = MaterialTheme.colorScheme.error)
    } else {
        val boundaries = listOf(0.0) + stops + profile.totalDistanceMeters
        if (stops.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stops.indices.forEach { index ->
                    FilterChip(
                        selected = stopIndex == index,
                        onClick = { stopIndex = index },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.stage_arrival_day, selected[index].dayNumber)) },
                    )
                }
            }
            Text(stringResource(R.string.stage_move_hint), style = MaterialTheme.typography.bodySmall)
        }
        val moveStop: (Double) -> Unit = { distance ->
            if (enabled && stops.isNotEmpty()) {
                val candidates =
                    profile.cumulativeDistanceMeters.filter {
                        it > boundaries[stopIndex] && it < boundaries[stopIndex + 2]
                    }
                val snapped = candidates.minByOrNull { abs(it - distance) }
                if (snapped != null && snapped != stops[stopIndex]) {
                    val updated = stops.toMutableList().also { it[stopIndex] = snapped }
                    history = (history + listOf(updated)).takeLast(30)
                }
            }
        }
        MissionStageGraphs(source.route, stops, stopIndex, enabled && stops.isNotEmpty(), moveStop)
        if (stops.isNotEmpty()) {
            StageStopSlider(stops[stopIndex], boundaries[stopIndex], boundaries[stopIndex + 2], enabled, moveStop)
            TextButton(onClick = { history = history.dropLast(1) }, enabled = enabled && history.size > 1) {
                Text(stringResource(R.string.stage_undo))
            }
        }
        selected.forEachIndexed { index, day ->
            val window = profile.windowBetweenDistances(boundaries[index], boundaries[index + 1])
            Text(
                stringResource(
                    R.string.stage_day_metrics,
                    day.dayNumber,
                    window.distanceMeters.toMissionPlanDistance(),
                    window.ascentMeters.roundToInt(),
                    window.estimatedDurationSeconds.toMissionPlanDuration(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (day.needsStayReview(source.route.route.id, boundaries[index + 1], profile.totalDistanceMeters)) {
                Text(stringResource(R.string.stage_review_stay), color = MaterialTheme.colorScheme.error)
            }
        }
        Text(stringResource(R.string.stage_apply_hint), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onApply(selected.map { it.id }, stops) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.stage_apply))
        }
    }
}

@Composable
private fun StageStopSlider(
    distance: Double,
    previous: Double,
    next: Double,
    enabled: Boolean,
    onMove: (Double) -> Unit,
) {
    var draft by remember(distance, previous, next) { mutableStateOf(distance.toFloat()) }
    Text(stringResource(R.string.stage_stop_distance, draft.toDouble().toMissionPlanDistance()))
    val description = stringResource(R.string.stage_stop_distance, draft.toDouble().toMissionPlanDistance())
    Slider(
        modifier = Modifier.semantics { contentDescription = description },
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = {
            onMove(draft.toDouble())
            draft = distance.toFloat()
        },
        valueRange = previous.toFloat()..next.toFloat(),
        enabled = enabled,
    )
}

@Composable
internal fun MissionStopReviewWarning(day: MissionPlanDay) {
    if (day.stopReviewRequired) {
        Text(
            stringResource(R.string.stage_review_stay),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
