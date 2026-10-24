package com.glancemap.glancemapwearos.presentation.features.download

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class OamActiveConnectionRegistry {
    val connections: MutableSet<HttpURLConnection> = Collections.synchronizedSet(mutableSetOf())
    private val resumable = ConcurrentHashMap<HttpURLConnection, AtomicBoolean>()
    private val generation = AtomicLong()
    val handoffGeneration: Long get() = generation.get()

    fun registerResumable(
        connection: HttpURLConnection,
        openingGeneration: Long,
    ) {
        val signal = AtomicBoolean()
        connections.add(connection)
        resumable[connection] = signal
        // Cover recovery between choosing a route and publishing the active connection.
        if (generation.get() != openingGeneration) requestHandoff(connection, signal)
    }

    fun unregisterResumable(connection: HttpURLConnection) {
        resumable.remove(connection)
        connections.remove(connection)
    }

    fun requestHandoff(disconnect: (HttpURLConnection) -> Unit = { it.disconnect() }): Int {
        generation.incrementAndGet()
        return resumable.entries.count { (connection, signal) -> requestHandoff(connection, signal, disconnect) }
    }

    fun isHandoffRequested(connection: HttpURLConnection): Boolean = resumable[connection]?.get() == true

    fun throwIfHandoffRequested(connection: HttpURLConnection) {
        if (isHandoffRequested(connection)) throw IOException("OAM_NETWORK_HANDOFF")
    }

    fun checkedInput(
        connection: HttpURLConnection,
        input: InputStream,
    ): InputStream =
        object : FilterInputStream(input) {
            override fun read(): Int {
                throwIfHandoffRequested(connection)
                val result = super.read()
                throwIfHandoffRequested(connection)
                return result
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                throwIfHandoffRequested(connection)
                val result = super.read(buffer, offset, length)
                throwIfHandoffRequested(connection)
                return result
            }
        }

    fun abortAll(): Int {
        val snapshot = synchronized(connections) { connections.toList() }
        snapshot.forEach { connection -> runCatching { connection.disconnect() } }
        return snapshot.size
    }

    private fun requestHandoff(
        connection: HttpURLConnection,
        signal: AtomicBoolean,
        disconnect: (HttpURLConnection) -> Unit = { it.disconnect() },
    ): Boolean {
        val requested = signal.compareAndSet(false, true)
        if (requested) runCatching { disconnect(connection) }
        return requested
    }
}
