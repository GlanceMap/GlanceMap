package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun mapToolsLayerPanel(
    state: MapToolsLayerState,
    actions: MapToolsLayerActions,
) {
    var themesExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (state.comparison.layer != null) {
            layerComparisonMix(state.comparison, actions.onTransparencyChanged)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            layerMapSelectors(state, actions, onSameMapSelected = { themesExpanded = true })
            if (state.base is PhoneMapSource.Offline || state.comparison.layer is PhoneMapComparisonLayer.Offline) {
                layerSection(
                    label = stringResource(R.string.map_layer_themes),
                    expanded = themesExpanded,
                    onClick = { themesExpanded = !themesExpanded },
                )
                if (themesExpanded) layerThemeControls(state, actions)
            }
            Text(
                stringResource(R.string.map_tools_maps_quick_display_heading),
                style = MaterialTheme.typography.titleSmall,
            )
            layerQuickDisplay(
                state = state,
                onTerrainPreview = actions.onOpenTerrainPreview,
                onSettings = actions.onMapSettingsChanged,
            )
        }
    }
}

@Composable
private fun layerComparisonMix(
    comparison: PhoneMapComparisonState,
    onTransparencyChanged: (Float) -> Unit,
) {
    val description = stringResource(R.string.map_layer_mix)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("layer-comparison-mix"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onTransparencyChanged(100f) },
            modifier = Modifier.testTag("layer-show-base"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.map_layer_base_source), style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = 100f - comparison.transparencyPercent,
            onValueChange = { onTransparencyChanged(100f - it) },
            valueRange = 0f..100f,
            steps = 99,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
        OutlinedButton(
            onClick = { onTransparencyChanged(0f) },
            modifier = Modifier.testTag("layer-show-comparison"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.map_layer_comparison), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun layerMapSelectors(
    state: MapToolsLayerState,
    actions: MapToolsLayerActions,
    onSameMapSelected: () -> Unit,
) {
    val base = state.base.asComparisonLayer(state.baseOnlineSource)
    val sourceGroups =
        mapPickerGroups(
            onlineOptions = state.onlineSources.map(PhoneMapComparisonLayer::Online),
            offlineOptions = state.offlineMaps.map(PhoneMapComparisonLayer::Offline),
            onlineLabel = stringResource(R.string.map_layer_group_online),
            offlineLabel = stringResource(R.string.map_layer_group_offline),
        )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            layerPicker(
                label = stringResource(R.string.map_layer_base_source),
                selected = comparisonLayerLabel(base),
                options = emptyList(),
                onSelect = actions.onBaseMapChanged,
                tag = "layer-base-picker",
                groups = sourceGroups,
            )
            if (base is PhoneMapComparisonLayer.Offline) {
                Text(
                    state.baseThemeConfig.summary(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = actions.onSwap, enabled = state.comparison.layer != null) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.map_layer_swap))
            }
            if (state.comparison.layer != null) {
                IconButton(
                    onClick = { actions.onLayerChanged(null) },
                    modifier = Modifier.testTag("layer-clear-comparison"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_layer_clear))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            layerComparisonPicker(state, actions, onSameMapSelected)
            if (state.comparison.layer is PhoneMapComparisonLayer.Offline) {
                Text(
                    state.comparisonThemeConfig.summary(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun mapPickerGroups(
    onlineOptions: List<PhoneMapComparisonLayer>,
    offlineOptions: List<PhoneMapComparisonLayer>,
    onlineLabel: String,
    offlineLabel: String,
): List<Pair<String, List<Pair<PhoneMapComparisonLayer, String>>>> =
    buildList {
        if (onlineOptions.isNotEmpty()) {
            add(onlineLabel to onlineOptions.map { it to comparisonLayerLabel(it) })
        }
        if (offlineOptions.isNotEmpty()) {
            add(offlineLabel to offlineOptions.map { it to comparisonLayerLabel(it) })
        }
    }

@Composable
private fun layerComparisonPicker(
    state: MapToolsLayerState,
    actions: MapToolsLayerActions,
    onSameMapSelected: () -> Unit,
) {
    val base = state.base.asComparisonLayer(state.baseOnlineSource)
    val sameMapThemeOption = base as? PhoneMapComparisonLayer.Offline
    val options =
        comparisonLayerOptions(state.base, state.baseOnlineSource, state.offlineMaps, state.onlineSources)
            .filterNot { it == sameMapThemeOption }
    val onlineOptions = options.filterIsInstance<PhoneMapComparisonLayer.Online>()
    val offlineOptions = options.filterIsInstance<PhoneMapComparisonLayer.Offline>()
    val sameMapThemeLabel = stringResource(R.string.map_layer_same_map_theme)
    val comparisonOptions = sameMapThemeOption?.let { listOf(it to sameMapThemeLabel) }.orEmpty()
    layerPicker<PhoneMapComparisonLayer>(
        label = stringResource(R.string.map_layer_comparison),
        selected = state.comparison.layer?.let { comparisonLayerLabel(it) } ?: stringResource(R.string.map_layer_none),
        options = comparisonOptions,
        onSelect = { layer ->
            if (layer is PhoneMapComparisonLayer.Offline && layer == base) {
                onSameMapSelected()
                if (state.baseThemeConfig == state.comparisonThemeConfig) {
                    val alternative = PhoneOfflineThemeCatalog.themes.first { it.id != state.baseThemeConfig.themeId }
                    actions.onComparisonThemeChanged(PhoneOfflineThemeCatalog.resolve(alternative.id, null))
                }
            }
            actions.onLayerChanged(layer)
        },
        tag = "layer-comparison-picker",
        groups =
            mapPickerGroups(
                onlineOptions = onlineOptions,
                offlineOptions = offlineOptions,
                onlineLabel = stringResource(R.string.map_layer_group_online),
                offlineLabel = stringResource(R.string.map_layer_group_offline),
            ),
    )
}

@Composable
private fun layerThemeControls(
    state: MapToolsLayerState,
    actions: MapToolsLayerActions,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.base is PhoneMapSource.Offline) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                layerThemePicker(state.baseThemeConfig, actions.onBaseThemeChanged, "base")
            }
        }
        if (state.comparison.layer is PhoneMapComparisonLayer.Offline) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                layerThemePicker(state.comparisonThemeConfig, actions.onComparisonThemeChanged, "comparison")
            }
        }
    }
}

