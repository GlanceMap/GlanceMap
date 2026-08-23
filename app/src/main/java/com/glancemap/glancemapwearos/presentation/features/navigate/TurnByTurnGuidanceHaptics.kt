package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.Context
import android.os.VibrationEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun TurnByTurnGuidanceHapticEffect(
    context: Context,
    state: TurnByTurnGuidanceState,
    currentSpeedMps: Float?,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    hapticsEnabled: Boolean,
    turnAlertsMode: String,
    offRouteAlertsEnabled: Boolean,
    offRouteRepeatSeconds: Int,
) {
    val vibrator = remember { vibratorFrom(context) }
    val latestState by rememberUpdatedState(state)
    val routeKey = state.alertSessionKey ?: state.trackTitle ?: "inactive_route"
    val gpsDeliveryIntervalMs =
        state.alertGpsDeliveryIntervalMs ?: SettingsRepository.DEFAULT_GPS_INTERVAL_MS
    val turnAlertTracker = remember(routeKey) { TurnHapticAlertTracker() }
    var arrivalAlertedTrack by remember { mutableStateOf<String?>(null) }
    var offRouteEpisodeActive by remember(routeKey) { mutableStateOf(false) }

    LaunchedEffect(state.active, state.trackTitle) {
        if (!state.active) {
            arrivalAlertedTrack = null
        }
    }

    LaunchedEffect(
        state.active,
        state.mode,
        state.offRoute,
        state.nextInstruction?.trackPointIndex,
        state.distanceToInstructionMeters,
        state.distanceFromStartMeters,
        state.alertSessionKey,
        state.alertGpsDeliveryIntervalMs,
        currentSpeedMps,
        activityProfile,
        hapticsEnabled,
        turnAlertsMode,
    ) {
        val events =
            turnAlertTracker.update(
                TurnHapticAlertSample(
                    routeKey = routeKey,
                    active = state.active,
                    mode = state.mode,
                    offRoute = state.offRoute,
                    instruction = state.nextInstruction,
                    distanceToInstructionMeters = state.distanceToInstructionMeters,
                    distanceFromStartMeters = state.distanceFromStartMeters,
                    speedMps = currentSpeedMps,
                    activityProfile = activityProfile,
                    gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
                    hapticsEnabled = hapticsEnabled,
                    turnAlertsMode = turnAlertsMode,
                ),
            )
        events.forEach { event ->
            DebugTelemetry.log("TurnByTurn", event.telemetryMessage(vibratorAvailable = vibrator != null))
            if (event.outcome == TurnHapticAlertOutcome.FIRED) {
                vibrator?.vibrate(turnAlertEffect(event.instruction.command))
            }
        }
    }

    LaunchedEffect(state.active, state.mode, state.trackTitle, hapticsEnabled) {
        if (!hapticsEnabled || !state.active || state.mode != GuidanceMode.FINISHED) return@LaunchedEffect
        val trackKey = state.trackTitle ?: "active_route"
        if (arrivalAlertedTrack == trackKey) return@LaunchedEffect
        arrivalAlertedTrack = trackKey
        DebugTelemetry.log("TurnByTurn", "haptic=arrival track=${state.trackTitle ?: "unknown"}")
        vibrator?.vibrate(ARRIVAL_ALERT_EFFECT)
    }

    LaunchedEffect(
        state.active,
        state.mode,
        state.offRoute,
        hapticsEnabled,
        offRouteAlertsEnabled,
        offRouteRepeatSeconds,
    ) {
        if (!hapticsEnabled || !offRouteAlertsEnabled) return@LaunchedEffect
        if (!state.active || state.mode != GuidanceMode.FOLLOW_ROUTE || !state.offRoute) return@LaunchedEffect

        while (isActive) {
            val currentState = latestState
            if (!currentState.active || currentState.mode != GuidanceMode.FOLLOW_ROUTE || !currentState.offRoute) {
                return@LaunchedEffect
            }
            DebugTelemetry.log(
                "TurnByTurn",
                "haptic=off_route distanceToRouteM=${currentState.distanceToRouteMeters?.toInt() ?: "na"} " +
                    "repeatSeconds=$offRouteRepeatSeconds",
            )
            vibrator?.vibrate(OFF_ROUTE_ALERT_EFFECT)
            delay(offRouteRepeatSeconds.coerceAtLeast(OFF_ROUTE_MIN_REPEAT_SECONDS) * 1_000L)
        }
    }

    LaunchedEffect(
        state.active,
        state.mode,
        state.offRoute,
        hapticsEnabled,
        offRouteAlertsEnabled,
    ) {
        if (!state.active || state.mode != GuidanceMode.FOLLOW_ROUTE) {
            offRouteEpisodeActive = false
            return@LaunchedEffect
        }
        if (!hapticsEnabled || !offRouteAlertsEnabled) {
            offRouteEpisodeActive = false
            return@LaunchedEffect
        }
        if (state.offRoute) {
            offRouteEpisodeActive = true
            return@LaunchedEffect
        }
        if (!offRouteEpisodeActive) return@LaunchedEffect

        offRouteEpisodeActive = false
        DebugTelemetry.log(
            "TurnByTurn",
            "haptic=back_on_route distanceToRouteM=${state.distanceToRouteMeters?.toInt() ?: "na"}",
        )
        vibrator?.vibrate(BACK_ON_ROUTE_ALERT_EFFECT)
    }
}

