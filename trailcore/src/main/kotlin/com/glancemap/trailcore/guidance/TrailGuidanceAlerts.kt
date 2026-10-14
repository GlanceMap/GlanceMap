package com.glancemap.trailcore.guidance

private const val GUIDANCE_ACTIVITY_PROFILE_BIKE = "BIKE"
private const val GUIDANCE_TURN_ALERTS_OFF = "OFF"
private const val GUIDANCE_TURN_ALERTS_ALL = "ALL"
private const val DEFAULT_GUIDANCE_ACTIVITY_PROFILE = "HIKE"
private const val DEFAULT_GUIDANCE_GPS_INTERVAL_MS = 3_000L

fun turnAlertDistanceMeters(
    speedMps: Float?,
    activityProfile: String = DEFAULT_GUIDANCE_ACTIVITY_PROFILE,
): Double {
    val speed =
        speedMps
            ?.takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: return turnAlertDefaultDistanceMeters(activityProfile)
    return (speed * TURN_ALERT_LOOKAHEAD_SECONDS)
        .coerceIn(
            TURN_ALERT_MIN_DISTANCE_METERS,
            turnAlertMaxDistanceMeters(activityProfile),
        )
}

fun turnHapticDistanceMeters(
    speedMps: Float?,
    activityProfile: String = DEFAULT_GUIDANCE_ACTIVITY_PROFILE,
    gpsDeliveryIntervalMs: Long = DEFAULT_GUIDANCE_GPS_INTERVAL_MS,
): Double {
    val speed =
        speedMps
            ?.takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
    val gpsDeliveryIntervalSeconds =
        gpsDeliveryIntervalMs
            .takeIf { it > 0L }
            ?.coerceAtMost(TURN_HAPTIC_MAX_GPS_INTERVAL_MS)
            ?.div(1_000.0)
            ?: DEFAULT_GUIDANCE_GPS_INTERVAL_MS / 1_000.0
    val lookAheadSeconds =
        TURN_HAPTIC_BASE_LOOKAHEAD_SECONDS +
            gpsDeliveryIntervalSeconds * TURN_HAPTIC_GPS_INTERVAL_WEIGHT
    val distanceMeters =
        speed?.let { it * lookAheadSeconds }
            ?: defaultTurnHapticDistanceForGpsInterval(
                activityProfile = activityProfile,
                gpsDeliveryIntervalSeconds = gpsDeliveryIntervalSeconds,
            )
    return distanceMeters
        .coerceIn(
            TURN_HAPTIC_MIN_DISTANCE_METERS,
            turnHapticMaxDistanceMeters(activityProfile),
        )
}

private fun defaultTurnHapticDistanceForGpsInterval(
    activityProfile: String,
    gpsDeliveryIntervalSeconds: Double,
): Double {
    val extraGpsDelaySeconds =
        (gpsDeliveryIntervalSeconds - DEFAULT_GUIDANCE_GPS_INTERVAL_MS / 1_000.0)
            .coerceAtLeast(0.0)
    return turnHapticDefaultDistanceMeters(activityProfile) +
        turnHapticAssumedSpeedMps(activityProfile) * extraGpsDelaySeconds * TURN_HAPTIC_GPS_INTERVAL_WEIGHT
}

fun turnHapticCrossingRecoveryMeters(
    speedMps: Float?,
    activityProfile: String = DEFAULT_GUIDANCE_ACTIVITY_PROFILE,
    gpsDeliveryIntervalMs: Long = DEFAULT_GUIDANCE_GPS_INTERVAL_MS,
): Double {
    val alertDistanceMeters =
        turnHapticDistanceMeters(
            speedMps = speedMps,
            activityProfile = activityProfile,
            gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
        )
    val speed = speedMps?.takeIf { it.isFinite() && it > 0f }?.toDouble()
    val gpsTravelMeters =
        speed?.let {
            val intervalSeconds =
                gpsDeliveryIntervalMs
                    .takeIf { interval -> interval > 0L }
                    ?.coerceAtMost(TURN_HAPTIC_MAX_GPS_INTERVAL_MS)
                    ?.div(1_000.0)
                    ?: DEFAULT_GUIDANCE_GPS_INTERVAL_MS / 1_000.0
            it * intervalSeconds
        } ?: alertDistanceMeters
    return (alertDistanceMeters + gpsTravelMeters)
        .coerceAtMost(turnHapticCrossingRecoveryMaxMeters(activityProfile))
}

