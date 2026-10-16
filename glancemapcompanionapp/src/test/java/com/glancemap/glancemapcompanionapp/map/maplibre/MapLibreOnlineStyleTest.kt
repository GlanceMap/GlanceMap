package com.glancemap.glancemapcompanionapp.map.maplibre

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyValue

class MapLibreOnlineStyleTest {
    @Test
    fun comparisonOpacityMultipliesTheOriginalPaintWithoutAccumulating() {
        val original = PropertyValue("fill-opacity", 0.6f)
        listOf(0.5f, 0f, 1f, 0.5f).forEach { opacity ->
            assertEquals(
                Expression.product(Expression.literal(opacity), Expression.literal(0.6f)),
                scaledBasemapOpacity(original, opacity),
            )
        }
    }

    @Test
    fun comparisonOpacityPreservesZoomExpressionsAndDefaultsUnsetOpacityToOne() {
        val expression = Expression.step(Expression.zoom(), Expression.literal(0.4f), Expression.stop(10, 0.8f))
        val original = PropertyValue("line-opacity", expression)
        assertEquals(
            Expression.step(
                Expression.zoom(),
                Expression.product(0.5f, 0.4f),
                Expression.stop(10, Expression.product(0.5f, 0.8f)),
            ),
            scaledBasemapOpacity(original, 0.5f),
        )
        assertEquals(
            Expression.product(Expression.literal(1f), Expression.literal(1f)),
            scaledBasemapOpacity(PropertyValue<Float>("text-opacity", null), 2f),
        )
    }

    @Test
    fun comparisonOpacityKeepsInterpolationAtTheTopLevel() {
        val expression =
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(0, 0.6f),
                Expression.stop(6, 0.1f),
            )
        assertEquals(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(0, Expression.product(0.5f, 0.6f)),
                Expression.stop(6, Expression.product(0.5f, 0.1f)),
            ),
            scaledBasemapOpacity(PropertyValue("raster-opacity", expression), 0.5f),
        )
        assertEquals(
            Expression.product(Expression.literal(0.5f), Expression.get("opacity")),
            scaledBasemapOpacity(PropertyValue("fill-opacity", Expression.get("opacity")), 0.5f),
        )
    }
}
