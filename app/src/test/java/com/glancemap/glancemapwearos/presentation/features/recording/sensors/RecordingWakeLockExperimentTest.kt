package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWakeLockExperimentTest {
    @Test
    fun disabledBuildNeverAcquiresLock() {
        val lock = FakeLock()
        val experiment = RecordingWakeLockExperiment(enabled = false, createLock = { lock })
        experiment.update(active = true, paused = false)
        assertEquals(0, lock.acquisitions)
    }

    @Test
    fun recordingOwnsOneBoundedLockAndPauseReleasesIt() {
        val lock = FakeLock()
        val experiment = RecordingWakeLockExperiment(enabled = true, createLock = { lock })
        experiment.update(active = true, paused = false)
        experiment.update(active = true, paused = false)
        assertTrue(lock.isHeld)
        assertEquals(1, lock.acquisitions)
        assertEquals(1_800_000L, lock.timeoutMillis)
        experiment.update(active = true, paused = true)
        assertFalse(lock.isHeld)
        experiment.update(active = true, paused = false)
        assertEquals(2, lock.acquisitions)
        experiment.update(active = false, paused = false)
        assertFalse(lock.isHeld)
    }

    @Test
    fun timedOutExperimentDoesNotReacquireIndefinitely() {
        val lock = FakeLock()
        val experiment = RecordingWakeLockExperiment(enabled = true, createLock = { lock })
        experiment.update(active = true, paused = false)
        lock.isHeld = false
        experiment.update(active = true, paused = false)
        assertEquals(1, lock.acquisitions)
        assertFalse(experiment.isHeld)
    }

    @Test
    fun acquisitionFailureCannotLeakLockOrBreakRecording() {
        val lock = FakeLock(failAfterAcquire = true)
        val experiment = RecordingWakeLockExperiment(enabled = true, createLock = { lock })
        experiment.update(active = true, paused = false)
        assertFalse(lock.isHeld)
        assertFalse(experiment.isHeld)
    }

    private class FakeLock(
        val failAfterAcquire: Boolean = false,
    ) : RecordingExperimentLock {
        override var isHeld = false
        var acquisitions = 0
        var timeoutMillis = 0L

        override fun acquire(timeoutMillis: Long) {
            acquisitions += 1
            this.timeoutMillis = timeoutMillis
            isHeld = true
            if (failAfterAcquire) error("acquire failed")
        }

        override fun release() {
            isHeld = false
        }
    }
}
