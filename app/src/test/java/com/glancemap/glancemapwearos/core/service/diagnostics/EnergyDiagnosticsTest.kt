package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeEnergyByModeSummarySection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnergyDiagnosticsTest {
    @Before
    fun setUp() {
        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()
        EnergyDiagnostics.clear()
        EnergyDiagnostics.setEnabled(false)
    }

    @After
    fun tearDown() {
        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()
        EnergyDiagnostics.clear()
        EnergyDiagnostics.setEnabled(false)
    }

    @Test
    fun summaryGroupsSamplesByRuntimeMode() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    "reason=gps_request_applied mode=BURST level=70 tempC=29.0 curNowUa=-200000",
                    "reason=periodic burst=true tracking=true level=69 tempC=29.5 curNowUa=-100000",
                    "reason=periodic screenState=SCREEN_OFF tracking=false level=68 tempC=28.0 curNowUa=-2000",
                    "reason=periodic burst=true interactive=false level=67 tempC=28.0 curNowUa=-4000",
                ),
            )
        val burst = checkNotNull(summary.modes["burst"])
        val screenOff = checkNotNull(summary.modes["screen_off"])

        assertEquals(2, burst.sampleCount)
        assertEquals(-150000L, burst.avgCurrentNowUa)
        assertEquals(150000L, burst.medianAbsCurrentNowUa)
        assertEquals(69, burst.minLevelPct)
        assertEquals(70, burst.maxLevelPct)

        assertEquals(2, screenOff.sampleCount)
        assertEquals(-3000L, screenOff.avgCurrentNowUa)
        assertEquals(3000L, screenOff.medianAbsCurrentNowUa)
        assertEquals(67, screenOff.minLevelPct)
        assertEquals(68, screenOff.maxLevelPct)
    }

    @Test
    fun batteryCaptureGateIsIndependentFromVerboseTelemetry() {
        assertFalse(DebugTelemetry.isEnabled())

        EnergyDiagnostics.setEnabled(true)

        assertTrue(EnergyDiagnostics.isEnabled())
        assertFalse(DebugTelemetry.isEnabled())
    }

    @Test
    fun batteryBenchmarkUsesOnlyFixedCadenceSamples() {
        EnergyDiagnostics.configure(captureActive = true, fullDiagnostics = false)

        assertTrue(EnergyDiagnostics.shouldRecordSample("periodic"))
        assertTrue(EnergyDiagnostics.shouldRecordSample("capture_toggle_on"))
        assertTrue(EnergyDiagnostics.shouldRecordSample("capture_toggle_off"))
        assertFalse(EnergyDiagnostics.shouldRecordSample("gps_burst_start"))
        assertFalse(EnergyDiagnostics.shouldRecordSample("http_transfer_start"))
    }

    @Test
    fun deepTraceInvalidatesBenchmarkUntilDiagnosticsAreCleared() {
        assertTrue(EnergyDiagnostics.batteryBenchmarkValidity().valid)

        EnergyDiagnostics.markBatteryBenchmarkInvalid("compass_deep_trace")

        val invalid = EnergyDiagnostics.batteryBenchmarkValidity()
        assertFalse(invalid.valid)
        assertEquals(listOf("compass_deep_trace"), invalid.invalidReasons)

        EnergyDiagnostics.clear()

        assertTrue(EnergyDiagnostics.batteryBenchmarkValidity().valid)
    }

    @Test
    fun chargeCounterIsPrimaryBatteryConsumptionMeasurement() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(atMs = 1_000L, currentUa = -100_000, chargeCounterUah = 400_000),
                    batteryLine(atMs = 3_601_000L, currentUa = -200_000, chargeCounterUah = 250_000),
                ),
            )

        val batteryUse = checkNotNull(summary.batteryUse)
        assertEquals(150.0, checkNotNull(batteryUse.consumedMah), 0.001)
        assertEquals(150.0, checkNotNull(batteryUse.averageDrawMa), 0.001)
        assertEquals(3_600_000L, batteryUse.captureDurationMs)
        assertEquals(3_600_000L, batteryUse.measuredDurationMs)
        assertEquals(100.0, batteryUse.measurementCoveragePct, 0.001)
        assertEquals("charge_counter", batteryUse.measurement)
        assertEquals("high", batteryUse.confidence)
        assertEquals("charge_counter", batteryUse.reason)
        assertNull(batteryUse.integratedCurrentMah)
    }

    @Test
    fun integratedCurrentIsFallbackWhenChargeCounterIsUnavailable() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(atMs = 1_000L, currentUa = -100_000),
                    batteryLine(atMs = 61_000L, currentUa = -200_000),
                ),
            )

        val batteryUse = checkNotNull(summary.batteryUse)
        assertEquals(2.5, checkNotNull(batteryUse.consumedMah), 0.001)
        assertEquals(150.0, checkNotNull(batteryUse.averageDrawMa), 0.001)
        assertEquals(60_000L, batteryUse.captureDurationMs)
        assertEquals(60_000L, batteryUse.measuredDurationMs)
        assertEquals(100.0, batteryUse.measurementCoveragePct, 0.001)
        assertEquals("integrated_current", batteryUse.measurement)
        assertEquals("medium", batteryUse.confidence)
        assertEquals("integrated_current", batteryUse.reason)
    }

    @Test
    fun tinyIntegratedCurrentCoverageIsInsufficientForLongCapture() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(atMs = 0L, currentUa = -100_000),
                    batteryLine(atMs = 600_000L, currentUa = -100_000),
                    batteryLine(atMs = 600_001L, currentUa = -200_000),
                    batteryLine(atMs = 601_735L, currentUa = -200_000),
                ),
            )

        val batteryUse = checkNotNull(summary.batteryUse)
        assertEquals("insufficient_data", batteryUse.measurement)
        assertEquals("integrated_current_coverage_too_low", batteryUse.reason)
        assertEquals("low", batteryUse.confidence)
        assertEquals(601_735L, batteryUse.captureDurationMs)
        assertEquals(1_735L, batteryUse.measuredDurationMs)
        assertEquals(1_735.0 * 100.0 / 601_735.0, batteryUse.measurementCoveragePct, 0.001)
        assertNull(batteryUse.consumedMah)
        assertNull(batteryUse.averageDrawMa)

        val report = buildString { writeEnergyByModeSummarySection(summary) }
        assertTrue(report.contains("batteryUsedMah=na"))
        assertTrue(report.contains("averageDrawMa=na"))
        assertTrue(report.contains("measurement=insufficient_data"))
        assertTrue(report.contains("confidence=low"))
        assertTrue(report.contains("reason=integrated_current_coverage_too_low"))
    }

    @Test
    fun chargingSamplesAreExcludedFromBatteryConsumption() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(atMs = 1_000L, currentUa = 100_000, status = "charging", plugged = "wireless"),
                    batteryLine(atMs = 61_000L, currentUa = 100_000, status = "charging", plugged = "wireless"),
                ),
            )

        assertNull(summary.batteryUse)
    }

    @Test
    fun chargeCounterEnergyIsAttributedOnlyToStableScreenStateIntervals() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(
                        atMs = 0L,
                        currentUa = -10_000,
                        chargeCounterUah = 100_000,
                        screenState = "INTERACTIVE",
                    ),
                    batteryLine(
                        atMs = 60_000L,
                        currentUa = -10_000,
                        chargeCounterUah = 90_000,
                        screenState = "INTERACTIVE",
                    ),
                    batteryLine(
                        atMs = 60_001L,
                        currentUa = -10_000,
                        chargeCounterUah = 90_000,
                        screenState = "SCREEN_OFF",
                    ),
                    batteryLine(
                        atMs = 120_001L,
                        currentUa = -10_000,
                        chargeCounterUah = 80_000,
                        screenState = "SCREEN_OFF",
                    ),
                ),
            )

        val attribution = checkNotNull(summary.screenStateEnergy)
        assertEquals("charge_counter_intervals", attribution.measurement)
        assertEquals(10.0, checkNotNull(attribution.screenOn).consumedMah, 0.001)
        assertEquals(10.0, checkNotNull(attribution.screenOff).consumedMah, 0.001)
        assertEquals(20.0, attribution.attributedMah, 0.001)
        assertEquals(0.0, attribution.unattributedMah, 0.001)
        assertEquals(100.0, attribution.attributionCoveragePct, 0.001)
        assertEquals("high", attribution.confidence)
    }

    @Test
    fun screenStateTransitionEnergyIsLeftUnattributed() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(
                        atMs = 0L,
                        currentUa = -10_000,
                        chargeCounterUah = 100_000,
                        screenState = "INTERACTIVE",
                    ),
                    batteryLine(
                        atMs = 60_000L,
                        currentUa = -10_000,
                        chargeCounterUah = 90_000,
                        screenState = "SCREEN_OFF",
                    ),
                ),
            )

        val attribution = checkNotNull(summary.screenStateEnergy)
        assertNull(attribution.screenOn)
        assertNull(attribution.screenOff)
        assertEquals(0.0, attribution.attributedMah, 0.001)
        assertEquals(10.0, attribution.unattributedMah, 0.001)
        assertEquals(0.0, attribution.attributionCoveragePct, 0.001)
        assertEquals("low", attribution.confidence)
    }

    @Test
    fun gpsRuntimeIsSeparatedByScreenState() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(
                        atMs = 0L,
                        currentUa = -10_000,
                        chargeCounterUah = 100_000,
                        screenState = "INTERACTIVE",
                        detail =
                            "gpsRequestActive=true gpsBackend=auto_fused gpsRequestIntervalMs=3000",
                    ),
                    batteryLine(
                        atMs = 60_000L,
                        currentUa = -10_000,
                        chargeCounterUah = 99_000,
                        screenState = "SCREEN_OFF",
                        detail =
                            "gpsRequestActive=false gpsBackend=none gpsRequestIntervalMs=na",
                    ),
                ),
            )

        assertEquals(1, summary.gpsRuntime.screenOn.sampleCount)
        assertEquals(1, summary.gpsRuntime.screenOn.requestActiveSampleCount)
        assertEquals(listOf("auto_fused"), summary.gpsRuntime.screenOn.observedBackends)
        assertEquals(listOf(3_000L), summary.gpsRuntime.screenOn.observedRequestIntervalsMs)
        assertEquals(1, summary.gpsRuntime.screenOff.sampleCount)
        assertEquals(1, summary.gpsRuntime.screenOff.requestInactiveSampleCount)
        assertTrue(
            summary.gpsRuntime.screenOff.observedBackends
                .isEmpty(),
        )
        assertTrue(
            summary.gpsRuntime.screenOff.observedRequestIntervalsMs
                .isEmpty(),
        )
    }

    @Test
    fun processCpuIsDerivedFromExistingBatterySamples() {
        val summary =
            EnergyDiagnostics.summarizeLines(
                listOf(
                    batteryLine(
                        atMs = 1_000L,
                        currentUa = -10_000,
                        chargeCounterUah = 100_000,
                        detail = "procCpuMs=120",
                    ),
                    batteryLine(
                        atMs = 61_000L,
                        currentUa = -10_000,
                        chargeCounterUah = 99_000,
                        detail = "procCpuMs=420",
                    ),
                ),
            )

        val cpu = checkNotNull(summary.processCpu)
        assertEquals(2, cpu.sampleCount)
        assertEquals(60_000L, cpu.wallDurationMs)
        assertEquals(300L, cpu.processCpuDurationMs)
        assertEquals(0.5, checkNotNull(cpu.averageCoreUtilizationPct), 0.001)
    }

    @Suppress("LongParameterList")
    private fun batteryLine(
        atMs: Long,
        currentUa: Int,
        chargeCounterUah: Int? = null,
        status: String = "discharging",
        plugged: String = "battery",
        screenState: String? = null,
        detail: String = "",
    ): String =
        "atMs=$atMs reason=periodic status=$status plugged=$plugged " +
            detail.takeIf { it.isNotBlank() }?.plus(" ").orEmpty() +
            screenState?.let { "screenState=$it " }.orEmpty() +
            "curNowUa=$currentUa chargeCounterUah=${chargeCounterUah ?: "na"} level=50 tempC=30.0"
}
