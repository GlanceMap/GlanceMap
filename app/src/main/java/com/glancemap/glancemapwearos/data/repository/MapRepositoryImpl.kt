package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class MapRepositoryImpl(
    private val context: Context,
) {
    private val mapDir by lazy { context.getDir("maps", Context.MODE_PRIVATE) }

    suspend fun listMapFiles(): List<File> =
        withContext(Dispatchers.IO) {
            if (!mapDir.exists()) return@withContext emptyList()

            mapDir
                .listFiles { _, name -> name.endsWith(".map", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

    suspend fun fileExists(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = File(fileName).name
            File(mapDir, safeName).exists()
        }

    @Suppress("LongParameterList")
    suspend fun saveMapFileAtomic(
        fileName: String,
        inputStream: InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
        computeSha256: Boolean = true,
    ): String? =
        withContext(Dispatchers.IO) {
            val exp = expectedSize?.takeIf { it > 0L }

            val options =
                AtomicStreamWriter.Options(
                    bufferSize = 2 * 1024 * 1024, // ✅ maps: larger write chunks reduce overhead
                    progressStepBytes = 8L * 1024 * 1024, // ✅ maps: fewer callbacks
                    fsync = true,
                    failIfExists = false,
                    expectedSize = exp,
                    requireExactSize = (exp != null),
                    resumeOffset = resumeOffset.coerceAtLeast(0L),
                    keepPartialOnCancel = true,
                    keepPartialOnFailure = true,
                    computeSha256 = computeSha256,
                )

            val result =
                AtomicStreamWriter.writeAtomic(
                    dir = mapDir,
                    fileName = fileName,
                    inputStream = inputStream,
                    onProgress = onProgress,
                    options = options,
                )

            result.sha256
        }

    suspend fun deleteMapFile(path: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(path)
            val isInsideDir = file.canonicalFile.parentFile?.canonicalPath == mapDir.canonicalPath
            val ok = if (file.exists() && isInsideDir) file.delete() else false

            // delete hidden part too
            File(mapDir, ".${file.name}.part").delete()

            ok
        }

    suspend fun renameMapFile(
        path: String,
        newName: String,
    ): File =
        withContext(Dispatchers.IO) {
            val sourceFile = File(path)
            require(sourceFile.exists()) { "The map could not be found." }

            val isInsideDir = sourceFile.canonicalFile.parentFile?.canonicalPath == mapDir.canonicalPath
            require(isInsideDir) { "The map is outside the app storage." }

            val normalizedTitle =
                newName
                    .trim()
                    .replace(Regex("\\s+"), " ")
            require(normalizedTitle.isNotBlank()) { "Enter a map name first." }

            val targetFileName = buildRenamedMapFileName(normalizedTitle)
            val targetMatchesSource = targetFileName.equals(sourceFile.name, ignoreCase = true)
            if (!targetMatchesSource && fileExists(targetFileName)) {
                error("A map with that name already exists.")
            }

            val targetFile = File(mapDir, targetFileName)
            if (!targetMatchesSource) {
                if (!sourceFile.renameTo(targetFile)) {
                    sourceFile.copyTo(targetFile, overwrite = true)
                    require(sourceFile.delete()) { "The original map could not be removed after renaming." }
                }

                val sourcePartial = File(mapDir, ".${sourceFile.name}.part")
                if (sourcePartial.exists()) {
                    val targetPartial = File(mapDir, ".${targetFile.name}.part")
                    if (!sourcePartial.renameTo(targetPartial)) {
                        sourcePartial.copyTo(targetPartial, overwrite = true)
                        sourcePartial.delete()
                    }
                }
            }

            targetFile
        }

    private fun buildRenamedMapFileName(name: String): String {
        val sanitized = File(name).name.removeSuffix(".map").trim()
        require(sanitized.isNotBlank()) { "Enter a map name first." }
        return "$sanitized.map"
    }
}
