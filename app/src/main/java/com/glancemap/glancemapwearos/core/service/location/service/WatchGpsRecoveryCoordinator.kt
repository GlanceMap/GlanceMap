package com.glancemap.glancemapwearos.core.service.location.service

import android.location.Location
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.location.adapters.CurrentLocationRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationGateway
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A deliberately rare escape hatch for a stalled Watch-GPS callback stream. This does not
 * change the recurring location request: it asks the platform once for its current GPS fix,
 * then returns to the normal stream. The returned point is processed through the ordinary
 * callback path, including source validation and jump filtering.
 */
internal class WatchGpsRecoveryCoordinator(
    private val serviceScope: CoroutineScope,
    private val telemetry: LocationServiceTelemetry,
    private val locationGateway: LocationGateway,
    private val strictFreshMaxAgeMs: () -> Long,
    private val processRecoveredLocation: (Location) -> Boolean,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val requestLock = Any()
    private var activeJob: Job? = null
    private var lastRequestedAtElapsedMs: Long = 0L

    /**
     * Returns true when Watch-GPS recovery owns this stale period, including its cooldown. That
     * prevents the regular self-heal path from immediately stacking a second GPS request on top.
     */
    fun maybeRequest(
        nowElapsedMs: Long,
        fixGapMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
    ): Boolean {
        synchronized(requestLock) {
            if (activeJob?.isActive == true) return true
            if (isWatchGpsRecoveryCooldownActive(nowElapsedMs, lastRequestedAtElapsedMs)) return true
            lastRequestedAtElapsedMs = nowElapsedMs
        }

        telemetry.logWatchGpsRecoveryRequested(
            fixGapMs = fixGapMs,
            staleThresholdMs = staleThresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            timeoutMs = WATCH_GPS_RECOVERY_TIMEOUT_MS,
        )
        val job = serviceScope.launch { recover() }
        synchronized(requestLock) {
            activeJob = job
        }
        job.invokeOnCompletion {
            synchronized(requestLock) {
                if (activeJob === job) activeJob = null
            }
        }
        return true
    }

    fun cancel(reason: String) {
        val job =
            synchronized(requestLock) {
                activeJob?.also { activeJob = null }
            } ?: return
        job.cancel()
        telemetry.logWatchGpsRecoveryOutcome(
            outcome = "cancelled",
            reason = reason,
            durationMs = 0L,
            fixAgeMs = null,
            accuracyM = null,
        )
    }

    @Suppress("LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun recover() {
        val startedAtElapsedMs = elapsedRealtimeMs()
        try {
            val location =
                locationGateway.getCurrentLocation(
                    CurrentLocationRequestParams(
                        priority = Priority.PRIORITY_HIGH_ACCURACY,
                        maxUpdateAgeMs = strictFreshMaxAgeMs(),
                        durationMs = WATCH_GPS_RECOVERY_TIMEOUT_MS,
                    ),
                )
            if (location == null) {
                finish(
                    outcome = "timed_out",
                    reason = "null_result",
                    startedAtElapsedMs = startedAtElapsedMs,
                )
                return
            }

            val evaluatedAtElapsedMs = elapsedRealtimeMs()
            val accepted = processRecoveredLocation(location)
            finish(
                outcome = if (accepted) "succeeded" else "rejected",
                reason = if (accepted) "accepted" else "fix_policy",
                startedAtElapsedMs = startedAtElapsedMs,
                fixAgeMs = LocationFixPolicy.locationAgeMs(location, evaluatedAtElapsedMs),
                accuracyM = location.accuracy,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finish(
                outcome = "failed",
                reason = error.javaClass.simpleName,
                startedAtElapsedMs = startedAtElapsedMs,
            )
        }
    }

    private fun finish(
        outcome: String,
        reason: String,
        startedAtElapsedMs: Long,
        fixAgeMs: Long? = null,
        accuracyM: Float? = null,
    ) {
        telemetry.logWatchGpsRecoveryOutcome(
            outcome = outcome,
            reason = reason,
            durationMs = (elapsedRealtimeMs() - startedAtElapsedMs).coerceAtLeast(0L),
            fixAgeMs = fixAgeMs,
            accuracyM = accuracyM,
        )
    }
}

internal fun resolveWatchGpsRecoveryStaleThresholdMs(
    expectedIntervalMs: Long,
): Long =
    maxOf(
        WATCH_GPS_RECOVERY_MIN_STALE_GAP_MS,
        expectedIntervalMs * WATCH_GPS_RECOVERY_INTERVAL_MULTIPLIER,
    )

internal fun isWatchGpsRecoveryCooldownActive(
    nowElapsedMs: Long,
    lastRequestedAtElapsedMs: Long,
): Boolean =
    lastRequestedAtElapsedMs > 0L &&
        nowElapsedMs - lastRequestedAtElapsedMs < WATCH_GPS_RECOVERY_COOLDOWN_MS

private const val WATCH_GPS_RECOVERY_TIMEOUT_MS = 6_000L
private const val WATCH_GPS_RECOVERY_COOLDOWN_MS = 60_000L
private const val WATCH_GPS_RECOVERY_MIN_STALE_GAP_MS = 15_000L
private const val WATCH_GPS_RECOVERY_INTERVAL_MULTIPLIER = 3L
