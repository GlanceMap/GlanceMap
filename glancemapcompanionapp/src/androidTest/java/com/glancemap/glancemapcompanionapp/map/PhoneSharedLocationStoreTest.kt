package com.glancemap.glancemapcompanionapp.map

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSharedLocationStoreTest {
    @Test
    fun sourceCopiesRawFixAndIgnoresOldOwnersAndGenerations() {
        val oldOwner = Any()
        val owner = Any()
        val location =
            Location("gps").apply {
                latitude = 45.0
                longitude = 6.0
                time = 123_456L
                elapsedRealtimeNanos = 10_000_000_000L
                accuracy = 5f
                speed = 2f
            }
        PhoneSharedLocationStore.start(owner, 1L, 3_000L)
        PhoneSharedLocationStore.offer(oldOwner, 1L, location)
        assertNull(PhoneSharedLocationStore.state.value?.location)
        PhoneSharedLocationStore.offer(owner, 1L, location)
        location.latitude = 0.0
        assertEquals(
            45.0,
            PhoneSharedLocationStore.state.value!!
                .location!!
                .latitude,
            0.0,
        )
        assertEquals(
            123_456L,
            PhoneSharedLocationStore.state.value!!
                .location!!
                .time,
        )
        assertEquals(
            2f,
            PhoneSharedLocationStore.state.value!!
                .location!!
                .speed,
            0f,
        )
        PhoneSharedLocationStore.start(owner, 2L, 10_000L)
        PhoneSharedLocationStore.stop(owner, 1L)
        PhoneSharedLocationStore.offer(owner, 1L, location)
        assertNull(PhoneSharedLocationStore.state.value?.location)
        PhoneSharedLocationStore.stop(owner, 2L)
        assertNull(PhoneSharedLocationStore.state.value)
    }
}
