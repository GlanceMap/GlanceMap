package com.glancemap.glancemapwearos.presentation.features.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
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
            isLoadingPartialSummary -> "Scanning..."
            partialSummary.count <= 0 -> "No partial files"
            partialSummary.count == 1 -> "1 file · ${formatStorageSize(partialSummary.totalBytes)}"
            else -> "${partialSummary.count} files · ${formatStorageSize(partialSummary.totalBytes)}"
        }

    WearSettingsListScreen(listTokens = listTokens) {
        item {
            Text(
                "General",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            SettingsSectionChip(
                label = "User profile",
                secondaryLabel =
                    formatUserProfileSummary(
                        activityProfile = activityProfile,
                        isMetric = isMetric,
                        userWeightKg = userWeightKg,
                        backpackWeightKg = backpackWeightKg,
                        bikeWeightKg = bikeWeightKg,
                    ),
                compactRoundWidthFraction = 0.86f,
                onClick = { navController.navigate(WatchRoutes.USER_PROFILE_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = "REC settings",
                onClick = { navController.navigate(WatchRoutes.RECORDING_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = "GPS settings",
                onClick = { navController.navigate(WatchRoutes.GPS_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = "Compass settings",
                onClick = { navController.navigate(WatchRoutes.COMPASS_SETTINGS) },
            )
        }

        item { Text("Screen settings", style = MaterialTheme.typography.titleMedium) }
        item {
            SettingsSectionChip(
                label = "POI settings",
                onClick = { navController.navigate(WatchRoutes.POI_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = "GPX settings",
                onClick = { navController.navigate(WatchRoutes.GPX_SETTINGS) },
            )
        }
        item {
            SettingsSectionChip(
                label = "Map settings",
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
                label = "Advanced settings",
                secondaryLabel = if (advancedSettingsExpanded) "Hide advanced options" else "Show advanced options",
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
                    label = "Exit back button",
                    secondaryLabel = "For compatible watches",
                )
            }
            item {
                SettingsPickerChip(
                    label = if (isClearingCache) "Clearing cache..." else "Clear cache",
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
                            "Clearing partial transfer..."
                        } else {
                            "Clear partial transfer"
                        },
                    secondaryLabel = partialSummaryText,
                    iconImageVector = null,
                    onClick = {
                        if (isClearingPartialFiles) return@SettingsPickerChip
                        if (partialSummary.count <= 0) {
                            Toast
                                .makeText(
                                    context,
                                    "No partial transfer files",
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
                    label = "Reset to Default",
                    iconImageVector = null,
                    onClick = { navController.navigate(WatchRoutes.RESET_DEFAULTS_CONFIRM) },
                )
            }
            item {
                SettingsSectionChip(
                    label = "Debugging",
                    onClick = { navController.navigate(WatchRoutes.DEBUG_SETTINGS) },
                )
            }
        }
        item {
            SettingsSectionChip(
                label = "Credits & Legal",
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
        title = "Clear cache",
        message =
            "Removes temporary map, GPX, relief and theme caches. " +
                "Imported maps, GPX and DEM files stay on the watch.",
        confirmText = if (isClearingCache) "Clearing..." else "Clear",
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
                        val deletedMb =
                            if (result.deletedBytes > 0L) {
                                " (${(result.deletedBytes / (1024.0 * 1024.0)).let { String.format(java.util.Locale.US, "%.1f", it) }} MB)"
                            } else {
                                ""
                            }
                        Toast
                            .makeText(
                                context,
                                "Cache cleared$deletedMb",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }.onFailure {
                        Toast
                            .makeText(
                                context,
                                "Cache cleanup failed",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    isClearingCache = false
                    showClearCacheDialog = false
                }
            }
        },
        dismissText = "Cancel",
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
        title = "Clear partial transfers",
        message =
            "Deletes ${partialSummary.count} partial transfer file(s) and frees " +
                "${formatStorageSize(partialSummary.totalBytes)}. Finished files stay on the watch.",
        confirmText = if (isClearingPartialFiles) "Clearing..." else "Clear",
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
                                    "Cleared ${result.removedFiles} partial file(s)"
                                } else {
                                    "No partial transfer files"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                    }.onFailure {
                        Toast
                            .makeText(
                                context,
                                "Partial transfer cleanup failed",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    isClearingPartialFiles = false
                    showClearPartialTransfersDialog = false
                    refreshPartialSummary()
                }
            }
        },
        dismissText = "Cancel",
        dismissEnabled = !isClearingPartialFiles,
        onDismiss = { showClearPartialTransfersDialog = false },
    )
}

private fun formatStorageSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safeBytes >= gb -> String.format(Locale.US, "%.2f GB", safeBytes / gb)
        safeBytes >= mb -> String.format(Locale.US, "%.1f MB", safeBytes / mb)
        safeBytes >= kb -> String.format(Locale.US, "%.0f KB", safeBytes / kb)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}

private fun formatSettingsUserWeight(
    weightKg: Float,
    isMetric: Boolean,
): String =
    if (isMetric) {
        "${weightKg.roundToInt()} kg"
    } else {
        "${(weightKg * SETTINGS_KG_TO_LB).roundToInt()} lb"
    }

private fun formatUserProfileSummary(
    activityProfile: String,
    isMetric: Boolean,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
): String {
    val isBike = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    val parts =
        mutableListOf(
            if (isBike) "Bike" else "Hike",
            if (isMetric) "Metric" else "Imperial",
            formatSettingsUserWeight(userWeightKg, isMetric),
        )
    if (backpackWeightKg.roundToInt() > 0) {
        parts += "pack ${formatSettingsUserWeight(backpackWeightKg, isMetric)}"
    }
    if (isBike) {
        parts += "bike ${formatSettingsUserWeight(bikeWeightKg, isMetric)}"
    }
    return parts.joinToString(" · ")
}

private const val SETTINGS_KG_TO_LB = 2.2046226218f
private const val SETTINGS_UI_PREFS = "settings_screen_ui_prefs"
private const val ADVANCED_SETTINGS_EXPANDED_KEY = "advanced_settings_expanded"
