package com.glancemap.glancemapwearos.presentation.features.recording.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class RecordingHealthForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as GlanceMapWearApp).container.recordingHealthRuntime
    private lateinit var lifecycle: RecordingHealthSessionLifecycle
    private val finishHandler: suspend () -> Boolean = { lifecycle.finish() }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.settings_recording_info),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val foreground =
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION,
                    notification(paused = false),
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                    } else {
                        0
                    },
                )
            }
        if (foreground.isFailure) {
            runtime.onServiceFailure("foreground: ${foreground.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf()
            return
        }
        lifecycle = RecordingHealthSessionLifecycle(runtime::createController)
        runtime.attachFinishHandler(finishHandler)
        scope.launch {
            combine(runtime.requests, runtime.saving, runtime.reconcileRevision) { request, saving, _ ->
                request to
                    saving
            }.collect { (request, saving) ->
                val finished = lifecycle.update(request)
                val noDesiredSession = request == null && runtime.requests.value == null
                if (noDesiredSession && finished && !saving) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (request != null) {
                    manager.notify(NOTIFICATION, notification(request.paused))
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::lifecycle.isInitialized) {
            runtime.detachFinishHandler(finishHandler)
            (application as GlanceMapWearApp).applicationScope.launch(Dispatchers.Main.immediate) { lifecycle.finish() }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(paused: Boolean): Notification =
        NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.recording_rec_paused else R.string.recording_rec_on_2))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
}

private const val CHANNEL = "recording_health"
private const val NOTIFICATION = 24_618
