package com.glancemap.glancemapwearos.core.service.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import kotlin.math.sqrt

internal data class CompassDeepTraceState(
    val active: Boolean = false,
    val startedAtEpochMs: Long? = null,
    val lastStopReason: String? = null,
)

internal data class CompassDeepTraceSnapshot(
    val active: Boolean,
    val sessionCount: Int,
    val windowCount: Int,
    val droppedLines: Int,
    val lastStopReason: String?,
    val lines: List<String>,
    val events: List<CompassDeepTraceEventRecord> = emptyList(),
    val droppedEvents: Int = 0,
    val incident: CompassDeepTraceIncidentSnapshot? = null,
)

internal object CompassDeepTraceDiagnostics {
    private const val TAG = "CompassDeepTrace"
    private const val MAX_BUFFERED_LINES = 720
    private const val WINDOW_DURATION_MS = 5_000L
    private val lock = Any()
    private val _state = MutableStateFlow(CompassDeepTraceState())
    private val lines = ArrayDeque<String>()
    private val eventRing = CompassDeepTraceEventRing(COMPASS_DEEP_TRACE_DECISION_EVENT_CAPACITY)
    private var incidentCapture: CompassDeepTraceIncidentCapture? = null
    private var preservedIncident: CompassDeepTraceIncidentSnapshot? = null
    private var droppedLines = 0
    private var sessionCount = 0
    private var windowCount = 0
    private var activeSessionStartWindowCount = 0
    private var currentWindow: CompassDeepTraceWindowAccumulator? = null
    private var sensorRegistration: CompassDeepTraceSensorRegistration? = null
    private val gyroHistory = ArrayDeque<CompassDeepTraceGyroSample>()

    val state: StateFlow<CompassDeepTraceState> = _state.asStateFlow()

    fun start(
        context: Context,
        batteryBenchmarkSelected: Boolean,
    ): Boolean {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowEpochMs = System.currentTimeMillis()
        val startLine: String
        val inventoryLine: String
        val registration: CompassDeepTraceSensorRegistration
        synchronized(lock) {
            if (_state.value.active) return false
            sessionCount += 1
            eventRing.clear()
            incidentCapture = null
            preservedIncident = null
            activeSessionStartWindowCount = windowCount
            currentWindow = CompassDeepTraceWindowAccumulator(startedAtElapsedMs = nowElapsedMs)
            registration =
                startCompassDeepTraceSensorRegistration(context) { sensor, values, atElapsedMs ->
                    recordRawSensorSample(sensor, values, atElapsedMs)
                }
            sensorRegistration = registration
            _state.value =
                CompassDeepTraceState(
                    active = true,
                    startedAtEpochMs = nowEpochMs,
                )
            startLine =
                "session_start schemaVersion=$COMPASS_DEEP_TRACE_SCHEMA_VERSION " +
                "id=$sessionCount atMs=$nowEpochMs autoStop=false " +
                "windowMs=$WINDOW_DURATION_MS bufferLines=$MAX_BUFFERED_LINES " +
                "decisionEventCapacity=$COMPASS_DEEP_TRACE_DECISION_EVENT_CAPACITY sensorPeriodUs=40000"
            appendLineLocked(startLine)
            val registeredSensors = registration.registeredSensors
            inventoryLine = "session_sensors id=$sessionCount registered=${registeredSensors.ifEmpty { "none" }}"
            appendLineLocked(inventoryLine)
            recordEventLocked(CompassDeepTraceEvent.Telemetry(nowElapsedMs, startLine))
            recordEventLocked(CompassDeepTraceEvent.Telemetry(nowElapsedMs, inventoryLine))
        }

        Log.d(TAG, startLine)
        Log.d(TAG, inventoryLine)

        if (batteryBenchmarkSelected || EnergyDiagnostics.isBatteryBenchmarkActive()) {
            EnergyDiagnostics.markBatteryBenchmarkInvalid("compass_deep_trace")
        }
        return true
    }

