package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedMapSelectionPolicyTest {
    @Test
    fun clearsSelectionWhenTheFailedMapIsStillSelected() {
        assertTrue(shouldClearFailedMapSelection("/maps/broken.map", "/maps/broken.map"))
    }

    @Test
    fun keepsANewerSelectionWhenAnOlderMapLoadFails() {
        assertFalse(shouldClearFailedMapSelection("/maps/old.map", "/maps/new.map"))
    }

    @Test
    fun doesNotClearAnAlreadyEmptySelection() {
        assertFalse(shouldClearFailedMapSelection("/maps/broken.map", null))
    }
}
