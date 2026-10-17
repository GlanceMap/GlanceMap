package com.glancemap.glancemapcompanionapp.filepicker

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner

internal fun CompanionHomeArea.usesMapDestination(): Boolean =
    when (this) {
        CompanionHomeArea.MAP,
        CompanionHomeArea.LIBRARY,
        CompanionHomeArea.SETTINGS,
        CompanionHomeArea.HOME,
        CompanionHomeArea.ROUTES,
        CompanionHomeArea.DISCOVER_ROUTES,
        -> true
        else -> false
    }

/** Keep one lazily created map in a stable slot while full-screen destinations cover it. */
@Composable
internal fun RetainedMapDestination(
    area: CompanionHomeArea,
    mapContent: @Composable (visible: Boolean) -> Unit,
    pageModifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val mapVisible = area.usesMapDestination()
    var retainMap by remember { mutableStateOf(false) }
    val currentMapVisible by rememberUpdatedState(mapVisible)
    val applicationContext = LocalContext.current.applicationContext
    SideEffect { if (mapVisible) retainMap = true }

    DisposableEffect(applicationContext) {
        val callbacks =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    if (!currentMapVisible) retainMap = false
                }

                override fun onTrimMemory(level: Int) {
                    if (
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                    ) {
                        onLowMemory()
                    }
                }
            }
        applicationContext.registerComponentCallbacks(callbacks)
        onDispose { applicationContext.unregisterComponentCallbacks(callbacks) }
    }

    Box(Modifier.fillMaxSize()) {
        if (mapVisible || retainMap) {
            val owner =
                rememberLifecycleOwner(
                    maxLifecycle = if (mapVisible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
                )
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(
                    Modifier.fillMaxSize().layout { measurable, constraints ->
                        val map = measurable.measure(constraints)
                        layout(map.width, map.height) {
                            // Keep the viewport dimensions without drawing, hits or accessibility while covered.
                            if (mapVisible) map.placeRelative(0, 0)
                        }
                    },
                ) {
                    mapContent(mapVisible)
                }
            }
        }
        Column(modifier = pageModifier, content = content)
    }
}
