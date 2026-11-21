package com.glancemap.glancemapwearos.core.service.wifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.security.SecureRandom

class GpxWifiReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var connectivity: ConnectivityManager

    @Volatile
    private var server: GpxWifiUploadServer? = null

    @Volatile
    private var advertiser: GpxWifiAdvertiser? = null

    @Volatile
    private var udpDiscovery: GpxWifiUdpDiscovery? = null

    @Volatile
    private var token: String = ""

    @Volatile
    private var startJob: Job? = null

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = startServerIfPossible()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) = startServerIfPossible()

            override fun onLost(network: Network) = stopServer()
        }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        createChannel()
        startForegroundNotification(getString(R.string.gpx_wifi_waiting_for_wifi))
        connectivity.registerNetworkCallback(
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            networkCallback,
        )
        startServerIfPossible()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startServerIfPossible()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        stopServer()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("TooGenericExceptionCaught") // NsdManager may throw platform-specific runtime exceptions.
    private fun startServerIfPossible() {
        if (server != null) return
        startJob?.takeIf { it.isActive }?.let { return }
        startJob =
            scope.launch {
                runCatching {
                    val host = wifiIpv4(connectivity) ?: return@launch
                    val serviceName = deviceName(contentResolver)
                    val app = application as GlanceMapWearApp
                    val newToken = GpxWifiProtocol.generateToken(SecureRandom())
                    val newServer =
                        GpxWifiUploadServer(
                            scope = scope,
                            cacheDir = cacheDir,
                            gpxRepository = app.container.gpxRepository,
                            token = newToken,
                            onSaved = { app.container.syncManager.requestGpxSync() },
                        )
                    newServer.start()
                    val newAdvertiser =
                        GpxWifiAdvertiser(
                            context = this@GpxWifiReceiveService,
                            serviceName = serviceName,
                            port = newServer.port,
                            token = newToken,
                        )
                    try {
                        newAdvertiser.start()
                    } catch (t: Throwable) {
                        newServer.stop()
                        throw t
                    }
                    val newUdpDiscovery =
                        GpxWifiUdpDiscovery(
                            scope = scope,
                            serviceName = serviceName,
                            httpPort = newServer.port,
                            token = newToken,
                        )
                    runCatching { newUdpDiscovery.start() }
                        .onFailure { Log.w(TAG, "UDP discovery unavailable", it) }
                    token = newToken
                    server = newServer
                    advertiser = newAdvertiser
                    udpDiscovery = newUdpDiscovery
                    updateNotification(
                        getString(
                            R.string.gpx_wifi_ready,
                            GpxWifiProtocol.uploadPageUrl(host, newServer.port, newToken),
                        ),
                    )
                }.onFailure {
                    Log.e(TAG, "Could not start GPX Wi-Fi receiver", it)
                    stopServer()
                }
            }
    }

    private fun stopServer() {
        udpDiscovery?.stop()
        udpDiscovery = null
        advertiser?.stop()
        advertiser = null
        server?.stop()
        server = null
        token = ""
        updateNotification(getString(R.string.gpx_wifi_waiting_for_wifi))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gpx_wifi_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(text: String): Notification {
        val wifiIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(Settings.ACTION_WIFI_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.gpx_wifi_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.gpx_wifi_open_wifi_settings), wifiIntent)
            .build()
    }

    private fun startForegroundNotification(text: String) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private companion object {
        const val TAG = "GpxWifiReceiveService"
        const val CHANNEL_ID = "gpx_wifi_receive"
        const val NOTIFICATION_ID = 12001
    }
}

private fun deviceName(resolver: ContentResolver): String =
    runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

private fun wifiIpv4(connectivity: ConnectivityManager): String? =
    connectivity.allNetworks
        .firstOrNull { network ->
            connectivity
                .getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }?.let { network -> connectivity.getLinkProperties(network) }
        ?.linkAddresses
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
