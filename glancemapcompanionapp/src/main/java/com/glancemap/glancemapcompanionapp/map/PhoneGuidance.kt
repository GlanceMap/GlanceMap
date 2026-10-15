package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import com.glancemap.trailcore.guidance.TrailGuidanceSession
import com.glancemap.trailcore.guidance.TrailGuidanceState
import com.glancemap.trailcore.guidance.buildTrailGuidanceSession
import com.glancemap.trailcore.guidance.reverseTrailPoints
import com.glancemap.trailcore.profile.TrailPacingConfig
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.glancemap.trailcore.recording.RecordingElevationProfileConfig
import com.glancemap.trailcore.recording.RecordingElevationProfilePoint
import com.glancemap.trailcore.recording.buildRecordingElevationProfileCumulative
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

internal enum class PhoneGuidanceMessage { IDLE, LOADING, ACTIVE, PAUSED, FINISHED, FAILED }

internal enum class PhoneGuidanceDecision { START_HERE, REVERSE_ROUTE }

/** Guards asynchronous GPX preparation against repeated taps and late completions. */
internal class PhoneGuidanceStartGate {
    private var generation = 0L
    private var pendingGeneration: Long? = null

    @Synchronized
    fun begin(alreadyActive: Boolean): Long? {
        if (alreadyActive || pendingGeneration != null) return null
        generation += 1L
        pendingGeneration = generation
        return generation
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = pendingGeneration == token && generation == token

    @Synchronized
    fun complete(token: Long): Boolean {
        if (!isCurrent(token)) return false
        pendingGeneration = null
        return true
    }

    @Synchronized
    fun completeCurrent(): Boolean {
        val token = pendingGeneration ?: return false
        return complete(token)
    }

    @Synchronized
    fun invalidate() {
        generation += 1L
        pendingGeneration = null
    }
}

internal data class PhoneGuidanceUiState(
    val session: TrailGuidanceSession? = null,
    val guidance: TrailGuidanceState? = null,
    val activityProfile: PhoneActivityProfile = PhoneActivityProfile.HIKE,
    val paused: Boolean = false,
    val pendingDecision: PhoneGuidanceDecision? = null,
    val message: PhoneGuidanceMessage = PhoneGuidanceMessage.IDLE,
)

/** Published by the service so navigation and activity recreation cannot own the guidance lifetime. */
internal object PhoneGuidanceSessionStore {
    private val mutableState = MutableStateFlow(PhoneGuidanceUiState())
    val state: StateFlow<PhoneGuidanceUiState> = mutableState.asStateFlow()

