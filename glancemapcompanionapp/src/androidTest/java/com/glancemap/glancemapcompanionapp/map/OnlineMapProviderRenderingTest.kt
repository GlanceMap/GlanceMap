package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.map.maplibre.MapLibreBasemapOpacity
import com.glancemap.glancemapcompanionapp.map.maplibre.prepareOnlineAttribution
import com.glancemap.glancemapcompanionapp.map.maplibre.setOnlineHillshade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.ColorReliefLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyValue
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class OnlineMapProviderRenderingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun newProvidersCanBeSelectedFromTheExistingDropdown() {
        val selected = mutableStateOf(PhoneOnlineMapSource.OPEN_TOPO)
        composeRule.setContent {
            MaterialTheme {
                mapToolsOnlineSourcePicker(PhoneMapRendererCatalog.availableOnlineSources(), selected.value) {
                    selected.value = it
                }
            }
        }
        val online = composeRule.activity.getString(com.glancemap.glancemapcompanionapp.R.string.map_source_online)
        val sources =
            listOf(
                PhoneOnlineMapSource.OPEN_FREE_MAP,
                PhoneOnlineMapSource.IGN_ORTHOPHOTO,
                PhoneOnlineMapSource.SWISS_TOPO,
                PhoneOnlineMapSource.SWISS_IMAGE,
                PhoneOnlineMapSource.USGS_TOPO,
                PhoneOnlineMapSource.USGS_IMAGERY,
                PhoneOnlineMapSource.ESRI_WORLD_IMAGERY,
                PhoneOnlineMapSource.MAPTOOLKIT_HIKING,
                PhoneOnlineMapSource.MAPTOOLKIT_CYCLING,
            )
        sources.forEach { source ->
            val currentLabel = PhoneMapRendererCatalog.onlineSourceLabel(selected.value)
            composeRule.onNodeWithText("$online: $currentLabel").performClick()
            composeRule
                .onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(source))
                .performScrollTo()
                .performClick()
            composeRule.runOnIdle { assertEquals(source, selected.value) }
        }
    }

    @Test
    fun vectorComparisonRetainsCreditsOverlaysAndValidZoomExpressions() {
        val loaded = AtomicReference<Style?>()
        val view = AtomicReference<MapView?>()
        composeRule.setContent {
            AndroidView(factory = { context ->
                ensureMapLibreConfigured(context)
                MapView(context).apply {
                    view.set(this)
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        map.setStyle(Style.Builder().fromJson(FIXTURE_STYLE)) { loaded.set(it) }
                    }
                }
            })
        }
        try {
            composeRule.waitUntil(30_000) { loaded.get() != null }
            composeRule.runOnIdle {
                val style = requireNotNull(loaded.get())
                val provider =
                    requireNotNull(
                        PhoneMapRendererCatalog.providerForOnlineSource(PhoneOnlineMapSource.OPEN_FREE_MAP),
                    )
                style.prepareOnlineAttribution(provider, "Base provider credits")
                val credits = requireNotNull(style.getSource("online-provider-credits")).attribution
                assertTrue(credits.contains("Base provider credits"))
                assertTrue(credits.contains("OpenMapTiles"))

                val opacity = MapLibreBasemapOpacity(style)
                val overlay = LineLayer("app-overlay", "fixture").withProperties(lineOpacity(0.9f))
                style.addLayer(overlay)
                opacity.apply(0f)
                assertTerrainOpacity(style, 0f)
                opacity.apply(0.5f)
                assertTerrainOpacity(style, 0.5f)
                assertEquals(0.9f, requireNotNull(overlay.lineOpacity.value), 0f)
                val zoomLayer = requireNotNull(style.getLayerAs<LineLayer>("zoom-line"))
                val zoomExpression = requireNotNull(zoomLayer.lineOpacity.expression)
                assertEquals("interpolate", zoomExpression.toArray()[0])
                assertTrue(zoomExpression.toString().contains("0.5"))

                style.setOnlineHillshade(true, 0.5f)
                assertNotNull(style.getSource("online-terrain-dem"))
                val ids = style.layers.map { it.id }
                assertTrue(ids.indexOf("online-terrain-hillshade") < ids.indexOf("labels"))
                assertTrue(ids.indexOf("online-terrain-hillshade") < ids.indexOf("app-overlay"))
            }
        } finally {
            composeRule.runOnIdle {
                view.get()?.apply {
                    onPause()
                    onStop()
                    onDestroy()
                }
            }
        }
    }

    private fun assertTerrainOpacity(
        style: Style,
        alpha: Float,
    ) {
        val relief = requireNotNull(style.getLayerAs<ColorReliefLayer>("color-relief"))
        val hillshade = requireNotNull(style.getLayerAs<HillshadeLayer>("hillshade"))
        assertScaledProperty(relief.colorReliefOpacity, 0.8f, alpha)
        assertScaledProperty(hillshade.hillshadeExaggeration, 0.5f, alpha)
    }

    private fun assertScaledProperty(
        property: PropertyValue<Float>,
        original: Float,
        alpha: Float,
    ) {
        if (property.isExpression) {
            assertEquals(
                Expression.product(Expression.literal(alpha), Expression.literal(original)).toString(),
                requireNotNull(property.expression).toString(),
            )
        } else {
            assertEquals(original * alpha, requireNotNull(property.value), 0.001f)
        }
    }

    private companion object {
        val FIXTURE_STYLE =
            """
            {"version":8,
             "sources":{"fixture":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}},
               "dem":{"type":"raster-dem","tiles":[],"encoding":"terrarium"}},
             "layers":[
               {"id":"background","type":"background","paint":{"background-color":"#ffffff"}},
               {"id":"color-relief","type":"color-relief","source":"dem",
                "paint":{"color-relief-opacity":0.8}},
               {"id":"hillshade","type":"hillshade","source":"dem",
                "paint":{"hillshade-exaggeration":0.5}},
               {"id":"zoom-line","type":"line","source":"fixture",
                "paint":{"line-opacity":["interpolate",["linear"],["zoom"],0,0.2,12,1]}},
               {"id":"labels","type":"symbol","source":"fixture"}
             ]}
            """.trimIndent()
    }
}
