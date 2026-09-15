package com.glancemap.glancemapwearos.data.repository

import com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Owns atomic POI file writes, independent of POI database reads and metadata. */
internal class PoiFileStorage(
    private val poiDir: File,
) {
    fun list(): List<File> =
        if (!poiDir.exists()) {
            emptyList()
        } else {
            poiDir
                .listFiles { _, name -> name.endsWith(".poi", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?: emptyList()
        }

    @Suppress("LongParameterList")
    suspend fun saveAtomic(
        fileName: String,
        inputStream: InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
        expectedSize: Long?,
        resumeOffset: Long,
        diagnosticContext: String?,
    ): String? {
        val expectedBytes = expectedSize?.takeIf { it > 0L }
        val options =
            AtomicStreamWriter.Options(
                bufferSize = 1024 * 1024,
                progressStepBytes = 2L * 1024 * 1024,
                fsync = true,
                failIfExists = false,
                expectedSize = expectedBytes,
                requireExactSize = (expectedBytes != null),
                resumeOffset = resumeOffset.coerceAtLeast(0L),
                keepPartialOnCancel = true,
                keepPartialOnFailure = true,
                computeSha256 = true,
                diagnosticContext = diagnosticContext,
            )
        return AtomicStreamWriter
            .writeAtomic(
                dir = poiDir,
                fileName = fileName,
                inputStream = inputStream,
                onProgress = onProgress,
                options = options,
            ).sha256
    }
}
