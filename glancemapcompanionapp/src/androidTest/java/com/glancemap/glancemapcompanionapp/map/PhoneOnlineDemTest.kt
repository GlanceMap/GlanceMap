package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PhoneOnlineDemTest {
    @Test
    fun androidPngDecoderPreservesTerrariumChannelsAndRejectsWrongDimensions() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff89db44.toInt())
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            assertEquals(2523.265625, decodePhoneTerrariumPng(bytes)!!.elevationAt(10.0, 10.0)!!, 0.000001)
        } finally {
            bitmap.recycle()
        }
        val wrongSize = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val bytes = ByteArrayOutputStream().also { wrongSize.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            assertNull(decodePhoneTerrariumPng(bytes))
        } finally {
            wrongSize.recycle()
        }
        assertNull(decodePhoneTerrariumPng(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun fallbackPreferenceSurvivesReloadAndDoesNotEnableHillshade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PhoneMapSettingsPreferences(context)
        val original = preferences.load()
        try {
            preferences.save(original.copy(onlineElevationEnabled = false, hillShadingEnabled = false))
            assertFalse(preferences.load().onlineElevationEnabled)
            assertFalse(preferences.load().hillShadingEnabled)
            preferences.save(preferences.load().copy(onlineElevationEnabled = true))
            assertTrue(preferences.load().onlineElevationEnabled)
            assertFalse(preferences.load().hillShadingEnabled)
        } finally {
            preferences.save(original)
        }
    }
}
