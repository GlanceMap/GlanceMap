package com.glancemap.glancemapcompanionapp.diagnostics

import com.glancemap.glancemapcompanionapp.weather.WeatherForecastSource
import com.glancemap.glancemapcompanionapp.weather.weatherDiagnosticReason
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot

internal data class MissionDayUpdateDiagnosticFields(
    val includesName: Boolean,
    val includesDate: Boolean,
    val includesStartTime: Boolean,
    val includesOvernight: Boolean,
    val includesNotes: Boolean,
    val includesSegment: Boolean,
)

internal data class MissionDayWeatherDiagnosticSummary(
    val unavailableSampleCount: Int,
    val sampleCount: Int,
    val includesNetwork: Boolean,
    val includesCache: Boolean,
    val includesStaleCache: Boolean,
    val hasScheduledOutlook: Boolean,
)

/**
 * Redacted, local-only diagnostics for the companion's route-planning journey.
 *
 * This writes only while [PhoneDebugCapture] is explicitly active. It never records GPX text,
 * coordinates, dates, times, route/watch identifiers, mission text, forecast values, or live
 * distance, altitude, pace, and ETA values.
 */
@Suppress("TooManyFunctions")
internal object CompanionJourneyDiagnostics {
    private const val TAG = "CompanionJourney"

    private val activeHikeLock = Any()
    private var activeHikeCaptureSessionId = Long.MIN_VALUE
    private var activeHikeSignature: String? = null

    fun routeImportStarted() {
        log("event=route_import outcome=started")
    }

    fun routeImportSucceeded(
        pointCount: Int,
        waypointCount: Int,
        elevationCount: Int,
    ) {
        val elevationCoverage =
            when {
                elevationCount <= 0 -> "none"
                elevationCount >= pointCount -> "complete"
                else -> "partial"
            }
        log(
            "event=route_import outcome=success route_points=${routePointCountBucket(pointCount)} " +
                "waypoints=${waypointCountBucket(waypointCount)} elevation=$elevationCoverage",
        )
    }

    fun routeImportFailed() {
        log("event=route_import outcome=failed")
    }

    fun routeWeatherRequested(forceRefresh: Boolean) {
        log("event=route_weather outcome=requested refresh=$forceRefresh")
    }

    fun routeWeatherUnavailable() {
        log("event=route_weather outcome=unavailable")
    }

    fun routeWeatherSucceeded(source: WeatherForecastSource) {
        log("event=route_weather outcome=success source=${source.diagnosticLabel}")
    }

    fun routeWeatherFailed(error: Throwable) {
        log("event=route_weather outcome=failed reason=${error.weatherDiagnosticReason()}")
    }

    fun missionPlanMutationStarted(operation: MissionPlanMutationOperation) {
        log("event=mission_plan operation=${operation.label} outcome=started")
    }

    fun missionPlanMutationSucceeded(
        operation: MissionPlanMutationOperation,
        dayCount: Int,
    ) {
        log(
            "event=mission_plan operation=${operation.label} outcome=success " +
                "days=${missionDayCountBucket(dayCount)}",
        )
    }

    fun missionPlanMutationFailed(operation: MissionPlanMutationOperation) {
        log("event=mission_plan operation=${operation.label} outcome=failed")
    }

    fun missionDayUpdateRequested(fields: MissionDayUpdateDiagnosticFields) {
        log(
            "event=mission_day_update outcome=requested name=${fields.includesName} " +
                "date=${fields.includesDate} start_time=${fields.includesStartTime} " +
                "overnight=${fields.includesOvernight} notes=${fields.includesNotes} " +
                "segment=${fields.includesSegment}",
        )
    }

    fun missionDayWeatherBlockedWithoutDate() {
        log("event=mission_day_weather outcome=blocked reason=missing_date")
    }

    fun missionDayWeatherRequested(
        forceRefresh: Boolean,
        hasStartTime: Boolean,
    ) {
        log(
            "event=mission_day_weather outcome=requested refresh=$forceRefresh " +
                "start_time=$hasStartTime",
        )
    }

    fun missionDayWeatherCompleted(summary: MissionDayWeatherDiagnosticSummary) {
        val outcome =
            when {
                summary.unavailableSampleCount <= 0 -> "ready"
                summary.unavailableSampleCount >= summary.sampleCount -> "unavailable"
                else -> "partial"
            }
        log(
            "event=mission_day_weather outcome=$outcome network=${summary.includesNetwork} " +
                "cache=${summary.includesCache} stale_cache=${summary.includesStaleCache} " +
                "scheduled=${summary.hasScheduledOutlook}",
        )
    }

