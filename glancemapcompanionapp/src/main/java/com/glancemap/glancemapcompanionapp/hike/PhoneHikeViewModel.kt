package com.glancemap.glancemapcompanionapp.hike

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryItem
import com.glancemap.glancemapcompanionapp.map.*
import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.glancemapcompanionapp.routes.MissionPlanDayUi
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.weather.*
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.reverseTrailPoints
import com.glancemap.trailcore.profile.TrailPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal data class PhoneHikeCheckpointDraft(
    val position: PhoneHikePosition,
    val name: String,
    val placeId: String,
    val location: GeoPoint,
    val checkpointId: String? = null,
    val stopMinutes: Int = 0,
)

internal data class PhoneHikeWeatherSample(
    val segmentIndex: Int,
    val fraction: Double,
    val forecast: WeatherForecast,
    val stale: Boolean,
)

internal data class PhoneHikeUiState(
    val data: PhoneHikeWorkspaceData = PhoneHikeWorkspaceData(),
    val model: PhoneHikeRouteModel? = null,
    val ready: Boolean = false,
    val loading: Boolean = true,
    val weatherLoading: Boolean = false,
    val weather: List<PhoneHikeWeatherSample> = emptyList(),
    val message: Int? = null,
    val pendingTodayId: String? = null,
    val checkpointDraft: PhoneHikeCheckpointDraft? = null,
    val pickingCheckpoint: Boolean = false,
    val currentPosition: PhoneHikePosition? = null,
    val currentFixElapsedMillis: Long? = null,
    val currentDeliveryIntervalMillis: Long = 3_000L,
    val cameraRequest: Long = 0L,
    val cameraReturnToLive: Boolean = false,
    val revealOnMap: Boolean = false,
) {
    val routeTitles: Map<String, String> get() = data.routeTitles
    val plan: PhoneHikePlan? get() = data.selectedPlan
    val cursor: PhoneHikePosition? get() = plan?.let { model?.position(it.cursorSegmentIndex, it.cursorFraction) }
}