    fun stop(reason: String) {
        val registration: CompassDeepTraceSensorRegistration?
        val completedLines = mutableListOf<String>()
        synchronized(lock) {
            if (!_state.value.active) return
            flushWindowLocked(SystemClock.elapsedRealtime())?.let(completedLines::add)
            val stopLine =
                "session_stop id=$sessionCount atMs=${System.currentTimeMillis()} reason=$reason " +
                    "windows=${windowCount - activeSessionStartWindowCount}"
            appendLineLocked(stopLine)
            completedLines += stopLine
            recordEventLocked(
                CompassDeepTraceEvent.Telemetry(
                    atElapsedMs = SystemClock.elapsedRealtime(),
                    line = stopLine,
                ),
            )
            registration = sensorRegistration
            sensorRegistration = null
            currentWindow = null
            gyroHistory.clear()
            freezeIncidentLocked(
                postTailComplete =
                    SystemClock.elapsedRealtime() >= incidentPostTailDeadlineOrMax(),
            )
            _state.value = CompassDeepTraceState(lastStopReason = reason)
        }
        registration?.stop()
        completedLines.forEach { Log.d(TAG, it) }
    }

    fun clear() {
        stop(reason = "cleared")
        synchronized(lock) {
            lines.clear()
            eventRing.clear()
            incidentCapture = null
            preservedIncident = null
            droppedLines = 0
            sessionCount = 0
            windowCount = 0
            activeSessionStartWindowCount = 0
            gyroHistory.clear()
            _state.value = CompassDeepTraceState()
        }
    }

    fun recordProviderSample(sample: CompassDeepTraceProviderSample) {
        recordAt(sample.atElapsedMs) { it.recordProvider(sample) }
        synchronized(lock) {
            if (!_state.value.active) return
            recordEventLocked(
                CompassDeepTraceEvent.ProviderMeasurement(
                    atElapsedMs = sample.atElapsedMs,
                    provider = sample.provider,
                    headingDeg = sample.headingDeg,
                    sourceSampleId = sample.sourceSampleId,
                    sourceMeasurementAtElapsedMs = sample.sourceMeasurementAtElapsedMs,
                    callbackArrivalAtElapsedMs = sample.callbackArrivalAtElapsedMs,
                    processingAtElapsedMs = sample.processingAtElapsedMs,
                    measurementDisposition = sample.measurementDisposition,
                    accuracy = sample.accuracy,
                    usable = sample.usable,
                    provenance = sample.provenance,
                ),
            )
            recordEventLocked(
                CompassDeepTraceEvent.IntegrityDecision(
                    atElapsedMs = sample.atElapsedMs,
                    provider = sample.provider,
                    sourceSampleId = sample.sourceSampleId,
                    headingDeg = sample.headingDeg,
                    liveHeadingErrorDeg = sample.liveHeadingErrorDeg,
                    conservativeHeadingErrorDeg = sample.conservativeHeadingErrorDeg,
                    trackingState = sample.trackingState,
                    trackingReason = sample.trackingReason,
                    relativeHeadingDeg = sample.relativeHeadingDeg,
                    fusedRelativeDisagreementDeg = sample.fusedRelativeDisagreementDeg,
                    targetHeadingDeg = sample.targetHeadingDeg,
                    trusted = sample.trusted,
                    quarantineActive = sample.quarantineActive,
                    recoveryActive = sample.recoveryActive,
                    heldOutput = sample.heldOutput,
                    provenance = sample.provenance,
                ),
            )
        }
    }

    fun recordRenderSample(sample: CompassDeepTraceRenderSample) {
        recordAt(sample.atElapsedMs) { it.recordRender(sample) }
        synchronized(lock) {
            if (!_state.value.active) return
            recordEventLocked(
                CompassDeepTraceEvent.Render(
                    atElapsedMs = sample.atElapsedMs,
                    sourceSampleId = sample.sourceSampleId,
                    targetHeadingDeg = sample.targetHeadingDeg,
                    renderedHeadingDeg = sample.renderedHeadingDeg,
                    mapRotationDeg = sample.mapRotationDeg,
                    heldOutput = sample.heldOutput,
                    provenance = sample.provenance,
                ),
            )
        }
    }

