package com.glancemap.glancemapwearos.core.service.transfer.runtime

/** An initial Wi-Fi connection needs no reconnect; each later recovery can hand off once. */
internal class DownloadWifiHandoff {
    private var previousWifi: Boolean? = null

    fun onChanged(wifi: Boolean?): Boolean {
        if (wifi == null) {
            if (previousWifi != null) previousWifi = false
            return false
        }
        val recovered = previousWifi == false && wifi
        previousWifi = wifi
        return recovered
    }

    fun reset() {
        previousWifi = null
    }
}
