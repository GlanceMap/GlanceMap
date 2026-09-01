package com.glancemap.glancemapwearos.core.service.diagnostics

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class RecordingScreenOffActivity {
    LOCATION_CALLBACK,
    RECORDING_POINT,
    SMART_TRACK,
    DEM_LOOKUP,
    HYBRID_ELEVATION,
    DRAFT_PERSIST,
    GPX_PERSIST,
    HEART_RATE_CALLBACK,
    PRESSURE_CALLBACK,
    MARKER_MOTION,
    COMPASS_CONE,
}

internal data class RecordingScreenOffActivityCounter(
    val count: Long,
    val elapsedMs: Long,
)

internal data class RecordingScreenOffActivityCounters(
    val locationCallback: RecordingScreenOffActivityCounter,
    val recordingPoint: RecordingScreenOffActivityCounter,
    val smartTrack: RecordingScreenOffActivityCounter,
    val demLookup: RecordingScreenOffActivityCounter,
    val hybridElevation: RecordingScreenOffActivityCounter,
    val draftPersist: RecordingScreenOffActivityCounter,
    val gpxPersist: RecordingScreenOffActivityCounter,
    val heartRateCallback: RecordingScreenOffActivityCounter,
    val pressureCallback: RecordingScreenOffActivityCounter,
    val markerMotion: RecordingScreenOffActivityCounter,
    val compassCone: RecordingScreenOffActivityCounter,
)

/** Full-diagnostics-only elapsed-time attribution for recording work while the display is off. */
internal object RecordingScreenOffDiagnostics {
    private const val NO_TIMER = Long.MIN_VALUE

    private val fullDiagnosticsEnabled = AtomicBoolean(false)
    private val nonInteractive = AtomicBoolean(false)
    private val recordingActive = AtomicBoolean(false)
    private val countByActivity = Array(RecordingScreenOffActivity.entries.size) { AtomicLong() }
    private val elapsedMsByActivity = Array(RecordingScreenOffActivity.entries.size) { AtomicLong() }

    @Volatile
    private var elapsedTimeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    fun configure(fullDiagnostics: Boolean) {
        if (fullDiagnosticsEnabled.getAndSet(fullDiagnostics) != fullDiagnostics) {
            snapshotAndReset()
        }
    }

    fun updateRuntimeState(
        isInteractive: Boolean,
        isRecordingActive: Boolean,
    ) {
        val nextNonInteractive = !isInteractive
        val screenStateChanged = nonInteractive.getAndSet(nextNonInteractive) != nextNonInteractive
        recordingActive.set(isRecordingActive)
        if (screenStateChanged) snapshotAndReset()
    }

    fun start(): Long = if (isCollecting()) elapsedTimeProvider() else NO_TIMER

    fun stop(
        activity: RecordingScreenOffActivity,
        startedAtElapsedMs: Long,
    ) {
        if (startedAtElapsedMs == NO_TIMER || !isCollecting()) return
        countByActivity[activity.ordinal].incrementAndGet()
        elapsedMsByActivity[activity.ordinal].addAndGet(
            (elapsedTimeProvider() - startedAtElapsedMs).coerceAtLeast(0L),
        )
    }

    internal fun setElapsedTimeProviderForTests(provider: (() -> Long)?) {
        elapsedTimeProvider = provider ?: { SystemClock.elapsedRealtime() }
    }

    fun snapshotAndReset(): RecordingScreenOffActivityCounters =
        RecordingScreenOffActivityCounters(
            locationCallback = snapshot(RecordingScreenOffActivity.LOCATION_CALLBACK),
            recordingPoint = snapshot(RecordingScreenOffActivity.RECORDING_POINT),
            smartTrack = snapshot(RecordingScreenOffActivity.SMART_TRACK),
            demLookup = snapshot(RecordingScreenOffActivity.DEM_LOOKUP),
            hybridElevation = snapshot(RecordingScreenOffActivity.HYBRID_ELEVATION),
            draftPersist = snapshot(RecordingScreenOffActivity.DRAFT_PERSIST),
            gpxPersist = snapshot(RecordingScreenOffActivity.GPX_PERSIST),
            heartRateCallback = snapshot(RecordingScreenOffActivity.HEART_RATE_CALLBACK),
            pressureCallback = snapshot(RecordingScreenOffActivity.PRESSURE_CALLBACK),
            markerMotion = snapshot(RecordingScreenOffActivity.MARKER_MOTION),
            compassCone = snapshot(RecordingScreenOffActivity.COMPASS_CONE),
        )

    private fun isCollecting(): Boolean = fullDiagnosticsEnabled.get() && nonInteractive.get() && recordingActive.get()

    private fun snapshot(activity: RecordingScreenOffActivity): RecordingScreenOffActivityCounter =
        RecordingScreenOffActivityCounter(
            count = countByActivity[activity.ordinal].getAndSet(0L),
            elapsedMs = elapsedMsByActivity[activity.ordinal].getAndSet(0L),
        )
}
