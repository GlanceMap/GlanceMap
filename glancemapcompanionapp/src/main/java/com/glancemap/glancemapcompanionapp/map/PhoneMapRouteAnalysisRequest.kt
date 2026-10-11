package com.glancemap.glancemapcompanionapp.map

internal enum class PhoneMapRouteAnalysisSelectionPhase {
    POINT_A,
    POINT_B,
}

internal data class PhoneMapRouteAnalysisTrackRevision(
    val trackId: String,
    val displayName: String,
    val track: PhoneMapGpxTrack,
) {
    fun isAvailableIn(
        items: List<PhoneMapGpxItem>,
        gpxTracksVisible: Boolean,
    ): Boolean =
        gpxTracksVisible &&
            items.any { item ->
                item.enabled && item.id == trackId && item.displayName == displayName && item.track === track
            }
}

internal data class PhoneMapRouteAnalysisRequestInput(
    val phase: PhoneMapRouteAnalysisSelectionPhase,
    val press: PhoneMapCoordinate,
    val candidateItems: List<PhoneMapGpxItem>,
    val currentAnalysis: PhoneMapRouteAnalysis?,
    val currentTrackRevision: PhoneMapRouteAnalysisTrackRevision?,
    val settings: PhoneMapGpxSettings,
    val generalSettings: PhoneGeneralSettings,
)

internal data class PhoneMapRouteAnalysisRequest(
    val generation: Long,
    val input: PhoneMapRouteAnalysisRequestInput,
) {
    fun hasSameCandidateTracks(currentItems: List<PhoneMapGpxItem>): Boolean {
        val currentCandidates =
            currentItems.filter { item ->
                item.enabled &&
                    (
                        input.phase == PhoneMapRouteAnalysisSelectionPhase.POINT_A ||
                            item.id == input.currentAnalysis?.trackId
                    )
            }
        return sameTrackSources(input.candidateItems, currentCandidates)
    }
}

internal data class PhoneMapRouteAnalysisRequestResult(
    val analysis: PhoneMapRouteAnalysis,
    val trackRevision: PhoneMapRouteAnalysisTrackRevision,
)

internal data class PhoneMapRouteAnalysisRequestCurrentState(
    val phase: PhoneMapRouteAnalysisSelectionPhase,
    val analysis: PhoneMapRouteAnalysis?,
    val trackRevision: PhoneMapRouteAnalysisTrackRevision?,
    val items: List<PhoneMapGpxItem>,
    val routeAnalysisAvailable: Boolean,
    val gpxTracksVisible: Boolean,
    val settings: PhoneMapGpxSettings,
    val generalSettings: PhoneGeneralSettings,
)

internal data class PhoneMapRouteAnalysisRequestState(
    val generation: Long = 0L,
) {
    fun begin(
        input: PhoneMapRouteAnalysisRequestInput,
    ): Pair<PhoneMapRouteAnalysisRequestState, PhoneMapRouteAnalysisRequest> {
        val nextState = copy(generation = generation + 1L)
        return nextState to PhoneMapRouteAnalysisRequest(nextState.generation, input)
    }

    fun invalidate(): PhoneMapRouteAnalysisRequestState = copy(generation = generation + 1L)

    fun canPublish(
        request: PhoneMapRouteAnalysisRequest,
        result: PhoneMapRouteAnalysisRequestResult,
        current: PhoneMapRouteAnalysisRequestCurrentState,
    ): Boolean =
        isCurrentRequest(request, current) &&
            request.hasSameCandidateTracks(current.items) &&
            result.matchesCurrentSource(request, current)

    private fun isCurrentRequest(
        request: PhoneMapRouteAnalysisRequest,
        current: PhoneMapRouteAnalysisRequestCurrentState,
    ): Boolean =
        generation == request.generation &&
            request.input.phase == current.phase &&
            current.routeAnalysisAvailable &&
            current.gpxTracksVisible &&
            request.input.settings == current.settings &&
            request.input.generalSettings == current.generalSettings &&
            request.matchesCurrentSelection(current)
}

