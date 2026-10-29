package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.HikeRouteProfileParams
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.routetools.routeStyleSettingsOptionsForActivityProfile
import com.glancemap.glancemapwearos.presentation.features.routetools.routeStyleTitleForSettingsValue

@Composable
// Resource-call wrapping increases line count; keep the existing flow together.
@Suppress("LongMethod", "FunctionNaming")
fun GpxToolsSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGpxSettings: () -> Unit,
) {
    val resources = LocalResources.current
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
                label = stringResource(R.string.gpx_route_type),
                selectedValue = routeStyle,
                options =
                    routeStyleSettingsOptionsForActivityProfile(activityProfile).map { (value, labelRes) ->
                        value to
                            stringResource(labelRes)
                    },
                onSelect = viewModel::setGpxToolRouteStyle,
                secondaryLabel = stringResource(routeStyleTitleForSettingsValue(routeStyle)),
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
            resources = resources,
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
                resources = resources,
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
    resources: Resources,
) {
    item {
        SettingsToggleChip(
            checked = state.useElevation,
            onCheckedChanged = actions.onUseElevationChanged,
            label = resources.getString(R.string.settings_flatter_routes),
            secondaryLabel =
                if (state.useElevation) {
                    resources.getString(R.string.settings_prioritize_flatter_routes)
                } else {
                    resources.getString(R.string.settings_use_standard_routing)
                },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (!state.isBikeProfile) {
        item {
            SettingsToggleChip(
                checked = state.preferForestPaths,
                onCheckedChanged = actions.onPreferForestPathsChanged,
                label = resources.getString(R.string.settings_prefer_forest_paths),
                secondaryLabel =
                    if (state.preferForestPaths) {
                        resources.getString(
                            R.string.glancemap_state_on,
                        )
                    } else {
                        resources.getString(R.string.glancemap_state_off)
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    item {
        SettingsToggleChip(
            checked = state.allowFerries,
            onCheckedChanged = actions.onAllowFerriesChanged,
            label = resources.getString(R.string.settings_allow_ferries),
            secondaryLabel =
                if (state.allowFerries) {
                    resources.getString(
                        R.string.settings_ferry_routes_allowed,
                    )
                } else {
                    resources.getString(R.string.settings_avoid_ferries)
                },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
private fun ScalingLazyListScope.hikeAdvancedRoutingSection(
    routeStyle: String,
    customHikeParams: HikeRouteProfileParams,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCustomHikeParamsChanged: (HikeRouteProfileParams) -> Unit,
    resources: Resources,
) {
    item {
        SettingsPickerChip(
            label =
                if (expanded) {
                    resources.getString(
                        R.string.settings_advanced_hike_routing,
                    )
                } else {
                    resources.getString(R.string.settings_advanced_hike_routing_2)
                },
            secondaryLabel = resources.getString(R.string.settings_edit_hike_route_profile),
            iconImageVector = null,
            onClick = onToggle,
        )
    }

    if (expanded) {
        editableHikeAdvancedRows(
            params = effectiveHikeParams(routeStyle, customHikeParams),
            onParamsChanged = onCustomHikeParamsChanged,
            resources = resources,
        )
    }
}

private fun ScalingLazyListScope.editableHikeAdvancedRows(
    params: HikeRouteProfileParams,
    onParamsChanged: (HikeRouteProfileParams) -> Unit,
    resources: Resources,
) {
    item {
        SettingsOptionPickerRow(
            label = resources.getString(R.string.settings_marked_hiking_routes),
            selectedValue = params.hikingRoutesPreference,
            options = hikingRoutePreferenceOptions.map { (value, labelRes) -> value to resources.getString(labelRes) },
            onSelect = { onParamsChanged(params.copy(hikingRoutesPreference = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        SettingsOptionPickerRow(
            label = resources.getString(R.string.settings_path_preference),
            selectedValue = params.pathPreference,
            options = pathPreferenceOptions.map { (value, labelRes) -> value to resources.getString(labelRes) },
            onSelect = { onParamsChanged(params.copy(pathPreference = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        SettingsOptionPickerRow(
            label = resources.getString(R.string.settings_max_difficulty),
            selectedValue = params.sacScaleLimit,
            options = sacScaleOptions.map { (value, labelRes) -> value to resources.getString(labelRes) },
            onSelect = { onParamsChanged(params.copy(sacScaleLimit = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        SettingsOptionPickerRow(
            label = resources.getString(R.string.settings_preferred_difficulty),
            selectedValue = params.sacScalePreferred,
            options = sacScaleOptions.map { (value, labelRes) -> value to resources.getString(labelRes) },
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
        0f to R.string.glancemap_state_off,
        0.20f to R.string.gpx_moderate,
        0.60f to R.string.settings_high,
        1f to R.string.gpx_very_high,
    )

private val pathPreferenceOptions =
    listOf(
        0f to R.string.settings_neutral,
        10f to R.string.gpx_prefer_paths,
        20f to R.string.gpx_strong,
    )

private val sacScaleOptions =
    listOf(
        1 to R.string.gpx_t1_easy,
        2 to R.string.gpx_t2_mountain,
        3 to R.string.gpx_t3_mountain_path,
        4 to R.string.gpx_t4_alpine,
    )

@Suppress("FunctionName")
@Composable
internal fun GpxSettingsShortcutChip(onClick: () -> Unit) {
    AdaptiveSettingsShortcutChip(
        standardLabel = stringResource(R.string.settings_gpx_settings),
        compactLabel = stringResource(R.string.common_gpx),
        standardSecondaryLabel = stringResource(R.string.settings_back_to_gpx_settings),
        compactSecondaryLabel = stringResource(R.string.common_back),
        iconImageVector = Icons.Filled.Folder,
        applyTopPadding = true,
        compactRoundWidthFraction = 0.78f,
        onClick = onClick,
    )
}
