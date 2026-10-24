package com.glancemap.glancemapwearos.core.service.transfer.runtime

internal data class DownloadNetworkCandidate<T>(
    val network: T,
    val validated: Boolean,
    val vpn: Boolean = false,
)

internal fun <T> selectDownloadNetwork(
    wifi: DownloadNetworkCandidate<T>?,
    fallback: DownloadNetworkCandidate<T>?,
): T? =
    when {
        fallback?.vpn == true -> fallback.takeIf { it.validated }?.network
        wifi?.validated == true -> wifi.network
        fallback?.validated == true -> fallback.network
        else -> null
    }
