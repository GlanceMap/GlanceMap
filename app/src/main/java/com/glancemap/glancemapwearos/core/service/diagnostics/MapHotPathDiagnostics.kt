package com.glancemap.glancemapwearos.core.service.diagnostics

import android.os.SystemClock
import java.util.ArrayDeque

internal data class MapHotPathStageSummary(
    val stage: String,
    val count: Int,
    val avgDurationMs: Long,
    val maxDurationMs: Long,
    val slowCount: Int,
    val errorCount: Int,
)

internal data class MapHotPathSummary(
    val eventCount: Int,
    val droppedLineCount: Int,
    val maxBufferedLines: Int,
    val stageCount: Int,
    val slowEventCount: Int,
    val errorEventCount: Int,
    val maxDurationMs: Long,
    val stageSummaries: List<MapHotPathStageSummary>,
)

@Suppress("TooManyFunctions")
internal object MapHotPathDiagnostics {
    private const val TAG = "MapHotPath"
    private const val MAX_LINES = 400
    private const val SLOW_EVENT_THRESHOLD_MS = 250L

    class Marker internal constructor(
        val stage: String,
        val startedAtElapsedMs: Long,
        val diagnosticsEnabled: Boolean,
    )

    private data class TimingEvent(
        val relativeMs: Long,
        val stage: String,
        val durationMs: Long,
        val status: String,
        val detail: String,
    )

    private data class StageAccumulator(
        var count: Int = 0,
        var totalDurationMs: Long = 0L,
        var maxDurationMs: Long = 0L,
        var slowCount: Int = 0,
        var errorCount: Int = 0,
    )

    private val lock = Any()
    private val events = ArrayDeque<TimingEvent>()
    private var droppedLines: Int = 0
    private var sessionStartElapsedMs: Long = 0L

    fun clear() {
        synchronized(lock) {
            events.clear()
            droppedLines = 0
            sessionStartElapsedMs = 0L
        }
    }

    fun snapshotLines(): List<String> =
        synchronized(lock) {
            events.map { event ->
                buildString {
                    append("+").append(event.relativeMs).append("ms")
                    append(" stage=").append(event.stage)
                    append(" durationMs=").append(event.durationMs)
                    append(" status=").append(event.status)
                    if (event.detail.isNotBlank()) {
                        append(" ").append(event.detail)
                    }
                }
            }
        }

    fun droppedLineCount(): Int = synchronized(lock) { droppedLines }

    fun maxBufferedLines(): Int = MAX_LINES

    fun summary(): MapHotPathSummary =
        synchronized(lock) {
            val perStage = linkedMapOf<String, StageAccumulator>()
            var slowEventCount = 0
            var errorEventCount = 0
            var maxDurationMs = 0L

            events.forEach { event ->
                val accumulator = perStage.getOrPut(event.stage) { StageAccumulator() }
                accumulator.count += 1
                accumulator.totalDurationMs += event.durationMs
                accumulator.maxDurationMs = maxOf(accumulator.maxDurationMs, event.durationMs)
                if (event.durationMs >= SLOW_EVENT_THRESHOLD_MS) {
                    accumulator.slowCount += 1
                    slowEventCount += 1
                }
                if (event.status.startsWith("error")) {
                    accumulator.errorCount += 1
                    errorEventCount += 1
                }
                maxDurationMs = maxOf(maxDurationMs, event.durationMs)
            }

            val stageSummaries =
                perStage.entries
                    .map { (stage, accumulator) ->
                        MapHotPathStageSummary(
                            stage = stage,
                            count = accumulator.count,
                            avgDurationMs =
                                if (accumulator.count > 0) {
                                    accumulator.totalDurationMs / accumulator.count
                                } else {
                                    0L
                                },
                            maxDurationMs = accumulator.maxDurationMs,
                            slowCount = accumulator.slowCount,
                            errorCount = accumulator.errorCount,
                        )
                    }.sortedWith(
                        compareByDescending<MapHotPathStageSummary> { it.maxDurationMs }
                            .thenByDescending { it.avgDurationMs }
                            .thenBy { it.stage },
                    )

            MapHotPathSummary(
                eventCount = events.size,
                droppedLineCount = droppedLines,
                maxBufferedLines = MAX_LINES,
                stageCount = stageSummaries.size,
                slowEventCount = slowEventCount,
                errorEventCount = errorEventCount,
                maxDurationMs = maxDurationMs,
                stageSummaries = stageSummaries,
            )
        }

