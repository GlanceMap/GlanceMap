package com.glancemap.glancemapcompanionapp.filepicker

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionHomeAreaTest {
    @Test
    fun normalLaunchOpensMap() {
        assertEquals(CompanionHomeArea.MAP, initialCompanionHomeArea(0L, 0L))
    }

    @Test
    fun fileShareAndLiveTrackingLaunchesKeepTheirDestinations() {
        assertEquals(CompanionHomeArea.SEND_TO_WATCH, initialCompanionHomeArea(0L, 1L))
        assertEquals(CompanionHomeArea.LIVE_TRACKING, initialCompanionHomeArea(1L, 0L))
        assertEquals(CompanionHomeArea.LIVE_TRACKING, initialCompanionHomeArea(1L, 1L))
    }
}
