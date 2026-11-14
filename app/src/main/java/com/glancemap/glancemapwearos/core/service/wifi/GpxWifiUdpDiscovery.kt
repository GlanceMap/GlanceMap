package com.glancemap.glancemapwearos.core.service.wifi

import android.util.Log
import com.glancemap.shared.transfer.WifiTransferContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class GpxWifiUdpDiscovery(
    private val scope: CoroutineScope,
    private val serviceName: String,
    private val httpPort: Int,
    private val token: String,
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val udpSocket =
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(WifiTransferContract.UDP_DISCOVERY_PORT))
            }
        socket = udpSocket
        job =
            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(512)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    if (runCatching { udpSocket.receive(packet) }.isFailure) break
                    val request = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (request == WifiTransferContract.UDP_DISCOVERY_REQUEST) {
                        val reply =
                            JSONObject()
                                .put("v", 1)
                                .put("name", serviceName)
                                .put("port", httpPort)
                                .put("token", token)
                                .toString()
                                .toByteArray(Charsets.UTF_8)
                        runCatching {
                            udpSocket.send(
                                DatagramPacket(reply, reply.size, packet.address, packet.port),
                            )
                        }.onFailure {
                            Log.w(TAG, "UDP discovery reply failed", it)
                        }
                    }
                }
            }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "GpxWifiUdpDiscovery"
    }
}
