package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

internal val TurnByTurnRouteStartOptions =
    listOf(
        SettingsRepository.TURN_BY_TURN_ROUTE_START_GO_TO_START to R.string.settings_go_to_gpx_start,
        SettingsRepository.TURN_BY_TURN_ROUTE_START_NEAREST_POINT to R.string.settings_start_nearest,
        SettingsRepository.TURN_BY_TURN_ROUTE_START_ASK to R.string.settings_ask_each_time,
    )

internal val TurnByTurnReverseSuggestionOptions =
    listOf(
        SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK to R.string.settings_when_near_the_end,
        SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_NEVER to R.string.settings_never,
    )

internal val TurnByTurnAlertsOptions =
    listOf(
        SettingsRepository.TURN_BY_TURN_TURN_ALERTS_ALL to R.string.settings_all_turns_recommended,
        SettingsRepository.TURN_BY_TURN_TURN_ALERTS_IMPORTANT to R.string.settings_major_turns,
        SettingsRepository.TURN_BY_TURN_TURN_ALERTS_OFF to R.string.glancemap_state_off,
    )

internal val TurnByTurnOffRouteThresholdOptions = listOf(20, 40, 60, 80, 100)
internal val TurnByTurnOffRouteRepeatOptions = listOf(30, 60, 120)

internal fun routeStartBehaviorLabel(
    behavior: String,
    resources: Resources,
): String =
    when (behavior) {
        SettingsRepository.TURN_BY_TURN_ROUTE_START_NEAREST_POINT ->
            resources.getString(
                R.string.settings_start_nearest,
            )
        SettingsRepository.TURN_BY_TURN_ROUTE_START_ASK -> resources.getString(R.string.settings_ask_each_time)
        else -> resources.getString(R.string.settings_go_to_gpx_start)
    }

internal fun reverseSuggestionLabel(
    mode: String,
    resources: Resources,
): String =
    when (mode) {
        SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_NEVER -> resources.getString(R.string.settings_never)
        else -> resources.getString(R.string.settings_when_near_the_end)
    }

internal fun turnAlertsLabel(
    mode: String,
    resources: Resources,
): String =
    when (mode) {
        SettingsRepository.TURN_BY_TURN_TURN_ALERTS_OFF -> resources.getString(R.string.glancemap_state_off)
        SettingsRepository.TURN_BY_TURN_TURN_ALERTS_ALL -> resources.getString(R.string.settings_all_turns)
        else -> resources.getString(R.string.settings_major_turns)
    }
