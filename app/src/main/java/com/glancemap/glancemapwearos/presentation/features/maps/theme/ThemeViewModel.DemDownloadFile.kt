package com.glancemap.glancemapwearos.presentation.features.maps.theme

import com.glancemap.glancemapwearos.core.service.diagnostics.DemDownloadDiagnostics
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal data class DemDownloadContext(
    val sourceId: String,
    val url: String,
    val target: File,
    val part: File,
    val tileName: String,
    val resumeOffset: Long,
)

internal data class DemDownloadResponse(
    val code: Int,
    val append: Boolean,
    val expectedTotalBytes: Long?,
)

internal data class DemDownloadRequest(
    val sourceId: String,
    val url: String,
    val target: File,
    val demRoot: File,
    val userAgent: String,
)

internal fun downloadDemFile(
    request: DemDownloadRequest,
    onConnectionOpened: (HttpURLConnection) -> Unit = {},
    onProgress: (bytesDone: Long, totalBytes: Long?) -> Unit = { _, _ -> },
) {
    val context =
        buildDemDownloadContext(
            sourceId = request.sourceId,
            url = request.url,
            target = request.target,
        )
    recordResumeAttempt(context)
    val connection = openDemConnection(context = context, userAgent = request.userAgent)
    onConnectionOpened(connection)
    try {
        val response = prepareDemResponse(connection = connection, context = context, demRoot = request.demRoot)
        val startingBytes = if (response.append) context.resumeOffset else 0L
        onProgress(startingBytes, response.expectedTotalBytes)
        copyDemResponse(
            connection = connection,
            context = context,
            response = response,
            onProgress = onProgress,
        )
        promoteAndValidateDemPart(context)
        recordSavedDemTile(context = context, response = response)
    } finally {
        connection.disconnect()
    }
}

private fun buildDemDownloadContext(
    sourceId: String,
    url: String,
    target: File,
): DemDownloadContext {
    val part = File(target.parentFile, ".${target.name}.part")
    return DemDownloadContext(
        sourceId = sourceId,
        url = url,
        target = target,
        part = part,
        tileName =
            target.name
                .removeSuffix(".hgt.zip")
                .removeSuffix(".hgt.gz")
                .removeSuffix(".hgt"),
        resumeOffset = part.takeIf { it.exists() && it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L,
    )
}

private fun recordResumeAttempt(context: DemDownloadContext) {
    if (context.resumeOffset <= 0L) return
    DemDownloadDiagnostics.record(
        event = "tile_resume_attempt",
        detail =
            "source=${context.sourceId} tile=${context.tileName} partialBytes=${context.resumeOffset} " +
                "url=${context.url.demDiagValue()}",
    )
}

private fun openDemConnection(
    context: DemDownloadContext,
    userAgent: String,
): HttpURLConnection =
    (URI(context.url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = DEM_CONNECT_TIMEOUT_MS
        readTimeout = DEM_READ_TIMEOUT_MS
        requestMethod = "GET"
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", userAgent)
        if (context.resumeOffset > 0L) {
            setRequestProperty("Range", "bytes=${context.resumeOffset}-")
        }
    }

private fun prepareDemResponse(
    connection: HttpURLConnection,
    context: DemDownloadContext,
    demRoot: File,
): DemDownloadResponse {
    val code = connection.responseCode
    handleDemHttpError(code = code, context = context, demRoot = demRoot)
    val append = context.resumeOffset > 0L && code == HttpURLConnection.HTTP_PARTIAL
    if (context.resumeOffset > 0L && code == HttpURLConnection.HTTP_OK && context.part.exists()) {
        recordDemResumeRestart(context)
        context.part.delete()
    }
    val expectedTotalBytes =
        connection.contentLengthLong
            .takeIf { it > 0L }
            ?.let { contentLength -> (if (append) context.resumeOffset else 0L) + contentLength }
    return DemDownloadResponse(code = code, append = append, expectedTotalBytes = expectedTotalBytes)
}

private fun handleDemHttpError(
    code: Int,
    context: DemDownloadContext,
    demRoot: File,
) {
    if (code in 200..299) return
    when (code) {
        HttpURLConnection.HTTP_NOT_FOUND -> markDemTileMissing(context = context, demRoot = demRoot)
        HTTP_REQUESTED_RANGE_NOT_SATISFIABLE -> rejectDemResumeIfNeeded(context)
        else -> recordDemHttpFailure(context = context, code = code)
    }
}

private fun markDemTileMissing(
    context: DemDownloadContext,
    demRoot: File,
) {
    context.part.delete()
    DemDownloadDiagnostics.record(
        event = "tile_http",
        detail =
            "source=${context.sourceId} tile=${context.tileName} code=404 action=missing " +
                "url=${context.url.demDiagValue()}",
    )
    createMissingDemMarker(target = context.target, demRoot = demRoot)
    throw FileNotFoundException("HTTP 404 for ${context.url}")
}

private fun rejectDemResumeIfNeeded(context: DemDownloadContext) {
    if (context.resumeOffset <= 0L) return
    context.part.delete()
    DemDownloadDiagnostics.record(
        event = "tile_resume_rejected",
        detail =
            "source=${context.sourceId} tile=${context.tileName} code=$HTTP_REQUESTED_RANGE_NOT_SATISFIABLE " +
                "partialBytes=${context.resumeOffset} url=${context.url.demDiagValue()}",
    )
    throw DemResumeRejectedException("DEM server rejected partial resume for ${context.url}")
}

private fun recordDemHttpFailure(
    context: DemDownloadContext,
    code: Int,
) {
    DemDownloadDiagnostics.record(
        event = "tile_http",
        detail =
            "source=${context.sourceId} tile=${context.tileName} code=$code action=fail " +
                "url=${context.url.demDiagValue()}",
    )
    throw IOException("HTTP $code for ${context.url}")
}

private fun recordDemResumeRestart(context: DemDownloadContext) {
    DemDownloadDiagnostics.record(
        event = "tile_resume_restart",
        detail =
            "source=${context.sourceId} tile=${context.tileName} reason=range_ignored " +
                "partialBytes=${context.resumeOffset} url=${context.url.demDiagValue()}",
    )
}

private const val DEM_CONNECT_TIMEOUT_MS = 20_000
private const val DEM_READ_TIMEOUT_MS = 60_000
private const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416
