package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mapsforge.core.model.LatLong
import java.io.File

class TraceRecordingDraftStoreTest {
    private lateinit var draftDir: File
    private lateinit var store: TraceRecordingDraftStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        draftDir = File(context.cacheDir, "glancemap-recording-draft-test-${System.nanoTime()}").apply { mkdirs() }
        store = TraceRecordingDraftStore(TestContext(context, draftDir))
    }

    @After
    fun tearDown() {
        draftDir.deleteRecursively()
    }

    @Test
    fun saveAndLoadUseJsonOnlyAndPreserveRecoveryState() =
        runBlocking {
            val state = recoveryState()

            val stats = store.save(state = state, lastUiAction = "pause")

            assertTrue(stats.jsonBytesWritten > 0)
            assertEquals(0, stats.gpxBytesWritten)
            assertEquals(state.points.size, stats.pointCount)
            assertTrue(File(draftDir, "current.json").isFile)
            assertFalse(File(draftDir, "current.gpx").exists())
            assertFalse(File(draftDir, "current.gpx.tmp").exists())
            assertEquals(File(draftDir, "current.json").absolutePath, store.draftPath())

            val recovered = requireNotNull(store.load())

            assertEquals(state.active, recovered.active)
            assertEquals(state.paused, recovered.paused)
            assertEquals(state.autoPaused, recovered.autoPaused)
            assertEquals(state.activityProfile, recovered.activityProfile)
            assertEquals(state.trackSmoothingMode, recovered.trackSmoothingMode)
            assertEquals(state.distanceSource, recovered.distanceSource)
            assertEquals(state.startedAtMillis, recovered.startedAtMillis)
            assertEquals(state.pausedAtMillis, recovered.pausedAtMillis)
            assertEquals(state.accumulatedPausedMillis, recovered.accumulatedPausedMillis)
            assertEquals(state.distanceMeters, recovered.distanceMeters, 0.0)
            assertEquals(state.gpsActiveDurationMillis, recovered.gpsActiveDurationMillis)
            assertEquals(state.recordingGapCount, recovered.recordingGapCount)
            assertEquals(state.recordingMaxGapMillis, recovered.recordingMaxGapMillis)
            assertEquals(state.externalRawDistanceUnits, recovered.externalRawDistanceUnits)
            assertEquals(state.externalDistanceMeters, recovered.externalDistanceMeters)
            assertEquals(state.externalIntegratedDistanceMeters, recovered.externalIntegratedDistanceMeters)
            assertEquals(state.stepCount, recovered.stepCount)
            assertEquals("pause", recovered.lastUiAction)
            assertEquals(state.points, recovered.points)
        }

    @Test
    fun loadAndClearRemoveLegacyGpxArtifactsWithoutRemovingJsonUntilClear() =
        runBlocking {
            store.save(state = recoveryState(), lastUiAction = "save")
            val legacyGpx = File(draftDir, "current.gpx").apply { writeText("legacy") }
            val legacyGpxTemp = File(draftDir, "current.gpx.tmp").apply { writeText("legacy temp") }

            assertNotNull(store.load())
            assertFalse(legacyGpx.exists())
            assertFalse(legacyGpxTemp.exists())
            assertTrue(File(draftDir, "current.json").exists())

            File(draftDir, "current.gpx").writeText("legacy")
            File(draftDir, "current.gpx.tmp").writeText("legacy temp")
            File(draftDir, "current.json.tmp").writeText("json temp")

            store.clear()

            assertFalse(File(draftDir, "current.json").exists())
            assertFalse(File(draftDir, "current.json.tmp").exists())
            assertFalse(File(draftDir, "current.gpx").exists())
            assertFalse(File(draftDir, "current.gpx.tmp").exists())
        }

    @Test
    fun corruptJsonPreservesLegacyGpxArtifactsForSalvage() =
        runBlocking {
            File(draftDir, "current.json").writeText("{corrupt")
            val legacyGpx = File(draftDir, "current.gpx").apply { writeText("legacy") }
            val legacyGpxTemp = File(draftDir, "current.gpx.tmp").apply { writeText("legacy temp") }

            assertEquals(null, store.load())
            assertTrue(legacyGpx.exists())
            assertTrue(legacyGpxTemp.exists())
        }

    @Test
    fun successfulSaveRemovesStaleLegacyGpxArtifacts() =
        runBlocking {
            val legacyGpx = File(draftDir, "current.gpx").apply { writeText("legacy") }
            val legacyGpxTemp = File(draftDir, "current.gpx.tmp").apply { writeText("legacy temp") }

            store.save(state = recoveryState(), lastUiAction = "save")

            assertFalse(legacyGpx.exists())
            assertFalse(legacyGpxTemp.exists())
            assertTrue(File(draftDir, "current.json").exists())
        }

    private fun recoveryState() =
        TraceRecordingUiState(
            active = true,
            paused = true,
            autoPaused = true,
            activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            trackSmoothingMode = SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
            points =
                listOf(
                    RecordedTracePoint(
                        latLong = LatLong(45.123456, 6.654321),
                        elevationMeters = 1_234.5,
                        timeMillis = 1_700_000_000_000L,
                        accuracyMeters = 4.5f,
                        speedMps = 2.25f,
                        elevationSource = "HYBRID",
                        heartRateBpm = 142,
                        stepCount = 87,
                        cadenceSpm = 164,
                        powerWatts = 210,
                        barometricPressureHpa = 913.42,
                        startsNewSegment = true,
                        segmentStartReason = RecordingSegmentStartReason.AUTO_PAUSE,
                        trajectoryFinalized = true,
                    ),
                ),
            distanceMeters = 4_321.0,
            startedAtMillis = 1_699_999_000_000L,
            pausedAtMillis = 1_700_000_100_000L,
            accumulatedPausedMillis = 42_000L,
            gpsActiveDurationMillis = 987_000L,
            recordingGapCount = 3,
            recordingMaxGapMillis = 12_000L,
            externalRawDistanceUnits = 123_456L,
            externalDistanceMeters = 5_678.9,
            externalIntegratedDistanceMeters = 5_600.1,
            stepCount = 88,
        )

    private class TestContext(
        base: Context,
        private val draftDir: File,
    ) : ContextWrapper(base) {
        override fun getDir(
            name: String,
            mode: Int,
        ): File = draftDir
    }
}
