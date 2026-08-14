@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.gpx

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssistantDirection
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingRecapMetric
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingRecapMetricsGrid
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.moveLabelAfter
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingRecapMetric
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingRecapMetricsForSnapshot
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.DeleteConfirmationDialog
import com.glancemap.glancemapwearos.presentation.ui.FeatureListHeader
import com.glancemap.glancemapwearos.presentation.ui.FeatureListScaffold
import com.glancemap.glancemapwearos.presentation.ui.RenameValueDialog
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class GpxListMode(
    val storedPage: String,
    val headerTitle: String,
    val icon: ImageVector,
    val toggleContentDescription: String,
    val emptyStateMessage: String,
) {
    TRACKS(
        storedPage = SettingsRepository.GPX_LIST_PAGE_TRACKS,
        headerTitle = "GPX",
        icon = Icons.Default.Route,
        toggleContentDescription = "Show hike activities",
        emptyStateMessage = "Send GPX files from the companion phone app.",
    ),
    HIKE_ACTIVITIES(
        storedPage = SettingsRepository.GPX_LIST_PAGE_HIKE_ACTIVITIES,
        headerTitle = "Hike",
        icon = Icons.AutoMirrored.Filled.DirectionsRun,
        toggleContentDescription = "Show bike activities",
        emptyStateMessage = "Hike recordings will appear here.",
    ),
    BIKE_ACTIVITIES(
        storedPage = SettingsRepository.GPX_LIST_PAGE_BIKE_ACTIVITIES,
        headerTitle = "Bike",
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        toggleContentDescription = "Show GPX tracks",
        emptyStateMessage = "Bike recordings will appear here.",
    ),
    ;

    fun next(): GpxListMode =
        when (this) {
            TRACKS -> HIKE_ACTIVITIES
            HIKE_ACTIVITIES -> BIKE_ACTIVITIES
            BIKE_ACTIVITIES -> TRACKS
        }

    companion object {
        fun fromStoredPage(page: String): GpxListMode = values().firstOrNull { it.storedPage == page } ?: TRACKS
    }
}

