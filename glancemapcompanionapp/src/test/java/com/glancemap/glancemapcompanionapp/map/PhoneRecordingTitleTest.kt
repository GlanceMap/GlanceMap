package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.recording.buildRecordingTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRecordingTitleTest {
    @Test
    fun defaultTitlePrefixesActivityTypeToWatchDateTimeTitle() {
        val start = 1_793_000_000_000L
        val end = start + 2 * 60 * 60 * 1_000L

        val bike = phoneRecordingDefaultTitle("Bike", start, end)
        val hike = phoneRecordingDefaultTitle("Hike", start, end)

        assertEquals("Bike ${buildRecordingTitle(start, end)}", bike)
        assertEquals("Hike ${buildRecordingTitle(start, end)}", hike)
        assertTrue(Regex("""Hike \d{4}-\d{2}-\d{2} \d{2}:\d{2} \d{2}:\d{2}""").matches(hike))
    }
}
