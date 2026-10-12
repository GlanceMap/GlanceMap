package com.glancemap.glancemapcompanionapp.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

class DiagnosticsEmailListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != TransferDataLayerContract.PATH_DIAGNOSTICS_EMAIL_REQUEST) return

        val payload =
            runCatching { parsePayload(messageEvent.data) }.getOrElse {
                Log.w(TAG, "Invalid diagnostics payload: ${it.message}")
                return
            } ?: return

        val diagnosticsFile =
            runCatching { saveDiagnosticsFile(payload.fileName, payload.text) }.getOrElse {
                Log.w(TAG, "Failed to save diagnostics payload: ${it.message}")
                return
            }

        val emailIntent =
            buildEmailIntent(
                targetEmail = payload.email,
                subject = payload.subject,
                diagnosticsFile = diagnosticsFile,
                truncated = payload.truncated,
            )

        val chooserIntent =
            Intent.createChooser(emailIntent, getString(R.string.diagnostics_send_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        runCatching { startActivity(chooserIntent) }
            .onFailure { error ->
                Log.w(TAG, "Could not launch email composer: ${error.message}")
                showComposeNotification(chooserIntent, payload.subject)
            }
    }

    private fun parsePayload(data: ByteArray): DiagnosticsPayload? {
        val json = JSONObject(String(data, Charsets.UTF_8))
        val email = json.optString("email", TransferDataLayerContract.DIAGNOSTICS_SUPPORT_EMAIL)
        val subject =
            json.optString(
                "subject",
                "${TransferDataLayerContract.DIAGNOSTICS_SUBJECT_PREFIX} watch diagnostics",
            )
        val fileName = json.optString("fileName", "glancemap_diagnostics.txt")
        val encoding = json.optString("encoding", "")
        val truncated = json.optBoolean("truncated", false)
        val chunked = json.optBoolean("chunked", false)
        val transferId = json.optString("transferId", "")
        val chunkIndex = json.optInt("chunkIndex", 0)
        val chunkCount = json.optInt("chunkCount", 1)
        val content = json.optString("content", "")

        require(content.isNotBlank()) { "Missing diagnostics content" }
        require(encoding == "gzip_base64_utf8_text") { "Unsupported encoding: $encoding" }

        val base64Content =
            if (chunked || chunkCount > 1) {
                PendingDiagnosticsEmailChunks.accept(
                    DiagnosticsPayloadChunk(
                        transferId = transferId,
                        email = email,
                        subject = subject,
                        fileName = fileName,
                        truncated = truncated,
                        chunkIndex = chunkIndex,
                        chunkCount = chunkCount,
                        content = content,
                    ),
                ) ?: return null
            } else {
                content
            }

        val compressed = Base64.decode(base64Content, Base64.DEFAULT)
        val text =
            GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }

        return DiagnosticsPayload(
            email = email,
            subject = subject,
            fileName = fileName,
            truncated = truncated,
            text = text,
        )
    }

    private fun saveDiagnosticsFile(
        fileName: String,
        text: String,
    ): File {
        val safeFileName =
            fileName
                .replace("\\", "_")
                .replace("/", "_")
                .trim()
                .ifBlank { "glancemap_diagnostics.txt" }

        val dir = File(cacheDir, "diagnostics_mail")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val file = File(dir, safeFileName)
        file.writeText(text)
        return file
    }

    private fun buildEmailIntent(
        targetEmail: String,
        subject: String,
        diagnosticsFile: File,
        truncated: Boolean,
    ): Intent {
        val uri =
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                diagnosticsFile,
            )
        val body =
            if (truncated) {
                "Diagnostics export attached. Note: payload was truncated to fit watch transfer limits."
            } else {
                "Diagnostics export attached."
            }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showComposeNotification(
        emailIntent: Intent,
        subject: String,
    ) {
        if (!canPostNotifications()) {
            Log.w(TAG, "Skipping diagnostics notification: POST_NOTIFICATIONS not granted")
            return
        }

        ensureNotificationChannel()
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                subject.hashCode(),
                emailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp)
                .setContentTitle(getString(R.string.diagnostics_notification_diagnostics_ready))
                .setContentText(getString(R.string.diagnostics_notification_tap_to_open_email_draft))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(subject.hashCode(), notification)
        }.onFailure {
            Log.w(TAG, "Notification publish failed: ${it.message}")
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.diagnostics_notification_diagnostics_email),
                NotificationManager.IMPORTANCE_HIGH,
            )
        channel.description = getString(R.string.diagnostics_notification_notifications_for_diagnostics_email_drafts)
        manager.createNotificationChannel(channel)
    }

    private data class DiagnosticsPayload(
        val email: String,
        val subject: String,
        val fileName: String,
        val truncated: Boolean,
        val text: String,
    )

    private data class PendingChunkSet(
        val email: String,
        val subject: String,
        val fileName: String,
        val truncated: Boolean,
        val chunkCount: Int,
        val createdAtMs: Long,
        val chunks: Array<String?>,
    )

    private data class DiagnosticsPayloadChunk(
        val transferId: String,
        val email: String,
        val subject: String,
        val fileName: String,
        val truncated: Boolean,
        val chunkIndex: Int,
        val chunkCount: Int,
        val content: String,
    )

    private object PendingDiagnosticsEmailChunks {
        private const val MAX_PENDING_AGE_MS = 10 * 60 * 1000L
        private val lock = Any()
        private val pending = linkedMapOf<String, PendingChunkSet>()

        fun accept(chunk: DiagnosticsPayloadChunk): String? {
            require(chunk.transferId.isNotBlank()) { "Missing chunk transferId" }
            require(chunk.chunkCount > 1) { "Invalid chunk count: ${chunk.chunkCount}" }
            require(chunk.chunkIndex in 0 until chunk.chunkCount) {
                "Invalid chunk index: ${chunk.chunkIndex}/${chunk.chunkCount}"
            }

            synchronized(lock) {
                pruneExpiredLocked()
                val set =
                    pending.getOrPut(chunk.transferId) {
                        PendingChunkSet(
                            email = chunk.email,
                            subject = chunk.subject,
                            fileName = chunk.fileName,
                            truncated = chunk.truncated,
                            chunkCount = chunk.chunkCount,
                            createdAtMs = System.currentTimeMillis(),
                            chunks = arrayOfNulls(chunk.chunkCount),
                        )
                    }
                require(set.chunkCount == chunk.chunkCount) {
                    "Chunk count changed for ${chunk.transferId}"
                }
                require(set.email == chunk.email && set.subject == chunk.subject && set.fileName == chunk.fileName) {
                    "Chunk metadata changed for ${chunk.transferId}"
                }

                set.chunks[chunk.chunkIndex] = chunk.content
                if (set.chunks.any { it == null }) return null

                pending.remove(chunk.transferId)
                return set.chunks.joinToString(separator = "") { it.orEmpty() }
            }
        }

        private fun pruneExpiredLocked() {
            val cutoff = System.currentTimeMillis() - MAX_PENDING_AGE_MS
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.createdAtMs < cutoff) {
                    iterator.remove()
                }
            }
        }
    }

    private companion object {
        const val TAG = "DiagEmailListener"
        const val CHANNEL_ID = "diagnostics_email"
    }
}
