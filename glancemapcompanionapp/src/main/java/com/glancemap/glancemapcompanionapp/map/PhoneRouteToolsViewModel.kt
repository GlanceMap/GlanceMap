package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import com.glancemap.glancemapcompanionapp.routing.PhoneBRouterRoutePlanner
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.routing.BRouterRoutePreset
import com.glancemap.trailcore.routing.BRouterRouteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PhoneRouteCreationMode {
    CURRENT_TO_DESTINATION,
    POINT_A_TO_B,
    MODIFY_ROUTE,
}

internal data class PhoneRouteToolsUiState(
    val isOpen: Boolean = false,
    val mode: PhoneRouteCreationMode? = null,
    val pointA: GeoPoint? = null,
    val pointB: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val editableRoutes: List<PhoneMapGpxItem> = emptyList(),
    val selectedRouteId: String? = null,
    val isRouting: Boolean = false,
    val message: String? = null,
    val savedRouteId: String? = null,
)

/** Owns the first phone route flow while keeping BRouter work off the Compose/map thread. */
@Suppress("TooManyFunctions") // Creation, modification, and dialog state transitions share one view-model boundary.
internal class PhoneRouteToolsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val planner = PhoneBRouterRoutePlanner(application)
    private val repository = RouteLibraryRepository(application)
    private val generalSettings = PhoneGeneralSettingsPreferences(application)
    private val _uiState = MutableStateFlow(PhoneRouteToolsUiState())
    val uiState: StateFlow<PhoneRouteToolsUiState> = _uiState.asStateFlow()

    fun open(editableRoutes: List<PhoneMapGpxItem> = emptyList()) {
        _uiState.value = PhoneRouteToolsUiState(isOpen = true, editableRoutes = editableRoutes)
    }

    fun chooseMode(mode: PhoneRouteCreationMode) {
        _uiState.value =
            PhoneRouteToolsUiState(
                isOpen = true,
                mode = mode,
                editableRoutes = _uiState.value.editableRoutes,
            )
    }

    fun selectRoute(routeId: String) {
        val state = _uiState.value
        if (state.editableRoutes.any { route -> route.id == routeId }) {
            _uiState.value = state.copy(selectedRouteId = routeId, message = null)
        }
    }

    fun selectMapPoint(point: GeoPoint) {
        val state = _uiState.value
        if (!state.isOpen || state.isRouting) return
        _uiState.value =
            when (state.mode) {
                PhoneRouteCreationMode.CURRENT_TO_DESTINATION -> state.copy(destination = point, message = null)
                PhoneRouteCreationMode.POINT_A_TO_B ->
                    when {
                        state.pointA == null -> state.copy(pointA = point, message = null)
                        state.pointB == null -> state.copy(pointB = point, message = null)
                        else -> state.copy(pointA = point, pointB = null, message = null)
                    }

                PhoneRouteCreationMode.MODIFY_ROUTE ->
                    when {
                        state.pointA == null -> state.copy(pointA = point, message = null)
                        state.pointB == null -> state.copy(pointB = point, message = null)
                        else -> state.copy(pointA = point, pointB = null, message = null)
                    }

                null -> state
            }
    }

    fun create(currentLocation: PhoneMapLocation?) {
        val state = _uiState.value
        if (!state.isOpen || state.isRouting) return
        val request = buildRequest(state, currentLocation) ?: return

        _uiState.value = state.copy(isRouting = true, message = null, savedRouteId = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { createAndSave(state, request) } }
            result
                .onSuccess { saved ->
                    _uiState.value =
                        PhoneRouteToolsUiState(
                            message = "Route saved: ${saved.selectedRoute?.title ?: "new GPX"}",
                            savedRouteId = saved.selectedRouteId,
                        )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value =
                        state.copy(
                            isRouting = false,
                            message = error.message ?: "Could not create route.",
                        )
                }
        }
    }

    private fun buildRequest(
        state: PhoneRouteToolsUiState,
        currentLocation: PhoneMapLocation?,
    ): BRouterRouteRequest? =
        runCatching {
            when (state.mode) {
                PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
                    BRouterRouteRequest(
                        origin = checkNotNull(currentLocation).toGeoPoint(),
                        destination = checkNotNull(state.destination),
                        preset = selectedRoutePreset(),
                    )

                PhoneRouteCreationMode.POINT_A_TO_B,
                PhoneRouteCreationMode.MODIFY_ROUTE,
                ->
                    BRouterRouteRequest(
                        origin = checkNotNull(state.pointA),
                        destination = checkNotNull(state.pointB),
                        preset = selectedRoutePreset(),
                    )

                null -> error("Choose a route type first.")
            }
        }.getOrElse { error ->
            _uiState.value = state.copy(message = error.message ?: "Select the route points first.")
            null
        }

    private suspend fun createAndSave(
        state: PhoneRouteToolsUiState,
        request: BRouterRouteRequest,
    ): RouteLibraryUiState =
        planner.createRoute(request).let { output ->
            val generated =
                if (state.mode == PhoneRouteCreationMode.MODIFY_ROUTE) {
                    val route =
                        state.editableRoutes.firstOrNull { item -> item.id == state.selectedRouteId }
                            ?: error("Choose a GPX route to modify.")
                    val modifiedPoints =
                        replacePhoneRouteSection(
                            original = route.track.points,
                            pointA = checkNotNull(state.pointA),
                            pointB = checkNotNull(state.pointB),
                            replacement = output.points,
                        )
                    output.copy(
                        title = "${route.displayName} modified",
                        fileName = "${route.id}-modified.gpx",
                        gpxBytes = encodePhoneRouteGpx("${route.displayName} modified", modifiedPoints),
                    )
                } else {
                    output
                }
            repository.saveGeneratedRoute(generated)
        }

    fun cancel() {
        _uiState.value = PhoneRouteToolsUiState()
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun PhoneMapLocation.toGeoPoint(): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)

    private fun selectedRoutePreset(): BRouterRoutePreset =
        if (generalSettings.load().activityProfile == PhoneActivityProfile.BIKE) {
            BRouterRoutePreset.BIKE_TOURING
        } else {
            BRouterRoutePreset.BALANCED_HIKE
        }
}
