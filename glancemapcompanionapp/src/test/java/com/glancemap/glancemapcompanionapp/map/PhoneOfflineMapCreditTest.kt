package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneOfflineMapCreditTest {
    @Test
    fun mapMakerHeaderCommentIsTheCredit() {
        val comment =
            "(c) Map: FZK project (CC-BY-3.0); Map data: OpenStreetMap contributors; " +
                "Contour data: sonny.4lima.de"

        assertEquals(comment, phoneOfflineMapAttribution("  $comment "))
    }

    @Test
    fun mapsWithoutHeaderCommentAreCreditedToOpenStreetMap() {
        assertEquals(OPEN_STREET_MAP_ATTRIBUTION, phoneOfflineMapAttribution(null))
        assertEquals(OPEN_STREET_MAP_ATTRIBUTION, phoneOfflineMapAttribution("   "))
    }

    @Test
    fun headerCommentIsShownAsTextNotMarkup() {
        assertEquals(
            "Maps &amp; data &lt;b&gt;&quot;OAM&quot;&lt;/b&gt;",
            phoneOfflineMapAttribution("Maps & data <b>\"OAM\"</b>"),
        )
    }
}
