package com.glancemap.glancemapwearos.core.service.location.service

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.GnssDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("LongParameterList", "TooManyFunctions")
internal class GnssDiagnosticsCoordinator(
    private val serviceScope: CoroutineScope,
    private val mainHandler: Handler,
    private val locationManagerProvider: () -> LocationManager?,
    private val hasFinePermission: () -> Boolean,
    private val hasCoarsePermission: () -> Boolean,
    private val trackingEnabled: () -> Boolean,
    private val bound: () -> Boolean,
    private val keepOpen: () -> Boolean,
    private val watchOnly: () -> Boolean,
    private val sourceMode: () -> String,
    private val watchGpsReason: () -> String,
    private val ambientModeActive: () -> Boolean,
    private val debugTelemetryEnabled: () -> Boolean,
    private val gpsSignalSnapshot: () -> GpsSignalSnapshot,
) {
    @Volatile
    private var collectorRegisteredAtElapsedMs: Long = 0L

    @Volatile
    private var statusSampleCount: Int = 0

    @Volatile
    private var lastStatusAtElapsedMs: Long = 0L

    @Volatile
    private var firstSignalAtElapsedMs: Long = 0L

    @Volatile
    private var firstEphemerisAtElapsedMs: Long = 0L

    @Volatile
    private var firstUsedInFixAtElapsedMs: Long = 0L

    @Volatile
    private var firstFixTtffMs: Int? = null

    @Volatile
    private var latestAcquisitionState: GnssAcquisitionState = GnssAcquisitionState.NO_RADIO_SIGNAL

    private var statusWatchdogJob: Job? = null
    private var statusCallback: GnssStatus.Callback? = null
    private var lastPolicyDisabledSignature: String? = null
    private var lastUsedZeroWithFreshLocationAtElapsedMs: Long = 0L
    private var lastSignalsWithoutFreshLocationAtElapsedMs: Long = 0L

    @SuppressLint("MissingPermission")
    @Suppress("LongMethod", "ReturnCount")
    @Synchronized
    fun update(enabled: Boolean) {
        if (!enabled) {
            lastPolicyDisabledSignature = null
            unregister(reason = "debug_disabled")
            return
        }

        if (shouldDisableCollectorBySourcePolicy()) {
            unregister(reason = "source_policy_auto_fused")
            val signature =
                "sourceMode=${sourceMode()} watchOnly=${watchOnly()} tracking=${trackingEnabled()} " +
                    "bound=${bound()} keepOpen=${keepOpen()} ambient=${ambientModeActive()}"
            if (signature != lastPolicyDisabledSignature) {
                lastPolicyDisabledSignature = signature
                GnssDiagnostics.recordEvent(
                    "collector_policy_disabled",
                    "reason=auto_fused_priority $signature",
                )
            }
            return
        }
        lastPolicyDisabledSignature = null

        if (!hasFinePermission()) {
            unregister(reason = "no_fine_permission")
            val manager = locationManagerProvider()
            GnssDiagnostics.recordEvent(
                "collector_inactive",
                "reason=no_fine_permission coarse=${hasCoarsePermission()} tracking=${trackingEnabled()} " +
                    "sourceMode=${sourceMode()} gpsProviderPresent=${gpsProviderPresent(manager)} " +
                    "gpsProviderEnabled=${gpsProviderEnabled(manager)}",
            )
            return
        }

        if (statusCallback != null) {
            scheduleStatusWatchdogIfNeeded()
            return
        }

        val manager = locationManagerProvider()
        if (manager == null) {
            GnssDiagnostics.recordEvent(
                "collector_inactive",
                "reason=no_location_manager tracking=${trackingEnabled()} sourceMode=${sourceMode()}",
            )
            return
        }

        collectorRegisteredAtElapsedMs = SystemClock.elapsedRealtime()
        statusSampleCount = 0
        lastStatusAtElapsedMs = 0L

        val callback =
            object : GnssStatus.Callback() {
                override fun onStarted() {
                    GnssDiagnostics.recordEvent("started")
                }

                override fun onStopped() {
                    GnssDiagnostics.recordEvent("stopped")
                }

                override fun onFirstFix(ttffMillis: Int) {
                    firstFixTtffMs = ttffMillis.coerceAtLeast(0)
                    GnssDiagnostics.recordEvent("first_fix", "ttffMs=${ttffMillis.coerceAtLeast(0)}")
                }

                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    statusSampleCount += 1
                    lastStatusAtElapsedMs = nowElapsedMs

                    if (statusSampleCount == 1) {
                        val sinceRegisterMs =
                            if (collectorRegisteredAtElapsedMs > 0L) {
                                (nowElapsedMs - collectorRegisteredAtElapsedMs).coerceAtLeast(0L)
                            } else {
                                -1L
                            }
                        GnssDiagnostics.recordEvent(
                            "status_stream_started",
                            "afterRegisterMs=${formatAgeMsForTelemetry(sinceRegisterMs)}",
                        )
                    }

                    val satellites = status.satelliteCount
                    var usedInFix = 0
                    var signalSatelliteCount = 0
                    var almanacSatelliteCount = 0
                    var ephemerisSatelliteCount = 0
                    var cn0Count = 0
                    var cn0Sum = 0f
                    var cn0Max = Float.NEGATIVE_INFINITY
                    var carrierFrequencySatelliteCount = 0
                    var l1SatelliteCount = 0
                    var l5SatelliteCount = 0
                    var gpsCount = 0
                    var galileoCount = 0
                    var glonassCount = 0
                    var beidouCount = 0
                    var qzssCount = 0
                    var sbasCount = 0
                    var unknownCount = 0

                    for (index in 0 until satellites) {
                        if (status.usedInFix(index)) {
                            usedInFix += 1
                        }
                        if (status.hasAlmanacData(index)) {
                            almanacSatelliteCount += 1
                        }
                        if (status.hasEphemerisData(index)) {
                            ephemerisSatelliteCount += 1
                        }
                        when (status.getConstellationType(index)) {
                            GnssStatus.CONSTELLATION_GPS -> gpsCount += 1
                            GnssStatus.CONSTELLATION_GALILEO -> galileoCount += 1
                            GnssStatus.CONSTELLATION_GLONASS -> glonassCount += 1
                            GnssStatus.CONSTELLATION_BEIDOU -> beidouCount += 1
                            GnssStatus.CONSTELLATION_QZSS -> qzssCount += 1
                            GnssStatus.CONSTELLATION_SBAS -> sbasCount += 1
                            else -> unknownCount += 1
                        }
                        val cn0 = status.getCn0DbHz(index)
                        if (cn0.isFinite() && cn0 > 0f) {
                            cn0Count += 1
                            signalSatelliteCount += 1
                            cn0Sum += cn0
                            if (cn0 > cn0Max) {
                                cn0Max = cn0
                            }
                        }
                        if (status.hasCarrierFrequencyHz(index)) {
                            carrierFrequencySatelliteCount += 1
                            val carrierFrequencyHz = status.getCarrierFrequencyHz(index).toDouble()
                            if (carrierFrequencyHz in GNSS_L1_MIN_HZ..GNSS_L1_MAX_HZ) {
                                l1SatelliteCount += 1
                            }
                            if (carrierFrequencyHz in GNSS_L5_MIN_HZ..GNSS_L5_MAX_HZ) {
                                l5SatelliteCount += 1
                            }
                        }
                    }

                    latestAcquisitionState =
                        resolveGnssAcquisitionState(
                            signalSatelliteCount = signalSatelliteCount,
                            ephemerisSatelliteCount = ephemerisSatelliteCount,
                            usedInFixCount = usedInFix,
                        )
                    recordFirstAcquisitionMilestones(
                        nowElapsedMs = nowElapsedMs,
                        signalSatelliteCount = signalSatelliteCount,
                        ephemerisSatelliteCount = ephemerisSatelliteCount,
                        usedInFixCount = usedInFix,
                    )
                    recordLocationConsistency(
                        nowElapsedMs = nowElapsedMs,
                        sourceMode = sourceMode(),
                        usedInFixCount = usedInFix,
                        signalSatelliteCount = signalSatelliteCount,
                        signalSnapshot = gpsSignalSnapshot(),
                    )

                    val avgCn0 = if (cn0Count > 0) cn0Sum / cn0Count else null
                    val maxCn0 = if (cn0Count > 0 && cn0Max.isFinite()) cn0Max else null
                    GnssDiagnostics.recordStatus(
                        satellites = satellites,
                        usedInFix = usedInFix,
                        signalSatelliteCount = signalSatelliteCount,
                        almanacSatelliteCount = almanacSatelliteCount,
                        ephemerisSatelliteCount = ephemerisSatelliteCount,
                        acquisitionState = latestAcquisitionState.telemetryValue,
                        cn0AvgDbHz = avgCn0,
                        cn0MaxDbHz = maxCn0,
                        carrierFrequencySatelliteCount = carrierFrequencySatelliteCount,
                        l1SatelliteCount = l1SatelliteCount,
                        l5SatelliteCount = l5SatelliteCount,
                        dualBandObserved = l1SatelliteCount > 0 && l5SatelliteCount > 0,
                        gpsCount = gpsCount,
                        galileoCount = galileoCount,
                        glonassCount = glonassCount,
                        beidouCount = beidouCount,
                        qzssCount = qzssCount,
                        sbasCount = sbasCount,
                        unknownCount = unknownCount,
                    )
                }
            }

        val registered =
            runCatching {
                manager.registerGnssStatusCallback(callback, mainHandler)
            }.getOrDefault(false)

        if (!registered) {
            resetRuntimeState()
            GnssDiagnostics.recordEvent(
                "collector_register_failed",
                "tracking=${trackingEnabled()} keepOpen=${keepOpen()} sourceMode=${sourceMode()} " +
                    "gpsProviderPresent=${gpsProviderPresent(manager)} gpsProviderEnabled=${gpsProviderEnabled(manager)}",
            )
            return
        }

        statusCallback = callback
        GnssDiagnostics.recordEvent(
            "collector_registered",
            "tracking=${trackingEnabled()} bound=${bound()} keepOpen=${keepOpen()} " +
                "watchOnlyRequested=${watchOnly()} sourceMode=${sourceMode()} " +
                "watchGpsReason=${watchGpsReason()} ambient=${ambientModeActive()} " +
                "gpsProviderPresent=${gpsProviderPresent(manager)} gpsProviderEnabled=${gpsProviderEnabled(manager)}",
        )
        scheduleStatusWatchdogIfNeeded()
    }

    private fun shouldDisableCollectorBySourcePolicy(): Boolean = !watchOnly() && sourceMode() == "auto_fused"

    @Synchronized
    fun unregister(reason: String = "unspecified") {
        statusWatchdogJob?.cancel()
        statusWatchdogJob = null

        val callback = statusCallback
        if (callback != null) {
            val manager = locationManagerProvider()
            runCatching { manager?.unregisterGnssStatusCallback(callback) }
            statusCallback = null

            val nowElapsedMs = SystemClock.elapsedRealtime()
            val runtimeMs =
                if (collectorRegisteredAtElapsedMs > 0L) {
                    (nowElapsedMs - collectorRegisteredAtElapsedMs).coerceAtLeast(0L)
                } else {
                    -1L
                }
            val lastStatusAgeMs =
                if (lastStatusAtElapsedMs > 0L) {
                    (nowElapsedMs - lastStatusAtElapsedMs).coerceAtLeast(0L)
                } else {
                    -1L
                }
            GnssDiagnostics.recordEvent(
                "collector_unregistered",
                "reason=$reason runtimeMs=${formatAgeMsForTelemetry(runtimeMs)} " +
                    "statusSamples=$statusSampleCount " +
                    "lastStatusAgeMs=${formatAgeMsForTelemetry(lastStatusAgeMs)} " +
                    "acquisition=${latestAcquisitionState.telemetryValue} " +
                    "firstSignalDelayMs=${formatMilestoneDelay(firstSignalAtElapsedMs)} " +
                    "firstEphemerisDelayMs=${formatMilestoneDelay(firstEphemerisAtElapsedMs)} " +
                    "firstUsedInFixDelayMs=${formatMilestoneDelay(firstUsedInFixAtElapsedMs)} " +
                    "firstFixTtffMs=${firstFixTtffMs ?: "na"}",
            )
        }
        resetRuntimeState()
    }

    private fun scheduleStatusWatchdogIfNeeded() {
        statusWatchdogJob?.cancel()
        val registeredAt = collectorRegisteredAtElapsedMs
        if (registeredAt <= 0L || statusCallback == null) return

        statusWatchdogJob =
            serviceScope.launch {
                delay(GNSS_STATUS_WATCHDOG_DELAY_MS)
                if (statusCallback == null || !debugTelemetryEnabled()) return@launch
                if (statusSampleCount > 0) return@launch

                val nowElapsedMs = SystemClock.elapsedRealtime()
                val sinceRegisterMs = (nowElapsedMs - registeredAt).coerceAtLeast(0L)
                GnssDiagnostics.recordEvent(
                    "collector_no_status",
                    "sinceRegisterMs=${formatAgeMsForTelemetry(sinceRegisterMs)} " +
                        "tracking=${trackingEnabled()} bound=${bound()} keepOpen=${keepOpen()} " +
                        "sourceMode=${sourceMode()}",
                )
            }
    }

    private fun resetRuntimeState() {
        collectorRegisteredAtElapsedMs = 0L
        statusSampleCount = 0
        lastStatusAtElapsedMs = 0L
        firstSignalAtElapsedMs = 0L
        firstEphemerisAtElapsedMs = 0L
        firstUsedInFixAtElapsedMs = 0L
        firstFixTtffMs = null
        latestAcquisitionState = GnssAcquisitionState.NO_RADIO_SIGNAL
        lastUsedZeroWithFreshLocationAtElapsedMs = 0L
        lastSignalsWithoutFreshLocationAtElapsedMs = 0L
    }

    private fun recordLocationConsistency(
        nowElapsedMs: Long,
        sourceMode: String,
        usedInFixCount: Int,
        signalSatelliteCount: Int,
        signalSnapshot: GpsSignalSnapshot,
    ) {
        val consistency =
            resolveGnssLocationConsistency(
                sourceMode = sourceMode,
                usedInFixCount = usedInFixCount,
                signalSatelliteCount = signalSatelliteCount,
                signalSnapshot = signalSnapshot,
            )
        if (consistency == GnssLocationConsistency.CONSISTENT) return

        val lastLoggedAt =
            when (consistency) {
                GnssLocationConsistency.USED_ZERO_WITH_FRESH_LOCATION ->
                    lastUsedZeroWithFreshLocationAtElapsedMs
                GnssLocationConsistency.SIGNALS_WITHOUT_FRESH_LOCATION ->
                    lastSignalsWithoutFreshLocationAtElapsedMs
                GnssLocationConsistency.CONSISTENT -> 0L
            }
        if (lastLoggedAt > 0L && nowElapsedMs - lastLoggedAt < GNSS_LOCATION_DISAGREEMENT_LOG_COOLDOWN_MS) {
            return
        }
        when (consistency) {
            GnssLocationConsistency.USED_ZERO_WITH_FRESH_LOCATION ->
                lastUsedZeroWithFreshLocationAtElapsedMs = nowElapsedMs
            GnssLocationConsistency.SIGNALS_WITHOUT_FRESH_LOCATION ->
                lastSignalsWithoutFreshLocationAtElapsedMs = nowElapsedMs
            GnssLocationConsistency.CONSISTENT -> Unit
        }

        val fixAgeMs =
            if (signalSnapshot.lastFixElapsedRealtimeMs > 0L) {
                (nowElapsedMs - signalSnapshot.lastFixElapsedRealtimeMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        GnssDiagnostics.recordEvent(
            "status_location_disagreement",
            "kind=${consistency.telemetryValue} sourceMode=$sourceMode used=$usedInFixCount " +
                "signal=$signalSatelliteCount fixFresh=${signalSnapshot.lastFixFresh} " +
                "fixAgeMs=${formatAgeMsForTelemetry(fixAgeMs)} " +
                "fixAccuracyM=${signalSnapshot.lastFixAccuracyM.takeIf { it.isFinite() } ?: "na"}",
        )
    }

    private fun recordFirstAcquisitionMilestones(
        nowElapsedMs: Long,
        signalSatelliteCount: Int,
        ephemerisSatelliteCount: Int,
        usedInFixCount: Int,
    ) {
        if (signalSatelliteCount > 0 && firstSignalAtElapsedMs <= 0L) {
            firstSignalAtElapsedMs = nowElapsedMs
            GnssDiagnostics.recordEvent(
                "acquisition_signal_detected",
                "afterRegisterMs=${formatMilestoneDelay(firstSignalAtElapsedMs)} signalSats=$signalSatelliteCount",
            )
        }
        if (ephemerisSatelliteCount > 0 && firstEphemerisAtElapsedMs <= 0L) {
            firstEphemerisAtElapsedMs = nowElapsedMs
            GnssDiagnostics.recordEvent(
                "acquisition_ephemeris_available",
                "afterRegisterMs=${formatMilestoneDelay(firstEphemerisAtElapsedMs)} " +
                    "ephemerisSats=$ephemerisSatelliteCount",
            )
        }
        if (usedInFixCount > 0 && firstUsedInFixAtElapsedMs <= 0L) {
            firstUsedInFixAtElapsedMs = nowElapsedMs
            GnssDiagnostics.recordEvent(
                "acquisition_satellites_used",
                "afterRegisterMs=${formatMilestoneDelay(firstUsedInFixAtElapsedMs)} used=$usedInFixCount",
            )
        }
    }

    private fun formatMilestoneDelay(milestoneElapsedMs: Long): String {
        if (collectorRegisteredAtElapsedMs <= 0L || milestoneElapsedMs <= 0L) return "na"
        return (milestoneElapsedMs - collectorRegisteredAtElapsedMs).coerceAtLeast(0L).toString()
    }

    private fun formatAgeMsForTelemetry(valueMs: Long): String {
        if (valueMs < 0L || valueMs == Long.MAX_VALUE) return "na"
        return valueMs.toString()
    }

    private fun gpsProviderPresent(manager: LocationManager?): Boolean =
        runCatching { manager?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true }
            .getOrDefault(false)

    private fun gpsProviderEnabled(manager: LocationManager?): Boolean =
        runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }
            .getOrDefault(false)
}

