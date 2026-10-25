package com.glancemap.glancemapwearos.presentation.features.recording.health

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RecordingHealthDesiredSession(
    val startedAtMillis: Long,
    val request: RecordingHealthRequest,
    val paused: Boolean,
)

internal class RecordingHealthSessionLifecycle(
    private val createController: (Long) -> RecordingHealthController,
) {
    private val commands = Mutex()
    private var current: RecordingHealthDesiredSession? = null
    private var controller: RecordingHealthController? = null
    private var cleanupPending = false

    suspend fun update(next: RecordingHealthDesiredSession?): Boolean =
        commands.withLock {
            val previous = current
            if (next == previous && !cleanupPending) return@withLock true
            val sessionChanged =
                previous?.startedAtMillis != next?.startedAtMillis || previous?.request != next?.request
            if (next == null || cleanupPending || sessionChanged) {
                cleanupPending = true
                if (controller?.end() == false) return@withLock false
                controller = null
                current = null
                cleanupPending = false
                if (next != null) {
                    controller = createController(next.startedAtMillis)
                    current = next
                    controller?.start(next.request)
                    if (next.paused) controller?.pause()
                }
            } else {
                current = next
                if (next.paused) controller?.pause() else controller?.resume()
            }
            true
        }

    suspend fun finish() = update(null)
}
