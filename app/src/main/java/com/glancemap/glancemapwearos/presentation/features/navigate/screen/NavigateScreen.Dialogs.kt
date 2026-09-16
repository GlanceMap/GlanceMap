package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.UserPoiRecord
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxViewModel
import com.glancemap.glancemapwearos.presentation.features.poi.PoiSearchUiState
import com.glancemap.glancemapwearos.presentation.features.poi.PoiViewModel
import com.glancemap.glancemapwearos.presentation.features.routetools.CoordinateStep
import com.glancemap.glancemapwearos.presentation.features.routetools.CoordinateTextEntryDialog
import com.glancemap.glancemapwearos.presentation.features.routetools.CoordinateValueEditorRow
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteCreateMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolDraftSummaryDialog
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolKind
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolLoopRetryOption
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolOptions
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolProgressDialog
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolResultDialog
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSaveResult
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolsActionPanel
import com.glancemap.glancemapwearos.presentation.features.routetools.formatCoordinateValue
import com.glancemap.glancemapwearos.presentation.features.routetools.isValidRouteToolCoordinate
import com.glancemap.glancemapwearos.presentation.features.routetools.normalizeLongitude
import com.glancemap.glancemapwearos.presentation.features.routetools.withVisibleLoopDefaults
import com.glancemap.glancemapwearos.presentation.ui.RenameValueDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton
import com.glancemap.glancemapwearos.presentation.ui.WearFormDialog
import org.mapsforge.core.model.LatLong

