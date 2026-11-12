package com.glancemap.glancemapcompanionapp.transfer

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import java.io.File

private const val WATCH_EXPORT_COPY_BUFFER_BYTES = 64 * 1024

internal data class WatchFileSuccessNotificationDescription(
    val contentText: String,
    val expandedText: String,
    val saveCopyActionLabel: String,
    val openActionLabel: String,
    val shareActionLabel: String,
)

internal fun buildWatchFileSuccessNotificationDescription(
    fileName: String,
    downloadsUriAvailable: Boolean,
): WatchFileSuccessNotificationDescription {
    val location =
        if (downloadsUriAvailable) {
            "Saved to Downloads/GlanceMap"
        } else {
            "Received from watch"
        }
    return WatchFileSuccessNotificationDescription(
        contentText = "$location. $fileName",
        expandedText = "$location\n$fileName",
        saveCopyActionLabel = "Save copy",
        openActionLabel = "Open",
        shareActionLabel = "Share",
    )
}

internal class WatchFileExportNotifier(
    private val context: Context,
    private val channelId: String,
    private val mimeType: String,
    private val fileLabel: String,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun showCompletion(
        file: File,
        shareIntentOverride: Intent? = null,
    ) {
        val downloadsUri = runCatching { copyToDownloads(file) }.getOrNull()
        if (!canPostNotifications()) {
            Log.w(TAG, "Skipping received-file notification: POST_NOTIFICATIONS not granted")
            return
        }
        val fileUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val openIntent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(downloadsUri ?: fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val openPendingIntent =
            activityPendingIntent(
                file.name.hashCode(),
                openIntent,
            )
        val savePendingIntent =
            activityPendingIntent(
                file.name.hashCode() + SAVE_REQUEST_OFFSET,
                buildSaveCopyIntent(
                    context = context,
                    fileUri = fileUri,
                    fileName = file.name,
                    mimeType = mimeType,
                ),
            )
        val shareIntent =
            shareIntentOverride ?: Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val sharePendingIntent =
            activityPendingIntent(
                file.name.hashCode() + SHARE_REQUEST_OFFSET,
                Intent.createChooser(shareIntent, "Share $fileLabel"),
            )
        val description =
            buildWatchFileSuccessNotificationDescription(
                fileName = file.name,
                downloadsUriAvailable = downloadsUri != null,
            )

        val notification =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle("$fileLabel ready")
                .setContentText(description.contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(description.expandedText))
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_save, description.saveCopyActionLabel, savePendingIntent)
                .addAction(android.R.drawable.ic_menu_view, description.openActionLabel, openPendingIntent)
                .addAction(android.R.drawable.ic_menu_share, description.shareActionLabel, sharePendingIntent)
                .build()

        runCatching { notificationManager?.notify(file.name.hashCode(), notification) }
            .onFailure { Log.w(TAG, "Notification publish failed: ${it.message}") }
    }

    private fun activityPendingIntent(
        requestCode: Int,
        intent: Intent,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun copyToDownloads(file: File): Uri? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            null
        } else {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/GlanceMap",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

            val resolver = context.contentResolver
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { uri ->
                runCatching {
                    resolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output, WATCH_EXPORT_COPY_BUFFER_BYTES)
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

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "WatchFileExport"
        const val SHARE_REQUEST_OFFSET = 10_000
        const val SAVE_REQUEST_OFFSET = 20_000
    }
}

private fun buildSaveCopyIntent(
    context: Context,
    fileUri: Uri,
    fileName: String,
    mimeType: String,
): Intent =
    Intent(context, MainActivityMobile::class.java).apply {
        action = WatchGpxSaveIntentContract.ACTION_SAVE_WATCH_GPX
        setDataAndType(fileUri, mimeType)
        putExtra(WatchGpxSaveIntentContract.EXTRA_FILE_NAME, fileName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
