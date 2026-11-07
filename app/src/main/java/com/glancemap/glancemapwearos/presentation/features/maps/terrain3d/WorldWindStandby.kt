package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.os.SystemClock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val WORLDWIND_STANDBY_MILLIS = 30_000L

internal suspend fun runWorldWindWithStandby(
    interactive: Flow<Boolean>,
    standbyMillis: Long,
    elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    onInteractiveChanged: (Boolean) -> Unit = {},
    runSession: suspend () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        interactive.first { it }
        val expired =
            coroutineScope {
                var timedOut = false
                var asleepSince: Long? = null
                val session = launch { runSession() }
                val watcher =
                    launch {
                        interactive.collectLatest { awake ->
                            if (!awake) {
                                asleepSince = elapsedRealtimeMillis()
                                onInteractiveChanged(false)
                                delay(standbyMillis)
                                timedOut = true
                                session.cancel()
                            } else {
                                val started = asleepSince
                                asleepSince = null
                                if (started != null && elapsedRealtimeMillis() - started >= standbyMillis) {
                                    timedOut = true
                                    session.cancel()
                                } else if (started != null) {
                                    onInteractiveChanged(true)
                                }
                            }
                        }
                    }
                try {
                    session.join()
                } finally {
                    watcher.cancelAndJoin()
                }
                timedOut
            }
        if (!expired) interactive.first { !it }
    }
}
