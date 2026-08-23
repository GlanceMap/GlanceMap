package com.glancemap.glancemapwearos.presentation.features.download

import java.util.Locale

/** Runtime state is deliberately sampled only by debug extraction telemetry. */
internal data class OamExtractionRuntimeSnapshot(
    val interactive: Boolean,
    val charging: Boolean,
    val plugged: String,
    val batteryPercent: String,
    val thermalStatus: String,
    val wakeLockHeld: Boolean,
    val wifiLockHeld: Boolean,
    val wakeLockType: String = "unknown",
    val wakeLockAcquireAgeMs: Long? = null,
    val wakeLockTimeoutMs: Long? = null,
    val wakeLockGeneration: Int = 0,
) {
    val screenState: String
        get() = if (interactive) "ON" else "OFF"
}

data class OamDownloadKeepAliveState(
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    val wakeLockType: String = "none",
    val wakeLockAcquireAgeMs: Long? = null,
    val wakeLockTimeoutMs: Long? = null,
    val wakeLockGeneration: Int = 0,
)

/**
 * Emits a small, bounded set of diagnostics while a ZIP entry is extracted.
 *
 * It is kept independent of Android APIs so the throttling and accounting can be tested directly.
 */
internal class OamExtractionTelemetryReporter(
    private val label: String,
    private val entryFileName: String,
    private val totalBytes: Long?,
    private val wallNowMs: () -> Long,
    private val uptimeNowMs: () -> Long,
    private val processCpuMs: () -> Long,
    private val runtimeSnapshot: () -> OamExtractionRuntimeSnapshot,
    private val emit: (String) -> Unit,
    private val progressIntervalMs: Long = PROGRESS_INTERVAL_MS,
    private val heartbeatIntervalMs: Long = STALL_HEARTBEAT_INTERVAL_MS,
) {
    private val lock = Any()
    private val startedAtWallMs = wallNowMs()
    private val startedAtUptimeMs = uptimeNowMs()
    private val startCpuMs = processCpuMs()
    private var lastObservedAtWallMs = startedAtWallMs
    private var lastSnapshot = runtimeSnapshot()
    private var latestBytes = 0L
    private var lastProgressBytes = 0L
    private var lastProgressAtWallMs = startedAtWallMs
    private var lastProgressAtUptimeMs = startedAtUptimeMs
    private var lastProgressLogAtWallMs = startedAtWallMs
    private var lastProgressLogAtUptimeMs = startedAtUptimeMs
    private var lastHeartbeatAtWallMs = startedAtWallMs
    private var lastHeartbeatCpuMs = startCpuMs
    private var screenOnDurationMs = 0L
    private var screenOffDurationMs = 0L
    private var chargingDurationMs = 0L
    private var batteryDurationMs = 0L
    private var maxWallNoProgressMs = 0L

    fun onBytesWritten(bytesWritten: Long) =
        synchronized(lock) {
            val wallNow = wallNowMs()
            val uptimeNow = uptimeNowMs()
            val snapshot = observe(wallNow)
            val safeBytes = bytesWritten.coerceAtLeast(0L)
            if (safeBytes > latestBytes) {
                maxWallNoProgressMs =
                    maxOf(maxWallNoProgressMs, elapsed(wallNow, lastProgressAtWallMs))
                latestBytes = safeBytes
                lastProgressAtWallMs = wallNow
                lastProgressAtUptimeMs = uptimeNow
            }
            if (elapsed(wallNow, lastProgressLogAtWallMs) >= progressIntervalMs) {
                emitProgress(wallNow, uptimeNow, snapshot)
            }
        }

    fun emitStallHeartbeatIfNeeded() {
        synchronized(lock) {
            val wallNow = wallNowMs()
            val uptimeNow = uptimeNowMs()
            val snapshot = observe(wallNow)
            val wallNoProgressMs = elapsed(wallNow, lastProgressAtWallMs)
            val uptimeNoProgressMs = elapsed(uptimeNow, lastProgressAtUptimeMs)
            maxWallNoProgressMs = maxOf(maxWallNoProgressMs, wallNoProgressMs)
            if (
                wallNoProgressMs < heartbeatIntervalMs ||
                elapsed(wallNow, lastHeartbeatAtWallMs) < heartbeatIntervalMs
            ) {
                return@synchronized
            }
            val currentCpuMs = processCpuMs()
            emit(
                "event=extract_stall_heartbeat label=$label entry=$entryFileName " +
                    "wallNoProgressMs=$wallNoProgressMs uptimeNoProgressMs=$uptimeNoProgressMs " +
                    "suspendOrSleepDeltaMs=${(wallNoProgressMs - uptimeNoProgressMs).coerceAtLeast(0L)} " +
                    "screenState=${snapshot.screenState} charging=${snapshot.charging} " +
                    "${keepAliveFields(snapshot)} " +
                    "processCpuDeltaMs=${(currentCpuMs - lastHeartbeatCpuMs).coerceAtLeast(0L)}",
            )
            lastHeartbeatAtWallMs = wallNow
            lastHeartbeatCpuMs = currentCpuMs
        }
    }

    fun complete(finalBytes: Long) =
        synchronized(lock) {
            val wallNow = wallNowMs()
            val uptimeNow = uptimeNowMs()
            observe(wallNow)
            if (finalBytes > latestBytes) {
                maxWallNoProgressMs =
                    maxOf(maxWallNoProgressMs, elapsed(wallNow, lastProgressAtWallMs))
                latestBytes = finalBytes
                lastProgressAtWallMs = wallNow
                lastProgressAtUptimeMs = uptimeNow
            }
            val wallDurationMs = elapsed(wallNow, startedAtWallMs)
            val activeCopyDurationMs = elapsed(uptimeNow, startedAtUptimeMs)
            emit(
                "event=extract_summary label=$label entry=$entryFileName " +
                    "wallDurationMs=$wallDurationMs activeCopyDurationMs=$activeCopyDurationMs " +
                    "suspendOrSleepDurationMs=${(wallDurationMs - activeCopyDurationMs).coerceAtLeast(0L)} " +
                    "wallAverageMBps=${mbPerSecond(latestBytes, wallDurationMs)} " +
                    "activeCopyAverageMBps=${mbPerSecond(latestBytes, activeCopyDurationMs)} " +
                    "screenOnDurationMs=$screenOnDurationMs screenOffDurationMs=$screenOffDurationMs " +
                    "chargingDurationMs=$chargingDurationMs batteryDurationMs=$batteryDurationMs " +
                    "maxWallNoProgressMs=$maxWallNoProgressMs " +
                    "cpuTimeDeltaMs=${(processCpuMs() - startCpuMs).coerceAtLeast(0L)}",
            )
        }

    private fun emitProgress(
        wallNow: Long,
        uptimeNow: Long,
        snapshot: OamExtractionRuntimeSnapshot,
    ) {
        val wallSinceLastProgressMs = elapsed(wallNow, lastProgressLogAtWallMs)
        val uptimeSinceLastProgressMs = elapsed(uptimeNow, lastProgressLogAtUptimeMs)
        val intervalBytes = (latestBytes - lastProgressBytes).coerceAtLeast(0L)
        val wallElapsedMs = elapsed(wallNow, startedAtWallMs)
        val uptimeElapsedMs = elapsed(uptimeNow, startedAtUptimeMs)
        emit(
            "event=extract_progress label=$label entry=$entryFileName " +
                "bytesWritten=$latestBytes totalBytes=${totalBytes ?: "unknown"} " +
                "wallElapsedMs=$wallElapsedMs wallSinceLastProgressMs=$wallSinceLastProgressMs " +
                "uptimeElapsedMs=$uptimeElapsedMs uptimeSinceLastProgressMs=$uptimeSinceLastProgressMs " +
                "suspendOrSleepDeltaMs=${(wallSinceLastProgressMs - uptimeSinceLastProgressMs).coerceAtLeast(0L)} " +
                "intervalBytes=$intervalBytes " +
                "wallCurrentMBps=${mbPerSecond(intervalBytes, wallSinceLastProgressMs)} " +
                "activeCopyCurrentMBps=${mbPerSecond(intervalBytes, uptimeSinceLastProgressMs)} " +
                "wallAverageMBps=${mbPerSecond(latestBytes, wallElapsedMs)} " +
                "activeCopyAverageMBps=${mbPerSecond(latestBytes, uptimeElapsedMs)} " +
                "screenState=${snapshot.screenState} interactive=${snapshot.interactive} " +
                "charging=${snapshot.charging} plugged=${snapshot.plugged} " +
                "batteryPercent=${snapshot.batteryPercent} thermalStatus=${snapshot.thermalStatus} " +
                "${keepAliveFields(snapshot)} " +
                "threadName=${Thread.currentThread().name} processCpuMs=${processCpuMs()}",
        )
        lastProgressLogAtWallMs = wallNow
        lastProgressLogAtUptimeMs = uptimeNow
        lastProgressBytes = latestBytes
    }

    private fun observe(wallNow: Long): OamExtractionRuntimeSnapshot {
        val durationMs = elapsed(wallNow, lastObservedAtWallMs)
        if (lastSnapshot.interactive) screenOnDurationMs += durationMs else screenOffDurationMs += durationMs
        if (lastSnapshot.charging) chargingDurationMs += durationMs else batteryDurationMs += durationMs
        lastObservedAtWallMs = wallNow
        return runtimeSnapshot().also { lastSnapshot = it }
    }

    private fun keepAliveFields(snapshot: OamExtractionRuntimeSnapshot): String =
        "wakeLockType=${snapshot.wakeLockType} wakeLockIsHeld=${snapshot.wakeLockHeld} " +
            "wakeLockAcquireAgeMs=${snapshot.wakeLockAcquireAgeMs ?: "na"} " +
            "wakeLockTimeoutMs=${snapshot.wakeLockTimeoutMs ?: "na"} " +
            "wakeLockGeneration=${snapshot.wakeLockGeneration} " +
            "wifiLockHeld=${snapshot.wifiLockHeld}"

    private fun elapsed(
        laterMs: Long,
        earlierMs: Long,
    ): Long = (laterMs - earlierMs).coerceAtLeast(0L)

    private fun mbPerSecond(
        bytes: Long,
        elapsedMs: Long,
    ): String =
        if (bytes <= 0L || elapsedMs <= 0L) {
            "0.00"
        } else {
            String.format(Locale.US, "%.2f", (bytes / 1_048_576.0) / (elapsedMs / 1_000.0))
        }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 5_000L
        const val STALL_HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
