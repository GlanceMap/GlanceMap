package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRemovedMaptoolkitTest {
    @Test
    fun savedMaptoolkitSourcesFallBackAndDoNotAppearInEitherPicker() {
        listOf("MAPTOOLKIT_HIKING", "MAPTOOLKIT_CYCLING").forEach { oldSource ->
            assertEquals(PhoneOnlineMapSource.OPEN_TOPO, PhoneOnlineMapSource.fromStorageValue(oldSource))
            assertTrue(PhoneMapRendererCatalog.availableOnlineSources().none { it.name == oldSource })
            assertTrue(PhoneMapRendererCatalog.availableComparisonOnlineSources().none { it.name == oldSource })
        }
    }

    @Test
    fun savedMaptoolkitComparisonIsClearedOnRestore() {
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }
        val saved = with(phoneMapUiStateSaver) { with(scope) { save(PhoneMapUiState()) } } as List<*>
        listOf("MAPTOOLKIT_HIKING", "MAPTOOLKIT_CYCLING").forEach { oldSource ->
            val oldState = saved.toMutableList()
            oldState[oldState.indexOf("comparisonOnline") + 1] = oldSource
            val restored = requireNotNull(phoneMapUiStateSaver.restore(oldState))
            assertNull(restored.comparison.layer)
        }
    }
}
