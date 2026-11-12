package com.glancemap.glancemapwearos.presentation.features.download

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal enum class OamResumeResponse { APPEND, RESTART, FRESH, ERROR }

internal fun oamResumeResponse(
    resumeOffset: Long,
    responseCode: Int,
): OamResumeResponse =
    when {
        resumeOffset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL -> OamResumeResponse.APPEND
        resumeOffset > 0 && (responseCode == HttpURLConnection.HTTP_OK || responseCode == 416) ->
            OamResumeResponse.RESTART
        responseCode == HttpURLConnection.HTTP_OK -> OamResumeResponse.FRESH
        else -> OamResumeResponse.ERROR
    }

internal fun oamResumeValidator(
    entityTag: String?,
    lastModified: String?,
    previousValidator: String? = null,
    isAppending: Boolean = false,
): String? =
    entityTag?.trim()?.takeIf { it.startsWith('"') && it.endsWith('"') }
        ?: lastModified?.takeIf { it.isNotBlank() }
        ?: previousValidator.takeIf { isAppending }

internal class OamDownloadConnections(
    private val connectionFactory: suspend (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun metadata(url: String): HttpURLConnection = open(url).apply { requestMethod = "HEAD" }

    suspend fun download(
        url: String,
        resumeOffset: Long,
        resumeValidator: String? = null,
    ): HttpURLConnection =
        open(url).apply {
            requestMethod = "GET"
            if (resumeOffset > 0L) {
                setRequestProperty("Range", "bytes=$resumeOffset-")
                resumeValidator?.let { setRequestProperty("If-Range", it) }
            }
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
