@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooManyFunctions",
)
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.presentation.features.maps.theme.ThemeViewModel
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolBusySpinner
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.DeleteConfirmationDialog
import com.glancemap.glancemapwearos.presentation.ui.FeatureListHeader
import com.glancemap.glancemapwearos.presentation.ui.FeatureListScaffold
import com.glancemap.glancemapwearos.presentation.ui.RenameValueDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearDataDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.items as foundationItems
import androidx.compose.foundation.lazy.itemsIndexed as foundationItemsIndexed

private val MAP_DATA_BADGE_SIZE = 26.dp
private val MAP_DATA_ICON_SIZE = 15.dp
private val MAP_ROUTING_BADGE_SLOT_WIDTH = 22.dp
private val MAP_DEM_BADGE_SLOT_WIDTH = 22.dp

internal enum class DemMapBadgeState {
    DOWNLOADING,
    READY,
    PARTIAL,
    UNAVAILABLE,
}

internal fun demMapBadgeState(
    mapFile: MapFileState,
    isDemDownloadingForThisMap: Boolean,
): DemMapBadgeState =
    when {
        isDemDownloadingForThisMap -> DemMapBadgeState.DOWNLOADING
        mapFile.demReady -> DemMapBadgeState.READY
        mapFile.demCoverageKnown && mapFile.demAvailableTiles > 0 -> DemMapBadgeState.PARTIAL
        else -> DemMapBadgeState.UNAVAILABLE
    }

internal fun isDemMapBadgeEnabled(
    badgeState: DemMapBadgeState,
    isDemDownloadRunning: Boolean,
): Boolean =
    when (badgeState) {
        DemMapBadgeState.DOWNLOADING -> true
        DemMapBadgeState.READY -> false
        DemMapBadgeState.PARTIAL,
        DemMapBadgeState.UNAVAILABLE,
        -> !isDemDownloadRunning
    }