internal fun computePhoneMapRouteAnalysisRequest(
    request: PhoneMapRouteAnalysisRequest,
): PhoneMapRouteAnalysisRequestResult? =
    request.input.let { input ->
        val selectingPointB = input.phase == PhoneMapRouteAnalysisSelectionPhase.POINT_B
        val hit =
            findClosestPhoneMapRoutePosition(
                press = input.press,
                items = input.candidateItems,
                allowedTrackId = input.currentAnalysis?.trackId.takeIf { selectingPointB },
            )?.takeIf { value -> value.distanceToTrackMeters <= PHONE_ROUTE_ANALYSIS_PRESS_THRESHOLD_METERS }
                ?: return null
        val pointA =
            if (selectingPointB) {
                pointAForRequest(input, hit) ?: return null
            } else {
                hit
            }
        PhoneMapRouteAnalysisRequestResult(
            analysis =
                buildPhoneMapRouteAnalysis(
                    pointA = pointA,
                    pointB = hit.takeIf { selectingPointB },
                    settings = input.settings,
                    generalSettings = input.generalSettings,
                ),
            trackRevision = hit.item.routeAnalysisTrackRevision(),
        )
    }

private fun pointAForRequest(
    input: PhoneMapRouteAnalysisRequestInput,
    pointB: PhoneMapRouteAnalysisHit,
): PhoneMapRouteAnalysisHit? {
    val current = input.currentAnalysis
    val revision = input.currentTrackRevision
    val sourceItem = revision?.let { expected -> input.candidateItems.firstOrNull { item -> item.matches(expected) } }
    return if (current == null || revision == null || sourceItem == null) {
        null
    } else {
        PhoneMapRouteAnalysisHit(
            item = sourceItem,
            position = current.pointAPosition,
            coordinate = current.pointA,
            distanceToTrackMeters = 0.0,
        )
    }.takeIf { pointB.item.id == revision?.trackId }
}

private fun PhoneMapRouteAnalysisRequest.matchesCurrentSelection(
    current: PhoneMapRouteAnalysisRequestCurrentState,
): Boolean =
    input.phase != PhoneMapRouteAnalysisSelectionPhase.POINT_B ||
        (
            input.currentAnalysis === current.analysis &&
                sameTrackRevision(input.currentTrackRevision, current.trackRevision)
        )

private fun PhoneMapRouteAnalysisRequestResult.matchesCurrentSource(
    request: PhoneMapRouteAnalysisRequest,
    current: PhoneMapRouteAnalysisRequestCurrentState,
): Boolean =
    trackRevision.isAvailableIn(current.items, current.gpxTracksVisible) &&
        analysis.trackId == trackRevision.trackId &&
        analysis.displayName == trackRevision.displayName &&
        request.input.candidateItems.any { item -> item.matches(trackRevision) }

private fun PhoneMapGpxItem.routeAnalysisTrackRevision(): PhoneMapRouteAnalysisTrackRevision =
    PhoneMapRouteAnalysisTrackRevision(
        trackId = id,
        displayName = displayName,
        track = track,
    )

private fun PhoneMapGpxItem.matches(revision: PhoneMapRouteAnalysisTrackRevision): Boolean =
    id == revision.trackId &&
        displayName == revision.displayName &&
        track === revision.track

private fun sameTrackRevision(
    first: PhoneMapRouteAnalysisTrackRevision?,
    second: PhoneMapRouteAnalysisTrackRevision?,
): Boolean =
    first === second ||
        first != null &&
        second != null &&
        first.trackId == second.trackId &&
        first.displayName == second.displayName &&
        first.track === second.track

private fun sameTrackSources(
    expected: List<PhoneMapGpxItem>,
    current: List<PhoneMapGpxItem>,
): Boolean {
    if (expected.size != current.size) return false
    val currentById = current.associateBy(PhoneMapGpxItem::id)
    return expected.all { snapshot ->
        val live = currentById[snapshot.id]
        live != null && live.enabled && live.displayName == snapshot.displayName && live.track === snapshot.track
    }
}
