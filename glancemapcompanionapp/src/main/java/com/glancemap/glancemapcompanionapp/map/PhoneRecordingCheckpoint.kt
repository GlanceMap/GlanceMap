package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Frequent fixes must not postpone the pending checkpoint. */
internal fun schedulePhoneRecordingCheckpoint(
    scope: CoroutineScope,
    pending: Job?,
    delayMillis: Long,
    persist: suspend () -> Unit,
): Job =
    pending?.takeIf { it.isActive } ?: scope.launch {
        delay(delayMillis)
        persist()
    }
