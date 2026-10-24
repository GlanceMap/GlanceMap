package com.glancemap.glancemapwearos.presentation.features.maps.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class DemDownloadOperation(
    private val scope: CoroutineScope,
    private val download: suspend (String) -> Unit,
    private val onFinished: (completed: Boolean) -> Unit,
) {
    private var job: Job? = null

    val isRunning: Boolean
        get() = job != null

    val activeJob: Job?
        get() = job

    fun start(mapPath: String): Boolean {
        if (job != null) return false
        val next = scope.launch(start = CoroutineStart.LAZY) { download(mapPath) }
        job = next
        // A cancelled coroutine may never enter its body, so cleanup belongs to completion.
        next.invokeOnCompletion { cause ->
            job = null
            onFinished(cause == null)
        }
        next.start()
        return true
    }

    fun cancel() {
        job?.cancel()
    }
}
