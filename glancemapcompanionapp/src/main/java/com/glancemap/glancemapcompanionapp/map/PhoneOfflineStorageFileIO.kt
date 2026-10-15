package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private const val STORAGE_BUFFER_BYTES = 1024 * 1024
private const val STORAGE_PROGRESS_INTERVAL_NANOS = 250_000_000L

internal suspend fun copyPhoneOfflineStorageFile(
    source: File,
    target: File,
    onBytesCopied: suspend (Long) -> Unit = {},
) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, ".${target.name}.part")
    FileInputStream(source).use { input ->
        FileOutputStream(temporary).use { output ->
            copyStorageStream(input, output, onBytesCopied)
        }
    }
    currentCoroutineContext().ensureActive()
    if (target.exists()) target.delete()
    check(temporary.renameTo(target)) { "Could not promote ${source.name}." }
}

private suspend fun copyStorageStream(
    input: InputStream,
    output: OutputStream,
    onBytesCopied: suspend (Long) -> Unit,
) {
    val buffer = ByteArray(STORAGE_BUFFER_BYTES)
    var copied = 0L
    var lastReportedBytes = 0L
    var lastReportedAt = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        copied += count
        val now = System.nanoTime()
        if (lastReportedAt == 0L || now - lastReportedAt >= STORAGE_PROGRESS_INTERVAL_NANOS) {
            onBytesCopied(copied)
            lastReportedBytes = copied
            lastReportedAt = now
        }
    }
    if (copied != lastReportedBytes) onBytesCopied(copied)
}

internal fun hashPhoneOfflineStorageFile(
    file: File,
    checkActive: () -> Unit = {},
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(STORAGE_BUFFER_BYTES)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
