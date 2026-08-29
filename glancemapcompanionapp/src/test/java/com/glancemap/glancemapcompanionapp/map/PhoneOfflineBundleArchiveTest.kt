package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneOfflineBundleArchiveTest {
    @Test
    fun expectedMapEntryUsesOnlyItsSafeLeafName() {
        assertEquals(
            "alps.map",
            expectedPhoneBundleArchiveEntryName("maps/alps.map", ".map"),
        )
    }

    @Test
    fun traversalAndWrongEntryTypesAreRejected() {
        assertNull(expectedPhoneBundleArchiveEntryName("../alps.map", ".map"))
        assertNull(expectedPhoneBundleArchiveEntryName("/alps.map", ".map"))
        assertNull(expectedPhoneBundleArchiveEntryName("alps.poi", ".map"))
    }
}
