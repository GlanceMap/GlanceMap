package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeListItem
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds
import com.glancemap.glancemapwearos.presentation.features.maps.DemSetupBottomSheet
import com.glancemap.glancemapwearos.presentation.features.maps.DemSetupReason
import com.glancemap.glancemapwearos.presentation.features.maps.theme.DemMapReadiness
import com.glancemap.glancemapwearos.presentation.features.maps.theme.ThemeViewModel
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import kotlinx.coroutines.launch

@Composable
fun MapSettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel,
    themeViewModel: ThemeViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val autoRecenterEnabled by viewModel.autoRecenterEnabled.collectAsState()
    val autoRecenterDelay by viewModel.autoRecenterDelay.collectAsState(initial = 5)
    val liveElevation by viewModel.liveElevation.collectAsState()
    val liveDistance by viewModel.liveDistance.collectAsState()
    val navigationMarkerAnchorMode by viewModel.navigationMarkerAnchorMode.collectAsState()
    val themeItems by themeViewModel.themeItems.collectAsState()
    val selectedMapPath by viewModel.selectedMapPath.collectAsState()
    val scope = rememberCoroutineScope()
    var showDemSetupDialog by remember { mutableStateOf(false) }
    var demSetupReason by remember { mutableStateOf(DemSetupReason.GENERIC) }
    var fallbackTerrainNotice by remember { mutableStateOf<DemMapReadiness?>(null) }
    var fallbackTerrainNoticeKey by remember { mutableStateOf<String?>(null) }
    val markerPositionOptions =
        listOf(
            SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER to stringResource(R.string.marker_position_middle),
            SettingsRepository.NAVIGATION_MARKER_ANCHOR_LOWER to stringResource(R.string.marker_position_bottom),
        )
    val hillShadingEnabled =
        remember(themeItems) {
            themeItems
                .filterIsInstance<ThemeListItem.GlobalToggle>()
                .firstOrNull { it.id == ThemeUiIds.HILL_SHADING }
                ?.enabled
                ?: false
        }
    val hillShadingSupported =
        remember(themeItems) {
            themeItems
                .filterIsInstance<ThemeListItem.GlobalToggle>()
                .firstOrNull { it.id == ThemeUiIds.HILL_SHADING }
                ?.supported
                ?: false
        }
    val reliefOverlayEnabled =
        remember(themeItems) {
            themeItems
                .filterIsInstance<ThemeListItem.GlobalToggle>()
                .firstOrNull { it.id == ThemeUiIds.RELIEF_OVERLAY }
                ?.enabled
                ?: false
        }
    val hillShadingChecked = hillShadingEnabled && hillShadingSupported
    val hillShadingSecondaryLabel =
        when {
            !hillShadingSupported -> stringResource(R.string.map_theme_not_supported)
            hillShadingEnabled -> stringResource(R.string.state_on)
            else -> stringResource(R.string.state_off)
        }
    val reliefOverlaySecondaryLabel =
        stringResource(if (reliefOverlayEnabled) R.string.state_on else R.string.state_off)

    DemSetupBottomSheet(
        visible = showDemSetupDialog,
        reason = demSetupReason,
        onDismiss = {
            showDemSetupDialog = false
            demSetupReason = DemSetupReason.GENERIC
        },
    )
    fallbackTerrainNotice?.let { readiness ->
        val fallbackSource =
            readiness
                .selectedSource
                .readFallbackOrder()
                .first { source -> source != readiness.selectedSource }
        WearActionDialog(
            visible = true,
            title = "Live elevation",
            message =
                "${readiness.selectedSource.displayName} is not available for all of this map.\n" +
                    "Using ${fallbackSource.displayName} instead.",
            confirmText = "OK",
            onConfirm = { fallbackTerrainNotice = null },
            onDismissRequest = { fallbackTerrainNotice = null },
        )
    }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            SettingsOptionPickerRow(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.map_position_marker),
                selectedValue = navigationMarkerAnchorMode,
                options = markerPositionOptions,
                secondaryLabel =
                    if (navigationMarkerAnchorMode == SettingsRepository.NAVIGATION_MARKER_ANCHOR_LOWER) {
                        stringResource(R.string.marker_position_bottom)
                    } else {
                        stringResource(R.string.marker_position_middle)
                    },
                onSelect = viewModel::setNavigationMarkerAnchorMode,
            )
        }
        item {
            SettingsToggleChip(
                checked = autoRecenterEnabled,
                onCheckedChanged = {
                    viewModel.setAutoRecenterEnabled(it)
                },
                label = stringResource(R.string.map_auto_recenter),
            )
        }
        if (autoRecenterEnabled) {
            item {
                RecenterDelaySetting(autoRecenterDelay) { newDelay ->
                    viewModel.setAutoRecenterDelay(newDelay)
                }
            }
        }
        item {
            SettingsToggleChip(
                checked = liveElevation,
                onCheckedChanged = { enabled ->
                    if (!enabled) {
                        viewModel.setLiveElevation(false)
                    } else {
                        scope.launch {
                            val readiness = themeViewModel.demReadinessForMap(selectedMapPath)
                            if (readiness.isReady) {
                                viewModel.setLiveElevation(true)
                                val noticeKey = "${selectedMapPath.orEmpty()}:${readiness.selectedSource.id}"
                                if (readiness.usesFallbackTerrain && fallbackTerrainNoticeKey != noticeKey) {
                                    fallbackTerrainNoticeKey = noticeKey
                                    fallbackTerrainNotice = readiness
                                }
                            } else {
                                viewModel.setLiveElevation(false)
                                demSetupReason = DemSetupReason.LIVE_ELEVATION
                                showDemSetupDialog = true
                            }
                        }
                    }
                },
                label = stringResource(R.string.map_live_elevation),
                secondaryLabel =
                    stringResource(if (liveElevation) R.string.state_on else R.string.state_off),
            )
        }
        item {
            SettingsToggleChip(
                checked = liveDistance,
                onCheckedChanged = { viewModel.setLiveDistance(it) },
                label = stringResource(R.string.map_live_distance),
                secondaryLabel =
                    stringResource(if (liveDistance) R.string.state_on else R.string.state_off),
            )
        }
        item {
            SettingsToggleChip(
                checked = hillShadingChecked,
                enabled = hillShadingSupported,
                onCheckedChanged = { enabled ->
                    if (!enabled) {
                        themeViewModel.setGlobalToggle(ThemeUiIds.HILL_SHADING, false)
                    } else {
                        scope.launch {
                            if (selectedMapPath.isNullOrBlank()) {
                                themeViewModel.setGlobalToggle(ThemeUiIds.HILL_SHADING, false)
                                demSetupReason = DemSetupReason.HILL_SHADING_MAP_REQUIRED
                                showDemSetupDialog = true
                                return@launch
                            }
                            val terrainAvailable =
                                themeViewModel
                                    .demReadinessForMap(selectedMapPath)
                                    .hasAnyTerrain
                            if (terrainAvailable) {
                                themeViewModel.setGlobalToggle(ThemeUiIds.HILL_SHADING, true)
                            } else {
                                themeViewModel.setGlobalToggle(ThemeUiIds.HILL_SHADING, false)
                                demSetupReason = DemSetupReason.HILL_SHADING
                                showDemSetupDialog = true
                            }
                        }
                    }
                },
                label = stringResource(R.string.map_hill_shading),
                secondaryLabel = hillShadingSecondaryLabel,
            )
        }
        item {
            SettingsToggleChip(
                checked = reliefOverlayEnabled,
                onCheckedChanged = { enabled ->
                    if (!enabled) {
                        themeViewModel.setGlobalToggle(ThemeUiIds.RELIEF_OVERLAY, false)
                    } else {
                        scope.launch {
                            val demReady = themeViewModel.demReadinessForMap(selectedMapPath).isReady
                            if (demReady) {
                                themeViewModel.setGlobalToggle(ThemeUiIds.RELIEF_OVERLAY, true)
                            } else {
                                themeViewModel.setGlobalToggle(ThemeUiIds.RELIEF_OVERLAY, false)
                                demSetupReason = DemSetupReason.SLOPE_OVERLAY
                                showDemSetupDialog = true
                            }
                        }
                    }
                },
                label = stringResource(R.string.map_slope_overlay),
                secondaryLabel = reliefOverlaySecondaryLabel,
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.map_theme),
                secondaryLabel = stringResource(R.string.map_open_theme_settings),
                onClick = { navController.navigate(WatchRoutes.THEME_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.map_display),
                secondaryLabel = stringResource(R.string.map_open_display_settings),
                onClick = { navController.navigate(WatchRoutes.MAP_DISPLAY_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.map_zoom),
                secondaryLabel = stringResource(R.string.map_open_zoom_settings),
                onClick = { navController.navigate(WatchRoutes.MAP_ZOOM_SETTINGS) },
            )
        }
    }
}

@Composable
private fun RecenterDelaySetting(
    delay: Int,
    onValueChange: (Int) -> Unit,
) {
    var internalValue by remember(delay) { mutableStateOf(delay.toFloat()) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.map_recenter_delay_seconds, internalValue.toInt()),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = internalValue,
            onValueChange = {
                internalValue = it
                onValueChange(it.toInt())
            },
            valueRange = 1f..30f,
            steps = 28,
            increaseIcon = {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase))
            },
            decreaseIcon = {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
