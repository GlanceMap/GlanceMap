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

internal enum class RecordingSensorDiagnosticKind(
    val token: String,
) {
    HEART_RATE("HeartRate"),
    PRESSURE("Pressure"),
    STEP("Step"),
    CADENCE("Cadence"),
}

internal data class RecordingDiagnosticCounter(
    val count: Long,
    val screenOffCount: Long,
)

internal data class RecordingInstrumentationCounters(
    val recordingDashboardTickCount: Long,
    val recordingDashboardScreenOffTickCount: Long,
    val recordingDashboardSnapshotBuildCount: Long,
    val recordingDashboardScreenOffSnapshotBuildCount: Long,
    val recordingDashboardPointsScanned: Long,
    val recordingDashboardScreenOffPointsScanned: Long,
    val tbtProjectionRunCount: Long,
    val tbtScreenOffProjectionRunCount: Long,
    val tbtProjectionSegmentsScanned: Long,
    val tbtScreenOffProjectionSegmentsScanned: Long,
    val tbtProjectionMaxSegments: Long,
    val tbtScreenOffProjectionMaxSegments: Long,
    val recordingDraftPersistCount: Long,
    val recordingDraftScreenOffPersistCount: Long,
    val recordingDraftJsonBytesWritten: Long,
    val recordingDraftScreenOffJsonBytesWritten: Long,
    val recordingDraftGpxBytesWritten: Long,
    val recordingDraftScreenOffGpxBytesWritten: Long,
    val recordingDraftTotalBytesWritten: Long,
    val recordingDraftScreenOffTotalBytesWritten: Long,
    val recordingDraftMaxPointCount: Long,
    val recordingDraftScreenOffMaxPointCount: Long,
    val recordingDraftPointsSerialized: Long,
    val recordingDraftScreenOffPointsSerialized: Long,
    val recordingSensorCallbackCount: Long,
    val recordingSensorScreenOffCallbackCount: Long,
    val recordingSensorUiPublishCount: Long,
    val recordingSensorScreenOffUiPublishCount: Long,
    val sensorCallbackCounts: Map<String, RecordingDiagnosticCounter>,
    val sensorUiPublishCounts: Map<String, RecordingDiagnosticCounter>,
)

private class ClassifiedCounter {
    val count = AtomicLong()
    val screenOffCount = AtomicLong()
}

/** Full-diagnostics-only elapsed-time attribution for recording work while the display is off. */
@Suppress("TooManyFunctions")
internal object RecordingScreenOffDiagnostics {
    private const val NO_TIMER = Long.MIN_VALUE

    private val fullDiagnosticsEnabled = AtomicBoolean(false)
    private val nonInteractive = AtomicBoolean(false)
    private val recordingActive = AtomicBoolean(false)
    private val countByActivity = Array(RecordingScreenOffActivity.entries.size) { AtomicLong() }
    private val elapsedMsByActivity = Array(RecordingScreenOffActivity.entries.size) { AtomicLong() }
    private val dashboardTickCount = ClassifiedCounter()
    private val dashboardSnapshotBuildCount = ClassifiedCounter()
    private val dashboardPointsScanned = ClassifiedCounter()
    private val tbtProjectionRunCount = ClassifiedCounter()
    private val tbtProjectionSegmentsScanned = ClassifiedCounter()
    private val tbtProjectionMaxSegments = ClassifiedCounter()
    private val recordingDraftPersistCount = ClassifiedCounter()
    private val recordingDraftJsonBytesWritten = ClassifiedCounter()
    private val recordingDraftGpxBytesWritten = ClassifiedCounter()
    private val recordingDraftTotalBytesWritten = ClassifiedCounter()
    private val recordingDraftMaxPointCount = ClassifiedCounter()
    private val recordingDraftPointsSerialized = ClassifiedCounter()
    private val recordingSensorCallbackCount = ClassifiedCounter()
    private val recordingSensorUiPublishCount = ClassifiedCounter()
    private val sensorCallbackCounts = Array(RecordingSensorDiagnosticKind.entries.size) { ClassifiedCounter() }
    private val sensorUiPublishCounts = Array(RecordingSensorDiagnosticKind.entries.size) { ClassifiedCounter() }