fun turnAlertMaxDistanceMeters(activityProfile: String): Double =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
        TURN_ALERT_BIKE_MAX_DISTANCE_METERS
    } else {
        TURN_ALERT_MAX_DISTANCE_METERS
    }

private fun turnAlertDefaultDistanceMeters(activityProfile: String): Double =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
        TURN_ALERT_BIKE_DEFAULT_DISTANCE_METERS
    } else {
        TURN_ALERT_DEFAULT_DISTANCE_METERS
    }

private fun turnHapticMaxDistanceMeters(activityProfile: String): Double =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
        TURN_HAPTIC_BIKE_MAX_DISTANCE_METERS
    } else {
        TURN_HAPTIC_MAX_DISTANCE_METERS
    }

private fun turnHapticCrossingRecoveryMaxMeters(activityProfile: String): Double =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
        TURN_HAPTIC_BIKE_CROSSING_RECOVERY_MAX_METERS
    } else {
        TURN_HAPTIC_CROSSING_RECOVERY_MAX_METERS
    }

private fun turnHapticDefaultDistanceMeters(activityProfile: String): Double =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
        TURN_HAPTIC_BIKE_DEFAULT_DISTANCE_METERS
    } else {
        TURN_HAPTIC_DEFAULT_DISTANCE_METERS
    }

private fun turnHapticAssumedSpeedMps(activityProfile: String): Double =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
        TURN_HAPTIC_BIKE_ASSUMED_SPEED_MPS
    } else {
        TURN_HAPTIC_ASSUMED_SPEED_MPS
    }

private const val TURN_ALERT_DEFAULT_DISTANCE_METERS = 35.0
private const val TURN_ALERT_BIKE_DEFAULT_DISTANCE_METERS = 70.0
private const val TURN_ALERT_MIN_DISTANCE_METERS = 35.0
private const val TURN_ALERT_MAX_DISTANCE_METERS = 90.0
private const val TURN_ALERT_BIKE_MAX_DISTANCE_METERS = 180.0
private const val TURN_ALERT_LOOKAHEAD_SECONDS = 8.0
private const val TURN_HAPTIC_DEFAULT_DISTANCE_METERS = 12.0
private const val TURN_HAPTIC_BIKE_DEFAULT_DISTANCE_METERS = 25.0
private const val TURN_HAPTIC_MIN_DISTANCE_METERS = 10.0
private const val TURN_HAPTIC_MAX_DISTANCE_METERS = 25.0
private const val TURN_HAPTIC_BIKE_MAX_DISTANCE_METERS = 60.0
private const val TURN_HAPTIC_BASE_LOOKAHEAD_SECONDS = 4.0
private const val TURN_HAPTIC_GPS_INTERVAL_WEIGHT = 0.5
private const val TURN_HAPTIC_ASSUMED_SPEED_MPS = 1.5
private const val TURN_HAPTIC_BIKE_ASSUMED_SPEED_MPS = 5.0
private const val TURN_HAPTIC_MAX_GPS_INTERVAL_MS = 10_000L
private const val TURN_HAPTIC_CROSSING_RECOVERY_MAX_METERS = 40.0
private const val TURN_HAPTIC_BIKE_CROSSING_RECOVERY_MAX_METERS = 120.0

enum class TrailGuidanceAlertOutcome(
    val telemetryValue: String,
) {
    FIRED("fired"),
    FILTERED("filtered"),
    OFF_ROUTE("off_route"),
    MISSED_WINDOW("missed_window"),
}

enum class TrailGuidanceAlertTrigger(
    val telemetryValue: String,
) {
    WINDOW("window"),
    CROSSING("crossing"),
}

enum class TrailGuidanceAlertTrackerState(
    val telemetryValue: String,
) {
    ACTIVE("active"),
    TERMINAL("terminal"),
}

data class TrailGuidanceObservationSnapshot(
    val instructionKey: String?,
    val instructionIndex: Int?,
    val projectedRouteProgressMeters: Double?,
    val distanceToInstructionMeters: Double?,
    val speedMps: Float?,
    val fixTimestampMs: Long?,
    val gpsDeliveryIntervalMs: Long?,
    val alertDistanceMeters: Double?,
)

