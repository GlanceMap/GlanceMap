package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhonePoiCreationTest {
    @Test
    fun decimalCommasWhitespaceAndSignedCoordinatesAreAccepted() {
        assertEquals(PhoneMapCoordinate(-33.9, -70.6), parsePhonePoiCoordinate(" -33,9 ", " -70.6 "))
    }

    @Test
    fun equatorAndCoordinateLimitsAreValid() {
        assertEquals(PhoneMapCoordinate(0.0, 0.0), parsePhonePoiCoordinate("0", "0"))
        assertEquals(PhoneMapCoordinate(-90.0, 180.0), parsePhonePoiCoordinate("-90", "180"))
        assertEquals(PhoneMapCoordinate(90.0, -180.0), parsePhonePoiCoordinate("90", "-180"))
    }

    @Test
    fun latitudeAndLongitudeUseTheirOwnLimits() {
        assertNull(parsePhonePoiCoordinate("90.01", "0"))
        assertNull(parsePhonePoiCoordinate("-90.01", "0"))
        assertNull(parsePhonePoiCoordinate("0", "180.01"))
        assertNull(parsePhonePoiCoordinate("0", "-180.01"))
        assertNull(parsePhonePoiCoordinate("120", "45"))
    }

    @Test
    fun nonFiniteAndMalformedValuesCannotBecomePoiLocations() {
        listOf("NaN", "Infinity", "-Infinity", "1e999", "", " ", "Paris", "45.1.2", "45,1,2").forEach { invalid ->
            assertNull(parsePhonePoiCoordinate(invalid, "6"))
            assertNull(parsePhonePoiCoordinate("45", invalid))
        }
    }
}
