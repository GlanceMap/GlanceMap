package com.glancemap.glancemapcompanionapp.transfer.service

import android.content.Context
import android.util.Log
import com.glancemap.glancemapcompanionapp.WatchNode
import com.glancemap.glancemapcompanionapp.activehike.CompanionLiveHikeSyncPreferences
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import com.glancemap.glancemapcompanionapp.transfer.datalayer.PhoneDataLayerRepository
import com.glancemap.shared.transfer.LiveHikeSyncSettingsCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Sends the companion-owned Live Hike preference to every reachable watch. */
internal class LiveHikeSyncController(
    context: Context,
    private val dataLayerRepository: PhoneDataLayerRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var syncJob: Job? = null

    @Volatile
    var enabled = CompanionLiveHikeSyncPreferences.isEnabled(appContext)
        private set

    fun update(enabled: Boolean) {
        this.enabled = enabled
        CompanionLiveHikeSyncPreferences.setEnabled(appContext, enabled)
        sync(dataLayerRepository.watches.value)
    }

    @Suppress("TooGenericExceptionCaught")
    fun sync(watches: List<WatchNode>) {
        if (watches.isEmpty()) return

        syncJob?.cancel()
        val setting = enabled
        syncJob =
            scope.launch(Dispatchers.IO) {
                val payload = LiveHikeSyncSettingsCodec.encode(setting)
                watches.forEach { watch ->
                    try {
                        dataLayerRepository.sendMessage(
                            nodeId = watch.id,
                            path = DataLayerPaths.PATH_LIVE_HIKE_SYNC_SETTINGS,
                            payload = payload,
                        )
                        PhoneTransferDiagnostics.log(
                            "ActiveHike",
                            "Live Hike sync sent enabled=$setting node=${watch.id}",
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.w(TAG, "Unable to sync Live Hike setting to ${watch.displayName}", error)
                        PhoneTransferDiagnostics.warn(
                            "ActiveHike",
                            "Live Hike sync send failed enabled=$setting node=${watch.id}",
                        )
                    }
                }
            }
    }

    fun cancel() {
        syncJob?.cancel()
    }

    private companion object {
        const val TAG = "LiveHikeSync"
    }
}
