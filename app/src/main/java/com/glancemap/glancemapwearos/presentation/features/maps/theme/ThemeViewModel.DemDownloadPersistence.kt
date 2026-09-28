package com.glancemap.glancemapwearos.presentation.features.maps.theme

import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.service.diagnostics.DemDownloadDiagnostics
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection

internal fun copyDemResponse(
    connection: HttpURLConnection,
    context: DemDownloadContext,
    response: DemDownloadResponse,
    onProgress: (bytesDone: Long, totalBytes: Long?) -> Unit = { _, _ -> },
) {
    val startingBytes = if (response.append) context.resumeOffset else 0L
    connection.inputStream.use { input ->
        FileOutputStream(context.part, response.append).use { out ->
            copyDemBytes(
                input = input,
                output = out,
                startingBytes = startingBytes,
                expectedTotalBytes = response.expectedTotalBytes,
                onProgress = onProgress,
            )
            out.flush()
            runCatching { out.fd.sync() }
        }
    }
    requireCompleteDemPart(context = context, expectedTotalBytes = response.expectedTotalBytes)
}

private fun copyDemBytes(
    input: InputStream,
    output: OutputStream,
    startingBytes: Long,
    expectedTotalBytes: Long?,
    onProgress: (bytesDone: Long, totalBytes: Long?) -> Unit,
): Long {
    var bytesDone = startingBytes
    var lastReportedBytes = startingBytes
    val buffer = ByteArray(DEM_DOWNLOAD_BUFFER_SIZE_BYTES)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        bytesDone += read
        val isComplete = expectedTotalBytes?.let { bytesDone >= it } == true
        if (bytesDone - lastReportedBytes >= DEM_PROGRESS_STEP_BYTES || isComplete) {
            onProgress(bytesDone, expectedTotalBytes)
            lastReportedBytes = bytesDone
        }
    }
    if (bytesDone != lastReportedBytes) onProgress(bytesDone, expectedTotalBytes)
    return bytesDone
}

internal fun promoteAndValidateDemPart(context: DemDownloadContext) {
    context.target.delete()
    if (!context.part.renameTo(context.target)) {
        throw IOException("Failed moving temp DEM file to ${context.target.absolutePath}")
    }
    runCatching { validateDemTileFile(context.target) }
        .onFailure { error ->
            recordDemValidationFailure(context = context, error = error)
            context.target.delete()
        }.getOrThrow()
}

internal fun recordSavedDemTile(
    context: DemDownloadContext,
    response: DemDownloadResponse,
) {
    val resumedFromBytes = if (response.append) context.resumeOffset else 0L
    DemDownloadDiagnostics.record(
        event = "tile_saved",
        detail =
            "source=${context.sourceId} tile=${context.tileName} code=${response.code} " +
                "bytes=${context.target.length()} " +
                "resumedFromBytes=$resumedFromBytes",
    )
}

internal fun createMissingDemMarker(
    target: File,
    demRoot: File,
) {
    val tileId =
        target.name
            .removeSuffix(".hgt.zip")
            .removeSuffix(".hgt.gz")
            .removeSuffix(".hgt")
    val marker =
        Dem3CoverageUtils
            .missingTileMarkerCandidates(demRoot = demRoot, tileId = tileId)
            .firstOrNull { it.parentFile == target.parentFile }
            ?: File(target.parentFile ?: demRoot, "$tileId.hgt.missing")
    marker.parentFile?.mkdirs()
    marker.writeText("missing_upstream\n")
}

private fun requireCompleteDemPart(
    context: DemDownloadContext,
    expectedTotalBytes: Long?,
) {
    if (expectedTotalBytes == null || context.part.length() == expectedTotalBytes) return
    DemDownloadDiagnostics.record(
        event = "tile_incomplete",
        detail =
            "source=${context.sourceId} tile=${context.tileName} expectedBytes=$expectedTotalBytes " +
                "actualBytes=${context.part.length()}",
    )
    throw IOException("INCOMPLETE_DEM_TILE: expected=$expectedTotalBytes got=${context.part.length()}")
}

private fun recordDemValidationFailure(
    context: DemDownloadContext,
    error: Throwable,
) {
    DemDownloadDiagnostics.record(
        event = "tile_validation_failed",
        detail =
            "source=${context.sourceId} tile=${context.tileName} bytes=${context.target.length()} " +
                "error=${error.javaClass.simpleName} message=${error.message.orEmpty().demDiagValue()}",
    )
}

private const val DEM_DOWNLOAD_BUFFER_SIZE_BYTES = 32 * 1024
private const val DEM_PROGRESS_STEP_BYTES = 64 * 1024L
