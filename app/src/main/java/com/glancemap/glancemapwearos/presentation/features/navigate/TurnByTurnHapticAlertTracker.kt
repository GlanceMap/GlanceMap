package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceAlertEvent
import com.glancemap.trailcore.guidance.TrailGuidanceAlertSample
import com.glancemap.trailcore.guidance.TrailGuidanceAlertTracker
import com.glancemap.trailcore.guidance.TrailGuidanceInstruction
import com.glancemap.trailcore.guidance.TrailGuidanceObservationSnapshot
import org.mapsforge.core.model.LatLong
import java.util.Locale
import com.glancemap.trailcore.guidance.shouldAlertForTurn as trailShouldAlertForTurn

internal enum class TurnHapticAlertOutcome(
    val telemetryValue: String,
) {
    FIRED("fired"),
    FILTERED("filtered"),
    OFF_ROUTE("off_route"),
    MISSED_WINDOW("missed_window"),
}

internal enum class TurnHapticAlertTrigger(
    val telemetryValue: String,
) {
    WINDOW("window"),
    CROSSING("crossing"),
}

internal enum class TurnHapticTrackerState(
    val telemetryValue: String,
) {
    ACTIVE("active"),
    TERMINAL("terminal"),
}

internal data class TurnHapticObservationSnapshot(
    val instructionKey: String?,
    val instructionIndex: Int?,
    val projectedRouteProgressMeters: Double?,
    val distanceToInstructionMeters: Double?,
    val speedMps: Float?,
    val fixTimestampMs: Long?,
    val gpsDeliveryIntervalMs: Long?,
    val alertDistanceMeters: Double?,
)

internal data class TurnHapticAlertSample(
    val routeKey: String,
    val active: Boolean,
    val mode: GuidanceMode,
    val offRoute: Boolean,
    val instruction: RouteInstruction?,
    val distanceToInstructionMeters: Double?,
    val distanceFromStartMeters: Double?,
    val speedMps: Float?,
    val activityProfile: String,
    val gpsDeliveryIntervalMs: Long,
    val hapticsEnabled: Boolean,
    val turnAlertsMode: String,
    val fixTimestampMs: Long? = null,
)

internal data class TurnHapticAlertEvent(
    val instructionKey: String,
    val outcome: TurnHapticAlertOutcome,
    val trigger: TurnHapticAlertTrigger?,
    val instruction: RouteInstruction,
    val distanceMeters: Double,
    val alertDistanceMeters: Double,
    val previousDistanceMeters: Double? = null,
    val overshootMeters: Double? = null,
    val reason: String? = null,
    val previousObservation: TurnHapticObservationSnapshot?,
    val currentObservation: TurnHapticObservationSnapshot,
    val recoveryDistanceMeters: Double? = null,
    val currentActivityProfile: String,
    val currentTurnAlertsMode: String,
    val currentGuidanceMode: GuidanceMode,
    val currentGuidanceActive: Boolean,
    val currentOffRoute: Boolean,
    val terminalTrackerState: TurnHapticTrackerState = TurnHapticTrackerState.ACTIVE,
)

/** Watch-facing adapter. Alert timing and deduplication live in TrailCore. */
internal class TurnHapticAlertTracker {
    private val delegate = TrailGuidanceAlertTracker()

    fun update(
        sample: TurnHapticAlertSample,
    ): List<TurnHapticAlertEvent> = delegate.update(sample.toTrailSample()).map(TrailGuidanceAlertEvent::toWatchEvent)
}

private fun TurnHapticAlertSample.toTrailSample(): TrailGuidanceAlertSample =
    TrailGuidanceAlertSample(
        routeKey = routeKey,
        active = active,
        mode = mode,
        offRoute = offRoute,
        instruction = instruction?.toTrailInstruction(),
        distanceToInstructionMeters = distanceToInstructionMeters,
        distanceFromStartMeters = distanceFromStartMeters,
        speedMps = speedMps,
        activityProfile = activityProfile,
        gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
        hapticsEnabled = hapticsEnabled,
        turnAlertsMode = turnAlertsMode,
        fixTimestampMs = fixTimestampMs,
    )

private fun RouteInstruction.toTrailInstruction(): TrailGuidanceInstruction =
    TrailGuidanceInstruction(
        command = command,
        message = message,
        location = GeoPoint(latLong.latitude, latLong.longitude),
        trackPointIndex = trackPointIndex,
        distanceFromStartMeters = distanceFromStartMeters,
        turnAngleDegrees = turnAngleDegrees,
        source = source,
    )

private fun TrailGuidanceAlertEvent.toWatchEvent(): TurnHapticAlertEvent =
    TurnHapticAlertEvent(
        instructionKey = instructionKey,
        outcome = TurnHapticAlertOutcome.valueOf(outcome.name),
        trigger = trigger?.let { TurnHapticAlertTrigger.valueOf(it.name) },
        instruction = instruction.toWatchInstruction(),
        distanceMeters = distanceMeters,
        alertDistanceMeters = alertDistanceMeters,
        previousDistanceMeters = previousDistanceMeters,
        overshootMeters = overshootMeters,
        reason = reason,
        previousObservation = previousObservation?.toWatchSnapshot(),
        currentObservation = currentObservation.toWatchSnapshot(),
        recoveryDistanceMeters = recoveryDistanceMeters,
        currentActivityProfile = currentActivityProfile,
        currentTurnAlertsMode = currentTurnAlertsMode,
        currentGuidanceMode = currentGuidanceMode,
        currentGuidanceActive = currentGuidanceActive,
        currentOffRoute = currentOffRoute,
        terminalTrackerState = TurnHapticTrackerState.valueOf(terminalTrackerState.name),
    )

