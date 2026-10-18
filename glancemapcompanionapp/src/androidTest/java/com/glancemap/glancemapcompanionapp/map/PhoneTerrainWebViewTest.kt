package com.glancemap.glancemapcompanionapp.map

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class PhoneTerrainWebViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bundledWebViewRendersTerrainAndSwitchesEveryBasemap() {
        val status = AtomicReference(PhoneTerrainPreviewStatus.LOADING)
        val webView = AtomicReference<WebView?>()
        val payload =
            PhoneTerrainPreviewData(
                PhoneMapCameraSnapshot(45.98, 7.66, 12.0),
                PhoneOnlineMapSource.MAPTOOLKIT_HIKING,
                listOf(PhoneMapRouteSegment(listOf(GeoPoint(45.98, 7.66), GeoPoint(45.99, 7.67)))),
            ).toPreviewJson()
        compose.setContent {
            phoneTerrainPreviewWebView(
                payload,
                PhoneTerrainPreviewBasemap.HIKING,
                { webView.set(it) },
                { status.set(it) },
                Modifier.fillMaxSize(),
            )
        }
        compose.waitUntil(60_000) { status.get() != PhoneTerrainPreviewStatus.LOADING }
        assertEquals("Initial hiking terrain", PhoneTerrainPreviewStatus.READY, status.get())
        for (basemap in listOf("CYCLING", "SATELLITE")) {
            status.set(PhoneTerrainPreviewStatus.LOADING)
            compose.runOnIdle {
                webView.get()!!.evaluateJavascript("window.GlanceMapTerrain.setBasemap('$basemap')", null)
            }
            compose.waitUntil(60_000) { status.get() != PhoneTerrainPreviewStatus.LOADING }
            assertEquals(basemap, PhoneTerrainPreviewStatus.READY, status.get())
        }
    }
}
