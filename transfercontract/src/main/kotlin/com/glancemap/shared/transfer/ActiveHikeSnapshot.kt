package com.glancemap.shared.transfer

import java.util.Base64

/**
 * A compact, latest-state message from the watch to the companion during navigation.
 *
 * It intentionally contains only values already calculated by watch guidance. The phone uses it
 * for a companion dashboard; it does not issue navigation decisions from this data.
 */
data class ActiveHikeSnapshot(
    val phase: ActiveHikePhase,
    val routeId: String?,
    val routeTitle: String?,
    val distanceFromStartMeters: Double?,
    val distanceRemainingMeters: Double?,
    val progressFraction: Double?,
    val estimatedRemainingSeconds: Long?,
    val remainingAscentMeters: Double?,
    val remainingDescentMeters: Double?,
    val activeDurationSeconds: Long? = null,
    val currentSpeedMetersPerSecond: Double? = null,
    val currentAltitudeMeters: Double? = null,
    val offRoute: Boolean,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(routeId == null || routeId.isNotBlank())
        require(routeTitle == null || routeTitle.isNotBlank())
        requireOptionalNonNegativeFinite(distanceFromStartMeters, "Distance from start")
        requireOptionalNonNegativeFinite(distanceRemainingMeters, "Distance remaining")
        require(progressFraction == null || (progressFraction.isFinite() && progressFraction in 0.0..1.0)) {
            "Progress must be finite and between zero and one."
        }
        require(estimatedRemainingSeconds == null || estimatedRemainingSeconds >= 0L)
        requireOptionalNonNegativeFinite(remainingAscentMeters, "Remaining ascent")
        requireOptionalNonNegativeFinite(remainingDescentMeters, "Remaining descent")
        require(activeDurationSeconds == null || activeDurationSeconds >= 0L)
        requireOptionalNonNegativeFinite(currentSpeedMetersPerSecond, "Current speed")
        require(currentAltitudeMeters == null || currentAltitudeMeters.isFinite())
        require(recordedAtEpochMillis >= 0L)
    }
}

enum class ActiveHikePhase {
    IDLE,
    WAITING_FOR_LOCATION,
    TO_START,
    FOLLOWING_ROUTE,
    PAUSED,
    FINISHED,
    RECORDING,
    RECORDING_PAUSED,
}

/** Shared line-based codec, kept dependency-free so both Android apps share one wire format. */
object ActiveHikeSnapshotCodec {
    fun encode(snapshot: ActiveHikeSnapshot): ByteArray =
        buildString {
            appendLine("version=$CURRENT_VERSION")
            appendLine("phase=${snapshot.phase.name}")
            appendLine("route_id=${encodeText(snapshot.routeId)}")
            appendLine("route_title=${encodeText(snapshot.routeTitle)}")
            appendLine("distance_from_start=${encodeDouble(snapshot.distanceFromStartMeters)}")
            appendLine("distance_remaining=${encodeDouble(snapshot.distanceRemainingMeters)}")
            appendLine("progress=${encodeDouble(snapshot.progressFraction)}")
            appendLine("estimated_remaining=${snapshot.estimatedRemainingSeconds.orEmpty()}")
            appendLine("remaining_ascent=${encodeDouble(snapshot.remainingAscentMeters)}")
            appendLine("remaining_descent=${encodeDouble(snapshot.remainingDescentMeters)}")
            appendLine("active_duration=${snapshot.activeDurationSeconds.orEmpty()}")
            appendLine("current_speed=${encodeDouble(snapshot.currentSpeedMetersPerSecond)}")
            appendLine("current_altitude=${encodeDouble(snapshot.currentAltitudeMeters)}")
            appendLine("off_route=${if (snapshot.offRoute) 1 else 0}")
            append("recorded_at=${snapshot.recordedAtEpochMillis}")
        }.toByteArray(Charsets.UTF_8)

    fun decode(payload: ByteArray): ActiveHikeSnapshot? =
        runCatching {
            val values =
                String(payload, Charsets.UTF_8)
                    .lineSequence()
                    .mapNotNull { line ->
                        line
                            .takeIf { '=' in it }
                            ?.let { entry -> entry.substringBefore('=') to entry.substringAfter('=') }
                    }.toMap()
            require(values["version"]?.toIntOrNull() in SUPPORTED_VERSIONS)
            ActiveHikeSnapshot(
                phase = values.requiredPhase(),
                routeId = values.decodeText("route_id"),
                routeTitle = values.decodeText("route_title"),
                distanceFromStartMeters = values.double("distance_from_start"),
                distanceRemainingMeters = values.double("distance_remaining"),
                progressFraction = values.double("progress"),
                estimatedRemainingSeconds = values.long("estimated_remaining"),
                remainingAscentMeters = values.double("remaining_ascent"),
                remainingDescentMeters = values.double("remaining_descent"),
                activeDurationSeconds = values.long("active_duration"),
                currentSpeedMetersPerSecond = values.double("current_speed"),
                currentAltitudeMeters = values.double("current_altitude"),
                offRoute = values.requiredBoolean("off_route"),
                recordedAtEpochMillis = values["recorded_at"]?.toLongOrNull() ?: error("Missing timestamp."),
            )
        }.getOrNull()

    private fun Map<String, String>.requiredPhase(): ActiveHikePhase =
        get("phase")
            ?.let { value -> runCatching { ActiveHikePhase.valueOf(value) }.getOrNull() }
            ?: error("Unknown active-hike phase.")

    private fun Map<String, String>.decodeText(key: String): String? =
        get(key)
            ?.takeIf(String::isNotBlank)
            ?.let { encoded -> String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }
            ?.takeIf(String::isNotBlank)

    private fun Map<String, String>.double(key: String): Double? =
        get(key)
            ?.takeIf(String::isNotBlank)
            ?.let { value -> value.toDoubleOrNull() ?: error("Invalid $key.") }

    private fun Map<String, String>.long(key: String): Long? =
        get(key)
            ?.takeIf(String::isNotBlank)
            ?.let { value -> value.toLongOrNull() ?: error("Invalid $key.") }

    private fun Map<String, String>.requiredBoolean(key: String): Boolean =
        when (get(key)) {
            "0" -> false
            "1" -> true
            else -> error("Invalid $key.")
        }

    private fun encodeText(value: String?): String =
        value
            ?.takeIf(String::isNotBlank)
            ?.toByteArray(Charsets.UTF_8)
            ?.let { bytes -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
            .orEmpty()

    private fun encodeDouble(value: Double?): String = value?.toString().orEmpty()

    private fun Long?.orEmpty(): String = this?.toString().orEmpty()

    private const val CURRENT_VERSION = 2
    private val SUPPORTED_VERSIONS = setOf(1, CURRENT_VERSION)
}

private fun requireOptionalNonNegativeFinite(
    value: Double?,
    name: String,
) {
    require(value == null || (value.isFinite() && value >= 0.0)) {
        "$name must be a non-negative, finite value when available."
    }
}
