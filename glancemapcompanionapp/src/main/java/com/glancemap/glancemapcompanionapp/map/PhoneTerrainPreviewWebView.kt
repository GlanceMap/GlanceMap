package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream

@Composable
internal fun phoneTerrainPreviewWebView(
    payload: String,
    basemap: PhoneTerrainPreviewBasemap,
    onWebView: (WebView?) -> Unit,
    onStatus: (PhoneTerrainPreviewStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(view, lifecycleOwner) {
        val currentView = view
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> currentView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> {
                        currentView?.evaluateJavascript(
                            "if(window.GlanceMapTerrain)window.GlanceMapTerrain.pause()",
                            null,
                        )
                        currentView?.onPause()
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { context ->
            try {
                createTerrainWebView(context, payload, basemap, onStatus) { url ->
                    runCatching { uriHandler.openUri(url) }
                }.also {
                    view = it
                    onWebView(it)
                }
            } catch (_: RuntimeException) {
                onStatus(PhoneTerrainPreviewStatus.ERROR)
                View(context)
            }
        },
        modifier = modifier,
        onRelease = {
            onWebView(null)
            if (it is WebView) {
                it.stopLoading()
                it.webChromeClient = null
                it.webViewClient = WebViewClient()
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled") // Only bundled scripts run; there is no native JavaScript interface.
private fun createTerrainWebView(
    context: Context,
    payload: String,
    basemap: PhoneTerrainPreviewBasemap,
    onStatus: (PhoneTerrainPreviewStatus) -> Unit,
    onOpenLink: (String) -> Unit,
): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = TerrainPreviewWebClient(context, payload, basemap, onStatus, onOpenLink)
        webChromeClient =
            object : WebChromeClient() {
                override fun onReceivedTitle(
                    view: WebView?,
                    title: String?,
                ) {
                    when (title) {
                        "GlanceMap terrain ready" -> onStatus(PhoneTerrainPreviewStatus.READY)
                        "GlanceMap terrain error" -> onStatus(PhoneTerrainPreviewStatus.ERROR)
                        "GlanceMap terrain unsupported" -> onStatus(PhoneTerrainPreviewStatus.UNSUPPORTED)
                        "GlanceMap terrain loading" -> onStatus(PhoneTerrainPreviewStatus.LOADING)
                    }
                }
            }
        loadUrl(PHONE_TERRAIN_PREVIEW_URL)
    }

private class TerrainPreviewWebClient(
    private val context: Context,
    private val payload: String,
    private val basemap: PhoneTerrainPreviewBasemap,
    private val onStatus: (PhoneTerrainPreviewStatus) -> Unit,
    private val onOpenLink: (String) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        val asset = terrainPreviewAssetName(url)
        return when {
            asset != null -> {
                val mime =
                    when (asset.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "css" -> "text/css"
                        else -> "text/javascript"
                    }
                WebResourceResponse(mime, "UTF-8", context.assets.open("terrain-preview/$asset"))
            }
            terrainPreviewAllowsRemoteResource(url) -> null
            else ->
                WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    403,
                    "Blocked",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        if (url.toString() == PHONE_TERRAIN_PREVIEW_URL) return false
        if (request.isForMainFrame && request.hasGesture() && url.scheme == "https") onOpenLink(url.toString())
        return true
    }

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        if (url == PHONE_TERRAIN_PREVIEW_URL) {
            view.evaluateJavascript(
                "if(window.GlanceMapTerrain){window.GlanceMapTerrain.start(Object.assign($payload," +
                    "{initialBasemap:'${basemap.name}'}));}" +
                    "else{document.title='GlanceMap terrain unsupported';}",
                null,
            )
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError?,
    ) {
        if (request.isForMainFrame) onStatus(PhoneTerrainPreviewStatus.ERROR)
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        onStatus(PhoneTerrainPreviewStatus.ERROR)
        return true
    }
}
