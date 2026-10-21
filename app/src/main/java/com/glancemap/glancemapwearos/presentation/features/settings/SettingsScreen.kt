package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.transfer.storage.StalePartialTransferCleaner
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel,
    mapViewModel: MapViewModel,
    gpxViewModel: GpxViewModel,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listTokens = rememberSettingsListTokens()
    val settingsUiPrefs =
        remember {
            context.getSharedPreferences(SETTINGS_UI_PREFS, android.content.Context.MODE_PRIVATE)
        }
    var advancedSettingsExpanded by remember {
        mutableStateOf(settingsUiPrefs.getBoolean(ADVANCED_SETTINGS_EXPANDED_KEY, false))
    }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearPartialTransfersDialog by remember { mutableStateOf(false) }
    var isClearingCache by remember { mutableStateOf(false) }
    var isLoadingPartialSummary by remember { mutableStateOf(true) }
    var isClearingPartialFiles by remember { mutableStateOf(false) }
    var partialSummary by remember {
        mutableStateOf(StalePartialTransferCleaner.PartialFilesSummary(count = 0, totalBytes = 0L))
    }
    val isMetric by viewModel.isMetric.collectAsState()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val userWeightKg by viewModel.userWeightKg.collectAsState()
    val backpackWeightKg by viewModel.backpackWeightKg.collectAsState()
    val bikeWeightKg by viewModel.bikeWeightKg.collectAsState()
    val backButtonExitsNavigation by viewModel.backButtonExitsNavigation.collectAsState()

    fun refreshPartialSummary() {
        scope.launch {
            isLoadingPartialSummary = true
            partialSummary =
                withContext(Dispatchers.IO) {
                    StalePartialTransferCleaner.scan(context)
                }
            isLoadingPartialSummary = false
        }
    }

    LaunchedEffect(Unit) {
        refreshPartialSummary()
    }

    val partialSummaryText =
        when {
            isLoadingPartialSummary -> stringResource(R.string.settings_scanning)
            partialSummary.count <= 0 -> stringResource(R.string.settings_no_partial_files)
            else ->
                pluralStringResource(
                    R.plurals.settings_partial_files_summary,
                    partialSummary.count,
                    partialSummary.count,
                    formatStorageSize(partialSummary.totalBytes, resources = resources),
                )
        }

    WearSettingsListScreen(listTokens = listTokens) {
        item {
            Text(
                stringResource(R.string.settings_general),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_user_profile),
                secondaryLabel =
                    formatUserProfileSummary(
                        activityProfile = activityProfile,
                        isMetric = isMetric,
                        userWeightKg = userWeightKg,
                        backpackWeightKg = backpackWeightKg,
                        bikeWeightKg = bikeWeightKg,
                        resources = resources,
                    ),
                compactRoundWidthFraction = 0.86f,
                onClick = { navController.navigate(WatchRoutes.USER_PROFILE_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_rec_settings),
                onClick = { navController.navigate(WatchRoutes.RECORDING_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.gps_settings),
                onClick = { navController.navigate(WatchRoutes.GPS_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_compass_settings),
                onClick = { navController.navigate(WatchRoutes.COMPASS_SETTINGS) },
            )
        }

        item { Text(stringResource(R.string.settings_screen_settings), style = MaterialTheme.typography.titleMedium) }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_poi_settings),
                onClick = { navController.navigate(WatchRoutes.POI_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_gpx_settings),
                onClick = { navController.navigate(WatchRoutes.GPX_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_map_settings),
                onClick = { navController.navigate(WatchRoutes.MAP_SETTINGS) },
            )
        }
        item {
            DownloadSettingsSectionChip(
                onClick = { navController.navigate(WatchRoutes.DOWNLOAD_SETTINGS) },
            )
        }

        item {
            SettingsPickerChip(
                label = stringResource(R.string.settings_advanced_settings),
                secondaryLabel =
                    if (advancedSettingsExpanded) {
                        stringResource(
                            R.string.settings_hide_advanced_options,
                        )
                    } else {
                        stringResource(R.string.settings_show_advanced_options)
                    },
                iconImageVector =
                    if (advancedSettingsExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                onClick = {
                    advancedSettingsExpanded = !advancedSettingsExpanded
                    settingsUiPrefs
                        .edit()
                        .putBoolean(ADVANCED_SETTINGS_EXPANDED_KEY, advancedSettingsExpanded)
                        .apply()
                },
            )
        }
        if (advancedSettingsExpanded) {
            item {
                SettingsToggleChip(
                    checked = backButtonExitsNavigation,
                    onCheckedChanged = viewModel::setBackButtonExitsNavigation,
                    label = stringResource(R.string.settings_exit_back_button),
                    secondaryLabel = stringResource(R.string.settings_for_compatible_watches),
                )
            }
            item {
                SettingsPickerChip(
                    label =
                        if (isClearingCache) {
                            stringResource(
                                R.string.settings_clearing_cache,
                            )
                        } else {
                            stringResource(R.string.settings_clear_cache)
                        },
                    iconImageVector = null,
                    onClick = {
                        if (!isClearingCache) {
                            showClearCacheDialog = true
                        }
                    },
                )
            }
            item {
                SettingsPickerChip(
                    label =
                        if (isClearingPartialFiles) {
                            stringResource(R.string.settings_clearing_partial_transfer)
                        } else {
                            stringResource(R.string.settings_clear_partial_transfer)
                        },
                    secondaryLabel = partialSummaryText,
                    iconImageVector = null,
                    onClick = {
                        if (isClearingPartialFiles) return@SettingsPickerChip
                        if (partialSummary.count <= 0) {
                            Toast
                                .makeText(
                                    context,
                                    resources.getString(R.string.settings_no_partial_transfer_files),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        } else {
                            showClearPartialTransfersDialog = true
                        }
                    },
                )
            }
            item {
                SettingsPickerChip(
                    label = stringResource(R.string.settings_reset_to_default),
                    iconImageVector = null,
                    onClick = { navController.navigate(WatchRoutes.RESET_DEFAULTS_CONFIRM) },
                )
            }
            item {
                SettingsSectionChip(
                    label = stringResource(R.string.settings_debugging),
                    onClick = { navController.navigate(WatchRoutes.DEBUG_SETTINGS) },
                )
            }
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_credits_legal),
                iconImageVector = Icons.Filled.Gavel,
                onClick = { navController.navigate(WatchRoutes.LICENSES) },
            )
        }
    }

    WearActionDialog(
        visible = showClearCacheDialog,
        onDismissRequest = {
            if (!isClearingCache) {
                showClearCacheDialog = false
            }
        },
        title = stringResource(R.string.settings_clear_cache),
        message =
            stringResource(R.string.settings_removes_temporary_map_gpx_relief_and_theme_caches_imported_maps_gpx_an),
        confirmText =
            if (isClearingCache) {
                stringResource(
                    R.string.settings_clearing,
                )
            } else {
                stringResource(R.string.common_clear)
            },
        confirmEnabled = !isClearingCache,
        onConfirm = {
            if (!isClearingCache) {
                isClearingCache = true
                scope.launch {
                    runCatching {
                        val result = mapViewModel.clearDerivedCaches()
                        gpxViewModel.clearDerivedCaches()
                        result
                    }.onSuccess { result ->
                        val message =
                            if (result.deletedBytes > 0L) {
                                resources.getString(
                                    R.string.settings_cache_cleared_mb,
                                    String.format(
                                        java.util.Locale.getDefault(),
                                        "%.1f",
                                        result.deletedBytes / (1024.0 * 1024.0),
                                    ),
                                )
                            } else {
                                resources.getString(R.string.settings_cache_cleared_message)
                            }
                        Toast
                            .makeText(
                                context,
                                message,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }.onFailure {
                        Toast
                            .makeText(
                                context,
                                resources.getString(R.string.settings_cache_cleanup_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    isClearingCache = false
                    showClearCacheDialog = false
                }
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        dismissEnabled = !isClearingCache,
        onDismiss = { showClearCacheDialog = false },
    )

    WearActionDialog(
        visible = showClearPartialTransfersDialog,
        onDismissRequest = {
            if (!isClearingPartialFiles) {
                showClearPartialTransfersDialog = false
            }
        },
        title = stringResource(R.string.settings_clear_partial_transfers),
        message =
            pluralStringResource(
                R.plurals.settings_partial_transfer_delete_confirmation,
                partialSummary.count,
                partialSummary.count,
                formatStorageSize(partialSummary.totalBytes, resources = resources),
            ),
        confirmText =
            if (isClearingPartialFiles) {
                stringResource(
                    R.string.settings_clearing,
                )
            } else {
                stringResource(R.string.common_clear)
            },
        confirmEnabled = !isClearingPartialFiles,
        onConfirm = {
            if (!isClearingPartialFiles) {
                isClearingPartialFiles = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            StalePartialTransferCleaner.clearAll(context)
                        }
                    }.onSuccess { result ->
                        Toast
                            .makeText(
                                context,
                                if (result.removedFiles > 0) {
                                    resources.getQuantityString(
                                        R.plurals.settings_partial_files_cleared,
                                        result.removedFiles,
                                        result.removedFiles,
                                    )
                                } else {
                                    resources.getString(R.string.settings_no_partial_transfer_files)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                    }.onFailure {
                        Toast
                            .makeText(
                                context,
                                resources.getString(R.string.settings_partial_transfer_cleanup_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    isClearingPartialFiles = false
                    showClearPartialTransfersDialog = false
                    refreshPartialSummary()
                }
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        dismissEnabled = !isClearingPartialFiles,
        onDismiss = { showClearPartialTransfersDialog = false },
    )
}

private fun formatStorageSize(
    bytes: Long,
    resources: Resources,
): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safeBytes >= gb -> resources.getString(R.string.settings_gb, safeBytes / gb)
        safeBytes >= mb -> resources.getString(R.string.settings_mb_2, safeBytes / mb)
        safeBytes >= kb -> resources.getString(R.string.settings_kb, safeBytes / kb)
        else -> resources.getString(R.string.download_b, bytes.coerceAtLeast(0L))
    }
}

private fun formatSettingsUserWeight(
    weightKg: Float,
    isMetric: Boolean,
    resources: Resources,
): String =
    if (isMetric) {
        resources.getString(R.string.settings_whole_kg, weightKg.roundToInt())
    } else {
        resources.getString(R.string.settings_whole_lb, (weightKg * SETTINGS_KG_TO_LB).roundToInt())
    }

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
private fun formatUserProfileSummary(
    activityProfile: String,
    isMetric: Boolean,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
    resources: Resources,
): String {
    val isBike = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    val parts =
        mutableListOf(
            if (isBike) resources.getString(R.string.common_bike) else resources.getString(R.string.common_hike),
            if (isMetric) {
                resources.getString(
                    R.string.common_metric,
                )
            } else {
                resources.getString(R.string.common_imperial)
            },
            formatSettingsUserWeight(userWeightKg, isMetric, resources = resources),
        )
    if (backpackWeightKg.roundToInt() > 0) {
        parts +=
            resources.getString(
                R.string.settings_pack,
                formatSettingsUserWeight(backpackWeightKg, isMetric, resources = resources),
            )
    }
    if (isBike) {
        parts +=
            resources.getString(
                R.string.settings_bike,
                formatSettingsUserWeight(bikeWeightKg, isMetric, resources = resources),
            )
    }
    return parts.joinToString(" · ")
}

private const val SETTINGS_KG_TO_LB = 2.2046226218f
private const val SETTINGS_UI_PREFS = "settings_screen_ui_prefs"
private const val ADVANCED_SETTINGS_EXPANDED_KEY = "advanced_settings_expanded"
