package com.glancemap.glancemapcompanionapp.routes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MissionPlanViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val missionPlanRepository = MissionPlanRepository(application)
    private val routeLibraryRepository = RouteLibraryRepository(application)
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
        viewModelScope.launch {
            mutate { missionPlanRepository.addDay(routeId) }
        }
    }

    fun selectDay(dayId: String) {
        viewModelScope.launch {
            mutate { missionPlanRepository.selectDay(dayId) }
        }
    }

    fun updateSegment(
        dayId: String,
        startDistanceMeters: Double,
        endDistanceMeters: Double?,
    ) {
        viewModelScope.launch {
            mutate { missionPlanRepository.updateSegment(dayId, startDistanceMeters, endDistanceMeters) }
        }
    }

    fun removeDay(dayId: String) {
        viewModelScope.launch {
            mutate { missionPlanRepository.removeDay(dayId) }
        }
    }

    fun prepareSelectedDayForTransfer(onPrepared: (Uri?) -> Unit) {
        val day = _uiState.value.selectedDay?.day ?: return onPrepared(null)
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
            onPrepared(transferUri)
        }
    }

    private suspend fun mutate(operation: suspend () -> MissionPlanRepository.MissionPlanIndex) {
        _uiState.value = _uiState.value.copy(isLoading = true, message = null)
        val index =
            runCatching {
                withContext(Dispatchers.IO) { operation() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        message = error.message ?: "Could not update the mission plan.",
                    )
                return
            }
        publish(index)
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
        val routeState = withContext(Dispatchers.IO) { routeLibraryRepository.load() }
        val routesById = routeState.routes.associateBy(RouteLibraryRoute::id)
        val dayUi =
            index.days.mapNotNull { day ->
                val route = routesById[day.routeId] ?: return@mapNotNull null
                val details =
                    withContext(Dispatchers.IO) { routeLibraryRepository.routeDetails(route.id) }
                        ?: return@mapNotNull null
                MissionPlanDayUi(day = day, route = route, briefing = details.missionPlanBriefing(day))
            }
        _uiState.value =
            MissionPlanUiState(
                days = dayUi,
                selectedDayId = index.selectedDayId?.takeIf { selected -> dayUi.any { it.day.id == selected } },
                isLoading = false,
            )
    }
}
