package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PhoneMapOrientationDiagnosticsTest {
    private lateinit var directory: File
    private val diagnostics = PhoneMapOrientationDiagnostics()

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "orientation-${System.nanoTime()}")
        PhoneDebugCapture.useTestStorage(directory)
        PhoneCompassDiagnostics.clear()
    }

    @After
    fun tearDown() {
        PhoneDebugCapture.stop()
        PhoneCompassDiagnostics.clear()
        directory.deleteRecursively()
    }

    @Test
    fun anglesAreCollectedOnlyForTheCurrentExplicitCapture() {
        PhoneCompassDiagnostics.recordOrientation(compass())
        assertNull(PhoneCompassDiagnostics.orientationSample(1L))
        PhoneDebugCapture.start()
        val firstSession = sessionId()
        assertNull(PhoneCompassDiagnostics.orientationSample(firstSession))
        PhoneCompassDiagnostics.recordOrientation(compass())
        assertEquals(compass(), PhoneCompassDiagnostics.orientationSample(firstSession))
        PhoneDebugCapture.stop()
        assertNull(PhoneCompassDiagnostics.orientationSample(firstSession))
        PhoneDebugCapture.start()
        assertNull(PhoneCompassDiagnostics.orientationSample(sessionId()))
    }

    @Test
    fun samplesDistinguishInputsFromObservedBearingsAndUseSignedWraparoundErrors() {
        PhoneDebugCapture.start()
        diagnostics.record(sessionId(), 1_000L, compass(), render())
        val line = PhoneDebugCapture.snapshot().single()
        listOf(
            "raw=359 adjusted=1 smoothed=1",
            "displayRotation=90 pitch=-30 roll=5",
            "mapRequested=1 mapActual=359 mapError=2",
            "markerExpectedScreen=2 markerObservedScreen=358 markerError=4",
            "markerObservation=maplibre_source markerAnimation=true",
            "sensorAgeMs=100 markerAgeMs=unknown",
        ).forEach { assertTrue(line, line.contains(it)) }
        assertFalse(line.contains("latitude"))
        assertFalse(line.contains("longitude"))
    }

    @Test
    fun changingAnglesAreThrottledAndQuietSamplesStillReportFreshness() {
        PhoneDebugCapture.start()
        val sessionId = sessionId()
        diagnostics.record(sessionId, 1_000L, compass(), render())
        val moved = render().copy(actualMapDegrees = 350f)
        diagnostics.record(sessionId, 1_250L, compass(), moved)
        assertEquals(1, PhoneDebugCapture.snapshot().size)
        diagnostics.record(sessionId, 1_500L, compass(), moved)
        assertEquals(2, PhoneDebugCapture.snapshot().size)
        diagnostics.record(sessionId, 6_499L, compass(), moved)
        assertEquals(2, PhoneDebugCapture.snapshot().size)
        diagnostics.record(sessionId, 6_500L, compass(), moved)
        assertEquals(3, PhoneDebugCapture.snapshot().size)
        assertTrue(PhoneDebugCapture.snapshot().last().contains("sensorAgeMs=5600"))
    }

    @Test
    fun freeModeManualBearingAndPassiveComparisonRemainExplicit() {
        PhoneDebugCapture.start()
        val render =
            render().copy(
                renderer = "mapsforge_comparison_2",
                mapMode = PhoneMapMode(PhoneMapOrientation.HEADING_UP, PhoneMapFollowMode.FREE, 90f),
                cameraControl = "passive",
                marker = PhoneMapOrientationMarker(2f, "mapsforge_draw", false, 800L),
            )
        diagnostics.record(sessionId(), 1_000L, compass(), render)
        val line = PhoneDebugCapture.snapshot().single()
        assertTrue(line.contains("renderer=mapsforge_comparison_2 control=passive"))
        assertTrue(line.contains("follow=FREE orientation=HEADING_UP manual=90"))
        assertTrue(line.contains("markerError=0"))
        assertTrue(line.contains("markerAgeMs=200"))
    }

    @Test
    fun missingAndNonFiniteReadingsRemainUnknownInsteadOfLookingLikeNorth() {
        PhoneDebugCapture.start()
        val invalid =
            compass().copy(
                rawHeadingDegrees = Float.NaN,
                northAdjustedHeadingDegrees = Float.POSITIVE_INFINITY,
                state = PhoneCompassState(),
                attitude = PhoneCompassAttitude(0),
                sampleElapsedMs = null,
            )
        diagnostics.record(
            sessionId(),
            1_000L,
            invalid,
            render().copy(actualMapDegrees = null, marker = PhoneMapOrientationMarker(observedVia = "read_failed")),
        )
        val line = PhoneDebugCapture.snapshot().single()
        assertTrue(line.contains("raw=unknown adjusted=unknown smoothed=unknown"))
        assertTrue(line.contains("mapActual=unknown mapError=unknown"))
        assertTrue(line.contains("markerObservedScreen=unknown markerError=unknown"))
        assertTrue(line.contains("sensorAgeMs=unknown"))
        assertFalse(line.contains("NaN"))
        assertFalse(line.contains("Infinity"))
    }

    @Test
    fun obsoleteCallbacksCannotWriteToTheNextCaptureAndItsThrottleStartsFresh() {
        PhoneDebugCapture.start()
        val oldSession = sessionId()
        diagnostics.record(oldSession, 1_000L, compass(), render())
        PhoneDebugCapture.start()
        diagnostics.record(oldSession, 1_100L, compass(), render())
        assertTrue(PhoneDebugCapture.snapshot().isEmpty())
        diagnostics.record(sessionId(), 1_100L, compass(), render())
        assertEquals(1, PhoneDebugCapture.snapshot().size)
        PhoneDebugCapture.stop()
        diagnostics.record(sessionId() - 1L, 2_000L, compass(), render())
        assertEquals(1, PhoneDebugCapture.snapshot().size)
    }

    @Test
    fun sampledOrientationSurvivesInterruptedCaptureRecovery() {
        PhoneDebugCapture.start()
        diagnostics.record(sessionId(), 1_000L, compass(), render())
        PhoneDebugCapture.flushForTest()
        PhoneDebugCapture.reloadFromStorageForTest()
        assertTrue(PhoneDebugCapture.state.value.interrupted)
        assertFalse(PhoneDebugCapture.isActive())
        assertTrue(PhoneDebugCapture.snapshot().single().contains("event=map_orientation"))
    }

    private fun sessionId(): Long = PhoneDebugCapture.state.value.sessionId

    private fun compass() =
        PhoneCompassOrientationSample(
            sampleElapsedMs = 900L,
            rawHeadingDegrees = 359f,
            northAdjustedHeadingDegrees = 1f,
            state =
                PhoneCompassState(
                    headingDegrees = 1f,
                    isRenderable = true,
                    accuracy = 3,
                    pipeline = PhoneCompassPipeline.ROTATION_VECTOR,
                ),
            attitude = PhoneCompassAttitude(90, -30f, 5f),
            northReference = PhoneMapNorthReferenceMode.TRUE,
        )

    private fun render() =
        PhoneMapOrientationRenderSample(
            renderer = "maplibre_base_1_style1",
            mapMode = PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP),
            requestedMapDegrees = 1f,
            actualMapDegrees = 359f,
            marker = PhoneMapOrientationMarker(358f, "maplibre_source", true),
            cameraControl = "follow",
        )
}
