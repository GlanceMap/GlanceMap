package com.glancemap.glancemapwearos.macrobenchmark

import android.content.Intent
import android.os.Build
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.PowerCategory
import androidx.benchmark.macro.PowerCategoryDisplayLevel
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchNavigationBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait(launchIntent())
    }

    @Test
    fun navigateFrameTiming() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait(launchIntent())
            device.waitForIdle()
        }
    ) {
        repeat(PAN_REPEATS_PER_ITERATION) {
            panNavigateMap()
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun navigateActiveSessionBaseline() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = navigateBaselineMetrics(),
        iterations = 5,
        setupBlock = {
            launchNavigateAndWait()
        }
    ) {
        Thread.sleep(NAVIGATE_IDLE_SETTLE_MS)
        repeat(PAN_REPEATS_PER_ITERATION) {
            panNavigateMap()
        }
        Thread.sleep(NAVIGATE_POST_PAN_SETTLE_MS)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun mapLoadHotPathBaseline() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = mapLoadMetrics(),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait(launchIntent())
        device.waitForIdle()
        Thread.sleep(MAP_LOAD_SETTLE_MS)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun reliefPanMemoryBaseline() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = reliefBaselineMetrics(),
        iterations = 5,
        setupBlock = {
            launchNavigateAndWait()
        }
    ) {
        Thread.sleep(NAVIGATE_IDLE_SETTLE_MS)
        repeat(RELIEF_PAN_REPEATS_PER_ITERATION) {
            panNavigateMap()
        }
        Thread.sleep(RELIEF_POST_PAN_SETTLE_MS)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun navigateBatteryImpact() {
        assumeTrue(
            "Battery benchmarks need a physical device.",
            !isEmulator()
        )
        assumeTrue(
            "Battery benchmarks require sufficient device charge.",
            PowerMetric.deviceBatteryHasMinimumCharge()
        )

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(configurePowerMetric()),
            iterations = 3,
            setupBlock = {
                pressHome()
                startActivityAndWait(launchIntent())
                device.waitForIdle()
            }
        ) {
            repeat(BATTERY_PAN_CYCLES) {
                panNavigateMap()
            }
            Thread.sleep(BATTERY_SETTLE_MS)
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun configurePowerMetric(): PowerMetric {
        return if (PowerMetric.deviceSupportsHighPrecisionTracking()) {
            PowerMetric(
                type = PowerMetric.Type.Energy(
                    mapOf(
                        PowerCategory.CPU to PowerCategoryDisplayLevel.TOTAL,
                        PowerCategory.DISPLAY to PowerCategoryDisplayLevel.TOTAL,
                        PowerCategory.GPS to PowerCategoryDisplayLevel.TOTAL,
                        PowerCategory.GPU to PowerCategoryDisplayLevel.TOTAL,
                        PowerCategory.NETWORK to PowerCategoryDisplayLevel.TOTAL,
                    )
                )
            )
        } else {
            PowerMetric(type = PowerMetric.Type.Battery())
        }
    }

    private fun MacrobenchmarkScope.panNavigateMap() {
        val centerY = device.displayHeight / 2
        val startX = (device.displayWidth * 0.75f).toInt()
        val endX = (device.displayWidth * 0.25f).toInt()

        device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
        device.waitForIdle()
        device.swipe(endX, centerY, startX, centerY, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.launchNavigateAndWait() {
        pressHome()
        startActivityAndWait(launchIntent())
        device.waitForIdle()
        Thread.sleep(APP_LAUNCH_SETTLE_MS)
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun navigateBaselineMetrics() =
        listOf(
            MemoryUsageMetric(
                mode = MemoryUsageMetric.Mode.Max,
                subMetrics =
                    listOf(
                        MemoryUsageMetric.SubMetric.HeapSize,
                        MemoryUsageMetric.SubMetric.RssAnon,
                    ),
            ),
            TraceSectionMetric(
                sectionName = "recompose.NavigateScreen",
                mode = TraceSectionMetric.Mode.Count,
                label = "navigateScreenRecompose",
            ),
            TraceSectionMetric(
                sectionName = "recompose.NavigateContent",
                mode = TraceSectionMetric.Mode.Count,
                label = "navigateContentRecompose",
            ),
        )

    @OptIn(ExperimentalMetricApi::class)
    private fun mapLoadMetrics() =
        listOf(
            StartupTimingMetric(),
            TraceSectionMetric(
                sectionName = "mapRenderer.updateMapLayer",
                mode = TraceSectionMetric.Mode.Sum,
                label = "mapLayerUpdate",
            ),
            TraceSectionMetric(
                sectionName = "mapRenderer.firstVisibleMap",
                mode = TraceSectionMetric.Mode.Sum,
                label = "firstVisibleMap",
            ),
            TraceSectionMetric(
                sectionName = "mapRenderer.firstVisibleMap",
                mode = TraceSectionMetric.Mode.Count,
                label = "firstVisibleMapCount",
            ),
            TraceSectionMetric(
                sectionName = "mapRenderer.openMapFile",
                mode = TraceSectionMetric.Mode.Sum,
                label = "mapFileOpen",
            ),
            TraceSectionMetric(
                sectionName = "mapRenderer.buildRenderThemeOrNull",
                mode = TraceSectionMetric.Mode.Sum,
                label = "renderThemeBuild",
            ),
            TraceSectionMetric(
                sectionName = "mapViewModel.applyThemeSelection",
                mode = TraceSectionMetric.Mode.Sum,
                label = "themeSelectionApply",
            ),
            TraceSectionMetric(
                sectionName = "themeComposer.createDynamicThemeFileOrNull",
                mode = TraceSectionMetric.Mode.Sum,
                label = "dynamicThemeCreate",
            ),
        )

    @OptIn(ExperimentalMetricApi::class)
    private fun reliefBaselineMetrics() =
        listOf(
            MemoryUsageMetric(
                mode = MemoryUsageMetric.Mode.Max,
                subMetrics =
                    listOf(
                        MemoryUsageMetric.SubMetric.HeapSize,
                        MemoryUsageMetric.SubMetric.RssAnon,
                        MemoryUsageMetric.SubMetric.RssFile,
                    ),
            ),
            TraceSectionMetric(
                sectionName = "relief.demReadDecode",
                mode = TraceSectionMetric.Mode.Sum,
                label = "reliefDemReadDecode",
            ),
            TraceSectionMetric(
                sectionName = "relief.overlayTileBuild",
                mode = TraceSectionMetric.Mode.Sum,
                label = "reliefTileBuild",
            ),
            TraceSectionMetric(
                sectionName = "relief.overlayTileBuild",
                mode = TraceSectionMetric.Mode.Count,
                label = "reliefTileBuildCount",
            ),
        )

    private fun launchIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(TARGET_PACKAGE, MAIN_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
    }

    companion object {
        private const val TARGET_PACKAGE = "com.glancemap.glancemapwearos"
        private const val MAIN_ACTIVITY_CLASS = "$TARGET_PACKAGE.presentation.MainActivity"
        private const val SWIPE_STEPS = 24
        private const val PAN_REPEATS_PER_ITERATION = 2
        private const val RELIEF_PAN_REPEATS_PER_ITERATION = 4
        private const val APP_LAUNCH_SETTLE_MS = 1_500L
        private const val NAVIGATE_IDLE_SETTLE_MS = 1_000L
        private const val NAVIGATE_POST_PAN_SETTLE_MS = 1_000L
        private const val MAP_LOAD_SETTLE_MS = 4_000L
        private const val RELIEF_POST_PAN_SETTLE_MS = 2_000L
        private const val BATTERY_PAN_CYCLES = 5
        private const val BATTERY_SETTLE_MS = 2_000L
    }
}