    fun begin(stage: String): Marker? {
        BenchmarkTrace.begin(stage)
        val diagnosticsEnabled = DebugTelemetry.isEnabled()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (diagnosticsEnabled) {
            synchronized(lock) {
                ensureSession(nowElapsedMs)
            }
        }
        return Marker(
            stage = stage,
            startedAtElapsedMs = nowElapsedMs,
            diagnosticsEnabled = diagnosticsEnabled,
        )
    }

    fun end(
        marker: Marker?,
        status: String = "ok",
        detail: String = "",
    ) {
        if (marker == null) return
        if (!marker.diagnosticsEnabled) {
            BenchmarkTrace.end()
            return
        }
        try {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val eventLine =
                recordTimingEvent(
                    stage = marker.stage,
                    startedAtElapsedMs = marker.startedAtElapsedMs,
                    completedAtElapsedMs = nowElapsedMs,
                    status = status,
                    detail = detail,
                )
            DebugTelemetry.log(TAG, eventLine)
        } finally {
            BenchmarkTrace.end()
        }
    }

    fun recordInterval(
        stage: String,
        startedAtElapsedMs: Long,
        completedAtElapsedMs: Long,
        status: String = "ok",
        detail: String = "",
    ) {
        if (!DebugTelemetry.isEnabled()) return
        val eventLine =
            recordTimingEvent(
                stage = stage,
                startedAtElapsedMs = startedAtElapsedMs,
                completedAtElapsedMs = completedAtElapsedMs,
                status = status,
                detail = detail,
            )
        DebugTelemetry.log(TAG, eventLine)
    }

    fun recordEvent(
        stage: String,
        status: String = "ok",
        detail: String = "",
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        recordInterval(
            stage = stage,
            startedAtElapsedMs = nowElapsedMs,
            completedAtElapsedMs = nowElapsedMs,
            status = status,
            detail = detail,
        )
    }

    inline fun <T> measure(
        stage: String,
        detail: String = "",
        block: () -> T,
    ): T {
        val marker = begin(stage)
        return try {
            val result = block()
            end(marker = marker, detail = detail)
            result
        } catch (error: Throwable) {
            end(
                marker = marker,
                status = "error_${error.javaClass.simpleName}",
                detail = detail,
            )
            throw error
        }
    }

    private fun ensureSession(nowElapsedMs: Long) {
        if (sessionStartElapsedMs != 0L) return
        sessionStartElapsedMs = nowElapsedMs
    }

    private fun recordTimingEvent(
        stage: String,
        startedAtElapsedMs: Long,
        completedAtElapsedMs: Long,
        status: String,
        detail: String,
    ): String =
        synchronized(lock) {
            ensureSession(startedAtElapsedMs)
            val event =
                TimingEvent(
                    relativeMs = (completedAtElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L),
                    stage = stage,
                    durationMs = (completedAtElapsedMs - startedAtElapsedMs).coerceAtLeast(0L),
                    status = status,
                    detail = detail,
                )
            events.addLast(event)
            while (events.size > MAX_LINES) {
                events.removeFirst()
                droppedLines += 1
            }
            buildString {
                append("+").append(event.relativeMs).append("ms")
                append(" stage=").append(event.stage)
                append(" durationMs=").append(event.durationMs)
                append(" status=").append(event.status)
                if (event.detail.isNotBlank()) {
                    append(" ").append(event.detail)
                }
            }
        }
}
