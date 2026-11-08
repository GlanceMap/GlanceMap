package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class WorldWindBitmapTest {
    @Test
    fun coalescedPixelsProduceIndependentBitmaps() {
        val pixels = IntArray(256 * 256) { if (it % 2 == 0) Color.RED else Color.BLUE }
        val first = worldWindBitmap(pixels)
        val second = worldWindBitmap(pixels)

        assertEquals(Color.RED, first.getPixel(0, 0))
        assertEquals(Color.BLUE, second.getPixel(1, 0))
        first.recycle()
        assertFalse(second.isRecycled)
        assertEquals(Color.RED, second.getPixel(0, 0))
        second.recycle()
    }

    @Test
    fun directPixelsMatchPreviousCompressedTileColors() {
        for (config in listOf(Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888)) {
            val source = Bitmap.createBitmap(256, 256, config)
            source.eraseColor(Color.rgb(75, 130, 220))
            source.setPixel(0, 0, Color.argb(128, 120, 32, 255))
            val encoded = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val bytes = encoded.toByteArray()
            val previous = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            val pixels = IntArray(256 * 256)
            source.getPixels(pixels, 0, 256, 0, 0, 256, 256)
            val direct = worldWindBitmap(pixels)
            val previousPixels = IntArray(pixels.size)
            val directPixels = IntArray(pixels.size)
            previous.getPixels(previousPixels, 0, 256, 0, 0, 256, 256)
            direct.getPixels(directPixels, 0, 256, 0, 0, 256, 256)
            assertArrayEquals(previousPixels, directPixels)
            source.recycle()
            previous.recycle()
            direct.recycle()
        }
    }
}
