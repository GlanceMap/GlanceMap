package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun layerQuickDisplay(
    state: MapToolsLayerState,
    onTerrainPreview: (() -> Unit)? = null,
    onSettings: (PhoneMapSettings) -> Unit,
) {
    val settings = state.mapSettings
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        layerChip(
            R.string.map_layer_quick_elevation,
            R.string.map_tools_maps_live_elevation,
            settings.liveElevationEnabled,
            { onSettings(settings.copy(liveElevationEnabled = it)) },
            Modifier.weight(1f),
        )
        layerChip(
            R.string.map_layer_quick_distance,
            R.string.map_tools_maps_live_distance,
            settings.liveDistanceEnabled,
            { onSettings(settings.copy(liveDistanceEnabled = it)) },
            Modifier.weight(1f),
        )
    }
    val tiltAvailable = phoneMapSupportsTilt(state.base, state.comparison.layer)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        layerChip(
            R.string.map_layer_quick_measure,
            R.string.map_tools_maps_distance_measurement,
            settings.distanceMeasurementEnabled,
            { onSettings(settings.copy(distanceMeasurementEnabled = it)) },
            Modifier.weight(1f),
        )
        layerChip(
            R.string.map_layer_quick_tilt,
            R.string.map_tools_maps_tilted_view,
            tiltAvailable && settings.tiltedViewEnabled,
            { onSettings(settings.copy(tiltedViewEnabled = it)) },
            Modifier.weight(1f),
            enabled = tiltAvailable,
        )
    }
    terrainPreviewLaunchButton(onTerrainPreview)
    if (state.base is PhoneMapSource.Offline) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val slopeReliefAvailable = state.hasElevationData
            layerChip(
                R.string.map_layer_slope_title,
                R.string.map_layer_slope_title,
                slopeReliefAvailable && settings.reliefOverlayEnabled,
                { onSettings(settings.copy(reliefOverlayEnabled = it)) },
                Modifier.weight(1f),
                enabled = slopeReliefAvailable,
            )
            layerChip(
                R.string.map_layer_quick_night,
                R.string.map_tools_maps_night_mode,
                settings.nightModeEnabled,
                { onSettings(settings.copy(nightModeEnabled = it)) },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun layerChip(
    label: Int,
    description: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accessibilityLabel = stringResource(description)
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        enabled = enabled,
        label = { Text(stringResource(label), maxLines = 1) },
        leadingIcon =
            if (checked) {
                {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            } else {
                null
            },
        modifier = modifier.semantics { contentDescription = accessibilityLabel },
    )
}

internal fun comparisonLayerOptions(
    base: PhoneMapSource,
    baseOnlineSource: PhoneOnlineMapSource,
    offlineMaps: List<PhoneOfflineMap>,
    onlineSources: List<PhoneOnlineMapSource>,
): List<PhoneMapComparisonLayer> =
    onlineSources
        .filterNot { base == PhoneMapSource.Online && it == baseOnlineSource }
        .map(PhoneMapComparisonLayer::Online) + offlineMaps.map(PhoneMapComparisonLayer::Offline)

@Composable
internal fun comparisonLayerLabel(layer: PhoneMapComparisonLayer): String =
    when (layer) {
        is PhoneMapComparisonLayer.Online -> PhoneMapRendererCatalog.onlineSourceLabel(layer.source)
        is PhoneMapComparisonLayer.Offline -> layer.map.displayName
    }

internal fun PhoneMapSource.asComparisonLayer(onlineSource: PhoneOnlineMapSource): PhoneMapComparisonLayer =
    when (this) {
        PhoneMapSource.Online -> PhoneMapComparisonLayer.Online(onlineSource)
        is PhoneMapSource.Offline -> PhoneMapComparisonLayer.Offline(map)
    }

internal fun PhoneOfflineThemeConfig.summary(): String {
    val theme = PhoneOfflineThemeCatalog.themeFor(themeId)
    val style =
        theme.styles
            .firstOrNull { it.id == styleId }
            ?.label
            .orEmpty()
    return "${theme.label} · $style"
}
