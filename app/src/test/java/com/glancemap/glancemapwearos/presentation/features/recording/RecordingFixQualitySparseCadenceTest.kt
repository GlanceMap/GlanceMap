package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingFixQualitySparseCadenceTest {
    @Test
    fun plausibleSparseCadenceMovementIsAcceptedForHikeAndBike() {
        sparseMovementScenarios().forEach { scenario ->
            val gate = RecordingFixQualityGate()
            assertTrue(
                gate
                    .evaluate(
                        sample(
                            northMeters = 0.0,
                            elapsedMillis = 1_000L,
                            speedMps = scenario.speedMps,
                        ),
                        scenario.activityProfile,
                    ).accepted,
            )

            val result =
                gate.evaluate(
                    sample(
                        northMeters = scenario.speedMps.toDouble() * scenario.sampleIntervalSeconds,
                        elapsedMillis = 1_000L + scenario.sampleIntervalSeconds * 1_000L,
                        speedMps = scenario.speedMps,
                    ),
                    scenario.activityProfile,
                )

            assertTrue(
                "profile=${scenario.activityProfile} cadence=${scenario.sampleIntervalSeconds}s result=$result",
                result.accepted,
            )
        }
    }

    private fun sparseMovementScenarios() =
        listOf(
            SparseMovementScenario(HIKE, speedMps = 2f, sampleIntervalSeconds = 60),
            SparseMovementScenario(HIKE, speedMps = 2f, sampleIntervalSeconds = 120),
            SparseMovementScenario(BIKE, speedMps = 8f, sampleIntervalSeconds = 60),
            SparseMovementScenario(BIKE, speedMps = 8f, sampleIntervalSeconds = 120),
        )

    private fun sample(
        northMeters: Double,
        elapsedMillis: Long,
        speedMps: Float,
    ) = RecordingFixSample(
        latLong = LatLong(45.0 + northMeters / 111_320.0, 6.0),
        timeMillis = elapsedMillis,
        elapsedRealtimeMillis = elapsedMillis,
        accuracyMeters = 5f,
        speedMps = speedMps,
        speedAccuracyMps = 0.2f,
    )

    private data class SparseMovementScenario(
        val activityProfile: String,
        val speedMps: Float,
        val sampleIntervalSeconds: Int,
    )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
    }
}
