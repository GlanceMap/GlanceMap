package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Requests finished out of those started since the view last had nothing left to load. */
internal data class PhoneWorldWindProgress(
    val finished: Int = 0,
    val started: Int = 0,
) {
    val complete: Boolean get() = finished >= started

    val fraction: Float get() = if (started == 0) 1f else finished.toFloat() / started
}

internal class PhoneWorldWindActivity {
    private val lock = Any()
    private val state = MutableStateFlow(PhoneWorldWindProgress())
    val progress: StateFlow<PhoneWorldWindProgress> = state.asStateFlow()

    suspend fun <T> track(work: suspend () -> T): T {
        synchronized(lock) {
            val batch = state.value.takeUnless { it.complete } ?: PhoneWorldWindProgress()
            // A new batch starts once everything earlier has loaded, so the ring stays full until then.
            state.value = batch.copy(started = batch.started + 1)
        }
        try {
            return work()
        } finally {
            synchronized(lock) {
                state.value = state.value.let { it.copy(finished = it.finished + 1) }
            }
        }
    }
}