@Composable
private fun <T> layerPicker(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    tag: String,
    groups: List<Pair<String, List<Pair<T, String>>>> = emptyList(),
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = {
            expanded = true
        }, modifier = Modifier.fillMaxWidth().testTag(tag), contentPadding = PaddingValues(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    trailingIcon = {
                        if (value is PhoneMapComparisonLayer.Online) phoneMap3dCapabilityIcon()
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
            groups.forEach { (groupLabel, groupOptions) ->
                DropdownMenuItem(
                    text = { Text(groupLabel) },
                    trailingIcon = {
                        Icon(
                            if (expandedGroup == groupLabel) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expandedGroup = if (expandedGroup == groupLabel) null else groupLabel
                    },
                )
                if (expandedGroup == groupLabel) {
                    groupOptions.forEach { (value, title) ->
                        DropdownMenuItem(
                            modifier = Modifier.padding(start = 12.dp),
                            text = { Text(title) },
                            onClick = {
                                expanded = false
                                onSelect(value)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun layerThemePicker(
    config: PhoneOfflineThemeConfig,
    onChange: (PhoneOfflineThemeConfig) -> Unit,
    side: String,
) {
    val theme = PhoneOfflineThemeCatalog.themeFor(config.themeId)
    Text(stringResource(if (side == "base") R.string.map_layer_base_source else R.string.map_layer_comparison))
    layerPicker(
        stringResource(R.string.map_theme_selector_theme_label),
        theme.label,
        PhoneOfflineThemeCatalog.themes.map { it.id to it.label },
        { onChange(PhoneOfflineThemeCatalog.resolve(it, null)) },
        "layer-$side-theme",
    )
    layerPicker(
        stringResource(R.string.map_theme_selector_style_label),
        theme.styles
            .firstOrNull { it.id == config.styleId }
            ?.label
            .orEmpty(),
        theme.styles.map { it.id to it.label },
        { onChange(PhoneOfflineThemeCatalog.resolve(theme.id, it)) },
        "layer-$side-style",
    )
    phoneOfflineThemeOverlayControls(config, side, onChange)
}

@Composable
private fun layerSection(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    HorizontalDivider()
    TextButton(onClick, Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
}
