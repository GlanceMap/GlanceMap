package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.FusedAbsoluteHeadingSample
import com.glancemap.glancemapwearos.domain.sensors.FusedHeadingIntegrityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingDiagnosticsTest {
    @Test
    fun acquisitionHeldProviderJumpIsNotReportedAsCorroborated() {
        val engine = FusedHeadingIntegrityEngine(relativeSensorAvailable = true, magnetometerAvailable = true)
        engine.reset(seedHeadingDeg = 100f, atElapsedMs = 1_000L, clearSensorEvidence = true)

        engine.onMagneticField(strengthUt = 42f, atElapsedMs = 1_000L)
        engine.onRelativeHeading(headingDeg = 0f, horizontalProjection = 0.9f, atElapsedMs = 1_000L)
        engine.onAbsoluteHeading(absoluteSample(headingDeg = 100f, atElapsedMs = 1_000L))

        engine.onMagneticField(strengthUt = 42f, atElapsedMs = 1_200L)
        engine.onRelativeHeading(headingDeg = 351.7f, horizontalProjection = 0.9f, atElapsedMs = 1_200L)
        val snapshot = engine.onAbsoluteHeading(absoluteSample(headingDeg = 352f, atElapsedMs = 1_200L))

        assertEquals(CompassTrackingState.ACQUIRING, snapshot.state)
        assertTrue(snapshot.heldOutput)
        assertEquals(-108f, snapshot.absoluteStepDeg!!, 0.01f)
        assertEquals(-8.3f, snapshot.relativeStepDeg!!, 0.01f)
        assertEquals(
            "acquisition_held",
            CompassHeadingDiagnostics.providerStepIntegrityDecision(snapshot, targetStepDeg = 0f),
        )
    }

    @Test
    fun corroboratedMovementIsReportedSeparatelyFromAnUnverifiedAcceptedStep() {
        val engine = FusedHeadingIntegrityEngine(relativeSensorAvailable = true, magnetometerAvailable = true)
        var nowElapsedMs = acquireStableHeading(engine, headingDeg = 0f, nowElapsedMs = 1_000L)

        nowElapsedMs += 20L
        engine.onRelativeHeading(headingDeg = 0f, horizontalProjection = 0.9f, atElapsedMs = nowElapsedMs)
        engine.onAbsoluteHeading(
            absoluteSample(
                headingDeg = 180f,
                atElapsedMs = nowElapsedMs,
                conservativeErrorDeg = 180f,
            ),
        )

        nowElapsedMs += 20L
        engine.onRelativeHeading(headingDeg = 90f, horizontalProjection = 0.9f, atElapsedMs = nowElapsedMs)
        val intermediate = engine.onAbsoluteHeading(absoluteSample(headingDeg = 90f, atElapsedMs = nowElapsedMs))

        nowElapsedMs += 20L
        engine.onRelativeHeading(headingDeg = 180f, horizontalProjection = 0.9f, atElapsedMs = nowElapsedMs)
        val recovered = engine.onAbsoluteHeading(absoluteSample(headingDeg = 180f, atElapsedMs = nowElapsedMs))
        val targetStepDeg =
            requireNotNull(recovered.renderHeadingDeg) - requireNotNull(intermediate.renderHeadingDeg)

        assertFalse(recovered.heldOutput)
        assertFalse(recovered.quarantineActive)
        assertTrue(recovered.relativeStepDeg != null)
        assertTrue(targetStepDeg > 0f)
        assertEquals(
            "accepted_with_corroboration",
            CompassHeadingDiagnostics.providerStepIntegrityDecision(recovered, targetStepDeg),
        )
    }

    private fun absoluteSample(
        headingDeg: Float,
        atElapsedMs: Long,
        conservativeErrorDeg: Float? = 30f,
    ): FusedAbsoluteHeadingSample =
        FusedAbsoluteHeadingSample(
            headingDeg = headingDeg,
            liveErrorDeg = 8f,
            conservativeErrorDeg = conservativeErrorDeg,
            atElapsedMs = atElapsedMs,
        )

    private fun acquireStableHeading(
        engine: FusedHeadingIntegrityEngine,
        headingDeg: Float,
        nowElapsedMs: Long,
    ): Long {
        var now = nowElapsedMs
        engine.onMagneticField(strengthUt = 42f, atElapsedMs = now)
        now += 200L
        engine.onMagneticField(strengthUt = 42f, atElapsedMs = now)
        repeat(9) { index ->
            if (index > 0) now += 50L
            engine.onMagneticField(strengthUt = 42f, atElapsedMs = now)
            engine.onRelativeHeading(headingDeg = 0f, horizontalProjection = 0.9f, atElapsedMs = now)
            engine.onAbsoluteHeading(absoluteSample(headingDeg = headingDeg, atElapsedMs = now))
        }
        return now
    }
}