    @Volatile
    private var elapsedTimeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    fun configure(fullDiagnostics: Boolean) {
        if (fullDiagnosticsEnabled.getAndSet(fullDiagnostics) != fullDiagnostics) {
            snapshotAndReset()
            resetInstrumentation()
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

    fun recordDashboardTick() {
        if (!fullDiagnosticsEnabled.get()) return
        recordEnabled(dashboardTickCount)
    }

    fun recordDashboardSnapshotBuild(pointsScanned: Int) {
        if (!fullDiagnosticsEnabled.get()) return
        recordEnabled(dashboardSnapshotBuildCount)
        recordEnabled(dashboardPointsScanned, pointsScanned.toLong())
    }

    fun recordTbtProjection(segmentsScanned: Int) {
        if (!fullDiagnosticsEnabled.get()) return
        val safeSegmentsScanned = segmentsScanned.coerceAtLeast(0).toLong()
        recordEnabled(tbtProjectionRunCount)
        recordEnabled(tbtProjectionSegmentsScanned, safeSegmentsScanned)
        updateMaxEnabled(tbtProjectionMaxSegments, safeSegmentsScanned)
    }

    fun recordDraftPersist(
        jsonBytesWritten: Long,
        gpxBytesWritten: Long,
        pointCount: Int,
    ) {
        if (!fullDiagnosticsEnabled.get()) return
        recordEnabled(recordingDraftPersistCount)
        recordEnabled(recordingDraftJsonBytesWritten, jsonBytesWritten)
        recordEnabled(recordingDraftGpxBytesWritten, gpxBytesWritten)
        recordEnabled(recordingDraftTotalBytesWritten, jsonBytesWritten + gpxBytesWritten)
        recordEnabled(recordingDraftPointsSerialized, pointCount.toLong())
        updateMaxEnabled(recordingDraftMaxPointCount, pointCount.toLong().coerceAtLeast(0L))
    }

    fun recordSensorCallback(kind: RecordingSensorDiagnosticKind) {
        if (!fullDiagnosticsEnabled.get()) return
        recordEnabled(recordingSensorCallbackCount)
        recordEnabled(sensorCallbackCounts[kind.ordinal])
    }

    fun recordSensorUiPublish(kind: RecordingSensorDiagnosticKind) {
        if (!fullDiagnosticsEnabled.get()) return
        recordEnabled(recordingSensorUiPublishCount)
        recordEnabled(sensorUiPublishCounts[kind.ordinal])
    }

    fun snapshotInstrumentation(): RecordingInstrumentationCounters =
        RecordingInstrumentationCounters(
            recordingDashboardTickCount = dashboardTickCount.count.get(),
            recordingDashboardScreenOffTickCount = dashboardTickCount.screenOffCount.get(),
            recordingDashboardSnapshotBuildCount = dashboardSnapshotBuildCount.count.get(),
            recordingDashboardScreenOffSnapshotBuildCount = dashboardSnapshotBuildCount.screenOffCount.get(),
            recordingDashboardPointsScanned = dashboardPointsScanned.count.get(),
            recordingDashboardScreenOffPointsScanned = dashboardPointsScanned.screenOffCount.get(),
            tbtProjectionRunCount = tbtProjectionRunCount.count.get(),
            tbtScreenOffProjectionRunCount = tbtProjectionRunCount.screenOffCount.get(),
            tbtProjectionSegmentsScanned = tbtProjectionSegmentsScanned.count.get(),
            tbtScreenOffProjectionSegmentsScanned = tbtProjectionSegmentsScanned.screenOffCount.get(),
            tbtProjectionMaxSegments = tbtProjectionMaxSegments.count.get(),
            tbtScreenOffProjectionMaxSegments = tbtProjectionMaxSegments.screenOffCount.get(),
            recordingDraftPersistCount = recordingDraftPersistCount.count.get(),
            recordingDraftScreenOffPersistCount = recordingDraftPersistCount.screenOffCount.get(),
            recordingDraftJsonBytesWritten = recordingDraftJsonBytesWritten.count.get(),
            recordingDraftScreenOffJsonBytesWritten = recordingDraftJsonBytesWritten.screenOffCount.get(),
            recordingDraftGpxBytesWritten = recordingDraftGpxBytesWritten.count.get(),
            recordingDraftScreenOffGpxBytesWritten = recordingDraftGpxBytesWritten.screenOffCount.get(),
            recordingDraftTotalBytesWritten = recordingDraftTotalBytesWritten.count.get(),
            recordingDraftScreenOffTotalBytesWritten = recordingDraftTotalBytesWritten.screenOffCount.get(),
            recordingDraftMaxPointCount = recordingDraftMaxPointCount.count.get(),
            recordingDraftScreenOffMaxPointCount = recordingDraftMaxPointCount.screenOffCount.get(),
            recordingDraftPointsSerialized = recordingDraftPointsSerialized.count.get(),
            recordingDraftScreenOffPointsSerialized = recordingDraftPointsSerialized.screenOffCount.get(),
            recordingSensorCallbackCount = recordingSensorCallbackCount.count.get(),
            recordingSensorScreenOffCallbackCount = recordingSensorCallbackCount.screenOffCount.get(),
            recordingSensorUiPublishCount = recordingSensorUiPublishCount.count.get(),
            recordingSensorScreenOffUiPublishCount = recordingSensorUiPublishCount.screenOffCount.get(),
            sensorCallbackCounts = sensorCountsSnapshot(sensorCallbackCounts),
            sensorUiPublishCounts = sensorCountsSnapshot(sensorUiPublishCounts),
        )

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

    private fun recordEnabled(
        counter: ClassifiedCounter,
        amount: Long = 1L,
    ) {
        val safeAmount = amount.coerceAtLeast(0L)
        counter.count.addAndGet(safeAmount)
        if (nonInteractive.get()) counter.screenOffCount.addAndGet(safeAmount)
    }

    private fun updateMaxEnabled(
        counter: ClassifiedCounter,
        value: Long,
    ) {
        updateMax(counter.count, value)
        if (nonInteractive.get()) updateMax(counter.screenOffCount, value)
    }

    private fun updateMax(
        target: AtomicLong,
        value: Long,
    ) {
        var previous = target.get()
        while (value > previous && !target.compareAndSet(previous, value)) {
            previous = target.get()
        }
    }

    private fun sensorCountsSnapshot(counters: Array<ClassifiedCounter>): Map<String, RecordingDiagnosticCounter> =
        RecordingSensorDiagnosticKind.entries.associate { kind ->
            kind.token to
                RecordingDiagnosticCounter(
                    count = counters[kind.ordinal].count.get(),
                    screenOffCount = counters[kind.ordinal].screenOffCount.get(),
                )
        }

    private fun resetInstrumentation() {
        arrayOf(
            dashboardTickCount,
            dashboardSnapshotBuildCount,
            dashboardPointsScanned,
            tbtProjectionRunCount,
            tbtProjectionSegmentsScanned,
            tbtProjectionMaxSegments,
            recordingDraftPersistCount,
            recordingDraftJsonBytesWritten,
            recordingDraftGpxBytesWritten,
            recordingDraftTotalBytesWritten,
            recordingDraftMaxPointCount,
            recordingDraftPointsSerialized,
            recordingSensorCallbackCount,
            recordingSensorUiPublishCount,
        ).forEach { counter ->
            counter.count.set(0L)
            counter.screenOffCount.set(0L)
        }
        sensorCallbackCounts.forEach { counter ->
            counter.count.set(0L)
            counter.screenOffCount.set(0L)
        }
        sensorUiPublishCounts.forEach { counter ->
            counter.count.set(0L)
            counter.screenOffCount.set(0L)
        }
    }

    private fun snapshot(activity: RecordingScreenOffActivity): RecordingScreenOffActivityCounter =
        RecordingScreenOffActivityCounter(
            count = countByActivity[activity.ordinal].getAndSet(0L),
            elapsedMs = elapsedMsByActivity[activity.ordinal].getAndSet(0L),
        )
}
