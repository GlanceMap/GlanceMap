package com.glancemap.glancemapwearos.presentation.features.navigate.activehike

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import com.glancemap.shared.transfer.ActiveHikeSnapshotCodec
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Sends the companion a throttled latest snapshot; snapshots are safe to lose or replace. */
internal class WatchActiveHikePublisher(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val app = appContext as GlanceMapWearApp
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private var lastSentElapsedMillis = Long.MIN_VALUE
    private var lastSentStateKey: String? = null

    fun publish(snapshot: ActiveHikeSnapshot) {
        val stateKey = snapshot.stateKey()
        val stateChanged = stateKey != lastSentStateKey
        val elapsedMillis = SystemClock.elapsedRealtime()
        val dueForRefresh = elapsedMillis - lastSentElapsedMillis >= REFRESH_INTERVAL_MILLIS
        if (!stateChanged && (!dueForRefresh || snapshot.phase == ActiveHikePhase.IDLE)) return

        lastSentElapsedMillis = elapsedMillis
        lastSentStateKey = stateKey
        val payload = ActiveHikeSnapshotCodec.encode(snapshot)
        app.applicationScope.launch {
            val nodes =
                runCatching { nodeClient.connectedNodes.await() }.getOrElse { error ->
                    Log.d(TAG, "Unable to find phone for active-hike snapshot: ${error.message}")
                    return@launch
                }
            nodes.forEach { node ->
                runCatching {
                    messageClient
                        .sendMessage(
                            node.id,
                            TransferDataLayerContract.PATH_ACTIVE_HIKE_SNAPSHOT,
                            payload,
                        ).await()
                }.onFailure { error ->
                    Log.d(TAG, "Active-hike snapshot send failed: ${error.message}")
                }
            }
        }
    }

    private fun ActiveHikeSnapshot.stateKey(): String =
        listOf(
            phase.name,
            routeId.orEmpty(),
            routeTitle.orEmpty(),
            offRoute.toString(),
        ).joinToString(separator = ":")

    private companion object {
        const val TAG = "WatchActiveHike"
        const val REFRESH_INTERVAL_MILLIS = 5_000L
    }
}
