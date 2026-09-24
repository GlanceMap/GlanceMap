package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class TurnByTurnHapticAlertTrackerTest {
    @Test
    fun turnInsideAdaptiveWindowFiresOnce() {
        val tracker = TurnHapticAlertTracker()
        val sample = sample(instruction = instruction(RouteInstructionCommand.LEFT, 100.0), progressMeters = 92.0)

        val firstEvents = tracker.update(sample)
        val repeatedEvents = tracker.update(sample)

        assertEquals(TurnHapticAlertOutcome.FIRED, firstEvents.single().outcome)
        assertEquals(TurnHapticAlertTrigger.WINDOW, firstEvents.single().trigger)
        assertTrue(repeatedEvents.isEmpty())
    }

    @Test
    fun configuredTurnModeIsReportedAsFiltered() {
        val tracker = TurnHapticAlertTracker()

        val events =
            tracker.update(
                sample(
                    instruction = instruction(RouteInstructionCommand.CONTINUE, 100.0),
                    progressMeters = 95.0,
                    turnAlertsMode = SettingsRepository.TURN_BY_TURN_TURN_ALERTS_IMPORTANT,
                ),
            )

        assertEquals(TurnHapticAlertOutcome.FILTERED, events.single().outcome)
        assertEquals("turn_mode", events.single().reason)
    }

    @Test
    fun allTurnsIncludesSlightTurnsButExcludesContinueAndArrival() {
        val mode = SettingsRepository.TURN_BY_TURN_TURN_ALERTS_ALL

        assertTrue(shouldAlertForTurn(mode, RouteInstructionCommand.SLIGHT_LEFT))
        assertTrue(shouldAlertForTurn(mode, RouteInstructionCommand.RIGHT))
        assertFalse(shouldAlertForTurn(mode, RouteInstructionCommand.CONTINUE))
        assertFalse(shouldAlertForTurn(mode, RouteInstructionCommand.FINISH))
    }

    @Test
    fun majorTurnsExcludesSlightTurnsAndArrival() {
        val mode = SettingsRepository.TURN_BY_TURN_TURN_ALERTS_IMPORTANT

        assertTrue(shouldAlertForTurn(mode, RouteInstructionCommand.LEFT))
        assertFalse(shouldAlertForTurn(mode, RouteInstructionCommand.SLIGHT_RIGHT))
        assertFalse(shouldAlertForTurn(mode, RouteInstructionCommand.CONTINUE))
        assertFalse(shouldAlertForTurn(mode, RouteInstructionCommand.FINISH))
    }

    @Test
    fun eligibleTurnSuppressedOffRouteIsReportedSeparately() {
        val tracker = TurnHapticAlertTracker()

        val events =
            tracker.update(
                sample(
                    instruction = instruction(RouteInstructionCommand.RIGHT, 100.0),
                    progressMeters = 95.0,
                    offRoute = true,
                ),
            )

        assertEquals(TurnHapticAlertOutcome.OFF_ROUTE, events.single().outcome)
    }

    @Test
    fun gpsJumpAcrossTurnFiresCrossingRecovery() {
        val tracker = TurnHapticAlertTracker()
        tracker.update(
            sample(
                instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                progressMeters = 70.0,
            ),
        )

        val events =
            tracker.update(
                sample(
                    instruction = instruction(RouteInstructionCommand.RIGHT, 200.0),
                    progressMeters = 110.0,
                ),
            )

        assertEquals(TurnHapticAlertOutcome.FIRED, events.single().outcome)
        assertEquals(TurnHapticAlertTrigger.CROSSING, events.single().trigger)
        assertEquals("gps_crossing_recovery", events.single().reason)
    }

    @Test
    fun excessivelyLateGpsCrossingIsReportedAsMissedWindow() {
        val tracker = TurnHapticAlertTracker()
        tracker.update(
            sample(
                instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                progressMeters = 70.0,
            ),
        )

        val events =
            tracker.update(
                sample(
                    instruction = instruction(RouteInstructionCommand.RIGHT, 200.0),
                    progressMeters = 170.0,
                ),
            )

        assertEquals(TurnHapticAlertOutcome.MISSED_WINDOW, events.single().outcome)
        assertEquals("recovery_too_late", events.single().reason)
    }

    @Test
    fun crossingTelemetryKeepsPreviousAndCurrentObservationsSeparate() {
        val tracker = TurnHapticAlertTracker()
        tracker.update(
            sample(
                instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                progressMeters = 70.0,
                speedMps = 1.1f,
                gpsDeliveryIntervalMs = 2_500L,
                fixTimestampMs = 1_000L,
            ),
        )

        val event =
            tracker
                .update(
                    sample(
                        instruction = instruction(RouteInstructionCommand.RIGHT, 200.0),
                        progressMeters = 110.0,
                        speedMps = 4.2f,
                        gpsDeliveryIntervalMs = 7_000L,
                        fixTimestampMs = 5_500L,
                    ),
                ).single()

        assertEquals("route:100:LEFT", event.previousObservation?.instructionKey)
        assertEquals(100, event.previousObservation?.instructionIndex)
        assertEquals(70.0, event.previousObservation?.projectedRouteProgressMeters ?: -1.0, 0.0)
        assertEquals(30.0, event.previousObservation?.distanceToInstructionMeters ?: -1.0, 0.0)
        assertEquals(1.1f, event.previousObservation?.speedMps ?: -1.0f, 0.0f)
        assertEquals(1_000L, event.previousObservation?.fixTimestampMs)
        assertEquals(2_500L, event.previousObservation?.gpsDeliveryIntervalMs)
        assertEquals("route:200:RIGHT", event.currentObservation.instructionKey)
        assertEquals(200, event.currentObservation.instructionIndex)
        assertEquals(110.0, event.currentObservation.projectedRouteProgressMeters ?: -1.0, 0.0)
        assertEquals(90.0, event.currentObservation.distanceToInstructionMeters ?: -1.0, 0.0)
        assertEquals(4.2f, event.currentObservation.speedMps ?: -1.0f, 0.0f)
        assertEquals(5_500L, event.currentObservation.fixTimestampMs)
        assertEquals(7_000L, event.currentObservation.gpsDeliveryIntervalMs)
    }

    @Test
    fun recoveryTooLateTelemetryReportsCurrentDecisionInputs() {
        val tracker = TurnHapticAlertTracker()
        tracker.update(
            sample(
                instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                progressMeters = 70.0,
                speedMps = 1.1f,
                gpsDeliveryIntervalMs = 2_500L,
                fixTimestampMs = 1_000L,
            ),
        )

        val event =
            tracker
                .update(
                    sample(
                        instruction = instruction(RouteInstructionCommand.RIGHT, 200.0),
                        progressMeters = 170.0,
                        speedMps = 4.2f,
                        gpsDeliveryIntervalMs = 7_000L,
                        fixTimestampMs = 5_500L,
                    ),
                ).single()
        val telemetry = event.telemetryMessage(vibratorAvailable = false)

        assertEquals(TurnHapticAlertOutcome.MISSED_WINDOW, event.outcome)
        assertEquals("recovery_too_late", event.reason)
        assertEquals(
            turnHapticCrossingRecoveryMeters(
                speedMps = 4.2f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                gpsDeliveryIntervalMs = 7_000L,
            ),
            event.recoveryDistanceMeters ?: -1.0,
            0.0,
        )
        assertTrue(telemetry.contains("previousSpeedMps=1.1"))
        assertTrue(telemetry.contains("currentSpeedMps=4.2"))
        assertTrue(telemetry.contains("previousGpsDeliveryIntervalMs=2500"))
        assertTrue(telemetry.contains("currentGpsDeliveryIntervalMs=7000"))
        assertTrue(telemetry.contains("effectiveGpsRequestIntervalMs=na"))
        assertTrue(telemetry.contains("previousFixTimestampMs=1000"))
        assertTrue(telemetry.contains("currentFixTimestampMs=5500"))
        assertTrue(telemetry.contains("fixTimestampSpacingMs=4500"))
        assertTrue(telemetry.contains("currentProgressM=170.0"))
        assertTrue(telemetry.contains("currentDistanceToInstructionM=30.0"))
        assertTrue(telemetry.contains("recoveryDistanceM="))
        assertTrue(telemetry.contains("terminalState=terminal"))
    }

    @Test
    fun missingTimingFieldsAreExportedAsNa() {
        val event =
            TurnHapticAlertTracker()
                .update(
                    sample(
                        instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                        progressMeters = 95.0,
                        speedMps = null,
                        gpsDeliveryIntervalMs = 0L,
                        fixTimestampMs = null,
                    ),
                ).single()

        val telemetry = event.telemetryMessage(vibratorAvailable = false)

        assertTrue(telemetry.contains("currentFixTimestampMs=na"))
        assertTrue(telemetry.contains("currentGpsDeliveryIntervalMs=na"))
        assertTrue(telemetry.contains("effectiveGpsRequestIntervalMs=na"))
        assertTrue(telemetry.contains("fixTimestampSpacingMs=na"))
        assertTrue(telemetry.contains("configuredGpsRequestIntervalMs=na"))
        assertTrue(telemetry.contains("callbackArrivalIntervalMs=na"))
    }

    @Test
    fun terminalAndDuplicateBookkeepingRemainUnchanged() {
        val tracker = TurnHapticAlertTracker()
        tracker.update(
            sample(
                instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                progressMeters = 70.0,
            ),
        )

        val firstEvent =
            tracker
                .update(
                    sample(
                        instruction = instruction(RouteInstructionCommand.RIGHT, 200.0),
                        progressMeters = 170.0,
                    ),
                ).single()
        val duplicateEvents =
            tracker.update(
                sample(
                    instruction = instruction(RouteInstructionCommand.RIGHT, 200.0),
                    progressMeters = 170.0,
                ),
            )

        assertEquals(TurnHapticTrackerState.TERMINAL, firstEvent.terminalTrackerState)
        assertTrue(duplicateEvents.isEmpty())
    }

    @Test
    fun formattingDoesNotChangeTrackerState() {
        val tracker = TurnHapticAlertTracker()
        val firstEvent =
            tracker
                .update(
                    sample(
                        instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                        progressMeters = 95.0,
                    ),
                ).single()

        firstEvent.telemetryMessage(vibratorAvailable = false)

        assertTrue(
            tracker
                .update(
                    sample(
                        instruction = instruction(RouteInstructionCommand.LEFT, 100.0),
                        progressMeters = 95.0,
                    ),
                ).isEmpty(),
        )
    }

    private fun sample(
        instruction: RouteInstruction,
        progressMeters: Double,
        offRoute: Boolean = false,
        turnAlertsMode: String = SettingsRepository.TURN_BY_TURN_TURN_ALERTS_ALL,
        speedMps: Float? = 1.4f,
        gpsDeliveryIntervalMs: Long = 3_000L,
        fixTimestampMs: Long? = null,
    ): TurnHapticAlertSample =
        TurnHapticAlertSample(
            routeKey = "route",
            active = true,
            mode = GuidanceMode.FOLLOW_ROUTE,
            offRoute = offRoute,
            instruction = instruction,
            distanceToInstructionMeters =
                (instruction.distanceFromStartMeters - progressMeters)
                    .coerceAtLeast(0.0),
            distanceFromStartMeters = progressMeters,
            speedMps = speedMps,
            activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
            gpsDeliveryIntervalMs = gpsDeliveryIntervalMs,
            hapticsEnabled = true,
            turnAlertsMode = turnAlertsMode,
            fixTimestampMs = fixTimestampMs,
        )

    private fun instruction(
        command: RouteInstructionCommand,
        distanceFromStartMeters: Double,
    ): RouteInstruction =
        RouteInstruction(
            command = command,
            message = command.name,
            latLong = LatLong(45.0, 6.0),
            trackPointIndex = distanceFromStartMeters.toInt(),
            distanceFromStartMeters = distanceFromStartMeters,
            turnAngleDegrees = null,
        )
}
