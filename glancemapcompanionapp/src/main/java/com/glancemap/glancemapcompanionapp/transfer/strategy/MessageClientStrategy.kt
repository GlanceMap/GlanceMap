package com.glancemap.glancemapcompanionapp.transfer.strategy

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

class MessageClientStrategy : TransferStrategy {
    companion object {
        private const val PATH_SMALL_FILE_PREFIX = "/glancemap/small_file"
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val MAX_SEND_ATTEMPTS = 3
    }

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
            awaitIfPaused()
            PhoneTransferDiagnostics.log(
                "Message",
                "Transfer start file=${metadata.displayFileName} node=$targetNodeId size=${metadata.totalSize}",
            )

            onProgress(0f, "Reading file...")
            val readStartMs = SystemClock.elapsedRealtime()
            val bytes =
                try {
                    context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    val message =
                        if (t is OutOfMemoryError) {
                            "File too large for Message transfer. Try again with Wi-Fi or Channel transfer."
                        } else {
                            "Failed to read file: ${t.message}"
                        }
                    return@withContext TransferResult(false, message)
                }

            if (bytes == null || bytes.isEmpty()) {
                PhoneTransferDiagnostics.warn("Message", "File empty file=${metadata.displayFileName}")
                return@withContext TransferResult(false, "File is empty")
            }
            val readMs = SystemClock.elapsedRealtime() - readStartMs

            onProgress(0.5f, "Sending via Message API...")

            // Compute SHA256 inline from in-memory bytes (fast for ≤80 KB files)
            val sha256 =
                metadata.checksumSha256 ?: run {
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(bytes)
                    digest.digest().joinToString("") { b -> "%02x".format(b) }
                }

            // Path structure: /glancemap/small_file/<transferId>/<sha256>/<encodedName>
            val path = "$PATH_SMALL_FILE_PREFIX/${metadata.transferId}/$sha256/${Uri.encode(metadata.displayFileName)}"

            try {
                val messageClient = Wearable.getMessageClient(context.applicationContext)
                awaitIfPaused()
                val sendStartMs = SystemClock.elapsedRealtime()
                var lastSendException: Exception? = null
                for (attempt in 1..MAX_SEND_ATTEMPTS) {
                    try {
                        awaitIfPaused()
                        messageClient.sendMessage(targetNodeId, path, bytes).await()
                        lastSendException = null
                        break
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Exception) {
                        lastSendException = e
                        PhoneTransferDiagnostics.warn(
                            "Message",
                            "Send attempt $attempt failed file=${metadata.displayFileName} msg=${e.message}",
                        )
                        if (attempt < MAX_SEND_ATTEMPTS) delay(1000L * attempt)
                    }
                }
                if (lastSendException != null) {
                    PhoneTransferDiagnostics.error(
                        "Message",
                        "Send failed after $MAX_SEND_ATTEMPTS attempts file=${metadata.displayFileName}",
                        lastSendException,
                    )
                    return@withContext TransferResult(false, "Send failed after $MAX_SEND_ATTEMPTS attempts: ${lastSendException.message}")
                }
                val sendMs = SystemClock.elapsedRealtime() - sendStartMs
                onProgress(1f, "Sent. Waiting for confirmation...")

                val ackStartMs = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(ACK_TIMEOUT_MS) { ackDeferred.await() }
                val ackWaitMs = SystemClock.elapsedRealtime() - ackStartMs
                Log.d(
                    "MessageClientStrategy",
                    "Message metrics file=${metadata.displayFileName} read=${readMs}ms " +
                        "send=${sendMs}ms ack=${ackWaitMs}ms total=${SystemClock.elapsedRealtime() - totalStartMs}ms",
                )
                PhoneTransferDiagnostics.log(
                    "Message",
                    "Metrics file=${metadata.displayFileName} read=${readMs}ms send=${sendMs}ms ack=${ackWaitMs}ms total=${SystemClock.elapsedRealtime() - totalStartMs}ms",
                )
                return@withContext result ?: TransferResult(true, "Sent, but watch did not confirm save.")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                PhoneTransferDiagnostics.error(
                    "Message",
                    "Send failed file=${metadata.displayFileName}",
                    e,
                )
                return@withContext TransferResult(false, "Send failed: ${e.message}")
            }
        }
}