private fun TurnHapticAlertEvent.telemetryMessage(vibratorAvailable: Boolean): String =
    buildString {
        if (outcome == TurnHapticAlertOutcome.FIRED) append("haptic=turn ")
        append("turnAlert=${outcome.telemetryValue} ")
        append("trigger=${trigger?.telemetryValue ?: "na"} ")
        append("command=${instruction.command} index=${instruction.trackPointIndex} ")
        append("distanceM=${distanceMeters.toInt()} alertDistanceM=${alertDistanceMeters.toInt()} ")
        append("previousDistanceM=${previousDistanceMeters?.toInt() ?: "na"} ")
        append("overshootM=${overshootMeters?.toInt() ?: "na"} ")
        append("gpsIntervalMs=$gpsDeliveryIntervalMs ")
        append(
            "speedMps=${
                speedMps
                    ?.takeIf { it.isFinite() }
                    ?.let { String.format(java.util.Locale.US, "%.1f", it) }
                    ?: "na"
            } ",
        )
        append(
            "reason=${reason ?: "na"} turnMode=$turnAlertsMode profile=$activityProfile " +
                "vibratorAvailable=$vibratorAvailable",
        )
    }

private fun turnAlertEffect(command: RouteInstructionCommand): VibrationEffect =
    when (command) {
        RouteInstructionCommand.SHARP_LEFT,
        RouteInstructionCommand.SHARP_RIGHT,
        RouteInstructionCommand.FINISH,
        -> STRONG_TURN_ALERT_EFFECT
        else -> REGULAR_TURN_ALERT_EFFECT
    }

private const val OFF_ROUTE_MIN_REPEAT_SECONDS = 15

private val REGULAR_TURN_ALERT_EFFECT: VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0L, 80L, 70L, 80L), -1)

private val STRONG_TURN_ALERT_EFFECT: VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0L, 100L, 70L, 100L), -1)

private val OFF_ROUTE_ALERT_EFFECT: VibrationEffect =
    VibrationEffect.createWaveform(
        longArrayOf(0L, 100L, 70L, 100L, 70L, 100L, 70L, 100L, 70L, 220L),
        -1,
    )

private val BACK_ON_ROUTE_ALERT_EFFECT: VibrationEffect =
    VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE)

private val ARRIVAL_ALERT_EFFECT: VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0L, 90L, 70L, 90L, 70L, 180L), -1)
