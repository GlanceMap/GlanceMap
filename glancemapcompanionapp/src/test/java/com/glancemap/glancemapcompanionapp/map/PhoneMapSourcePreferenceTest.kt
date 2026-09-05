package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMapSourcePreferenceTest {
    @Test
    fun restoringOnlineModeKeepsAnUnavailablePersistedOnlineSource() {
        val persistedOfflineSelection =
            PhoneMapSourcePreference(
                mode = PhoneMapSourcePreferenceMode.OFFLINE,
                onlineSource = PhoneOnlineMapSource.SATELLITE,
            )

        val effectiveRuntimeSource =
            effectiveOnlineMapSource(persistedOfflineSelection.onlineSource) { source ->
                source != PhoneOnlineMapSource.SATELLITE
            }
        val restored = persistedOfflineSelection.restoredOnline()

        assertEquals(PhoneOnlineMapSource.OPEN_TOPO, effectiveRuntimeSource)
        assertEquals(PhoneMapSourcePreferenceMode.ONLINE, restored.mode)
        assertEquals(PhoneOnlineMapSource.SATELLITE, restored.onlineSource)
    }
}
