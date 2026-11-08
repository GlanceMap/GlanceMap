package com.glancemap.glancemapwearos.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Terrain3dSettingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun enabledSettingSurvivesLegacyRendererAndAnglePreferences() {
        val preferences =
            mutablePreferencesOf(
                TERRAIN_3D_ENABLED to true,
                stringPreferencesKey("terrain_3d_renderer") to "custom",
                intPreferencesKey("terrain_3d_viewing_angle") to 45,
            )
        assertTrue(preferences.terrain3dEnabled())
    }

    @Test
    fun defaultsOffAndSurvivesDataStoreReopen() =
        runBlocking {
            val file = temporaryFolder.newFile("terrain.preferences_pb")
            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val first = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            assertFalse(first.data.first().terrain3dEnabled())
            first.edit {
                it[TERRAIN_3D_ENABLED] = true
            }
            assertTrue(first.data.first().terrain3dEnabled())
            firstScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            firstScope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val second = PreferenceDataStoreFactory.create(scope = secondScope) { file }
                assertTrue(second.data.first().terrain3dEnabled())
                second.edit { it[TERRAIN_3D_ENABLED] = false }
                assertFalse(second.data.first().terrain3dEnabled())
            } finally {
                secondScope.cancel()
            }
        }
}
