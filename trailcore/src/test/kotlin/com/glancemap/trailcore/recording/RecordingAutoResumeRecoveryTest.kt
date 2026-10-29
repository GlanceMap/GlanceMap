package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAutoResumeRecoveryTest {
    private fun fix(
        id: Int,
        elapsed: Long,
        meters: Double,
    ): RecordingAutoResumeFix<Int> =
        RecordingAutoResumeFix(
            value = id,
            point = RecordingPoint(GeoPoint(0.0, meters / 111_320.0), null, elapsed, 4f, 4f),
            elapsedMillis = elapsed,
            speedAccuracyMps = 0.5f,
            source = "gps",
        )

    @Test
    fun credibleRestartRetainsOriginalFixes() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), true)
        buffer.offer(fix(1, 15_000, 20.0), true)
        buffer.offer(fix(2, 20_000, 40.0), true)
        assertEquals(listOf(0, 1, 2), buffer.recover(RecordingSettings.ACTIVITY_PROFILE_BIKE).map { it.value })
    }

    @Test
    fun driftAbandonedStartAndImplausibleJumpAreNotRecovered() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), true)
        buffer.offer(fix(1, 15_000, 1.0), true)
        assertTrue(buffer.recover(RecordingSettings.ACTIVITY_PROFILE_BIKE).isEmpty())
        buffer.offer(fix(2, 20_000, 0.0), false)
        buffer.offer(fix(3, 25_000, 1_000.0), true)
        assertTrue(buffer.recover(RecordingSettings.ACTIVITY_PROFILE_BIKE).isEmpty())
        buffer.offer(fix(4, 30_000, 2_000.0), true)
        assertTrue(buffer.recover(RecordingSettings.ACTIVITY_PROFILE_BIKE).isEmpty())
    }
}
