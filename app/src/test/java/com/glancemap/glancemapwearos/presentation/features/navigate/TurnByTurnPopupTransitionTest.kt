package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnByTurnPopupTransitionTest {
    @Test
    fun `popup owns the time chip during enter and exit`() {
        assertTrue(fullScreenPopupTransitionOwnsTimeChip(currentlyVisible = false, targetVisible = true))
        assertTrue(fullScreenPopupTransitionOwnsTimeChip(currentlyVisible = true, targetVisible = false))
        assertFalse(fullScreenPopupTransitionOwnsTimeChip(currentlyVisible = false, targetVisible = false))
    }
}
