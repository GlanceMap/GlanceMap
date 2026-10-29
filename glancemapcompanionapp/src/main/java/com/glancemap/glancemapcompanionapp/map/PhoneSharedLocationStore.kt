package com.glancemap.glancemapcompanionapp.map

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Original fused fixes delivered to the phone recording/guidance service. */
internal data class PhoneSharedLocation(
    val owner: Any,
    val generation: Long,
    val intervalMillis: Long,
    val location: Location? = null,
)

internal object PhoneSharedLocationStore {
    private val mutableState = MutableStateFlow<PhoneSharedLocation?>(null)
    val state = mutableState.asStateFlow()

    @Synchronized
    fun start(
        owner: Any,
        generation: Long,
        intervalMillis: Long,
    ) {
        mutableState.value = PhoneSharedLocation(owner, generation, intervalMillis)
    }

    @Synchronized
    fun offer(
        owner: Any,
        generation: Long,
        location: Location,
    ) {
        val current = mutableState.value ?: return
        if (current.owner !== owner ||
            current.generation != generation ||
            location.elapsedRealtimeNanos <= (current.location?.elapsedRealtimeNanos ?: 0L)
        ) {
            return
        }
        mutableState.value = current.copy(location = Location(location))
    }

    @Synchronized
    fun stop(
        owner: Any,
        generation: Long,
    ) {
        if (mutableState.value?.let { it.owner === owner && it.generation == generation } == true) {
            mutableState.value = null
        }
    }
}