/** The saved hike and disposable preview have independent identities and planning metadata. */
internal class PhoneHikeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val store = PhoneHikeStore(File(application.filesDir, "hikes"))
    private val routes = RouteLibraryRepository(application)
    private val weatherRepository =
        WeatherForecastRepository(OpenMeteoWeatherForecastProvider(), FileWeatherForecastStore(application))
    private val saveLock = Mutex()
    private val models = mutableMapOf<String, PhoneHikeRouteModel>()
    private val forecasts = mutableMapOf<String, List<PhoneHikeWeatherSample>>()
    private val mutableState = MutableStateFlow(PhoneHikeUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var selectionGeneration = 0L
    private var weatherGeneration = 0L
    private var saveGeneration = 0L
    private var cursorSave: Job? = null
    private var locationJob: Job? = null
    private var weatherJob: Job? = null
    private var checkpointJob: Job? = null
    private var latestLocation: PhoneMapLocation? = null
    private var lastGuidanceKey: String? = null
    private var guidanceRouteId: String? = null

    init {
        reload()
        viewModelScope.launch {
            state.first { it.ready }
            PhoneGuidanceSessionStore.state.map { it.session }.distinctUntilChanged().collect { session ->
                if (session == null) {
                    lastGuidanceKey = null
                    guidanceRouteId = null
                    updateLocation(latestLocation)
                    return@collect
                }
                val canonical = if (session.reversed) reverseTrailPoints(session.trackPoints) else session.trackPoints
                val id =
                    withContext(
                        Dispatchers.Default,
                    ) { phoneHikeRouteId(session.trackId, canonical, session.routeOffsetMeters) }
                guidanceRouteId = id
                if (lastGuidanceKey == "$id:${session.reversed}") return@collect
                lastGuidanceKey = "$id:${session.reversed}"
                guarded {
                    val draft = withContext(Dispatchers.IO) { PhoneGuidanceDraftStore(application).loadRoute() }
                    val config =
                        draft?.etaConfig
                            ?: defaultDraft(
                                session.trackId,
                                session.trackTitle,
                                canonical,
                                session.routeOffsetMeters,
                            ).etaConfig
                    installRoute(
                        PhoneHikeRoute(
                            id,
                            session.trackId,
                            session.trackTitle,
                            canonical,
                            session.routeOffsetMeters,
                            config,
                        ),
                        session.reversed,
                    )
                    if (state.value.data.todayId != id) updateData(state.value.data.useForToday(id))
                    showSelected()
                }
            }
        }
    }

    fun reload() =
        viewModelScope.launch {
            guarded {
                val data = withContext(Dispatchers.IO) { store.load() }
                mutableState.value = state.value.copy(data = data, ready = true, loading = false)
                showSelected()
            }
        }

    fun seedToday(
        routeId: String,
        day: MissionPlanDayUi? = null,
    ) {
        if (!state.value.ready || state.value.data.todayId != null || state.value.loading) return
        viewModelScope.launch {
            guarded {
                val route = withContext(Dispatchers.IO) { routes.load().routes.firstOrNull { it.id == routeId } }
                if (route != null &&
                    state.value.data.todayId == null
                ) {
                    prepare(PhoneLibraryItem.Gpx(route), day, today = true)
                }
            }
        }
    }

    fun preview(item: PhoneLibraryItem) = prepare(item, null, today = false)

    fun preview(item: PhoneMapGpxItem) = prepare(PhoneLibraryItem.FolderGpx(item), null, today = false)

    fun setMissionDay(day: MissionPlanDayUi) = prepare(PhoneLibraryItem.Gpx(day.route), day, today = true)

    fun useLibraryRoute(routeId: String) =
        viewModelScope.launch {
            guarded {
                val route = withContext(Dispatchers.IO) { routes.load().routes.firstOrNull { it.id == routeId } }
                if (route != null) prepare(PhoneLibraryItem.Gpx(route), null, today = true)
            }
        }

    private fun prepare(
        item: PhoneLibraryItem,
        day: MissionPlanDayUi?,
        today: Boolean,
    ) {
        if (!state.value.ready) return
        val token = ++generation
        mutableState.value = state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            guarded {
                val draft =
                    withContext(Dispatchers.IO) {
                        val points =
                            when (item) {
                                is PhoneLibraryItem.Gpx -> {
                                    val uri =
                                        if (day !=
                                            null
                                        ) {
                                            routes.contentUriFor(day.day)
                                        } else {
                                            routes.contentUriFor(item.route.id)
                                        }
                                    requireNotNull(uri)
                                    getApplication<Application>()
                                        .contentResolver
                                        .openInputStream(uri)
                                        ?.use(CompanionGpxRouteParser::parse)
                                        ?.points ?: error("Missing route")
                                }
                                is PhoneLibraryItem.FolderGpx -> item.item.track.points
                                is PhoneLibraryItem.ReceivedGpx ->
                                    item.file
                                        .inputStream()
                                        .use(CompanionGpxRouteParser::parse)
                                        .points
                                else -> error("Unsupported route")
                            }
                        require(points.size >= 2)
                        defaultDraft(
                            item.key,
                            if (day ==
                                null
                            ) {
                                item.title
                            } else {
                                day.day.name ?: item.title
                            },
                            points,
                            day?.day?.startDistanceMeters ?: 0.0,
                        )
                    }
                if (token != generation) return@guarded
                val route =
                    withContext(Dispatchers.Default) {
                        PhoneHikeRoute(
                            phoneHikeRouteId(draft.trackId, draft.points, draft.routeOffsetMeters),
                            draft.trackId,
                            draft.trackTitle,
                            draft.points,
                            draft.routeOffsetMeters,
                            draft.etaConfig,
                        )
                    }
                installRoute(route)
                if (token != generation) return@guarded
                val data = state.value.data
                updateData(data.openPreview(route.id))
                mutableState.value = state.value.copy(revealOnMap = true)
                if (today) {
                    if (day != null && data.plans.none { it.routeId == route.id }) {
                        updatePlan(
                            data.plan(route.id).copy(date = day.day.plannedDate, startTime = day.day.plannedStartTime),
                        )
                    }
                }
                showSelected()
                if (today && token == generation) requestUseForToday()
                PhoneDebugCapture.log("PhoneHike", "event=route_preview_loaded")
            }
        }
    }

    private fun defaultDraft(
        trackId: String,
        title: String,
        points: List<TrailPoint>,
        offset: Double,
    ): PhoneGuidanceRouteDraft {
        val app = getApplication<Application>()
        val general = PhoneGeneralSettingsPreferences(app).load()
        return PhoneGuidanceRouteDraft(
            trackId,
            title,
            points,
            offset,
            general.activityProfile.storageValue,
            phoneGuidanceEtaConfig(PhoneMapGpxSettingsPreferences(app).load(), general),
        )
    }

    private suspend fun installRoute(
        route: PhoneHikeRoute,
        reversed: Boolean = false,
    ) {
        val model = withContext(Dispatchers.Default) { PhoneHikeRouteModel(route, reversed) }
        require(model.totalDistanceMeters > 0.0)
        withContext(Dispatchers.IO) { store.saveRoute(route) }
        models[route.id] = model
        mutableState.value = state.value.copy(data = state.value.data.copy(routeTitles = state.value.routeTitles + (route.id to route.title)))
    }

    fun selectTab(preview: Boolean) {
        ++generation
        updateData(state.value.data.copy(previewSelected = preview))
        mutableState.value = state.value.copy(revealOnMap = true)
        viewModelScope.launch { guarded { showSelected() } }
    }

    fun requestUseForToday() {
        val data = state.value.data
        val id = data.selectedId ?: return
        val replacesPlan =
            data.todayId != null &&
                (data.todayId != id || data.previewSelected && data.todayPlan != data.plan(id).copy(inspecting = false))
        if (replacesPlan ||
            PhoneGuidanceSessionStore.state.value.session != null &&
            !matchesGuidance(state.value.model)
        ) {
            mutableState.value = state.value.copy(pendingTodayId = id)
        } else {
            promote(id)
        }
    }

    fun dismissPromotion() {
        mutableState.value = state.value.copy(pendingTodayId = null)
    }

    fun confirmPromotion() =
        viewModelScope.launch {
            guarded {
                val id = state.value.pendingTodayId ?: return@guarded
                if (PhoneGuidanceSessionStore.state.value.session != null && !matchesGuidance(models[id])) {
                    PhoneRecordingService.request(getApplication(), PhoneRecordingService.ACTION_GUIDANCE_STOP)
                    withTimeout(10_000L) { PhoneGuidanceSessionStore.state.first { it.session == null } }
                }
                promote(id)
            }
        }

    private fun promote(id: String) {
        updateData(state.value.data.useForToday(id))
        mutableState.value = state.value.copy(pendingTodayId = null)
        viewModelScope.launch { guarded { showSelected() } }
    }

    private suspend fun showSelected() {
        val token = ++selectionGeneration
        ++weatherGeneration
        weatherJob?.cancel()
        checkpointJob?.cancel()
        val id = state.value.data.selectedId
        val model =
            id?.let {
                models[it] ?: withContext(Dispatchers.IO) { store.loadRoute(it) }?.let { route ->
                    withContext(Dispatchers.Default) { PhoneHikeRouteModel(route) }
                }
            }
        if (token != selectionGeneration || id != state.value.data.selectedId) return
        if (id != null && model != null) models[id] = model
        val retained = listOfNotNull(state.value.data.todayId, state.value.data.previewId).toSet()
        models.keys.retainAll(retained)
        forecasts.keys.retainAll(retained)
        mutableState.value =
            state.value.copy(
                model = model,
                loading = false,
                weatherLoading = false,
                weather = forecasts[id].orEmpty(),
                message =
                    if (id !=
                        null &&
                        model == null
                    ) {
                        R.string.hike_load_failed
                    } else {
                        null
                    },
                checkpointDraft = null,
                pendingTodayId = null,
                pickingCheckpoint = false,
                currentPosition = null,
                cameraRequest =
                    state.value.cameraRequest + 1L,
                cameraReturnToLive = false,
            )
        updateLocation(latestLocation)
    }

    fun inspect(distanceMeters: Double) {
        val model = state.value.model ?: return
        val point = model.atDistance(distanceMeters) ?: return
        updatePlan(
            (state.value.plan ?: return).copy(
                cursorSegmentIndex = point.segmentIndex,
                cursorFraction = point.fraction,
                inspecting = true,
            ),
            debounce = true,
        )
    }

    fun returnToLive() {
        val plan = state.value.plan ?: return
        updatePlan(plan.copy(inspecting = false))
        mutableState.value = state.value.copy(cameraRequest = state.value.cameraRequest + 1L, cameraReturnToLive = true)
    }

    fun beginCheckpoint() {
        val ui = state.value
        val position =
            (if (ui.plan?.inspecting == true) ui.cursor else ui.currentPosition) ?: ui.model?.atDistance(0.0) ?: return
        beginCheckpoint(position, "", UUID.randomUUID().toString(), position.coordinate)
    }

    private fun beginCheckpoint(
        position: PhoneHikePosition,
        name: String,
        placeId: String,
        point: GeoPoint,
    ) {
        mutableState.value =
            state.value.copy(
                checkpointDraft = PhoneHikeCheckpointDraft(position, name, placeId, point),
                pickingCheckpoint = false,
            )
    }

    fun pickCheckpointOnMap() {
        mutableState.value = state.value.copy(pickingCheckpoint = true)
    }

    fun cancelCheckpoint() {
        checkpointJob?.cancel()
        mutableState.value = state.value.copy(checkpointDraft = null, pickingCheckpoint = false)
    }

    fun checkpointAt(
        point: GeoPoint,
        name: String = "",
        placeId: String = UUID.randomUUID().toString(),
        requireOnRoute: Boolean = true,
    ) {
        val model = state.value.model ?: return
        val previous =
            (
                if (state.value.plan?.inspecting ==
                    true
                ) {
                    state.value.cursor
                } else {
                    state.value.currentPosition
                }
            )?.distanceMeters
        checkpointJob?.cancel()
        checkpointJob =
            viewModelScope.launch {
                val hit = withContext(Dispatchers.Default) { model.nearest(point, previous) } ?: return@launch
                if (state.value.model !== model) return@launch
                if (requireOnRoute && hit.second > 75.0) {
                    mutableState.value = state.value.copy(message = R.string.hike_checkpoint_near_route)
                } else {
                    beginCheckpoint(hit.first, name, placeId, point)
                }
            }
    }

    fun editCheckpoint(checkpoint: PhoneHikeCheckpoint) {
        val position = state.value.model?.position(checkpoint.segmentIndex, checkpoint.fraction) ?: return
        mutableState.value =
            state.value.copy(
                checkpointDraft =
                    PhoneHikeCheckpointDraft(
                        position,
                        checkpoint.name,
                        checkpoint.placeId,
                        GeoPoint(checkpoint.latitude, checkpoint.longitude),
                        checkpoint.id,
                        checkpoint.stopMinutes,
                    ),
            )
    }

    fun saveCheckpoint(
        name: String,
        stopMinutes: Int,
    ) {
        val draft = state.value.checkpointDraft ?: return
        val plan = state.value.plan ?: return
        if (plan.checkpoints.any {
                it.id != draft.checkpointId &&
                    it.placeId == draft.placeId &&
                    it.segmentIndex == draft.position.segmentIndex &&
                    kotlin.math.abs(it.fraction - draft.position.fraction) < 0.0001
            }
        ) {
            cancelCheckpoint()
            return
        }
        val checkpoint =
            PhoneHikeCheckpoint(
                draft.checkpointId ?: UUID.randomUUID().toString(),
                draft.placeId,
                name.trim().take(100).ifBlank {
                    getApplication<Application>().getString(
                        R.string.hike_checkpoint_default,
                        plan.checkpoints.size + 1,
                    )
                },
                draft.location.latitude,
                draft.location.longitude,
                draft.position.segmentIndex,
                draft.position.fraction,
                stopMinutes.coerceIn(0, 1_440),
            )
        updatePlan(plan.copy(checkpoints = plan.checkpoints.filterNot { it.id == checkpoint.id } + checkpoint))
        cancelCheckpoint()
        PhoneDebugCapture.log("PhoneHike", "event=checkpoint_saved count=${plan.checkpoints.size + 1}")
    }

    fun deleteCheckpoint(id: String) {
        state.value.plan?.let {
            updatePlan(
                it.copy(
                    checkpoints =
                        it.checkpoints.filterNot { checkpoint ->
                            checkpoint.id ==
                                id
                        },
                ),
            )
        }
    }

    fun inspectCheckpoint(
        routeId: String,
        checkpoint: PhoneHikeCheckpoint,
    ) {
        ++generation
        mutableState.value = state.value.copy(revealOnMap = true)
        updateData(
            state.value.data
                .openPreview(routeId)
                .updatePlan(
                    state.value.data
                        .plan(routeId)
                        .copy(
                            cursorSegmentIndex = checkpoint.segmentIndex,
                            cursorFraction = checkpoint.fraction,
                            inspecting = true,
                        ),
                ),
        )
        viewModelScope.launch { guarded { showSelected() } }
    }

    fun saveSchedule(
        date: String,
        time: String,
        zoneId: String?,
    ) {
        LocalDate.parse(date)
        LocalTime.parse(time)
        zoneId?.let(ZoneId::of)
        state.value.plan?.let { updatePlan(it.copy(date = date, startTime = time, zoneId = zoneId)) }
    }

    fun consumeMapReveal() {
        mutableState.value = state.value.copy(revealOnMap = false)
    }

    fun updateLocation(location: PhoneMapLocation?) {
        latestLocation = location
        locationJob?.cancel()
        val model = state.value.model ?: return
        val guidance = PhoneGuidanceSessionStore.state.value
        val interval = guidance.guidance?.alertGpsDeliveryIntervalMs ?: 3_000L
        mutableState.value =
            state.value.copy(
                currentFixElapsedMillis = location?.fixElapsedRealtimeMillis,
                currentDeliveryIntervalMillis = interval,
            )
        locationJob =
            viewModelScope.launch {
                val position =
                    withContext(Dispatchers.Default) {
                        when {
                            location == null -> null
                            matchesGuidance(model) && (guidance.guidance?.offRoute == true || guidance.paused) -> null
                            matchesGuidance(
                                model,
                            ) &&
                                guidance.message == PhoneGuidanceMessage.ACTIVE ->
                                guidance.guidance
                                    ?.distanceFromStartMeters
                                    ?.let(
                                        model::atDistance,
                                    )
                            else ->
                                model
                                    .nearest(
                                        GeoPoint(location.latitude, location.longitude),
                                        state.value.currentPosition?.distanceMeters,
                                    )?.takeIf {
                                        it.second <=
                                            40.0
                                    }?.first
                        }
                    }
                if (state.value.model === model) mutableState.value = state.value.copy(currentPosition = position)
            }
    }

    fun matchesGuidance(model: PhoneHikeRouteModel?): Boolean {
        val session = PhoneGuidanceSessionStore.state.value.session ?: return false
        return model != null && model.route.id == guidanceRouteId && session.reversed == model.reversed
    }

    fun loadWeather(forceRefresh: Boolean = false) {
        val model = state.value.model ?: return
        val plan = state.value.plan ?: return
        val id = model.route.id
        if (state.value.weatherLoading) return
        val token = ++weatherGeneration
        mutableState.value = state.value.copy(weatherLoading = true, message = null)
        weatherJob =
            viewModelScope.launch {
                try {
                    val positions = phoneHikeWeatherPositions(model, plan)
                    val semaphore = Semaphore(2)
                    val samples =
                        coroutineScope {
                            positions
                                .map { point ->
                                    async {
                                        semaphore.withPermit {
                                            try {
                                                val result =
                                                    weatherRepository.forecast(
                                                        WeatherForecastLocation(
                                                            point.coordinate.latitude,
                                                            point.coordinate.longitude,
                                                            point.elevationMeters,
                                                            "Route forecast",
                                                        ),
                                                        forceRefresh,
                                                        requireHourlyForecast = true,
                                                    )
                                                PhoneHikeWeatherSample(
                                                    point.segmentIndex,
                                                    point.fraction,
                                                    result.forecast,
                                                    result.source == WeatherForecastSource.STALE_CACHE,
                                                )
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                null
                                            }
                                        }
                                    }
                                }.awaitAll()
                                .filterNotNull()
                        }
                    if (token != weatherGeneration || state.value.data.selectedId != id) return@launch
                    check(samples.isNotEmpty())
                    forecasts[id] = samples
                    mutableState.value =
                        state.value.copy(
                            weather = samples,
                            message =
                                if (samples.size <
                                    positions.size
                                ) {
                                    R.string.hike_weather_partial
                                } else {
                                    null
                                },
                        )
                    PhoneDebugCapture.log("PhoneHike", "event=weather_loaded samples=${samples.size}")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (token == weatherGeneration &&
                        state.value.data.selectedId == id
                    ) {
                        mutableState.value =
                            state.value.copy(message = R.string.hike_weather_unavailable)
                    }
                } finally {
                    if (token == weatherGeneration) mutableState.value = state.value.copy(weatherLoading = false)
                }
            }
    }

    private fun updatePlan(
        plan: PhoneHikePlan,
        debounce: Boolean = false,
    ) {
        updateData(state.value.data.updateSelectedPlan(plan), debounce)
    }

    private fun updateData(
        data: PhoneHikeWorkspaceData,
        debounce: Boolean = false,
    ) {
        mutableState.value = state.value.copy(data = data, message = null)
        if (!state.value.ready) return
        val token = ++saveGeneration
        cursorSave?.cancel()
        cursorSave =
            viewModelScope.launch {
                if (debounce) delay(400L)
                saveLock.withLock {
                    if (token == saveGeneration) guarded { withContext(Dispatchers.IO) { store.save(data) } }
                }
            }
    }

    private suspend fun guarded(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = state.value.copy(loading = false, message = R.string.hike_load_failed)
            PhoneDebugCapture.log("PhoneHike", "event=operation_failed")
        }
    }
}
