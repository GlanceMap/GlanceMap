package com.glancemap.glancemapwearos.presentation.features.poi

import com.glancemap.glancemapwearos.data.repository.SyntheticTopGroup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoiCategoryLabelsTest {
    @Test
    fun `every app-owned synthetic heading has a resource while imported labels pass through`() {
        SyntheticTopGroup.entries.forEach { group ->
            assertNotNull(group.label, poiCategoryNameRes(group.label))
        }
        assertNull(poiCategoryNameRes("User-supplied refuge category"))
    }
}
