package com.glancemap.glancemapcompanionapp.transfer.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.WatchNode
import com.glancemap.shared.transfer.WifiTransferContract
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class WifiWatchRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock =
        wifiManager.createMulticastLock("GlanceMap:WifiWatchDiscovery").apply {
            setReferenceCounted(false)
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nodes = ConcurrentHashMap<String, WatchNode>()
    private val _watches = MutableStateFlow<List<WatchNode>>(emptyList())
    val watches: StateFlow<List<WatchNode>> = _watches.asStateFlow()

    @Volatile
    private var discovering = false

    @Volatile
    private var udpJob: Job? = null

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                Log.w(TAG, "mDNS discovery start failed code=$errorCode")
                discovering = false
                if (multicastLock.isHeld) multicastLock.release()
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                discovering = false
                if (multicastLock.isHeld) multicastLock.release()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
                if (multicastLock.isHeld) multicastLock.release()
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (discovering) resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS service temporarily lost name=${info.serviceName}; keeping last endpoint")
            }
        }

    fun start() {
        if (discovering) return
        multicastLock.acquire()
        discovering = true
        val wifiNetwork = wifiNetwork()
        runCatching {
            nsdManager.discoverServices(
                WifiTransferContract.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure {
            discovering = false
            if (multicastLock.isHeld) multicastLock.release()
            Log.w(TAG, "mDNS discovery failed", it)
        }
        startUdpDiscovery(wifiNetwork)
    }

    fun refresh() {
        if (!discovering) start()
        if (udpJob?.isActive != true) startUdpDiscovery(wifiNetwork())
    }

    fun stop() {
        if (discovering) {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            discovering = false
            if (multicastLock.isHeld) multicastLock.release()
        }
        udpJob?.cancel()
        udpJob = null
        nodes.clear()
        publish()
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? =
        connectivity.allNetworks.firstOrNull { network ->
            connectivity
                .getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    private fun resolve(info: NsdServiceInfo) {
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "mDNS resolve failed name=${info.serviceName} code=$errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (!discovering) return
                    val endpoint =
                        info.host?.hostAddress?.let { host ->
                            info.attributes[WifiTransferContract.TXT_TOKEN]
                                ?.let { String(it, Charsets.UTF_8) }
                                ?.takeIf { it.isNotBlank() }
                                ?.let { token -> WifiWatchEndpoint(host, info.port, token) }
                        } ?: return
                    val name =
                        info.attributes[WifiTransferContract.TXT_NAME]
                            ?.let { String(it, Charsets.UTF_8) }
                            ?.takeIf { it.isNotBlank() }
                            ?: info.serviceName
                    Log.d(
                        TAG,
                        "Resolved id=${endpoint.encode()} name=$name host=${endpoint.host} port=${info.port}",
                    )
                    addNode("mdns:${info.serviceName}", endpoint, name)
                }
            }
        runCatching { nsdManager.resolveService(info, listener) }
            .onFailure { Log.w(TAG, "mDNS resolve call failed name=${info.serviceName}", it) }
    }

    private fun startUdpDiscovery(network: Network?) {
        if (udpJob?.isActive == true) return
        udpJob =
            scope.launch(Dispatchers.IO) {
                val socket = DatagramSocket()
                try {
                    if (network != null) network.bindSocket(socket)
                    socket.broadcast = true
                    socket.soTimeout = 1_000
                    val request =
                        WifiTransferContract.UDP_DISCOVERY_REQUEST.toByteArray(Charsets.UTF_8)
                    val broadcast = broadcastAddress(network) ?: InetAddress.getByName("255.255.255.255")
                    while (isActive) {
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    request,
                                    request.size,
                                    broadcast,
                                    WifiTransferContract.UDP_DISCOVERY_PORT,
                                ),
                            )
                        }
                        val deadline = SystemClock.elapsedRealtime() + 1_500
                        while (isActive && SystemClock.elapsedRealtime() < deadline) {
                            val buffer = ByteArray(1_024)
                            val packet = DatagramPacket(buffer, buffer.size)
                            val received =
                                try {
                                    socket.receive(packet)
                                } catch (_: SocketTimeoutException) {
                                    null
                                } catch (t: IOException) {
                                    if (isActive) Log.w(TAG, "UDP receive failed", t)
                                    null
                                }
                            if (received != null) handleUdpReply(packet)
                        }
                        delay(500L)
                    }
                } catch (t: IOException) {
                    if (isActive) Log.w(TAG, "UDP discovery stopped", t)
                } finally {
                    runCatching { socket.close() }
                }
            }
    }

    private fun handleUdpReply(packet: DatagramPacket) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val watch =
            runCatching {
                val json = JsonParser.parseString(text).asJsonObject
                val name = json.get("name")?.asString?.takeIf { it.isNotBlank() } ?: error("name")
                val port = json.get("port")?.asInt?.takeIf { it > 0 } ?: error("port")
                val token = json.get("token")?.asString?.takeIf { it.isNotBlank() } ?: error("token")
                val host = packet.address?.hostAddress ?: error("host")
                UdpWatch(name, host, port, token)
            }.getOrNull() ?: return
        addNode(
            "udp:${watch.host}:${watch.port}",
            WifiWatchEndpoint(watch.host, watch.port, watch.token),
            watch.name,
        )
    }

    private data class UdpWatch(
        val name: String,
        val host: String,
        val port: Int,
        val token: String,
    )

    private fun addNode(
        key: String,
        endpoint: WifiWatchEndpoint,
        name: String,
    ) {
        nodes.keys
            .filter { existingKey -> existingKey != key && nodes[existingKey]?.displayName == name }
            .forEach { existingKey -> nodes.remove(existingKey) }
        nodes[key] = WatchNode(endpoint.encode(), name)
        publish()
    }

    private fun broadcastAddress(network: Network?): InetAddress? =
        network
            ?.let { connectivity.getLinkProperties(it) }
            ?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.let { linkAddress ->
                val prefixLength = linkAddress.prefixLength
                val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
                val address = (linkAddress.address as Inet4Address).address
                val broadcast = ByteBuffer.wrap(address).int or mask.inv()
                InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcast).array())
            }

    private fun publish() {
        _watches.value = nodes.values.sortedBy { it.displayName.lowercase() }
    }

    private companion object {
        const val TAG = "WifiWatchRepo"
    }
}
