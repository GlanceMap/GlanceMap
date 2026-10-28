package com.glancemap.glancemapwearos.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Terrain3dSettingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsOffAndSurvivesDataStoreReopen() =
        runBlocking {
            val file = temporaryFolder.newFile("terrain.preferences_pb")
            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val first = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            assertFalse(first.data.first().terrain3dEnabled())
            first.edit { it[TERRAIN_3D_ENABLED] = true }
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

    @Test
    fun viewingAngleDefaultsTo60AndRejectsInvalidValues() {
        assertEquals(60, emptyPreferences().terrain3dViewingAngle())
        assertEquals(60, mutablePreferencesOf(TERRAIN_3D_VIEWING_ANGLE to 47).terrain3dViewingAngle())
        assertEquals(60, mutablePreferencesOf(TERRAIN_3D_VIEWING_ANGLE to -1).terrain3dViewingAngle())
    }

    @Test
    fun supportedViewingAnglesSurviveDataStoreReopen() =
        runBlocking {
            val file = temporaryFolder.newFile("terrain-angle.preferences_pb")
            for (angle in listOf(45, 50, 55, 60, 65, 70)) {
                val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val writer = PreferenceDataStoreFactory.create(scope = writeScope) { file }
                writer.edit { it[TERRAIN_3D_VIEWING_ANGLE] = angle }
                writeScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

                val readScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val reader = PreferenceDataStoreFactory.create(scope = readScope) { file }
                assertEquals(angle, reader.data.first().terrain3dViewingAngle())
                readScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
            }
        }
}
