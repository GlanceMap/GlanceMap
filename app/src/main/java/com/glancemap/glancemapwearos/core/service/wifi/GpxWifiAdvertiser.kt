package com.glancemap.glancemapwearos.core.service.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.glancemap.shared.transfer.WifiTransferContract

class GpxWifiAdvertiser(
    context: Context,
    private val serviceName: String,
    private val port: Int,
    private val token: String,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock =
        wifiManager.createMulticastLock("GlanceMap:GpxWifiAdvertiser").apply {
            setReferenceCounted(false)
        }

    private val listener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(
                info: NsdServiceInfo,
                errorCode: Int,
            ) {
                Log.w(TAG, "mDNS registration failed code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                info: NsdServiceInfo,
                errorCode: Int,
            ) = Unit
        }

    private var registered = false

    @Suppress("TooGenericExceptionCaught") // NsdManager may throw platform-specific runtime exceptions.
    fun start() {
        if (registered) return
        multicastLock.acquire()
        val serviceNameValue = serviceName
        val servicePort = port
        val info =
            NsdServiceInfo().apply {
                this.serviceName = serviceNameValue
                serviceType = WifiTransferContract.SERVICE_TYPE
                this.port = servicePort
                setAttribute(WifiTransferContract.TXT_VERSION, "1")
                setAttribute(WifiTransferContract.TXT_NAME, serviceNameValue)
                setAttribute(WifiTransferContract.TXT_TOKEN, token)
            }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registered = true
        } catch (t: Throwable) {
            if (multicastLock.isHeld) multicastLock.release()
            throw t
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { nsdManager.unregisterService(listener) }
        registered = false
        if (multicastLock.isHeld) multicastLock.release()
    }

    private companion object {
        const val TAG = "GpxWifiAdvertiser"
    }
}
