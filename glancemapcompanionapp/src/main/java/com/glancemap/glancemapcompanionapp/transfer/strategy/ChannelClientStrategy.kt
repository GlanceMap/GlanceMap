package com.glancemap.glancemapcompanionapp.transfer.strategy

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.util.CopyWithProgressOptions
import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils
import com.glancemap.shared.transfer.MapsforgeThemePackage
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class ChannelClientStrategy :
    TransferStrategy,
    AutoCloseable {
    companion object {
        private const val TAG = "ChannelClientStrategy"

        private const val CHANNEL_PREFIX = "/glancemap/file"

        // ✅ Pre-warm message path (watch can ignore it; it still wakes the process)
        private const val PATH_PREPARE_CHANNEL = "/glancemap/prepare_channel"

        private const val OPEN_CHANNEL_TIMEOUT_MS = 20_000L
        private const val OPEN_STREAM_TIMEOUT_MS = 15_000L
        private const val WATCH_ACK_TIMEOUT_MS = 30_000L
        private const val PREWARM_MAX_ATTEMPTS = 3
        private const val PREWARM_RETRY_DELAY_MS = 500L
        private const val PREWARM_POST_SUCCESS_DELAY_MS = 400L
    }

    @Volatile
    private var activeChannelClient: ChannelClient? = null

    @Volatile
    private var activeChannel: ChannelClient.Channel? = null

    @Volatile
    private var activeOutputStream: OutputStream? = null

    override suspend fun transfer(
        context: Context,
        fileUri: Uri,
        targetNodeId: String,
        metadata: TransferMetadata,
        ackDeferred: CompletableDeferred<TransferResult>,
        awaitIfPaused: suspend () -> Unit,
        onProgress: (Float, String) -> Unit,
    ): TransferResult =
        withContext(Dispatchers.IO) {
            val totalStartMs = SystemClock.elapsedRealtime()

            val diagnosticContext =
                "${metadata.diagnosticContext()} file=${metadata.safeFileName} " +
                    "expectedBytes=${metadata.totalSize} checksumAvailable=${!metadata.checksumSha256.isNullOrBlank()}"
            val validationFailure =
                when {
                    metadata.isMapFile && metadata.totalSize <= 0L -> "missing_expected_size"
                    metadata.checksumSha256.isNullOrBlank() -> "missing_expected_checksum"
                    else -> null
                }
            if (validationFailure != null) {
                PhoneTransferDiagnostics.error(
                    "Channel",
                    "event=sender_result $diagnosticContext bytesSent=0 sourceStreamOpen=no " +
                        "result=failed reason=$validationFailure",
                )
                return@withContext TransferResult(false, validationFailure)
            }

            val channelClient = Wearable.getChannelClient(context)
            val messageClient = Wearable.getMessageClient(context)
            activeChannelClient = channelClient

            var input: InputStream? = null
            var sourceStreamOpened = false
            var bytesSent = 0L
            var channel: ChannelClient.Channel? = null

            // Adaptive buffer (Channel is usually BT-limited, but slightly larger helps reduce overhead)
            val bufferBytes =
                when {
                    metadata.totalSize in 1..(512 * 1024L) -> 16 * 1024 // <= 512KB
                    metadata.totalSize in 1..(5 * 1024 * 1024L) -> 64 * 1024 // <= 5MB
                    metadata.totalSize in 1..(30 * 1024 * 1024L) -> 256 * 1024 // <= 30MB
                    else -> 512 * 1024 // ✅ if used above 30MB, keep stable
                }

            try {
                val sourceInput =
                    context.contentResolver.openInputStream(fileUri)
                        ?: throw IOException("source_stream_open_failed")
                input = sourceInput
                sourceStreamOpened = true
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "event=source_stream_open $diagnosticContext sourceStreamOpen=yes",
                )

                // 0) Pre-warm watch
                onProgress(0f, "Waking watch…")
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "event=channel_send_start $diagnosticContext",
                )
                val payload =
                    JSONObject()
                        .apply {
                            put("id", metadata.transferId)
                            put("name", metadata.safeFileName)
                            put("size", metadata.totalSize)
                            metadata.checksumSha256?.let { put("sha256", it) }
                            put("v", 2)
                        }.toString()
                        .toByteArray(Charsets.UTF_8)

                var prewarmSuccess = false
                for (attempt in 1..PREWARM_MAX_ATTEMPTS) {
                    val result =
                        runCatching {
                            messageClient.sendMessage(targetNodeId, PATH_PREPARE_CHANNEL, payload).await()
                        }
                    if (result.isSuccess) {
                        prewarmSuccess = true
                        break
                    } else {
                        val reason = result.exceptionOrNull()?.let(::safeFailureReason) ?: "unknown"
                        Log.d(TAG, "Pre-warm attempt $attempt failed (non-fatal): reason=$reason")
                        PhoneTransferDiagnostics.warn(
                            "Channel",
                            "event=prewarm_failed ${metadata.diagnosticContext()} " +
                                "attempt=$attempt reason=$reason",
                        )
                        if (attempt < PREWARM_MAX_ATTEMPTS) delay(PREWARM_RETRY_DELAY_MS)
                    }
                }
                if (prewarmSuccess) {
                    delay(PREWARM_POST_SUCCESS_DELAY_MS)
                }

                // 1) Open Channel
                val path = "$CHANNEL_PREFIX/${metadata.transferId}/${metadata.safeFileName}"
                val t0 = SystemClock.elapsedRealtime()
                onProgress(0f, "Opening channel…")

                channel =
                    withTimeout(OPEN_CHANNEL_TIMEOUT_MS) {
                        channelClient.openChannel(targetNodeId, path).await()
                    }
                activeChannel = channel

                val openMs = SystemClock.elapsedRealtime() - t0
                Log.d(TAG, "Channel opened in ${openMs}ms")
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "Channel opened file=${metadata.displayFileName} openMs=$openMs",
                )

                // 2) Open OutputStream
                onProgress(0f, "Opening stream…")
                val streamStartMs = SystemClock.elapsedRealtime()
                val rawOut =
                    withTimeout(OPEN_STREAM_TIMEOUT_MS) {
                        channelClient.getOutputStream(channel).await()
                    }
                activeOutputStream = rawOut
                val streamOpenMs = SystemClock.elapsedRealtime() - streamStartMs

                // 3) Copy
                val copyStartMs = SystemClock.elapsedRealtime()
                val countedOutput = CountingOutputStream(rawOut) { bytesWritten -> bytesSent += bytesWritten }
                BufferedOutputStream(countedOutput, bufferBytes).use { out ->
                    sourceInput.use { inp ->
                        onProgress(0f, "Transferring…")
                        bytesSent =
                            TransferUtils.copyWithProgress(
                                input = inp,
                                output = out,
                                options =
                                    CopyWithProgressOptions(
                                        totalBytes = metadata.totalSize,
                                        bufferBytes = bufferBytes,
                                        awaitIfPaused = awaitIfPaused,
                                    ),
                                onProgress = onProgress,
                            )
                    }
                }
                input = null
                val copyMs = SystemClock.elapsedRealtime() - copyStartMs
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "event=channel_send_complete $diagnosticContext bytesSent=$bytesSent " +
                        "result=success",
                )

                // 4) Wait ACK
                onProgress(1f, "Waiting for watch confirmation…")
                val ackStartMs = SystemClock.elapsedRealtime()
                val ack =
                    withTimeoutOrNull(
                        if (MapsforgeThemePackage.isTransferName(
                                metadata.safeFileName,
                            )
                        ) {
                            MapsforgeThemePackage.INSTALL_ACK_TIMEOUT_MS
                        } else {
                            WATCH_ACK_TIMEOUT_MS
                        },
                    ) { ackDeferred.await() }
                val ackWaitMs = SystemClock.elapsedRealtime() - ackStartMs
                Log.d(
                    TAG,
                    "Channel metrics file=${metadata.displayFileName} open=${openMs}ms " +
                        "stream=${streamOpenMs}ms copy=${copyMs}ms ack=${ackWaitMs}ms " +
                        "total=${SystemClock.elapsedRealtime() - totalStartMs}ms",
                )
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "event=channel_summary ${metadata.diagnosticContext()} " +
                        "file=${metadata.displayFileName} bytesSent=$bytesSent " +
                        "copyDurationMs=$copyMs " +
                        "averageMiBps=${String.format(
                            Locale.US,
                            "%.2f",
                            if (copyMs > 0L) {
                                bytesSent / (1024.0 * 1024.0) / (copyMs / 1000.0)
                            } else {
                                0.0
                            },
                        )} " +
                        "ackWaitMs=$ackWaitMs " +
                        "finalAckStatus=${ack?.let { if (it.success) "DONE" else "ERROR" } ?: "TIMEOUT"} " +
                        "openMs=$openMs streamOpenMs=$streamOpenMs " +
                        "totalMs=${SystemClock.elapsedRealtime() - totalStartMs}",
                )
                if (ack == null) {
                    PhoneTransferDiagnostics.warn(
                        "Channel",
                        "event=channel_ack_timeout ${metadata.diagnosticContext()} " +
                            "file=${metadata.displayFileName} waitMs=$ackWaitMs",
                    )
                } else {
                    PhoneTransferDiagnostics.log(
                        "Channel",
                        "event=channel_ack_received ${metadata.diagnosticContext()} " +
                            "file=${metadata.displayFileName} " +
                            "success=${ack.success} waitMs=$ackWaitMs",
                    )
                }
                val result = channelAckResult(ack)
                val resultReason = if (result.success) "na" else safeFailureReason(result.message)
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "event=sender_result $diagnosticContext " +
                        "bytesSent=$bytesSent sourceStreamOpen=$sourceStreamOpened " +
                        "result=${if (result.success) "success" else "failed"} reason=$resultReason",
                )
                result
            } catch (ce: CancellationException) {
                PhoneTransferDiagnostics.warn(
                    "Channel",
                    "event=sender_result $diagnosticContext " +
                        "bytesSent=$bytesSent sourceStreamOpen=$sourceStreamOpened " +
                        "result=failed reason=cancelled",
                )
                throw ce
            } catch (e: Exception) {
                val reason = safeFailureReason(e)
                Log.e(TAG, "Transfer failed reason=$reason")
                PhoneTransferDiagnostics.error(
                    "Channel",
                    "event=sender_result $diagnosticContext bytesSent=$bytesSent " +
                        "sourceStreamOpen=$sourceStreamOpened result=failed " +
                        "reason=$reason",
                )
                return@withContext TransferResult(false, "Error: $reason")
            } finally {
                runCatching { input?.close() }
                activeOutputStream = null
                channel?.let { runCatching { channelClient.close(it).await() } }
                activeChannel = null
                activeChannelClient = null
            }
        }

    override fun close() {
        runCatching { activeOutputStream?.close() }
        val client = activeChannelClient
        val channel = activeChannel
        if (client != null && channel != null) {
            runCatching { client.close(channel) }
        }
        activeOutputStream = null
        activeChannel = null
        activeChannelClient = null
    }
}