@Composable
fun MapsScreen(
    navController: NavHostController,
    mapViewModel: MapViewModel,
    themeViewModel: ThemeViewModel,
) {
    val resources = LocalResources.current
    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val mapFiles by mapViewModel.mapFiles.collectAsState()
    val routingPackFiles by mapViewModel.routingPackFiles.collectAsState()
    val demTileFiles by mapViewModel.demTileFiles.collectAsState()
    val selectedMapPath by mapViewModel.selectedMapPath.collectAsState()
    val demDownloadState by themeViewModel.demDownloadUiState.collectAsState()
    val selectedDemSource by themeViewModel.demSource.collectAsState()

    // Explicit state (no "by" delegation to avoid the error you saw)
    val showDeleteDialogState = remember { mutableStateOf(false) }
    val mapToDeleteState = remember { mutableStateOf<MapFileState?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var isRenameMode by remember { mutableStateOf(false) }
    var visibleDemStatusMessage by remember { mutableStateOf("") }
    var lastShownDemCompletionAt by remember { mutableStateOf(demDownloadState.lastCompletedAtMillis) }
    var showDemNetworkErrorDialog by remember { mutableStateOf(false) }
    var demNetworkErrorMessage by remember { mutableStateOf("") }
    var mapToRename by remember { mutableStateOf<MapFileState?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInProgress by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var showDemDataDialog by remember { mutableStateOf(false) }
    var demTileToDelete by remember { mutableStateOf<DemTileFileState?>(null) }
    var demSourceToDeleteAll by remember { mutableStateOf<DemSource?>(null) }
    var showRoutingDataDialog by remember { mutableStateOf(false) }
    var routingPackToDelete by remember { mutableStateOf<RoutingPackFileState?>(null) }
    var showDeleteAllRoutingDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val helpPrefs =
        remember(context) {
            context.getSharedPreferences(MAPS_HELP_PREFS, android.content.Context.MODE_PRIVATE)
        }
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
    val emptyStatePadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val settingsButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val bottomActionBottomPadding = 0.dp
    val bottomActionVisualOffsetY =
        when (screenSize) {
            WearScreenSize.LARGE -> (-6).dp
            WearScreenSize.MEDIUM -> (-5).dp
            WearScreenSize.SMALL -> (-4).dp
        }
    val rowSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 7.dp
            WearScreenSize.SMALL -> 6.dp
        }
    val headerTopSafePadding = headerTopPadding + adaptive.headerTopSafeInset

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(helpPrefs) {
        if (!helpPrefs.getBoolean(MAPS_HELP_SHOWN_KEY, false)) {
            showHelpDialog = true
        }
    }
    // 🔁 Ensure we always reload when this screen is first shown
    LaunchedEffect(Unit) {
        mapViewModel.loadMapFiles()
    }
    LaunchedEffect(mapFiles.size) {
        if (mapFiles.isEmpty()) {
            isDeleteMode = false
            isRenameMode = false
        }
    }
    LaunchedEffect(demDownloadState.lastCompletedAtMillis) {
        if (demDownloadState.lastCompletedAtMillis <= 0L) return@LaunchedEffect
        mapViewModel.loadMapFiles(preserveExistingCoverage = false)
        if (demDownloadState.downloadedTiles > 0) {
            mapViewModel.refreshMapLayer()
        }
    }
    LaunchedEffect(
        demDownloadState.isDownloading,
        demDownloadState.statusMessage,
        demDownloadState.lastCompletedAtMillis,
    ) {
        val message = demDownloadState.statusMessage
        if (message.isBlank()) {
            visibleDemStatusMessage = ""
            return@LaunchedEffect
        }
        if (demDownloadState.isDownloading) {
            visibleDemStatusMessage = message
            return@LaunchedEffect
        }
        val completedAt = demDownloadState.lastCompletedAtMillis
        if (completedAt <= 0L || completedAt <= lastShownDemCompletionAt) {
            visibleDemStatusMessage = ""
            return@LaunchedEffect
        }
        lastShownDemCompletionAt = completedAt
        if (demDownloadState.networkUnavailable) {
            demNetworkErrorMessage =
                if (message.isBlank()) {
                    resources.getString(
                        R.string.maps_dem_internet_required,
                    )
                } else {
                    message
                }
            showDemNetworkErrorDialog = true
            visibleDemStatusMessage = ""
            return@LaunchedEffect
        }
        visibleDemStatusMessage = message
        delay(5_000L)
        visibleDemStatusMessage = ""
    }

    fun dismissHelpDialog() {
        showHelpDialog = false
        helpPrefs.edit().putBoolean(MAPS_HELP_SHOWN_KEY, true).apply()
    }

    ScreenScaffold(scrollState = listState) {
        val showDeleteDialog = showDeleteDialogState.value
        val mapToDelete = mapToDeleteState.value

        DeleteConfirmationDialog(
            visible = showDeleteDialog && mapToDelete != null,
            title = stringResource(R.string.maps_delete_map),
            message = stringResource(R.string.gpx_delete, mapToDelete?.name.orEmpty()),
            onConfirm = {
                mapToDelete?.path?.let {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    mapViewModel.deleteMapFile(it)
                }
                showDeleteDialogState.value = false
                mapToDeleteState.value = null
            },
            onDismiss = {
                showDeleteDialogState.value = false
                mapToDeleteState.value = null
            },
        )

        RenameValueDialog(
            visible = showRenameDialog && mapToRename != null,
            title = stringResource(R.string.maps_rename_map),
            initialValue = mapToRename?.name?.substringBeforeLast(".map") ?: "",
            isSaving = renameInProgress,
            error = renameError,
            fullScreen = true,
            onDismiss = {
                if (!renameInProgress) {
                    showRenameDialog = false
                    mapToRename = null
                    renameError = null
                }
            },
            onConfirm = { newName ->
                val target = mapToRename ?: return@RenameValueDialog
                if (renameInProgress) return@RenameValueDialog
                renameInProgress = true
                renameError = null
                mapViewModel.renameMapFile(
                    filePath = target.path,
                    newName = newName,
                ) { result ->
                    renameInProgress = false
                    result
                        .onSuccess {
                            showRenameDialog = false
                            mapToRename = null
                            renameError = null
                        }.onFailure { error ->
                            renameError = error.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: resources.getString(R.string.maps_failed_to_rename_the_map)
                        }
                }
            },
        )

        WearActionDialog(
            visible = showDemNetworkErrorDialog,
            title = stringResource(R.string.maps_dem_download_failed),
            message =
                demNetworkErrorMessage.ifBlank {
                    stringResource(R.string.maps_dem_internet_required)
                },
            confirmText = stringResource(R.string.common_ok),
            onConfirm = { showDemNetworkErrorDialog = false },
            onDismissRequest = { showDemNetworkErrorDialog = false },
        )

        DeleteConfirmationDialog(
            visible = routingPackToDelete != null,
            title = stringResource(R.string.maps_delete_routing_pack),
            message = routingPackToDelete?.name ?: "",
            onConfirm = {
                routingPackToDelete?.let { mapViewModel.deleteRoutingPackFile(it.path) }
                routingPackToDelete = null
            },
            onDismiss = { routingPackToDelete = null },
        )

        DeleteConfirmationDialog(
            visible = demTileToDelete != null,
            title = stringResource(R.string.maps_delete_dem_tile),
            message = demTileToDelete?.name ?: "",
            onConfirm = {
                demTileToDelete?.let { mapViewModel.deleteDemTileFile(it.path) }
                demTileToDelete = null
            },
            onDismiss = { demTileToDelete = null },
        )

        DeleteConfirmationDialog(
            visible = demSourceToDeleteAll != null,
            title = stringResource(R.string.maps_delete_dem_source),
            message =
                demSourceToDeleteAll?.let { source ->
                    stringResource(
                        R.string.maps_delete_all_dem_files_from_the_watch,
                        stringResource(source.displayNameRes),
                    )
                } ?: "",
            onConfirm = {
                demSourceToDeleteAll?.let { mapViewModel.deleteAllDemTileFiles(it) }
                demSourceToDeleteAll = null
            },
            onDismiss = { demSourceToDeleteAll = null },
        )

        DeleteConfirmationDialog(
            visible = showDeleteAllRoutingDialog,
            title = stringResource(R.string.maps_delete_all_routing_data),
            message = routingDeleteAllMessage(routingPackFiles, resources = resources),
            onConfirm = {
                mapViewModel.deleteAllRoutingPackFiles()
                showDeleteAllRoutingDialog = false
            },
            onDismiss = { showDeleteAllRoutingDialog = false },
        )

        WearDataDialog(
            visible = showRoutingDataDialog,
            title = stringResource(R.string.maps_routing_data),
            onDismiss = { showRoutingDataDialog = false },
            bottomAction =
                if (routingPackFiles.isNotEmpty()) {
                    {
                        Button(
                            onClick = { showDeleteAllRoutingDialog = true },
                            modifier =
                                Modifier
                                    .wrapContentWidth()
                                    .height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    text = stringResource(R.string.maps_delete_all),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                } else {
                    null
                },
        ) {
            item {
                Text(
                    text = routingPackSummary(routingPackFiles, resources = resources),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (routingPackFiles.isNotEmpty()) {
                foundationItems(routingPackFiles) { pack ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(
                                text = pack.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatRoutingStorageSize(pack.sizeBytes, resources = resources),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                        }
                        CompactIconHitTargetButton(
                            onClick = { routingPackToDelete = pack },
                            visualSize = 26.dp,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.maps_delete_routing_pack_2),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        val demDataHighFont = adaptive.fontScale > 1f
        WearDataDialog(
            visible = showDemDataDialog,
            title = stringResource(R.string.maps_elevation_data),
            onDismiss = { showDemDataDialog = false },
            viewportPadding =
                if (demDataHighFont) {
                    PaddingValues(top = 24.dp, bottom = 72.dp)
                } else {
                    PaddingValues(0.dp)
                },
        ) {
            val demDialogHorizontalPadding = if (demDataHighFont) 22.dp else 0.dp
            item {
                cappedFontScale(maxFontScale = 1.08f) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = demDialogHorizontalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.maps_for_shading_slope_and_altitude),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.maps_quality_for_new_downloads),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            foundationItemsIndexed(DemSource.entries) { index, source ->
                DemDialogDenseContent(highFont = demDataHighFont) {
                    val rowModifier =
                        Modifier.padding(
                            start = demDialogHorizontalPadding,
                            top = if (index == 0) 0.dp else 6.dp,
                            end = demDialogHorizontalPadding,
                            bottom = 0.dp,
                        )
                    DemQualityChoiceRow(
                        source = source,
                        selected = selectedDemSource == source,
                        onSelect = { themeViewModel.setDemSource(source) },
                        modifier = rowModifier,
                    )
                }
            }
            foundationItems(DemSource.entries) { source ->
                val files = demTileFiles.filter { it.source == source }
                DemDialogDenseContent(highFont = demDataHighFont) {
                    DemStorageSummaryRow(
                        source = source,
                        files = files,
                        onDeleteAll = { demSourceToDeleteAll = source },
                        modifier = Modifier.padding(horizontal = demDialogHorizontalPadding),
                    )
                }
            }
            if (mapFiles.isNotEmpty()) {
                item {
                    DemDialogDenseContent(highFont = demDataHighFont) {
                        Text(
                            text = stringResource(R.string.maps_map_coverage),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = demDialogHorizontalPadding)
                                    .padding(top = 2.dp),
                        )
                    }
                }
                foundationItems(mapFiles) { mapFile ->
                    DemDialogDenseContent(highFont = demDataHighFont) {
                        DemMapCoverageRow(
                            mapFile = mapFile,
                            modifier = Modifier.padding(horizontal = demDialogHorizontalPadding),
                        )
                    }
                }
            }
            if (demDataHighFont) {
                item {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }

        WearInfoDialog(
            visible = showHelpDialog,
            title = stringResource(R.string.maps_map_actions),
            onDismiss = { dismissHelpDialog() },
        ) {
            item {
                Text(
                    stringResource(R.string.maps_toggle_a_map_to_use_it_on_navigate),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(
                            R.string.maps_use_the_download_bundle_or_the_phone_app_to_send_map_and_routing_to_th,
                        ),
                    )
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(
                            R.string.maps_click_on_elevation_icon_to_download_elevation_that_adds_altitude_slope,
                        ),
                    )
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(stringResource(R.string.download_routing_enables_offline_route_calculation))
                }
            }
            item {
                Text(
                    stringResource(R.string.maps_grey_means_missing_amber_partial_green_ready),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FeatureListScaffold(horizontalAlignment = Alignment.Start) {
            FeatureListHeader(
                title = stringResource(R.string.download_maps),
                topPadding = headerTopSafePadding,
                bottomPadding = headerBottomPadding,
                actionSpacing = headerActionSpacing,
                verticalSpacing = headerVerticalSpacing,
                statusText =
                    when {
                        isRenameMode -> stringResource(R.string.gpx_rename_mode)
                        isDeleteMode -> stringResource(R.string.download_delete_mode)
                        else -> null
                    },
                statusColor =
                    if (isDeleteMode) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            ) {
                CompactIconHitTargetButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showHelpDialog = true
                    },
                    visualSize = headerActionButtonSize,
                    visualOffsetY = headerActionVisualOffsetY,
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.maps_map_actions_help),
                        modifier = Modifier.size(headerActionIconSize),
                    )
                }
                CompactIconHitTargetButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        mapViewModel.loadDemTileFiles()
                        showDemDataDialog = true
                    },
                    visualSize = headerActionButtonSize,
                    visualOffsetY = headerActionVisualOffsetY,
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = stringResource(R.string.maps_dem_data),
                        modifier = Modifier.size(headerActionIconSize),
                    )
                }
                CompactIconHitTargetButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        mapViewModel.loadRoutingPackFiles()
                        showRoutingDataDialog = true
                    },
                    visualSize = headerActionButtonSize,
                    visualOffsetY = headerActionVisualOffsetY,
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = stringResource(R.string.maps_routing_data),
                        modifier = Modifier.size(headerActionIconSize),
                    )
                }
            }

            // Middle list
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = listTopPadding,
                            start = listHorizontalPadding,
                            end = listHorizontalPadding,
                            bottom = listBottomPadding,
                        ),
                    anchorType = ScalingLazyListAnchorType.ItemStart,
                    autoCentering = null,
                ) {
                    if (mapFiles.isEmpty()) {
                        item {
                            Text(
                                text =
                                    stringResource(
                                        R.string.maps_empty_state_help,
                                    ),
                                modifier = Modifier.padding(emptyStatePadding),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    items(mapFiles) { mapFile ->
                        MapItem(
                            mapFile = mapFile,
                            isSelected = selectedMapPath == mapFile.path,
                            onToggle = { isChecked ->
                                mapViewModel.selectMapPath(
                                    if (isChecked) mapFile.path else null,
                                )
                            },
                            onRename = {
                                mapToRename = mapFile
                                showRenameDialog = true
                                renameError = null
                            },
                            onDelete = {
                                mapToDeleteState.value = mapFile
                                showDeleteDialogState.value = true
                            },
                            showDelete = isDeleteMode,
                            showRename = isRenameMode,
                            rowSpacing = rowSpacing,
                            isDemDownloadRunning = demDownloadState.isDownloading,
                            isDemDownloadingForThisMap =
                                demDownloadState.isDownloading &&
                                    demDownloadState.activeMapPath == mapFile.path,
                            onDownloadDem = {
                                themeViewModel.downloadDemForMap(mapFile.path)
                            },
                            onCancelDemDownload = {
                                themeViewModel.cancelDemDownload()
                            },
                        )
                    }
                }
            }

            val showDemStatusBlock =
                demDownloadState.isDownloading || visibleDemStatusMessage.isNotBlank()

            if (showDemStatusBlock) {
                val demStatusWidthFraction =
                    when {
                        !adaptive.isRound -> 1f
                        adaptive.fontScale > 1f -> 0.76f
                        else -> 0.86f
                    }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (demDownloadState.isDownloading) {
                        RouteToolBusySpinner(size = 30.dp)

                        val progressPercent = demDownloadState.progressPercent
                        if (progressPercent != null) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.map_dem_download_progress,
                                        progressPercent,
                                        demDownloadState.processedTiles,
                                        demDownloadState.totalTiles,
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    if (visibleDemStatusMessage.isNotBlank()) {
                        Text(
                            text = visibleDemStatusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(demStatusWidthFraction),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Bottom actions
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomActionBottomPadding),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (mapFiles.isNotEmpty() && !demDownloadState.isDownloading) {
                        CompactIconHitTargetButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val nextRenameMode = !isRenameMode
                                isRenameMode = nextRenameMode
                                if (nextRenameMode) {
                                    isDeleteMode = false
                                }
                            },
                            visualSize = headerActionButtonSize,
                            visualOffsetY = bottomActionVisualOffsetY,
                            containerColor =
                                if (isRenameMode) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Black.copy(alpha = 0.8f)
                                },
                            contentColor =
                                if (isRenameMode) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    Color.White
                                },
                        ) {
                            Icon(
                                imageVector = if (isRenameMode) Icons.Default.Close else Icons.Default.Edit,
                                contentDescription =
                                    if (isRenameMode) {
                                        stringResource(R.string.gpx_exit_rename_mode)
                                    } else {
                                        stringResource(R.string.gpx_enter_rename_mode)
                                    },
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                    CompactIconHitTargetButton(
                        onClick = { navController.navigate(WatchRoutes.MAP_SETTINGS) },
                        visualSize = settingsButtonSize,
                        visualOffsetY = bottomActionVisualOffsetY,
                        containerColor = Color.Black.copy(alpha = 0.8f),
                        contentColor = Color.White,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.maps_map_settings))
                    }
                    if (demDownloadState.isDownloading) {
                        CompactIconHitTargetButton(
                            onClick = { themeViewModel.cancelDemDownload() },
                            visualSize = headerActionButtonSize,
                            visualOffsetY = bottomActionVisualOffsetY,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.maps_cancel_elevation_download),
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                    if (mapFiles.isNotEmpty() && !demDownloadState.isDownloading) {
                        CompactIconHitTargetButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val nextDeleteMode = !isDeleteMode
                                isDeleteMode = nextDeleteMode
                                if (nextDeleteMode) {
                                    isRenameMode = false
                                }
                            },
                            visualSize = headerActionButtonSize,
                            visualOffsetY = bottomActionVisualOffsetY,
                            containerColor =
                                if (isDeleteMode) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    Color.Black.copy(alpha = 0.8f)
                                },
                            contentColor =
                                if (isDeleteMode) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    Color.White
                                },
                        ) {
                            Icon(
                                imageVector = if (isDeleteMode) Icons.Default.Close else Icons.Default.Delete,
                                contentDescription =
                                    if (isDeleteMode) {
                                        stringResource(R.string.download_exit_delete_mode)
                                    } else {
                                        stringResource(R.string.download_enter_delete_mode)
                                    },
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapItem(
    mapFile: MapFileState,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean,
    showRename: Boolean,
    rowSpacing: Dp,
    isDemDownloadRunning: Boolean,
    isDemDownloadingForThisMap: Boolean,
    onDownloadDem: () -> Unit,
    onCancelDemDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (showDelete || showRename) {
                Arrangement.spacedBy(rowSpacing)
            } else {
                Arrangement.Start
            },
    ) {
        SwitchButton(
            modifier = Modifier.weight(1f),
            checked = isSelected,
            onCheckedChange = onToggle,
            label = {
                Text(
                    text = mapFile.name,
                    modifier = Modifier.basicMarquee(),
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            },
        )

        if (showRename) {
            Button(
                onClick = onRename,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.gpx_rename),
                )
            }
        } else if (showDelete) {
            Button(
                onClick = onDelete,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                )
            }
        } else {
            val demBadgeState =
                demMapBadgeState(
                    mapFile = mapFile,
                    isDemDownloadingForThisMap = isDemDownloadingForThisMap,
                )
            val demIconTint =
                when (demBadgeState) {
                    DemMapBadgeState.DOWNLOADING -> Color(0xFF6EC8FF)
                    DemMapBadgeState.READY -> Color(0xFF76E36A)
                    DemMapBadgeState.PARTIAL -> Color(0xFFFFC857)
                    DemMapBadgeState.UNAVAILABLE -> Color(0xFF8E8E8E)
                }
            val routingIconTint =
                when {
                    !mapFile.routingCoverageKnown || mapFile.routingAvailableSegments == 0 -> Color(0xFF8E8E8E)
                    mapFile.routingReady -> Color(0xFF76E36A)
                    else -> Color(0xFFFFC857)
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.wrapContentWidth(),
            ) {
                Box(
                    modifier =
                        Modifier.size(
                            width = MAP_DEM_BADGE_SLOT_WIDTH,
                            height = 48.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactIconHitTargetButton(
                        onClick = {
                            when (demBadgeState) {
                                DemMapBadgeState.DOWNLOADING -> onCancelDemDownload()
                                DemMapBadgeState.READY -> Unit
                                DemMapBadgeState.PARTIAL,
                                DemMapBadgeState.UNAVAILABLE,
                                -> if (!isDemDownloadRunning) onDownloadDem()
                            }
                        },
                        enabled =
                            isDemMapBadgeEnabled(
                                badgeState = demBadgeState,
                                isDemDownloadRunning = isDemDownloadRunning,
                            ),
                        visualSize = MAP_DATA_BADGE_SIZE,
                        containerColor = Color.Black.copy(alpha = 0.72f),
                        contentColor = demIconTint,
                        disabledContainerColor = Color.Black.copy(alpha = 0.42f),
                        disabledContentColor = demIconTint,
                    ) {
                        when (demBadgeState) {
                            DemMapBadgeState.DOWNLOADING -> {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.maps_cancel_elevation_download),
                                    modifier = Modifier.size(MAP_DATA_ICON_SIZE),
                                )
                            }

                            DemMapBadgeState.READY -> {
                                Icon(
                                    imageVector = Icons.Default.Landscape,
                                    contentDescription = stringResource(R.string.maps_dem_downloaded),
                                    modifier = Modifier.size(MAP_DATA_ICON_SIZE),
                                )
                            }

                            DemMapBadgeState.PARTIAL,
                            DemMapBadgeState.UNAVAILABLE,
                            -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_dem_download),
                                    contentDescription =
                                        if (demBadgeState == DemMapBadgeState.PARTIAL) {
                                            stringResource(R.string.maps_download_remaining_dem)
                                        } else {
                                            stringResource(R.string.maps_download_dem)
                                        },
                                    modifier = Modifier.size(MAP_DATA_ICON_SIZE),
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier =
                        Modifier.size(
                            width = MAP_ROUTING_BADGE_SLOT_WIDTH,
                            height = MAP_DATA_BADGE_SIZE,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = stringResource(routingIconContentDescriptionRes(mapFile)),
                        modifier = Modifier.size(MAP_DATA_ICON_SIZE),
                        tint = routingIconTint,
                    )
                }
            }
        }
    }
}

