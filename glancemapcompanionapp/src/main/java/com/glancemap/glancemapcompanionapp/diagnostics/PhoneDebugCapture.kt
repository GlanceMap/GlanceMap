package com.glancemap.glancemapcompanionapp.diagnostics

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class PhoneDebugCaptureState(
    val active: Boolean = false,
    val sessionId: Long = 0L,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val bufferedLines: Int = 0,
    val droppedLines: Int = 0,
    val totalLoggedLines: Long = 0L,
    val interrupted: Boolean = false,
    val hasPreviousCapture: Boolean = false,
)

enum class PhoneDebugCaptureSlot { CURRENT, PREVIOUS }

private data class PersistedPhoneDebugCapture(
    val state: PhoneDebugCaptureState,
    val lines: List<String>,
    val sections: Map<String, String>,
)

/** Explicit user diagnostics survive a process termination without collecting background telemetry. */
@Suppress("TooManyFunctions") // Lifecycle, journal, and export ownership deliberately stay together.
object PhoneDebugCapture {
    private const val MAX_LINES = 4000
    private const val STORAGE_VERSION = 1
    private const val CURRENT_FILE = "phone-debug-capture-current.bin"
    private const val PREVIOUS_FILE = "phone-debug-capture-previous.bin"

    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val sections = linkedMapOf<String, String>()
    private val stateFlow = MutableStateFlow(PhoneDebugCaptureState())
    private val persistenceScheduled = AtomicBoolean(false)
    private val persistenceWriter =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-debug-capture").apply { isDaemon = true }
        }
    private val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private var directory: File? = null
    private var previous: PersistedPhoneDebugCapture? = null
    private var crashHandlerInstalled = false
    private var persistenceRevision = 0L

    val state = stateFlow.asStateFlow()

    /** Must be called from the companion entry activity before user UI is shown. */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (directory != null) return
            directory = File(context.applicationContext.filesDir, "diagnostics").also(File::mkdirs)
            restoreCurrentCapture()
            previous = readCapture(PREVIOUS_FILE)
            stateFlow.value = stateFlow.value.copy(hasPreviousCapture = previous != null)
            installCrashCaptureHook()
        }
    }

    fun start() {
        flushPersistedWrites()
        synchronized(lock) {
            val current = snapshotLocked()
            if (current.state.sessionId > 0L) {
                previous = current
                writeCapture(PREVIOUS_FILE, current)
            }
            lines.clear()
            sections.clear()
            enabled.set(true)
            stateFlow.value =
                PhoneDebugCaptureState(
                    active = true,
                    sessionId = maxOf(current.state.sessionId, previous?.state?.sessionId ?: 0L) + 1L,
                    startedAtMs = System.currentTimeMillis(),
                    hasPreviousCapture = previous != null,
                )
            writeCapture(CURRENT_FILE, snapshotLocked())
        }
    }

    fun stop(): PhoneDebugCaptureState {
        flushPersistedWrites()
        return synchronized(lock) {
            enabled.set(false)
            stateFlow.value = stateFlow.value.copy(active = false, endedAtMs = System.currentTimeMillis())
            writeCapture(CURRENT_FILE, snapshotLocked())
            stateFlow.value
        }
    }

    fun isActive(): Boolean = enabled.get()

    @Suppress("MaxLineLength") // Keeps the trivial slot lookup as one expression.
    fun snapshot(slot: PhoneDebugCaptureSlot = PhoneDebugCaptureSlot.CURRENT): List<String> = synchronized(lock) { captureFor(slot)?.lines.orEmpty() }

    @Suppress("MaxLineLength") // Keeps the trivial slot lookup as one expression.
    fun hasCapture(slot: PhoneDebugCaptureSlot): Boolean = synchronized(lock) { captureFor(slot)?.state?.sessionId?.let { it > 0L } == true }

    fun updateSection(
        key: String,
        reportText: String,
    ) {
        if (!enabled.get() || reportText.isBlank()) return
        synchronized(lock) {
            sections[key] = reportText.trimEnd()
            persistCurrentLocked()
        }
    }

    fun log(
        tag: String,
        message: String,
    ) {
        if (!enabled.get()) return
        synchronized(lock) {
            val current = stateFlow.value
            lines.addLast("${tsFormatter.format(Instant.now())} [$tag] $message")
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
            persistCurrentLocked()
        }
    }

    fun buildReport(
        context: Context,
        additionalSections: List<String> = emptyList(),
        slot: PhoneDebugCaptureSlot = PhoneDebugCaptureSlot.CURRENT,
    ): String {
        val capture =
            synchronized(lock) { captureFor(slot) }
                ?: PersistedPhoneDebugCapture(PhoneDebugCaptureState(), emptyList(), emptyMap())
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
        val reportSections =
            capture.sections.values +
                additionalSections.filter { it.isNotBlank() && it !in capture.sections.values }
        return buildString {
            appendLine("GlanceMap Companion Diagnostics")
            appendLine("Generated: ${timestampFormatter.format(Instant.now())}")
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
            appendLine("Slot: $slot")
            appendLine("SessionId: ${capture.state.sessionId}")
            appendLine("ActiveAtExport: ${capture.state.active}")
            appendLine("Interrupted: ${capture.state.interrupted}")
            if (capture.state.interrupted) appendLine("Termination: process ended before capture stopped")
            appendLine("StartedAt: ${formatTime(capture.state.startedAtMs)}")
            appendLine("EndedAt: ${formatTime(capture.state.endedAtMs)}")
            appendLine("BufferedLines: ${capture.state.bufferedLines}")
            appendLine("DroppedLines: ${capture.state.droppedLines}")
            appendLine("TotalLoggedLines: ${capture.state.totalLoggedLines}")
            reportSections.forEach { section ->
                appendLine()
                appendLine(section.trimEnd())
            }
            appendLine()
            appendLine("Logs")
            if (capture.lines.isEmpty()) {
                appendLine("No logs captured. Start recording and reproduce the issue.")
            } else {
                capture.lines.forEach(::appendLine)
            }
        }
    }

    private fun restoreCurrentCapture() {
        val restored = readCapture(CURRENT_FILE) ?: return
        val recovered =
            if (restored.state.active) {
                restored
                    .copy(state = restored.state.copy(active = false, interrupted = true, endedAtMs = null))
                    .also { writeCapture(CURRENT_FILE, it) }
            } else {
                restored
            }
        lines.clear()
        lines.addAll(recovered.lines.takeLast(MAX_LINES))
        sections.clear()
        sections.putAll(recovered.sections)
        enabled.set(false)
        stateFlow.value = recovered.state.copy(bufferedLines = lines.size)
    }

    private fun captureFor(slot: PhoneDebugCaptureSlot): PersistedPhoneDebugCapture? =
        when (slot) {
            PhoneDebugCaptureSlot.CURRENT -> snapshotLocked().takeIf { it.state.sessionId > 0L }
            PhoneDebugCaptureSlot.PREVIOUS -> previous
        }

    private fun snapshotLocked() = PersistedPhoneDebugCapture(stateFlow.value, lines.toList(), sections.toMap())

    private fun persistCurrentLocked() {
        val storageDirectory = directory ?: return
        persistenceRevision += 1L
        if (!persistenceScheduled.compareAndSet(false, true)) return
        persistenceWriter.execute {
            while (true) {
                val (capture, revision) = synchronized(lock) { snapshotLocked() to persistenceRevision }
                writeCapture(storageDirectory, CURRENT_FILE, capture)
                val complete = synchronized(lock) { revision == persistenceRevision }
                if (complete) {
                    persistenceScheduled.set(false)
                    return@execute
                }
            }
        }
    }

    private fun readCapture(fileName: String): PersistedPhoneDebugCapture? {
        return directory?.let { storageDirectory ->
            runCatching {
                DataInputStream(FileInputStream(File(storageDirectory, fileName))).use { input ->
                    if (input.readInt() != STORAGE_VERSION) return@use null
                    val state =
                        PhoneDebugCaptureState(
                            active = input.readBoolean(),
                            sessionId = input.readLong(),
                            startedAtMs = input.readLong().takeIf { it >= 0L },
                            endedAtMs = input.readLong().takeIf { it >= 0L },
                            bufferedLines = input.readInt(),
                            droppedLines = input.readInt(),
                            totalLoggedLines = input.readLong(),
                            interrupted = input.readBoolean(),
                        )
                    val persistedLines = List(input.readInt()) { input.readUTF() }
                    val persistedSections =
                        buildMap { repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) } }
                    PersistedPhoneDebugCapture(state, persistedLines, persistedSections)
                }
            }.getOrNull()
        }
    }

    private fun writeCapture(
        fileName: String,
        capture: PersistedPhoneDebugCapture,
    ) {
        directory?.let { writeCapture(it, fileName, capture) }
    }

    private fun writeCapture(
        storageDirectory: File,
        fileName: String,
        capture: PersistedPhoneDebugCapture,
    ) {
        storageDirectory.mkdirs()
        val target = File(storageDirectory, fileName)
        val temporary = File(storageDirectory, "$fileName.tmp")
        runCatching {
            DataOutputStream(FileOutputStream(temporary)).use { output ->
                output.writeInt(STORAGE_VERSION)
                output.writeBoolean(capture.state.active)
                output.writeLong(capture.state.sessionId)
                output.writeLong(capture.state.startedAtMs ?: -1L)
                output.writeLong(capture.state.endedAtMs ?: -1L)
                output.writeInt(capture.state.bufferedLines)
                output.writeInt(capture.state.droppedLines)
                output.writeLong(capture.state.totalLoggedLines)
                output.writeBoolean(capture.state.interrupted)
                output.writeInt(capture.lines.size)
                capture.lines.forEach(output::writeUTF)
                output.writeInt(capture.sections.size)
                capture.sections.forEach { (key, value) ->
                    output.writeUTF(key)
                    output.writeUTF(value)
                }
                output.flush()
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    private fun installCrashCaptureHook() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled.get()) {
                log(
                    "UncaughtException",
                    "class=${error.javaClass.name} message=${error.message.orEmpty().take(240)} " +
                        "thread=${thread.name} stack=${error.stackTrace.take(24).joinToString(" | ")}",
                )
                synchronized(lock) { writeCapture(CURRENT_FILE, snapshotLocked()) }
            }
            if (previousHandler != null) previousHandler.uncaughtException(thread, error) else throw error
        }
    }

    @VisibleForTesting
    internal fun useTestStorage(storageDirectory: File) {
        flushForTest()
        synchronized(lock) {
            directory = storageDirectory.also(File::mkdirs)
            lines.clear()
            sections.clear()
            previous = null
            enabled.set(false)
            stateFlow.value = PhoneDebugCaptureState()
        }
    }

    @VisibleForTesting
    internal fun reloadFromStorageForTest() {
        flushForTest()
        synchronized(lock) {
            lines.clear()
            sections.clear()
            previous = null
            enabled.set(false)
            stateFlow.value = PhoneDebugCaptureState()
            restoreCurrentCapture()
            previous = readCapture(PREVIOUS_FILE)
            stateFlow.value = stateFlow.value.copy(hasPreviousCapture = previous != null)
        }
    }

    @VisibleForTesting
    internal fun sectionForTest(
        slot: PhoneDebugCaptureSlot,
        key: String,
    ): String? = synchronized(lock) { captureFor(slot)?.sections?.get(key) }

    @VisibleForTesting
    internal fun flushForTest() {
        flushPersistedWrites()
    }

    private fun flushPersistedWrites() {
        persistenceWriter.submit {}.get()
    }

    @Suppress("MaxLineLength") // One direct timestamp conversion is clearer than another formatting helper.
    private fun formatTime(epochMs: Long?): String = epochMs?.let { tsFormatter.format(Instant.ofEpochMilli(it)) } ?: "na"
}
