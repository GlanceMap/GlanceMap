package com.glancemap.glancemapcompanionapp.routes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.glancemapcompanionapp.diagnostics.MissionDayUpdateDiagnosticFields
import com.glancemap.glancemapcompanionapp.diagnostics.MissionDayWeatherDiagnosticSummary
import com.glancemap.glancemapcompanionapp.diagnostics.MissionPlanMutationOperation
import com.glancemap.glancemapcompanionapp.weather.FileWeatherForecastStore
import com.glancemap.glancemapcompanionapp.weather.OpenMeteoWeatherForecastProvider
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastRepository
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("LongMethod", "TooManyFunctions")
class MissionPlanViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val missionPlanRepository = MissionPlanRepository(application)
    private val routeLibraryRepository = RouteLibraryRepository(application)
    private val weatherForecastRepository =
        WeatherForecastRepository(
            provider = OpenMeteoWeatherForecastProvider(),
            store = FileWeatherForecastStore(application),
        )
    private val mutationMutex = Mutex()
    private val routeDetailsCache = mutableMapOf<RouteLibraryRoute, RouteLibraryRouteDetails>()
    private val _uiState = MutableStateFlow(MissionPlanUiState())
    val uiState: StateFlow<MissionPlanUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            mutationMutex.withLock {
                routeDetailsCache.clear()
                updateFromRepository()
            }
        }
    }

    fun addDay(routeId: String) {
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.ADD_DAY)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.ADD_DAY) { missionPlanRepository.addDay(routeId) }
        }
    }

    fun saveAdventure(
        id: String?,
        update: MissionAdventureUpdate,
    ) {
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.UPDATE_ADVENTURE) { missionPlanRepository.saveAdventure(id, update) }
        }
    }

    fun selectAdventure(id: String) {
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.SELECT_ADVENTURE) { missionPlanRepository.selectAdventure(id) }
        }
    }

    fun addBlankDay(isRestDay: Boolean) {
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.ADD_DAY) { missionPlanRepository.addDay(isRestDay = isRestDay) }
        }
    }

    fun assignRoute(
        dayId: String,
        routeId: String,
    ) {
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.ASSIGN_ROUTE) { missionPlanRepository.assignRoute(dayId, routeId) }
        }
    }

    fun importDayRoute(
        dayId: String,
        uri: Uri,
        onImported: () -> Unit,
    ) {
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.ASSIGN_ROUTE) {
                val imported = routeLibraryRepository.importRoute(uri)
                missionPlanRepository.assignRoute(dayId, requireNotNull(imported.selectedRouteId))
            }
            onImported()
        }
    }

    suspend fun loadStageSource(
        adventureId: String,
        routeId: String,
    ): MissionRouteStageSource =
        withContext(Dispatchers.IO) {
            val index = missionPlanRepository.load()
            require(
                index.adventures.any { it.id == adventureId },
            ) { "This adventure no longer exists. Reopen Adventures and try again." }
            MissionRouteStageSource(
                requireNotNull(
                    routeLibraryRepository.routeDetails(routeId),
                ) { "This GPX is no longer in your library. Import it again or choose another route." },
                index.days.filter { it.adventureId == adventureId }.sortedBy { it.dayNumber },
            )
        }

    suspend fun importStageRoute(uri: Uri): String =
        withContext(Dispatchers.IO) {
            requireNotNull(routeLibraryRepository.importRoute(uri).selectedRouteId)
        }

    suspend fun saveStages(
        adventureId: String,
        routeId: String,
        dayIds: List<String>,
        stops: List<Double>,
    ) {
        mutationMutex.withLock {
            val index =
                withContext(Dispatchers.IO) {
                    val details =
                        requireNotNull(
                            routeLibraryRepository.routeDetails(routeId),
                        ) { "This GPX is no longer in your library. Import it again or choose another route." }
                    val distances = details.profile.cumulativeDistanceMeters.toSet()
                    require(stops.all { it in distances }) { "Choose stops on this GPX." }
                    missionPlanRepository.planStages(
                        adventureId,
                        routeId,
                        dayIds,
                        stops,
                        details.profile.totalDistanceMeters,
                    )
                }
            publish(index)
        }
    }

    fun changeDay(
        dayId: String,
        edit: (MissionPlanDay) -> MissionPlanDayUpdate,
    ) {
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.UPDATE_DAY) { missionPlanRepository.editDay(dayId, edit) }
        }
    }

    fun selectDay(dayId: String) {
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.SELECT_DAY)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.SELECT_DAY) { missionPlanRepository.selectDay(dayId) }
        }
    }

    fun updateSegment(
        dayId: String,
        startDistanceMeters: Double,
        endDistanceMeters: Double?,
    ) {
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.UPDATE_SEGMENT)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.UPDATE_SEGMENT) {
                missionPlanRepository.updateSegment(dayId, startDistanceMeters, endDistanceMeters)
            }
        }
    }

    fun updateDay(
        dayId: String,
        update: MissionPlanDayUpdate,
    ) {
        CompanionJourneyDiagnostics.missionDayUpdateRequested(
            MissionDayUpdateDiagnosticFields(
                includesName = !update.name.isNullOrBlank(),
                includesDate = !update.plannedDate.isNullOrBlank(),
                includesStartTime = !update.plannedStartTime.isNullOrBlank(),
                includesOvernight = !update.overnight.isNullOrBlank(),
                includesNotes = !update.notes.isNullOrBlank(),
                includesSegment = update.startDistanceMeters > 0.0 || update.endDistanceMeters != null,
            ),
        )
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.UPDATE_DAY)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.UPDATE_DAY) { missionPlanRepository.updateDay(dayId, update) }
        }
    }

    fun moveDay(
        dayId: String,
        targetIndex: Int,
    ) {
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.REORDER_DAY)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.REORDER_DAY) { missionPlanRepository.moveDay(dayId, targetIndex) }
        }
    }

    fun removeDay(dayId: String) {
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.REMOVE_DAY)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.REMOVE_DAY) { missionPlanRepository.removeDay(dayId) }
        }
    }

    fun loadDayWeather(
        dayId: String,
        forceRefresh: Boolean,
    ) {
        val day =
            _uiState.value
                .weatherPlanDay(dayId)
                ?.takeUnless { _uiState.value.weatherFor(it)?.isLoading == true } ?: return
        val plannedDate = day.plannedDate
        if (plannedDate == null) {
            CompanionJourneyDiagnostics.missionDayWeatherBlockedWithoutDate()
            updateDayWeather(
                dayId = dayId,
                weather =
                    MissionDayWeatherUiState(
                        message =
                            getApplication<android.app.Application>().getString(
                                R.string.mission_plan_add_a_date_to_this_day_before,
                            ),
                    ),
            )
            return
        }

        CompanionJourneyDiagnostics.missionDayWeatherRequested(
            forceRefresh = forceRefresh,
            hasStartTime = day.plannedStartTime != null,
        )
        viewModelScope.launch {
            updateDayWeather(
                dayId = dayId,
                weather =
                    MissionDayWeatherUiState(
                        plannedDate = plannedDate,
                        planKey = day.weatherKey(),
                        plannedStartTime = day.plannedStartTime,
                        isLoading = true,
                    ),
            )
            val samples =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val targets =
                            if (day.isRestDay) {
                                day.weatherLocation
                                    ?.let { location ->
                                        listOf(
                                            MissionDayWeatherSampleTarget(
                                                MissionDayWeatherSamplePosition.STAY,
                                                0.0,
                                                0L,
                                                location,
                                            ),
                                        )
                                    }.orEmpty()
                            } else {
                                day.routeId
                                    ?.let { routeLibraryRepository.routeDetails(it) }
                                    ?.missionDayWeatherTargets(day)
                                    .orEmpty()
                            }
                        if (targets.isEmpty()) {
                            error(
                                getApplication<android.app.Application>().getString(
                                    R.string.mission_plan_this_gpx_day_has_no_route_distance,
                                ),
                            )
                        }
                        targets.map { target -> loadDayWeatherSample(target, day, forceRefresh) }
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    CompanionJourneyDiagnostics.missionDayWeatherFailed()
                    updateDayWeather(
                        dayId = dayId,
                        weather =
                            MissionDayWeatherUiState(
                                plannedDate = plannedDate,
                                planKey = day.weatherKey(),
                                plannedStartTime = day.plannedStartTime,
                                message =
                                    getApplication<android.app.Application>().getString(
                                        R.string.mission_plan_day_weather_is_unavailable_check_your_connection,
                                    ),
                            ),
                    )
                    return@launch
                }
            val unavailableCount = samples.count { sample -> sample.forecast == null }
            CompanionJourneyDiagnostics.missionDayWeatherCompleted(
                MissionDayWeatherDiagnosticSummary(
                    unavailableSampleCount = unavailableCount,
                    sampleCount = samples.size,
                    includesNetwork = samples.any { sample -> sample.forecast != null && !sample.isCached },
                    includesCache = samples.any(MissionDayWeatherSampleUi::isCached),
                    includesStaleCache = samples.any(MissionDayWeatherSampleUi::isStale),
                    hasScheduledOutlook = samples.any { sample -> sample.scheduledOutlook != null },
                ),
            )
            updateDayWeather(
                dayId = dayId,
                weather =
                    MissionDayWeatherUiState(
                        plannedDate = plannedDate,
                        planKey = day.weatherKey(),
                        plannedStartTime = day.plannedStartTime,
                        samples = samples,
                        message =
                            when {
                                unavailableCount == 0 -> null
                                unavailableCount == samples.size ->
                                    getApplication<android.app.Application>().getString(
                                        R.string.mission_plan_day_weather_is_unavailable_check_your_connection,
                                    )
                                else ->
                                    getApplication<android.app.Application>().getString(
                                        R.string.mission_plan_value_of_value_weather_samples_could_not,
                                        unavailableCount,
                                        samples.size,
                                    )
                            },
                    ),
            )
        }
    }

    fun prepareSelectedDayForTransfer(onPrepared: (Uri?) -> Unit) {
        CompanionJourneyDiagnostics.missionDayTransferRequested()
        val day =
            _uiState.value.selectedDay?.day
                ?: run {
                    CompanionJourneyDiagnostics.missionDayTransferPrepared(success = false)
                    return onPrepared(null)
                }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPreparingTransfer = true, message = null)
            val transferUri =
                runCatching {
                    withContext(Dispatchers.IO) { routeLibraryRepository.contentUriFor(day) }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }.getOrNull()
            _uiState.value =
                _uiState.value.copy(
                    isPreparingTransfer = false,
                    message =
                        if (transferUri == null) {
                            getApplication<android.app.Application>().getString(
                                R.string.mission_plan_could_not_prepare_this_day_s_gpx,
                            )
                        } else {
                            null
                        },
                )
            CompanionJourneyDiagnostics.missionDayTransferPrepared(success = transferUri != null)
            onPrepared(transferUri)
        }
    }

    private suspend fun mutate(
        diagnosticOperation: MissionPlanMutationOperation,
        operation: suspend () -> MissionPlanRepository.MissionPlanIndex,
    ) = mutationMutex.withLock {
        _uiState.value = _uiState.value.copy(message = null)
        val index =
            runCatching {
                withContext(Dispatchers.IO) { operation() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                CompanionJourneyDiagnostics.missionPlanMutationFailed(diagnosticOperation)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        message =
                            error.message
                                ?: getApplication<android.app.Application>().getString(
                                    R.string.mission_plan_could_not_update_the_mission_plan,
                                ),
                    )
                return@withLock
            }
        publish(index)
        CompanionJourneyDiagnostics.missionPlanMutationSucceeded(diagnosticOperation, index.days.size)
    }

    private suspend fun updateFromRepository() {
        val index =
            runCatching {
                withContext(Dispatchers.IO) { missionPlanRepository.load() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        message =
                            error.message
                                ?: getApplication<android.app.Application>().getString(
                                    R.string.mission_plan_could_not_load_the_mission_plan,
                                ),
                    )
                return
            }
        publish(index)
    }

    private suspend fun publish(index: MissionPlanRepository.MissionPlanIndex) {
        val routeState =
            runCatching {
                withContext(Dispatchers.IO) { routeLibraryRepository.load() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        message =
                            error.message
                                ?: getApplication<android.app.Application>().getString(
                                    R.string.mission_plan_could_not_load_routes_for_this_mission,
                                ),
                    )
                return
            }
        routeDetailsCache.keys.retainAll(routeState.routes.toSet())
        val routesById = routeState.routes.associateBy(RouteLibraryRoute::id)
        val planDays =
            index.days.filter { it.adventureId == index.selectedAdventureId }.sortedBy(
                MissionPlanDay::dayNumber,
            )
        val dayUi = mutableListOf<MissionPlanDayUi>()
        val restLocations = mutableMapOf<String, WeatherForecastLocation>()
        var previousFinish: WeatherForecastLocation? = null
        for (day in planDays) {
            if (day.isRestDay) {
                previousFinish = day.weatherLocation ?: previousFinish
                previousFinish?.let { restLocations[day.id] = it }
                continue
            }
            val route = routesById[day.routeId]
            val details = route?.let { routeDetailsOrNull(it) }
            previousFinish =
                details?.weatherLocationAt(
                    day.endDistanceFor(details.profile.totalDistanceMeters),
                    day.overnight ?: details.route.displayName,
                )
            if (details != null) {
                dayUi +=
                    MissionPlanDayUi(
                        day,
                        route,
                        details.missionPlanBriefing(day),
                        details.missionDayTimeline(day),
                        details.missionDayPlanProfile(day),
                    )
            }
        }
        val nextState =
            MissionPlanUiState(
                days = dayUi,
                selectedDayId = index.selectedDayId?.takeIf { selected -> dayUi.any { it.day.id == selected } },
                adventures = index.adventures,
                selectedAdventureId = index.selectedAdventureId,
                planDays = planDays,
                restLocations = restLocations,
                unavailableDayCount = planDays.count { !it.isRestDay } - dayUi.size,
                isLoading = false,
            )
        _uiState.value =
            nextState.copy(
                weatherByDayId =
                    _uiState.value.weatherByDayId.filter { (id, weather) ->
                        nextState.weatherPlanDay(id)?.let { day ->
                            day.plannedDate == weather.plannedDate &&
                                day.plannedStartTime == weather.plannedStartTime &&
                                day.weatherKey() == weather.planKey
                        } == true
                    },
            )
    }

    @Suppress(
        "TooGenericExceptionCaught",
        "SwallowedException",
    ) // Keep the planning visible when a GPX is missing or malformed.
    private suspend fun routeDetailsOrNull(route: RouteLibraryRoute): RouteLibraryRouteDetails? =
        try {
            routeDetailsCache[route] ?: withContext(Dispatchers.IO) { routeLibraryRepository.routeDetails(route.id) }
                ?.also { routeDetailsCache[route] = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private suspend fun loadDayWeatherSample(
        target: MissionDayWeatherSampleTarget,
        day: MissionPlanDay,
        forceRefresh: Boolean,
    ): MissionDayWeatherSampleUi =
        try {
            val result =
                weatherForecastRepository.forecast(
                    location = target.location,
                    forceRefresh = forceRefresh,
                    requireHourlyForecast = true,
                )
            val scheduledTime = target.plannedDateTime(day)
            MissionDayWeatherSampleUi(
                target = target,
                forecast = result.forecast,
                dailyOutlook = result.forecast.daily.firstOrNull { outlook -> outlook.date == day.plannedDate },
                scheduledOutlook = scheduledTime?.let(result.forecast::hourlyOutlookNear),
                scheduledTimeIso8601 = scheduledTime?.toString(),
                isCached = result.source != WeatherForecastSource.NETWORK,
                isStale = result.source == WeatherForecastSource.STALE_CACHE,
                savedSnapshotCount = weatherForecastRepository.history(target.location).size,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            MissionDayWeatherSampleUi(
                target = target,
                message =
                    getApplication<android.app.Application>().getString(
                        R.string.map_tools_settings_sensor_unavailable,
                    ),
            )
        }

    private fun updateDayWeather(
        dayId: String,
        weather: MissionDayWeatherUiState,
    ) {
        val day = _uiState.value.weatherPlanDay(dayId) ?: return
        if (
            day.plannedDate != weather.plannedDate ||
            day.plannedStartTime != weather.plannedStartTime ||
            day.weatherKey() != weather.planKey
        ) {
            return
        }
        _uiState.value =
            _uiState.value.copy(
                weatherByDayId = _uiState.value.weatherByDayId + (dayId to weather),
            )
    }
}
