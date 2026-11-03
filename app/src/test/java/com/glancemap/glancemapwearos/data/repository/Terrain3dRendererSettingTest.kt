package com.glancemap.glancemapwearos.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class Terrain3dRendererSettingTest {
    private val key = stringPreferencesKey("terrain_3d_renderer")

    @Test
    fun `renderer defaults to custom for missing and unknown values`() {
        assertEquals("custom", mutablePreferencesOf().terrain3dRenderer())
        assertEquals("custom", mutablePreferencesOf(key to "unknown").terrain3dRenderer())
    }

    @Test
    fun `worldwind selection is retained`() {
        assertEquals("worldwind", mutablePreferencesOf(key to "worldwind").terrain3dRenderer())
    }

    @Test
    fun `renderer preference does not change existing terrain preferences`() {
        val enabled = booleanPreferencesKey("terrain_3d_enabled")
        val angle = intPreferencesKey("terrain_3d_viewing_angle")
        val preferences = mutablePreferencesOf(enabled to true, angle to 55)
        preferences[key] = "worldwind"
        assertEquals(true, preferences[enabled])
        assertEquals(55, preferences[angle])
        assertEquals("worldwind", preferences.terrain3dRenderer())
    }
}
