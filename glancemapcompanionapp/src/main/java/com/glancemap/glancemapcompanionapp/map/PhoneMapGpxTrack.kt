package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import kotlin.collections.AbstractList

private const val PHONE_RECORDING_OVERLAY_ID = "phone-recording"

/** Semantic GPX geometry supplied to the phone map after companion parsing has completed. */
internal data class PhoneMapGpxTrack(
    val id: String,
    val points: List<TrailPoint>,
) {
    init {
        require(id.isNotBlank())
    }
}

/** A Route Library GPX item whose map visibility is independent from every other item. */
internal data class PhoneMapGpxItem(
    val id: String,
    val displayName: String,
    val track: PhoneMapGpxTrack,
    val enabled: Boolean,
    val isEditable: Boolean = true,
    val isRecording: Boolean = false,
    val libraryKey: String = "gpx/$id",
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(track.id == id)
    }
}

/** One independently enabled track prepared for any phone-map renderer. */
internal data class PhoneMapGpxOverlay(
    val id: String,
    val displayName: String,
    val segments: List<PhoneMapRouteSegment>,
    /** Non-null only for the live recording overlay, avoiding full-history list equality. */
    val renderRevision: Long? = null,
    val renderBaseRevision: Long? = null,
    val renderStartIndex: Int = 0,
    val settings: PhoneMapGpxSettings? = null,
) {
    override fun equals(other: Any?): Boolean =
        if (this === other) {
            true
        } else if (other !is PhoneMapGpxOverlay || id != other.id || displayName != other.displayName) {
            false
        } else if (settings != other.settings) {
            false
        } else if (renderRevision != null || other.renderRevision != null) {
            renderRevision == other.renderRevision
        } else {
            segments == other.segments
        }

    override fun hashCode(): Int =
        31 * (
            31 * (31 * id.hashCode() + displayName.hashCode()) +
                (renderRevision?.hashCode() ?: segments.hashCode())
        ) + (settings?.hashCode() ?: 0)
}

/** Small route metadata passed into the phone map without coupling its panel UI to Route Library. */
internal data class PhoneMapGpxSource(
    val id: String,
    val displayName: String,
    val isRecording: Boolean = false,
    val libraryKey: String = "gpx/$id",
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
    }
}

internal fun mergePhoneMapGpxItems(
    previous: List<PhoneMapGpxItem>,
    loaded: List<PhoneMapGpxItem>,
    initiallyEnabledId: String?,
): List<PhoneMapGpxItem> {
    val enabledById = previous.associate { item -> item.id to item.enabled }
    return loaded.map { item ->
        item.copy(enabled = enabledById[item.id] ?: (item.id == initiallyEnabledId))
    }
}

internal fun List<PhoneMapGpxItem>.toggleEnabled(id: String): List<PhoneMapGpxItem> =
    map { item ->
        if (item.id == id) item.copy(enabled = !item.enabled) else item
    }

internal fun List<PhoneMapGpxItem>.enabledOverlays(
    globalVisible: Boolean,
    showSavedActivities: Boolean = true,
    customization: PhoneGpxCustomizationState = PhoneGpxCustomizationState(),
    generalSettings: PhoneMapGpxSettings = PhoneMapGpxSettings(),
    organization: PhoneLibraryOrganization = PhoneLibraryOrganization(),
): List<PhoneMapGpxOverlay> =
    if (!globalVisible) {
        emptyList()
    } else {
        filter { item -> item.enabled && (showSavedActivities || !item.isRecording) }
            .map { item ->
                PhoneMapGpxOverlay(
                    id = item.id,
                    displayName = item.displayName,
                    segments = item.track.toRouteSegments(),
                )
            }.withAppearance(this, customization, generalSettings, organization)
    }

internal fun List<PhoneMapGpxOverlay>.withAppearance(
    items: List<PhoneMapGpxItem>,
    customization: PhoneGpxCustomizationState,
    generalSettings: PhoneMapGpxSettings,
    organization: PhoneLibraryOrganization,
): List<PhoneMapGpxOverlay> {
    val keysById = items.associate { it.id to it.libraryKey }
    return map { overlay ->
        val key = keysById[overlay.id] ?: "gpx/${overlay.id}"
        val settings =
            customization
                .settingsForFile(key, generalSettings, organization)
                .takeIf { it != generalSettings }
        if (overlay.settings == settings) overlay else overlay.copy(settings = settings)
    }
}

