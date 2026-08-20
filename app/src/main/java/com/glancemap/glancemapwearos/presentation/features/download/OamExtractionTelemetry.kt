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
) {
    val screenState: String
        get() = if (interactive) "ON" else "OFF"
}

data class OamDownloadKeepAliveState(
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
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
    private val nowMs: () -> Long,
    private val processCpuMs: () -> Long,
    private val runtimeSnapshot: () -> OamExtractionRuntimeSnapshot,
    private val emit: (String) -> Unit,
    private val progressIntervalMs: Long = PROGRESS_INTERVAL_MS,
    private val heartbeatIntervalMs: Long = STALL_HEARTBEAT_INTERVAL_MS,
) {
    private val lock = Any()
    private val startedAtMs = nowMs()
    private val startCpuMs = processCpuMs()
    private var lastObservedAtMs = startedAtMs
    private var lastSnapshot = runtimeSnapshot()
    private var latestBytes = 0L
    private var lastProgressBytes = 0L
    private var lastProgressAtMs = startedAtMs
    private var lastProgressLogAtMs = startedAtMs
    private var lastHeartbeatAtMs = startedAtMs
    private var lastHeartbeatCpuMs = startCpuMs
    private var screenOnDurationMs = 0L
    private var screenOffDurationMs = 0L
    private var chargingDurationMs = 0L
    private var batteryDurationMs = 0L
    private var maxNoProgressMs = 0L

    fun onBytesWritten(bytesWritten: Long) = synchronized(lock) {
        val now = nowMs()
        val snapshot = observe(now)
        val safeBytes = bytesWritten.coerceAtLeast(0L)
        if (safeBytes > latestBytes) {
            latestBytes = safeBytes
            lastProgressAtMs = now
        }
        if (now - lastProgressLogAtMs >= progressIntervalMs) {
            emitProgress(now, snapshot)
        }
    }

    fun emitStallHeartbeatIfNeeded() {
        synchronized(lock) {
            val now = nowMs()
            val snapshot = observe(now)
            val noProgressMs = (now - lastProgressAtMs).coerceAtLeast(0L)
            maxNoProgressMs = maxOf(maxNoProgressMs, noProgressMs)
            if (
                noProgressMs < heartbeatIntervalMs ||
                now - lastHeartbeatAtMs < heartbeatIntervalMs
            ) {
                return@synchronized
            }
            val currentCpuMs = processCpuMs()
            emit(
                "event=extract_stall_heartbeat label=$label entry=$entryFileName " +
                    "noProgressMs=$noProgressMs screenState=${snapshot.screenState} " +
                    "charging=${snapshot.charging} wakeLockHeld=${snapshot.wakeLockHeld} " +
                    "processCpuDeltaMs=${(currentCpuMs - lastHeartbeatCpuMs).coerceAtLeast(0L)}",
            )
            lastHeartbeatAtMs = now
            lastHeartbeatCpuMs = currentCpuMs
        }
    }

    fun complete(finalBytes: Long) = synchronized(lock) {
        val now = nowMs()
        observe(now)
        latestBytes = maxOf(latestBytes, finalBytes.coerceAtLeast(0L))
        val durationMs = (now - startedAtMs).coerceAtLeast(0L)
        val noProgressMs = (now - lastProgressAtMs).coerceAtLeast(0L)
        maxNoProgressMs = maxOf(maxNoProgressMs, noProgressMs)
        emit(
            "event=extract_summary label=$label entry=$entryFileName " +
                "extractDurationMs=$durationMs " +
                "extractAverageMBps=${mbPerSecond(latestBytes, durationMs)} " +
                "screenOnDurationMs=$screenOnDurationMs screenOffDurationMs=$screenOffDurationMs " +
                "chargingDurationMs=$chargingDurationMs batteryDurationMs=$batteryDurationMs " +
                "maxNoProgressMs=$maxNoProgressMs " +
                "cpuTimeDeltaMs=${(processCpuMs() - startCpuMs).coerceAtLeast(0L)}",
        )
    }

    private fun emitProgress(
        now: Long,
        snapshot: OamExtractionRuntimeSnapshot,
    ) {
        val intervalMs = (now - lastProgressLogAtMs).coerceAtLeast(0L)
        val intervalBytes = (latestBytes - lastProgressBytes).coerceAtLeast(0L)
        val elapsedMs = (now - startedAtMs).coerceAtLeast(0L)
        emit(
            "event=extract_progress label=$label entry=$entryFileName " +
                "bytesWritten=$latestBytes totalBytes=${totalBytes ?: "unknown"} " +
                "elapsedMs=$elapsedMs intervalBytes=$intervalBytes intervalMs=$intervalMs " +
                "currentMBps=${mbPerSecond(intervalBytes, intervalMs)} " +
                "averageMBps=${mbPerSecond(latestBytes, elapsedMs)} " +
                "screenState=${snapshot.screenState} interactive=${snapshot.interactive} " +
                "charging=${snapshot.charging} plugged=${snapshot.plugged} " +
                "batteryPercent=${snapshot.batteryPercent} thermalStatus=${snapshot.thermalStatus} " +
                "wakeLockHeld=${snapshot.wakeLockHeld} wifiLockHeld=${snapshot.wifiLockHeld} " +
                "threadName=${Thread.currentThread().name} processCpuMs=${processCpuMs()}",
        )
        lastProgressLogAtMs = now
        lastProgressBytes = latestBytes
    }

    private fun observe(now: Long): OamExtractionRuntimeSnapshot {
        val durationMs = (now - lastObservedAtMs).coerceAtLeast(0L)
        if (lastSnapshot.interactive) screenOnDurationMs += durationMs else screenOffDurationMs += durationMs
        if (lastSnapshot.charging) chargingDurationMs += durationMs else batteryDurationMs += durationMs
        lastObservedAtMs = now
        return runtimeSnapshot().also { lastSnapshot = it }
    }

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
