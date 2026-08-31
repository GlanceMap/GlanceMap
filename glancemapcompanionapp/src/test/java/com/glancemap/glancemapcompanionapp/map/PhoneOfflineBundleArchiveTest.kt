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

    @Test
    fun demSourcesUseWatchCompatibleRemoteNamesAndFolders() {
        assertEquals(
            "N45E006.hgt.zip",
            PhoneOfflineDemSource.STANDARD.remoteFileName("n45e006"),
        )
        assertEquals(
            "https://download.mapsforge.org/maps/dem/dem3/N45/N45E006.hgt.zip",
            PhoneOfflineDemSource.STANDARD.remoteUrl("n45e006"),
        )
        assertEquals(
            "https://s3.amazonaws.com/elevation-tiles-prod/skadi/S45/S45E006.hgt.gz",
            PhoneOfflineDemSource.DETAILED.remoteUrl("s45e006"),
        )
    }
}
