package com.glancemap.glancemapwearos.domain.sensors

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class CompassViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val compassManager = CompassManager.getInstance(application)

    val renderState = compassManager.renderState

    val heading =
        renderState
            .map { it.headingDeg }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), renderState.value.headingDeg)
    val accuracy =
        renderState
            .map { it.accuracy }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), renderState.value.accuracy)
    val headingSource =
        renderState
            .map { it.headingSource }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), renderState.value.headingSource)
    val headingSourceStatus =
        renderState
            .map { it.headingSourceStatus }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                renderState.value.headingSourceStatus,
            )
    val northReferenceStatus =
        renderState
            .map { it.northReferenceStatus }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                renderState.value.northReferenceStatus,
            )
    val magneticInterference =
        renderState
            .map { it.magneticInterference }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                renderState.value.magneticInterference,
            )

    fun start(lowPower: Boolean = false) {
        compassManager.start(lowPower = lowPower)
    }

    fun stop(
        reason: String = "unspecified",
        delayMs: Long = 0L,
    ) {
        compassManager.stop(reason = reason, delayMs = delayMs)
    }

    fun recalibrate() {
        compassManager.recalibrate()
    }

    fun updateDeclinationFromLocation(location: android.location.Location) {
        compassManager.updateDeclinationFromLocation(location)
    }

    fun primeDeclinationFromApproximateLocation(
        latitude: Double,
        longitude: Double,
        altitudeM: Float = 0f,
    ) {
        compassManager.primeDeclinationFromApproximateLocation(
            latitude = latitude,
            longitude = longitude,
            altitudeM = altitudeM,
        )
    }

    fun setNorthReferenceMode(
        mode: NorthReferenceMode,
        forceRefresh: Boolean = false,
    ) {
        compassManager.setNorthReferenceMode(mode, forceRefresh = forceRefresh)
    }

    fun setHeadingSourceMode(
        mode: CompassHeadingSourceMode,
        forceRefresh: Boolean = false,
    ) {
        compassManager.setHeadingSourceMode(mode, forceRefresh = forceRefresh)
    }

    fun setProviderType(
        type: CompassProviderType,
        forceRefresh: Boolean = false,
    ) {
        compassManager.setProviderType(type, forceRefresh = forceRefresh)
    }

    override fun onCleared() {
        // Safety: if viewmodel is cleared while running
        compassManager.stop(reason = "viewmodel_cleared")
        super.onCleared()
    }
}
