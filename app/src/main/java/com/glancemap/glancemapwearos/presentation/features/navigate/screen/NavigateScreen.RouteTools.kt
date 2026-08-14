package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.glancemap.glancemapwearos.core.routing.LoopRouteSuggestionException
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.data.repository.UserPoiRecord
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxViewModel
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker
import com.glancemap.glancemapwearos.presentation.features.poi.PoiViewModel
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteCreateMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteSaveBehavior
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolCreatePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolKind
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolLoopRetryOption
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolModifyPreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolOptions
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolPreflightResult
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSaveResult
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.features.routetools.buildLoopRetryOptions
import com.glancemap.glancemapwearos.presentation.features.routetools.preflightStart
import com.glancemap.glancemapwearos.presentation.features.routetools.previewBeforeSaving
import com.glancemap.glancemapwearos.presentation.features.routetools.withVisibleLoopDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

internal data class NavigateRouteToolActions(
    val openRouteToolsPanel: () -> Unit,
    val startPoiCreationSelection: () -> Unit,
    val savePoiAtCurrentMapCenter: () -> Unit,
    val savePoiAt: (LatLong) -> Unit,
    val startRouteToolSelection: (RouteToolSession) -> Unit,
    val undoRouteToolPoint: () -> Unit,
    val createRouteToPoi: (PoiOverlayMarker) -> Unit,
    val executeCreateDraft: (RouteToolSession, Boolean) -> Unit,
    val saveCreatePreview: () -> Unit,
    val refreshLoopPreview: () -> Unit,
    val executeModifyDraft: (RouteToolSession, Boolean) -> Unit,
    val captureRouteToolPoint: (LatLong) -> Unit,
)

private data class PendingDirectPoiRouteTriggers(
    val session: RouteToolSession?,
    val currentLocation: LatLong?,
    val gpsSignalSnapshot: GpsSignalSnapshot,
    val offlineMode: Boolean,
    val selectedMapPath: String?,
)

private data class PendingDirectPoiRouteActions(
    val evaluatePreflight: (RouteToolSession, LatLong?, GpsSignalSnapshot) -> RouteToolPreflightResult,
    val onReady: (RouteToolSession, LatLong, GpsSignalSnapshot) -> Unit,
    val onBlocked: (String?) -> Unit,
    val onTimeout: (RouteToolSession) -> Unit,
)

@Composable
@Suppress("FunctionNaming")
private fun PendingDirectPoiRouteEffect(
    triggers: PendingDirectPoiRouteTriggers,
    actions: PendingDirectPoiRouteActions,
) {
    val latestActions = rememberUpdatedState(actions)
    LaunchedEffect(
        triggers.session,
        triggers.currentLocation,
        triggers.gpsSignalSnapshot.isLocationAvailable,
        triggers.gpsSignalSnapshot.lastFixElapsedRealtimeMs,
        triggers.gpsSignalSnapshot.lastFixAgeMs,
        triggers.offlineMode,
        triggers.selectedMapPath,
    ) {
        val pendingSession = triggers.session ?: return@LaunchedEffect
        val currentActions = latestActions.value
        val preflight =
            currentActions.evaluatePreflight(
                pendingSession,
                triggers.currentLocation,
                triggers.gpsSignalSnapshot,
            )
        when {
            preflight.canStart && triggers.currentLocation != null -> {
                logPoiRoutePreflight(
                    event = "pending_ready",
                    triggers = triggers,
                )
                currentActions.onReady(
                    pendingSession,
                    triggers.currentLocation,
                    triggers.gpsSignalSnapshot,
                )
            }

            !preflight.shouldRequestFreshLocation -> {
                logPoiRoutePreflight(
                    event = "pending_blocked",
                    triggers = triggers,
                    message = preflight.message,
                )
                currentActions.onBlocked(preflight.message)
            }

            else -> {
                logPoiRoutePreflight(
                    event = "pending_waiting",
                    triggers = triggers,
                )
                delay(DIRECT_POI_ROUTE_GPS_WAIT_TIMEOUT_MS)
                logPoiRoutePreflight(
                    event = "pending_timeout",
                    triggers = triggers,
                )
                latestActions.value.onTimeout(pendingSession)
            }
        }
    }
}

