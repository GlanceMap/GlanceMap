package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OamDownloadAreaCoverageTest {
    @Test
    fun wurzburgIncludesBayernButNotTheOverlappingHessenBoundingBox() {
        val suggestions =
            OamDownloadCatalog.areasForLocation(
                latitude = 49.7913,
                longitude = 9.9534,
            )

        assertTrue(suggestions.any { it.id == "germany-bayern" })
        assertFalse(suggestions.any { it.id == "germany-hessen" })
    }

    @Test
    fun invalidLocationDoesNotSuggestAnArea() {
        val suggestions = OamDownloadCatalog.areasForLocation(latitude = 91.0, longitude = 10.0)

        assertTrue(suggestions.isEmpty())
    }
}