data class TrailGuidanceAlertSample(
    val routeKey: String,
    val active: Boolean,
    val mode: TrailGuidanceMode,
    val offRoute: Boolean,
    val instruction: TrailGuidanceInstruction?,
    val distanceToInstructionMeters: Double?,
    val distanceFromStartMeters: Double?,
    val speedMps: Float?,
    val activityProfile: String,
    val gpsDeliveryIntervalMs: Long,
    val hapticsEnabled: Boolean,
    val turnAlertsMode: String,
    val fixTimestampMs: Long? = null,
)

data class TrailGuidanceAlertEvent(
    val instructionKey: String,
    val outcome: TrailGuidanceAlertOutcome,
    val trigger: TrailGuidanceAlertTrigger?,
    val instruction: TrailGuidanceInstruction,
    val distanceMeters: Double,
    val alertDistanceMeters: Double,
    val previousDistanceMeters: Double? = null,
    val overshootMeters: Double? = null,
    val reason: String? = null,
    val previousObservation: TrailGuidanceObservationSnapshot?,
    val currentObservation: TrailGuidanceObservationSnapshot,
    val recoveryDistanceMeters: Double? = null,
    val currentActivityProfile: String,
    val currentTurnAlertsMode: String,
    val currentGuidanceMode: TrailGuidanceMode,
    val currentGuidanceActive: Boolean,
    val currentOffRoute: Boolean,
    val terminalTrackerState: TrailGuidanceAlertTrackerState = TrailGuidanceAlertTrackerState.ACTIVE,
)

class TrailGuidanceAlertTracker {
    private var previousObservation: TrailGuidanceObservation? = null
    private val terminalInstructionKeys = mutableSetOf<String>()
    private val lastOutcomeSignatures = mutableMapOf<String, String>()

    fun update(sample: TrailGuidanceAlertSample): List<TrailGuidanceAlertEvent> {
        if (!sample.active) {
            reset()
            return emptyList()
        }

        val currentObservation = sample.toObservation()
        val events = mutableListOf<TrailGuidanceAlertEvent>()
        crossingEvent(
            previous = previousObservation,
            current = currentObservation,
            sample = sample,
        )?.let(events::add)
        windowEvent(current = currentObservation, sample = sample)?.let(events::add)
        previousObservation = currentObservation
        return events.mapNotNull(::recordIfNew)
    }

    private fun crossingEvent(
        previous: TrailGuidanceObservation?,
        current: TrailGuidanceObservation?,
        sample: TrailGuidanceAlertSample,
    ): TrailGuidanceAlertEvent? =
        previous
            ?.takeIf { it.wasSkippedBy(current, sample) }
            ?.takeUnless { it.instructionKey in terminalInstructionKeys }
            ?.let { crossedObservation ->
                buildCrossingEvent(
                    crossedObservation = crossedObservation,
                    current = current,
                    sample = sample,
                )
            }

    private fun buildCrossingEvent(
        crossedObservation: TrailGuidanceObservation,
        current: TrailGuidanceObservation?,
        sample: TrailGuidanceAlertSample,
    ): TrailGuidanceAlertEvent? {
        val block = alertBlock(sample, crossedObservation.instruction.command)
        val routeProgressMeters = sample.distanceFromStartMeters
        return when {
            block != null ->
                crossedObservation.event(
                    outcome = block.outcome,
                    trigger = TrailGuidanceAlertTrigger.CROSSING,
                    reason = block.reason,
                    sample = sample,
                )
            routeProgressMeters != null ->
                eligibleCrossingEvent(
                    crossedObservation = crossedObservation,
                    current = current,
                    sample = sample,
                    routeProgressMeters = routeProgressMeters,
                )
            else -> null
        }
    }

