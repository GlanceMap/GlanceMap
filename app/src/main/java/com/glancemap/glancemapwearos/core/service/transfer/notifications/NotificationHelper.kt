package com.glancemap.glancemapwearos.core.service.transfer.notifications
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.glancemap.glancemapwearos.R
import com.glancemap.shared.transfer.TransferDataLayerContract
import kotlin.math.abs

internal const val FGS_DATA_SYNC_QUOTA_EXHAUSTED = TransferDataLayerContract.FGS_DATA_SYNC_QUOTA_EXHAUSTED
internal const val FGS_START_NOT_ALLOWED = TransferDataLayerContract.FGS_START_NOT_ALLOWED
internal const val FGS_DATA_SYNC_TIMEOUT = TransferDataLayerContract.FGS_DATA_SYNC_TIMEOUT

private const val FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION =
    "android.app.ForegroundServiceStartNotAllowedException"

internal fun foregroundStartFailureDetail(
    exceptionClassName: String,
    sdkInt: Int,
    exceptionMessage: String? = null,
): String =
    // Android 15 uses this exception for dataSync quota exhaustion, but Android also
    // uses it for ordinary background-start restrictions; require both quota markers.
    when {
        sdkInt >= Build.VERSION_CODES.S &&
            exceptionClassName == FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION &&
            exceptionMessage?.contains("time limit already exhausted", ignoreCase = true) == true &&
            exceptionMessage.contains("dataSync", ignoreCase = true) ->
            FGS_DATA_SYNC_QUOTA_EXHAUSTED

        sdkInt >= Build.VERSION_CODES.S && exceptionClassName == FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION ->
            FGS_START_NOT_ALLOWED

        else -> "FGS_START_FAILED:${exceptionClassName.substringAfterLast('.')}"
    }

class NotificationHelper(
    private val service: Service,
) {
    companion object {
        const val CHANNEL_ID = "DataLayerListenerChannel"

        // Throttle notification refresh
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_UPDATE_PROGRESS_STEP = 4 // percent
    }

    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastUpdateTimeMs: Long = 0L
    private var lastProgress: Int = -999
    private var lastStatus: String = ""

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "File Transfers",
                    NotificationManager.IMPORTANCE_LOW,
                )
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Foreground (non-swipeable) */
    fun startForeground(
        notificationId: Int,
        fileName: String,
        status: String,
    ) {
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }

        // Reset throttling
        lastUpdateTimeMs = 0L
        lastProgress = -999
        lastStatus = ""

        ServiceCompat.startForeground(
            service,
            notificationId,
            buildForegroundNotification(fileName, status, -1).build(),
            foregroundServiceType,
        )
    }

    /** Update the foreground notification while transferring */
    fun updateForeground(
        notificationId: Int,
        fileName: String,
        status: String,
        progress: Int,
    ) {
        val now = SystemClock.elapsedRealtime()

        val statusChanged = status != lastStatus
        val progressDeltaOk =
            (abs(progress - lastProgress) >= MIN_UPDATE_PROGRESS_STEP) || lastProgress == -999
        val timeOk = (now - lastUpdateTimeMs) >= MIN_UPDATE_INTERVAL_MS

        if (!statusChanged && !timeOk && !progressDeltaOk) return

        lastUpdateTimeMs = now
        lastProgress = progress
        lastStatus = status

        notificationManager.notify(
            notificationId,
            buildForegroundNotification(fileName, status, progress).build(),
        )
    }

    /**
     * Stop foreground and REMOVE the foreground notification.
     * (Then you can post a normal swipeable notification.)
     */
    fun stopForeground(notificationId: Int? = null) {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // extra safety: ensure the old foreground notif id is cleared
        notificationId?.let(notificationManager::cancel)
    }

    /** Normal (swipeable) completion notification */
    fun showCompletion(
        notificationId: Int,
        fileName: String,
        status: String = "Saved ✓",
    ) {
        notificationManager.notify(notificationId, buildCompletionNotification(fileName, status).build())
    }

    /** Normal (swipeable) error notification */
    fun showError(
        notificationId: Int,
        fileName: String,
        status: String,
    ) {
        notificationManager.notify(notificationId, buildErrorNotification(fileName, status).build())
    }

    private fun buildForegroundNotification(
        fileName: String,
        status: String,
        progress: Int,
    ): NotificationCompat.Builder {
        val builder =
            NotificationCompat
                .Builder(service, CHANNEL_ID)
                .setContentTitle("Receiving: $fileName")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true) // <-- this makes it non-swipeable
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        return if (progress < 0) {
            builder.setProgress(0, 0, true) // indeterminate
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
    }

    private fun buildCompletionNotification(
        fileName: String,
        status: String,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(service, CHANNEL_ID)
            .setContentTitle(fileName)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(false) // <-- swipeable
            .setAutoCancel(true) // <-- disappears when tapped (still swipeable)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // optional: auto-remove after 10s (you can delete this line if you want it to stay)
            .setTimeoutAfter(10_000L)

    private fun buildErrorNotification(
        fileName: String,
        status: String,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(service, CHANNEL_ID)
            .setContentTitle(fileName)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(false) // <-- swipeable
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
}
