package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.glancemap.glancemapwearos.core.routing.HikeRouteProfileParams
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.routetools.routeStyleSettingsOptionsForActivityProfile
import com.glancemap.glancemapwearos.presentation.features.routetools.routeStyleTitleForSettingsValue

@Composable
fun GpxToolsSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGpxSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val routeStyle by viewModel.gpxToolRouteStyle.collectAsState()
    val useElevation by viewModel.gpxToolUseElevation.collectAsState()
    val allowFerries by viewModel.gpxToolAllowFerries.collectAsState()
    val customHikeParams = collectCustomHikeParams(viewModel)
    var advancedHikeExpanded by remember { mutableStateOf(false) }
    val isBikeProfile = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GpxSettingsShortcutChip(
                onClick = onOpenGpxSettings,
            )
        }

        item {
            SettingsOptionPickerRow(
                label = "Route type",
                selectedValue = routeStyle,
                options = routeStyleSettingsOptionsForActivityProfile(activityProfile),
                onSelect = viewModel::setGpxToolRouteStyle,
                secondaryLabel = routeStyleTitleForSettingsValue(routeStyle),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        gpxRouteToggles(
            state =
                GpxRouteToggleState(
                    useElevation = useElevation,
                    allowFerries = allowFerries,
                    isBikeProfile = isBikeProfile,
                    preferForestPaths = customHikeParams.considerForest,
                ),
            actions =
                GpxRouteToggleActions(
                    onUseElevationChanged = viewModel::setGpxToolUseElevation,
                    onAllowFerriesChanged = viewModel::setGpxToolAllowFerries,
                    onPreferForestPathsChanged = viewModel::setGpxToolHikeConsiderForest,
                ),
        )

        if (!isBikeProfile) {
            hikeAdvancedRoutingSection(
                routeStyle = routeStyle,
                customHikeParams = customHikeParams,
                expanded = advancedHikeExpanded,
                onToggle = { advancedHikeExpanded = !advancedHikeExpanded },
                onCustomHikeParamsChanged = { params ->
                    viewModel.setGpxToolCustomHikeProfile(
                        hikingRoutesPreference = params.hikingRoutesPreference,
                        pathPreference = params.pathPreference,
                        sacScaleLimit = params.sacScaleLimit,
                        sacScalePreferred = params.sacScalePreferred,
                        considerForest = params.considerForest,
                    )
                },
            )
        }
    }
}

@Composable
private fun collectCustomHikeParams(viewModel: SettingsViewModel): HikeRouteProfileParams {
    val customHikingRoutesPreference by viewModel.gpxToolHikeHikingRoutesPreference.collectAsState()
    val customPathPreference by viewModel.gpxToolHikePathPreference.collectAsState()
    val customSacScaleLimit by viewModel.gpxToolHikeSacScaleLimit.collectAsState()
    val customSacScalePreferred by viewModel.gpxToolHikeSacScalePreferred.collectAsState()
    val customConsiderForest by viewModel.gpxToolHikeConsiderForest.collectAsState()
    return HikeRouteProfileParams(
        hikingRoutesPreference = customHikingRoutesPreference,
        pathPreference = customPathPreference,
        sacScaleLimit = customSacScaleLimit,
        sacScalePreferred = customSacScalePreferred,
        considerForest = customConsiderForest,
    )
}

private data class GpxRouteToggleState(
    val useElevation: Boolean,
    val allowFerries: Boolean,
    val isBikeProfile: Boolean,
    val preferForestPaths: Boolean,
)

private data class GpxRouteToggleActions(
    val onUseElevationChanged: (Boolean) -> Unit,
    val onAllowFerriesChanged: (Boolean) -> Unit,
    val onPreferForestPathsChanged: (Boolean) -> Unit,
)

