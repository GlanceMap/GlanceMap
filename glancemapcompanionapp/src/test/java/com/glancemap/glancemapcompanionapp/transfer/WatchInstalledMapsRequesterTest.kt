package com.glancemap.glancemapcompanionapp.transfer

import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WatchInstalledMapsRequesterTest {
    @Test
    fun installedMapQueryUsesListMapsWithoutChannelPrewarm() {
        val path = installedMapQueryPath()

        assertEquals(DataLayerPaths.PATH_LIST_MAPS, path)
        assertNotEquals(DataLayerPaths.PATH_PREPARE_CHANNEL, path)
    }
}
