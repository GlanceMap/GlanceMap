package com.glancemap.glancemapwearos.presentation.features.download

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal class OamDownloadConnections(
    private val connectionFactory: suspend (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun metadata(url: String): HttpURLConnection = open(url).apply { requestMethod = "HEAD" }

    suspend fun download(
        url: String,
        resumeOffset: Long,
    ): HttpURLConnection =
        open(url).apply {
            requestMethod = "GET"
            if (resumeOffset > 0L) setRequestProperty("Range", "bytes=$resumeOffset-")
        }

    private suspend fun open(url: String): HttpURLConnection =
        connectionFactory(URI(url).toURL()).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "GlanceMap-WearOS-OAM-Downloader/1.0 https://www.openandromaps.org")
        }
}
