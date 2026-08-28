package com.glancemap.trailcore.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiSemanticsTest {
    @Test
    fun classifiesCommonImportedTagsWithTheSharedTaxonomy() {
        assertEquals(
            PoiType.WATER,
            PoiSemantics.classify(
                tags = mapOf("amenity" to "drinking_water"),
                categoryName = "Other",
                rawData = "",
            ),
        )
        assertEquals(
            PoiType.HUT,
            PoiSemantics.classify(
                tags = mapOf("tourism" to "alpine_hut"),
                categoryName = "Other",
                rawData = "",
            ),
        )
        assertEquals(
            PoiType.VIEWPOINT,
            PoiSemantics.classify(
                tags = mapOf("source" to "import"),
                categoryName = "Panorama",
                rawData = "",
            ),
        )
    }

    @Test
    fun preparesOnlyAvailableRefugeDetails() {
        val details =
            PoiSemantics.details(
                tags =
                    mapOf(
                        "ele" to "2345 m",
                        "capacity" to "18 beds",
                        "refuges_info:state" to "Open",
                    ),
                categoryName = "Alpine huts",
            )

        assertEquals("Alpine huts", details?.typeLabel)
        assertEquals(2345, details?.elevationMeters)
        assertEquals(18, details?.sleepingPlaces)
        assertEquals("Open", details?.state)
        assertNull(details?.shortDescription)
    }

    @Test
    fun parsesOnlyNonBlankTagEntries() {
        assertEquals(
            mapOf("name" to "Spring", "amenity" to "drinking_water"),
            PoiSemantics.parseTags("name=Spring\namenity=drinking_water\nempty=\ninvalid"),
        )
    }
}
