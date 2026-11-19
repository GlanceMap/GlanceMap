@file:Suppress("MatchingDeclarationName")

package com.glancemap.glancemapwearos.presentation.features.home

internal enum class HomeGpsToggleAction { TURN_OFF, TURN_ON, REQUEST_LOCATION }

internal fun homeGpsToggleAction(
    gpsOff: Boolean,
    locationGranted: Boolean,
): HomeGpsToggleAction =
    when {
        !gpsOff -> HomeGpsToggleAction.TURN_OFF
        locationGranted -> HomeGpsToggleAction.TURN_ON
        else -> HomeGpsToggleAction.REQUEST_LOCATION
    }