internal enum class GnssAcquisitionState(
    val telemetryValue: String,
) {
    NO_RADIO_SIGNAL("no_radio_signal"),
    SIGNALS_NO_EPHEMERIS("signals_no_ephemeris"),
    EPHEMERIS_NO_FIX("ephemeris_no_fix"),
    SATELLITES_USED("satellites_used"),
}

internal fun resolveGnssAcquisitionState(
    signalSatelliteCount: Int,
    ephemerisSatelliteCount: Int,
    usedInFixCount: Int,
): GnssAcquisitionState =
    when {
        usedInFixCount > 0 -> GnssAcquisitionState.SATELLITES_USED
        signalSatelliteCount <= 0 -> GnssAcquisitionState.NO_RADIO_SIGNAL
        ephemerisSatelliteCount <= 0 -> GnssAcquisitionState.SIGNALS_NO_EPHEMERIS
        else -> GnssAcquisitionState.EPHEMERIS_NO_FIX
    }

private const val GNSS_STATUS_WATCHDOG_DELAY_MS = 12_000L
private const val GNSS_LOCATION_DISAGREEMENT_LOG_COOLDOWN_MS = 30_000L
private const val GNSS_L1_MIN_HZ = 1_559_000_000.0
private const val GNSS_L1_MAX_HZ = 1_610_000_000.0
private const val GNSS_L5_MIN_HZ = 1_160_000_000.0
private const val GNSS_L5_MAX_HZ = 1_200_000_000.0