    fun missionDayWeatherFailed() {
        log("event=mission_day_weather outcome=failed")
    }

    fun missionDayTransferRequested() {
        log("event=mission_day_transfer outcome=requested")
    }

    fun missionDayTransferPrepared(success: Boolean) {
        log("event=mission_day_transfer outcome=${if (success) "success" else "failed"}")
    }

    fun liveHikeDashboardOpened(snapshot: ActiveHikeSnapshot?) {
        val state =
            if (snapshot == null) {
                "waiting"
            } else {
                activeHikeMode(snapshot)
            }
        log("event=live_hike_dashboard outcome=opened state=$state")
    }

    fun activeHikeSnapshotRejected() {
        log("event=active_hike_snapshot outcome=rejected")
    }

    /** Logs a state/capability change at most once per manual capture session. */
    fun activeHikeSnapshotAccepted(snapshot: ActiveHikeSnapshot) {
        if (!PhoneDebugCapture.isActive()) return

        val signature = activeHikeSignature(snapshot)
        val sessionId = PhoneDebugCapture.state.value.sessionId
        synchronized(activeHikeLock) {
            if (activeHikeCaptureSessionId != sessionId) {
                activeHikeCaptureSessionId = sessionId
                activeHikeSignature = null
            }
            if (activeHikeSignature == signature) return
            activeHikeSignature = signature
        }
        PhoneDebugCapture.log(TAG, "event=active_hike_snapshot outcome=accepted $signature")
    }

    private fun activeHikeSignature(snapshot: ActiveHikeSnapshot): String {
        val hasRemainingElevation =
            snapshot.remainingAscentMeters != null || snapshot.remainingDescentMeters != null
        val hasRecordingMetrics =
            snapshot.activeDurationSeconds != null ||
                snapshot.currentSpeedMetersPerSecond != null ||
                snapshot.currentAltitudeMeters != null
        return (
            "mode=${activeHikeMode(snapshot)} phase=${snapshot.phase.name} off_route=${snapshot.offRoute} " +
                "route=${snapshot.routeId != null} progress=${snapshot.progressFraction != null} " +
                "eta=${snapshot.estimatedRemainingSeconds != null} " +
                "remaining_elevation=$hasRemainingElevation recording_metrics=$hasRecordingMetrics"
        )
    }

    private fun activeHikeMode(snapshot: ActiveHikeSnapshot): String =
        when (snapshot.phase) {
            ActiveHikePhase.RECORDING,
            ActiveHikePhase.RECORDING_PAUSED,
            -> "recording"

            else -> if (snapshot.routeId == null) "unrouted" else "routed"
        }

    private fun log(message: String) {
        PhoneDebugCapture.log(TAG, message)
    }

    private val WeatherForecastSource.diagnosticLabel: String
        get() =
            when (this) {
                WeatherForecastSource.NETWORK -> "network"
                WeatherForecastSource.MEMORY_CACHE -> "memory_cache"
                WeatherForecastSource.PERSISTED_CACHE -> "persisted_cache"
                WeatherForecastSource.STALE_CACHE -> "stale_cache"
            }

    private fun routePointCountBucket(count: Int): String =
        when {
            count <= 0 -> "0"
            count <= 50 -> "1_50"
            count <= 250 -> "51_250"
            count <= 1_000 -> "251_1000"
            else -> "1001_plus"
        }

    private fun waypointCountBucket(count: Int): String =
        when {
            count <= 0 -> "0"
            count <= 5 -> "1_5"
            count <= 25 -> "6_25"
            else -> "26_plus"
        }

    private fun missionDayCountBucket(count: Int): String =
        when {
            count <= 0 -> "0"
            count == 1 -> "1"
            count <= 3 -> "2_3"
            else -> "4_plus"
        }
}

internal enum class MissionPlanMutationOperation(
    val label: String,
) {
    ADD_DAY("add_day"),
    SELECT_DAY("select_day"),
    UPDATE_DAY("update_day"),
    UPDATE_SEGMENT("update_segment"),
    REORDER_DAY("reorder_day"),
    REMOVE_DAY("remove_day"),
}