/** A renderable contiguous GPX segment, deliberately independent from a map SDK. */
internal data class PhoneMapRouteSegment(
    val points: List<GeoPoint>,
    val elevationMeters: List<Double?> = emptyList(),
    val recordingStartIndex: Int? = null,
) {
    init {
        require(elevationMeters.isEmpty() || elevationMeters.size == points.size)
    }
}

/** Mapsforge/MapLibre receive lazy coordinate views over the service-owned point objects. */
internal fun phoneRecordingOverlay(
    state: PhoneRecordingUiState,
    displayName: String,
): PhoneMapGpxOverlay? {
    if (!state.session.isActive || state.points.size < 2) return null
    val starts = (state.segmentStartIndices + 0).distinct().sorted()
    val segments =
        starts.mapIndexedNotNull { segmentIndex, startIndex ->
            val endIndex = starts.getOrNull(segmentIndex + 1) ?: state.points.size
            val segmentSize = endIndex - startIndex
            if (segmentSize >= 2) {
                PhoneMapRouteSegment(
                    points = RecordingGeoPointView(state.points, startIndex, segmentSize),
                    elevationMeters = RecordingElevationView(state.points, startIndex, segmentSize),
                    recordingStartIndex = startIndex,
                )
            } else {
                null
            }
        }
    return segments.takeIf { it.isNotEmpty() }?.let { renderSegments ->
        PhoneMapGpxOverlay(
            id = PHONE_RECORDING_OVERLAY_ID,
            displayName = displayName,
            segments = renderSegments,
            renderRevision = state.pointsRevision,
            renderBaseRevision = state.pointsBaseRevision,
            renderStartIndex = state.pointsChangedFromIndex,
        )
    }
}

private class RecordingGeoPointView(
    private val points: List<com.glancemap.trailcore.recording.RecordingPoint>,
    private val startIndex: Int,
    override val size: Int,
) : AbstractList<GeoPoint>() {
    override fun get(index: Int): GeoPoint = points[startIndex + index].location
}

private class RecordingElevationView(
    private val points: List<com.glancemap.trailcore.recording.RecordingPoint>,
    private val startIndex: Int,
    override val size: Int,
) : AbstractList<Double?>() {
    override fun get(index: Int): Double? = points[startIndex + index].elevationMeters
}

internal fun PhoneMapGpxTrack.toRouteSegments(): List<PhoneMapRouteSegment> {
    val segments = mutableListOf<List<TrailPoint>>()
    var currentSegment = mutableListOf<TrailPoint>()
    points.forEach { point ->
        if (point.startsNewSegment && currentSegment.isNotEmpty()) {
            segments += currentSegment
            currentSegment = mutableListOf()
        }
        currentSegment += point
    }
    if (currentSegment.isNotEmpty()) {
        segments += currentSegment
    }
    return segments
        .filter { segment -> segment.size >= MINIMUM_RENDERABLE_SEGMENT_POINTS }
        .map { segment ->
            PhoneMapRouteSegment(
                points = segment.map(TrailPoint::location),
                elevationMeters = segment.map(TrailPoint::elevationMeters),
            )
        }
}

internal data class PhoneMapRouteBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

internal fun List<PhoneMapRouteSegment>.boundsOrNull(): PhoneMapRouteBounds? {
    val points = flatMap(PhoneMapRouteSegment::points)
    if (points.size < MINIMUM_RENDERABLE_SEGMENT_POINTS) return null
    return PhoneMapRouteBounds(
        west = points.minOf(GeoPoint::longitude),
        south = points.minOf(GeoPoint::latitude),
        east = points.maxOf(GeoPoint::longitude),
        north = points.maxOf(GeoPoint::latitude),
    )
}

private const val MINIMUM_RENDERABLE_SEGMENT_POINTS = 2