    private fun eligibleCrossingEvent(
        crossedObservation: TrailGuidanceObservation,
        current: TrailGuidanceObservation?,
        sample: TrailGuidanceAlertSample,
        routeProgressMeters: Double,
    ): TrailGuidanceAlertEvent {
        val overshootMeters =
            (routeProgressMeters - crossedObservation.instruction.distanceFromStartMeters)
                .coerceAtLeast(0.0)
        val currentTurnNeedsAlert =
            current?.insideWindow == true &&
                alertBlock(sample, current.instruction.command) == null
        val recoveryDistanceMeters =
            turnHapticCrossingRecoveryMeters(
                speedMps = sample.speedMps,
                activityProfile = sample.activityProfile,
                gpsDeliveryIntervalMs = sample.gpsDeliveryIntervalMs,
            )
        return when {
            currentTurnNeedsAlert ->
                crossedObservation.event(
                    outcome = TrailGuidanceAlertOutcome.MISSED_WINDOW,
                    trigger = TrailGuidanceAlertTrigger.CROSSING,
                    overshootMeters = overshootMeters,
                    reason = "next_turn_due",
                    recoveryDistanceMeters = recoveryDistanceMeters,
                    sample = sample,
                )
            overshootMeters <= recoveryDistanceMeters ->
                crossedObservation.event(
                    outcome = TrailGuidanceAlertOutcome.FIRED,
                    trigger = TrailGuidanceAlertTrigger.CROSSING,
                    overshootMeters = overshootMeters,
                    reason = "gps_crossing_recovery",
                    recoveryDistanceMeters = recoveryDistanceMeters,
                    sample = sample,
                )
            else ->
                crossedObservation.event(
                    outcome = TrailGuidanceAlertOutcome.MISSED_WINDOW,
                    trigger = TrailGuidanceAlertTrigger.CROSSING,
                    overshootMeters = overshootMeters,
                    reason = "recovery_too_late",
                    recoveryDistanceMeters = recoveryDistanceMeters,
                    sample = sample,
                )
        }
    }

    private fun windowEvent(
        current: TrailGuidanceObservation?,
        sample: TrailGuidanceAlertSample,
    ): TrailGuidanceAlertEvent? =
        current
            ?.takeIf { it.insideWindow }
            ?.takeUnless { it.instructionKey in terminalInstructionKeys }
            ?.let { dueObservation ->
                val block = alertBlock(sample, dueObservation.instruction.command)
                dueObservation.event(
                    outcome = block?.outcome ?: TrailGuidanceAlertOutcome.FIRED,
                    trigger = TrailGuidanceAlertTrigger.WINDOW,
                    reason = block?.reason,
                    sample = sample,
                )
            }

    private fun alertBlock(
        sample: TrailGuidanceAlertSample,
        command: TrailGuidanceCommand,
    ): TrailGuidanceAlertBlock? =
        when {
            !sample.hapticsEnabled ->
                TrailGuidanceAlertBlock(TrailGuidanceAlertOutcome.FILTERED, "haptics_disabled")
            !shouldAlertForTurn(sample.turnAlertsMode, command) ->
                TrailGuidanceAlertBlock(TrailGuidanceAlertOutcome.FILTERED, "turn_mode")
            sample.mode != TrailGuidanceMode.FOLLOW_ROUTE ->
                TrailGuidanceAlertBlock(TrailGuidanceAlertOutcome.FILTERED, "guidance_mode")
            sample.offRoute ->
                TrailGuidanceAlertBlock(TrailGuidanceAlertOutcome.OFF_ROUTE, "off_route")
            else -> null
        }

    private fun recordIfNew(event: TrailGuidanceAlertEvent): TrailGuidanceAlertEvent? {
        val signature = "${event.outcome}:${event.trigger}:${event.reason}"
        if (lastOutcomeSignatures[event.instructionKey] == signature) return null
        lastOutcomeSignatures[event.instructionKey] = signature
        if (event.outcome == TrailGuidanceAlertOutcome.FIRED || event.outcome == TrailGuidanceAlertOutcome.MISSED_WINDOW) {
            terminalInstructionKeys += event.instructionKey
        }
        return event.copy(
            terminalTrackerState =
                if (event.instructionKey in terminalInstructionKeys) {
                    TrailGuidanceAlertTrackerState.TERMINAL
                } else {
                    TrailGuidanceAlertTrackerState.ACTIVE
                },
        )
    }

    private fun reset() {
        previousObservation = null
        terminalInstructionKeys.clear()
        lastOutcomeSignatures.clear()
    }
}

