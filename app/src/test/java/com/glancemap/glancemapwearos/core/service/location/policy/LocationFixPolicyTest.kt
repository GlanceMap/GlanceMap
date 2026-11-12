package com.glancemap.glancemapwearos.core.service.location.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixPolicyTest {
    @Test
    fun acceptsFiniteCoordinatesInsideEarthBounds() {
        assertTrue(LocationFixPolicy.hasValidCoordinates(latitude = 48.8566, longitude = 2.3522))
        assertTrue(LocationFixPolicy.hasValidCoordinates(latitude = -90.0, longitude = -180.0))
        assertTrue(LocationFixPolicy.hasValidCoordinates(latitude = 90.0, longitude = 180.0))
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        assertFalse(LocationFixPolicy.hasValidCoordinates(latitude = Double.NaN, longitude = 2.3522))
        assertFalse(LocationFixPolicy.hasValidCoordinates(latitude = 48.8566, longitude = Double.NaN))
        assertFalse(
            LocationFixPolicy.hasValidCoordinates(
                latitude = Double.POSITIVE_INFINITY,
                longitude = 2.3522,
            ),
        )
    }

    @Test
    fun rejectsCoordinatesOutsideEarthBounds() {
        assertFalse(LocationFixPolicy.hasValidCoordinates(latitude = 90.0001, longitude = 2.3522))
        assertFalse(LocationFixPolicy.hasValidCoordinates(latitude = 48.8566, longitude = 180.0001))
        assertFalse(LocationFixPolicy.hasValidCoordinates(latitude = -90.0001, longitude = 2.3522))
        assertFalse(LocationFixPolicy.hasValidCoordinates(latitude = 48.8566, longitude = -180.0001))
    }

    @Test
    fun fusedFixWithTheWatchChipFixedAccuracyIsAcceptedOnlyWithoutAConfirmedPhone() {
        val fused = FixAcceptancePolicy(maxAgeMs = 20_000L, maxAccuracyM = 50f)
        val withoutPhone =
            LocationFixPolicy.allowKnownWatchGpsFloor(fused, LocationSourceMode.AUTO_FUSED, false, 130f)
        val unknownPhone =
            LocationFixPolicy.allowKnownWatchGpsFloor(fused, LocationSourceMode.AUTO_FUSED, null, 130f)
        val withPhone =
            LocationFixPolicy.allowKnownWatchGpsFloor(fused, LocationSourceMode.AUTO_FUSED, true, 130f)

        assertFalse(withoutPhone.rejectsAccuracy(125f))
        assertFalse(unknownPhone.rejectsAccuracy(127.5f))
        assertTrue(withPhone.rejectsAccuracy(125f))
        assertTrue(fused.rejectsAccuracy(125f))
        assertEquals(fused, withPhone)
    }

    @Test
    fun onlyTheKnownFixedValueBypassesTheFusedAccuracyLimit() {
        val policy =
            LocationFixPolicy.allowKnownWatchGpsFloor(
                FixAcceptancePolicy(maxAgeMs = 20_000L, maxAccuracyM = 50f),
                LocationSourceMode.AUTO_FUSED,
                false,
                130f,
            )
        assertFalse(policy.rejectsAccuracy(30f))
        assertTrue(policy.rejectsAccuracy(60f))
        assertTrue(policy.rejectsAccuracy(100f))
        assertTrue(policy.rejectsAccuracy(129f))
        assertTrue(policy.rejectsAccuracy(Float.NaN))
    }

    @Test
    fun watchGpsAcceptsThePlaceholderUpToTheFloorCeilingAndPassiveDoesNot() {
        val policy = FixAcceptancePolicy(maxAgeMs = 20_000L, maxAccuracyM = 130f)
        val watchGps = LocationFixPolicy.allowKnownWatchGpsFloor(policy, LocationSourceMode.WATCH_GPS, true, 250f)
        val passive =
            LocationFixPolicy.allowKnownWatchGpsFloor(policy, LocationSourceMode.PASSIVE_EXTERNAL, false, 250f)

        assertEquals(250f, watchGps.knownWatchGpsFloorMaxAccuracyM ?: -1f, 0f)
        assertNull(passive.knownWatchGpsFloorMaxAccuracyM)
    }
}
