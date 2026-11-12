package com.glancemap.glancemapcompanionapp.transfer.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.LinkedHashMap

private const val CACHE_MAX_ENTRIES = 32
private const val HASH_UPDATE_INTERVAL_MS = 500L
private const val HASH_UPDATE_STEP_BYTES = 8L * 1024L * 1024L

internal object Sha256TransferUtils {
    private val cacheLock = Any()
    private val sha256Cache =
        object : LinkedHashMap<String, String>(CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX_ENTRIES
        }

    @Suppress("TooGenericExceptionCaught") // ContentResolver streams can fail with provider-specific exceptions.
    suspend fun computeSha256(
        context: Context,
        uri: Uri,
        options: Sha256Options = Sha256Options(),
    ): String? {
        var bytesHashed = 0L
        var input: InputStream? = null
        return try {
            input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                options.diagnosticContext?.let {
                    PhoneTransferDiagnostics.error(
                        "Hash",
                        "event=hash_failed $it expectedBytes=${expectedSize(options.knownSize)} " +
                            "bytesHashed=0 checksumAvailable=no streamOpen=no result=failed reason=stream_open_failed",
                    )
                }
                return null
            }
            input.use { stream ->
                computeSha256FromStream(
                    input = stream,
                    options =
                        options.copy(
                            onBytesHashed = { count ->
                                bytesHashed = count
                                options.onBytesHashed(count)
                            },
                        ),
                )
            }
        } catch (cancelled: CancellationException) {
            options.diagnosticContext?.let {
                PhoneTransferDiagnostics.warn(
                    "Hash",
                    "event=hash_failed $it expectedBytes=${expectedSize(options.knownSize)} " +
                        "bytesHashed=$bytesHashed checksumAvailable=no streamOpen=${input != null} " +
                        "result=failed reason=cancelled",
                )
            }
            throw cancelled
        } catch (error: Exception) {
            options.diagnosticContext?.let {
                PhoneTransferDiagnostics.error(
                    "Hash",
                    "event=hash_failed $it expectedBytes=${expectedSize(options.knownSize)} " +
                        "bytesHashed=$bytesHashed checksumAvailable=no streamOpen=${input != null} " +
                        "result=failed reason=${error.javaClass.simpleName}",
                )
            }
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // InputStream and progress callbacks may throw platform-specific failures.
    internal suspend fun computeSha256FromStream(
        input: InputStream,
        options: Sha256Options = Sha256Options(),
    ): String? {
        var copied = 0L
        return try {
            val reportBytesHashed = options.onBytesHashed
            val checksum =
                hashStream(
                    input,
                    options.copy(
                        onBytesHashed = { count ->
                            copied = count
                            reportBytesHashed(count)
                        },
                    ),
                )
            options.diagnosticContext?.let {
                PhoneTransferDiagnostics.log(
                    "Hash",
                    "event=hash_complete $it expectedBytes=${expectedSize(options.knownSize)} " +
                        "bytesHashed=$copied checksumAvailable=yes source=stream result=success",
                )
            }
            checksum
        } catch (cancelled: CancellationException) {
            options.diagnosticContext?.let {
                PhoneTransferDiagnostics.warn(
                    "Hash",
                    "event=hash_failed $it expectedBytes=${expectedSize(options.knownSize)} " +
                        "bytesHashed=$copied checksumAvailable=no streamOpen=yes result=failed reason=cancelled",
                )
            }
            throw cancelled
        } catch (error: Exception) {
            options.diagnosticContext?.let {
                PhoneTransferDiagnostics.error(
                    "Hash",
                    "event=hash_failed $it expectedBytes=${expectedSize(options.knownSize)} " +
                        "bytesHashed=$copied checksumAvailable=no streamOpen=yes result=failed " +
                        "reason=${hashFailureReason(error)}",
                )
            }
            null
        }
    }

    suspend fun computeSha256Cached(
        context: Context,
        uri: Uri,
        knownDisplayName: String? = null,
        hashOptions: Sha256Options = Sha256Options(),
    ): String? {
        val metadata = queryShaCacheMetadata(context, uri)
        val effectiveSize = hashOptions.knownSize ?: metadata.size
        val cacheKey =
            buildShaCacheKey(
                uri = uri,
                displayName = knownDisplayName ?: metadata.displayName,
                size = effectiveSize,
                lastModified = metadata.lastModified,
            )
        val cached = synchronized(cacheLock) { sha256Cache[cacheKey] }
        if (cached != null) {
            hashOptions.onProgress?.invoke(1f, "Checksum ready (cached)")
            hashOptions.diagnosticContext?.let {
                PhoneTransferDiagnostics.log(
                    "Hash",
                    "event=hash_complete $it expectedBytes=${expectedSize(effectiveSize)} " +
                        "bytesHashed=na checksumAvailable=yes source=cache result=success",
                )
            }
        }
        if (cached == null) hashOptions.onProgress?.invoke(0f, "Computing checksum…")

        val computed =
            cached ?: computeSha256(
                context = context,
                uri = uri,
                options = hashOptions.copy(knownSize = effectiveSize),
            ) ?: return null
        if (cached == null) synchronized(cacheLock) { sha256Cache[cacheKey] = computed }
        return computed
    }
}

private suspend fun hashStream(
    input: InputStream,
    options: Sha256Options,
): String {
    val expectedBytes = options.knownSize?.takeIf { it >= 0L }
    val digest = MessageDigest.getInstance("SHA-256")
    val coroutineContext = currentCoroutineContext()
    var copied = 0L
    var lastUiBytes = 0L
    var lastUiMs = 0L
    val buffer = ByteArray(256 * 1024)

    options.onProgress?.invoke(0f, formatHashProgress(copied, expectedBytes))
    while (true) {
        coroutineContext.ensureActive()
        options.awaitIfPaused?.invoke()
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) {
            if (expectedBytes != null && read.toLong() > expectedBytes - copied) {
                throw IOException("unexpected_extra_data expectedBytes=$expectedBytes bytesHashed=$copied")
            }

            digest.update(buffer, 0, read)
            copied += read
            options.onBytesHashed(copied)

            val now = options.elapsedRealtimeMs()
            if (shouldPublishHashProgress(now, lastUiMs, copied, lastUiBytes)) {
                lastUiMs = now
                lastUiBytes = copied
                val progress = hashProgress(copied, expectedBytes)
                options.onProgress?.invoke(progress, formatHashProgress(copied, expectedBytes))
            }
        }
    }
    if (expectedBytes != null && copied != expectedBytes) {
        throw IOException("unexpected_eof expectedBytes=$expectedBytes bytesHashed=$copied")
    }