@Composable
fun GpxScreen(
    navController: NavHostController,
    gpxViewModel: GpxViewModel,
    isMetric: Boolean,
    autoStartRecordingWithGuidance: Boolean = false,
    recordingActiveOrSaving: Boolean = false,
    onStartRecording: () -> Unit = {},
) {
    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val gpxFiles by gpxViewModel.gpxFiles.collectAsState()
    val lastVisitedGpxListPage by gpxViewModel.lastVisitedGpxListPage.collectAsState()
    val turnByTurnGuidanceSession by gpxViewModel.turnByTurnGuidanceSession.collectAsState()
    val elevationProfileUiState by gpxViewModel.elevationProfileUiState.collectAsState()
    val exportUiState by gpxViewModel.exportUiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var isSendMode by remember { mutableStateOf(false) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var isRenameMode by remember { mutableStateOf(false) }
    var selectedSendPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fileToDelete by remember { mutableStateOf<GpxFileState?>(null) }
    var fileToRename by remember { mutableStateOf<GpxFileState?>(null) }
    var activityDetailsFile by remember { mutableStateOf<GpxFileState?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInProgress by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var guidanceMessageTitle by remember { mutableStateOf<String?>(null) }
    var guidanceMessageBody by remember { mutableStateOf<String?>(null) }
    var navigateAfterGuidanceMessage by remember { mutableStateOf(false) }
    var guidanceStartingPath by remember { mutableStateOf<String?>(null) }
    val listMode = GpxListMode.fromStoredPage(lastVisitedGpxListPage)
    val showActivities = listMode != GpxListMode.TRACKS
    val visibleGpxFiles =
        remember(gpxFiles, listMode) {
            gpxFiles.filter { gpxFile ->
                when (listMode) {
                    GpxListMode.TRACKS -> !gpxFile.isActivity
                    GpxListMode.HIKE_ACTIVITIES ->
                        gpxFile.isActivity &&
                            gpxFile.activityProfile != SettingsRepository.ACTIVITY_PROFILE_BIKE
                    GpxListMode.BIKE_ACTIVITIES ->
                        gpxFile.isActivity &&
                            gpxFile.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
                }
            }
        }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val helpPrefs =
        remember(context) {
            context.getSharedPreferences(GPX_HELP_PREFS, android.content.Context.MODE_PRIVATE)
        }
    val listHorizontalPadding =
        if (showActivities) {
            when (screenSize) {
                WearScreenSize.LARGE -> 8.dp
                WearScreenSize.MEDIUM -> 6.dp
                WearScreenSize.SMALL -> 4.dp
            }
        } else {
            when (screenSize) {
                WearScreenSize.LARGE -> 16.dp
                WearScreenSize.MEDIUM -> 14.dp
                WearScreenSize.SMALL -> 12.dp
            }
        }
    val listTopPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 1.dp
            WearScreenSize.MEDIUM -> 0.dp
            WearScreenSize.SMALL -> 0.dp
        }
    val listBottomPadding =
        if (showActivities) {
            when (screenSize) {
                WearScreenSize.LARGE -> 14.dp
                WearScreenSize.MEDIUM -> 12.dp
                WearScreenSize.SMALL -> 10.dp
            }
        } else {
            when (screenSize) {
                WearScreenSize.LARGE -> 2.dp
                WearScreenSize.MEDIUM -> 1.dp
                WearScreenSize.SMALL -> 0.dp
            }
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
    val activityToggleButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 40.dp
            WearScreenSize.MEDIUM -> 38.dp
            WearScreenSize.SMALL -> 36.dp
        }
    val activityToggleIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 20.dp
            WearScreenSize.MEDIUM -> 19.dp
            WearScreenSize.SMALL -> 18.dp
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
    val singleGuidanceRowSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 2.dp
            WearScreenSize.MEDIUM -> 1.dp
            WearScreenSize.SMALL -> 0.dp
        }
    val secondaryTextSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val deleteButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 36.dp
            WearScreenSize.MEDIUM -> 34.dp
            WearScreenSize.SMALL -> 32.dp
        }
    val singleGuidanceButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 32.dp
            WearScreenSize.MEDIUM -> 31.dp
            WearScreenSize.SMALL -> 30.dp
        }
    val activityActionButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 26.dp
            WearScreenSize.MEDIUM -> 25.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val activityActionHitTargetSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 34.dp
            WearScreenSize.MEDIUM -> 33.dp
            WearScreenSize.SMALL -> 32.dp
        }
    val dialogTextTopPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 6.dp
            WearScreenSize.SMALL -> 4.dp
        }
    val dialogTextBottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val headerTopSafePadding = headerTopPadding + adaptive.headerTopSafeInset

    LaunchedEffect(helpPrefs) {
        if (!helpPrefs.getBoolean(GPX_HELP_SHOWN_KEY, false)) {
            showHelpDialog = true
        }
    }
    LaunchedEffect(gpxFiles.size) {
        if (gpxFiles.isEmpty()) {
            isSendMode = false
            isDeleteMode = false
            isRenameMode = false
            selectedSendPaths = emptySet()
        }
    }
    LaunchedEffect(gpxFiles) {
        val existingPaths = gpxFiles.mapTo(mutableSetOf()) { it.path }
        selectedSendPaths = selectedSendPaths.filterTo(mutableSetOf()) { it in existingPaths }
    }

    fun dismissHelpDialog() {
        showHelpDialog = false
        helpPrefs.edit().putBoolean(GPX_HELP_SHOWN_KEY, true).apply()
    }

    fun startGuidance(gpxFile: GpxFileState) {
        if (guidanceStartingPath != null) {
            DebugTelemetry.log(
                "TurnByTurnStart",
                "event=tap_ignored reason=already_starting activeFile=${guidanceStartingPath?.telemetryToken() ?: "na"} " +
                    "requestedFile=${gpxFile.path.telemetryToken()}",
            )
            return
        }
        val tapElapsedMs = SystemClock.elapsedRealtime()
        guidanceStartingPath = gpxFile.path
        DebugTelemetry.log(
            "TurnByTurnStart",
            "event=tap file=${gpxFile.path.telemetryToken()} title=${gpxFile.displayTitle.telemetryToken()} " +
                "autoStartRecording=$autoStartRecordingWithGuidance recordingActiveOrSaving=$recordingActiveOrSaving",
        )
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        gpxViewModel.startTurnByTurnGuidance(gpxFile.path) { result ->
            val elapsedMs = (SystemClock.elapsedRealtime() - tapElapsedMs).coerceAtLeast(0L)
            if (guidanceStartingPath == gpxFile.path) {
                guidanceStartingPath = null
            }
            DebugTelemetry.log(
                "TurnByTurnStart",
                "event=tap_complete success=${result.isSuccess} elapsedMs=$elapsedMs file=${gpxFile.path.telemetryToken()}",
            )
            result
                .onSuccess { startResult ->
                    if (autoStartRecordingWithGuidance && !recordingActiveOrSaving) {
                        onStartRecording()
                    }
                    val warning = startResult.warningMessage
                    if (warning != null) {
                        guidanceMessageTitle = "BRouter unavailable"
                        guidanceMessageBody = warning
                        navigateAfterGuidanceMessage = true
                    } else {
                        navController.navigate(WatchRoutes.NAVIGATE) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }.onFailure { error ->
                    guidanceMessageTitle = "Guidance failed"
                    guidanceMessageBody =
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "The GPX could not be started for guidance."
                    navigateAfterGuidanceMessage = false
                }
        }
    }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    LaunchedEffect(listMode) {
        listState.scrollToItem(0)
        DebugTelemetry.log(
            "GpxScreen",
            "event=scroll_top mode=${listMode.name}",
        )
    }

    ScreenScaffold(scrollState = listState) {
        DeleteConfirmationDialog(
            visible = showDeleteDialog,
            title = "Delete Track?",
            message = "Delete '${fileToDelete?.displayTitle.orEmpty()}'?",
            messageTopPadding = dialogTextTopPadding,
            messageBottomPadding = dialogTextBottomPadding,
            onConfirm = {
                fileToDelete?.path?.let {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    gpxViewModel.deleteGpxFile(it)
                }
                showDeleteDialog = false
                fileToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                fileToDelete = null
            },
        )

        RenameValueDialog(
            visible = showRenameDialog && fileToRename != null,
            title = "Rename GPX",
            initialValue = fileToRename?.displayTitle.orEmpty(),
            isSaving = renameInProgress,
            error = renameError,
            fullScreen = true,
            onDismiss = {
                if (!renameInProgress) {
                    showRenameDialog = false
                    fileToRename = null
                    renameError = null
                }
            },
            onConfirm = { newName ->
                val target = fileToRename ?: return@RenameValueDialog
                if (renameInProgress) return@RenameValueDialog
                renameInProgress = true
                renameError = null
                gpxViewModel.renameGpxFile(
                    filePath = target.path,
                    newName = newName,
                ) { result ->
                    renameInProgress = false
                    result
                        .onSuccess {
                            showRenameDialog = false
                            fileToRename = null
                            renameError = null
                        }.onFailure { error ->
                            renameError = error.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "Failed to rename the GPX."
                        }
                }
            },
        )

        elevationProfileUiState?.let { profile ->
            GpxElevationProfileDialog(
                profile = profile,
                isMetric = isMetric,
                onDismiss = gpxViewModel::dismissElevationProfile,
            )
        }

        GpxHelpBottomSheet(
            visible = showHelpDialog,
            onDismiss = { dismissHelpDialog() },
        )

        WearHelpDialog(
            visible = guidanceMessageBody != null,
            title = guidanceMessageTitle ?: "GPX guidance",
            onDismiss = {
                guidanceMessageTitle = null
                guidanceMessageBody = null
                val shouldNavigate = navigateAfterGuidanceMessage
                navigateAfterGuidanceMessage = false
                if (shouldNavigate) {
                    navController.navigate(WatchRoutes.NAVIGATE) {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            lines = listOf(guidanceMessageBody.orEmpty()),
        )

        ActivityDetailsDialog(
            gpxFile = activityDetailsFile,
            isMetric = isMetric,
            onDismiss = { activityDetailsFile = null },
        )

        FeatureListScaffold {
            FeatureListHeader(
                title = listMode.headerTitle,
                titleStyle =
                    if (adaptive.isCompact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                topPadding = headerTopSafePadding,
                bottomPadding = headerBottomPadding,
                actionSpacing = headerActionSpacing,
                verticalSpacing = headerVerticalSpacing,
                statusText =
                    when {
                        isSendMode && selectedSendPaths.isEmpty() -> "Select GPX"
                        isSendMode -> "${selectedSendPaths.size} selected"
                        isRenameMode -> "Rename mode"
                        isDeleteMode -> "Delete mode"
                        else -> null
                    },
                statusColor =
                    when {
                        isSendMode -> MaterialTheme.colorScheme.secondary
                        isRenameMode -> MaterialTheme.colorScheme.primary
                        isDeleteMode -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onBackground
                    },
                statusTopPadding = if (isSendMode) 8.dp else 0.dp,
            ) {
                if (gpxFiles.isNotEmpty()) {
                    CompactIconHitTargetButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            gpxViewModel.setLastVisitedGpxListPage(listMode.next().storedPage)
                            isSendMode = false
                            selectedSendPaths = emptySet()
                            isRenameMode = false
                            isDeleteMode = false
                        },
                        visualSize = activityToggleButtonSize,
                        visualOffsetY = headerActionVisualOffsetY,
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor =
                            if (showActivities) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White
                            },
                    ) {
                        Icon(
                            imageVector =
                                listMode.icon,
                            contentDescription = listMode.toggleContentDescription,
                            modifier = Modifier.size(activityToggleIconSize),
                        )
                    }
                }
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
                        contentDescription = "GPX actions help",
                        modifier = Modifier.size(headerActionIconSize),
                    )
                }
                if (gpxFiles.isNotEmpty()) {
                    CompactIconHitTargetButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val nextSendMode = !isSendMode
                            isSendMode = nextSendMode
                            if (nextSendMode) {
                                isRenameMode = false
                                isDeleteMode = false
                            } else {
                                selectedSendPaths = emptySet()
                            }
                        },
                        visualSize = headerActionButtonSize,
                        visualOffsetY = headerActionVisualOffsetY,
                        containerColor =
                            if (isSendMode) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Black.copy(alpha = 0.7f)
                            },
                        contentColor =
                            if (isSendMode) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                Color.White
                            },
                    ) {
                        if (isSendMode) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Exit send mode",
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_mobile_arrow_right),
                                contentDescription = "Send GPX to phone",
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                }
            }

            // Middle list (takes all remaining space)
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
                            start = listHorizontalPadding,
                            end = listHorizontalPadding,
                            top = listTopPadding,
                            bottom = listBottomPadding,
                        ),
                    anchorType = ScalingLazyListAnchorType.ItemStart,
                    autoCentering = null,
                ) {
                    if (visibleGpxFiles.isEmpty()) {
                        item {
                            Text(
                                text =
                                    listMode.emptyStateMessage,
                                modifier = Modifier.padding(emptyStatePadding),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    items(visibleGpxFiles, key = { it.path }) { gpxFile ->
                        GpxTrackItem(
                            gpxFile = gpxFile,
                            onToggle = {
                                // ignore bool, just let VM toggle internally
                                gpxViewModel.toggleGpxFile(gpxFile.path)
                            },
                            onDelete = {
                                fileToDelete = gpxFile
                                showDeleteDialog = true
                            },
                            onRename = {
                                fileToRename = gpxFile
                                showRenameDialog = true
                                renameError = null
                            },
                            onStartGuidance = {
                                startGuidance(gpxFile)
                            },
                            onStopGuidance = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                gpxViewModel.stopTurnByTurnGuidance()
                            },
                            onSend = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedSendPaths =
                                    if (gpxFile.path in selectedSendPaths) {
                                        selectedSendPaths - gpxFile.path
                                    } else {
                                        selectedSendPaths + gpxFile.path
                                    }
                            },
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                gpxViewModel.showElevationProfile(gpxFile.path)
                            },
                            onShowActivityDetails = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                activityDetailsFile = gpxFile
                            },
                            showSend = isSendMode,
                            isSendSelected = gpxFile.path in selectedSendPaths,
                            showDelete = isDeleteMode,
                            showRename = isRenameMode,
                            showGuidance = !isSendMode && !isDeleteMode && !isRenameMode,
                            isGuidanceActive = turnByTurnGuidanceSession?.trackId == gpxFile.path,
                            isGuidanceStarting = guidanceStartingPath == gpxFile.path,
                            exportState = exportUiState.takeIf { it.filePath == gpxFile.path },
                            isMetric = isMetric,
                            rowSpacing = rowSpacing,
                            singleGuidanceRowSpacing = singleGuidanceRowSpacing,
                            secondaryTextSize = secondaryTextSize,
                            deleteButtonSize = deleteButtonSize,
                            singleGuidanceButtonSize = singleGuidanceButtonSize,
                            activityActionButtonSize = activityActionButtonSize,
                            activityActionHitTargetSize = activityActionHitTargetSize,
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
                    if (gpxFiles.isNotEmpty()) {
                        CompactIconHitTargetButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val nextRenameMode = !isRenameMode
                                isRenameMode = nextRenameMode
                                if (nextRenameMode) {
                                    isSendMode = false
                                    selectedSendPaths = emptySet()
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
                                        "Exit rename mode"
                                    } else {
                                        "Enter rename mode"
                                    },
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                    CompactIconHitTargetButton(
                        onClick = {
                            if (isSendMode) {
                                val paths = selectedSendPaths.toList()
                                if (paths.isNotEmpty()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedSendPaths = emptySet()
                                    isSendMode = false
                                    gpxViewModel.sendGpxFilesToPhone(paths)
                                }
                            } else {
                                navController.navigate(WatchRoutes.GPX_SETTINGS)
                            }
                        },
                        enabled = !isSendMode || (selectedSendPaths.isNotEmpty() && exportUiState.isSending != true),
                        visualSize = settingsButtonSize,
                        visualOffsetY = bottomActionVisualOffsetY,
                        containerColor =
                            if (isSendMode) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Black.copy(alpha = 0.8f)
                            },
                        contentColor =
                            if (isSendMode) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                Color.White
                            },
                        disabledContainerColor = Color.Black.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.45f),
                    ) {
                        if (isSendMode) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mobile_arrow_right),
                                contentDescription = "Send selected GPX to phone",
                            )
                        } else {
                            Icon(Icons.Default.Settings, contentDescription = "GPX Settings")
                        }
                    }
                    if (gpxFiles.isNotEmpty()) {
                        CompactIconHitTargetButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val nextDeleteMode = !isDeleteMode
                                isDeleteMode = nextDeleteMode
                                if (nextDeleteMode) {
                                    isSendMode = false
                                    selectedSendPaths = emptySet()
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
                                        "Exit delete mode"
                                    } else {
                                        "Enter delete mode"
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
private fun ActivityDetailsDialog(
    gpxFile: GpxFileState?,
    isMetric: Boolean,
    onDismiss: () -> Unit,
) {
    if (gpxFile == null) return
    val (distanceValue, distanceUnit) = gpxFile.formattedDistance(isMetric)
    val (elevationValue, elevationUnit) = gpxFile.formattedElevation(isMetric)
    val (elevationLossValue, elevationLossUnit) = gpxFile.formattedElevationLoss(isMetric)
    val metrics =
        remember(gpxFile, isMetric) {
            activityDetailsMetrics(
                gpxFile = gpxFile,
                isMetric = isMetric,
                fallbackDistanceValue = distanceValue,
                fallbackDistanceUnit = distanceUnit,
                fallbackElevationValue = elevationValue,
                fallbackElevationUnit = elevationUnit,
                fallbackElevationLossValue = elevationLossValue,
                fallbackElevationLossUnit = elevationLossUnit,
            )
        }
    WearInfoDialog(
        visible = true,
        title = "Activity details",
        onDismiss = onDismiss,
        backgroundColor = Color.Black,
    ) {
        item {
            Text(
                text = gpxFile.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            RecordingRecapMetricsGrid(metrics = metrics)
        }
        item {
            Spacer(modifier = Modifier.height(56.dp))
        }
    }
}

private fun activityDetailsMetrics(
    gpxFile: GpxFileState,
    isMetric: Boolean,
    fallbackDistanceValue: String,
    fallbackDistanceUnit: String,
    fallbackElevationValue: String,
    fallbackElevationUnit: String,
    fallbackElevationLossValue: String,
    fallbackElevationLossUnit: String,
): List<RecordingRecapMetric> {
    val summary = gpxFile.activitySummary
    if (summary == null) {
        return listOf(
            recordingRecapMetric("Dist", fallbackDistanceValue, fallbackDistanceUnit),
            recordingRecapMetric("Time", gpxFile.formattedActivityDurationClock()),
            recordingRecapMetric("Elev +", fallbackElevationValue, fallbackElevationUnit),
            recordingRecapMetric("Elev -", fallbackElevationLossValue, fallbackElevationLossUnit),
        )
    }

    return recordingRecapMetricsForSnapshot(summary, isMetric)
        .moveLabelAfter(label = "HR (Avg)", afterLabel = "Elev -")
        .moveLabelAfter(label = "Max HR", afterLabel = "HR (Avg)")
}

private const val GPX_HELP_PREFS = "gpx_screen_help_prefs"
private const val GPX_HELP_SHOWN_KEY = "gpx_help_shown"

private fun String.telemetryToken(): String =
    substringAfterLast('/')
        .replace(Regex("\\s+"), "_")
        .take(100)

@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
private fun GpxTrackItem(
    gpxFile: GpxFileState,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onStartGuidance: () -> Unit,
    onStopGuidance: () -> Unit,
    onSend: () -> Unit,
    onLongPress: () -> Unit,
    onShowActivityDetails: () -> Unit,
    showSend: Boolean,
    isSendSelected: Boolean,
    showDelete: Boolean,
    showRename: Boolean,
    showGuidance: Boolean,
    isGuidanceActive: Boolean,
    isGuidanceStarting: Boolean,
    exportState: GpxExportUiState?,
    isMetric: Boolean,
    rowSpacing: Dp,
    singleGuidanceRowSpacing: Dp,
    secondaryTextSize: TextUnit,
    deleteButtonSize: Dp,
    singleGuidanceButtonSize: Dp,
    activityActionButtonSize: Dp,
    activityActionHitTargetSize: Dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val longPressHandler by rememberUpdatedState(onLongPress)
    val longPressTimeoutMs = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    var suppressNextToggle by remember(gpxFile.path) { mutableStateOf(false) }

    LaunchedEffect(interactionSource, gpxFile.path, longPressTimeoutMs) {
        var trackedPress: PressInteraction.Press? = null
        var longPressJob: Job? = null

        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    suppressNextToggle = false
                    trackedPress = interaction
                    longPressJob?.cancel()
                    longPressJob =
                        launch {
                            delay(longPressTimeoutMs)
                            if (trackedPress == interaction) {
                                suppressNextToggle = true
                                longPressHandler()
                            }
                        }
                }

                is PressInteraction.Release -> {
                    if (interaction.press == trackedPress) {
                        trackedPress = null
                        longPressJob?.cancel()
                        longPressJob = null
                    }
                }

                is PressInteraction.Cancel -> {
                    if (interaction.press == trackedPress) {
                        trackedPress = null
                        longPressJob?.cancel()
                        longPressJob = null
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            when {
                showGuidance && gpxFile.isActivity -> Arrangement.spacedBy(0.dp)
                showGuidance -> Arrangement.spacedBy(singleGuidanceRowSpacing)
                showSend || showDelete || showRename || showGuidance -> Arrangement.spacedBy(rowSpacing)
                else -> Arrangement.Start
            },
    ) {
        SwitchButton(
            modifier = Modifier.weight(1f),
            checked = if (showSend) isSendSelected else gpxFile.isActive,
            onCheckedChange = { checked ->
                if (suppressNextToggle) {
                    suppressNextToggle = false
                    return@SwitchButton
                }
                if (showSend) {
                    onSend()
                } else {
                    onToggle(checked)
                }
            },
            interactionSource = interactionSource,
            label = {
                Text(
                    text = gpxFile.displayTitle,
                    modifier = Modifier.basicMarquee(),
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            },
            secondaryLabel = {
                val (distValue, distUnit) = gpxFile.formattedDistance(isMetric)
                val (elevValue, elevUnit) = gpxFile.formattedElevation(isMetric)
                val eta = gpxFile.formattedEtaShort()
                val exportMessage = exportState?.message
                Text(
                    text = exportMessage ?: "$distValue $distUnit · D+ $elevValue $elevUnit · $eta",
                    modifier = Modifier.basicMarquee(),
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                    fontSize = secondaryTextSize,
                )
            },
        )

        if (showSend) {
            CompactIconHitTargetButton(
                onClick = onSend,
                enabled = exportState?.isSending != true,
                visualSize = deleteButtonSize,
                containerColor =
                    if (isSendSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Black.copy(alpha = 0.72f)
                    },
                contentColor =
                    if (isSendSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        Color.White
                    },
            ) {
                if (isSendSelected) {
                    Icon(Icons.Default.Check, contentDescription = "Selected for send")
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_mobile_arrow_right),
                        contentDescription = "Select for send",
                    )
                }
            }
        } else if (showRename) {
            CompactIconHitTargetButton(
                onClick = onRename,
                visualSize = deleteButtonSize,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Rename")
            }
        } else if (showDelete) {
            CompactIconHitTargetButton(
                onClick = onDelete,
                visualSize = deleteButtonSize,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        } else if (showGuidance) {
            if (gpxFile.isActivity) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactIconHitTargetButton(
                        onClick = onShowActivityDetails,
                        visualSize = activityActionButtonSize,
                        hitTargetSize = activityActionHitTargetSize,
                        containerColor = Color.Black.copy(alpha = 0.72f),
                        contentColor = Color.White,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = "Activity details",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    GuidanceActionButton(
                        isGuidanceActive = isGuidanceActive,
                        isGuidanceStarting = isGuidanceStarting,
                        visualSize = activityActionButtonSize,
                        hitTargetSize = activityActionHitTargetSize,
                        iconSize = 15.dp,
                        onStartGuidance = onStartGuidance,
                        onStopGuidance = onStopGuidance,
                    )
                }
            } else {
                GuidanceActionButton(
                    isGuidanceActive = isGuidanceActive,
                    isGuidanceStarting = isGuidanceStarting,
                    visualSize = singleGuidanceButtonSize,
                    iconSize = null,
                    onStartGuidance = onStartGuidance,
                    onStopGuidance = onStopGuidance,
                )
            }
        }
    }
}

@Composable
private fun GuidanceActionButton(
    isGuidanceActive: Boolean,
    isGuidanceStarting: Boolean,
    visualSize: Dp,
    hitTargetSize: Dp = 48.dp,
    iconSize: Dp?,
    onStartGuidance: () -> Unit,
    onStopGuidance: () -> Unit,
) {
    CompactIconHitTargetButton(
        onClick = {
            if (isGuidanceStarting) {
                return@CompactIconHitTargetButton
            } else if (isGuidanceActive) {
                onStopGuidance()
            } else {
                onStartGuidance()
            }
        },
        visualSize = visualSize,
        hitTargetSize = hitTargetSize,
        containerColor =
            when {
                isGuidanceStarting -> MaterialTheme.colorScheme.primaryContainer
                isGuidanceActive -> MaterialTheme.colorScheme.errorContainer
                else -> Color.Black.copy(alpha = 0.72f)
            },
        contentColor =
            when {
                isGuidanceStarting -> MaterialTheme.colorScheme.onPrimaryContainer
                isGuidanceActive -> MaterialTheme.colorScheme.onErrorContainer
                else -> Color.White
            },
    ) {
        Icon(
            imageVector =
                when {
                    isGuidanceActive -> Icons.Default.Stop
                    else -> Icons.AutoMirrored.Filled.AssistantDirection
                },
            contentDescription =
                when {
                    isGuidanceStarting -> "Starting GPX guidance"
                    isGuidanceActive -> "Stop GPX guidance"
                    else -> "Start GPX guidance"
                },
            modifier = iconSize?.let { Modifier.size(it) } ?: Modifier,
        )
    }
}
