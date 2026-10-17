package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun layerQuickDisplay(
    state: MapToolsLayerState,
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        layerChip(
            R.string.map_layer_quick_measure,
            R.string.map_tools_maps_distance_measurement,
            settings.distanceMeasurementEnabled,
            { onSettings(settings.copy(distanceMeasurementEnabled = it)) },
            Modifier.weight(1f),
        )
        layerChip(
            R.string.map_layer_quick_shading,
            R.string.map_tools_maps_hill_shading,
            settings.hillShadingEnabled,
            { onSettings(settings.copy(hillShadingEnabled = it)) },
            Modifier.weight(1f),
        )
    }
    if (state.base is PhoneMapSource.Offline) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            layerChip(
                R.string.map_layer_quick_night,
                R.string.map_tools_maps_night_mode,
                settings.nightModeEnabled,
                { onSettings(settings.copy(nightModeEnabled = it)) },
                Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
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
) {
    val accessibilityLabel = stringResource(description)
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
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

@Composable
internal fun layerTerrain(
    state: MapToolsLayerState,
    onSettings: (PhoneMapSettings) -> Unit,
) {
    val available = state.base is PhoneMapSource.Offline && state.hasElevationData
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_layer_slope_title)) },
        supportingContent = {
            Text(
                stringResource(
                    if (available) R.string.map_layer_slope_available else R.string.map_layer_slope_unavailable,
                ),
            )
        },
        trailingContent = {
            Switch(
                available && state.mapSettings.reliefOverlayEnabled,
                { onSettings(state.mapSettings.copy(reliefOverlayEnabled = it)) },
                enabled = available,
            )
        },
    )
    if (available && state.mapSettings.reliefOverlayEnabled) {
        Text(stringResource(R.string.map_layer_slope_transparency, 100 - state.mapSettings.reliefOverlayOpacityPercent))
        var opacity by remember(state.mapSettings.reliefOverlayOpacityPercent) {
            mutableStateOf(state.mapSettings.reliefOverlayOpacityPercent.toFloat())
        }
        Slider(opacity, { opacity = it }, onValueChangeFinished = {
            onSettings(state.mapSettings.copy(reliefOverlayOpacityPercent = opacity.toInt()))
        }, valueRange = 0f..100f, steps = 99)
    }
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
