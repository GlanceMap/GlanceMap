package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOfflineMapCreditTest {
    @Test
    fun knownMakersGetAShortLinkedCreditInsteadOfTheirLongHeader() {
        // Header texts copied from real OpenAndroMaps and Freizeitkarte files.
        val openAndroMaps =
            "#VectorMaps:(c)Christian Kernbeis, GeneralMaps:(c)Michael Bechtold, Themes:(c)Tobias Kuehn, " +
                "www.openandromaps.org 2012-2024 #Hoehendaten: sonny.4lima.de, viewfinderpanoamas.org, NASA " +
                "#Kartendaten: openstreetmap.org"
        val freizeitkarte =
            "(c) Map: FZK project (CC-BY-3.0); Map data: OpenStreetMap contributors; " +
                "Contour data: sonny.4lima.de"

        val oamCredit = phoneOfflineMapAttribution(openAndroMaps)
        val fzkCredit = phoneOfflineMapAttribution(freizeitkarte)

        assertTrue(oamCredit.contains("OpenAndroMaps") && oamCredit.endsWith(OPEN_STREET_MAP_ATTRIBUTION))
        assertTrue(fzkCredit.contains("Freizeitkarte") && fzkCredit.endsWith(OPEN_STREET_MAP_ATTRIBUTION))
    }

    @Test
    fun otherMakersAreCreditedWithTheirOwnHeaderText() {
        assertEquals("Map by Example Club", phoneOfflineMapAttribution("  Map by Example Club "))
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
