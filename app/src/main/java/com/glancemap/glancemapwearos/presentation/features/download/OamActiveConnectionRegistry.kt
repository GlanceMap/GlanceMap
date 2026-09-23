package com.glancemap.glancemapwearos.presentation.features.download

import java.net.HttpURLConnection
import java.util.Collections

internal class OamActiveConnectionRegistry {
    val connections: MutableSet<HttpURLConnection> = Collections.synchronizedSet(mutableSetOf())

    fun abortAll(): Int {
        val snapshot = synchronized(connections) { connections.toList() }
        snapshot.forEach { connection ->
            runCatching { connection.disconnect() }
        }
        return snapshot.size
    }
}
