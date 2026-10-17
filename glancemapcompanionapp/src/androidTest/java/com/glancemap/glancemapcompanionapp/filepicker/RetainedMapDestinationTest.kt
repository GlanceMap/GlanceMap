package com.glancemap.glancemapcompanionapp.filepicker

import android.content.ComponentCallbacks2
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.map.mapViewLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RetainedMapDestinationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val area = mutableStateOf(CompanionHomeArea.SEND_TO_WATCH)
    private val showHost = mutableStateOf(true)
    private val styleLoads = AtomicInteger()
    private var creations = 0
    private var destructions = 0
    private var touches = 0
    private var mapBackPresses = 0
    private var pageBackPresses = 0
    private var lastView: MapView? = null
    private var mapOwner: LifecycleOwner? = null

    @Test
    fun transferLaunchIsLazyAndFullScreenRoundTripsKeepTheNativeViewAndStyle() {
        setContent()
        composeRule.runOnIdle { assertEquals(0, creations) }
        navigate(CompanionHomeArea.MAP)
        composeRule.waitUntil(timeoutMillis = 5_000) { styleLoads.get() == 1 }
        val initial = requireNotNull(lastView)

        composeRule.onNodeWithTag("retained-map").performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertTrue("The page column must let map gestures through", touches > 0) }

        repeat(3) {
            listOf(CompanionHomeArea.MISSION_PLAN, CompanionHomeArea.SEND_TO_WATCH).forEach { destination ->
                navigate(destination)
                composeRule.onNodeWithTag("retained-map").assertIsNotDisplayed()
                composeRule.runOnIdle {
                    assertEquals(Lifecycle.State.CREATED, mapOwner?.lifecycle?.currentState)
                    assertSame(initial, lastView)
                    assertFalse(initial.isDestroyed)
                    assertEquals(0, destructions)
                }
                navigate(CompanionHomeArea.MAP)
                composeRule.runOnIdle {
                    assertEquals(Lifecycle.State.RESUMED, mapOwner?.lifecycle?.currentState)
                    assertSame(initial, lastView)
                    assertEquals(1, creations)
                    assertEquals(1, styleLoads.get())
                }
            }
        }
    }

    @Test
    fun hiddenMapCannotConsumeTheFullScreenPagesBackPress() {
        area.value = CompanionHomeArea.MAP
        setContent()
        composeRule.waitUntil(timeoutMillis = 5_000) { styleLoads.get() == 1 }
        navigate(CompanionHomeArea.MISSION_PLAN)
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, pageBackPresses)
            assertEquals(0, mapBackPresses)
        }
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(1, mapBackPresses) }
    }

    @Test
    fun parentPauseAndResumeRespectHiddenVisibilityAndDisposalDestroysOnce() {
        val parent =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        composeRule.runOnUiThread { parent.lifecycle.currentState = Lifecycle.State.RESUMED }
        area.value = CompanionHomeArea.MAP
        setContent(parent)
        composeRule.waitUntil(timeoutMillis = 5_000) { styleLoads.get() == 1 }
        val initial = requireNotNull(lastView)
        composeRule.runOnIdle { parent.lifecycle.currentState = Lifecycle.State.CREATED }
        composeRule.runOnIdle {
            assertEquals(Lifecycle.State.CREATED, mapOwner?.lifecycle?.currentState)
            assertFalse(initial.isDestroyed)
        }
        navigate(CompanionHomeArea.SEND_TO_WATCH)
        composeRule.runOnIdle { parent.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.runOnIdle { assertEquals(Lifecycle.State.CREATED, mapOwner?.lifecycle?.currentState) }
        navigate(CompanionHomeArea.MAP)
        composeRule.runOnIdle {
            assertSame(initial, lastView)
            assertEquals(Lifecycle.State.RESUMED, mapOwner?.lifecycle?.currentState)
            showHost.value = false
        }
        composeRule.runOnIdle {
            assertTrue(initial.isDestroyed)
            assertEquals(1, destructions)
            parent.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
        composeRule.runOnIdle { assertEquals(1, destructions) }
    }

    @Test
    fun memoryPressureCanReleaseTheHiddenMapAndItsNextVisitRecreatesIt() {
        area.value = CompanionHomeArea.MAP
        setContent()
        composeRule.waitUntil(timeoutMillis = 5_000) { styleLoads.get() == 1 }
        val initial = requireNotNull(lastView)
        composeRule.runOnIdle {
            composeRule.activity.application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }
        composeRule.runOnIdle { assertEquals(0, destructions) }
        navigate(CompanionHomeArea.MISSION_PLAN)
        composeRule.runOnIdle {
            composeRule.activity.application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        }
        composeRule.runOnIdle {
            assertEquals(0, destructions)
            assertFalse(initial.isDestroyed)
            composeRule.activity.application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }
        composeRule.runOnIdle {
            assertEquals(1, destructions)
            assertTrue(initial.isDestroyed)
        }
        navigate(CompanionHomeArea.MAP)
        composeRule.waitUntil(timeoutMillis = 5_000) { styleLoads.get() == 2 }
        composeRule.runOnIdle {
            assertEquals(2, creations)
            assertEquals(1, destructions)
            assertFalse(requireNotNull(lastView).isDestroyed)
        }
    }

    private fun setContent(parent: LifecycleOwner? = null) {
        composeRule.runOnUiThread { ensureMapLibreConfigured(composeRule.activity) }
        composeRule.setContent {
            MaterialTheme {
                val owner = parent ?: LocalLifecycleOwner.current
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (showHost.value) host()
                }
            }
        }
    }

    @Composable
    private fun host() {
        BackHandler(enabled = !area.value.usesMapDestination()) {
            pageBackPresses += 1
            area.value = CompanionHomeArea.MAP
        }
        RetainedMapDestination(
            area = area.value,
            mapContent = { visible ->
                mapOwner = LocalLifecycleOwner.current
                BackHandler { mapBackPresses += 1 }
                var view by remember { mutableStateOf<MapView?>(null) }
                mapViewLifecycle(view) { destructions += 1 }
                AndroidView(
                    factory = { context ->
                        MapView(context)
                            .apply {
                                onCreate(null)
                                setOnTouchListener { _, _ ->
                                    touches += 1
                                    false
                                }
                                getMapAsync { map -> map.setStyle(Style.Builder()) { styleLoads.incrementAndGet() } }
                            }.also {
                                creations += 1
                                view = it
                                lastView = it
                            }
                    },
                    update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
                    modifier = Modifier.fillMaxSize().testTag("retained-map"),
                )
            },
        ) {
            if (!area.value.usesMapDestination()) Text(area.value.name)
        }
    }

    private fun navigate(destination: CompanionHomeArea) {
        composeRule.runOnIdle { area.value = destination }
        composeRule.waitForIdle()
    }
}
