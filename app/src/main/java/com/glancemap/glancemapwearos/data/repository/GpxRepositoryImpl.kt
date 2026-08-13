package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class GpxRepositoryImpl(
    private val context: Context,
) {
    private val gpxDir by lazy { context.getDir("gpx", Context.MODE_PRIVATE) }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("gpx_metadata", Context.MODE_PRIVATE)
    }

    private companion object {
        const val KEY_ACTIVE_GPX_FILES = "active_gpx_files"
    }

    suspend fun listGpxFiles(): List<File> =
        withContext(Dispatchers.IO) {
            if (!gpxDir.exists()) return@withContext emptyList()

            gpxDir
                .listFiles { _, name -> name.endsWith(".gpx", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

    suspend fun saveGpxFileAtomic(
        fileName: String,
        inputStream: InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
    ): String? =
        withContext(Dispatchers.IO) {
            val exp = expectedSize?.takeIf { it > 0L }

            val options =
                AtomicStreamWriter.Options(
                    bufferSize = 256 * 1024,
                    progressStepBytes = 1L * 1024 * 1024,
                    fsync = true,
                    failIfExists = false,
                    expectedSize = exp,
                    requireExactSize = (exp != null),
                    resumeOffset = resumeOffset.coerceAtLeast(0L),
                    keepPartialOnCancel = true, // keep .part on pause
                    keepPartialOnFailure = true, // keep .part on recoverable IO failure
                    computeSha256 = true,
                )

            val result =
                AtomicStreamWriter.writeAtomic(
                    dir = gpxDir,
                    fileName = fileName,
                    inputStream = inputStream,
                    onProgress = onProgress,
                    options = options,
                )

            result.sha256
        }

    suspend fun fileExists(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = File(fileName).name
            File(gpxDir, safeName).exists()
        }

    suspend fun deleteGpxFile(path: String) =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext

            val isInsideDir = file.canonicalFile.parentFile?.canonicalPath == gpxDir.canonicalPath
            if (!isInsideDir) return@withContext

            val activePaths = prefs.getStringSet(KEY_ACTIVE_GPX_FILES, emptySet()) ?: emptySet()
            if (activePaths.contains(path)) {
                setActiveGpxFiles(activePaths - path)
            }

            file.delete()

            // delete hidden part too
            File(gpxDir, ".${file.name}.part").delete()
        }

    suspend fun absolutePathForFileName(fileName: String): String =
        withContext(Dispatchers.IO) {
            File(gpxDir, File(fileName).name).absolutePath
        }

    fun getActiveGpxFiles(): Flow<Set<String>> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                    if (key == KEY_ACTIVE_GPX_FILES) {
                        trySend(sp.getStringSet(KEY_ACTIVE_GPX_FILES, emptySet()) ?: emptySet())
                    }
                }

            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(prefs.getStringSet(KEY_ACTIVE_GPX_FILES, emptySet()) ?: emptySet())

            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    suspend fun setActiveGpxFiles(paths: Set<String>) =
        withContext(Dispatchers.IO) {
            prefs.edit().putStringSet(KEY_ACTIVE_GPX_FILES, paths.toSet()).apply()
        }
}
