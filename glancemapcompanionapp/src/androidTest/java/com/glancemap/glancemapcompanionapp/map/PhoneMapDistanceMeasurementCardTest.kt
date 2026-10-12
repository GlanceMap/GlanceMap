package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PhoneMapDistanceMeasurementCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cardSamplesBothEndpointsRefreshesDemAndCloses() {
        val folder = temporaryFolder.newFolder()
        writeTile(folder, "N00E000.hgt", 0)
        writeTile(folder, "N01E000.hgt", -12)
        val repository = PhoneElevationRepository(listOf(folder))
        val measurement =
            mutableStateOf(
                PhoneMapDistanceMeasurement(PhoneMapCoordinate(0.25, 0.25), PhoneMapCoordinate(1.25, 0.25)),
            )
        val terrainVersion = mutableStateOf(0L)
        var cleared = false
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    phoneMapDistanceMeasurementCard(
                        measurement = measurement.value,
                        elevationRepository = repository,
                        terrainDataVersion = terrainVersion.value,
                        isMetric = true,
                        onClear = { cleared = true },
                    )
                }
            }
        }
        awaitElevationText("Elevation · A: 0 m · B: -12 m")

        composeRule.runOnIdle {
            measurement.value = measurement.value.moveEndpoint(0, PhoneMapCoordinate(2.25, 0.25))
        }
        awaitElevationText("Elevation · A: — · B: -12 m")

        writeTile(folder, "N01E000.hgt", 200)
        composeRule.runOnIdle {
            repository.invalidate()
            terrainVersion.value += 1L
        }
        awaitElevationText("Elevation · A: — · B: 200 m")

        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.common_action_close))
            .performClick()
        composeRule.runOnIdle { assertTrue(cleared) }
    }

    @Test
    fun movingAnEndpointWhileDemLoadsCannotPublishOldElevations() {
        val folder = temporaryFolder.newFolder()
        writeTile(folder, "N00E000.hgt", 100)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockFirstLookup = AtomicBoolean(true)
        val repository =
            PhoneElevationRepository {
                if (blockFirstLookup.compareAndSet(true, false)) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                listOf(folder)
            }
        val measurement =
            mutableStateOf(
                PhoneMapDistanceMeasurement(PhoneMapCoordinate(0.25, 0.25), PhoneMapCoordinate(0.75, 0.75)),
            )
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    phoneMapDistanceMeasurementCard(
                        measurement = measurement.value,
                        elevationRepository = repository,
                        terrainDataVersion = 0L,
                        isMetric = true,
                        onClear = {},
                    )
                }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            composeRule.runOnIdle {
                measurement.value = measurement.value.moveEndpoint(0, PhoneMapCoordinate(2.25, 0.25))
            }
            awaitElevationText("Elevation · A: — · B: 100 m")
        } finally {
            release.countDown()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Elevation · A: — · B: 100 m").assertIsDisplayed()
    }

    @Test
    fun movingPointsAndDisablingFallbackCancelPendingOnlineReadings() {
        val local = PhoneElevationRepository(listOf(temporaryFolder.newFolder()))
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val blockFirstLookup = AtomicBoolean(true)
        val online =
            PhoneOnlineElevationRepository(fetchTile = {
                if (blockFirstLookup.compareAndSet(true, false)) {
                    started.countDown()
                    release.await()
                    PhoneTerrariumTile(IntArray(4) { 0xff806400.toInt() }, 2)
                } else {
                    PhoneTerrariumTile(IntArray(4) { 0xff80c800.toInt() }, 2)
                }
            })
        val enabled = mutableStateOf(true)
        val measurement =
            mutableStateOf(
                PhoneMapDistanceMeasurement(PhoneMapCoordinate(0.25, 0.25), PhoneMapCoordinate(0.75, 0.75)),
            )
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    phoneMapDistanceMeasurementCard(
                        measurement = measurement.value,
                        elevationRepository = local,
                        terrainDataVersion = 0L,
                        isMetric = true,
                        onClear = {},
                        onlineElevationRepository = online.takeIf { enabled.value },
                    )
                }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            composeRule.runOnIdle { enabled.value = false }
            awaitElevationText("Elevation · A: — · B: —")
            composeRule.runOnIdle {
                measurement.value =
                    PhoneMapDistanceMeasurement(
                        PhoneMapCoordinate(1.25, 0.25),
                        PhoneMapCoordinate(1.75, 0.75),
                    )
                enabled.value = true
            }
            awaitElevationText("Elevation · A: 200 m · B: 200 m")
        } finally {
            release.complete(Unit)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Elevation · A: 200 m · B: 200 m").assertIsDisplayed()
    }

    private fun awaitElevationText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun writeTile(
        folder: File,
        name: String,
        elevation: Int,
    ) {
        DataOutputStream(File(folder, name).outputStream()).use { output ->
            repeat(4) { output.writeShort(elevation) }
        }
    }
}
