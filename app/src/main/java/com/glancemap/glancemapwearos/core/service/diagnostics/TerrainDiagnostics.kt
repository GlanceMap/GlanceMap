package com.glancemap.glancemapwearos.core.service.diagnostics

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

internal object TerrainDiagnostics {
    private const val TAG = "TerrainDiagnostics"
    private const val MAX_LINES = 300

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var droppedLines = 0
    private val nextCorrelationId = AtomicLong(0L)

    fun clear() {
        synchronized(lock) {
            lines.clear()
            droppedLines = 0
        }
    }

    fun snapshotLines(): List<String> = synchronized(lock) { lines.toList() }

    fun droppedLineCount(): Int = synchronized(lock) { droppedLines }

    fun maxBufferedLines(): Int = MAX_LINES

    fun nextCorrelationId(): Long = nextCorrelationId.incrementAndGet()

    fun record(
        event: String,
        detail: String = "",
    ) {
        val line =
            buildString {
                append("event=").append(event)
                if (detail.isNotBlank()) append(' ').append(detail)
            }
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                droppedLines += 1
            }
        }
        DebugTelemetry.log(TAG, line)
    }

    fun redactedMapIdentity(path: String?): String = path?.hashCode()?.toUInt()?.toString(16) ?: "none"

    fun signatureIdentity(signature: String?): String = signature?.hashCode()?.toUInt()?.toString(16) ?: "none"
}
