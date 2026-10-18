package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import java.util.Locale

@Composable
internal fun phoneOfflineThemeOverlayControls(
    config: PhoneOfflineThemeConfig,
    side: String,
    onChange: (PhoneOfflineThemeConfig) -> Unit,
) {
    val context = LocalContext.current
    val overlays =
        remember(context, config.themeId, config.styleId) {
            PhoneOfflineThemeOverlayCatalog.overlaysFor(config, context)
        }
    if (overlays.isEmpty()) return

    val defaultIds =
        remember(overlays) {
            PhoneOfflineThemeOverlayCatalog.idsForPreset(overlays, PhoneOfflineThemeOverlayPreset.DEFAULT)
        }
    val enabledIds = config.enabledOverlayLayerIds ?: defaultIds
    val expandedGroups = remember(config.themeId, config.styleId, side) { mutableStateMapOf<String, Boolean>() }
    val groups = remember(overlays) { phoneOfflineThemeOverlayGroups(overlays) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.map_theme_overlays_heading),
            style = MaterialTheme.typography.labelSmall,
        )
        phoneOfflineThemePresetButtons(overlays, config, defaultIds, onChange)
        groups.forEach { (id, items) ->
            phoneOfflineThemeOverlayGroup(
                group =
                    PhoneOfflineThemeOverlayGroupState(
                        id = id,
                        items = items,
                        enabledIds = enabledIds,
                        expanded = expandedGroups[id] == true,
                    ),
                side = side,
                onExpandedChange = { expandedGroups[id] = it },
                onOverlayChange = { overlayId, enabled ->
                    val changedIds = enabledIds.toMutableSet().apply {
                        if (enabled) add(overlayId) else remove(overlayId)
                    }
                    onChange(config.copy(enabledOverlayLayerIds = changedIds))
                },
            )
        }
    }
}

@Composable
private fun phoneOfflineThemePresetButtons(
    overlays: List<PhoneOfflineThemeOverlay>,
    config: PhoneOfflineThemeConfig,
    defaultIds: Set<String>,
    onChange: (PhoneOfflineThemeConfig) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        phoneOfflineThemePresetButton(R.string.map_theme_overlay_default) {
            onChange(config.copy(enabledOverlayLayerIds = defaultIds))
        }
        phoneOfflineThemePresetButton(R.string.map_theme_overlay_minimal) {
            onChange(
                config.copy(
                    enabledOverlayLayerIds =
                            PhoneOfflineThemeOverlayCatalog.idsForPreset(
                                overlays,
                                PhoneOfflineThemeOverlayPreset.MINIMAL,
                            ),
                ),
            )
        }
        phoneOfflineThemePresetButton(R.string.map_theme_overlay_all) {
            onChange(
                config.copy(
                    enabledOverlayLayerIds =
                            PhoneOfflineThemeOverlayCatalog.idsForPreset(
                                overlays,
                                PhoneOfflineThemeOverlayPreset.ALL_ON,
                            ),
                ),
            )
        }
    }
}

private data class PhoneOfflineThemeOverlayGroupState(
    val id: String,
    val items: List<PhoneOfflineThemeOverlay>,
    val enabledIds: Set<String>,
    val expanded: Boolean,
)

@Composable
private fun phoneOfflineThemeOverlayGroup(
    group: PhoneOfflineThemeOverlayGroupState,
    side: String,
    onExpandedChange: (Boolean) -> Unit,
    onOverlayChange: (String, Boolean) -> Unit,
) {
    val enabledCount = group.items.count { it.id in group.enabledIds }
    TextButton(
        onClick = { onExpandedChange(!group.expanded) },
        modifier = Modifier.fillMaxWidth().testTag("offline-theme-overlay-group-$side-${group.id}"),
    ) {
        Text(
            stringResource(
                when (group.id) {
                    "winter" -> R.string.map_theme_overlay_group_winter
                    "routes" -> R.string.map_theme_overlay_group_routes
                    "poi" -> R.string.map_theme_overlay_group_poi
                    else -> R.string.map_theme_overlay_group_map
                },
            ),
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.map_theme_overlay_count, enabledCount, group.items.size))
        Icon(
            if (group.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
        )
    }
    if (group.expanded) {
        group.items.forEach { overlay ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    overlay.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Checkbox(
                    checked = overlay.id in group.enabledIds,
                    onCheckedChange = { enabled -> onOverlayChange(overlay.id, enabled) },
                    modifier = Modifier.testTag("offline-theme-overlay-$side-${overlay.id}"),
                )
            }
        }
    }
}

private fun phoneOfflineThemeOverlayGroups(
    overlays: List<PhoneOfflineThemeOverlay>,
): List<Pair<String, List<PhoneOfflineThemeOverlay>>> =
    listOf("winter", "routes", "poi", "map").mapNotNull { id ->
        val items = overlays.filter { PhoneOfflineThemeOverlayCatalog.groupIdForOverlay(it.id) == id }
        if (items.isEmpty()) null else id to items.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

@Composable
private fun RowScope.phoneOfflineThemePresetButton(
    labelResource: Int,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
    ) {
        Text(stringResource(labelResource), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
