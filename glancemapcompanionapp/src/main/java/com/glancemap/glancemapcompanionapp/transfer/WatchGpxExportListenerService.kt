package com.glancemap.glancemapcompanionapp.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.routes.WatchRouteImport
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

/** [addedRoutes] counts routes added while the notification stayed open; 0 means the import failed. */
internal fun buildWatchGpxSuccessNotificationDescription(
    fileName: String,
    addedRoutes: Int,
    text: (Int) -> String,
): WatchGpxSuccessNotificationDescription {
    val location =
        when {
            addedRoutes <= 0 -> text(R.string.gpx_notification_received_from_watch)
            addedRoutes == 1 -> text(R.string.gpx_notification_added_to_routes)
            else -> text(R.string.gpx_notification_routes_added).format(addedRoutes)
        }
    return WatchGpxSuccessNotificationDescription(
        contentText = text(R.string.gpx_notification_content).format(location, fileName),
        expandedText = text(R.string.gpx_notification_expanded).format(location, fileName),
        saveCopyActionLabel = text(R.string.gpx_notification_save_copy),
        openActionLabel = text(R.string.gpx_notification_open),
        shareActionLabel = text(R.string.gpx_notification_share),
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
                showError(
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.gpx_notification_could_not_receive_gpx),
                )
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
                ?: throw IllegalArgumentException(getString(R.string.gpx_notification_invalid_gpx_export_request))
        val exportDir = PhoneOfflineStorage(applicationContext).watchGpxExportsDirectory().apply { mkdirs() }
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
                error(getString(R.string.gpx_notification_received_gpx_checksum_did_not_match))
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            importIntoRoutes(finalFile, actualSha256)
        } finally {
            tempFile.delete()
            runCatching { channelClient.close(channel) }
        }
    }

    /** The received copy is only kept when import fails, so the Library can still offer a manual import. */
    @Suppress("TooGenericExceptionCaught") // Any import failure keeps the verified file for a manual retry.
    private suspend fun importIntoRoutes(
        file: File,
        sha256: String,
    ) {
        val repository = RouteLibraryRepository(applicationContext)
        val imported =
            try {
                repository.importWatchRoute(file, sha256)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Watch GPX kept for manual import", error)
                showCompletion(file.name, file.shareUri(this), addedRoutes = 0)
                return
            }
        file.delete()
        val routeUri = repository.contentUriFor(imported.route.id)
        if (imported is WatchRouteImport.Added && routeUri != null) {
            val added = notificationManager.watchRoutesAdded(ROUTES_NOTIFICATION_ID, EXTRA_ADDED_ROUTES) + 1
            showCompletion(imported.route.displayName, routeUri, addedRoutes = added)
        }
    }

    private fun showCompletion(
        displayName: String,
        fileUri: Uri,
        addedRoutes: Int,
    ) {
        val openIntent =
            Intent(this, MainActivityMobile::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                ROUTES_NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val savePendingIntent =
            PendingIntent.getActivity(
                this,
                ROUTES_NOTIFICATION_ID + SAVE_REQUEST_OFFSET,
                buildWatchGpxSaveIntent(
                    context = this,
                    fileUri = fileUri,
                    fileName = gpxFileName(displayName),
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
                ROUTES_NOTIFICATION_ID + SHARE_REQUEST_OFFSET,
                Intent.createChooser(shareIntent, getString(R.string.gpx_notification_share_gpx)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val description =
            buildWatchGpxSuccessNotificationDescription(
                fileName = displayName,
                addedRoutes = addedRoutes,
                text = this::getString,
            )

        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle(getString(R.string.gpx_notification_gpx_ready))
                .setContentText(description.contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(description.expandedText))
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .addExtras(Bundle().apply { putInt(EXTRA_ADDED_ROUTES, addedRoutes) })
                .addAction(android.R.drawable.ic_menu_save, description.saveCopyActionLabel, savePendingIntent)
                .addAction(android.R.drawable.ic_menu_view, description.openActionLabel, openPendingIntent)
                .addAction(android.R.drawable.ic_menu_share, description.shareActionLabel, sharePendingIntent)
                .build()

        runCatching { notificationManager.notify(ROUTES_NOTIFICATION_ID, notification) }
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
                .setContentTitle(getString(R.string.gpx_notification_gpx_transfer_failed))
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
                getString(R.string.gpx_notification_watch_gpx_exports),
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
        const val ERROR_NOTIFICATION_ID = 57_230
        const val SHARE_REQUEST_OFFSET = 10_000
        const val SAVE_REQUEST_OFFSET = 20_000
        const val ROUTES_NOTIFICATION_ID = 57_231
        const val EXTRA_ADDED_ROUTES = "com.glancemap.glancemapcompanionapp.extra.WATCH_ROUTES_ADDED"
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

/** Counts routes still listed in the open notification, so one notification covers a batch. */
private fun NotificationManager.watchRoutesAdded(
    id: Int,
    key: String,
): Int =
    runCatching {
        activeNotifications
            .firstOrNull { it.id == id }
            ?.notification
            ?.extras
            ?.getInt(key)
    }.getOrNull() ?: 0

private fun gpxFileName(name: String): String = if (name.endsWith(".gpx", ignoreCase = true)) name else "$name.gpx"

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
