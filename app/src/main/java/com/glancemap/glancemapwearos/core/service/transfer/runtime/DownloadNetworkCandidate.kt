package com.glancemap.glancemapwearos.core.service.transfer.runtime

internal data class DownloadNetworkCandidate<T>(
    val network: T,
    val validated: Boolean,
    val vpn: Boolean = false,
    val wifi: Boolean = false,
    val transport: String = "unknown",
)

internal fun <T> selectDownloadNetwork(
    wifi: DownloadNetworkCandidate<T>?,
    fallback: DownloadNetworkCandidate<T>?,
): T? = selectDownloadNetworkCandidate(wifi, fallback)?.network

internal fun <T> selectDownloadNetworkCandidate(
    wifi: DownloadNetworkCandidate<T>?,
    fallback: DownloadNetworkCandidate<T>?,
): DownloadNetworkCandidate<T>? =
    when {
        fallback?.vpn == true -> fallback.takeIf { it.validated }
        wifi?.validated == true -> wifi
        fallback?.validated == true -> fallback
        else -> null
    }
