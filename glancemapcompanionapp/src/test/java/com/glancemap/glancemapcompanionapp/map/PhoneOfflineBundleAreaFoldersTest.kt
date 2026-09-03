package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadArea
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneOfflineBundleAreaFoldersTest {
    @Test
    fun foldersMatchWatchOrdering() {
        val folders =
            phoneOfflineBundleAreaFolders(
                listOf(
                    area(continent = "Europe", region = "Zulu"),
                    area(continent = "Africa", region = "Kenya"),
                    area(continent = "Europe", region = "Alps"),
                ),
            )

        assertEquals(listOf("Africa", "Europe"), folders.map { it.first })
        assertEquals(listOf("Alps", "Zulu"), folders[1].second.map { it.region })
    }

    private fun area(
        continent: String,
        region: String,
    ): OamDownloadArea =
        OamDownloadArea(
            id = "$continent-$region",
            continent = continent,
            region = region,
            mapSizeLabel = "",
            mapSizeBytes = 0L,
            poiSizeLabel = "",
            poiSizeBytes = 0L,
            notes = "",
            contourLabel = "",
            mapZipUrl = "",
            poiZipUrl = "",
        )
}
