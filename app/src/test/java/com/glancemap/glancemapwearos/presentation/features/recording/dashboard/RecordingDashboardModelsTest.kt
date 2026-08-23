@file:Suppress("LargeClass", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingDashboardModelsTest {
    @Test
    fun normalizedRecordingDashboardSlotsPreservesLegacyPageAndAddsSecondPageDefaults() {
        val slots =
            normalizedRecordingDashboardSlots(
                listOf(
                    SettingsRepository.RECORDING_METRIC_DISTANCE,
                    SettingsRepository.RECORDING_METRIC_DURATION,
                    SettingsRepository.RECORDING_METRIC_ELEVATION_GAIN,
                    SettingsRepository.RECORDING_METRIC_ELEVATION_LOSS,
                ),
            )

        assertEquals(
            SettingsRepository.DEFAULT_RECORDING_DASHBOARD_ALL_METRICS,
            slots,
        )
    }

    @Test
    fun normalizedRecordingDashboardSlotsPreservesDynamicPages() {
        val slots =
            normalizedRecordingDashboardSlots(
                SettingsRepository.DEFAULT_RECORDING_DASHBOARD_ALL_METRICS +
                    listOf(
                        SettingsRepository.RECORDING_METRIC_CURRENT_SPEED,
                        SettingsRepository.RECORDING_METRIC_AVERAGE_SPEED,
                        SettingsRepository.RECORDING_METRIC_AVERAGE_PACE,
                        SettingsRepository.RECORDING_METRIC_HEART_RATE,
                    ),
            )

        assertEquals(12, slots.size)
        assertEquals(SettingsRepository.RECORDING_METRIC_HEART_RATE, slots.last())
    }

    @Test
    fun normalizedRecordingDashboardSlotsKeepsExistingEightSlotsAsTwoPages() {
        val slots =
            normalizedRecordingDashboardSlots(
                SettingsRepository.DEFAULT_RECORDING_DASHBOARD_ALL_METRICS,
            )

        assertEquals(8, slots.size)
        assertEquals(SettingsRepository.DEFAULT_RECORDING_DASHBOARD_ALL_METRICS, slots)
    }

    @Test
    fun normalizedRecordingDashboardSlotsCapsPagesAtFive() {
        val sixPages =
            List(24) { index ->
                if (index % 2 == 0) {
                    SettingsRepository.RECORDING_METRIC_DISTANCE
                } else {
                    SettingsRepository.RECORDING_METRIC_DURATION
                }
            }

        val slots = normalizedRecordingDashboardSlots(sixPages)

        assertEquals(20, slots.size)
    }

    @Test
    fun buildRecordingDashboardSnapshotExcludesPausedTimeFromDuration() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        active = true,
                        paused = true,
                        startedAtMillis = 1_000L,
                        pausedAtMillis = 7_000L,
                        accumulatedPausedMillis = 2_000L,
                    ),
                nowMillis = 10_000L,
            )

        assertEquals(4.0, snapshot.durationSeconds, 0.0)
        assertEquals(9.0, snapshot.totalDurationSeconds, 0.0)
    }

    @Test
    fun buildRecordingDashboardSnapshotFallsBackToGpsAfterPodDistanceBecomesStale() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        distanceMeters = 1_400.0,
                        externalDistanceMeters = 1_000.0,
                        externalDistanceUpdatedAtMillis = 1_000L,
                        externalDistanceFallbackBaseMeters = 1_000.0,
                        externalDistanceFallbackGpsMeters = 1_200.0,
                        distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                    ),
                nowMillis = 16_001L,
            )

        assertEquals(1_200.0, snapshot.distanceMeters, 0.0)
    }

    @Test
    fun buildRecordingDashboardSnapshotUsesFreshPodDistance() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        distanceMeters = 1_400.0,
                        externalDistanceMeters = 1_000.0,
                        externalDistanceUpdatedAtMillis = 15_000L,
                        externalDistanceFallbackBaseMeters = 1_000.0,
                        externalDistanceFallbackGpsMeters = 1_200.0,
                        distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                    ),
                nowMillis = 16_001L,
            )

        assertEquals(1_000.0, snapshot.distanceMeters, 0.0)
    }

    @Test
    fun formattedRecordingMetricUsesClockDurationForRecordingDuration() {
        val metric =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_DURATION,
                snapshot =
                    RecordingDashboardSnapshot(
                        durationSeconds = 3_723.0,
                        distanceMeters = 0.0,
                        elevationGainMeters = 0.0,
                        elevationLossMeters = 0.0,
                        currentElevationMeters = null,
                        currentSpeedMps = null,
                        averageSpeedMps = null,
                        gpsAccuracyMeters = null,
                        pointCount = 0,
                        gpsActiveDurationSeconds = 0.0,
                        recordingGapCount = 0,
                        recordingMaxGapSeconds = 0.0,
                    ),
                isMetric = true,
            )

        assertEquals("01:02:03", metric.value)
    }

    @Test
    fun formattedRecordingMetricUsesUnitSettingForTwoDecimalDistance() {
        val snapshot =
            RecordingDashboardSnapshot(
                durationSeconds = 0.0,
                distanceMeters = 1_234.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                currentElevationMeters = null,
                currentSpeedMps = null,
                averageSpeedMps = null,
                gpsAccuracyMeters = null,
                pointCount = 0,
                gpsActiveDurationSeconds = 0.0,
                recordingGapCount = 0,
                recordingMaxGapSeconds = 0.0,
            )

        val metricDistance =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_DISTANCE,
                snapshot = snapshot,
                isMetric = true,
            )
        val imperialDistance =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_DISTANCE,
                snapshot = snapshot,
                isMetric = false,
            )

        assertEquals("1.23", metricDistance.value)
        assertEquals("km", metricDistance.unit)
        assertEquals("0.77", imperialDistance.value)
        assertEquals("mi", imperialDistance.unit)
    }

    @Test
    fun sensorMetricsAreSelectableAndShowUnavailableValuesWhenNoSensorDataExists() {
        val snapshot =
            RecordingDashboardSnapshot(
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                currentElevationMeters = null,
                currentSpeedMps = null,
                averageSpeedMps = null,
                gpsAccuracyMeters = null,
                pointCount = 0,
                gpsActiveDurationSeconds = 0.0,
                recordingGapCount = 0,
                recordingMaxGapSeconds = 0.0,
            )

        val heartRate =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_HEART_RATE,
                snapshot = snapshot,
                isMetric = true,
            )
        val pressure =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE,
                snapshot = snapshot,
                isMetric = true,
            )

        assertEquals("Heart rate", heartRate.label)
        assertEquals("--", heartRate.value)
        assertEquals("bpm", heartRate.unit)
        assertEquals("Pressure (hPa)", pressure.label)
        assertEquals("--", pressure.value)
        assertEquals("hPa", pressure.unit)
        assertEquals(
            true,
            recordingMetricDefinitions.any { it.id == SettingsRepository.RECORDING_METRIC_CADENCE },
        )
    }

    @Test
    fun formattedRecordingMetricFormatsCurrentPaceFromSpeed() {
        val snapshot =
            RecordingDashboardSnapshot(
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                currentElevationMeters = null,
                currentSpeedMps = 2.5f,
                averageSpeedMps = null,
                gpsAccuracyMeters = null,
                pointCount = 0,
                gpsActiveDurationSeconds = 0.0,
                recordingGapCount = 0,
                recordingMaxGapSeconds = 0.0,
            )

        val pace =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_CURRENT_PACE,
                snapshot = snapshot,
                isMetric = true,
            )

        assertEquals("Pace", pace.label)
        assertEquals("6:40", pace.value)
        assertEquals("min/km", pace.unit)
    }

    @Test
    fun averagePowerUsesRecordedPowerSamples() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        active = true,
                        startedAtMillis = 1_000L,
                        points =
                            listOf(
                                recordingPoint(
                                    longitude = 0.0,
                                    elevationMeters = 0.0,
                                    timeMillis = 1_000L,
                                    powerWatts = 180,
                                ),
                                recordingPoint(
                                    longitude = 0.001,
                                    elevationMeters = 0.0,
                                    timeMillis = 2_000L,
                                    powerWatts = 220,
                                ),
                            ),
                    ),
                nowMillis = 3_000L,
            )

        val metric =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_AVERAGE_POWER,
                snapshot = snapshot,
                isMetric = true,
            )

        assertEquals("Power (Avg)", metric.label)
        assertEquals("200", metric.value)
        assertEquals("W", metric.unit)
    }

    @Test
    fun maxDashboardMetricsUseSnapshotMaximums() {
        val snapshot =
            RecordingDashboardSnapshot(
                durationSeconds = 60.0,
                distanceMeters = 1_000.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                currentElevationMeters = null,
                currentSpeedMps = 2.0f,
                averageSpeedMps = 3.0,
                fastestSpeedMps = 4.0,
                gpsAccuracyMeters = null,
                pointCount = 2,
                gpsActiveDurationSeconds = 60.0,
                recordingGapCount = 0,
                recordingMaxGapSeconds = 0.0,
                maxHeartRateBpm = 165,
                averageCadenceSpm = 155,
                maxCadenceSpm = 172,
                maxPowerWatts = 260,
            )

        val maxSpeed =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_MAX_SPEED,
                snapshot = snapshot,
                isMetric = true,
            )
        val maxPace =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_MAX_PACE,
                snapshot = snapshot,
                isMetric = true,
            )
        val maxHeartRate =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_MAX_HEART_RATE,
                snapshot = snapshot,
                isMetric = true,
            )
        val averageCadence =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_AVERAGE_CADENCE,
                snapshot = snapshot,
                isMetric = true,
            )
        val maxCadence =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_MAX_CADENCE,
                snapshot = snapshot,
                isMetric = true,
            )
        val maxPower =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_MAX_POWER,
                snapshot = snapshot,
                isMetric = true,
            )

        assertEquals("Speed (Max)", maxSpeed.label)
        assertEquals("14.4", maxSpeed.value)
        assertEquals("km/h", maxSpeed.unit)
        assertEquals("Pace (Max)", maxPace.label)
        assertEquals("4:10", maxPace.value)
        assertEquals("min/km", maxPace.unit)
        assertEquals("Heart rate (Max)", maxHeartRate.label)
        assertEquals("165", maxHeartRate.value)
        assertEquals("bpm", maxHeartRate.unit)
        assertEquals("Cadence (Avg)", averageCadence.label)
        assertEquals("155", averageCadence.value)
        assertEquals("Cadence (Max)", maxCadence.label)
        assertEquals("172", maxCadence.value)
        assertEquals("Power (Max)", maxPower.label)
        assertEquals("260", maxPower.value)
        assertEquals("W", maxPower.unit)
    }

    @Test
    fun recordingRecapMetricsUseActivityDetailOrderAndRemoveLiveOnlyMetrics() {
        val snapshot =
            RecordingDashboardSnapshot(
                durationSeconds = 3_723.0,
                distanceMeters = 1_234.0,
                elevationGainMeters = 120.0,
                elevationLossMeters = 95.0,
                currentElevationMeters = 1_450.0,
                currentSpeedMps = 2.5f,
                averageSpeedMps = 2.0,
                fastestSpeedMps = 3.0,
                gpsAccuracyMeters = null,
                pointCount = 42,
                gpsActiveDurationSeconds = 3_700.0,
                recordingGapCount = 0,
                recordingMaxGapSeconds = 0.0,
                calorieEstimate =
                    RecordingCalorieEstimate(
                        grossKcal = 120.0,
                        activeKcal = 90.0,
                        restingKcal = 30.0,
                    ),
                heartRateBpm = 130,
                averageHeartRateBpm = 125,
                maxHeartRateBpm = 158,
                stepCount = 1_500,
                cadenceSpm = 160,
                averageCadenceSpm = 155,
                maxCadenceSpm = 172,
                powerWatts = 190,
                averagePowerWatts = 175,
                maxPowerWatts = 260,
                barometricPressureHpa = 913.0,
            )

        val labels =
            recordingRecapMetricsForSnapshot(snapshot, isMetric = true)
                .map { metric -> metric.label }

        assertEquals(
            listOf(
                "Distance",
                "Steps",
                "Time (Total)",
                "Time (Active)",
                "Elev +",
                "Elev -",
                "Speed (Avg)",
                "Max speed",
                "Pace (Avg)",
                "Max pace",
                "HR (Avg)",
                "Max HR",
                "Power (Avg)",
                "Max Power",
                "Cadence (Avg)",
                "Max cad",
                "Cal (Total)",
                "Cal (Active)",
                "Cal (Rest)",
            ),
            labels,
        )
    }

    @Test
    fun bikeRecordingRecapPlacesElevationAndHeartRateBeforeSpeed() {
        val snapshot =
            RecordingDashboardSnapshot(
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
                durationSeconds = 3_723.0,
                distanceMeters = 1_234.0,
                elevationGainMeters = 120.0,
                elevationLossMeters = 95.0,
                currentElevationMeters = null,
                currentSpeedMps = null,
                averageSpeedMps = 2.0,
                gpsAccuracyMeters = null,
                pointCount = 42,
                gpsActiveDurationSeconds = 3_700.0,
                recordingGapCount = 0,
                recordingMaxGapSeconds = 0.0,
            )

        val labels = recordingRecapMetricsForSnapshot(snapshot, isMetric = true).map { it.label }

        assertEquals(
            listOf(
                "Distance",
                "Cal (Total)",
                "Cal (Active)",
                "Cal (Rest)",
                "Time (Active)",
                "Time (Total)",
                "Elev +",
                "Elev -",
                "HR (Avg)",
                "Max HR",
                "Speed (Avg)",
                "Max speed",
                "Power (Avg)",
                "Max Power",
            ),
            labels,
        )
    }

    @Test
    fun metricPickerOptionsAreAlphabetical() {
        val labels =
            recordingMetricPickerOptionsForProfile(SettingsRepository.ACTIVITY_PROFILE_HIKE)
                .map { it.second }
        assertEquals(labels.sortedBy { it.lowercase() }, labels)
        assertTrue(labels.contains("Cadence (Avg)"))
        assertTrue(labels.contains("Cadence (Max)"))
        assertTrue(labels.contains("Heart rate (Max)"))
        assertTrue(labels.contains("Power (Avg)"))
        assertTrue(labels.contains("Power (Max)"))
        assertTrue(labels.contains("Speed (Max)"))
        assertTrue(labels.contains("Pace (Max)"))
    }

    @Test
    fun bikeMetricPickerExcludesStepsAndPace() {
        val options = recordingMetricPickerOptionsForProfile(SettingsRepository.ACTIVITY_PROFILE_BIKE)
        val metricIds = options.map { it.first }
        val labels = options.map { it.second }

        assertEquals(labels.sortedBy { it.lowercase() }, labels)
        assertTrue(SettingsRepository.RECORDING_METRIC_CADENCE in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_POWER in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_STEPS !in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_CURRENT_PACE !in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_AVERAGE_PACE !in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_MAX_PACE !in metricIds)
    }

    @Test
    fun bikeCadenceUsesRpm() {
        val value =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_CADENCE,
                snapshot =
                    RecordingDashboardSnapshot(
                        activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
                        durationSeconds = 60.0,
                        distanceMeters = 1_000.0,
                        elevationGainMeters = 0.0,
                        elevationLossMeters = 0.0,
                        currentElevationMeters = null,
                        currentSpeedMps = null,
                        averageSpeedMps = null,
                        gpsAccuracyMeters = null,
                        pointCount = 0,
                        gpsActiveDurationSeconds = 0.0,
                        recordingGapCount = 0,
                        recordingMaxGapSeconds = 0.0,
                        cadenceSpm = 92,
                    ),
                isMetric = true,
            )

        assertEquals("92", value.value)
        assertEquals("rpm", value.unit)
    }

    @Test
    fun buildRecordingDashboardSnapshotUsesFreshLivePointOnlyForCurrentMetrics() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        active = true,
                        startedAtMillis = 0L,
                        points =
                            listOf(
                                recordingPoint(
                                    longitude = 0.0,
                                    elevationMeters = 100.0,
                                    timeMillis = 0L,
                                ),
                            ),
                        latestLivePoint =
                            recordingPoint(
                                longitude = 0.001,
                                elevationMeters = 104.0,
                                timeMillis = 9_000L,
                                speedMps = 2.5f,
                            ),
                        distanceMeters = 0.0,
                    ),
                nowMillis = 10_000L,
            )

        assertEquals(0.0, snapshot.distanceMeters, 0.0)
        assertEquals(1, snapshot.pointCount)
        assertEquals(104.0, snapshot.currentElevationMeters!!, 0.0)
        assertEquals(2.5f, snapshot.currentSpeedMps!!)
        assertEquals(1_000L, snapshot.lastLiveFixAgeMillis)
        assertEquals(10_000L, snapshot.lastRecordedPointAgeMillis)
    }

    @Test
    fun buildRecordingDashboardSnapshotIgnoresStaleLivePoint() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        active = true,
                        startedAtMillis = 0L,
                        points =
                            listOf(
                                recordingPoint(
                                    longitude = 0.0,
                                    elevationMeters = 100.0,
                                    timeMillis = 0L,
                                    speedMps = 1.0f,
                                ),
                            ),
                        latestLivePoint =
                            recordingPoint(
                                longitude = 0.001,
                                elevationMeters = 104.0,
                                timeMillis = 1_000L,
                                speedMps = 2.5f,
                            ),
                    ),
                nowMillis = 20_000L,
            )

        assertEquals(100.0, snapshot.currentElevationMeters!!, 0.0)
        assertEquals(1.0f, snapshot.currentSpeedMps!!)
        assertEquals(19_000L, snapshot.lastLiveFixAgeMillis)
    }

    @Test
    fun buildRecordingDashboardSnapshotDoesNotCountElevationJumpAcrossGpsGap() {
        val snapshot =
            buildRecordingDashboardSnapshot(
                state =
                    TraceRecordingUiState(
                        active = true,
                        startedAtMillis = 0L,
                        points =
                            listOf(
                                recordingPoint(0.0, 100.0, 0L),
                                recordingPoint(0.001, 110.0, 10_000L),
                                recordingPoint(0.100, 1_000.0, 60_000L, startsNewSegment = true),
                                recordingPoint(0.101, 1_010.0, 70_000L),
                            ),
                    ),
                nowMillis = 70_000L,
            )

        assertTrue(snapshot.elevationGainMeters < 100.0)
    }

    @Test
    fun buildRecordingDashboardSnapshotDoesNotAccumulateSmallElevationCorrections() {
        val points =
            List(120) { index ->
                recordingPoint(
                    longitude = index * 0.000045,
                    elevationMeters = 200.0 + if (index % 2 == 0) 0.12 else -0.12,
                    timeMillis = index * 3_000L,
                )
            }
        val snapshot =
            buildRecordingDashboardSnapshot(
                state = TraceRecordingUiState(active = true, startedAtMillis = 0L, points = points),
                nowMillis = 357_000L,
            )

        assertTrue(snapshot.elevationGainMeters < 0.5)
        assertTrue(snapshot.elevationLossMeters < 0.5)
    }

    @Test
    fun formattedRecordingMetricEstimatesCaloriesFromWeightDistanceAndDuration() {
        val estimate =
            estimateRecordingCalories(
                points = oneHourFlatWalkPoints(),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
            )

        val calories =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_CALORIES,
                snapshot = recordingSnapshot(calorieEstimate = estimate),
                isMetric = true,
            )
        assertEquals("Cal (Total)", calories.label)
        assertEquals("286", calories.value)
        assertEquals("kcal", calories.unit)
        assertEquals(75.0, estimate.restingKcal, 0.1)
        assertEquals(211.0, estimate.activeKcal, 1.0)
        assertEquals(286.0, estimate.pandolfBaseGrossKcal, 1.0)
        assertEquals(211.0, estimate.pandolfBaseActiveKcal, 1.0)
        assertEquals(75.0, estimate.pandolfBaseRestingKcal, 0.1)
        assertEquals(295.0, estimate.lcdaGrossKcal, 1.0)
        assertEquals(220.0, estimate.lcdaActiveKcal, 1.0)
        assertEquals(75.0, estimate.lcdaRestingKcal, 0.1)
    }

    @Test
    fun formattedRecordingMetricAddsBackpackLoadToCaloriesEstimate() {
        val estimate =
            estimateRecordingCalories(
                points = oneHourFlatWalkPoints(),
                userWeightKg = 75f,
                backpackWeightKg = 10f,
            )

        val calories =
            formattedRecordingMetric(
                metricId = SettingsRepository.RECORDING_METRIC_CALORIES,
                snapshot = recordingSnapshot(calorieEstimate = estimate),
                isMetric = true,
            )

        assertEquals("314", calories.value)
        assertEquals(323.0, estimate.lcdaGrossKcal, 1.0)
    }

    @Test
    fun estimateRecordingCaloriesAppliesDownhillCorrection() {
        val flat =
            estimateRecordingCalories(
                points = oneHourFlatWalkPoints(),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
            )
        val downhill =
            estimateRecordingCalories(
                points =
                    oneHourWalkPoints(
                        startElevationMeters = 756.0,
                        endElevationMeters = 0.0,
                    ),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
            )

        assertEquals(286.0, flat.grossKcal, 1.0)
        assertEquals(227.0, downhill.grossKcal, 1.0)
        assertEquals(0.0, downhill.pandolfBaseGrossKcal, 1.0)
    }

    @Test
    fun estimateRecordingCaloriesSmoothsIsolatedElevationSpike() {
        val flat =
            estimateRecordingCalories(
                points = oneHourFlatWalkPoints(),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
            )
        val spiked =
            estimateRecordingCalories(
                points =
                    oneHourWalkPoints(
                        startElevationMeters = 0.0,
                        endElevationMeters = 0.0,
                    ).mapIndexed { index, point ->
                        if (index == ONE_HOUR_WALK_SEGMENT_COUNT / 2) {
                            point.copy(elevationMeters = 300.0)
                        } else {
                            point
                        }
                    },
                userWeightKg = 75f,
                backpackWeightKg = 0f,
            )

        assertEquals(flat.grossKcal, spiked.grossKcal, 1.0)
        assertEquals(flat.lcdaGrossKcal, spiked.lcdaGrossKcal, 1.0)
    }

    @Test
    fun estimateRecordingCaloriesUsesPowerMeterForBikeProfile() {
        val estimate =
            estimateRecordingCalories(
                points = oneHourFlatWalkPoints().map { it.copy(powerWatts = 230) },
                userWeightKg = 75f,
                backpackWeightKg = 0f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )

        assertEquals("cycling_power_meter_efficiency_v1", estimate.model)
        assertEquals(828.0, estimate.cyclingMechanicalKj, 1.0)
        assertEquals(860.0, estimate.activeKcal, 1.0)
        assertEquals(935.0, estimate.grossKcal, 1.0)
        assertEquals(6, estimate.cyclingPowerSampleSegments)
        assertEquals(0, estimate.cyclingPhysicsSegments)
    }

    @Test
    fun estimateRecordingCaloriesUsesPhysicsFallbackForBikeProfileWithoutPower() {
        val estimate =
            estimateRecordingCalories(
                points = oneHourBikePoints(),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )

        assertEquals("cycling_physics_fallback_v1", estimate.model)
        assertEquals(229.0, estimate.cyclingMechanicalKj, 5.0)
        assertEquals(238.0, estimate.activeKcal, 5.0)
        assertEquals(313.0, estimate.grossKcal, 5.0)
        assertEquals(0, estimate.cyclingPowerSampleSegments)
        assertEquals(6, estimate.cyclingPhysicsSegments)
    }

    @Test
    fun estimateRecordingCaloriesUsesBikeWeightForBikePhysicsFallback() {
        val lightBike =
            estimateRecordingCalories(
                points = oneHourBikePoints(),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
                bikeWeightKg = 8f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )
        val heavyBike =
            estimateRecordingCalories(
                points = oneHourBikePoints(),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
                bikeWeightKg = 20f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )

        assertTrue(heavyBike.activeKcal > lightBike.activeKcal)
    }

    @Test
    fun estimateRecordingCaloriesDoesNotUseGpsAccelerationForBikePhysicsFallback() {
        val estimate =
            estimateRecordingCalories(
                points =
                    listOf(
                        recordingPoint(longitude = 0.0, elevationMeters = 0.0, timeMillis = 0L),
                        recordingPoint(longitude = 0.000018, elevationMeters = 0.0, timeMillis = 5_000L),
                        recordingPoint(longitude = 0.000198, elevationMeters = 0.0, timeMillis = 10_000L),
                        recordingPoint(longitude = 0.000216, elevationMeters = 0.0, timeMillis = 15_000L),
                        recordingPoint(longitude = 0.000396, elevationMeters = 0.0, timeMillis = 20_000L),
                    ),
                userWeightKg = 75f,
                backpackWeightKg = 0f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )

        assertEquals("cycling_physics_fallback_v1", estimate.model)
        assertEquals(0, estimate.cyclingPowerSampleSegments)
        assertEquals(4, estimate.cyclingPhysicsSegments)
        assertTrue(estimate.cyclingMechanicalKj < 1.0)
    }

    private fun oneHourFlatWalkPoints(): List<RecordedTracePoint> =
        oneHourWalkPoints(
            startElevationMeters = 0.0,
            endElevationMeters = 0.0,
        )

    private fun oneHourBikePoints(): List<RecordedTracePoint> =
        oneHourRoutePoints(
            longitudeDelta = ONE_HOUR_BIKE_LONGITUDE_DELTA,
            startElevationMeters = 0.0,
            endElevationMeters = 0.0,
        )

    private fun oneHourWalkPoints(
        startElevationMeters: Double,
        endElevationMeters: Double,
    ): List<RecordedTracePoint> =
        oneHourRoutePoints(
            longitudeDelta = ONE_HOUR_WALK_LONGITUDE_DELTA,
            startElevationMeters = startElevationMeters,
            endElevationMeters = endElevationMeters,
        )

    private fun oneHourRoutePoints(
        longitudeDelta: Double,
        startElevationMeters: Double,
        endElevationMeters: Double,
    ): List<RecordedTracePoint> =
        (0..ONE_HOUR_WALK_SEGMENT_COUNT).map { index ->
            val progress = index / ONE_HOUR_WALK_SEGMENT_COUNT.toDouble()
            recordingPoint(
                longitude = longitudeDelta * progress,
                elevationMeters = startElevationMeters + (endElevationMeters - startElevationMeters) * progress,
                timeMillis = (3_600_000L * progress).toLong(),
            )
        }

    private fun recordingSnapshot(calorieEstimate: RecordingCalorieEstimate): RecordingDashboardSnapshot =
        RecordingDashboardSnapshot(
            durationSeconds = 3_600.0,
            distanceMeters = 5_040.0,
            elevationGainMeters = 0.0,
            elevationLossMeters = 0.0,
            currentElevationMeters = null,
            currentSpeedMps = null,
            averageSpeedMps = 1.4,
            gpsAccuracyMeters = null,
            pointCount = 2,
            gpsActiveDurationSeconds = 3_600.0,
            recordingGapCount = 0,
            recordingMaxGapSeconds = 0.0,
            userWeightKg = 75f,
            calorieEstimate = calorieEstimate,
        )

    private fun recordingPoint(
        longitude: Double,
        elevationMeters: Double,
        timeMillis: Long,
        speedMps: Float? = null,
        powerWatts: Int? = null,
        startsNewSegment: Boolean = false,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(0.0, longitude),
            elevationMeters = elevationMeters,
            timeMillis = timeMillis,
            accuracyMeters = null,
            speedMps = speedMps,
            powerWatts = powerWatts,
            startsNewSegment = startsNewSegment,
        )

    private companion object {
        private const val ONE_HOUR_WALK_SEGMENT_COUNT = 6
        private const val ONE_HOUR_WALK_LONGITUDE_DELTA = 0.045319
        private const val ONE_HOUR_BIKE_LONGITUDE_DELTA = 0.161697
    }
}
