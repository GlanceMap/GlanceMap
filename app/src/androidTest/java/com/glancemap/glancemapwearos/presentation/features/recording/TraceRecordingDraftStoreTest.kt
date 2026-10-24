package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
            assertEquals(state.recordingMetadata, recovered.recordingMetadata)
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
            val xml =
                encodeRecordedTraceAsGpx(
                    "Recovered",
                    recovered.points,
                    recordingMetadata = recovered.recordingMetadata,
                    exporterVersion = "new-exporter",
                ).toString(Charsets.UTF_8)
            assertTrue(xml.contains("creator=\"GlanceMap new-exporter\""))
            assertTrue(xml.contains("<gmap:recordingAppVersion>old-recorder</gmap:recordingAppVersion>"))
            assertTrue(xml.contains("<gmap:locationAgeMillis>120</gmap:locationAgeMillis>"))
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
            recordingMetadata =
                RecordingCaptureMetadata(
                    appVersion = "old-recorder",
                    appBuild = 42,
                    deviceManufacturer = "Example",
                    deviceModel = "Test Watch",
                    androidVersion = "14",
                    androidApiLevel = 34,
                    androidBuild = "test-build",
                    locationSourceModeAtStart = "WATCH_GPS",
                    sampleIntervalSecondsAtStart = 3,
                    effectiveLocationIntervalMillisAtStart = 1_000L,
                    elevationSourceAtStart = "GPS",
                    powerSaveModeAtStart = false,
                ),
            points =
                listOf(
                    RecordedTracePoint(
                        latLong = LatLong(45.123456, 6.654321),
                        elevationMeters = 1_234.5,
                        timeMillis = 1_700_000_000_000L,
                        accuracyMeters = 4.5f,
                        speedMps = 2.25f,
                        effectiveAccuracyMeters = 18f,
                        accuracyInterpretation = RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS,
                        elevationSource = "HYBRID",
                        heartRateBpm = 142,
                        stepCount = 87,
                        cadenceSpm = 164,
                        powerWatts = 210,
                        barometricPressureHpa = 913.42,
                        startsNewSegment = true,
                        segmentStartReason = RecordingSegmentStartReason.AUTO_PAUSE,
                        trajectoryFinalized = true,
                        locationQuality = RecordedLocationQuality("gps", "WATCH_GPS", 4.5f, 0.25f, 120L),
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

    @Test
    fun legacyDraftWithoutAccuracyProvenanceStillRecovers() =
        runBlocking {
            val legacyPoint =
                JSONObject()
                    .put("lat", 45.123456)
                    .put("lon", 6.654321)
                    .put("timeMillis", 1_700_000_000_000L)
                    .put("accuracyMeters", 125f)
                    .put("speedMps", 2.25f)
            File(draftDir, "current.json").writeText(
                JSONObject()
                    .put("active", true)
                    .put("points", JSONArray().put(legacyPoint))
                    .toString(),
            )

            val recovered = requireNotNull(store.load())
            val point = recovered.points.single()

            assertEquals(null, recovered.recordingMetadata)
            assertEquals(null, point.locationQuality)
            assertEquals(125f, point.accuracyMeters)
            assertEquals(125f, point.effectiveAccuracyMeters)
            assertEquals(RECORDING_ACCURACY_INTERPRETATION_RAW, point.accuracyInterpretation)
        }

    @Test
    fun captureUsesTheRecordingDeviceAndAvailableMeasurementQuality() {
        val metadata = captureRecordingMetadata("WATCH_GPS", 3, 1_000L, "GPS", false)
        assertEquals(BuildConfig.VERSION_NAME, metadata.appVersion)
        assertEquals(BuildConfig.VERSION_CODE, metadata.appBuild)
        assertEquals(Build.MANUFACTURER, metadata.deviceManufacturer)
        assertEquals(Build.MODEL, metadata.deviceModel)
        assertEquals(Build.VERSION.RELEASE, metadata.androidVersion)
        assertEquals(Build.VERSION.SDK_INT, metadata.androidApiLevel)
        assertEquals(Build.DISPLAY, metadata.androidBuild)

        val location =
            Location("gps").apply {
                elapsedRealtimeNanos = 1_000_000_000L
                verticalAccuracyMeters = 4.5f
                speedAccuracyMetersPerSecond = 0.25f
            }
        assertEquals(
            RecordedLocationQuality("gps", "WATCH_GPS", 4.5f, 0.25f, 120L),
            location.recordingLocationQuality("WATCH_GPS", 1_120L),
        )
        val unavailable = Location("fused").recordingLocationQuality(null, 1_120L)
        assertEquals(RecordedLocationQuality(provider = "fused"), unavailable)
        assertEquals(null, location.recordingLocationQuality(null, 900L).ageMillis)
    }

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
