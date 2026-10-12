package com.glancemap.glancemapcompanionapp.transfer.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.transfer.presentation.TransferTextFormatter
import com.glancemap.glancemapcompanionapp.transfer.service.FileTransferService
import kotlin.math.abs

class NotificationHelper(
    private val service: Service,
) {
    companion object {
        const val CHANNEL_ID = "file_transfer_channel"
        const val NOTIFICATION_ID = 1

        // ✅ Throttle notification refresh to avoid slowing IO
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_UPDATE_PROGRESS_STEP = 4 // percent

        private const val REQ_TOGGLE = 1001
        private const val REQ_CANCEL = 1002
        private const val REQ_OPEN_APP = 1003
    }

    private val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val smallNotificationIconRes = R.mipmap.ic_launcher_companionapp_foreground

    private var lastUpdateTimeMs: Long = 0L
    private var lastProgress: Int = -999
    private var lastText: String = ""
    private var isTransferNotificationActive: Boolean = false

    // Launch the app when tapping the notification
    private val contentIntent =
        Intent(service, MainActivityMobile::class.java).let {
            PendingIntent.getActivity(
                service,
                REQ_OPEN_APP,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    service.getString(R.string.transfer_notification_file_transfers),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    fun startForeground(text: String) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }

        // Reset throttling on new transfer
        lastUpdateTimeMs = 0L
        lastProgress = -999
        lastText = ""
        isTransferNotificationActive = true

        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            buildNotification(progress = -1, text = text, isPaused = false).build(),
            type,
        )
    }

    /**
     * Backward compatible call.
     * Use progress < 0 for indeterminate (Connecting / Waiting…).
     */
    fun updateProgress(
        progress: Int,
        text: String,
    ) {
        updateProgress(progress = progress, text = text, isPaused = false)
    }

    /**
     * New version: allows pause/resume toggle.
     * Use progress < 0 for indeterminate.
     */
    fun updateProgress(
        progress: Int,
        text: String,
        isPaused: Boolean,
    ) {
        if (!isTransferNotificationActive) return

        val now = SystemClock.elapsedRealtime()

        val textChanged = text != lastText
        val progressDeltaOk =
            (abs(progress - lastProgress) >= MIN_UPDATE_PROGRESS_STEP) || lastProgress == -999
        val timeOk = (now - lastUpdateTimeMs) >= MIN_UPDATE_INTERVAL_MS

        if (!textChanged && !timeOk && !progressDeltaOk) return

        lastUpdateTimeMs = now
        lastProgress = progress
        lastText = text

        nm.notify(NOTIFICATION_ID, buildNotification(progress, text, isPaused).build())
    }

    fun showCompletion(
        fileName: String,
        targetName: String,
    ) {
        // Make sure the foreground is not left "ongoing"
        stopForeground()

        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat
                .Builder(service, CHANNEL_ID)
                .setContentTitle(service.getString(R.string.transfer_notification_transfer_complete))
                .setContentText(
                    service.getString(R.string.transfer_notification_sent_value_to_value, fileName, targetName),
                ).setSmallIcon(smallNotificationIconRes)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .build(),
        )
    }

    /**
     * ✅ Call this when a transfer fails, so the user can swipe it away.
     */
    fun showError(
        title: String = service.getString(R.string.transfer_notification_transfer_failed),
        message: String,
    ) {
        // Remove any foreground ongoing notification first
        stopForeground()

        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat
                .Builder(service, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(smallNotificationIconRes)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .build(),
        )
    }

    /**
     * Remove foreground mode without force-cancelling a terminal notification.
     */
    fun stopForeground() {
        isTransferNotificationActive = false
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Hard cleanup: remove foreground mode and cancel the current notification.
     */
    fun cancelForeground() {
        isTransferNotificationActive = false
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(
        progress: Int,
        text: String,
        isPaused: Boolean,
    ): NotificationCompat.Builder {
        val normalizedText = text.trim().ifBlank { service.getString(R.string.transfer_notification_transferring) }
        val presentation =
            TransferTextFormatter(service::getString).buildNotificationPresentation(
                rawText = normalizedText,
                progress = progress,
                isPaused = isPaused,
                showTechnicalDetails = PhoneDebugCapture.isActive(),
            )
        val waitingForReconnect = presentation.waitingForReconnect
        val title = presentation.title
        val bodyText = presentation.contentText
        val expandedBody = presentation.expandedText
        val subText = presentation.subText

        // Cancel action -> service
        val cancelIntent =
            Intent(service, FileTransferService::class.java).apply {
                action = FileTransferService.ACTION_CANCEL
            }
        val cancelPending =
            PendingIntent.getService(
                service,
                REQ_CANCEL,
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val toggleAction = if (isPaused) FileTransferService.ACTION_RESUME else FileTransferService.ACTION_PAUSE
        val toggleLabel =
            if (isPaused) {
                service.getString(
                    R.string.common_action_resume,
                )
            } else {
                service.getString(R.string.common_action_pause)
            }
        val toggleIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val toggleIntent =
            Intent(service, FileTransferService::class.java).apply {
                action = toggleAction
            }
        val togglePending =
            PendingIntent.getService(
                service,
                REQ_TOGGLE,
                toggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat
                .Builder(service, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(bodyText)
                .setSmallIcon(smallNotificationIconRes)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
                .setContentIntent(contentIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    service.getString(R.string.common_action_cancel),
                    cancelPending,
                )

        if (!waitingForReconnect) {
            builder.addAction(toggleIcon, toggleLabel, togglePending)
        }

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        // Progress: indeterminate if progress < 0
        return if (progress < 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
    }
}