@Composable
@Suppress("LongParameterList", "FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
internal fun NavigateScreenDialogsHost(
    showKeepAppOpenInfoDialog: Boolean,
    helpDialogMaxHeight: Dp,
    onContinueKeepAppOpen: () -> Unit,
    onDismissKeepAppOpen: () -> Unit,
    showNotificationPermissionDialog: Boolean,
    onContinueNotificationPermission: () -> Unit,
    onDismissNotificationPermission: () -> Unit,
    showPoiCreationChoiceDialog: Boolean,
    showPoiCoordinateEntryDialog: Boolean,
    onDismissPoiCreationChoiceDialog: () -> Unit,
    onOpenPoiCoordinateEntryDialog: () -> Unit,
    onDismissPoiCoordinateEntryDialog: () -> Unit,
    showCreatedPoiRenameDialog: Boolean,
    createdPoiPendingRename: UserPoiRecord?,
    createdPoiRenameInProgress: Boolean,
    createdPoiRenameError: String?,
    onDismissCreatedPoiRename: () -> Unit,
    onConfirmCreatedPoiRename: (String) -> Unit,
    createdPoiCreateInProgress: Boolean,
    showRouteToolsPanel: Boolean,
    activeGpxDetailsCount: Int,
    coordinateSeed: LatLong?,
    poiSearchState: PoiSearchUiState,
    routeToolOptions: RouteToolOptions,
    routeToolPreflightMessage: String?,
    onRouteToolOptionsChange: (RouteToolOptions) -> Unit,
    poiViewModel: PoiViewModel,
    routeToolActions: NavigateRouteToolActions,
    completedRouteToolDraft: RouteToolSession?,
    reshapePreviewInspectMode: Boolean,
    routeToolExecutionInProgress: Boolean,
    routeToolExecutionMessage: String?,
    routeToolExecutionStatus: String?,
    routeToolLoopRetryOptions: List<RouteToolLoopRetryOption>,
    onDismissDraftSummary: () -> Unit,
    onSetRouteToolOptions: (RouteToolOptions) -> Unit,
    onClearRouteToolExecutionMessage: () -> Unit,
    onClearRouteToolLoopRetryOptions: () -> Unit,
    routeToolResult: RouteToolSaveResult?,
    routeToolRenameInProgress: Boolean,
    routeToolRenameError: String?,
    isMetric: Boolean,
    recordingGpsEnabled: Boolean,
    gpxViewModel: GpxViewModel,
    onSetRouteToolResult: (RouteToolSaveResult?) -> Unit,
    onSetRouteToolRenameInProgress: (Boolean) -> Unit,
    onSetRouteToolRenameError: (String?) -> Unit,
    onDismissRouteToolsPanel: () -> Unit,
    onOpenGpxToolsSettings: () -> Unit,
    onRouteToolGuidanceStarted: () -> Unit,
) {
    var showGpsDeactivatedInfo by rememberSaveable { mutableStateOf(false) }

    NavigateKeepAppOpenDialog(
        visible = showKeepAppOpenInfoDialog,
        helpDialogMaxHeight = helpDialogMaxHeight,
        onContinue = onContinueKeepAppOpen,
        onDismiss = onDismissKeepAppOpen,
    )

    NavigateNotificationPermissionDialog(
        visible = showNotificationPermissionDialog,
        onContinue = onContinueNotificationPermission,
        onDismiss = onDismissNotificationPermission,
    )

    PoiCreationChoiceDialog(
        visible = showPoiCreationChoiceDialog,
        onPickOnMap = {
            onDismissPoiCreationChoiceDialog()
            routeToolActions.startPoiCreationSelection()
        },
        onCoordinates = onOpenPoiCoordinateEntryDialog,
        onDismiss = onDismissPoiCreationChoiceDialog,
    )

    PoiCoordinateEntryDialog(
        visible = showPoiCoordinateEntryDialog,
        coordinateSeed = coordinateSeed,
        onCreatePoi = { coordinate ->
            onDismissPoiCoordinateEntryDialog()
            routeToolActions.savePoiAt(coordinate)
        },
        onDismiss = onDismissPoiCoordinateEntryDialog,
    )

    NavigateCreatedPoiRenameDialog(
        visible = showCreatedPoiRenameDialog,
        pendingRename = createdPoiPendingRename,
        isSaving = createdPoiRenameInProgress,
        error = createdPoiRenameError,
        onDismiss = onDismissCreatedPoiRename,
        onConfirm = onConfirmCreatedPoiRename,
    )

    RouteToolProgressDialog(
        visible = createdPoiCreateInProgress,
        message = "Saving POI...",
        fullScreenBackground = true,
    )

    NavigateRouteToolDialogs(
        showRouteToolsPanel = showRouteToolsPanel,
        canModifyActiveGpx = activeGpxDetailsCount == 1,
        coordinateSeed = coordinateSeed,
        poiSearchState = poiSearchState,
        options = routeToolOptions,
        preflightMessage = routeToolPreflightMessage,
        onOptionsChange = onRouteToolOptionsChange,
        onSearchPoi = { query -> poiViewModel.searchOfflinePoi(query) },
        onClearPoiSearch = { poiViewModel.clearOfflinePoiSearch() },
        onDismissRouteToolsPanel = onDismissRouteToolsPanel,
        onOpenGpxToolsSettings = onOpenGpxToolsSettings,
        onStartRouteToolSelection = routeToolActions.startRouteToolSelection,
        completedRouteToolDraft = if (reshapePreviewInspectMode) null else completedRouteToolDraft,
        routeToolExecutionInProgress = routeToolExecutionInProgress,
        routeToolExecutionMessage = routeToolExecutionMessage,
        routeToolLoopRetryOptions = routeToolLoopRetryOptions,
        onDismissDraftSummary = onDismissDraftSummary,
        onConfirmCreateDraft =
            completedRouteToolDraft
                ?.takeIf { it.options.toolKind == RouteToolKind.CREATE }
                ?.let { draft ->
                    {
                        onClearRouteToolLoopRetryOptions()
                        if (draft.options.createMode == RouteCreateMode.LOOP_AROUND_HERE) {
                            routeToolActions.startRouteToolSelection(draft)
                        } else {
                            routeToolActions.executeCreateDraft(draft, false)
                        }
                    }
                },
        onConfirmModifyDraft =
            completedRouteToolDraft
                ?.takeIf { it.options.toolKind == RouteToolKind.MODIFY }
                ?.let { draft ->
                    {
                        routeToolActions.executeModifyDraft(draft, false)
                    }
                },
        onSelectLoopRetryOption = { retryOption ->
            val draft = completedRouteToolDraft ?: return@NavigateRouteToolDialogs
            onClearRouteToolExecutionMessage()
            onClearRouteToolLoopRetryOptions()
            onSetRouteToolOptions(retryOption.options.withVisibleLoopDefaults())
            routeToolActions.startRouteToolSelection(
                draft.copy(
                    options = retryOption.options,
                    loopVariationIndex = 0,
                ),
            )
        },
        routeToolProgressVisible =
            routeToolExecutionInProgress &&
                completedRouteToolDraft == null &&
                routeToolExecutionStatus != null,
        routeToolProgressMessage = routeToolExecutionStatus ?: "Working...",
        routeToolResult = routeToolResult,
        isMetric = isMetric,
        routeToolRenameInProgress = routeToolRenameInProgress,
        routeToolRenameError = routeToolRenameError,
        onDismissRouteToolResult = {
            if (!routeToolRenameInProgress) {
                onSetRouteToolResult(null)
                onSetRouteToolRenameError(null)
            }
        },
        onDeleteRouteToolResult = {
            val currentResult = routeToolResult ?: return@NavigateRouteToolDialogs
            if (routeToolRenameInProgress) return@NavigateRouteToolDialogs
            gpxViewModel.deleteGpxFile(currentResult.filePath)
            onSetRouteToolResult(null)
            onSetRouteToolRenameError(null)
        },
        onOpenRouteToolRename = {
            onSetRouteToolRenameError(null)
        },
        onConfirmRouteToolRename = { newName ->
            val currentResult = routeToolResult ?: return@NavigateRouteToolDialogs
            if (routeToolRenameInProgress) return@NavigateRouteToolDialogs
            onSetRouteToolRenameInProgress(true)
            onSetRouteToolRenameError(null)
            gpxViewModel.renameRouteToolResult(
                filePath = currentResult.filePath,
                newName = newName,
            ) { result ->
                onSetRouteToolRenameInProgress(false)
                result
                    .onSuccess { updatedResult ->
                        onSetRouteToolRenameError(null)
                        onSetRouteToolResult(updatedResult)
                    }.onFailure { error ->
                        onSetRouteToolRenameError(
                            error.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "Failed to rename the GPX.",
                        )
                    }
            }
        },
        onStartRouteToolGuidance = {
            val currentResult = routeToolResult ?: return@NavigateRouteToolDialogs
            if (routeToolRenameInProgress) return@NavigateRouteToolDialogs
            if (!recordingGpsEnabled) {
                showGpsDeactivatedInfo = true
                return@NavigateRouteToolDialogs
            }
            onSetRouteToolRenameError(null)
            gpxViewModel.startTurnByTurnGuidance(currentResult.filePath) { result ->
                result
                    .onSuccess {
                        onSetRouteToolResult(null)
                        onSetRouteToolRenameError(null)
                        onRouteToolGuidanceStarted()
                    }.onFailure { error ->
                        onSetRouteToolRenameError(
                            error.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "Failed to start guidance.",
                        )
                    }
            }
        },
    )

    WearActionDialog(
        visible = showGpsDeactivatedInfo,
        title = "GPS deactivated",
        message = "Activate GPS in Recording settings before starting guidance.",
        confirmText = "OK",
        onConfirm = { showGpsDeactivatedInfo = false },
        onDismissRequest = { showGpsDeactivatedInfo = false },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun PoiCreationChoiceDialog(
    visible: Boolean,
    onPickOnMap: () -> Unit,
    onCoordinates: () -> Unit,
    onDismiss: () -> Unit,
) {
    WearActionDialog(
        visible = visible,
        title = "Create POI",
        onDismissRequest = onDismiss,
        buttons =
            listOf(
                WearActionDialogButton(
                    text = "Pick on map",
                    onClick = onPickOnMap,
                ),
                WearActionDialogButton(
                    text = "Coordinates",
                    onClick = onCoordinates,
                ),
                WearActionDialogButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    role = WearActionButtonRole.Secondary,
                ),
            ),
    ) {}
}

private enum class PoiCoordinateEntryField(
    val title: String,
    val isLatitude: Boolean,
) {
    LATITUDE("Latitude", true),
    LONGITUDE("Longitude", false),
}

@Composable
@Suppress("FunctionNaming", "LongMethod")
private fun PoiCoordinateEntryDialog(
    visible: Boolean,
    coordinateSeed: LatLong?,
    onCreatePoi: (LatLong) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    var latitude by remember(visible) { mutableStateOf(coordinateSeed?.latitude) }
    var longitude by remember(visible) { mutableStateOf(coordinateSeed?.longitude) }
    var editingField by remember(visible) { mutableStateOf<PoiCoordinateEntryField?>(null) }
    val selectedField = editingField
    if (selectedField != null) {
        val value = if (selectedField.isLatitude) latitude else longitude
        CoordinateTextEntryDialog(
            visible = true,
            title = selectedField.title,
            initialValue = value?.let(::formatCoordinateValue).orEmpty(),
            isLatitude = selectedField.isLatitude,
            onConfirm = { updatedValue ->
                if (selectedField.isLatitude) {
                    latitude = updatedValue
                } else {
                    longitude = updatedValue
                }
                editingField = null
            },
            onDismiss = { editingField = null },
        )
        return
    }

    val endpoint =
        if (isValidRouteToolCoordinate(latitude, longitude)) {
            LatLong(latitude!!, longitude!!)
        } else {
            null
        }
    val step = CoordinateStep.ONE_THOUSANDTH
    WearFormDialog(
        visible = true,
        title = "Coordinates",
        onDismiss = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.92f),
    ) { formTokens ->
        CoordinateValueEditorRow(
            label = "Latitude",
            value = latitude,
            onEdit = { editingField = PoiCoordinateEntryField.LATITUDE },
            onDecrease = { latitude = latitude?.let { (it - step.delta).coerceAtLeast(-90.0) } },
            onIncrease = { latitude = latitude?.let { (it + step.delta).coerceAtMost(90.0) } },
            modifier = formTokens.controlModifier,
        )
        CoordinateValueEditorRow(
            label = "Longitude",
            value = longitude,
            onEdit = { editingField = PoiCoordinateEntryField.LONGITUDE },
            onDecrease = { longitude = longitude?.let { normalizeLongitude(it - step.delta) } },
            onIncrease = { longitude = longitude?.let { normalizeLongitude(it + step.delta) } },
            modifier = formTokens.controlModifier,
        )
        if (endpoint == null) {
            Text(
                text = "Enter valid coordinates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = { endpoint?.let(onCreatePoi) },
            enabled = endpoint != null,
            modifier = formTokens.controlModifier.heightIn(min = formTokens.buttonMinHeight),
        ) {
            Text("Create POI")
        }
        Button(
            onClick = onDismiss,
            modifier = formTokens.controlModifier.heightIn(min = formTokens.buttonMinHeight),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
            Text("Back")
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun NavigateKeepAppOpenDialog(
    visible: Boolean,
    @Suppress("UNUSED_PARAMETER")
    helpDialogMaxHeight: Dp,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    WearActionDialog(
        visible = visible,
        title = "Stay open",
        onDismissRequest = onDismiss,
        buttons =
            listOf(
                WearActionDialogButton(
                    text = "Continue",
                    onClick = onContinue,
                ),
                WearActionDialogButton(
                    text = "Later",
                    onClick = onDismiss,
                    role = WearActionButtonRole.Secondary,
                ),
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeepAppOpenInfoRow(
                imageVector = Icons.Filled.Visibility,
                text = "keeps GlanceMap active.",
            )
            KeepAppOpenInfoRow(
                imageVector = Icons.Filled.VisibilityOff,
                text = "lets the watch sleep and removes it from Recents.",
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun NavigateNotificationPermissionDialog(
    visible: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    WearActionDialog(
        visible = visible,
        title = "Notifications",
        onDismissRequest = onDismiss,
        buttons =
            listOf(
                WearActionDialogButton(
                    text = "Continue",
                    onClick = onContinue,
                ),
                WearActionDialogButton(
                    text = "Later",
                    onClick = onDismiss,
                    role = WearActionButtonRole.Secondary,
                ),
            ),
    ) {
        KeepAppOpenInfoRow(
            imageVector = Icons.Filled.Visibility,
            text = "Stay uses notifications while GlanceMap remains active.",
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun KeepAppOpenInfoRow(
    imageVector: ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
        )
    }
}

@Composable
internal fun NavigateCreatedPoiRenameDialog(
    visible: Boolean,
    pendingRename: UserPoiRecord?,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    RenameValueDialog(
        visible = visible && pendingRename != null,
        title = "Rename POI",
        initialValue = pendingRename?.name.orEmpty(),
        isSaving = isSaving,
        error = error,
        autoFocusInput = false,
        fullScreen = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun NavigateRouteToolDialogs(
    showRouteToolsPanel: Boolean,
    canModifyActiveGpx: Boolean,
    coordinateSeed: LatLong?,
    poiSearchState: PoiSearchUiState,
    options: RouteToolOptions,
    preflightMessage: String?,
    onOptionsChange: (RouteToolOptions) -> Unit,
    onSearchPoi: (String) -> Unit,
    onClearPoiSearch: () -> Unit,
    onDismissRouteToolsPanel: () -> Unit,
    onOpenGpxToolsSettings: () -> Unit,
    onStartRouteToolSelection: (RouteToolSession) -> Unit,
    completedRouteToolDraft: RouteToolSession?,
    routeToolExecutionInProgress: Boolean,
    routeToolExecutionMessage: String?,
    routeToolLoopRetryOptions: List<RouteToolLoopRetryOption>,
    onDismissDraftSummary: () -> Unit,
    onConfirmCreateDraft: (() -> Unit)?,
    onConfirmModifyDraft: (() -> Unit)?,
    onSelectLoopRetryOption: (RouteToolLoopRetryOption) -> Unit,
    routeToolProgressVisible: Boolean,
    routeToolProgressMessage: String,
    routeToolResult: RouteToolSaveResult?,
    isMetric: Boolean,
    routeToolRenameInProgress: Boolean,
    routeToolRenameError: String?,
    onDismissRouteToolResult: () -> Unit,
    onDeleteRouteToolResult: () -> Unit,
    onOpenRouteToolRename: () -> Unit,
    onConfirmRouteToolRename: (String) -> Unit,
    onStartRouteToolGuidance: () -> Unit,
) {
    RouteToolsActionPanel(
        visible = showRouteToolsPanel,
        canModifyActiveGpx = canModifyActiveGpx,
        coordinateSeed = coordinateSeed,
        poiSearchState = poiSearchState,
        options = options,
        preflightMessage = preflightMessage,
        onOptionsChange = onOptionsChange,
        onSearchPoi = onSearchPoi,
        onClearPoiSearch = onClearPoiSearch,
        onDismiss = onDismissRouteToolsPanel,
        onStartSelection = onStartRouteToolSelection,
        onOpenGpxToolsSettings = onOpenGpxToolsSettings,
    )

    RouteToolDraftSummaryDialog(
        visible = completedRouteToolDraft != null,
        session = completedRouteToolDraft,
        isExecuting = routeToolExecutionInProgress,
        executionMessage = routeToolExecutionMessage,
        loopRetryOptions = routeToolLoopRetryOptions,
        onDismiss = onDismissDraftSummary,
        onConfirmCreate = onConfirmCreateDraft,
        onConfirmModify = onConfirmModifyDraft,
        onSelectLoopRetryOption = onSelectLoopRetryOption,
    )

    RouteToolProgressDialog(
        visible = routeToolProgressVisible,
        message = routeToolProgressMessage,
    )

    RouteToolResultDialog(
        visible = routeToolResult != null,
        result = routeToolResult,
        isMetric = isMetric,
        renameInProgress = routeToolRenameInProgress,
        renameError = routeToolRenameError,
        onDismiss = onDismissRouteToolResult,
        onDelete = onDeleteRouteToolResult,
        onRenameOpen = onOpenRouteToolRename,
        onRenameConfirm = onConfirmRouteToolRename,
        onStartGuidance = onStartRouteToolGuidance,
    )
}