@Composable
internal fun rememberNavigateRouteToolActions(
    context: Context,
    scope: CoroutineScope,
    mapView: MapView,
    gpxViewModel: GpxViewModel,
    poiViewModel: PoiViewModel,
    locationViewModel: LocationViewModel,
    recenterTarget: LatLong?,
    gpsSignalSnapshot: GpsSignalSnapshot,
    offlineMode: Boolean,
    activeGpxDetailsCount: Int,
    selectedMapPath: String?,
    triggerHaptic: () -> Unit,
    routeToolOptions: RouteToolOptions,
    routeToolDefaultOptions: RouteToolOptions,
    setRouteToolOptions: (RouteToolOptions) -> Unit,
    routeToolSession: RouteToolSession?,
    setRouteToolSession: (RouteToolSession?) -> Unit,
    setCompletedRouteToolDraft: (RouteToolSession?) -> Unit,
    routeToolExecutionInProgress: Boolean,
    setRouteToolExecutionInProgress: (Boolean) -> Unit,
    setRouteToolExecutionStatus: (String?) -> Unit,
    setRouteToolExecutionMessage: (String?) -> Unit,
    setRouteToolLoopRetryOptions: (List<RouteToolLoopRetryOption>) -> Unit,
    setRouteToolResult: (RouteToolSaveResult?) -> Unit,
    setRouteToolRenameInProgress: (Boolean) -> Unit,
    setRouteToolRenameError: (String?) -> Unit,
    setRouteToolPreview: (RouteToolModifyPreview?) -> Unit,
    routeToolCreatePreview: RouteToolCreatePreview?,
    setRouteToolCreatePreview: (RouteToolCreatePreview?) -> Unit,
    routeToolCreatePreviewInProgress: Boolean,
    setRouteToolCreatePreviewInProgress: (Boolean) -> Unit,
    routeToolCreatePreviewMessage: String?,
    setRouteToolCreatePreviewMessage: (String?) -> Unit,
    setRouteToolPreflightMessage: (String?) -> Unit,
    setShortcutTrayExpanded: (Boolean) -> Unit,
    setShowRouteToolsPanel: (Boolean) -> Unit,
    setPoiCreationSelectionActive: (Boolean) -> Unit,
    createdPoiCreateInProgress: Boolean,
    setCreatedPoiCreateInProgress: (Boolean) -> Unit,
    setCreatedPoiPendingRename: (UserPoiRecord?) -> Unit,
    setCreatedPoiRenameError: (String?) -> Unit,
    setShowCreatedPoiRenameDialog: (Boolean) -> Unit,
): NavigateRouteToolActions {
    val pendingDirectPoiRoute = remember { mutableStateOf<RouteToolSession?>(null) }
    // POI popups can outlive the composition that opened them. Route actions therefore read
    // these values when invoked rather than using the GPS state captured when the popup opened.
    val latestRecenterTarget = rememberUpdatedState(recenterTarget)
    val latestGpsSignalSnapshot = rememberUpdatedState(gpsSignalSnapshot)
    val latestOfflineMode = rememberUpdatedState(offlineMode)
    val latestActiveGpxDetailsCount = rememberUpdatedState(activeGpxDetailsCount)
    val latestSelectedMapPath = rememberUpdatedState(selectedMapPath)
    val latestRouteToolOptions = rememberUpdatedState(routeToolOptions)
    val latestRouteToolDefaultOptions = rememberUpdatedState(routeToolDefaultOptions)

    fun clearRouteToolPreviewState() {
        setRouteToolPreview(null)
        setRouteToolCreatePreview(null)
        setRouteToolCreatePreviewMessage(null)
        setRouteToolCreatePreviewInProgress(false)
    }

    fun clearRouteToolExecutionFeedback() {
        setRouteToolExecutionStatus(null)
        setRouteToolExecutionMessage(null)
        setRouteToolPreflightMessage(null)
        setRouteToolLoopRetryOptions(emptyList())
    }

    fun clearRouteToolResultState() {
        setRouteToolRenameInProgress(false)
        setRouteToolRenameError(null)
        setRouteToolResult(null)
    }

    fun beginRouteToolExecution(status: String) {
        setRouteToolExecutionInProgress(true)
        setRouteToolExecutionStatus(status)
        setRouteToolExecutionMessage(null)
        setRouteToolPreflightMessage(null)
        setRouteToolLoopRetryOptions(emptyList())
        setRouteToolCreatePreviewInProgress(false)
    }

    fun finishRouteToolExecution() {
        setRouteToolExecutionInProgress(false)
        setRouteToolExecutionStatus(null)
    }

    fun previewModifyDraft(
        draft: RouteToolSession,
        @Suppress("UNUSED_PARAMETER") showProgressToast: Boolean,
    ) {
        if (routeToolExecutionInProgress) return
        beginRouteToolExecution("Previewing...")
        setRouteToolExecutionMessage(null)
        setRouteToolPreview(null)
        gpxViewModel.previewRouteToolModification(draft) { result ->
            finishRouteToolExecution()
            result
                .onSuccess { preview ->
                    setRouteToolExecutionMessage(null)
                    setRouteToolPreview(preview)
                    setCompletedRouteToolDraft(draft)
                }.onFailure { error ->
                    val message =
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Failed to preview the edited GPX."
                    setRouteToolExecutionMessage(message)
                    setRouteToolPreview(null)
                    setCompletedRouteToolDraft(draft)
                }
        }
    }

    fun previewCreateDraft(
        draft: RouteToolSession,
        fallbackSession: RouteToolSession?,
        @Suppress("UNUSED_PARAMETER") showProgressToast: Boolean = false,
    ) {
        val previousPreview = routeToolCreatePreview
        setRouteToolCreatePreviewInProgress(true)
        setRouteToolCreatePreviewMessage(null)
        setRouteToolExecutionMessage(null)
        setRouteToolLoopRetryOptions(emptyList())
        gpxViewModel.previewRouteToolCreation(
            session = draft,
            currentLocation = latestRecenterTarget.value,
        ) { result ->
            setRouteToolCreatePreviewInProgress(false)
            result
                .onSuccess { preview ->
                    setRouteToolCreatePreview(preview)
                    setRouteToolCreatePreviewMessage(null)
                    setCompletedRouteToolDraft(null)
                    setRouteToolSession(draft)
                }.onFailure { error ->
                    val message =
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Could not update the route."
                    if (draft.options.createMode == RouteCreateMode.LOOP_AROUND_HERE) {
                        if (previousPreview != null) {
                            setRouteToolCreatePreview(previousPreview)
                            setRouteToolCreatePreviewMessage(message)
                            setRouteToolSession(draft)
                        } else {
                            setRouteToolCreatePreview(null)
                            setRouteToolCreatePreviewMessage(null)
                            setRouteToolExecutionMessage(message)
                            setRouteToolLoopRetryOptions(
                                if (error is LoopRouteSuggestionException) {
                                    buildLoopRetryOptions(draft.options, error)
                                } else {
                                    emptyList()
                                },
                            )
                            setCompletedRouteToolDraft(draft)
                            setRouteToolSession(null)
                        }
                    } else {
                        setRouteToolCreatePreviewMessage(message)
                        if (fallbackSession != null) {
                            setRouteToolSession(fallbackSession)
                        }
                    }
                }
        }
    }

    LaunchedEffect(
        routeToolSession,
        recenterTarget,
        routeToolCreatePreview,
        routeToolCreatePreviewMessage,
        routeToolCreatePreviewInProgress,
        routeToolExecutionInProgress,
    ) {
        val current = routeToolSession ?: return@LaunchedEffect
        if (!current.isMultiPointCreate) return@LaunchedEffect
        if (current.chainPoints.size < 2) return@LaunchedEffect
        if (routeToolCreatePreview != null) return@LaunchedEffect
        if (routeToolCreatePreviewMessage != null) return@LaunchedEffect
        if (routeToolCreatePreviewInProgress || routeToolExecutionInProgress) return@LaunchedEffect
        previewCreateDraft(current, current)
    }

    fun openRouteToolsPanel() {
        pendingDirectPoiRoute.value = null
        triggerHaptic()
        setShortcutTrayExpanded(false)
        setPoiCreationSelectionActive(false)
        setRouteToolOptions(
            latestRouteToolOptions.value
                .copy(
                    routeStyle = latestRouteToolDefaultOptions.value.routeStyle,
                    useElevation = latestRouteToolDefaultOptions.value.useElevation,
                    allowFerries = latestRouteToolDefaultOptions.value.allowFerries,
                ).withVisibleLoopDefaults()
                .copy(saveBehavior = RouteSaveBehavior.SAVE_AS_NEW),
        )
        clearRouteToolExecutionFeedback()
        clearRouteToolResultState()
        clearRouteToolPreviewState()
        poiViewModel.clearOfflinePoiSearch()
        setShowRouteToolsPanel(true)
    }

    fun startPoiCreationSelection() {
        pendingDirectPoiRoute.value = null
        triggerHaptic()
        setShortcutTrayExpanded(false)
        setShowRouteToolsPanel(false)
        setRouteToolSession(null)
        clearRouteToolPreviewState()
        clearRouteToolExecutionFeedback()
        clearRouteToolResultState()
        setCreatedPoiCreateInProgress(false)
        setCreatedPoiPendingRename(null)
        setCreatedPoiRenameError(null)
        setShowCreatedPoiRenameDialog(false)
        poiViewModel.clearOfflinePoiSearch()
        gpxViewModel.dismissInspection()
        setPoiCreationSelectionActive(true)
    }

    fun savePoiAt(center: LatLong) {
        if (createdPoiCreateInProgress) return
        triggerHaptic()
        setCreatedPoiCreateInProgress(true)
        scope.launch {
            runCatching {
                poiViewModel.createMyCreationPoiAt(
                    lat = center.latitude,
                    lon = center.longitude,
                )
            }.onSuccess { record ->
                setCreatedPoiCreateInProgress(false)
                setCreatedPoiPendingRename(record)
                setCreatedPoiRenameError(null)
                setShowCreatedPoiRenameDialog(true)
            }.onFailure { error ->
                setCreatedPoiCreateInProgress(false)
                val message =
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Failed to save the POI."
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun savePoiAtCurrentMapCenter() {
        savePoiAt(mapView.model.mapViewPosition.center)
    }

    fun preflightRouteToolStart(
        session: RouteToolSession,
        currentLocation: LatLong? = latestRecenterTarget.value,
        gpsSignal: GpsSignalSnapshot = latestGpsSignalSnapshot.value,
    ): RouteToolPreflightResult =
        session.preflightStart(
            context = context,
            currentLocation = currentLocation,
            gpsSignalSnapshot = gpsSignal,
            isOfflineMode = latestOfflineMode.value,
            hasSingleActiveGpx = latestActiveGpxDetailsCount.value == 1,
            selectedMapPath = latestSelectedMapPath.value,
        )

    fun showRouteToolSaveSuccess(saveResult: RouteToolSaveResult) {
        setRouteToolExecutionMessage(null)
        setCompletedRouteToolDraft(null)
        setShortcutTrayExpanded(false)
        setRouteToolRenameInProgress(false)
        setRouteToolRenameError(null)
        setRouteToolPreview(null)
        setRouteToolResult(saveResult)
    }

    fun handleRouteToolCreationResult(
        session: RouteToolSession,
        result: Result<RouteToolSaveResult>,
    ) {
        finishRouteToolExecution()
        result
            .onSuccess(::showRouteToolSaveSuccess)
            .onFailure { error ->
                val message = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Failed to create the GPX."
                setRouteToolExecutionMessage(message)
                setRouteToolLoopRetryOptions(
                    if (
                        session.options.createMode == RouteCreateMode.LOOP_AROUND_HERE &&
                        error is LoopRouteSuggestionException
                    ) {
                        buildLoopRetryOptions(session.options, error)
                    } else {
                        emptyList()
                    },
                )
                setRouteToolResult(null)
                setCompletedRouteToolDraft(session)
            }
    }

    fun handleRouteToolModificationResult(
        session: RouteToolSession,
        result: Result<RouteToolSaveResult>,
    ) {
        finishRouteToolExecution()
        result
            .onSuccess(::showRouteToolSaveSuccess)
            .onFailure { error ->
                val message =
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Failed to save the edited GPX."
                setRouteToolExecutionMessage(message)
                setRouteToolResult(null)
                setCompletedRouteToolDraft(session)
            }
    }

    fun startCompletedCreateRouteTool(
        session: RouteToolSession,
        currentLocation: LatLong?,
    ) {
        if (session.options.createMode == RouteCreateMode.LOOP_AROUND_HERE) {
            if (!routeToolCreatePreviewInProgress && !routeToolExecutionInProgress) {
                setRouteToolSession(session)
                previewCreateDraft(
                    draft = session,
                    fallbackSession = null,
                    showProgressToast = true,
                )
            }
        } else if (!routeToolExecutionInProgress) {
            setRouteToolSession(null)
            beginRouteToolExecution("Finding route...")
            gpxViewModel.applyRouteToolCreation(
                session = session,
                currentLocation = currentLocation,
                onProgress = setRouteToolExecutionStatus,
            ) { result ->
                handleRouteToolCreationResult(session, result)
            }
        }
    }

    fun startCompletedModifyRouteTool(session: RouteToolSession) {
        setRouteToolSession(null)
        if (session.options.modifyMode.previewBeforeSaving) {
            previewModifyDraft(session, true)
        } else if (!routeToolExecutionInProgress) {
            beginRouteToolExecution("Saving GPX...")
            gpxViewModel.applyRouteToolModification(
                session = session,
                onProgress = setRouteToolExecutionStatus,
            ) { result ->
                handleRouteToolModificationResult(session, result)
            }
        }
    }

    fun startCompletedRouteToolSelection(
        session: RouteToolSession,
        currentLocation: LatLong?,
    ) {
        when {
            session.options.toolKind == RouteToolKind.CREATE ->
                startCompletedCreateRouteTool(session, currentLocation)
            session.options.saveBehavior == RouteSaveBehavior.SAVE_AS_NEW ->
                startCompletedModifyRouteTool(session)
            else -> {
                setRouteToolSession(null)
                setCompletedRouteToolDraft(session)
            }
        }
    }

    fun handleRouteToolPreflightBlock(preflight: RouteToolPreflightResult) {
        preflight.message?.let(setRouteToolPreflightMessage)
        if (preflight.shouldRequestFreshLocation) {
            locationViewModel.requestImmediateLocation(source = "ui_route_tool_preflight")
        }
    }

    fun resetRouteToolSelectionUi() {
        setRouteToolPreflightMessage(null)
        setShowRouteToolsPanel(false)
        setPoiCreationSelectionActive(false)
        setCompletedRouteToolDraft(null)
        setRouteToolExecutionMessage(null)
        setRouteToolLoopRetryOptions(emptyList())
        clearRouteToolResultState()
        clearRouteToolPreviewState()
        gpxViewModel.dismissInspection()
    }

    fun startRouteToolSelection(
        session: RouteToolSession,
        currentLocation: LatLong?,
        gpsSignal: GpsSignalSnapshot,
    ) {
        pendingDirectPoiRoute.value = null
        val preflight = preflightRouteToolStart(session, currentLocation, gpsSignal)
        if (!preflight.canStart) {
            handleRouteToolPreflightBlock(preflight)
        } else {
            resetRouteToolSelectionUi()
            if (session.isComplete) {
                startCompletedRouteToolSelection(session, currentLocation)
            } else {
                setRouteToolSession(session)
            }
        }
    }

    fun startRouteToolSelection(session: RouteToolSession) {
        startRouteToolSelection(
            session = session,
            currentLocation = latestRecenterTarget.value,
            gpsSignal = latestGpsSignalSnapshot.value,
        )
    }

    fun evaluatePendingPoiRoutePreflight(
        session: RouteToolSession,
        currentLocation: LatLong?,
        gpsSignal: GpsSignalSnapshot,
    ): RouteToolPreflightResult =
        preflightRouteToolStart(
            session = session,
            currentLocation = currentLocation,
            gpsSignal = gpsSignal,
        )

    fun startPendingPoiRoute(
        session: RouteToolSession,
        currentLocation: LatLong,
        gpsSignal: GpsSignalSnapshot,
    ) {
        pendingDirectPoiRoute.value = null
        startRouteToolSelection(
            session = session,
            currentLocation = currentLocation,
            gpsSignal = gpsSignal,
        )
    }

    fun showPendingPoiRouteBlocker(message: String?) {
        pendingDirectPoiRoute.value = null
        setRouteToolPreflightMessage(message)
        setShowRouteToolsPanel(true)
    }

    fun showPendingPoiRouteTimeout(session: RouteToolSession) {
        if (pendingDirectPoiRoute.value != session) return
        pendingDirectPoiRoute.value = null
        Toast
            .makeText(
                context,
                "Could not get a fresh GPS fix.",
                Toast.LENGTH_SHORT,
            ).show()
    }

    PendingDirectPoiRouteEffect(
        triggers =
            PendingDirectPoiRouteTriggers(
                session = pendingDirectPoiRoute.value,
                currentLocation = recenterTarget,
                gpsSignalSnapshot = gpsSignalSnapshot,
                offlineMode = offlineMode,
                selectedMapPath = selectedMapPath,
            ),
        actions =
            PendingDirectPoiRouteActions(
                evaluatePreflight = ::evaluatePendingPoiRoutePreflight,
                onReady = ::startPendingPoiRoute,
                onBlocked = ::showPendingPoiRouteBlocker,
                onTimeout = ::showPendingPoiRouteTimeout,
            ),
    )

    fun undoRouteToolPoint() {
        val current = routeToolSession ?: return
        if (!current.isMultiPointCreate) return
        if (routeToolCreatePreviewInProgress || routeToolExecutionInProgress) return

        val updated = current.removeLastChainPoint()
        if (updated === current) return

        setRouteToolSession(updated)
        setRouteToolCreatePreviewMessage(null)
        if (updated.chainPoints.size < 2) {
            setRouteToolCreatePreview(null)
            return
        }
        previewCreateDraft(updated, current)
    }

    fun createDirectPoiRouteSession(marker: PoiOverlayMarker): RouteToolSession {
        val createOptions =
            latestRouteToolOptions.value
                .copy(
                    toolKind = RouteToolKind.CREATE,
                    createMode = RouteCreateMode.CURRENT_TO_HERE,
                ).withVisibleLoopDefaults()
        setRouteToolOptions(createOptions)
        return RouteToolSession(
            options = createOptions,
            destination = LatLong(marker.lat, marker.lon),
        )
    }

    fun createRouteToPoi(marker: PoiOverlayMarker) {
        pendingDirectPoiRoute.value = null
        triggerHaptic()
        setShortcutTrayExpanded(false)
        setShowRouteToolsPanel(false)
        setRouteToolPreflightMessage(null)
        setPoiCreationSelectionActive(false)
        setCompletedRouteToolDraft(null)
        clearRouteToolExecutionFeedback()
        clearRouteToolResultState()
        clearRouteToolPreviewState()
        val currentLocation = latestRecenterTarget.value
        val gpsSignal = latestGpsSignalSnapshot.value
        val session = createDirectPoiRouteSession(marker)
        val preflight =
            preflightRouteToolStart(
                session = session,
                currentLocation = currentLocation,
                gpsSignal = gpsSignal,
            )
        logPoiRoutePreflight(
            event = "click_preflight",
            currentLocation = currentLocation,
            gpsSignal = gpsSignal,
            result =
                when {
                    preflight.canStart -> "ready"
                    preflight.shouldRequestFreshLocation -> "waiting"
                    else -> "blocked"
                },
            message = preflight.message,
        )
        when {
            preflight.canStart ->
                startRouteToolSelection(
                    session = session,
                    currentLocation = currentLocation,
                    gpsSignal = gpsSignal,
                )
            preflight.shouldRequestFreshLocation -> {
                pendingDirectPoiRoute.value = session
                locationViewModel.requestImmediateLocation(source = "ui_poi_to_here")
                Toast.makeText(context, "Waiting for GPS", Toast.LENGTH_SHORT).show()
            }
            else -> {
                setRouteToolPreflightMessage(preflight.message)
                setShowRouteToolsPanel(true)
            }
        }
    }

    fun executeCreateDraft(
        draft: RouteToolSession,
        @Suppress("UNUSED_PARAMETER") showProgressToast: Boolean,
        preview: RouteToolCreatePreview? = null,
    ) {
        if (routeToolExecutionInProgress) return
        beginRouteToolExecution(if (preview != null) "Saving GPX..." else "Finding route...")
        gpxViewModel.applyRouteToolCreation(
            session = draft,
            currentLocation = latestRecenterTarget.value,
            preview = preview,
            onProgress = setRouteToolExecutionStatus,
        ) { result ->
            finishRouteToolExecution()
            result
                .onSuccess { saveResult ->
                    setRouteToolExecutionMessage(null)
                    setCompletedRouteToolDraft(null)
                    setShortcutTrayExpanded(false)
                    setRouteToolRenameInProgress(false)
                    setRouteToolRenameError(null)
                    setRouteToolPreview(null)
                    setRouteToolCreatePreview(null)
                    setRouteToolCreatePreviewMessage(null)
                    setRouteToolResult(saveResult)
                }.onFailure { error ->
                    val message =
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Failed to create the GPX."
                    setRouteToolExecutionMessage(message)
                    setRouteToolLoopRetryOptions(
                        if (
                            draft.options.createMode == RouteCreateMode.LOOP_AROUND_HERE &&
                            error is LoopRouteSuggestionException
                        ) {
                            buildLoopRetryOptions(draft.options, error)
                        } else {
                            emptyList()
                        },
                    )
                    setRouteToolResult(null)
                    setCompletedRouteToolDraft(draft)
                }
        }
    }

    fun saveCreatePreview() {
        val current = routeToolSession ?: return
        val preview = routeToolCreatePreview ?: return
        if (current.options.toolKind != RouteToolKind.CREATE) return
        if (current.isMultiPointCreate && current.chainPoints.size < 2) return
        if (
            !current.isMultiPointCreate &&
            current.options.createMode != RouteCreateMode.LOOP_AROUND_HERE
        ) {
            return
        }
        if (routeToolCreatePreviewInProgress || routeToolExecutionInProgress) return
        setRouteToolSession(null)
        executeCreateDraft(current, true, preview)
    }

    fun refreshLoopPreview() {
        val current = routeToolSession ?: return
        if (current.options.toolKind != RouteToolKind.CREATE) return
        if (current.options.createMode != RouteCreateMode.LOOP_AROUND_HERE) return
        if (!current.isComplete) return
        if (routeToolCreatePreviewInProgress || routeToolExecutionInProgress) return
        val updated = current.advanceLoopVariation()
        setRouteToolSession(updated)
        setRouteToolExecutionMessage(null)
        setRouteToolLoopRetryOptions(emptyList())
        setCompletedRouteToolDraft(null)
        previewCreateDraft(
            draft = updated,
            fallbackSession = null,
            showProgressToast = true,
        )
    }

    fun executeModifyDraft(
        draft: RouteToolSession,
        @Suppress("UNUSED_PARAMETER") showProgressToast: Boolean,
    ) {
        if (routeToolExecutionInProgress) return
        beginRouteToolExecution("Saving GPX...")
        gpxViewModel.applyRouteToolModification(
            session = draft,
            onProgress = setRouteToolExecutionStatus,
        ) { result ->
            finishRouteToolExecution()
            result
                .onSuccess { saveResult ->
                    setRouteToolExecutionMessage(null)
                    setCompletedRouteToolDraft(null)
                    setShortcutTrayExpanded(false)
                    setRouteToolRenameInProgress(false)
                    setRouteToolRenameError(null)
                    setRouteToolPreview(null)
                    setRouteToolCreatePreview(null)
                    setRouteToolCreatePreviewMessage(null)
                    setRouteToolResult(saveResult)
                }.onFailure { error ->
                    val message =
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Failed to save the edited GPX."
                    setRouteToolExecutionMessage(message)
                    setRouteToolResult(null)
                    setCompletedRouteToolDraft(draft)
                }
        }
    }

    fun captureRouteToolPoint(point: LatLong) {
        val current = routeToolSession ?: return
        if (current.isMultiPointCreate) {
            if (routeToolCreatePreviewInProgress || routeToolExecutionInProgress) return
            val updated = current.captureSelection(point)
            if (updated.chainPoints.size == current.chainPoints.size) return
            setRouteToolSession(updated)
            setRouteToolCreatePreviewMessage(null)
            if (updated.chainPoints.size < 2) {
                setRouteToolCreatePreview(null)
                return
            }
            previewCreateDraft(updated, current)
            return
        }
        val updated = current.captureSelection(point)
        if (updated.isComplete) {
            setRouteToolExecutionStatus(null)
            setRouteToolExecutionMessage(null)
            setRouteToolLoopRetryOptions(emptyList())
            setRouteToolCreatePreview(null)
            setRouteToolCreatePreviewMessage(null)
            setRouteToolCreatePreviewInProgress(false)
            when {
                updated.options.toolKind == RouteToolKind.CREATE -> {
                    if (updated.options.createMode == RouteCreateMode.LOOP_AROUND_HERE) {
                        setRouteToolSession(updated)
                        previewCreateDraft(
                            draft = updated,
                            fallbackSession = null,
                            showProgressToast = true,
                        )
                        return
                    }
                    setRouteToolSession(null)
                    executeCreateDraft(updated, true)
                }

                updated.options.modifyMode.previewBeforeSaving -> {
                    setRouteToolSession(null)
                    previewModifyDraft(updated, true)
                }

                updated.options.saveBehavior == RouteSaveBehavior.SAVE_AS_NEW -> {
                    setRouteToolSession(null)
                    executeModifyDraft(updated, true)
                }

                else -> {
                    setRouteToolSession(null)
                    setCompletedRouteToolDraft(updated)
                }
            }
            return
        }
        setRouteToolSession(updated)
    }

    return NavigateRouteToolActions(
        openRouteToolsPanel = ::openRouteToolsPanel,
        startPoiCreationSelection = ::startPoiCreationSelection,
        savePoiAtCurrentMapCenter = ::savePoiAtCurrentMapCenter,
        savePoiAt = ::savePoiAt,
        startRouteToolSelection = { session -> startRouteToolSelection(session) },
        undoRouteToolPoint = ::undoRouteToolPoint,
        createRouteToPoi = ::createRouteToPoi,
        executeCreateDraft = ::executeCreateDraft,
        saveCreatePreview = ::saveCreatePreview,
        refreshLoopPreview = ::refreshLoopPreview,
        executeModifyDraft = ::executeModifyDraft,
        captureRouteToolPoint = ::captureRouteToolPoint,
    )
}

private fun logPoiRoutePreflight(
    event: String,
    triggers: PendingDirectPoiRouteTriggers,
    message: String? = null,
) {
    logPoiRoutePreflight(
        event = event,
        currentLocation = triggers.currentLocation,
        gpsSignal = triggers.gpsSignalSnapshot,
        message = message,
    )
}

private fun logPoiRoutePreflight(
    event: String,
    currentLocation: LatLong?,
    gpsSignal: GpsSignalSnapshot,
    result: String? = null,
    message: String? = null,
) {
    if (!DebugTelemetry.isEnabled()) return
    DebugTelemetry.log(
        POI_ROUTE_TELEMETRY_TAG,
        buildString {
            append("event=")
            append(event)
            result?.let {
                append(" result=")
                append(it)
            }
            append(" currentLocation=")
            append(currentLocation != null)
            append(" locationAvailable=")
            append(gpsSignal.isLocationAvailable)
            append(" fixElapsedMs=")
            append(gpsSignal.lastFixElapsedRealtimeMs)
            append(" fixAgeMs=")
            append(gpsSignal.lastFixAgeMs)
            message?.let {
                append(" message=")
                append(it.replace(' ', '_'))
            }
        },
    )
}

private const val DIRECT_POI_ROUTE_GPS_WAIT_TIMEOUT_MS = 12_000L
private const val POI_ROUTE_TELEMETRY_TAG = "PoiRoute"
