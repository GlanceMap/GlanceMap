package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.OnlineMapProvider
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.ColorReliefLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

internal fun OnlineMapProvider.mapLibreOnlineStyle(additionalAttribution: String? = null): Style.Builder =
    styleUrl?.let { Style.Builder().fromUri(it) }
        ?: Style.Builder().fromJson(mapLibreRasterStyleJson(additionalAttribution))

internal fun Style.prepareOnlineAttribution(
    provider: OnlineMapProvider,
    additionalAttribution: String?,
) {
    if (provider.styleUrl == null) return
    val attribution = listOfNotNull(provider.attribution, additionalAttribution).distinct().joinToString(" | ")
    // No layers or tile URLs: this source contributes credits without requesting another map.
    val credits = TileSet("2.2.0").apply { this.attribution = attribution }
    addSource(RasterSource("online-provider-credits", credits, 256))
}

/** Capture before app overlays are added, so comparison fading affects only the basemap. */
internal class MapLibreBasemapOpacity(
    style: Style,
) {
    private val originalPaint =
        style.layers.mapNotNull { layer ->
            layer.opacityProperties().takeIf { it.isNotEmpty() }?.let { layer to it }
        }

    fun apply(opacity: Float) {
        originalPaint.forEach { (layer, properties) ->
            properties.forEach { property ->
                layer.setProperties(PaintPropertyValue(property.name, scaledBasemapOpacity(property, opacity)))
            }
        }
    }
}

private fun Layer.opacityProperties(): List<PropertyValue<Float>> =
    when (this) {
        is BackgroundLayer -> listOf(backgroundOpacity)
        is RasterLayer -> listOf(rasterOpacity)
        is ColorReliefLayer -> listOf(colorReliefOpacity)
        is HillshadeLayer -> listOf(hillshadeExaggeration)
        is FillLayer -> listOf(fillOpacity)
        is LineLayer -> listOf(lineOpacity)
        is SymbolLayer -> listOf(textOpacity, iconOpacity)
        is CircleLayer -> listOf(circleOpacity, circleStrokeOpacity)
        is FillExtrusionLayer -> listOf(fillExtrusionOpacity)
        else -> emptyList()
    }

internal fun scaledBasemapOpacity(
    original: PropertyValue<*>,
    opacity: Float,
): Expression {
    val alpha = phoneMapLibreRasterOpacity(opacity)
    val defaultValue = if (original.name == "hillshade-exaggeration") 0.5f else 1f
    val expression =
        if (original.isExpression) {
            requireNotNull(original.expression)
        } else {
            Expression.literal(original.value ?: defaultValue)
        }
    val json = if (original.isExpression) JsonParser.parseString(expression.toString()).asJsonArray else null
    val firstOutput =
        when (json?.get(0)?.asString) {
            "interpolate" -> 4
            "step" -> 2
            else -> null
        }
    return if (firstOutput != null && json != null) {
        // Zoom must stay at the top level; scale stop outputs rather than wrapping the expression.
        for (index in firstOutput until json.size() step 2) {
            json.set(
                index,
                JsonArray().apply {
                    add("*")
                    add(alpha)
                    add(json[index])
                },
            )
        }
        Expression.raw(json.toString())
    } else {
        Expression.product(Expression.literal(alpha), expression)
    }
}
