package com.glancemap.glancemapwearos.presentation.features.maps.theme

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DemDownloadDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.DownloadNetworkLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Owns standalone DEM work independently of screen visibility and resumes retained partial files. */
class StandaloneDemDownloadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model by lazy { (application as GlanceMapWearApp).container.themeViewModel }
    private val notification by lazy { NotificationHelper(this) }
    private val requestStore by lazy { getSharedPreferences("standalone_dem_download", MODE_PRIVATE) }
    private var network: DownloadNetworkLease? = null
    private var progressJob: Job? = null
    private var destroying = false
    private var latestStartId = 0
    private val operation by lazy {
        DemDownloadOperation(
            scope = scope,
            download = ::runDownload,
            onFinished = { completed ->
                progressJob?.cancel()
                network?.close()
                network = null
                if (completed) clearRequest()
                if (!destroying) {
                    notification.stopForeground(NOTIFICATION_ID)
                    stopSelfResult(latestStartId)
                }
            },
        )
    }

    // Framework network and I/O failures must be surfaced without crashing the service.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runDownload(mapPath: String) {
        val lease = DownloadNetworkLease(this, DemDownloadDiagnostics::record)
        network = lease
        try {
            lease.start()
            model.runDemDownloadForMap(mapPath, lease)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            model.reportDemDownloadServiceFailure(error)
        }
    }

    // Keep command guards explicit and report platform foreground-start failures to the existing UI.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            clearRequest()
            model.cancelActiveDemDownload(expectedJob = operation.activeJob)
            operation.cancel()
            if (!operation.isRunning) stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        if (operation.isRunning) return START_STICKY
        val mapPath = intent?.getStringExtra(KEY_MAP_PATH) ?: requestStore.getString(KEY_MAP_PATH, null)
        if (mapPath.isNullOrBlank()) {
            stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        try {
            val title = getString(R.string.download_dem, File(mapPath).nameWithoutExtension)
            notification.startForeground(NOTIFICATION_ID, title, getString(R.string.dem_checking_watch_internet))
            if (!requestStore.edit().putString(KEY_MAP_PATH, mapPath).commit()) {
                throw IOException("Could not persist DEM download request")
            }
            operation.start(mapPath)
            if (operation.isRunning) {
                progressJob =
                    scope.launch {
                        model.demDownloadUiState.collect { state ->
                            if (state.isDownloading) {
                                notification.updateForeground(
                                    NOTIFICATION_ID,
                                    title,
                                    state.statusMessage,
                                    state.progressPercent ?: -1,
                                )
                            }
                        }
                    }
            }
        } catch (error: Exception) {
            clearRequest()
            model.reportDemDownloadServiceFailure(error)
            notification.stopForeground(NOTIFICATION_ID)
            stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        return if (operation.isRunning) START_STICKY else START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        super.onTimeout(startId, fgsType)
        if (startId != latestStartId || fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return
        clearRequest()
        model.cancelActiveDemDownload(timedOut = true, expectedJob = operation.activeJob)
        operation.cancel()
        notification.stopForeground(NOTIFICATION_ID)
        stopSelfResult(latestStartId)
    }

    override fun onDestroy() {
        destroying = true
        operation.activeJob?.let { model.cancelActiveDemDownload(expectedJob = it) }
        scope.cancel()
        network?.close()
        notification.stopForeground(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun clearRequest() {
        requestStore.edit().remove(KEY_MAP_PATH).commit()
    }

    companion object {
        private const val NOTIFICATION_ID = 42_211
        private const val KEY_MAP_PATH = "map_path"
        private const val ACTION_CANCEL = "com.glancemap.glancemapwearos.CANCEL_STANDALONE_DEM"

        fun start(
            context: Context,
            mapPath: String,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StandaloneDemDownloadForegroundService::class.java).putExtra(KEY_MAP_PATH, mapPath),
            )
        }

        fun cancel(context: Context) {
            val intent = Intent(context, StandaloneDemDownloadForegroundService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
