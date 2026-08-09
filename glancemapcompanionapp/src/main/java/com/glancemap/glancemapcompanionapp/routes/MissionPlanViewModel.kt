package com.glancemap.glancemapcompanionapp.routes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.glancemapcompanionapp.diagnostics.MissionDayUpdateDiagnosticFields
import com.glancemap.glancemapcompanionapp.diagnostics.MissionDayWeatherDiagnosticSummary
import com.glancemap.glancemapcompanionapp.diagnostics.MissionPlanMutationOperation
import com.glancemap.glancemapcompanionapp.weather.FileWeatherForecastStore
import com.glancemap.glancemapcompanionapp.weather.OpenMeteoWeatherForecastProvider
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastRepository
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val _uiState = MutableStateFlow(MissionPlanUiState())
    val uiState: StateFlow<MissionPlanUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            updateFromRepository()
        }
    }

    fun addDay(routeId: String) {
        CompanionJourneyDiagnostics.missionPlanMutationStarted(MissionPlanMutationOperation.ADD_DAY)
        viewModelScope.launch {
            mutate(MissionPlanMutationOperation.ADD_DAY) { missionPlanRepository.addDay(routeId) }
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
        val dayUi = _uiState.value.days.firstOrNull { day -> day.day.id == dayId } ?: return
        val plannedDate = dayUi.day.plannedDate
        if (plannedDate == null) {
            CompanionJourneyDiagnostics.missionDayWeatherBlockedWithoutDate()
            updateDayWeather(
                dayId = dayId,
                weather =
                    MissionDayWeatherUiState(
                        message = "Add a date to this day before loading its planned forecast.",
                    ),
            )
            return
        }

        CompanionJourneyDiagnostics.missionDayWeatherRequested(
            forceRefresh = forceRefresh,
            hasStartTime = dayUi.day.plannedStartTime != null,
        )
        viewModelScope.launch {
            updateDayWeather(
                dayId = dayId,
                weather =
                    MissionDayWeatherUiState(
                        plannedDate = plannedDate,
                        plannedStartTime = dayUi.day.plannedStartTime,
                        isLoading = true,
                    ),
            )
            val samples =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val details =
                            routeLibraryRepository.routeDetails(dayUi.route.id)
                                ?: error("This day's GPX is no longer available.")
                        val targets = details.missionDayWeatherTargets(dayUi.day)
                        if (targets.isEmpty()) error("This GPX day has no route distance to sample.")
                        targets.map { target -> loadDayWeatherSample(target, dayUi.day, forceRefresh) }
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    CompanionJourneyDiagnostics.missionDayWeatherFailed()
                    updateDayWeather(
                        dayId = dayId,
                        weather =
                            MissionDayWeatherUiState(
                                plannedDate = plannedDate,
                                plannedStartTime = dayUi.day.plannedStartTime,
                                message = "Day weather is unavailable. Check your connection and try again.",
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
                        plannedStartTime = dayUi.day.plannedStartTime,
                        samples = samples,
                        message =
                            when {
                                unavailableCount == 0 -> null
                                unavailableCount == samples.size ->
                                    "Day weather is unavailable. Check your connection and try again."
                                else -> "$unavailableCount of ${samples.size} weather samples could not be loaded."
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
                            "Could not prepare this day's GPX for transfer."
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
    ) {
        _uiState.value = _uiState.value.copy(isLoading = true, message = null)
        val index =
            runCatching {
                withContext(Dispatchers.IO) { operation() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                CompanionJourneyDiagnostics.missionPlanMutationFailed(diagnosticOperation)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        message = error.message ?: "Could not update the mission plan.",
                    )
                return
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
                        message = error.message ?: "Could not load the mission plan.",
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
                        message = error.message ?: "Could not load routes for this mission plan.",
                    )
                return
            }
        val routesById = routeState.routes.associateBy(RouteLibraryRoute::id)
        val dayUi =
            index.days.mapNotNull { day ->
                val route = routesById[day.routeId] ?: return@mapNotNull null
                val details =
                    try {
                        withContext(Dispatchers.IO) { routeLibraryRepository.routeDetails(route.id) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    } ?: return@mapNotNull null
                MissionPlanDayUi(
                    day = day,
                    route = route,
                    briefing = details.missionPlanBriefing(day),
                    timeline = details.missionDayTimeline(day),
                    profile = details.missionDayPlanProfile(day),
                )
            }
        _uiState.value =
            MissionPlanUiState(
                days = dayUi,
                selectedDayId =
                    index.selectedDayId?.takeIf { selected -> dayUi.any { it.day.id == selected } }
                        ?: dayUi.firstOrNull()?.day?.id,
                weatherByDayId =
                    _uiState.value.weatherByDayId.filter { (dayId, weather) ->
                        val currentDay =
                            dayUi
                                .firstOrNull { day -> day.day.id == dayId }
                                ?.day
                        currentDay?.plannedDate == weather.plannedDate &&
                            currentDay?.plannedStartTime == weather.plannedStartTime
                    },
                unavailableDayCount = index.days.size - dayUi.size,
                isLoading = false,
            )
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
                message = "Unavailable",
            )
        }

    private fun updateDayWeather(
        dayId: String,
        weather: MissionDayWeatherUiState,
    ) {
        val day = _uiState.value.days.firstOrNull { dayUi -> dayUi.day.id == dayId } ?: return
        if (
            day.day.plannedDate != weather.plannedDate ||
            day.day.plannedStartTime != weather.plannedStartTime
        ) {
            return
        }
        _uiState.value =
            _uiState.value.copy(
                weatherByDayId = _uiState.value.weatherByDayId + (dayId to weather),
            )
    }
}
