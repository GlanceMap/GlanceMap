package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMapSourcePreferenceTest {
    @Test
    fun restoringOnlineModeKeepsAnUnavailablePersistedKeyDependentSource() {
        listOf(
            PhoneOnlineMapSource.SATELLITE,
            PhoneOnlineMapSource.TRACESTRACK_TOPO,
        ).forEach { unavailableSource ->
            val persistedOfflineSelection =
                PhoneMapSourcePreference(
                    mode = PhoneMapSourcePreferenceMode.OFFLINE,
                    onlineSource = unavailableSource,
                )

            val effectiveRuntimeSource =
                effectiveOnlineMapSource(persistedOfflineSelection.onlineSource) { source ->
                    source != unavailableSource
                }
            val restored = persistedOfflineSelection.restoredOnline()

            assertEquals(PhoneOnlineMapSource.OPEN_TOPO, effectiveRuntimeSource)
            assertEquals(PhoneMapSourcePreferenceMode.ONLINE, restored.mode)
            assertEquals(unavailableSource, restored.onlineSource)
        }
    }
}
