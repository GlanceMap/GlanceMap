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
import androidx.compose.ui.graphics.vector.ImageVector
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
    var terrainExpanded by rememberSaveable { mutableStateOf(false) }
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
            Text(
                stringResource(R.string.map_tools_maps_quick_display_heading),
                style = MaterialTheme.typography.titleSmall,
            )
            layerQuickDisplay(
                state,
                actions.onMapSettingsChanged,
            )
            if (state.base is PhoneMapSource.Offline || state.comparison.layer is PhoneMapComparisonLayer.Offline) {
                layerSection(
                    label = stringResource(R.string.map_layer_themes),
                    expanded = themesExpanded,
                    onClick = { themesExpanded = !themesExpanded },
                )
                if (themesExpanded) layerThemeControls(state, actions)
            }
            layerSection(
                label = stringResource(R.string.map_layer_terrain),
                expanded = terrainExpanded,
                onClick = { terrainExpanded = !terrainExpanded },
            )
            if (terrainExpanded) layerTerrain(state, actions.onMapSettingsChanged)
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
    val allMaps =
        state.onlineSources.map(PhoneMapComparisonLayer::Online) +
            state.offlineMaps.map(PhoneMapComparisonLayer::Offline)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            layerPicker(
                label = stringResource(R.string.map_layer_base_source),
                selected = comparisonLayerLabel(base),
                options = allMaps.map { it to comparisonLayerLabel(it) },
                onSelect = actions.onBaseMapChanged,
                tag = "layer-base-picker",
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
        IconButton(onClick = actions.onSwap, enabled = state.comparison.layer != null) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.map_layer_swap))
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
    val clearLabel = stringResource(R.string.map_layer_clear)
    val sameMapThemeLabel = stringResource(R.string.map_layer_same_map_theme)
    val comparisonOptions =
        buildList<Pair<PhoneMapComparisonLayer?, String>> {
            sameMapThemeOption?.let { add(it to sameMapThemeLabel) }
            add(null to clearLabel)
            addAll(options.map { it to comparisonLayerLabel(it) })
        }
    layerPicker<PhoneMapComparisonLayer?>(
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
        optionIcon = { if (it == null) Icons.Filled.Close else null },
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
    optionIcon: ((T) -> ImageVector?)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
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
                val icon = optionIcon?.invoke(value)
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon =
                        if (icon != null) {
                            { Icon(icon, contentDescription = null) }
                        } else {
                            null
                        },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
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
