package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularSignalMonitorTest {
    @Test
    fun mapsAndroidSignalLevelsToArkluzPercent() {
        assertEquals(-1, (-1).toArkluzSignalPercent())
        assertEquals(20, 0.toArkluzSignalPercent())
        assertEquals(40, 1.toArkluzSignalPercent())
        assertEquals(60, 2.toArkluzSignalPercent())
        assertEquals(80, 3.toArkluzSignalPercent())
        assertEquals(100, 4.toArkluzSignalPercent())
        assertEquals(100, 5.toArkluzSignalPercent())
    }

    @Test
    fun keepsUnknownSignalSeparateFromNoSignal() {
        assertEquals(-1, (-1).toArkluzSignalPercent())
        assertEquals(20, 0.toArkluzSignalPercent())
    }
}
