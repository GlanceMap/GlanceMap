package com.glancemap.glancemapcompanionapp.map

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.abs

@Composable
internal fun mapToolsOnlineMapSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    var sourceOrder by remember(settings.onlineSourceOrder) {
        mutableStateOf(normalizePhoneOnlineSourceOrder(settings.onlineSourceOrder))
    }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val latestSettings by rememberUpdatedState(settings)
    val latestOnSettingsChanged by rememberUpdatedState(onSettingsChanged)
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_maps_settings_online_summary))
        // Use the full configured catalog so hidden providers can always be enabled again.
        PhoneMapRendererCatalog.availableOnlineSources(sourceOrder = sourceOrder).forEach { source ->
            key(source) {
                val checked = source !in settings.hiddenOnlineSources
                val label = PhoneMapRendererCatalog.onlineSourceLabel(source)
                ListItem(
                    headlineContent = { Text(label, maxLines = 1) },
                    leadingContent = {
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .testTag("online-map-reorder-${source.name}")
                                    .pointerInput(source) {
                                        var dragDistancePx = 0f
                                        var didMove = false
                                        val finishDrag = {
                                            if (didMove) {
                                                latestOnSettingsChanged(
                                                    latestSettings.copy(onlineSourceOrder = sourceOrder),
                                                )
                                            }
                                            didMove = false
                                        }
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragDistancePx = 0f
                                                didMove = false
                                            },
                                            onDragEnd = finishDrag,
                                            onDragCancel = finishDrag,
                                            onDrag = { change, amount ->
                                                change.consume()
                                                if (rowHeightPx > 0) {
                                                    dragDistancePx += amount.y
                                                    while (abs(dragDistancePx) >= rowHeightPx) {
                                                        val visibleSources =
                                                            PhoneMapRendererCatalog.availableOnlineSources(
                                                                sourceOrder = sourceOrder,
                                                            )
                                                        val currentIndex = visibleSources.indexOf(source)
                                                        val direction = if (dragDistancePx > 0) 1 else -1
                                                        val targetIndex = currentIndex + direction
                                                        val reordered =
                                                            if (targetIndex in visibleSources.indices) {
                                                                reorderPhoneOnlineSourceOrder(
                                                                    sourceOrder,
                                                                    visibleSources,
                                                                    source,
                                                                    targetIndex,
                                                                )
                                                            } else {
                                                                sourceOrder
                                                            }
                                                        if (reordered == sourceOrder) {
                                                            dragDistancePx = 0f
                                                            break
                                                        }
                                                        sourceOrder = reordered
                                                        dragDistancePx -= direction * rowHeightPx
                                                        didMove = true
                                                    }
                                                }
                                            },
                                        )
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.map_tools_maps_reorder_source, label),
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            phoneMap3dCapabilityIcon()
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { rowHeightPx = it.height }
                            .toggleable(
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
