package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularSignalMonitorTest {
    @Test
    fun mapsAndroidSignalLevelsToArkluzPercent() {
        assertEquals(0, 0.toArkluzSignalPercent())
        assertEquals(25, 1.toArkluzSignalPercent())
        assertEquals(50, 2.toArkluzSignalPercent())
        assertEquals(75, 3.toArkluzSignalPercent())
        assertEquals(100, 4.toArkluzSignalPercent())
        assertEquals(100, 5.toArkluzSignalPercent())
    }

    @Test
    fun keepsUnknownSignalSeparateFromNoSignal() {
        assertEquals(-1, (-1).toArkluzSignalPercent())
        assertEquals(0, 0.toArkluzSignalPercent())
    }
}
