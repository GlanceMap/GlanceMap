package com.glancemap.glancemapcompanionapp.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
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

internal data class WatchGpxSuccessNotificationDescription(
    val contentText: String,
    val expandedText: String,
    val saveCopyActionLabel: String,
    val openActionLabel: String,
    val shareActionLabel: String,
)

internal fun buildWatchGpxSuccessNotificationDescription(
    fileName: String,
    downloadsUriAvailable: Boolean,
): WatchGpxSuccessNotificationDescription {
    val location =
        if (downloadsUriAvailable) {
            "Saved to Downloads/GlanceMap"
        } else {
            "Received from watch"
        }
    return WatchGpxSuccessNotificationDescription(
        contentText = "$location. $fileName",
        expandedText = "$location\n$fileName",
        saveCopyActionLabel = "Save copy",
        openActionLabel = "Open",
        shareActionLabel = "Share",
    )
}

class WatchGpxExportListenerService : WearableListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelClient by lazy { Wearable.getChannelClient(applicationContext) }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        val exportPrefix = "${TransferDataLayerContract.CHANNEL_WATCH_GPX_EXPORT_PREFIX}/"
        if (!channel.path.startsWith(exportPrefix)) {
            return
        }

        serviceScope.launch {
            try {
                receiveGpx(channel)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
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

            val downloadsUri = runCatching { copyToDownloads(finalFile) }.getOrNull()
            showCompletion(finalFile, downloadsUri)
        } finally {
            tempFile.delete()
            runCatching { channelClient.close(channel) }
        }
    }

    private fun copyToDownloads(file: File): Uri? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            null
        } else {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, WATCH_GPX_MIME_TYPE)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/GlanceMap",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

            val resolver = contentResolver
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { uri ->
                runCatching {
                    resolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output, WATCH_GPX_COPY_BUFFER_BYTES)
                        }
                    } ?: error("Unable to open Downloads destination.")

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }.onFailure {
                    resolver.delete(uri, null, null)
                    throw it
                }
            }
        }

    private fun showCompletion(
        file: File,
        downloadsUri: Uri?,
    ) {
        val fileUri = file.shareUri(this)
        val openIntent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(downloadsUri ?: fileUri, WATCH_GPX_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                file.name.hashCode(),
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val savePendingIntent =
            PendingIntent.getActivity(
                this,
                file.name.hashCode() + SAVE_REQUEST_OFFSET,
                buildWatchGpxSaveIntent(
                    context = this,
                    fileUri = fileUri,
                    fileName = file.name,
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = WATCH_GPX_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val sharePendingIntent =
            PendingIntent.getActivity(
                this,
                file.name.hashCode() + SHARE_REQUEST_OFFSET,
                Intent.createChooser(shareIntent, "Share GPX"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = file.name,
                downloadsUriAvailable = downloadsUri != null,
            )

        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle("GPX ready")
                .setContentText(description.contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(description.expandedText))
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_save, description.saveCopyActionLabel, savePendingIntent)
                .addAction(android.R.drawable.ic_menu_view, description.openActionLabel, openPendingIntent)
                .addAction(android.R.drawable.ic_menu_share, description.shareActionLabel, sharePendingIntent)
                .build()

        runCatching { notificationManager.notify(file.name.hashCode(), notification) }
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
        const val SHARE_REQUEST_OFFSET = 10_000
        const val SAVE_REQUEST_OFFSET = 20_000
    }
}

private fun buildWatchGpxSaveIntent(
    context: Context,
    fileUri: Uri,
    fileName: String,
): Intent =
    Intent(context, MainActivityMobile::class.java).apply {
        action = WatchGpxSaveIntentContract.ACTION_SAVE_WATCH_GPX
        setDataAndType(fileUri, WATCH_GPX_MIME_TYPE)
        putExtra(WatchGpxSaveIntentContract.EXTRA_FILE_NAME, fileName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

private fun File.shareUri(context: Context): Uri =
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        this,
    )

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
