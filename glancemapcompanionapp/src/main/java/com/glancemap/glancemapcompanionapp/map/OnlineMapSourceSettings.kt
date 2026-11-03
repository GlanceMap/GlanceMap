package com.glancemap.glancemapcompanionapp.map

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

/** Matches the panel column spacing, so a dragged row moves by one full row pitch. */
private val onlineSourceRowSpacing = 12.dp

@Composable
@Suppress("LongMethod") // One list keeps the drag state, row visuals and persistence together.
internal fun mapToolsOnlineMapSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    var sourceOrder by remember(settings.onlineSourceOrder) {
        mutableStateOf(normalizePhoneOnlineSourceOrder(settings.onlineSourceOrder))
    }
    var rowPitchPx by remember { mutableIntStateOf(0) }
    var draggedSource by remember { mutableStateOf<PhoneOnlineMapSource?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val latestSettings by rememberUpdatedState(settings)
    val latestOnSettingsChanged by rememberUpdatedState(onSettingsChanged)
    val haptics = LocalHapticFeedback.current
    val rowSpacingPx = with(LocalDensity.current) { onlineSourceRowSpacing.roundToPx() }
    // Use the full configured catalog so hidden providers can always be enabled again.
    val sources = PhoneMapRendererCatalog.availableOnlineSources(sourceOrder = sourceOrder)
    val draggedIndex = sources.indexOf(draggedSource)
    val targetIndex = phoneOnlineSourceDragTargetIndex(draggedIndex, dragOffsetPx, rowPitchPx, sources.size)
    val finishDrag = {
        val source = draggedSource
        val visibleSources = PhoneMapRendererCatalog.availableOnlineSources(sourceOrder = sourceOrder)
        val fromIndex = visibleSources.indexOf(source)
        val dropIndex = phoneOnlineSourceDragTargetIndex(fromIndex, dragOffsetPx, rowPitchPx, visibleSources.size)
        if (source != null && dropIndex != fromIndex) {
            sourceOrder = reorderPhoneOnlineSourceOrder(sourceOrder, visibleSources, source, dropIndex)
            latestOnSettingsChanged(latestSettings.copy(onlineSourceOrder = sourceOrder))
        }
        draggedSource = null
        dragOffsetPx = 0f
    }
    // Row gestures outlive recompositions; the drop must see the current order state.
    val latestFinishDrag by rememberUpdatedState(finishDrag)
    mapToolPanelColumn(verticalSpacing = onlineSourceRowSpacing) {
        Text(stringResource(R.string.map_tools_maps_settings_online_summary))
        sources.forEachIndexed { index, source ->
            key(source) {
                val checked = source !in settings.hiddenOnlineSources
                val label = PhoneMapRendererCatalog.onlineSourceLabel(source)
                val dragged = source == draggedSource
                // Rows slide aside while another row is dragged, then snap into the saved order on drop.
                val shiftPx by animateFloatAsState(
                    targetValue = phoneOnlineSourceDragShift(index, draggedIndex, targetIndex) * rowPitchPx.toFloat(),
                    animationSpec = if (draggedIndex < 0) snap() else spring(),
                    label = "onlineSourceShift",
                )
                ListItem(
                    headlineContent = { Text(label, maxLines = 1) },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(48.dp).testTag("online-map-reorder-${source.name}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.map_tools_maps_reorder_source, label),
                            )
                        }
                    },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            phoneMap3dCapabilityIcon()
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    },
                    colors =
                        if (dragged) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ListItemDefaults.colors()
                        },
                    shadowElevation = if (dragged) 8.dp else 0.dp,
                    modifier =
                        Modifier
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragged) dragOffsetPx else shiftPx
                                val scale = if (dragged) 1.03f else 1f
                                scaleX = scale
                                scaleY = scale
                            }.fillMaxWidth()
                            .onSizeChanged { rowPitchPx = it.height + rowSpacingPx }
                            .pointerInput(source) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggedSource = source
                                        dragOffsetPx = 0f
                                    },
                                    onDragEnd = { latestFinishDrag() },
                                    onDragCancel = { latestFinishDrag() },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetPx += amount.y
                                    },
                                )
                            }.toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { enabled ->
                                    onSettingsChanged(
                                        settings.copy(
                                            hiddenOnlineSources =
                                                if (enabled) {
                                                    settings.hiddenOnlineSources - source
                                                } else {
                                                    settings.hiddenOnlineSources + source
                                                },
                                        ),
                                    )
                                },
                            ),
                )
            }
        }
    }
}

/** The slot a dragged row would drop into; [fromIndex] stays put until the row passes half a row. */
internal fun phoneOnlineSourceDragTargetIndex(
    fromIndex: Int,
    dragOffsetPx: Float,
    rowPitchPx: Int,
    count: Int,
): Int =
    if (fromIndex < 0 || rowPitchPx <= 0) {
        fromIndex
    } else {
        (fromIndex + (dragOffsetPx / rowPitchPx).roundToInt()).coerceIn(0, count - 1)
    }

/** Rows between the dragged row and its target slide one row toward the dragged row's old slot. */
internal fun phoneOnlineSourceDragShift(
    index: Int,
    fromIndex: Int,
    targetIndex: Int,
): Int =
    when {
        fromIndex < 0 || index == fromIndex -> 0
        fromIndex < targetIndex && index in (fromIndex + 1)..targetIndex -> -1
        targetIndex < fromIndex && index in targetIndex until fromIndex -> 1
        else -> 0
    }
