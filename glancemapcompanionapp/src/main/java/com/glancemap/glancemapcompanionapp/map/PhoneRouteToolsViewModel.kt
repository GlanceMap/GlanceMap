package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import com.glancemap.glancemapcompanionapp.routing.PhoneBRouterRoutePlanner
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.routing.BRouterRouteOutput
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
    MULTI_POINT_CHAIN,
    EXTEND_ROUTE_TO_DESTINATION,
    COORDINATES,
    MODIFY_ROUTE,
}

internal enum class PhoneRouteModificationMode {
    RESHAPE_ROUTE,
    REPLACE_SECTION_A_TO_B,
    KEEP_ONLY_A_TO_B,
    TRIM_START_TO_HERE,
    TRIM_END_FROM_HERE,
    REVERSE_GPX,
}

internal data class PhoneRouteToolsUiState(
    val isOpen: Boolean = false,
    val mode: PhoneRouteCreationMode? = null,
    val modificationMode: PhoneRouteModificationMode = PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
    val pointA: GeoPoint? = null,
    val pointB: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val chainPoints: List<GeoPoint> = emptyList(),
    val coordinateLatitude: String = "",
    val coordinateLongitude: String = "",
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

    fun chooseModificationMode(mode: PhoneRouteModificationMode) {
        val state = _uiState.value
        if (!state.isOpen || state.mode != PhoneRouteCreationMode.MODIFY_ROUTE || state.isRouting) return
        _uiState.value =
            state.copy(
                modificationMode = mode,
                pointA = null,
                pointB = null,
                destination = null,
                message = null,
            )
    }

    fun updateCoordinates(
        latitude: String,
        longitude: String,
    ) {
        val state = _uiState.value
        if (!state.isOpen || state.isRouting) return
        _uiState.value =
            state.copy(
                coordinateLatitude = latitude,
                coordinateLongitude = longitude,
                message = null,
            )
    }

    fun selectRoute(routeId: String) {
        val state = _uiState.value
        if (state.editableRoutes.any { route -> route.id == routeId && route.isEditable }) {
            _uiState.value =
                if (state.selectedRouteId == routeId) {
                    state.copy(message = null)
                } else {
                    state.copy(
                        selectedRouteId = routeId,
                        pointA = null,
                        pointB = null,
                        destination = null,
                        message = null,
                    )
                }
        }
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "NestedBlockDepth",
    ) // Point selection is a finite mode/state transition and is kept atomic.
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
                        else -> state
                    }

                PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
                    if (state.chainPoints.size < PHONE_ROUTE_TOOLS_MAX_CHAIN_POINTS) {
                        state.copy(chainPoints = state.chainPoints + point, message = null)
                    } else {
                        state
                    }

                PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION ->
                    if (state.selectedRouteId == null) {
                        state
                    } else {
                        state.copy(destination = point, message = null)
                    }

                PhoneRouteCreationMode.MODIFY_ROUTE ->
                    if (state.selectedRouteId == null) {
                        state
                    } else {
                        when (state.modificationMode) {
                            PhoneRouteModificationMode.RESHAPE_ROUTE ->
                                when {
                                    state.pointA == null -> state.copy(pointA = point, message = null)
                                    state.destination == null -> state.copy(destination = point, message = null)
                                    else -> state
                                }

                            PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
                            PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
                            ->
                                when {
                                    state.pointA == null -> state.copy(pointA = point, message = null)
                                    state.pointB == null -> state.copy(pointB = point, message = null)
                                    else -> state
                                }

                            PhoneRouteModificationMode.TRIM_START_TO_HERE ->
                                if (state.pointA == null) {
                                    state.copy(pointA = point, message = null)
                                } else {
                                    state
                                }

                            PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
                                if (state.pointB == null) {
                                    state.copy(pointB = point, message = null)
                                } else {
                                    state
                                }

                            PhoneRouteModificationMode.REVERSE_GPX -> state
                        }
                    }

                PhoneRouteCreationMode.COORDINATES -> state
                null -> state
            }
    }

    fun resetMapPoints() {
        val state = _uiState.value
        if (!state.isOpen || state.isRouting) return
        _uiState.value =
            state.copy(
                pointA = null,
                pointB = null,
                destination = null,
                chainPoints = emptyList(),
                coordinateLatitude = "",
                coordinateLongitude = "",
                message = null,
            )
    }

    fun undoLastMapPoint() {
        val state = _uiState.value
        if (!state.isOpen || state.isRouting) return
        _uiState.value = state.undoLastMapPoint()
    }

    fun create(currentLocation: PhoneMapLocation?) {
        val state = _uiState.value
        if (!state.isOpen || state.isRouting) return
        val validationError = validate(state, currentLocation)
        if (validationError != null) {
            _uiState.value = state.copy(message = validationError)
            return
        }

        _uiState.value = state.copy(isRouting = true, message = null, savedRouteId = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { createAndSave(state, currentLocation) } }
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

    private fun validate(
        state: PhoneRouteToolsUiState,
        currentLocation: PhoneMapLocation?,
    ): String? =
        runCatching {
            when (state.mode) {
                PhoneRouteCreationMode.CURRENT_TO_DESTINATION -> {
                    requireNotNull(currentLocation) { "A current location is required." }
                    requireNotNull(state.destination) { "Pick a destination first." }
                }

                PhoneRouteCreationMode.POINT_A_TO_B -> {
                    requireNotNull(state.pointA) { "Pick point A first." }
                    requireNotNull(state.pointB) { "Pick point B first." }
                }

                PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
                    require(state.chainPoints.size >= MIN_CHAIN_POINTS) {
                        "Pick at least two points first."
                    }

                PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION -> {
                    val route = selectedEditableRoute(state)
                    require(route.track.points.size >= 2) { "The selected GPX has no editable route." }
                    requireNotNull(state.destination) { "Pick a destination first." }
                }

                PhoneRouteCreationMode.COORDINATES -> {
                    requireNotNull(currentLocation) { "A current location is required." }
                    state.coordinatesDestination()
                }

                PhoneRouteCreationMode.MODIFY_ROUTE -> {
                    val route = selectedEditableRoute(state)
                    require(route.track.points.size >= 2) { "The selected GPX has no editable route." }
                    when (state.modificationMode) {
                        PhoneRouteModificationMode.RESHAPE_ROUTE -> {
                            val anchor = requireNotNull(state.pointA) { "Select a route point first." }
                            val bend = requireNotNull(state.destination) { "Pick the new bend point first." }
                            require(anchor != bend) { "Select two different points." }
                            requireNearRoute(route, anchor)
                        }

                        PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
                        PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
                        -> {
                            val pointA = requireNotNull(state.pointA) { "Pick point A on the route first." }
                            val pointB = requireNotNull(state.pointB) { "Pick point B on the route first." }
                            require(pointA != pointB) { "Select two different points on the route." }
                            requireNearRoute(route, pointA)
                            requireNearRoute(route, pointB)
                            requireDifferentRoutePoints(route, pointA, pointB)
                        }

                        PhoneRouteModificationMode.TRIM_START_TO_HERE ->
                            requireNotNull(state.pointA) { "Pick the new start first." }

                        PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
                            requireNotNull(state.pointB) { "Pick the new end first." }

                        PhoneRouteModificationMode.REVERSE_GPX -> Unit
                    }
                }

                null -> error("Choose a route type first.")
            }
        }.exceptionOrNull()?.message

    private suspend fun createAndSave(
        state: PhoneRouteToolsUiState,
        currentLocation: PhoneMapLocation?,
    ): RouteLibraryUiState {
        val generated =
            when (state.mode) {
                PhoneRouteCreationMode.CURRENT_TO_DESTINATION,
                PhoneRouteCreationMode.POINT_A_TO_B,
                PhoneRouteCreationMode.MULTI_POINT_CHAIN,
                PhoneRouteCreationMode.COORDINATES,
                -> planner.createRoute(buildRequest(state, currentLocation))

                PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION -> {
                    val route = selectedEditableRoute(state)
                    val output =
                        planner.createRoute(
                            routeRequest(
                                origin =
                                    route.track.points
                                        .last()
                                        .location,
                                destination = checkNotNull(state.destination),
                            ),
                        )
                    editedOutput(
                        route = route,
                        points = mergePhoneRoutePoints(route.track.points, output.points),
                        suffix = "extended",
                    )
                }

                PhoneRouteCreationMode.MODIFY_ROUTE -> createModifiedRoute(state)
                null -> error("Choose a route type first.")
            }
        return repository.saveGeneratedRoute(generated)
    }

    private fun buildRequest(
        state: PhoneRouteToolsUiState,
        currentLocation: PhoneMapLocation?,
    ): BRouterRouteRequest =
        when (state.mode) {
            PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
                routeRequest(
                    origin = requireNotNull(currentLocation).toGeoPoint(),
                    destination = requireNotNull(state.destination),
                )

            PhoneRouteCreationMode.POINT_A_TO_B ->
                routeRequest(
                    origin = requireNotNull(state.pointA),
                    destination = requireNotNull(state.pointB),
                )

            PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
                routeRequest(
                    origin = state.chainPoints.first(),
                    destination = state.chainPoints.last(),
                    viaPoints = state.chainPoints.drop(1).dropLast(1),
                )

            PhoneRouteCreationMode.COORDINATES ->
                routeRequest(
                    origin = requireNotNull(currentLocation).toGeoPoint(),
                    destination = state.coordinatesDestination(),
                )

            else -> error("This route action does not use a single route request.")
        }

    private suspend fun createModifiedRoute(state: PhoneRouteToolsUiState): BRouterRouteOutput {
        val route = selectedEditableRoute(state)
        val original = route.track.points
        val points =
            when (state.modificationMode) {
                PhoneRouteModificationMode.RESHAPE_ROUTE -> reshapeRoute(route, state)

                PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B -> {
                    val output =
                        planner.createRoute(
                            routeRequest(
                                origin = routePoint(route, requireNotNull(state.pointA)),
                                destination = routePoint(route, requireNotNull(state.pointB)),
                            ),
                        )
                    replacePhoneRouteSection(
                        original = original,
                        pointA = checkNotNull(state.pointA),
                        pointB = checkNotNull(state.pointB),
                        replacement = output.points,
                    )
                }

                PhoneRouteModificationMode.KEEP_ONLY_A_TO_B ->
                    keepPhoneRouteSection(
                        original = original,
                        pointA = checkNotNull(state.pointA),
                        pointB = checkNotNull(state.pointB),
                    )

                PhoneRouteModificationMode.TRIM_START_TO_HERE ->
                    trimStartRoute(route, checkNotNull(state.pointA))

                PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
                    trimEndRoute(route, checkNotNull(state.pointB))

                PhoneRouteModificationMode.REVERSE_GPX -> reversePhoneRoute(original)
            }
        val suffix =
            when (state.modificationMode) {
                PhoneRouteModificationMode.RESHAPE_ROUTE -> "reshaped"
                PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B -> "rerouted"
                PhoneRouteModificationMode.KEEP_ONLY_A_TO_B -> "trimmed"
                PhoneRouteModificationMode.TRIM_START_TO_HERE -> "start-changed"
                PhoneRouteModificationMode.TRIM_END_FROM_HERE -> "end-changed"
                PhoneRouteModificationMode.REVERSE_GPX -> "reversed"
            }
        return editedOutput(route, points, suffix)
    }

    private suspend fun trimStartRoute(
        route: PhoneMapGpxItem,
        target: GeoPoint,
    ): List<TrailPoint> {
        val original = route.track.points
        val nearestIndex = nearestPhoneRoutePointIndex(original, target)
        val nearestPoint = original[nearestIndex].location
        val distance = haversineDistanceMeters(nearestPoint, target)
        if (distance <= ROUTE_ENDPOINT_SNAP_THRESHOLD_METERS) {
            return trimPhoneRouteStart(original, target)
        }
        val routed = planner.createRoute(routeRequest(origin = target, destination = nearestPoint))
        return mergePhoneRoutePoints(routed.points, original.drop(nearestIndex))
    }

    private suspend fun trimEndRoute(
        route: PhoneMapGpxItem,
        target: GeoPoint,
    ): List<TrailPoint> {
        val original = route.track.points
        val nearestIndex = nearestPhoneRoutePointIndex(original, target)
        val nearestPoint = original[nearestIndex].location
        val distance = haversineDistanceMeters(nearestPoint, target)
        if (distance <= ROUTE_ENDPOINT_SNAP_THRESHOLD_METERS) {
            return trimPhoneRouteEnd(original, target)
        }
        val routed = planner.createRoute(routeRequest(origin = nearestPoint, destination = target))
        return mergePhoneRoutePoints(original.take(nearestIndex + 1), routed.points)
    }

    private suspend fun reshapeRoute(
        route: PhoneMapGpxItem,
        state: PhoneRouteToolsUiState,
    ): List<TrailPoint> {
        val original = route.track.points
        val anchor = checkNotNull(state.pointA)
        val bend = checkNotNull(state.destination)
        val anchorIndex = nearestPhoneRoutePointIndex(original, anchor)
        val rejoinIndex =
            original.indices
                .filter { index -> index > anchorIndex }
                .minByOrNull { index -> haversineDistanceMeters(original[index].location, bend) }
                ?: error("Pick a route point before the end of the GPX.")
        require(rejoinIndex > anchorIndex) { "Pick a route point before the end of the GPX." }
        val firstLeg =
            planner.createRoute(
                routeRequest(
                    origin = original[anchorIndex].location,
                    destination = bend,
                ),
            )
        val secondLeg =
            planner.createRoute(
                routeRequest(
                    origin = bend,
                    destination = original[rejoinIndex].location,
                ),
            )
        return mergePhoneRoutePoints(
            original.take(anchorIndex),
            firstLeg.points,
            secondLeg.points,
            original.drop(rejoinIndex + 1),
        )
    }

    private fun editedOutput(
        route: PhoneMapGpxItem,
        points: List<TrailPoint>,
        suffix: String,
    ): BRouterRouteOutput {
        val title = "${route.displayName} $suffix"
        return BRouterRouteOutput(
            fileName = "${route.id}-$suffix.gpx",
            title = title,
            gpxBytes = encodePhoneRouteGpx(title, points),
            points = points,
        )
    }

    private fun routeRequest(
        origin: GeoPoint,
        destination: GeoPoint,
        viaPoints: List<GeoPoint> = emptyList(),
    ): BRouterRouteRequest =
        BRouterRouteRequest(
            origin = origin,
            destination = destination,
            viaPoints = viaPoints,
            preset = selectedRoutePreset(),
        )

    private fun selectedEditableRoute(state: PhoneRouteToolsUiState): PhoneMapGpxItem =
        state.editableRoutes
            .firstOrNull { route -> route.id == state.selectedRouteId && route.isEditable }
            ?: error("Choose an editable GPX route.")

    private fun requireNearRoute(
        route: PhoneMapGpxItem,
        target: GeoPoint,
    ) {
        val distance =
            route.track.points.minOfOrNull { point -> haversineDistanceMeters(point.location, target) }
                ?: error("The selected GPX has no route points.")
        require(distance <= ROUTE_POINT_MATCH_THRESHOLD_METERS) {
            "Move the selected point closer to the active GPX."
        }
    }

    private fun requireDifferentRoutePoints(
        route: PhoneMapGpxItem,
        first: GeoPoint,
        second: GeoPoint,
    ) {
        require(
            nearestPhoneRoutePointIndex(route.track.points, first) !=
                nearestPhoneRoutePointIndex(route.track.points, second),
        ) {
            "Select two different points on the route."
        }
    }

    private fun routePoint(
        route: PhoneMapGpxItem,
        target: GeoPoint,
    ): GeoPoint = route.track.points[nearestPhoneRoutePointIndex(route.track.points, target)].location

    private fun PhoneRouteToolsUiState.coordinatesDestination(): GeoPoint {
        val latitude = coordinateLatitude.trim().toDoubleOrNull()
        val longitude = coordinateLongitude.trim().toDoubleOrNull()
        require(latitude != null && latitude.isFinite() && latitude in -90.0..90.0) {
            "Enter a valid latitude between -90 and 90."
        }
        require(longitude != null && longitude.isFinite() && longitude in -180.0..180.0) {
            "Enter a valid longitude between -180 and 180."
        }
        return GeoPoint(latitude = latitude, longitude = longitude)
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

    private companion object {
        const val MIN_CHAIN_POINTS = 2
        const val ROUTE_POINT_MATCH_THRESHOLD_METERS = 250.0
        const val ROUTE_ENDPOINT_SNAP_THRESHOLD_METERS = 60.0
    }
}