private fun TrailGuidanceInstruction.toWatchInstruction(): RouteInstruction =
    RouteInstruction(
        command = command,
        message = message,
        latLong = LatLong(location.latitude, location.longitude),
        trackPointIndex = trackPointIndex,
        distanceFromStartMeters = distanceFromStartMeters,
        turnAngleDegrees = turnAngleDegrees,
        source = source,
    )

private fun TrailGuidanceObservationSnapshot.toWatchSnapshot(): TurnHapticObservationSnapshot =
    TurnHapticObservationSnapshot(
        instructionKey = instructionKey,
        instructionIndex = instructionIndex,
        projectedRouteProgressMeters = projectedRouteProgressMeters,
        distanceToInstructionMeters = distanceToInstructionMeters,
        speedMps = speedMps,
        fixTimestampMs = fixTimestampMs,
        gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
        alertDistanceMeters = alertDistanceMeters,
    )

internal fun TurnHapticAlertEvent.telemetryMessage(vibratorAvailable: Boolean): String =
    buildString {
        if (outcome == TurnHapticAlertOutcome.FIRED) append("haptic=turn ")
        append("turnAlert=${outcome.telemetryValue} ")
        append("trigger=${trigger?.telemetryValue ?: "na"} ")
        append("command=${instruction.command} index=${instruction.trackPointIndex} ")
        append("previousInstructionKey=${previousObservation?.instructionKey ?: "na"} ")
        append("previousInstructionIndex=${previousObservation?.instructionIndex ?: "na"} ")
        append("previousProgressM=${previousObservation?.projectedRouteProgressMeters.formatTelemetryNumber()} ")
        val previousInstructionDistance = previousObservation?.distanceToInstructionMeters.formatTelemetryNumber()
        append("previousDistanceToInstructionM=$previousInstructionDistance ")
        append("previousAlertDistanceM=${previousObservation?.alertDistanceMeters.formatTelemetryNumber()} ")
        append("previousSpeedMps=${previousObservation?.speedMps.formatTelemetrySpeed()} ")
        append("previousFixTimestampMs=${previousObservation?.fixTimestampMs ?: "na"} ")
        append("previousGpsDeliveryIntervalMs=${previousObservation?.gpsDeliveryIntervalMs ?: "na"} ")
        append("currentInstructionKey=${currentObservation.instructionKey ?: "na"} ")
        append("currentInstructionIndex=${currentObservation.instructionIndex ?: "na"} ")
        append("currentProgressM=${currentObservation.projectedRouteProgressMeters.formatTelemetryNumber()} ")
        append(
            "currentDistanceToInstructionM=${currentObservation.distanceToInstructionMeters.formatTelemetryNumber()} ",
        )
        append("currentAlertDistanceM=${currentObservation.alertDistanceMeters.formatTelemetryNumber()} ")
        append("currentSpeedMps=${currentObservation.speedMps.formatTelemetrySpeed()} ")
        append("currentFixTimestampMs=${currentObservation.fixTimestampMs ?: "na"} ")
        append("currentGpsDeliveryIntervalMs=${currentObservation.gpsDeliveryIntervalMs ?: "na"} ")
        append("effectiveGpsRequestIntervalMs=na configuredGpsRequestIntervalMs=na callbackArrivalIntervalMs=na ")
        val fixTimestampSpacingMillis =
            previousObservation?.fixTimestampMs?.let { currentObservation.fixTimestampMs?.minus(it) } ?: "na"
        append("fixTimestampSpacingMs=$fixTimestampSpacingMillis ")
        append("recoveryDistanceM=${recoveryDistanceMeters.formatTelemetryNumber()} ")
        append("terminalState=${terminalTrackerState.telemetryValue} ")
        append("reason=${reason ?: "na"} guidanceMode=${currentGuidanceMode.name} ")
        append("guidanceActive=$currentGuidanceActive offRoute=$currentOffRoute ")
        append("turnMode=$currentTurnAlertsMode profile=$currentActivityProfile ")
        append("screenState=na projectionSegmentIndex=na projectionContinuity=na ")
        append("vibratorAvailable=$vibratorAvailable")
    }

private fun Double?.formatTelemetryNumber(): String {
    val value = this?.takeIf(Double::isFinite) ?: return "na"
    return String.format(Locale.US, "%.1f", value)
}

private fun Float?.formatTelemetrySpeed(): String {
    val value = this?.takeIf(Float::isFinite) ?: return "na"
    return String.format(Locale.US, "%.1f", value)
}

internal fun shouldAlertForTurn(
    mode: String,
    command: RouteInstructionCommand,
): Boolean = trailShouldAlertForTurn(mode, command)