    /** Stores compass lifecycle and integrity events while the optional trace is active. */
    fun recordTelemetryLine(line: String) {
        synchronized(lock) {
            if (_state.value.active) {
                appendLineLocked(line)
                recordEventLocked(
                    CompassDeepTraceEvent.Telemetry(
                        atElapsedMs = SystemClock.elapsedRealtime(),
                        line = line,
                    ),
                )
            }
        }
    }

    fun recordMarker(
        type: String,
        detail: String = "",
        atElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(lock) {
            if (!_state.value.active) return
            if (
                type == HEADING_LOOKS_WRONG_MARKER &&
                incidentCapture == null &&
                preservedIncident == null
            ) {
                incidentCapture =
                    CompassDeepTraceIncidentCapture(
                        preMarkerEvents = eventRing.snapshot(),
                        preMarkerLiveRingDroppedEvents = eventRing.droppedEvents,
                        postTailEndsAtElapsedMs = atElapsedMs + COMPASS_DEEP_TRACE_INCIDENT_POST_TAIL_MS,
                        postEventCapacity = COMPASS_DEEP_TRACE_INCIDENT_POST_EVENT_CAPACITY,
                    )
            }
            recordEventLocked(CompassDeepTraceEvent.Marker(atElapsedMs, type, detail))
        }
    }

    fun recordUiConfidence(
        provider: String,
        quality: String,
        accuracyColorsEnabled: Boolean,
        provenance: CompassHeadingProvenance?,
        atElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(lock) {
            if (!_state.value.active) return
            recordEventLocked(
                CompassDeepTraceEvent.UiConfidence(
                    atElapsedMs = atElapsedMs,
                    provider = provider,
                    quality = quality,
                    accuracyColorsEnabled = accuracyColorsEnabled,
                    provenance = provenance,
                ),
            )
        }
    }

    /** Values are available only while the optional deep trace is already running. */
    fun gyroMotionForInterval(
        endElapsedMs: Long,
        intervalMs: Long,
    ): CompassDeepTraceGyroMotion {
        if (!_state.value.active) return CompassDeepTraceGyroMotion()
        val startElapsedMs = endElapsedMs - intervalMs.coerceAtLeast(1L)
        synchronized(lock) {
            val samples = gyroHistory.filter { it.atElapsedMs in startElapsedMs..endElapsedMs }
            if (samples.isEmpty()) return CompassDeepTraceGyroMotion()
            var integratedRadians = 0f
            samples.zipWithNext { previous, current ->
                val intervalSeconds = (current.atElapsedMs - previous.atElapsedMs).coerceAtLeast(0L) / 1_000f
                integratedRadians += previous.magnitudeRadPerSec * intervalSeconds
            }
            return CompassDeepTraceGyroMotion(
                integratedRotationDeg = Math.toDegrees(integratedRadians.toDouble()).toFloat(),
                peakDegPerSec = Math.toDegrees(samples.maxOf { it.magnitudeRadPerSec }.toDouble()).toFloat(),
            )
        }
    }

    fun snapshot(): CompassDeepTraceSnapshot =
        synchronized(lock) {
            if (
                incidentCapture != null &&
                SystemClock.elapsedRealtime() >= incidentPostTailDeadlineOrMax()
            ) {
                freezeIncidentLocked(postTailComplete = true)
            }
            CompassDeepTraceSnapshot(
                active = _state.value.active,
                sessionCount = sessionCount,
                windowCount = windowCount,
                droppedLines = droppedLines,
                lastStopReason = _state.value.lastStopReason,
                lines = lines.toList(),
                events = eventRing.snapshot(),
                droppedEvents = eventRing.droppedEvents,
                incident = preservedIncident,
            )
        }

