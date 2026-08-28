package com.glancemap.glancemapwearos.domain.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHardwareCapabilityTest {
    @Test
    fun temporaryHeadingNoneOnCompassCapableWatchDoesNotShowTheNotice() {
        val temporaryHeadingSource = HeadingSource.NONE
        val capability =
            CompassHardwareCapability(
                compassFeatureAvailable = true,
                magnetometerAvailable = true,
            )

        assertEquals(HeadingSource.NONE, temporaryHeadingSource)
        assertFalse(capability.hardwareCompassUnavailable)
        assertFalse(
            shouldShowCompassHardwareUnavailableNotice(
                capability = capability,
                acknowledged = false,
            ),
        )
    }

    @Test
    fun absentCompassHardwareShowsTheNoticeOnlyUntilAcknowledged() {
        val capability =
            CompassHardwareCapability(
                compassFeatureAvailable = false,
                magnetometerAvailable = false,
            )

        assertTrue(capability.hardwareCompassUnavailable)
        assertTrue(
            shouldShowCompassHardwareUnavailableNotice(
                capability = capability,
                acknowledged = false,
            ),
        )
        assertFalse(
            shouldShowCompassHardwareUnavailableNotice(
                capability = capability,
                acknowledged = true,
            ),
        )
    }

    @Test
    fun exposedMagnetometerPreventsAFalseNoticeWhenFeatureMetadataIsMissing() {
        val capability =
            CompassHardwareCapability(
                compassFeatureAvailable = false,
                magnetometerAvailable = true,
            )

        assertFalse(capability.hardwareCompassUnavailable)
    }
}
