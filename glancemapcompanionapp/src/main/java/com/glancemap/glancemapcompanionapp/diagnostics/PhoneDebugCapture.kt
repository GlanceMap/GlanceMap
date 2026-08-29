package com.glancemap.glancemapcompanionapp.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

data class PhoneDebugCaptureState(
    val active: Boolean = false,
    val sessionId: Long = 0L,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val bufferedLines: Int = 0,
    val droppedLines: Int = 0,
    val totalLoggedLines: Long = 0L,
)

object PhoneDebugCapture {
    private const val MAX_LINES = 4000

    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val stateFlow = MutableStateFlow(PhoneDebugCaptureState())
    private val tsFormatter: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    val state = stateFlow.asStateFlow()

    fun start() {
        synchronized(lock) {
            val nextSessionId = stateFlow.value.sessionId + 1L
            lines.clear()
            enabled.set(true)
            stateFlow.value =
                PhoneDebugCaptureState(
                    active = true,
                    sessionId = nextSessionId,
                    startedAtMs = System.currentTimeMillis(),
                    endedAtMs = null,
                    bufferedLines = 0,
                    droppedLines = 0,
                    totalLoggedLines = 0L,
                )
        }
    }

    fun stop(): PhoneDebugCaptureState =
        synchronized(lock) {
            enabled.set(false)
            val current =
                stateFlow.value.copy(
                    active = false,
                    endedAtMs = System.currentTimeMillis(),
                )
            stateFlow.value = current
            current
        }

    fun isActive(): Boolean = enabled.get()

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun log(
        tag: String,
        message: String,
    ) {
        if (!enabled.get()) return

        synchronized(lock) {
            val current = stateFlow.value
            val nowMs = System.currentTimeMillis()
            val line = "${tsFormatter.format(Instant.ofEpochMilli(nowMs))} [$tag] $message"
            lines.addLast(line)

            var dropped = current.droppedLines
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                dropped += 1
            }

            stateFlow.value =
                current.copy(
                    bufferedLines = lines.size,
                    droppedLines = dropped,
                    totalLoggedLines = current.totalLoggedLines + 1L,
                )
        }
    }

    fun buildReport(
        context: Context,
        additionalSections: List<String> = emptyList(),
    ): String {
        val state = state.value
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val generatedAt = Instant.now()
        val timestampFormatter =
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.systemDefault())

        return buildString {
            appendLine("GlanceMap Companion Diagnostics")
            appendLine("Generated: ${timestampFormatter.format(generatedAt)}")
            appendLine()
            appendLine("App")
            appendLine("Package: ${context.packageName}")
            appendLine("VersionName: ${packageInfo.versionName}")
            appendLine("VersionCode: ${PackageInfoCompat.getLongVersionCode(packageInfo)}")
            appendLine()
            appendLine("Device")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Capture")
            appendLine("SessionId: ${state.sessionId}")
            appendLine("ActiveAtExport: ${state.active}")
            appendLine("StartedAt: ${formatTime(state.startedAtMs)}")
            appendLine("EndedAt: ${formatTime(state.endedAtMs)}")
            appendLine("BufferedLines: ${state.bufferedLines}")
            appendLine("DroppedLines: ${state.droppedLines}")
            appendLine("TotalLoggedLines: ${state.totalLoggedLines}")
            additionalSections.filter(String::isNotBlank).forEach { section ->
                appendLine()
                appendLine(section.trimEnd())
            }
            appendLine()
            appendLine("Logs")
            val snapshot = snapshot()
            if (snapshot.isEmpty()) {
                appendLine("No logs captured. Start recording and reproduce the issue.")
            } else {
                snapshot.forEach { appendLine(it) }
            }
        }
    }

    private fun formatTime(epochMs: Long?): String {
        if (epochMs == null) return "na"
        return tsFormatter.format(Instant.ofEpochMilli(epochMs))
    }
}
