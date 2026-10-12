package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.mapSaver
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Persist selections, not GPX geometry, computed profiles or pending jobs. */
internal data class PhoneMapRouteAnalysisSavedSelection(
    val trackId: String,
    val displayName: String,
    val contentRevision: String,
    val pointA: PhoneMapCoordinate,
    val positionA: PhoneMapRouteAnalysisPosition,
    val pointB: PhoneMapCoordinate? = null,
    val positionB: PhoneMapRouteAnalysisPosition? = null,
) {
    init {
        require(trackId.isNotBlank() && displayName.isNotBlank() && contentRevision.isNotBlank())
        require((pointB == null) == (positionB == null))
    }
}

internal fun PhoneMapRouteAnalysisRequestResult.savedSelection(): PhoneMapRouteAnalysisSavedSelection =
    PhoneMapRouteAnalysisSavedSelection(
        analysis.trackId,
        analysis.displayName,
        trackRevision.contentRevision,
        analysis.pointA,
        analysis.pointAPosition,
        analysis.pointB,
        analysis.pointBPosition,
    )

/** Called on the analysis worker; includes elevation and segment boundaries as well as geometry. */
internal fun PhoneMapGpxTrack.analysisContentRevision(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(26)
    points.forEach { point ->
        buffer.clear()
        buffer.putDouble(point.location.latitude)
        buffer.putDouble(point.location.longitude)
        buffer.putDouble(point.elevationMeters ?: 0.0)
        buffer.put(if (point.elevationMeters == null) 0.toByte() else 1.toByte())
        buffer.put(if (point.startsNewSegment) 1.toByte() else 0.toByte())
        digest.update(buffer.array())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

internal fun restorePhoneMapRouteAnalysis(
    selection: PhoneMapRouteAnalysisSavedSelection,
    items: List<PhoneMapGpxItem>,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
): PhoneMapRouteAnalysisRequestResult? {
    val item =
        items.firstOrNull { it.enabled && it.id == selection.trackId && it.displayName == selection.displayName }
            ?: return null
    val revision = item.track.analysisContentRevision()
    val validPositions =
        selection.positionA.isValidIn(item.track) &&
            (selection.positionB?.isValidIn(item.track) != false)
    return if (revision != selection.contentRevision || !validPositions) {
        null
    } else {
        val pointA = PhoneMapRouteAnalysisHit(item, selection.positionA, selection.pointA, 0.0)
        val pointB =
            selection.pointB?.let { coordinate ->
                selection.positionB?.let { position -> PhoneMapRouteAnalysisHit(item, position, coordinate, 0.0) }
            }
        PhoneMapRouteAnalysisRequestResult(
            buildPhoneMapRouteAnalysis(pointA, pointB, settings, generalSettings),
            PhoneMapRouteAnalysisTrackRevision(item.id, item.displayName, item.track, revision),
        )
    }
}

private fun PhoneMapRouteAnalysisPosition.isValidIn(track: PhoneMapGpxTrack): Boolean {
    val validFraction = fraction.isFinite() && fraction in 0.0..1.0
    return segmentIndex in 0 until track.points.lastIndex &&
        validFraction &&
        !track.points[segmentIndex + 1].startsNewSegment
}

internal fun phoneMapRouteAnalysisRestoreIsCurrent(
    expected: PhoneMapRouteAnalysisSavedSelection,
    current: PhoneMapRouteAnalysisSavedSelection?,
    expectedGeneration: Long,
    requests: PhoneMapRouteAnalysisRequestState,
): Boolean = expected == current && expectedGeneration == requests.generation

internal val phoneMapRouteAnalysisSelectionSaver =
    mapSaver<PhoneMapRouteAnalysisSavedSelection?>(
        save = { selection -> selection?.savedValues().orEmpty() },
        restore = { values ->
            runCatching {
                PhoneMapRouteAnalysisSavedSelection(
                    trackId = values["trackId"] as String,
                    displayName = values["displayName"] as String,
                    contentRevision = values["revision"] as String,
                    pointA = PhoneMapCoordinate(values["aLat"] as Double, values["aLon"] as Double),
                    positionA = PhoneMapRouteAnalysisPosition(values["aIndex"] as Int, values["aFraction"] as Double),
                    pointB = (values["bLat"] as Double?)?.let { PhoneMapCoordinate(it, values["bLon"] as Double) },
                    positionB =
                        (values["bIndex"] as Int?)?.let {
                            PhoneMapRouteAnalysisPosition(it, values["bFraction"] as Double)
                        },
                )
            }.getOrNull()
        },
    )

private fun PhoneMapRouteAnalysisSavedSelection.savedValues(): Map<String, Any?> =
    mapOf(
        "trackId" to trackId,
        "displayName" to displayName,
        "revision" to contentRevision,
        "aLat" to pointA.latitude,
        "aLon" to pointA.longitude,
        "aIndex" to positionA.segmentIndex,
        "aFraction" to positionA.fraction,
        "bLat" to pointB?.latitude,
        "bLon" to pointB?.longitude,
        "bIndex" to positionB?.segmentIndex,
        "bFraction" to positionB?.fraction,
    )
