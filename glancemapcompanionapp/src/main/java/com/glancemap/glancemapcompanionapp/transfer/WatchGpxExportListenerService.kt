package com.glancemap.glancemapcompanionapp.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.MessageDigest

private const val WATCH_GPX_COPY_BUFFER_BYTES = 64 * 1024
private const val WATCH_GPX_MIME_TYPE = "application/gpx+xml"

class WatchGpxExportListenerService : WearableListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        val exportPrefix = "${TransferDataLayerContract.CHANNEL_WATCH_GPX_EXPORT_PREFIX}/"
        if (!channel.path.startsWith(exportPrefix)) {
            return
        }

        serviceScope.launch {
            runCatching { receiveGpx(channel) }
                .onFailure { error ->
                    Log.e(TAG, "Watch GPX export failed", error)
                    showError(error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Could not receive GPX")
                }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun receiveGpx(channel: ChannelClient.Channel) {
        val request =
            parseChannelPath(channel.path)
                ?: throw IllegalArgumentException("Invalid GPX export request.")
        val exportDir = File(filesDir, EXPORT_DIR_NAME).apply { mkdirs() }
        val tempFile = File(exportDir, ".${request.transferId}.part")
        val finalFile = uniqueFile(exportDir, request.fileName)

        try {
            channelClient.getInputStream(channel).await().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, WATCH_GPX_COPY_BUFFER_BYTES)
                }
            }

            val actualSha256 = tempFile.sha256()
            if (!actualSha256.equals(request.sha256, ignoreCase = true)) {
                tempFile.delete()
                error("Received GPX checksum did not match.")
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            WatchFileExportNotifier(this, CHANNEL_ID, WATCH_GPX_MIME_TYPE, "GPX")
                .showCompletion(finalFile)
        } finally {
            tempFile.delete()
            runCatching { channelClient.close(channel).await() }
        }
    }

    private fun showError(message: String) {
        val intent = Intent(this, MainActivityMobile::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                ERROR_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle("GPX transfer failed")
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        runCatching { notificationManager.notify(ERROR_NOTIFICATION_ID, notification) }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Watch GPX exports",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun parseChannelPath(path: String): WatchGpxExportRequest? {
        val parts = path.split('/').filter { it.isNotBlank() }
        val transferId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        val sha256 = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val fileName = parts.getOrNull(4)?.let { sanitizeGpxFileName(Uri.decode(it)) }
        return if (transferId != null && sha256 != null && fileName != null) {
            WatchGpxExportRequest(transferId, sha256, fileName)
        } else {
            null
        }
    }

    private fun uniqueFile(
        dir: File,
        fileName: String,
    ): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var index = 2
        while (candidate.exists()) {
            val indexedName =
                if (extension.isBlank()) {
                    "$base-$index"
                } else {
                    "$base-$index.$extension"
                }
            candidate = File(dir, indexedName)
            index += 1
        }
        return candidate
    }

    private data class WatchGpxExportRequest(
        val transferId: String,
        val sha256: String,
        val fileName: String,
    )

    private companion object {
        const val TAG = "WatchGpxExport"
        const val CHANNEL_ID = "watch_gpx_exports"
        const val EXPORT_DIR_NAME = "watch-gpx-exports"
        const val ERROR_NOTIFICATION_ID = 57_230
    }
}

private fun sanitizeGpxFileName(name: String): String {
    val clean =
        name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\t]"), " ")
            .trim()
            .ifBlank { "watch-track.gpx" }
    return if (clean.endsWith(".gpx", ignoreCase = true)) clean else "$clean.gpx"
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(WATCH_GPX_COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
