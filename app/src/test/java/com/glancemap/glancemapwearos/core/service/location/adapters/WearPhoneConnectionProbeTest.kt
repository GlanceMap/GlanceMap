package com.glancemap.glancemapwearos.core.service.location.adapters

import com.google.android.gms.wearable.Node
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPhoneConnectionProbeTest {
    @Test
    fun remotePhoneDoesNotDisableStandaloneGpsRecovery() {
        assertFalse(hasNearbyPhone(listOf(TestNode("phone", nearby = false))))
    }

    @Test
    fun directlyConnectedPhonePreservesPhoneAssistedGps() {
        assertTrue(hasNearbyPhone(listOf(TestNode("phone", nearby = true))))
    }

    @Test
    fun nearbyNodeIsFoundEvenWhenRemoteNodeComesFirst() {
        assertTrue(hasNearbyPhone(listOf(TestNode("remote", false), TestNode("nearby", true))))
    }

    @Test
    fun noReachableNodesAllowsStandaloneGpsRecovery() {
        assertFalse(hasNearbyPhone(emptyList()))
    }

    private class TestNode(
        private val nodeId: String,
        private val nearby: Boolean,
    ) : Node {
        override fun getId(): String = nodeId

        override fun getDisplayName(): String = nodeId

        override fun isNearby(): Boolean = nearby
    }
}
