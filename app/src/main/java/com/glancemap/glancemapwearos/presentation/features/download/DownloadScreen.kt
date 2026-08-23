@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooManyFunctions",
    "UnusedPrivateMember",
)

package com.glancemap.glancemapwearos.presentation.features.download

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.features.navigate.rememberLocationPermissionState
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsListAnchorType
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsListAutoCentering
import com.glancemap.glancemapwearos.presentation.features.settings.rememberSettingsScalingLazyListState
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.DeleteConfirmationDialog
import com.glancemap.glancemapwearos.presentation.ui.FeatureListScaffold
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.launch
import androidx.wear.compose.material3.Icon as Material3Icon

@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel,
    areaPickerOpen: Boolean,
    onAreaPickerOpenChange: (Boolean) -> Unit,
    selectedAreaFolder: String?,
    onSelectedAreaFolderChange: (String?) -> Unit,
    areaSearchQuery: String,
    onAreaSearchQueryChange: (String) -> Unit,
    onLibraryChanged: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val uiState by viewModel.uiState.collectAsState()
    val showAreaPicker = areaPickerOpen
    var bundlePendingDelete by remember { mutableStateOf<OamInstalledBundle?>(null) }
    var showOamInfoDialog by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf(false) }
    var refreshMode by remember { mutableStateOf(false) }
    val effectiveRefreshMode =
        refreshMode &&
            !uiState.isDownloading &&
            uiState.pausedOperation != DownloadOperation.REFRESH
    var showAreaSearchDialog by remember { mutableStateOf(false) }
    val infoPrefs =
        remember(context) {
            context.getSharedPreferences(DOWNLOAD_INFO_PREFS, android.content.Context.MODE_PRIVATE)
        }
    val coroutineScope = rememberCoroutineScope()
    var isFindingCurrentLocation by remember { mutableStateOf(false) }
    var useCurrentLocationAfterPermission by remember { mutableStateOf(false) }
    var locationSuggestionMessage by remember { mutableStateOf<String?>(null) }
    val locationPermissionState =
        rememberLocationPermissionState(context) { granted ->
            if (!granted && useCurrentLocationAfterPermission) {
                useCurrentLocationAfterPermission = false
                locationSuggestionMessage = "Location permission is needed"
            }
        }
    val findSuggestedAreasFromCurrentLocation: () -> Unit = {
        if (!isFindingCurrentLocation) {
            coroutineScope.launch {
                isFindingCurrentLocation = true
                locationSuggestionMessage = null
                val location = runCatching { requestDownloadCurrentLocation(context) }.getOrNull()
                isFindingCurrentLocation = false
                if (location == null) {
                    locationSuggestionMessage = "Current location unavailable"
                } else {
                    val suggestedAreas =
                        viewModel.suggestAreasForLocation(
                            latitude = location.latitude,
                            longitude = location.longitude,
                        )
                    locationSuggestionMessage =
                        if (suggestedAreas.isEmpty()) {
                            "No matching map bundle"
                        } else {
                            null
                        }
                }
            }
        }
    }
    LaunchedEffect(useCurrentLocationAfterPermission, locationPermissionState.hasLocationPermission) {
        if (useCurrentLocationAfterPermission && locationPermissionState.hasLocationPermission) {
            useCurrentLocationAfterPermission = false
            findSuggestedAreasFromCurrentLocation()
        }
    }
    var wasAreaPickerOpen by remember { mutableStateOf(showAreaPicker) }
    val selectedAreas = uiState.selectedAreas
    val estimatedSize =
        selectedAreas.estimatedSizeLabel(uiState.selection)
    val selectedAreaLabel = selectedAreas.selectedAreaLabel()
    val selectedAreaSecondaryLabel = selectedAreas.selectedAreaSecondaryLabel()
    val listHorizontalPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val listTopPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 1.dp
            WearScreenSize.MEDIUM -> 0.dp
            WearScreenSize.SMALL -> 0.dp
        }
    val listBottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 2.dp
            WearScreenSize.MEDIUM -> 1.dp
            WearScreenSize.SMALL -> 0.dp
        }
    val listState = rememberSettingsScalingLazyListState(topPadding = listTopPadding)
    val rowSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 7.dp
            WearScreenSize.SMALL -> 5.dp
        }
    val areaFolders =
        remember(uiState.areas) {
            uiState.areas
                .groupBy { it.continent }
                .toSortedMap()
                .map { (continent, areas) -> continent to areas.sortedBy { it.region } }
        }
    val areaSearchQueryNormalized = areaSearchQuery.trim()
    val visiblePickerAreas =
        remember(uiState.areas, selectedAreaFolder, areaSearchQueryNormalized) {
            val query = areaSearchQueryNormalized.lowercase()
            uiState.areas
                .asSequence()
                .filter { area -> selectedAreaFolder == null || area.continent == selectedAreaFolder }
                .filter { area ->
                    query.isBlank() ||
                        area.region.lowercase().contains(query) ||
                        area.continent.lowercase().contains(query)
                }.sortedWith(compareBy<OamDownloadArea> { it.continent }.thenBy { it.region })
                .toList()
        }
    val headerTopPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 6.dp
            WearScreenSize.SMALL -> 4.dp
        }
    val headerBottomPadding = 0.dp
    val headerActionButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 24.dp
            WearScreenSize.MEDIUM -> 22.dp
            WearScreenSize.SMALL -> 20.dp
        }
    val headerActionIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 14.dp
            WearScreenSize.MEDIUM -> 13.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val headerActionVisualOffsetY =
        when (screenSize) {
            WearScreenSize.LARGE -> 4.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val headerActionSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 4.dp
            WearScreenSize.MEDIUM -> 3.dp
            WearScreenSize.SMALL -> 2.dp
        }
    val headerVerticalSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> (-14).dp
            WearScreenSize.MEDIUM -> (-15).dp
            WearScreenSize.SMALL -> (-16).dp
        }
    val headerTopSafePadding = headerTopPadding + adaptive.headerTopSafeInset
    val actionButtonHeight =
        when (screenSize) {
            WearScreenSize.LARGE -> 48.dp
            WearScreenSize.MEDIUM -> 46.dp
            WearScreenSize.SMALL -> 44.dp
        }
    val actionButtonIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 18.dp
            WearScreenSize.MEDIUM -> 17.dp
            WearScreenSize.SMALL -> 16.dp
        }
    val bottomActionBottomPadding = 0.dp
    val bottomActionVisualOffsetY =
        when (screenSize) {
            WearScreenSize.LARGE -> (-6).dp
            WearScreenSize.MEDIUM -> (-5).dp
            WearScreenSize.SMALL -> (-4).dp
        }
    val settingsButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val pickerDownloadButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 38.dp
            WearScreenSize.MEDIUM -> 34.dp
            WearScreenSize.SMALL -> 30.dp
        }
    val pickerDownloadIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 20.dp
            WearScreenSize.MEDIUM -> 18.dp
            WearScreenSize.SMALL -> 16.dp
        }
    val areaPickerListBottomPadding =
        listBottomPadding +
            if (adaptive.isRound && adaptive.fontScale > 1f) {
                when (screenSize) {
                    WearScreenSize.LARGE -> 42.dp
                    WearScreenSize.MEDIUM -> 36.dp
                    WearScreenSize.SMALL -> 30.dp
                }
            } else {
                0.dp
            }
    LaunchedEffect(uiState.lastLibraryChangedAtMillis) {
        if (uiState.lastLibraryChangedAtMillis > 0L) {
            onLibraryChanged()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshInstalledBundles()
        listState.scrollToItem(0)
    }
    LaunchedEffect(infoPrefs) {
        if (!infoPrefs.getBoolean(DOWNLOAD_INFO_SHOWN_KEY, false)) {
            showOamInfoDialog = true
        }
    }
    LaunchedEffect(uiState.installedBundles.isEmpty()) {
        if (uiState.installedBundles.isEmpty()) {
            deleteMode = false
            refreshMode = false
            viewModel.clearRefreshBundleSelection()
        }
    }
    LaunchedEffect(uiState.isDownloading) {
        if (uiState.isDownloading) {
            refreshMode = false
            deleteMode = false
            onAreaPickerOpenChange(false)
        }
    }
    LaunchedEffect(showAreaPicker) {
        val closedAreaPicker = wasAreaPickerOpen && !showAreaPicker
        wasAreaPickerOpen = showAreaPicker
        if (!showAreaPicker) {
            onSelectedAreaFolderChange(null)
            onAreaSearchQueryChange("")
            showAreaSearchDialog = false
            if (closedAreaPicker && uiState.selectedAreaIds.isNotEmpty()) {
                listState.scrollToItem(DOWNLOAD_MAIN_ACTION_ITEM_INDEX)
            }
        }
    }

    fun dismissOamInfoDialog() {
        showOamInfoDialog = false
        infoPrefs.edit().putBoolean(DOWNLOAD_INFO_SHOWN_KEY, true).apply()
    }
    BackHandler(enabled = showAreaPicker) {
        when {
            areaSearchQuery.isNotBlank() -> onAreaSearchQueryChange("")
            selectedAreaFolder != null -> onSelectedAreaFolderChange(null)
            else -> onAreaPickerOpenChange(false)
        }
    }

    DeleteConfirmationDialog(
        visible = bundlePendingDelete != null,
        title = "Delete bundle?",
        message = "This will remove the downloaded files for ${bundlePendingDelete?.areaLabel.orEmpty()}.",
        onConfirm = {
            bundlePendingDelete?.let(viewModel::deleteBundle)
            bundlePendingDelete = null
        },
        onDismiss = { bundlePendingDelete = null },
    )
    OamAttributionDialog(
        visible = showOamInfoDialog,
        onDismiss = { dismissOamInfoDialog() },
    )
    DownloadNetworkWarningDialog(
        message = uiState.networkWarningMessage,
        onContinue = viewModel::continueDownloadWithoutWifi,
        onDismiss = viewModel::dismissNetworkWarning,
    )
    RefreshBundleDialog(
        check = uiState.refreshPrompt,
        onConfirm = viewModel::confirmRefreshBundle,
        onDismiss = viewModel::dismissRefreshPrompt,
    )
    RefreshBundleSummaryDialog(
        summary = uiState.refreshSummaryPrompt,
        onConfirm = viewModel::confirmRefreshSelectedBundles,
        onDismiss = viewModel::dismissRefreshSummary,
    )
    AreaSearchDialog(
        visible = showAreaSearchDialog,
        initialQuery = areaSearchQuery,
        onDismiss = { showAreaSearchDialog = false },
        onApply = { query ->
            onAreaSearchQueryChange(query.trim())
            onSelectedAreaFolderChange(null)
            showAreaSearchDialog = false
        },
    )

    ScreenScaffold(scrollState = listState) {
        FeatureListScaffold {
            DownloadHeader(
                isDownloading = uiState.isDownloading,
                isCheckingUpdates = uiState.isCheckingUpdates,
                hasInstalledBundles = uiState.installedBundles.isNotEmpty(),
                refreshMode = effectiveRefreshMode,
                deleteMode = deleteMode,
                selectedRefreshBundleCount = uiState.selectedRefreshBundleIds.size,
                useLargeFontHeader = adaptive.isRound && adaptive.fontScale > 1f,
                topPadding = headerTopSafePadding,
                bottomPadding = headerBottomPadding,
                actionButtonSize = headerActionButtonSize,
                actionIconSize = headerActionIconSize,
                actionVisualOffsetY = headerActionVisualOffsetY,
                actionSpacing = headerActionSpacing,
                verticalSpacing = headerVerticalSpacing,
                onInfoClick = { showOamInfoDialog = true },
                onRefreshModeClick = {
                    val nextRefreshMode = !effectiveRefreshMode
                    refreshMode = nextRefreshMode
                    if (!nextRefreshMode) {
                        viewModel.clearRefreshBundleSelection()
                    }
                    if (nextRefreshMode) {
                        deleteMode = false
                        onAreaPickerOpenChange(false)
                        coroutineScope.launch {
                            listState.animateScrollToItem(DOWNLOAD_FIRST_REFRESH_BUNDLE_ITEM_INDEX)
                        }
                    }
                },
            )

            ScalingLazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                state = listState,
                contentPadding =
                    PaddingValues(
                        start = listHorizontalPadding,
                        end = listHorizontalPadding,
                        top = listTopPadding,
                        bottom = if (showAreaPicker) areaPickerListBottomPadding else listBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(rowSpacing),
                anchorType = SettingsListAnchorType,
                autoCentering = SettingsListAutoCentering,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showAreaPicker) {
                    downloadAreaPickerItems(
                        areaSearchQueryNormalized = areaSearchQueryNormalized,
                        selectedAreaLabel = selectedAreaLabel,
                        visiblePickerAreas = visiblePickerAreas,
                        areaFolders = areaFolders,
                        selectedAreaFolder = selectedAreaFolder,
                        selectedAreaIds = uiState.selectedAreaIds,
                        suggestedAreas = uiState.suggestedAreas,
                        selection = uiState.selection,
                        isFindingCurrentLocation = isFindingCurrentLocation,
                        locationSuggestionMessage = locationSuggestionMessage,
                        onDone = { onAreaPickerOpenChange(false) },
                        onUseCurrentLocation = {
                            if (locationPermissionState.hasLocationPermission) {
                                findSuggestedAreasFromCurrentLocation()
                            } else {
                                useCurrentLocationAfterPermission = true
                                locationPermissionState.launchPermissions()
                            }
                        },
                        onToggleSuggestedArea = viewModel::toggleArea,
                        onOpenSearch = { showAreaSearchDialog = true },
                        onClearSearch = { onAreaSearchQueryChange("") },
                        onClearAreaSelection = viewModel::clearAreaSelection,
                        onSelectedAreaFolderChange = onSelectedAreaFolderChange,
                        onToggleArea = viewModel::toggleArea,
                    )
                } else {
                    if (!effectiveRefreshMode) {
                        item {
                            DownloadChip(
                                label = selectedAreaLabel,
                                secondaryLabel = selectedAreaSecondaryLabel,
                                icon = Icons.Filled.UnfoldMore,
                                onClick = {
                                    if (!uiState.isDownloading) {
                                        onAreaPickerOpenChange(true)
                                    }
                                },
                            )
                        }

                        item {
                            DownloadSummary(
                                areas = selectedAreas,
                                selection = uiState.selection,
                                estimatedSize = estimatedSize,
                            )
                        }

                        if (uiState.isDownloading) {
                            item {
                                DownloadProgress(uiState)
                            }
                            item {
                                DownloadActionButton(
                                    label = "Pause",
                                    icon = Icons.Filled.Pause,
                                    enabled = true,
                                    height = actionButtonHeight,
                                    iconSize = actionButtonIconSize,
                                    onClick = viewModel::pauseDownload,
                                )
                            }
                            item {
                                DownloadActionButton(
                                    label = "Cancel",
                                    icon = Icons.Filled.Close,
                                    enabled = true,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    height = actionButtonHeight,
                                    iconSize = actionButtonIconSize,
                                    onClick = viewModel::cancelDownload,
                                )
                            }
                        } else {
                            item {
                                DownloadActionButton(
                                    label = if (uiState.isPausedDownload) "Resume" else "Download",
                                    icon = Icons.Filled.Download,
                                    enabled = uiState.canStartOrResumeDownload,
                                    height = actionButtonHeight,
                                    iconSize = actionButtonIconSize,
                                    onClick = viewModel::downloadSelectedBundle,
                                )
                            }
                        }
                    }

                    if (!effectiveRefreshMode) {
                        item {
                            Text(
                                text = "Installed bundles",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                maxLines = 1,
                            )
                        }
                    }

                    if (uiState.installedBundles.isEmpty()) {
                        item {
                            Text(
                                text = "No bundles installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        uiState.installedBundles.forEach { bundle ->
                            item {
                                InstalledBundleRow(
                                    bundle = bundle,
                                    needsRepair = uiState.bundleHealthByAreaId[bundle.areaId]?.needsRepair == true,
                                    refreshMode = effectiveRefreshMode,
                                    refreshSelected = bundle.areaId in uiState.selectedRefreshBundleIds,
                                    deleteMode = deleteMode,
                                    onRefresh = {
                                        if (!uiState.isDownloading && effectiveRefreshMode) {
                                            viewModel.toggleRefreshBundleSelection(bundle.areaId)
                                        }
                                    },
                                    onDelete = {
                                        if (!uiState.isDownloading && deleteMode) {
                                            bundlePendingDelete = bundle
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (!uiState.isDownloading) {
                        uiState.statusMessage?.let { message ->
                            item {
                                StatusText(
                                    text = message,
                                    error = false,
                                )
                            }
                        }
                    }
                    uiState.errorMessage?.let { message ->
                        item {
                            StatusText(
                                text = message,
                                error = true,
                            )
                        }
                    }
                }
            }

            if (showAreaPicker) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(pickerDownloadButtonSize + bottomActionBottomPadding)
                            .padding(horizontal = listHorizontalPadding)
                            .padding(bottom = bottomActionBottomPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState.selectedAreaIds.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onAreaPickerOpenChange(false)
                                viewModel.downloadSelectedBundle()
                            },
                            enabled = uiState.selection.canDownload,
                            modifier = Modifier.size(pickerDownloadButtonSize),
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = Color.White.copy(alpha = 0.08f),
                                    disabledContentColor = Color.White.copy(alpha = 0.38f),
                                ),
                        ) {
                            Material3Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Download selected areas",
                                modifier = Modifier.size(pickerDownloadIconSize),
                            )
                        }
                    }
                }
            } else {
                if (effectiveRefreshMode && uiState.installedBundles.isNotEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = listHorizontalPadding)
                                .padding(bottom = bottomActionBottomPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        DownloadActionButton(
                            label =
                                if (uiState.isCheckingUpdates) {
                                    "Checking updates..."
                                } else {
                                    refreshSelectionButtonLabel(uiState.selectedRefreshBundleIds.size)
                                },
                            icon = Icons.Filled.Update,
                            enabled =
                                uiState.selectedRefreshBundleIds.isNotEmpty() &&
                                    !uiState.isCheckingUpdates,
                            height = actionButtonHeight,
                            iconSize = actionButtonIconSize,
                            onClick = viewModel::checkSelectedBundlesForRefresh,
                        )
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = bottomActionBottomPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(modifier = Modifier.size(48.dp))
                            CompactIconHitTargetButton(
                                onClick = onOpenSettings,
                                enabled = !uiState.isDownloading,
                                visualSize = settingsButtonSize,
                                visualOffsetY = bottomActionVisualOffsetY,
                                containerColor = Color.Black.copy(alpha = 0.8f),
                                contentColor = Color.White,
                                disabledContainerColor = Color.Black.copy(alpha = 0.32f),
                                disabledContentColor = Color.White.copy(alpha = 0.38f),
                            ) {
                                Material3Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Bundle settings",
                                )
                            }
                            if (uiState.installedBundles.isNotEmpty()) {
                                CompactIconHitTargetButton(
                                    onClick = {
                                        val nextDeleteMode = !deleteMode
                                        deleteMode = nextDeleteMode
                                        if (nextDeleteMode) {
                                            refreshMode = false
                                            viewModel.clearRefreshBundleSelection()
                                        }
                                    },
                                    enabled = !uiState.isDownloading,
                                    visualSize = headerActionButtonSize,
                                    visualOffsetY = bottomActionVisualOffsetY,
                                    containerColor =
                                        if (deleteMode) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            Color.Black.copy(alpha = 0.8f)
                                        },
                                    contentColor =
                                        if (deleteMode) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            Color.White
                                        },
                                    disabledContainerColor = Color.Black.copy(alpha = 0.32f),
                                    disabledContentColor = Color.White.copy(alpha = 0.38f),
                                ) {
                                    Material3Icon(
                                        imageVector = if (deleteMode) Icons.Filled.Close else Icons.Filled.Delete,
                                        contentDescription =
                                            if (deleteMode) {
                                                "Exit delete mode"
                                            } else {
                                                "Enter delete mode"
                                            },
                                        modifier = Modifier.size(headerActionIconSize),
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.size(48.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadHeader(
    isDownloading: Boolean,
    isCheckingUpdates: Boolean,
    hasInstalledBundles: Boolean,
    refreshMode: Boolean,
    deleteMode: Boolean,
    selectedRefreshBundleCount: Int,
    useLargeFontHeader: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    actionButtonSize: Dp,
    actionIconSize: Dp,
    actionVisualOffsetY: Dp,
    actionSpacing: Dp,
    verticalSpacing: Dp,
    onInfoClick: () -> Unit,
    onRefreshModeClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = topPadding, bottom = bottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        ) {
            if (useLargeFontHeader) {
                cappedFontScale(maxFontScale = 1f) {
                    Text(
                        text = "Download",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "Download",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(actionSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionButton(
                    icon = Icons.Filled.Info,
                    contentDescription = "OpenAndroMaps info",
                    buttonSize = actionButtonSize,
                    iconSize = actionIconSize,
                    visualOffsetY = actionVisualOffsetY,
                    onClick = onInfoClick,
                )
                HeaderActionButton(
                    icon = Icons.Filled.Update,
                    contentDescription = if (refreshMode) "Exit refresh mode" else "Enter refresh mode",
                    buttonSize = actionButtonSize,
                    iconSize = actionIconSize,
                    visualOffsetY = actionVisualOffsetY,
                    enabled = hasInstalledBundles && !isDownloading && !isCheckingUpdates,
                    selected = refreshMode,
                    onClick = onRefreshModeClick,
                )
            }
            if (refreshMode) {
                val refreshInstruction =
                    when {
                        isCheckingUpdates && useLargeFontHeader -> "Checking"
                        isCheckingUpdates -> "Checking selected"
                        selectedRefreshBundleCount == 0 && useLargeFontHeader -> "Select"
                        selectedRefreshBundleCount == 0 -> "Select bundles to update"
                        else -> "$selectedRefreshBundleCount selected"
                    }
                if (useLargeFontHeader) {
                    cappedFontScale(maxFontScale = 1f) {
                        Text(
                            text = refreshInstruction,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                } else {
                    Text(
                        text = refreshInstruction,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else if (deleteMode) {
                Text(
                    text = "Delete mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    buttonSize: Dp,
    iconSize: Dp,
    visualOffsetY: Dp = 0.dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    CompactIconHitTargetButton(
        onClick = onClick,
        enabled = enabled,
        visualSize = buttonSize,
        visualOffsetY = visualOffsetY,
        containerColor =
            when {
                selected && danger -> MaterialTheme.colorScheme.errorContainer
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Black.copy(alpha = 0.7f)
            },
        contentColor =
            when {
                selected && danger -> MaterialTheme.colorScheme.onErrorContainer
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> Color.White
            },
        disabledContainerColor = Color.Black.copy(alpha = 0.32f),
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    ) {
        Material3Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun DownloadActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    height: Dp,
    iconSize: Dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = Color.White.copy(alpha = 0.08f),
                disabledContentColor = Color.White.copy(alpha = 0.38f),
            ),
    ) {
        Material3Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadSummary(
    areas: List<OamDownloadArea>,
    selection: OamDownloadSelection,
    estimatedSize: String,
) {
    val fileCountLine =
        if (areas.size > 1) {
            "\nFiles: ${areas.fileCountLabel(selection)}"
        } else {
            ""
        }

    Text(
        text = "Size: $estimatedSize$fileCountLine",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DownloadProgress(uiState: DownloadUiState) {
    val totalBytes = uiState.totalBytes
    val progressText =
        if (totalBytes != null && totalBytes > 0L) {
            "${formatBytes(uiState.bytesDone)} / ${formatBytes(totalBytes)}"
        } else {
            formatBytes(uiState.bytesDone)
        }
    Text(
        text =
            listOfNotNull(
                uiState.phase,
                uiState.detail,
                progressText.takeIf { uiState.bytesDone > 0L || totalBytes != null },
            ).joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InstalledBundleRow(
    bundle: OamInstalledBundle,
    needsRepair: Boolean,
    refreshMode: Boolean,
    refreshSelected: Boolean,
    deleteMode: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    DownloadChip(
        label = bundle.areaLabel,
        secondaryLabel =
            if (needsRepair) {
                "Repair needed · ${installedBundleSubtitle(bundle)}"
            } else {
                installedBundleSubtitle(bundle)
            },
        secondaryMarquee = true,
        icon =
            when {
                refreshMode && refreshSelected -> Icons.Filled.Check
                refreshMode -> Icons.Filled.Update
                deleteMode -> Icons.Filled.Delete
                else -> Icons.Filled.Check
            },
        selected = (refreshMode && refreshSelected) || (!deleteMode && !refreshMode),
        warning = needsRepair,
        onClick =
            when {
                refreshMode -> onRefresh
                deleteMode -> onDelete
                else -> ({})
            },
    )
}

private fun refreshSelectionButtonLabel(selectedCount: Int): String =
    if (selectedCount > 0) {
        "Check for update ($selectedCount)"
    } else {
        "Check for update"
    }

private fun installedBundleSubtitle(bundle: OamInstalledBundle): String =
    listOfNotNull(
        "Map".takeIf { bundle.mapFileName != null },
        "POI".takeIf { bundle.poiFileName != null },
        "Refuges.info".takeIf { bundle.refugesInfoFileName != null },
        "Routing".takeIf { bundle.routingFileNames.isNotEmpty() },
        "${bundle.demSource.shortLabel} elevation".takeIf { bundle.demTileIds.isNotEmpty() },
    ).joinToString(" + ").ifBlank { bundle.bundleChoice.label }

private fun OamDownloadSelection.itemCountLabel(): String {
    val itemCount =
        listOf(
            includeMap,
            includePoi,
            includeRouting,
            includeDem,
            includeRefugesInfo,
        ).count { it }
    val noun = if (itemCount == 1) "item" else "items"
    return "$itemCount $noun"
}

private fun List<OamDownloadArea>.estimatedSizeLabel(
    selection: OamDownloadSelection,
): String = estimatedBytes(selection).toSizeLabel(selection)

internal fun OamDownloadArea.areaSizeLabel(
    selection: OamDownloadSelection,
): String = "$continent - ${estimatedBytes(selection).toSizeLabel(selection)}"

private fun List<OamDownloadArea>.estimatedBytes(
    selection: OamDownloadSelection,
): Long = sumOf { it.estimatedBytes(selection) }

private fun OamDownloadArea.estimatedBytes(selection: OamDownloadSelection): Long =
    (if (selection.includeMap) mapSizeBytes else 0L) +
        (if (selection.includePoi) poiSizeBytes else 0L)

private fun List<OamDownloadArea>.fileCountLabel(selection: OamDownloadSelection): String {
    val knownCount =
        size *
            (
                (if (selection.includeMap) 1 else 0) +
                    (if (selection.includePoi) 1 else 0)
            )
    val hasUnknownCount = selection.includeRouting || selection.includeDem || selection.includeRefugesInfo
    return when {
        hasUnknownCount && knownCount == 0 -> "bundle files"
        hasUnknownCount -> "$knownCount+"
        else -> knownCount.toString()
    }
}

private fun Long.toSizeLabel(selection: OamDownloadSelection): String =
    buildList {
        if (this@toSizeLabel > 0L) add(formatBytes(this@toSizeLabel))
        if (selection.includeRouting) add("routing")
        if (selection.includeDem) add("${selection.demSource.shortLabel} elevation")
        if (selection.includeRefugesInfo) add("Refuges.info")
    }.joinToString(" + ").ifBlank {
        formatBytes(this)
    }

private fun List<OamDownloadArea>.selectedAreaLabel(): String =
    when (size) {
        0 -> "Pick area"
        1 -> first().region
        2 -> joinToString(" + ") { it.region }
        else -> "$size areas selected"
    }

private fun List<OamDownloadArea>.selectedAreaSecondaryLabel(): String =
    when (size) {
        0 -> "No area selected"
        1 -> "1 area selected"
        else -> "$size areas selected"
    }

@Composable
private fun StatusText(
    text: String,
    error: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
            },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun DownloadChip(
    label: String,
    secondaryLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
    warning: Boolean = false,
    secondaryMarquee: Boolean = false,
) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            Text(
                text = secondaryLabel,
                modifier = if (secondaryMarquee) Modifier.basicMarquee() else Modifier,
                maxLines = 1,
                overflow = if (secondaryMarquee) TextOverflow.Visible else TextOverflow.Ellipsis,
            )
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ChipDefaults.IconSize),
            )
        },
        colors =
            ChipDefaults.secondaryChipColors(
                backgroundColor =
                    when {
                        warning -> WarningChipBackground
                        selected -> SelectedChipBackground
                        else -> ChipBackground
                    },
                contentColor = ChipContent,
                secondaryContentColor = ChipSecondaryContent,
                iconColor =
                    when {
                        warning -> WarningChipIcon
                        selected -> SelectedChipIcon
                        else -> ChipIcon
                    },
            ),
        onClick = onClick,
    )
}

private fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1024L) return "$safeBytes B"
    val kib = safeBytes / 1024.0
    if (kib < 1024.0) return "${kib.formatOneDecimal()} KB"
    val mib = kib / 1024.0
    if (mib < 1024.0) return "${mib.formatOneDecimal()} MB"
    return "${(mib / 1024.0).formatOneDecimal()} GB"
}

private fun Double.formatOneDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)

private val ChipBackground = Color(0xFF222A33)
private val SelectedChipBackground = Color(0xFF1F4656)
private val ChipContent = Color(0xFFF4F7FB)
private val ChipSecondaryContent = Color(0xFFC7D2DE)
private val ChipIcon = Color(0xFF9DB1C7)
private val SelectedChipIcon = Color(0xFF7FE4C8)
private val WarningChipBackground = Color(0xFF5A3B16)
private val WarningChipIcon = Color(0xFFFFB95C)

private const val DOWNLOAD_INFO_PREFS = "download_screen_info_prefs"
private const val DOWNLOAD_INFO_SHOWN_KEY = "oam_info_shown"
private const val DOWNLOAD_MAIN_ACTION_ITEM_INDEX = 2
private const val DOWNLOAD_FIRST_REFRESH_BUNDLE_ITEM_INDEX = 0