private data class TrailGuidanceObservation(
    val instructionKey: String,
    val instruction: TrailGuidanceInstruction,
    val distanceMeters: Double,
    val alertDistanceMeters: Double,
    val projectedRouteProgressMeters: Double?,
    val speedMps: Float?,
    val fixTimestampMs: Long?,
    val activityProfile: String,
    val turnAlertsMode: String,
    val gpsDeliveryIntervalMs: Long,
) {
    val insideWindow: Boolean = distanceMeters <= alertDistanceMeters

    fun wasSkippedBy(
        current: TrailGuidanceObservation?,
        sample: TrailGuidanceAlertSample,
    ): Boolean {
        val instructionChanged = instructionKey != current?.instructionKey
        val routeProgressMeters = sample.distanceFromStartMeters?.takeIf(Double::isFinite)
        val crossedInstruction = routeProgressMeters?.let { it >= instruction.distanceFromStartMeters } == true
        return instructionChanged && !insideWindow && crossedInstruction
    }

    // The arguments remain separate to mirror the crossing-decision telemetry exactly.
    @Suppress("LongParameterList")
    fun event(
        outcome: TrailGuidanceAlertOutcome,
        trigger: TrailGuidanceAlertTrigger,
        overshootMeters: Double? = null,
        reason: String? = null,
        recoveryDistanceMeters: Double? = null,
        sample: TrailGuidanceAlertSample,
    ): TrailGuidanceAlertEvent =
        TrailGuidanceAlertEvent(
            instructionKey = instructionKey,
            outcome = outcome,
            trigger = trigger,
            instruction = instruction,
            distanceMeters = if (trigger == TrailGuidanceAlertTrigger.CROSSING) 0.0 else distanceMeters,
            alertDistanceMeters = alertDistanceMeters,
            previousDistanceMeters = distanceMeters.takeIf { trigger == TrailGuidanceAlertTrigger.CROSSING },
            overshootMeters = overshootMeters,
            reason = reason,
            previousObservation = snapshot().takeIf { trigger == TrailGuidanceAlertTrigger.CROSSING },
            currentObservation = sample.toSnapshot(),
            recoveryDistanceMeters = recoveryDistanceMeters,
            currentActivityProfile = sample.activityProfile,
            currentTurnAlertsMode = sample.turnAlertsMode,
            currentGuidanceMode = sample.mode,
            currentGuidanceActive = sample.active,
            currentOffRoute = sample.offRoute,
        )

    fun snapshot(): TrailGuidanceObservationSnapshot =
        TrailGuidanceObservationSnapshot(
            instructionKey = instructionKey,
            instructionIndex = instruction.trackPointIndex,
            projectedRouteProgressMeters = projectedRouteProgressMeters,
            distanceToInstructionMeters = distanceMeters,
            speedMps = speedMps,
            fixTimestampMs = fixTimestampMs,
            gpsDeliveryIntervalMs = gpsDeliveryIntervalMs.takeIf { it > 0L },
            alertDistanceMeters = alertDistanceMeters,
        )
}

private data class TrailGuidanceAlertBlock(
    val outcome: TrailGuidanceAlertOutcome,
    val reason: String,
)

private fun TrailGuidanceAlertSample.toObservation(): TrailGuidanceObservation? {
    val currentInstruction = instruction
    val distanceMeters = distanceToInstructionMeters?.takeIf(Double::isFinite)
    return if (currentInstruction != null && distanceMeters != null) {
        val alertDistanceMeters =
            turnHapticDistanceMeters(
                speedMps = speedMps,
                activityProfile = activityProfile,
                gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
            )
        TrailGuidanceObservation(
            instructionKey = currentInstruction.instructionKey(routeKey),
            instruction = currentInstruction,
            distanceMeters = distanceMeters,
            alertDistanceMeters = alertDistanceMeters,
            projectedRouteProgressMeters = distanceFromStartMeters?.takeIf(Double::isFinite),
            speedMps = speedMps,
            fixTimestampMs = fixTimestampMs?.takeIf { it > 0L },
            activityProfile = activityProfile,
            turnAlertsMode = turnAlertsMode,
            gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
        )
    } else {
        null
    }
}

private fun TrailGuidanceAlertSample.toSnapshot(): TrailGuidanceObservationSnapshot =
    TrailGuidanceObservationSnapshot(
        instructionKey = instruction?.instructionKey(routeKey),
        instructionIndex = instruction?.trackPointIndex,
        projectedRouteProgressMeters = distanceFromStartMeters?.takeIf(Double::isFinite),
        distanceToInstructionMeters = distanceToInstructionMeters?.takeIf(Double::isFinite),
        speedMps = speedMps?.takeIf(Float::isFinite),
        fixTimestampMs = fixTimestampMs?.takeIf { it > 0L },
        gpsDeliveryIntervalMs = gpsDeliveryIntervalMs.takeIf { it > 0L },
        alertDistanceMeters =
            turnHapticDistanceMeters(
                speedMps = speedMps,
                activityProfile = activityProfile,
                gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
            ),
    )

private fun TrailGuidanceInstruction.instructionKey(routeKey: String): String = "$routeKey:$trackPointIndex:$command"