private fun ScalingLazyListScope.gpxRouteToggles(
    state: GpxRouteToggleState,
    actions: GpxRouteToggleActions,
) {
    item {
        SettingsToggleChip(
            checked = state.useElevation,
            onCheckedChanged = actions.onUseElevationChanged,
            label = "Flatter routes",
            secondaryLabel =
                if (state.useElevation) {
                    "Prioritize flatter routes"
                } else {
                    "Use standard routing"
                },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (!state.isBikeProfile) {
        item {
            SettingsToggleChip(
                checked = state.preferForestPaths,
                onCheckedChanged = actions.onPreferForestPathsChanged,
                label = "Prefer forest paths",
                secondaryLabel = if (state.preferForestPaths) "On" else "Off",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    item {
        SettingsToggleChip(
            checked = state.allowFerries,
            onCheckedChanged = actions.onAllowFerriesChanged,
            label = "Allow ferries",
            secondaryLabel = if (state.allowFerries) "Ferry routes allowed" else "Avoid ferries",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ScalingLazyListScope.hikeAdvancedRoutingSection(
    routeStyle: String,
    customHikeParams: HikeRouteProfileParams,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCustomHikeParamsChanged: (HikeRouteProfileParams) -> Unit,
) {
    item {
        SettingsPickerChip(
            label = if (expanded) "▾ Advanced hike routing" else "▸ Advanced hike routing",
            secondaryLabel = "Edit hike route profile",
            iconImageVector = null,
            onClick = onToggle,
        )
    }

    if (expanded) {
        editableHikeAdvancedRows(
            params = effectiveHikeParams(routeStyle, customHikeParams),
            onParamsChanged = onCustomHikeParamsChanged,
        )
    }
}

private fun ScalingLazyListScope.editableHikeAdvancedRows(
    params: HikeRouteProfileParams,
    onParamsChanged: (HikeRouteProfileParams) -> Unit,
) {
    item {
        SettingsOptionPickerRow(
            label = "Marked hiking routes",
            selectedValue = params.hikingRoutesPreference,
            options = hikingRoutePreferenceOptions,
            onSelect = { onParamsChanged(params.copy(hikingRoutesPreference = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        SettingsOptionPickerRow(
            label = "Path preference",
            selectedValue = params.pathPreference,
            options = pathPreferenceOptions,
            onSelect = { onParamsChanged(params.copy(pathPreference = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        SettingsOptionPickerRow(
            label = "Max difficulty",
            selectedValue = params.sacScaleLimit,
            options = sacScaleOptions,
            onSelect = { onParamsChanged(params.copy(sacScaleLimit = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        SettingsOptionPickerRow(
            label = "Preferred difficulty",
            selectedValue = params.sacScalePreferred,
            options = sacScaleOptions,
            onSelect = { onParamsChanged(params.copy(sacScalePreferred = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun effectiveHikeParams(
    routeStyle: String,
    customHikeParams: HikeRouteProfileParams,
): HikeRouteProfileParams =
    when (routeStyle) {
        SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE -> customHikeParams
        SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_TRAILS ->
            HikeRouteProfileParams(
                hikingRoutesPreference = 0.60f,
                pathPreference = 20f,
                sacScaleLimit = 3,
                sacScalePreferred = 2,
                considerForest = customHikeParams.considerForest,
            )

        SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_EASIEST ->
            HikeRouteProfileParams(
                hikingRoutesPreference = 0f,
                pathPreference = 0f,
                sacScaleLimit = 1,
                sacScalePreferred = 1,
                considerForest = customHikeParams.considerForest,
            )

        else ->
            HikeRouteProfileParams(
                hikingRoutesPreference = 0.20f,
                pathPreference = 0f,
                sacScaleLimit = 3,
                sacScalePreferred = 1,
                considerForest = customHikeParams.considerForest,
            )
    }

private val hikingRoutePreferenceOptions =
    listOf(
        0f to "Off",
        0.20f to "Moderate",
        0.60f to "High",
        1f to "Very high",
    )

private val pathPreferenceOptions =
    listOf(
        0f to "Neutral",
        10f to "Prefer paths",
        20f to "Strong",
    )

private val sacScaleOptions =
    listOf(
        1 to "T1 easy",
        2 to "T2 mountain",
        3 to "T3 mountain path",
        4 to "T4 alpine",
    )

@Composable
private fun GpxSettingsShortcutChip(
    onClick: () -> Unit,
) {
    AdaptiveSettingsShortcutChip(
        standardLabel = "GPX settings",
        compactLabel = "GPX",
        standardSecondaryLabel = "Back to GPX settings",
        compactSecondaryLabel = "Back",
        iconImageVector = Icons.Filled.Folder,
        applyTopPadding = true,
        compactRoundWidthFraction = 0.78f,
        onClick = onClick,
    )
}
