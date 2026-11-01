package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PhoneWorldWindActivity {
    private val lock = Any()
    private var pending = 0
    private val active = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = active.asStateFlow()

    suspend fun <T> track(work: suspend () -> T): T {
        synchronized(lock) {
            pending++
            active.value = true
        }
        try {
            return work()
        } finally {
            synchronized(lock) {
                pending--
                active.value = pending > 0
            }
        }
    }
}
