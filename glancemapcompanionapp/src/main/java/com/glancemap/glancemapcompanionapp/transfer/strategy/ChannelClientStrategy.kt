package com.glancemap.glancemapcompanionapp.transfer.strategy

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils
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

            val channelClient = Wearable.getChannelClient(context)
            val messageClient = Wearable.getMessageClient(context)
            activeChannelClient = channelClient

            val input =
                context.contentResolver.openInputStream(fileUri)
                    ?: return@withContext TransferResult(false, "Cannot open input stream")

            // Adaptive buffer (Channel is usually BT-limited, but slightly larger helps reduce overhead)
            val bufferBytes =
                when {
                    metadata.totalSize in 1..(512 * 1024L) -> 16 * 1024 // <= 512KB
                    metadata.totalSize in 1..(5 * 1024 * 1024L) -> 64 * 1024 // <= 5MB
                    metadata.totalSize in 1..(30 * 1024 * 1024L) -> 256 * 1024 // <= 30MB
                    else -> 512 * 1024 // ✅ if used above 30MB, keep stable
                }

            var channel: ChannelClient.Channel? = null

            try {
                // 0) Pre-warm watch
                onProgress(0f, "Waking watch…")
                PhoneTransferDiagnostics.log(
                    "Channel",
                    "Transfer start ${metadata.diagnosticContext()} " +
                        "file=${metadata.displayFileName} size=${metadata.totalSize}",
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
                        Log.d(TAG, "Pre-warm attempt $attempt failed (non-fatal): ${result.exceptionOrNull()?.message}")
                        PhoneTransferDiagnostics.warn(
                            "Channel",
                            "Prewarm attempt $attempt failed file=${metadata.displayFileName} msg=${result.exceptionOrNull()?.message}",
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
                var bytesSent = 0L
                BufferedOutputStream(rawOut, bufferBytes).use { out ->
                    input.use { inp ->
                        onProgress(0f, "Transferring…")
                        bytesSent =
                            TransferUtils.copyWithProgress(
                                input = inp,
                                output = out,
                                totalBytes = metadata.totalSize,
                                bufferBytes = bufferBytes,
                                awaitIfPaused = awaitIfPaused,
                                onProgress = onProgress,
                            )
                    }
                }
                val copyMs = SystemClock.elapsedRealtime() - copyStartMs

                // 4) Wait ACK
                onProgress(1f, "Waiting for watch confirmation…")
                val ackStartMs = SystemClock.elapsedRealtime()
                val ack = withTimeoutOrNull(WATCH_ACK_TIMEOUT_MS) { ackDeferred.await() }
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
                return@withContext channelAckResult(ack)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Transfer error", e)
                PhoneTransferDiagnostics.error(
                    "Channel",
                    "Transfer error file=${metadata.displayFileName}",
                    e,
                )
                return@withContext TransferResult(false, "Error: ${e.localizedMessage}")
            } finally {
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