internal fun TrailGuidanceAlertEvent.telemetryMessage(vibratorAvailable: Boolean): String =
    buildString {
        if (outcome == TrailGuidanceAlertOutcome.FIRED) append("haptic=turn ")
        append("turnAlert=${outcome.telemetryValue} ")
        append("trigger=${trigger?.telemetryValue ?: "na"} ")
        append("command=${instruction.command} index=${instruction.trackPointIndex} ")
        append("previousInstructionKey=${previousObservation?.instructionKey ?: "na"} ")
        append("previousInstructionIndex=${previousObservation?.instructionIndex ?: "na"} ")
        append("previousProgressM=${previousObservation?.projectedRouteProgressMeters.formatTelemetryNumber()} ")
        append(
            "previousDistanceToInstructionM=${
                previousObservation?.distanceToInstructionMeters.formatTelemetryNumber()
            } ",
        )
        append("previousAlertDistanceM=${previousObservation?.alertDistanceMeters.formatTelemetryNumber()} ")
        append("previousSpeedMps=${previousObservation?.speedMps.formatTelemetrySpeed()} ")
        append("previousFixTimestampMs=${previousObservation?.fixTimestampMs ?: "na"} ")
        append("previousGpsDeliveryIntervalMs=${previousObservation?.gpsDeliveryIntervalMs ?: "na"} ")
        append("currentInstructionKey=${currentObservation.instructionKey ?: "na"} ")
        append("currentInstructionIndex=${currentObservation.instructionIndex ?: "na"} ")
        append("currentProgressM=${currentObservation.projectedRouteProgressMeters.formatTelemetryNumber()} ")
        append(
            "currentDistanceToInstructionM=${
                currentObservation.distanceToInstructionMeters.formatTelemetryNumber()
            } ",
        )
        append("currentAlertDistanceM=${currentObservation.alertDistanceMeters.formatTelemetryNumber()} ")
        append("currentSpeedMps=${currentObservation.speedMps.formatTelemetrySpeed()} ")
        append("currentFixTimestampMs=${currentObservation.fixTimestampMs ?: "na"} ")
        append("currentGpsDeliveryIntervalMs=${currentObservation.gpsDeliveryIntervalMs ?: "na"} ")
        append("effectiveGpsRequestIntervalMs=na ")
        append("configuredGpsRequestIntervalMs=na callbackArrivalIntervalMs=na ")
        append("fixTimestampSpacingMs=${fixTimestampSpacingMs() ?: "na"} ")
        append("recoveryDistanceM=${recoveryDistanceMeters.formatTelemetryNumber()} ")
        append("terminalState=${terminalTrackerState.telemetryValue} ")
        append(
            "reason=${reason ?: "na"} guidanceMode=${currentGuidanceMode.name} " +
                "guidanceActive=$currentGuidanceActive offRoute=$currentOffRoute " +
                "turnMode=$currentTurnAlertsMode profile=$currentActivityProfile " +
                "screenState=na projectionSegmentIndex=na projectionContinuity=na " +
                "vibratorAvailable=$vibratorAvailable",
        )
    }

private fun TrailGuidanceAlertEvent.fixTimestampSpacingMs(): Long? =
    previousObservation?.fixTimestampMs?.let { previousTimestamp ->
        currentObservation.fixTimestampMs?.minus(previousTimestamp)
    }

private fun Double?.formatTelemetryNumber(): String =
    this
        ?.takeIf(Double::isFinite)
        ?.let { String.format(java.util.Locale.US, "%.1f", it) }
        ?: "na"

private fun Float?.formatTelemetrySpeed(): String =
    this
        ?.takeIf(Float::isFinite)
        ?.let { String.format(java.util.Locale.US, "%.1f", it) }
        ?: "na"

fun shouldAlertForTurn(
    mode: String,
    command: TrailGuidanceCommand,
): Boolean =
    when (mode) {
        GUIDANCE_TURN_ALERTS_OFF -> false
        GUIDANCE_TURN_ALERTS_ALL ->
            command != TrailGuidanceCommand.CONTINUE &&
                command != TrailGuidanceCommand.FINISH
        else ->
            command != TrailGuidanceCommand.CONTINUE &&
                command != TrailGuidanceCommand.SLIGHT_LEFT &&
                command != TrailGuidanceCommand.SLIGHT_RIGHT &&
                command != TrailGuidanceCommand.FINISH
    }
