package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@RunWith(AndroidJUnit4::class)
class PhoneOfflineMapCompositingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun comparisonClearPreservesBaseAndBlendsMapWithoutFadingSemanticOverlays() {
        val opacity = mutableFloatStateOf(1f)
        composeRule.setContent {
            Box(Modifier.size(96.dp).background(Color.Red).testTag("map-blend")) {
                AndroidView(
                    factory = { MapsforgeComparisonDrawingView(it) },
                    update = { it.setMapOpacity(opacity.floatValue) },
                    modifier = Modifier.fillMaxSize().phoneOfflineMapCompositing(transparentBackground = true),
                )
            }
        }

        listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0.5f, 0f).forEach { alpha ->
            composeRule.runOnIdle { opacity.floatValue = alpha }
            val image = composeRule.onNodeWithTag("map-blend").captureToImage().asAndroidBitmap()
            val blended = image.getPixel(image.width / 4, image.height / 4)
            assertEquals("opaque composite at $alpha", 255, AndroidColor.alpha(blended))
            assertTrue("base red at $alpha", abs(AndroidColor.red(blended) - ((1f - alpha) * 255f).roundToInt()) <= 2)
            assertTrue("comparison blue at $alpha", abs(AndroidColor.blue(blended) - (alpha * 255f).roundToInt()) <= 2)
            assertEquals("semantic overlay at $alpha", AndroidColor.GREEN, image.getPixel(image.width / 2, image.height / 2))
        }
    }
}

/** Exercises Mapsforge's real CLEAR operation without a map file, tile jobs, or network access. */
private class MapsforgeComparisonDrawingView(
    context: Context,
) : View(context) {
    private val mapPaint = Paint().apply { color = AndroidColor.BLUE }
    private val overlayPaint = Paint().apply { color = AndroidColor.GREEN }

    fun setMapOpacity(opacity: Float) {
        mapPaint.alpha = (opacity * 255f).roundToInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        AndroidGraphicFactory.createGraphicContext(canvas).fillColor(AndroidColor.TRANSPARENT)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mapPaint)
        canvas.drawCircle(width / 2f, height / 2f, width / 10f, overlayPaint)
    }
}