    private fun recordEventLocked(event: CompassDeepTraceEvent) {
        val record = eventRing.record(event) ?: return
        val capture = incidentCapture ?: return
        if (!capture.record(record)) {
            freezeIncidentLocked(postTailComplete = true)
        }
    }

    private fun freezeIncidentLocked(postTailComplete: Boolean) {
        val capture = incidentCapture ?: return
        preservedIncident = capture.snapshot(postTailComplete = postTailComplete)
        incidentCapture = null
    }

    private fun incidentPostTailDeadlineOrMax(): Long = incidentCapture?.postTailEndsAtElapsedMs() ?: Long.MAX_VALUE

    private fun recordAt(
        atElapsedMs: Long,
        record: (CompassDeepTraceWindowAccumulator) -> Unit,
    ) {
        var completedLine: String? = null
        synchronized(lock) {
            if (!_state.value.active) return
            val window = currentWindow ?: CompassDeepTraceWindowAccumulator(atElapsedMs)
            if (atElapsedMs - window.startedAtElapsedMs >= WINDOW_DURATION_MS) {
                completedLine = flushWindowLocked(atElapsedMs)
                currentWindow = CompassDeepTraceWindowAccumulator(atElapsedMs)
            }
            record(currentWindow ?: return)
        }
        completedLine?.let { Log.d(TAG, it) }
    }

    private fun recordRawSensorSample(
        sensor: CompassDeepTraceRawSensor,
        values: FloatArray,
        atElapsedMs: Long,
    ) {
        if (values.size < 3) return
        if (sensor == CompassDeepTraceRawSensor.GYROSCOPE) {
            val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
            if (magnitude.isFinite()) {
                synchronized(lock) {
                    if (_state.value.active) {
                        gyroHistory.addLast(CompassDeepTraceGyroSample(atElapsedMs, magnitude))
                        while (
                            gyroHistory.firstOrNull()?.atElapsedMs ?: Long.MAX_VALUE <
                            atElapsedMs - GYRO_HISTORY_MS
                        ) {
                            gyroHistory.removeFirst()
                        }
                    }
                }
            }
        }
        recordAt(atElapsedMs) { window ->
            window.recordRawSensor(sensor, values[0], values[1], values[2])
        }
    }

    private fun flushWindowLocked(endedAtElapsedMs: Long): String? {
        val window = currentWindow
        return if (window == null || !window.hasSamples) {
            null
        } else {
            windowCount += 1
            val line =
                "atMs=${System.currentTimeMillis()} " +
                    window.toTelemetryLine(index = windowCount, endedAtElapsedMs = endedAtElapsedMs)
            appendLineLocked(line)
            line
        }
    }

    private fun appendLineLocked(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_BUFFERED_LINES) {
            lines.removeFirst()
            droppedLines += 1
        }
    }
}

internal fun isCompassTelemetryCaptureActive(): Boolean = DebugTelemetry.isEnabled() || CompassDeepTraceDiagnostics.state.value.active

internal data class CompassDeepTraceGyroMotion(
    val integratedRotationDeg: Float? = null,
    val peakDegPerSec: Float? = null,
)

private data class CompassDeepTraceGyroSample(
    val atElapsedMs: Long,
    val magnitudeRadPerSec: Float,
)

internal const val COMPASS_DEEP_TRACE_SCHEMA_VERSION = 4
internal const val COMPASS_DEEP_TRACE_DECISION_EVENT_CAPACITY = 2_048
private const val HEADING_LOOKS_WRONG_MARKER = "heading_looks_wrong"
private const val COMPASS_DEEP_TRACE_INCIDENT_POST_TAIL_MS = 2_000L
private const val COMPASS_DEEP_TRACE_INCIDENT_POST_EVENT_CAPACITY = 512
private const val GYRO_HISTORY_MS = 3_000L