    fun publish(value: PhoneGuidanceUiState) {
        mutableState.value = value
    }
}

internal data class PhoneGuidanceRouteDraft(
    val trackId: String,
    val trackTitle: String,
    val points: List<TrailPoint>,
    val routeOffsetMeters: Double,
    val activityProfile: String,
    val etaConfig: TrailGuidanceEtaConfig,
    val sessionActive: Boolean = false,
)

internal data class PhoneGuidanceProgressDraft(
    val reversed: Boolean = false,
    val startReached: Boolean = false,
    val paused: Boolean = false,
    val distanceFromStartMeters: Double? = null,
    val stableMidRouteSampleCount: Int = 0,
    val offRoute: Boolean = false,
    val outsideSampleCount: Int = 0,
    val recoverySampleCount: Int = 0,
    val lastAlertInstructionKey: String? = null,
    val dismissedDecisionKey: String? = null,
)

internal fun buildPhoneGuidanceSession(
    route: PhoneGuidanceRouteDraft,
    progress: PhoneGuidanceProgressDraft = PhoneGuidanceProgressDraft(),
): TrailGuidanceSession {
    val points = if (progress.reversed) reverseTrailPoints(route.points) else route.points
    val profile = buildTrailRouteProfile(points, TrailPacingConfig())
    val elevation =
        if (points.count { it.elevationMeters?.isFinite() == true } >= 2) {
            val cumulativeDistances = profile.cumulativeDistanceMeters.toDoubleArray()
            val segmentLengths =
                DoubleArray(points.lastIndex) { index ->
                    (cumulativeDistances[index + 1] - cumulativeDistances[index]).coerceAtLeast(0.0)
                }
            buildRecordingElevationProfileCumulative(
                points = points.map { RecordingElevationProfilePoint(it.elevationMeters, it.startsNewSegment, it.hasTimestamp) },
                segmentLengths = segmentLengths,
                cumulativeDistances = cumulativeDistances,
                elevationFilterConfig = RecordingElevationProfileConfig(),
            )
        } else {
            null
        }
    return buildTrailGuidanceSession(
        trackId = route.trackId,
        trackTitle = route.trackTitle,
        trackPoints = points,
        startReached = progress.startReached,
        reversed = progress.reversed,
        cumulativeDistancesMeters = profile.cumulativeDistanceMeters,
        routeOffsetMeters = route.routeOffsetMeters,
    ).copy(
        cumulativeAscentMeters = elevation?.first?.toList() ?: emptyList(),
        cumulativeDescentMeters = elevation?.second?.toList() ?: emptyList(),
    )
}

internal data class PhoneGuidanceFeedbackSettings(
    val voiceEnabled: Boolean = false,
    val hapticsEnabled: Boolean = true,
)

internal class PhoneGuidanceFeedbackPreferences(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load() =
        PhoneGuidanceFeedbackSettings(
            voiceEnabled = preferences.getBoolean(KEY_VOICE_ENABLED, false),
            hapticsEnabled = preferences.getBoolean(KEY_HAPTICS_ENABLED, true),
        )

    fun save(settings: PhoneGuidanceFeedbackSettings) {
        preferences
            .edit()
            .putBoolean(KEY_VOICE_ENABLED, settings.voiceEnabled)
            .putBoolean(KEY_HAPTICS_ENABLED, settings.hapticsEnabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_guidance_feedback"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    }
}

internal enum class PhoneGuidanceFeedbackEvent { OFF_ROUTE, BACK_ON_ROUTE, ARRIVAL }

internal fun phoneGuidanceFeedbackEvent(
    previous: TrailGuidanceState?,
    current: TrailGuidanceState,
): PhoneGuidanceFeedbackEvent? {
    if (!current.active) return null
    if (current.mode == TrailGuidanceMode.FINISHED) {
        return if (previous?.mode == TrailGuidanceMode.FINISHED) null else PhoneGuidanceFeedbackEvent.ARRIVAL
    }
    if (current.mode != TrailGuidanceMode.FOLLOW_ROUTE) return null
    return when {
        current.offRoute && (previous?.mode != TrailGuidanceMode.FOLLOW_ROUTE || previous.offRoute != true) ->
            PhoneGuidanceFeedbackEvent.OFF_ROUTE
        !current.offRoute && previous?.mode == TrailGuidanceMode.FOLLOW_ROUTE && previous.offRoute ->
            PhoneGuidanceFeedbackEvent.BACK_ON_ROUTE
        else -> null
    }
}

internal fun persistGuidanceProgressSafely(
    write: () -> Unit,
    onFailure: () -> Unit = {},
): Boolean =
    try {
        write()
        true
    } catch (_: Exception) {
        runCatching(onFailure)
        false
    }

internal fun phoneGuidanceEtaConfig(
    gpx: PhoneMapGpxSettings,
    general: PhoneGeneralSettings,
    activityProfile: PhoneActivityProfile = general.activityProfile,
): TrailGuidanceEtaConfig {
    val flatSpeed =
        if (activityProfile == PhoneActivityProfile.BIKE &&
            gpx.flatSpeedMetersPerSecond == DEFAULT_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND
        ) {
            DEFAULT_PHONE_BIKE_GPX_FLAT_SPEED_METERS_PER_SECOND
        } else {
            gpx.flatSpeedMetersPerSecond
        }
    return TrailGuidanceEtaConfig(
        flatSpeedMps = flatSpeed.toDouble(),
        advancedVerticalRateEnabled = gpx.advancedEtaEnabled,
        staminaAdjustmentEnabled = gpx.staminaAdjustmentEnabled,
        activityProfile = activityProfile.storageValue,
        userWeightKg = general.userWeightKg.toDouble(),
        backpackWeightKg = general.backpackWeightKg.toDouble(),
        bikeWeightKg = general.bikeWeightKg.toDouble(),
        uphillVerticalMetersPerHour = gpx.uphillVerticalMetersPerHour.toDouble(),
        downhillVerticalMetersPerHour = gpx.downhillVerticalMetersPerHour.toDouble(),
    )
}

internal class PhoneGuidanceDraftStore(
    context: Context,
) {
    private val directory = File(context.applicationContext.filesDir, "guidance/phone-tbt")
    private val routeFile = File(directory, "route.json")
    private val progressFile = File(directory, "progress.json")
    private val gson = Gson()
    private val routeType = object : TypeToken<PhoneGuidanceRouteDraft>() {}.type
    private val progressType = object : TypeToken<PhoneGuidanceProgressDraft>() {}.type

    fun saveRoute(route: PhoneGuidanceRouteDraft) = write(routeFile, route)

    fun loadRoute(): PhoneGuidanceRouteDraft? = read(routeFile, routeType)

    fun saveProgress(progress: PhoneGuidanceProgressDraft) = write(progressFile, progress)

    fun loadProgress(): PhoneGuidanceProgressDraft = read<PhoneGuidanceProgressDraft>(progressFile, progressType) ?: PhoneGuidanceProgressDraft()

    fun clear() {
        routeFile.delete()
        progressFile.delete()
    }

    private fun <T> read(
        file: File,
        type: java.lang.reflect.Type,
    ): T? = runCatching { file.takeIf(File::isFile)?.reader()?.use { gson.fromJson<T>(it, type) } }.getOrNull()

    private fun <T> write(
        file: File,
        value: T,
    ) {
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                val writer = output.writer(Charsets.UTF_8)
                gson.toJson(value, writer)
                writer.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not replace phone guidance state" }
        } finally {
            temporary.delete()
        }
    }
}

internal fun PhoneGuidanceUiState.isNavigating(): Boolean = session != null && message != PhoneGuidanceMessage.FAILED

internal fun TrailGuidanceState.isFollowingRoute(): Boolean = mode == TrailGuidanceMode.FOLLOW_ROUTE || mode == TrailGuidanceMode.FINISHED