    options.onProgress?.invoke(1f, formatHashProgress(copied, expectedBytes))
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun shouldPublishHashProgress(
    nowMs: Long,
    lastUiMs: Long,
    copiedBytes: Long,
    lastUiBytes: Long,
): Boolean =
    (nowMs - lastUiMs) >= HASH_UPDATE_INTERVAL_MS ||
        (copiedBytes - lastUiBytes) >= HASH_UPDATE_STEP_BYTES

private fun hashProgress(
    copiedBytes: Long,
    expectedBytes: Long?,
): Float =
    if (expectedBytes != null && expectedBytes > 0L) {
        (copiedBytes.toDouble() / expectedBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }

private fun queryShaCacheMetadata(
    context: Context,
    uri: Uri,
): ShaCacheMetadata =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use ShaCacheMetadata(displayName = null, size = null, lastModified = null)
            }

            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val displayName =
                if (nameIndex != -1 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
            val size = if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            val lastModified =
                if (modifiedIndex != -1 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null

            ShaCacheMetadata(displayName = displayName, size = size, lastModified = lastModified)
        } ?: ShaCacheMetadata(displayName = null, size = null, lastModified = null)
    }.getOrElse { ShaCacheMetadata(displayName = null, size = null, lastModified = null) }

private fun buildShaCacheKey(
    uri: Uri,
    displayName: String?,
    size: Long?,
    lastModified: Long?,
): String =
    buildString {
        append(uri.toString())
        append('|')
        append(displayName ?: "")
        append('|')
        append(size ?: -1L)
        append('|')
        append(lastModified ?: -1L)
    }

private fun formatHashProgress(
    copied: Long,
    total: Long?,
): String =
    if (total != null && total > 0L) {
        "Checksum: ${TransferUtils.formatBytes(copied)} / ${TransferUtils.formatBytes(total)}"
    } else {
        "Checksum: ${TransferUtils.formatBytes(copied)}"
    }

private fun expectedSize(size: Long?): String = size?.takeIf { it >= 0L }?.toString() ?: "na"

private fun hashFailureReason(error: Throwable): String =
    when {
        error.message?.startsWith("unexpected_eof") == true -> "unexpected_eof"
        error.message?.startsWith("unexpected_extra_data") == true -> "unexpected_extra_data"
        else -> error.javaClass.simpleName
    }

private data class ShaCacheMetadata(
    val displayName: String?,
    val size: Long?,
    val lastModified: Long?,
)