internal const val CHANNEL_ACK_TIMEOUT_MESSAGE = "File sent, but watch did not confirm that it was saved."

internal fun channelAckResult(ack: TransferResult?): TransferResult =
    if (ack != null) {
        ack
    } else {
        TransferResult(false, CHANNEL_ACK_TIMEOUT_MESSAGE)
    }

private class CountingOutputStream(
    private val delegate: OutputStream,
    private val onBytesWritten: (Long) -> Unit,
) : OutputStream() {
    override fun write(value: Int) {
        delegate.write(value)
        onBytesWritten(1L)
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        delegate.write(buffer, offset, length)
        onBytesWritten(length.toLong())
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

private fun safeFailureReason(error: Throwable): String {
    if (error is CancellationException) return "cancelled"
    val detailReason = safeFailureReason(error.message.orEmpty())
    return when {
        detailReason != "receiver_error" -> detailReason
        error is IOException -> "io_exception"
        else -> error.javaClass.simpleName
    }
}

private fun safeFailureReason(detail: String): String =
    when {
        detail.startsWith("unexpected_eof") || detail.startsWith("INCOMPLETE_FILE:") -> "unexpected_eof"
        detail.startsWith("unexpected_extra_data") || detail.startsWith("TOO_MUCH_DATA:") -> "unexpected_extra_data"
        detail.startsWith("missing_expected_size") -> "missing_expected_size"
        detail.startsWith("missing_expected_checksum") -> "missing_expected_checksum"
        detail.startsWith("checksum_compute_failed") -> "checksum_compute_failed"
        detail.startsWith("checksum_mismatch") || detail.startsWith("CHECKSUM_MISMATCH") -> "checksum_mismatch"
        detail.startsWith("source_stream_open_failed") -> "source_stream_open_failed"
        else -> "receiver_error"
    }
