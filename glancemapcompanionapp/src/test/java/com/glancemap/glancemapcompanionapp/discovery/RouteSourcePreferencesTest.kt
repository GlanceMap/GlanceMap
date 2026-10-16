package com.glancemap.glancemapcompanionapp.discovery

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class RouteSourcePreferencesTest {
    @Test
    fun independentSourceTogglesPersistAndDefaultsNeedNoCredentials() {
        val values = mutableMapOf<String, Any>()
        val pending = mutableMapOf<String, Any>()
        val editor =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putBoolean", "putString" -> {
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "apply" -> {
                        values.putAll(pending)
                        pending.clear()
                        null
                    }
                    else -> error("Unexpected editor call")
                }
            } as SharedPreferences.Editor
        val preferences =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getBoolean", "getString" -> values[args[0]] ?: args[1]
                    "edit" -> editor
                    else -> error("Unexpected preference call")
                }
            } as SharedPreferences
        val store = RouteSourcePreferences(preferences)
        assertEquals(setOf(TrailProvider.OPEN_STREET_MAP), store.load().enabled)
        val saved = store.save(store.load().copy(enabled = setOf(TrailProvider.GEOTREK, TrailProvider.DATATOURISME)))
        assertEquals(saved, RouteSourcePreferences(preferences).load())
        val disabled = store.save(saved.copy(enabled = emptySet()))
        assertEquals(disabled, RouteSourcePreferences(preferences).load())
    }
}
