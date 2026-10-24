package com.glancemap.glancemapwearos.core.service.location.adapters

import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.tasks.await

internal class WearPhoneConnectionProbe(
    private val nodeClient: NodeClient,
) {
    suspend fun isPhoneConnected(): Boolean? =
        runCatching {
            hasNearbyPhone(nodeClient.connectedNodes.await())
        }.getOrNull()
}

// Cloud reachability cannot stand in for the nearby phone used by assisted GPS.
internal fun hasNearbyPhone(nodes: List<Node>): Boolean = nodes.any { it.isNearby }
