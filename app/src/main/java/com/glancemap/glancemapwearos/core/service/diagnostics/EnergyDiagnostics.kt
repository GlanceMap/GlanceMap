package com.glancemap.glancemapwearos.core.service.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal object EnergyDiagnostics {
    private const val TAG = "EnergyTelemetry"
    private const val MAX_LINES = 2_000
    private const val MAX_CURRENT_INTEGRATION_GAP_MS = 150_000L
    private const val UA_MS_PER_MAH = 3_600_000_000.0

    data class ModeStats(
        val sampleCount: Int,
        val currentSampleCount: Int,
        val avgCurrentNowUa: Long?,
        val medianAbsCurrentNowUa: Long?,
        val minCurrentNowUa: Int?,
        val maxCurrentNowUa: Int?,
        val minLevelPct: Int?,
        val maxLevelPct: Int?,
        val avgLevelPct: Double?,
        val minTempC: Double?,
        val maxTempC: Double?,
        val avgTempC: Double?,
    )

    data class Summary(
        val modes: Map<String, ModeStats>,
        val batteryUse: BatteryUseStats?,
        val screenStateEnergy: ScreenStateEnergy?,
        val gpsRuntime: GpsRuntimeSummary,
        val processCpu: ProcessCpuStats?,
        val runtimeAttribution: RuntimeAttributionSummary,
    )

    /** Process CPU time sampled alongside the existing battery samples. */
    data class ProcessCpuStats(
        val sampleCount: Int,
        val wallDurationMs: Long,
        val processCpuDurationMs: Long,
        val averageCoreUtilizationPct: Double?,
    )

    /** Durations from app-managed resources; this is not a platform-wide wakelock report. */
    data class RuntimeAttributionSummary(
        val partialWakeLocks: Map<String, DurationStats>,
        val recordingSensors: Map<String, DurationStats>,
    )

    data class DurationStats(
        val activationCount: Int,
        val observedDurationMs: Long,
        val activeCount: Int,
    )

    /**
     * Charge-counter energy attributed only to sample intervals whose display state is known
     * and unchanged. This deliberately leaves transition intervals unattributed rather than
     * guessing which state consumed their energy.
     */
    data class ScreenStateEnergy(
        val measurement: String,
        val totalMeasuredMah: Double,
        val screenOn: ScreenEnergyUse?,
        val screenOff: ScreenEnergyUse?,
        val attributedMah: Double,
        val unattributedMah: Double,
        val attributionCoveragePct: Double,
        val confidence: String,
    )

    data class ScreenEnergyUse(
        val consumedMah: Double,
        val durationMs: Long,
        val intervalCount: Int,
        val averageDrawMa: Double?,
    )

    /** Runtime GPS state observed at the fixed battery-capture cadence. */
    data class GpsRuntimeSummary(
        val screenOn: GpsRuntimeStats,
        val screenOff: GpsRuntimeStats,
    )

    data class GpsRuntimeStats(
        val sampleCount: Int,
        val requestActiveSampleCount: Int,
        val requestInactiveSampleCount: Int,
        val observedBackends: List<String>,
        val observedRequestIntervalsMs: List<Long>,
    )

    data class BatteryUseStats(
        val durationMs: Long,
        val consumedMah: Double,
        val averageDrawMa: Double,
        val integratedCurrentMah: Double?,
        val medianDrawMa: Double?,
        val p90DrawMa: Double?,
        val measurement: String,
        val confidence: String,
        val chargeCounterStartUah: Int?,
        val chargeCounterEndUah: Int?,
    )

    data class BatteryBenchmarkValidity(
        val valid: Boolean,
        val invalidReasons: List<String>,
    )

    private val lock = Any()

    private enum class CaptureMode {
        OFF,
        FULL,
        BATTERY_BENCHMARK,
    }

    private val captureMode = AtomicReference(CaptureMode.OFF)
    private val lines = ArrayDeque<String>()
    private val batteryBenchmarkInvalidReasons = linkedSetOf<String>()
    private val runtimeAttributionLock = Any()
    private val partialWakeLockDurations = linkedMapOf<String, MutableDurationStats>()
    private val recordingSensorDurations = linkedMapOf<String, MutableDurationStats>()
    private val activePartialWakeLocks = mutableMapOf<Int, ActivePartialWakeLock>()
    private val activeRecordingSensors = mutableMapOf<String, Long>()
    private var droppedLines: Int = 0

    fun clear() {
        synchronized(lock) {
            lines.clear()
            batteryBenchmarkInvalidReasons.clear()
            droppedLines = 0
        }
        synchronized(runtimeAttributionLock) {
            partialWakeLockDurations.clear()
            recordingSensorDurations.clear()
            activePartialWakeLocks.clear()
            activeRecordingSensors.clear()
        }
    }

    fun setEnabled(value: Boolean) {
        updateCaptureMode(if (value) CaptureMode.FULL else CaptureMode.OFF)
    }

    fun configure(
        captureActive: Boolean,
        fullDiagnostics: Boolean,
    ) {
        updateCaptureMode(
            when {
                !captureActive -> CaptureMode.OFF
                fullDiagnostics -> CaptureMode.FULL
                else -> CaptureMode.BATTERY_BENCHMARK
            },
        )
    }

    fun isEnabled(): Boolean = captureMode.get() != CaptureMode.OFF

    fun isBatteryBenchmarkActive(): Boolean = captureMode.get() == CaptureMode.BATTERY_BENCHMARK

    /** Records an app-owned partial wakelock without querying or polling the operating system. */
    fun recordPartialWakeLockAcquired(
        lockId: Int,
        tag: String,
        timeoutMs: Long,
    ) {
        if (!isEnabled()) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        synchronized(runtimeAttributionLock) {
            activePartialWakeLocks.remove(lockId)?.let { active ->
                closePartialWakeLock(active, nowElapsedMs)
            }
            partialWakeLockDurations.getOrPut(tag) { MutableDurationStats() }.activationCount += 1
            activePartialWakeLocks[lockId] =
                ActivePartialWakeLock(
                    tag = tag,
                    startedAtElapsedMs = nowElapsedMs,
                    expiresAtElapsedMs = saturatingAdd(nowElapsedMs, timeoutMs),
                )
        }
    }

    fun recordPartialWakeLockReleased(lockId: Int) {
        if (!isEnabled()) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        synchronized(runtimeAttributionLock) {
            activePartialWakeLocks.remove(lockId)?.let { active ->
                closePartialWakeLock(active, nowElapsedMs)
            }
        }
    }

    /** Records recording-sensor lifetimes from the existing register/unregister calls. */
    fun recordRecordingSensorsRegistered(sensorTokens: Collection<String>) {
        if (!isEnabled()) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        synchronized(runtimeAttributionLock) {
            sensorTokens.distinct().forEach { sensorToken ->
                if (activeRecordingSensors.putIfAbsent(sensorToken, nowElapsedMs) == null) {
                    recordingSensorDurations.getOrPut(sensorToken) { MutableDurationStats() }.activationCount += 1
                }
            }
        }
    }

    fun recordRecordingSensorsUnregistered(sensorTokens: Collection<String>) {
        if (!isEnabled()) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        synchronized(runtimeAttributionLock) {
            sensorTokens.distinct().forEach { sensorToken ->
                activeRecordingSensors.remove(sensorToken)?.let { startedAtElapsedMs ->
                    closeRecordingSensor(
                        sensorToken = sensorToken,
                        startedAtElapsedMs = startedAtElapsedMs,
                        nowElapsedMs = nowElapsedMs,
                    )
                }
            }
        }
    }

    fun markBatteryBenchmarkInvalid(reason: String) {
        if (reason.isBlank()) return
        synchronized(lock) {
            batteryBenchmarkInvalidReasons += reason
        }
    }

    fun batteryBenchmarkValidity(): BatteryBenchmarkValidity =
        synchronized(lock) {
            BatteryBenchmarkValidity(
                valid = batteryBenchmarkInvalidReasons.isEmpty(),
                invalidReasons = batteryBenchmarkInvalidReasons.toList(),
            )
        }

    internal fun shouldRecordSample(reason: String): Boolean =
        when (captureMode.get()) {
            CaptureMode.OFF -> false
            CaptureMode.FULL -> true
            CaptureMode.BATTERY_BENCHMARK ->
                reason == "periodic" || reason == "capture_toggle_on" || reason == "capture_toggle_off"
        }

    fun snapshotLines(): List<String> = synchronized(lock) { lines.toList() }

    fun droppedLineCount(): Int = synchronized(lock) { droppedLines }

    fun maxBufferedLines(): Int = MAX_LINES

    fun summary(): Summary = summarizeLines(snapshotLines()).copy(runtimeAttribution = runtimeAttributionSummary())

    internal fun summarizeLines(snapshot: List<String>): Summary {
        if (snapshot.isEmpty()) {
            return Summary(
                modes = emptyMap(),
                batteryUse = null,
                screenStateEnergy = null,
                gpsRuntime = GpsRuntimeSummary(emptyGpsRuntimeStats(), emptyGpsRuntimeStats()),
                processCpu = null,
                runtimeAttribution = emptyRuntimeAttributionSummary(),
            )
        }
        val accumulators = linkedMapOf<String, ModeAccumulator>()
        snapshot.forEach { line ->
            if (" level=" !in line && " curNowUa=" !in line && " tempC=" !in line) return@forEach
            val mode = classifyMode(line)
            val accumulator = accumulators.getOrPut(mode) { ModeAccumulator() }
            accumulator.add(line)
        }
        val observations = batteryObservations(snapshot)
        val batteryUse = buildBatteryUse(observations)
        return Summary(
            modes =
                accumulators
                    .mapValues { (_, accumulator) -> accumulator.toStats() }
                    .filterValues { stats -> stats.sampleCount > 0 },
            batteryUse = batteryUse,
            screenStateEnergy = summarizeScreenStateEnergy(observations, batteryUse),
            gpsRuntime = summarizeGpsRuntime(snapshot),
            processCpu = summarizeProcessCpu(snapshot),
            runtimeAttribution = emptyRuntimeAttributionSummary(),
        )
    }

    fun recordSample(
        context: Context,
        reason: String,
        detail: String = "",
    ) {
        if (!shouldRecordSample(reason)) return

        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryIntent =
            runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()

        val levelPct = batteryPercent(batteryIntent)
        val batteryStatus = batteryStatus(batteryIntent)
        val plugged = batteryPlugged(batteryIntent)
        val temperatureC = batteryTemperatureC(batteryIntent)
        val voltageMv = batteryVoltageMv(batteryIntent)

        val currentNowUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAvgUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val capacityPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val chargeCounterUah = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val processCpuMs = Process.getElapsedCpuTime()

        val powerSave = powerManager?.isPowerSaveMode ?: false
        val interactive = powerManager?.isInteractive ?: false
        val thermal =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                powerManager.currentThermalStatus.toString()
            } else {
                "na"
            }

        val line =
            buildString {
                append("atMs=").append(System.currentTimeMillis())
                append(" reason=").append(reason)
                if (detail.isNotBlank()) {
                    append(" ").append(detail)
                }
                append(" level=").append(levelPct)
                append(" status=").append(batteryStatus)
                append(" plugged=").append(plugged)
                append(" tempC=").append(temperatureC)
                append(" voltMv=").append(voltageMv)
                append(" curNowUa=").append(propertyOrNa(currentNowUa))
                append(" curAvgUa=").append(propertyOrNa(currentAvgUa))
                append(" capPropPct=").append(propertyOrNa(capacityPct))
                append(" chargeCounterUah=").append(propertyOrNa(chargeCounterUah))
                append(" procCpuMs=").append(processCpuMs)
                append(" saver=").append(powerSave)
                append(" interactive=").append(interactive)
                append(" thermal=").append(thermal)
            }

        push(line)
        DebugTelemetry.log(TAG, line)
    }

    fun recordEvent(
        reason: String,
        detail: String = "",
    ) {
        // Context-only events are useful for full troubleshooting, but deliberately omitted
        // from battery benchmark mode to keep its measurement overhead minimal.
        if (captureMode.get() != CaptureMode.FULL || !DebugTelemetry.isEnabled()) return
        val line =
            if (detail.isBlank()) {
                "atMs=${System.currentTimeMillis()} reason=$reason"
            } else {
                "atMs=${System.currentTimeMillis()} reason=$reason $detail"
            }
        push(line)
        DebugTelemetry.log(TAG, line)
    }

    private fun push(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                droppedLines += 1
            }
        }
    }

    private fun updateCaptureMode(nextMode: CaptureMode) {
        val previousMode = captureMode.getAndSet(nextMode)
        if (
            previousMode != CaptureMode.OFF &&
            nextMode == CaptureMode.OFF &&
            hasActiveRuntimeAttribution()
        ) {
            finalizeRuntimeAttribution(SystemClock.elapsedRealtime())
        }
    }

    private fun hasActiveRuntimeAttribution(): Boolean =
        synchronized(runtimeAttributionLock) {
            activePartialWakeLocks.isNotEmpty() || activeRecordingSensors.isNotEmpty()
        }

    private fun runtimeAttributionSummary(): RuntimeAttributionSummary {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        synchronized(runtimeAttributionLock) {
            return RuntimeAttributionSummary(
                partialWakeLocks =
                    durationStatsSnapshot(
                        completed = partialWakeLockDurations,
                        activeCounts = activePartialWakeLocks.values.groupingBy { it.tag }.eachCount(),
                        activeDurations =
                            activePartialWakeLocks.values.groupBy { it.tag }.mapValues { (_, activeLocks) ->
                                activeLocks.sumOf { active -> active.durationAt(nowElapsedMs) }
                            },
                    ),
                recordingSensors =
                    durationStatsSnapshot(
                        completed = recordingSensorDurations,
                        activeCounts = activeRecordingSensors.keys.groupingBy { it }.eachCount(),
                        activeDurations =
                            activeRecordingSensors.mapValues { (_, startedAtElapsedMs) ->
                                (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
                            },
                    ),
            )
        }
    }

    private fun finalizeRuntimeAttribution(nowElapsedMs: Long) {
        synchronized(runtimeAttributionLock) {
            activePartialWakeLocks.values.forEach { active -> closePartialWakeLock(active, nowElapsedMs) }
            activePartialWakeLocks.clear()
            activeRecordingSensors.forEach { (sensorToken, startedAtElapsedMs) ->
                closeRecordingSensor(sensorToken, startedAtElapsedMs, nowElapsedMs)
            }
            activeRecordingSensors.clear()
        }
    }

    private fun closePartialWakeLock(
        active: ActivePartialWakeLock,
        nowElapsedMs: Long,
    ) {
        partialWakeLockDurations.getOrPut(active.tag) { MutableDurationStats() }.observedDurationMs +=
            active.durationAt(nowElapsedMs)
    }

    private fun closeRecordingSensor(
        sensorToken: String,
        startedAtElapsedMs: Long,
        nowElapsedMs: Long,
    ) {
        recordingSensorDurations.getOrPut(sensorToken) { MutableDurationStats() }.observedDurationMs +=
            (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
    }

    private fun durationStatsSnapshot(
        completed: Map<String, MutableDurationStats>,
        activeCounts: Map<String, Int>,
        activeDurations: Map<String, Long>,
    ): Map<String, DurationStats> =
        (completed.keys + activeCounts.keys)
            .distinct()
            .sorted()
            .associateWith { token ->
                val completedStats = completed[token]
                DurationStats(
                    activationCount = completedStats?.activationCount ?: 0,
                    observedDurationMs =
                        (completedStats?.observedDurationMs ?: 0L) + (activeDurations[token] ?: 0L),
                    activeCount = activeCounts[token] ?: 0,
                )
            }

    private fun emptyRuntimeAttributionSummary(): RuntimeAttributionSummary =
        RuntimeAttributionSummary(
            partialWakeLocks = emptyMap(),
            recordingSensors = emptyMap(),
        )

    private fun propertyOrNa(value: Int?): String {
        if (value == null || value == Int.MIN_VALUE) return "na"
        return value.toString()
    }

    private fun batteryPercent(intent: Intent?): String {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "na"
        val pct = (level * 100f / scale.toFloat())
        return TelemetryFormatters.decimal(pct, 0)
    }

    private fun batteryTemperatureC(intent: Intent?): String {
        val tempTenths =
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?: Int.MIN_VALUE
        if (tempTenths == Int.MIN_VALUE) return "na"
        return TelemetryFormatters.decimal(tempTenths / 10f, 1)
    }

    private fun batteryVoltageMv(intent: Intent?): String {
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (voltage == Int.MIN_VALUE) return "na"
        return voltage.toString()
    }

    private fun batteryStatus(intent: Intent?): String =
        when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

    private fun batteryPlugged(intent: Intent?): String =
        when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }

    private fun classifyMode(line: String): String =
        when {
            "screenState=SCREEN_OFF" in line || "interactive=false" in line -> "screen_off"
            "mode=BURST" in line || "burst=true" in line -> "burst"
            "mode=INTERACTIVE" in line || "screenState=INTERACTIVE" in line || "tracking=true" in line -> {
                "interactive"
            }
            "tracking=false" in line -> "idle"
            "transfer" in line.lowercase() || "channel" in line.lowercase() || "http" in line.lowercase() -> "transfer"
            else -> "other"
        }

    private data class BatteryObservation(
        val atMs: Long,
        val dischargeCurrentUa: Long?,
        val chargeCounterUah: Int?,
        val screenState: ScreenState,
    )

    private data class ProcessCpuObservation(
        val atMs: Long,
        val processCpuMs: Long,
    )

    private data class ActivePartialWakeLock(
        val tag: String,
        val startedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
    ) {
        fun durationAt(nowElapsedMs: Long): Long = (minOf(nowElapsedMs, expiresAtElapsedMs) - startedAtElapsedMs).coerceAtLeast(0L)
    }

    private class MutableDurationStats(
        var activationCount: Int = 0,
        var observedDurationMs: Long = 0L,
    )

    private enum class ScreenState {
        SCREEN_ON,
        SCREEN_OFF,
        UNKNOWN,
    }

    private data class IntegratedCurrentUse(
        val consumedMah: Double,
        val durationMs: Long,
    )

    private data class ChargeCounterUse(
        val consumedMah: Double,
        val durationMs: Long,
    )

    private fun batteryObservations(lines: List<String>): List<BatteryObservation> =
        lines
            .mapNotNull(::batteryObservationOrNull)
            .sortedBy { it.atMs }

    private fun buildBatteryUse(observations: List<BatteryObservation>): BatteryUseStats? {
        val integratedUse = integrateCurrentUse(observations)
        val chargeObservations = observations.filter { it.chargeCounterUah != null }
        val startCharge = chargeObservations.firstOrNull()
        val endCharge = chargeObservations.lastOrNull()
        val chargeCounterUse = resolveChargeCounterUse(startCharge, endCharge)
        val consumedMah = chargeCounterUse?.consumedMah ?: integratedUse?.consumedMah
        val durationMs = chargeCounterUse?.durationMs ?: integratedUse?.durationMs
        return if (consumedMah != null && durationMs != null) {
            val currentSamples = observations.mapNotNull { it.dischargeCurrentUa }.sorted()
            BatteryUseStats(
                durationMs = durationMs,
                consumedMah = consumedMah,
                averageDrawMa = consumedMah * 3_600_000.0 / durationMs,
                integratedCurrentMah = integratedUse?.consumedMah,
                medianDrawMa = percentile(currentSamples, 0.5)?.div(1_000.0),
                p90DrawMa = percentile(currentSamples, 0.9)?.div(1_000.0),
                measurement = if (chargeCounterUse != null) "charge_counter" else "integrated_current",
                confidence = if (chargeCounterUse != null) "high" else "medium",
                chargeCounterStartUah = startCharge?.chargeCounterUah,
                chargeCounterEndUah = endCharge?.chargeCounterUah,
            )
        } else {
            null
        }
    }

    private fun batteryObservationOrNull(line: String): BatteryObservation? {
        val atMs = tokenValue(line, "atMs=")?.toLongOrNull()
        val discharging = tokenValue(line, "status=") == "discharging"
        val unplugged = tokenValue(line, "plugged=") == "battery"
        return if (atMs != null && discharging && unplugged) {
            BatteryObservation(
                atMs = atMs,
                dischargeCurrentUa =
                    tokenValue(line, "curNowUa=")
                        ?.toLongOrNull()
                        ?.takeIf { it != Int.MIN_VALUE.toLong() }
                        ?.let(::abs),
                chargeCounterUah =
                    tokenValue(line, "chargeCounterUah=")
                        ?.toIntOrNull()
                        ?.takeIf { it != Int.MIN_VALUE },
                screenState = screenStateFor(line),
            )
        } else {
            null
        }
    }

    private fun summarizeScreenStateEnergy(
        observations: List<BatteryObservation>,
        batteryUse: BatteryUseStats?,
    ): ScreenStateEnergy? {
        if (batteryUse?.measurement != "charge_counter" || observations.size < 2) return null
        val screenOn = ScreenEnergyAccumulator()
        val screenOff = ScreenEnergyAccumulator()

        observations.zipWithNext().forEach { (previous, current) ->
            val durationMs = current.atMs - previous.atMs
            if (durationMs <= 0L || durationMs > MAX_CURRENT_INTEGRATION_GAP_MS) return@forEach
            val state = previous.screenState.takeIf { it == current.screenState } ?: ScreenState.UNKNOWN
            when (state) {
                ScreenState.SCREEN_ON -> screenOn.add(previous, current, durationMs)
                ScreenState.SCREEN_OFF -> screenOff.add(previous, current, durationMs)
                ScreenState.UNKNOWN -> Unit
            }
        }

        val screenOnUse = screenOn.toUseOrNull()
        val screenOffUse = screenOff.toUseOrNull()
        val attributedMah = (screenOnUse?.consumedMah ?: 0.0) + (screenOffUse?.consumedMah ?: 0.0)
        val totalMeasuredMah = batteryUse.consumedMah
        val coveragePct =
            if (totalMeasuredMah > 0.0) {
                (attributedMah * 100.0 / totalMeasuredMah).coerceIn(0.0, 100.0)
            } else {
                0.0
            }
        return ScreenStateEnergy(
            measurement = "charge_counter_intervals",
            totalMeasuredMah = totalMeasuredMah,
            screenOn = screenOnUse,
            screenOff = screenOffUse,
            attributedMah = attributedMah.coerceAtMost(totalMeasuredMah),
            unattributedMah = (totalMeasuredMah - attributedMah).coerceAtLeast(0.0),
            attributionCoveragePct = coveragePct,
            confidence = attributionConfidence(coveragePct),
        )
    }

    private fun summarizeGpsRuntime(lines: List<String>): GpsRuntimeSummary {
        val screenOn = GpsRuntimeAccumulator()
        val screenOff = GpsRuntimeAccumulator()
        lines.forEach { line ->
            if (lastTokenValue(line, "gpsRequestActive=") == null) return@forEach
            when (screenStateFor(line)) {
                ScreenState.SCREEN_ON -> screenOn.add(line)
                ScreenState.SCREEN_OFF -> screenOff.add(line)
                ScreenState.UNKNOWN -> Unit
            }
        }
        return GpsRuntimeSummary(
            screenOn = screenOn.toStats(),
            screenOff = screenOff.toStats(),
        )
    }

    private fun summarizeProcessCpu(lines: List<String>): ProcessCpuStats? {
        val observations =
            lines
                .mapNotNull { line ->
                    val atMs = tokenValue(line, "atMs=")?.toLongOrNull() ?: return@mapNotNull null
                    val processCpuMs = tokenValue(line, "procCpuMs=")?.toLongOrNull() ?: return@mapNotNull null
                    ProcessCpuObservation(atMs = atMs, processCpuMs = processCpuMs)
                }.sortedBy { it.atMs }
        if (observations.size < 2) return null

        var wallDurationMs = 0L
        var processCpuDurationMs = 0L
        observations.zipWithNext().forEach { (previous, current) ->
            val wallDeltaMs = current.atMs - previous.atMs
            val cpuDeltaMs = current.processCpuMs - previous.processCpuMs
            if (wallDeltaMs <= 0L || wallDeltaMs > MAX_CURRENT_INTEGRATION_GAP_MS || cpuDeltaMs < 0L) {
                return@forEach
            }
            wallDurationMs += wallDeltaMs
            processCpuDurationMs += cpuDeltaMs
        }
        if (wallDurationMs <= 0L) return null
        return ProcessCpuStats(
            sampleCount = observations.size,
            wallDurationMs = wallDurationMs,
            processCpuDurationMs = processCpuDurationMs,
            averageCoreUtilizationPct = processCpuDurationMs * 100.0 / wallDurationMs,
        )
    }

    private fun resolveChargeCounterUse(
        start: BatteryObservation?,
        end: BatteryObservation?,
    ): ChargeCounterUse? {
        val startUah = start?.chargeCounterUah
        val endUah = end?.chargeCounterUah
        val durationMs = if (start != null && end != null) end.atMs - start.atMs else 0L
        val countersAvailable = startUah != null && endUah != null
        val counterDecreased = countersAvailable && checkNotNull(startUah) > checkNotNull(endUah)
        if (!counterDecreased || durationMs <= 0L) return null
        return ChargeCounterUse(
            consumedMah = (checkNotNull(startUah) - checkNotNull(endUah)) / 1_000.0,
            durationMs = durationMs,
        )
    }

    private fun integrateCurrentUse(observations: List<BatteryObservation>): IntegratedCurrentUse? {
        var integratedUaMs = 0.0
        var integratedDurationMs = 0L
        observations.zipWithNext().forEach { (previous, current) ->
            val previousUa = previous.dischargeCurrentUa ?: return@forEach
            val currentUa = current.dischargeCurrentUa ?: return@forEach
            val durationMs = current.atMs - previous.atMs
            if (durationMs <= 0L || durationMs > MAX_CURRENT_INTEGRATION_GAP_MS) return@forEach
            integratedUaMs += ((previousUa + currentUa) / 2.0) * durationMs
            integratedDurationMs += durationMs
        }
        return if (integratedDurationMs > 0L) {
            IntegratedCurrentUse(
                consumedMah = integratedUaMs / UA_MS_PER_MAH,
                durationMs = integratedDurationMs,
            )
        } else {
            null
        }
    }

    private fun percentile(
        sortedValues: List<Long>,
        quantile: Double,
    ): Long? {
        if (sortedValues.isEmpty()) return null
        val index = ((sortedValues.lastIndex) * quantile).toInt().coerceIn(sortedValues.indices)
        return sortedValues[index]
    }

    private fun saturatingAdd(
        value: Long,
        increment: Long,
    ): Long =
        if (increment <= 0L || value > Long.MAX_VALUE - increment) {
            Long.MAX_VALUE
        } else {
            value + increment
        }

    @Suppress("ReturnCount")
    private fun tokenValue(
        line: String,
        key: String,
    ): String? {
        val index = line.indexOf(key)
        if (index < 0) return null
        val start = index + key.length
        if (start >= line.length) return null
        val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
        return line.substring(start, end).trim()
    }

    private fun lastTokenValue(
        line: String,
        key: String,
    ): String? {
        val index = line.lastIndexOf(key)
        val start = index + key.length
        return start
            .takeIf { index >= 0 && it < line.length }
            ?.let { validStart ->
                val end = line.indexOf(' ', validStart).let { if (it < 0) line.length else it }
                line.substring(validStart, end).trim()
            }
    }

    private fun screenStateFor(line: String): ScreenState {
        val explicitState = lastTokenValue(line, "screenState=")
        return when (explicitState) {
            "INTERACTIVE" -> ScreenState.SCREEN_ON
            "AMBIENT", "SCREEN_OFF", "OFF" -> ScreenState.SCREEN_OFF
            else ->
                when (lastTokenValue(line, "interactive=")) {
                    "true" -> ScreenState.SCREEN_ON
                    "false" -> ScreenState.SCREEN_OFF
                    else -> ScreenState.UNKNOWN
                }
        }
    }

    private fun attributionConfidence(coveragePct: Double): String =
        when {
            coveragePct >= 95.0 -> "high"
            coveragePct >= 75.0 -> "medium"
            else -> "low"
        }

    private class ScreenEnergyAccumulator {
        private var consumedMah = 0.0
        private var durationMs = 0L
        private var intervalCount = 0

        fun add(
            previous: BatteryObservation,
            current: BatteryObservation,
            intervalDurationMs: Long,
        ) {
            durationMs += intervalDurationMs
            intervalCount += 1
            val previousCounter = previous.chargeCounterUah ?: return
            val currentCounter = current.chargeCounterUah ?: return
            consumedMah += (previousCounter - currentCounter).coerceAtLeast(0) / 1_000.0
        }

        fun toUseOrNull(): ScreenEnergyUse? =
            if (intervalCount == 0) {
                null
            } else {
                ScreenEnergyUse(
                    consumedMah = consumedMah,
                    durationMs = durationMs,
                    intervalCount = intervalCount,
                    averageDrawMa =
                        if (durationMs > 0L) consumedMah * 3_600_000.0 / durationMs else null,
                )
            }
    }

    private class GpsRuntimeAccumulator {
        private var sampleCount = 0
        private var requestActiveSampleCount = 0
        private val backends = linkedSetOf<String>()
        private val requestIntervalsMs = linkedSetOf<Long>()

        fun add(line: String) {
            sampleCount += 1
            if (lastTokenValue(line, "gpsRequestActive=") == "true") {
                requestActiveSampleCount += 1
            }
            lastTokenValue(line, "gpsBackend=")
                ?.takeUnless { it == "na" || it == "none" }
                ?.let(backends::add)
            lastTokenValue(line, "gpsRequestIntervalMs=")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(requestIntervalsMs::add)
        }

        fun toStats(): GpsRuntimeStats =
            GpsRuntimeStats(
                sampleCount = sampleCount,
                requestActiveSampleCount = requestActiveSampleCount,
                requestInactiveSampleCount = sampleCount - requestActiveSampleCount,
                observedBackends = backends.toList(),
                observedRequestIntervalsMs = requestIntervalsMs.toList(),
            )
    }

    private fun emptyGpsRuntimeStats(): GpsRuntimeStats =
        GpsRuntimeStats(
            sampleCount = 0,
            requestActiveSampleCount = 0,
            requestInactiveSampleCount = 0,
            observedBackends = emptyList(),
            observedRequestIntervalsMs = emptyList(),
        )

    private class ModeAccumulator {
        private var sampleCount = 0
        private var currentSampleCount = 0
        private var currentTotalUa = 0L
        private val absoluteCurrentSamplesUa = mutableListOf<Long>()
        private var minCurrentUa: Int? = null
        private var maxCurrentUa: Int? = null
        private var levelSampleCount = 0
        private var levelTotal = 0
        private var minLevel: Int? = null
        private var maxLevel: Int? = null
        private var tempSampleCount = 0
        private var tempTotal = 0.0
        private var minTemp: Double? = null
        private var maxTemp: Double? = null

        @Suppress("CyclomaticComplexMethod")
        fun add(line: String) {
            sampleCount += 1
            tokenValue(line, "curNowUa=")
                ?.toIntOrNull()
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { current ->
                    currentSampleCount += 1
                    currentTotalUa += current.toLong()
                    absoluteCurrentSamplesUa += abs(current.toLong())
                    minCurrentUa = minCurrentUa?.let { minOf(it, current) } ?: current
                    maxCurrentUa = maxCurrentUa?.let { maxOf(it, current) } ?: current
                }
            tokenValue(line, "level=")
                ?.toIntOrNull()
                ?.let { level ->
                    levelSampleCount += 1
                    levelTotal += level
                    minLevel = minLevel?.let { minOf(it, level) } ?: level
                    maxLevel = maxLevel?.let { maxOf(it, level) } ?: level
                }
            tokenValue(line, "tempC=")
                ?.toDoubleOrNull()
                ?.let { temp ->
                    tempSampleCount += 1
                    tempTotal += temp
                    minTemp = minTemp?.let { minOf(it, temp) } ?: temp
                    maxTemp = maxTemp?.let { maxOf(it, temp) } ?: temp
                }
        }

        fun toStats(): ModeStats =
            ModeStats(
                sampleCount = sampleCount,
                currentSampleCount = currentSampleCount,
                avgCurrentNowUa =
                    if (currentSampleCount > 0) {
                        currentTotalUa / currentSampleCount
                    } else {
                        null
                    },
                medianAbsCurrentNowUa = median(absoluteCurrentSamplesUa),
                minCurrentNowUa = minCurrentUa,
                maxCurrentNowUa = maxCurrentUa,
                minLevelPct = minLevel,
                maxLevelPct = maxLevel,
                avgLevelPct =
                    if (levelSampleCount > 0) {
                        levelTotal.toDouble() / levelSampleCount.toDouble()
                    } else {
                        null
                    },
                minTempC = minTemp,
                maxTempC = maxTemp,
                avgTempC =
                    if (tempSampleCount > 0) {
                        tempTotal / tempSampleCount.toDouble()
                    } else {
                        null
                    },
            )

        private fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2L
            } else {
                sorted[middle]
            }
        }
    }
}