@StringRes
private fun routingIconContentDescriptionRes(mapFile: MapFileState): Int =
    when {
        !mapFile.routingCoverageKnown -> R.string.map_routing_coverage_unavailable
        mapFile.routingRequiredSegments == 0 -> R.string.map_no_routing_packs_required
        mapFile.routingReady -> R.string.map_routing_ready
        mapFile.routingAvailableSegments > 0 -> R.string.map_routing_partially_available
        else -> R.string.map_routing_unavailable
    }

private fun routingPackSummary(
    packs: List<RoutingPackFileState>,
    resources: Resources,
): String {
    if (packs.isEmpty()) return resources.getString(R.string.map_no_routing_packs)
    val totalBytes = packs.sumOf { it.sizeBytes }
    return resources.getQuantityString(
        R.plurals.map_routing_pack_summary,
        packs.size,
        packs.size,
        formatRoutingStorageSize(totalBytes, resources),
    )
}

private fun routingDeleteAllMessage(
    packs: List<RoutingPackFileState>,
    resources: Resources,
): String {
    if (packs.isEmpty()) return resources.getString(R.string.map_no_routing_packs_installed)
    return resources.getString(R.string.map_deletes_from_the_watch, routingPackSummary(packs, resources = resources))
}

@Composable
private fun DemQualityChoiceRow(
    source: DemSource,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        colors =
            if (selected) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Landscape,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(source.displayNameRes),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(source.detailLabelRes),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.common_selected),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun DemStorageSummaryRow(
    source: DemSource,
    files: List<DemTileFileState>,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(source.displayNameRes),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = demTileSummary(files, resources = resources),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (files.isNotEmpty()) {
            CompactIconHitTargetButton(
                onClick = onDeleteAll,
                visualSize = 24.dp,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.maps_delete, stringResource(source.displayNameRes)),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun DemMapCoverageRow(
    mapFile: MapFileState,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = mapFile.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = demMapCoverageLabel(mapFile, resources = resources),
            style = MaterialTheme.typography.bodySmall,
            color = demMapCoverageColor(mapFile),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DemDialogDenseContent(
    highFont: Boolean,
    content: @Composable () -> Unit,
) {
    if (highFont) {
        cappedFontScale(maxFontScale = 1.08f, content = content)
    } else {
        content()
    }
}

private fun demMapCoverageLabel(
    mapFile: MapFileState,
    resources: Resources,
): String {
    if (!mapFile.demCombinedCoverageKnown) return resources.getString(R.string.map_unknown)
    if (mapFile.demCombinedRequiredTiles == 0) return resources.getString(R.string.map_no_elevation)
    if (mapFile.demDetailedAvailableTiles >=
        mapFile.demCombinedRequiredTiles
    ) {
        return resources.getString(R.string.map_detailed_ready)
    }
    if (mapFile.demCombinedAvailableTiles >= mapFile.demCombinedRequiredTiles) {
        return if (mapFile.demDetailedAvailableTiles > 0) {
            resources.getString(R.string.map_mixed_ready)
        } else {
            resources.getString(R.string.map_standard_ready)
        }
    }
    if (mapFile.demCombinedAvailableTiles > 0) {
        return resources.getString(
            R.string.common_count_ratio,
            mapFile.demCombinedAvailableTiles,
            mapFile.demCombinedRequiredTiles,
        )
    }
    return resources.getString(R.string.map_missing)
}

private fun demMapCoverageColor(mapFile: MapFileState): Color =
    when {
        !mapFile.demCombinedCoverageKnown -> Color.White.copy(alpha = 0.68f)
        mapFile.demCombinedRequiredTiles == 0 -> Color.White.copy(alpha = 0.68f)
        mapFile.demCombinedAvailableTiles >= mapFile.demCombinedRequiredTiles -> Color(0xFF76E36A)
        mapFile.demCombinedAvailableTiles > 0 -> Color(0xFFFFCA5C)
        else -> Color.White.copy(alpha = 0.68f)
    }

private fun demTileSummary(
    files: List<DemTileFileState>,
    resources: Resources,
): String {
    if (files.isEmpty()) return resources.getString(R.string.map_none_installed)
    val totalBytes = files.sumOf { it.sizeBytes }
    return resources.getQuantityString(
        R.plurals.dem_tile_storage_summary,
        files.size,
        files.size,
        formatRoutingStorageSize(totalBytes, resources),
    )
}

private fun formatRoutingStorageSize(
    bytes: Long,
    resources: Resources,
): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        safeBytes >= mb -> resources.getString(R.string.settings_mb_2, safeBytes / mb)
        safeBytes >= kb -> resources.getString(R.string.settings_kb, safeBytes / kb)
        else -> resources.getString(R.string.download_b, safeBytes.toLong())
    }
}

private const val MAPS_HELP_PREFS = "maps_screen_help_prefs"
private const val MAPS_HELP_SHOWN_KEY = "maps_help_shown"
