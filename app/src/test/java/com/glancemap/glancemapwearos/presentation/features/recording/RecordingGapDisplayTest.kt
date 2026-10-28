package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingGapDisplayTest {
    @Test
    fun onlySegmentBreaksProduceConnectorsIncludingSinglePointSegments() {
        val a = LatLong(49.0, 9.0)
        val b = LatLong(49.1, 9.1)
        val c = LatLong(49.2, 9.2)
        val d = LatLong(49.3, 9.3)
        assertEquals(
            listOf(listOf(a, b), listOf(c, d)),
            recordingGapConnectors(listOf(listOf(a), listOf(b, c), listOf(d))),
        )
        assertEquals(emptyList<List<LatLong>>(), recordingGapConnectors(listOf(listOf(a, b))))
    }
}
