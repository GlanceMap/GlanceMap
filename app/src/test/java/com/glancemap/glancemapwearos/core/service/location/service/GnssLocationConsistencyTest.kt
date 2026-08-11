package com.glancemap.glancemapwearos.core.service.location.service

import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class GnssLocationConsistencyTest {
    @Test
    fun zeroUsedSatellitesDoesNotInvalidateAFreshWatchGpsLocation() {
        assertEquals(
            GnssLocationConsistency.USED_ZERO_WITH_FRESH_LOCATION,
            resolveGnssLocationConsistency(
                sourceMode = "watch_gps",
                usedInFixCount = 0,
                signalSatelliteCount = 47,
                signalSnapshot = GpsSignalSnapshot(lastFixFresh = true),
            ),
        )
    }

    @Test
    fun watchGpsSignalsWithoutAFreshLocationAreReportedSeparately() {
        assertEquals(
            GnssLocationConsistency.SIGNALS_WITHOUT_FRESH_LOCATION,
            resolveGnssLocationConsistency(
                sourceMode = "watch_gps",
                usedInFixCount = 0,
                signalSatelliteCount = 47,
                signalSnapshot = GpsSignalSnapshot(lastFixFresh = false),
            ),
        )
    }

    @Test
    fun autoFusedStatusDoesNotProduceWatchGpsDisagreementEvents() {
        assertEquals(
            GnssLocationConsistency.CONSISTENT,
            resolveGnssLocationConsistency(
                sourceMode = "auto_fused",
                usedInFixCount = 0,
                signalSatelliteCount = 47,
                signalSnapshot = GpsSignalSnapshot(lastFixFresh = false),
            ),
        )
    }
}
