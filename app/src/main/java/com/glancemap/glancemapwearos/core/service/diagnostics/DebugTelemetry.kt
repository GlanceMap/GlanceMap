package com.glancemap.glancemapwearos.core.service.diagnostics

import android.util.Log
import com.glancemap.glancemapwearos.domain.sensors.COMPASS_TELEMETRY_TAG
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("TooManyFunctions")
internal object DebugTelemetry {
    internal data class CaptureSessionSnapshot(
        val sessionId: Long,
        val startedAtMs: Long?,
        val endedAtMs: Long?,
        val active: Boolean,
        val droppedLines: Int,
        val bufferedLines: Int,
        val totalLoggedLines: Long,
        val firstBufferedAtMs: Long?,
        val lastBufferedAtMs: Long?,
    )

    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private var exportFreezeActive = false

    @Volatile private var transitionMarkersEnabled = true
    private val lines = ArrayDeque<String>()
    private val lineTimesMs = ArrayDeque<Long>()
    private const val MAX_LINES = 30_000
    private var droppedLines: Int = 0
    private var totalLoggedLines: Long = 0L
    private var sessionId: Long = 0L
    private var sessionStartedAtMs: Long? = null
    private var sessionEndedAtMs: Long? = null
    private val tsFormatter: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    fun setEnabledFromLocationService(value: Boolean) {
        val marker =
            synchronized(lock) {
                if (value && exportFreezeActive) return
                if (!value) exportFreezeActive = false
                setEnabledLocked(value)
            }
        recordTransitionMarker(marker)
    }

    /**
     * Stops capture without allowing an in-flight settings emission to reopen a short-lived
     * session while the diagnostic export is taking its snapshot.
     */
    fun freezeForExport() {
        val marker =
            synchronized(lock) {
                exportFreezeActive = true
                setEnabledLocked(false)
            }
        recordTransitionMarker(marker)
    }

    private fun setEnabledLocked(value: Boolean): CaptureTransition? {
        val previous = enabled.getAndSet(value)
        if (previous == value) return null

        return if (value) {
            sessionId += 1L
            sessionStartedAtMs = System.currentTimeMillis()
            sessionEndedAtMs = null
            CaptureTransition(type = "diagnostics_capture_start", note = "s$sessionId")
        } else if (sessionStartedAtMs != null) {
            sessionEndedAtMs = System.currentTimeMillis()
            CaptureTransition(type = "diagnostics_capture_stop", note = "s$sessionId")
        } else {
            null
        }
    }

    private fun recordTransitionMarker(transition: CaptureTransition?) {
        transition ?: return
        if (!transitionMarkersEnabled) return
        FieldMarkerDiagnostics.recordMarker(type = transition.type, note = transition.note)
    }

    private data class CaptureTransition(
        val type: String,
        val note: String,
    )

    internal fun setTransitionMarkersEnabledForTests(enabled: Boolean) {
        transitionMarkersEnabled = enabled
    }

    fun isEnabled(): Boolean = enabled.get()

    fun log(
        tag: String,
        message: String,
    ) {
        val nowMs = System.currentTimeMillis()
        val line = "${tsFormatter.format(Instant.ofEpochMilli(nowMs))} [$tag] $message"
        if (tag == COMPASS_TELEMETRY_TAG) {
            CompassDeepTraceDiagnostics.recordTelemetryLine(line)
        }
        if (!enabled.get()) return
        synchronized(lock) {
            lines.addLast(line)
            lineTimesMs.addLast(nowMs)
            totalLoggedLines += 1L
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                lineTimesMs.removeFirst()
                droppedLines += 1
            }
        }
        Log.d(tag, message)
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        var markerNote = "na"
        synchronized(lock) {
            lines.clear()
            lineTimesMs.clear()
            droppedLines = 0
            totalLoggedLines = 0L
            if (enabled.get()) {
                sessionId = 1L
                sessionStartedAtMs = System.currentTimeMillis()
                sessionEndedAtMs = null
                markerNote = "s$sessionId"
            } else {
                sessionId = 0L
                sessionStartedAtMs = null
                sessionEndedAtMs = null
                markerNote = "disabled"
            }
        }
        FieldMarkerDiagnostics.recordMarker(type = "diagnostics_capture_clear", note = markerNote)
    }

    fun size(): Int = synchronized(lock) { lines.size }

    fun maxBufferedLines(): Int = MAX_LINES

    fun captureSessionSnapshot(): CaptureSessionSnapshot =
        synchronized(lock) {
            CaptureSessionSnapshot(
                sessionId = sessionId,
                startedAtMs = sessionStartedAtMs,
                endedAtMs = sessionEndedAtMs,
                active = enabled.get(),
                droppedLines = droppedLines,
                bufferedLines = lines.size,
                totalLoggedLines = totalLoggedLines,
                firstBufferedAtMs = lineTimesMs.firstOrNull(),
                lastBufferedAtMs = lineTimesMs.lastOrNull(),
            )
        }
}
