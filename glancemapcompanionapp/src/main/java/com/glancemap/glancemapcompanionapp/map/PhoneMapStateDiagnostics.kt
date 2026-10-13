package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture

internal enum class PhoneMapDiagnosticRendererStatus { ACTIVE, DISPOSED, PENDING }

internal fun phoneMapDiagnosticLocationOwner(
    offlineComparisonActive: Boolean,
    onlineComparisonActive: Boolean,
): String {
    val comparisonOwnsMarker = phoneMapComparisonOwnsSemanticOverlays(offlineComparisonActive, onlineComparisonActive)
    return if (comparisonOwnsMarker) "comparison" else "base"
}

internal fun PhoneOnlineMapSource.diagnosticProviderId(): String =
    when (this) {
        PhoneOnlineMapSource.OPEN_TOPO -> "open_topo_map"
        PhoneOnlineMapSource.PLAN_IGN_V2 -> "plan_ign_v2"
        PhoneOnlineMapSource.OPEN_STREET_MAP -> "open_street_map"
        PhoneOnlineMapSource.CYCLOSM -> "cyclosm"
        PhoneOnlineMapSource.TRACESTRACK_TOPO -> "tracestrack_topo"
        PhoneOnlineMapSource.SATELLITE -> "maptiler_satellite"
    }

internal data class PhoneMapDiagnosticRenderer(
    val identity: String,
    val generation: Long,
    val status: PhoneMapDiagnosticRendererStatus,
)

internal data class PhoneMapOfflineRevisionSelection(
    val selectionIdentity: String,
    val opaqueDiagnosticId: String,
)

internal fun phoneMapOfflineRevisionChangeLines(
    previous: Map<PhoneOfflineMapRendererRole, PhoneMapOfflineRevisionSelection>,
    current: Map<PhoneOfflineMapRendererRole, PhoneMapOfflineRevisionSelection>,
): List<String> =
    current.mapNotNull { (role, selection) ->
        val old = previous[role]
        if (
            old != null &&
            old.selectionIdentity == selection.selectionIdentity &&
            old.opaqueDiagnosticId != selection.opaqueDiagnosticId
        ) {
            "event=offline_map_revision_changed role=${role.name.lowercase()} " +
                "previous=${old.opaqueDiagnosticId} current=${selection.opaqueDiagnosticId}"
        } else {
            null
        }
    }

/** Contains only fixed provider IDs, opaque offline IDs, and non-positional map ownership state. */
internal data class PhoneMapDiagnosticState(
    val requestedBaseSourceId: String,
    val effectiveBaseSourceId: String,
    val baseFallbackReason: String?,
    val requestedComparisonSourceId: String,
    val effectiveComparisonSourceId: String,
    val comparisonFallbackReason: String?,
    val baseRenderer: PhoneMapDiagnosticRenderer,
    val comparisonRenderer: PhoneMapDiagnosticRenderer?,
    val comparisonMode: String,
    val comparisonTransparencyPercent: Float?,
    val appliedComparisonOpacity: Float?,
    val cameraAuthority: String,
    val touchOwner: String,
    val overlayOwner: String,
    val locationOwner: String,
    val locationFollowOwner: String,
    val followMode: String,
    val orientation: String,
    val panelMode: String,
    val panelTool: String,
    val panelContent: String,
    val toolLauncherExpanded: Boolean,
) {
    fun toReportSection(timestampMs: Long): String =
        buildString {
            appendLine("Companion map state")
            appendLine("Captured at (epoch ms): $timestampMs")
            appendLine("Base source requested: $requestedBaseSourceId")
            appendLine("Base source effective: $effectiveBaseSourceId")
            appendLine("Base source fallback: ${baseFallbackReason ?: "none"}")
            appendLine(
                "Base renderer: ${baseRenderer.identity} " +
                    "generation=${baseRenderer.generation} status=${baseRenderer.status}",
            )
            appendLine("Comparison source requested: $requestedComparisonSourceId")
            appendLine("Comparison source effective: $effectiveComparisonSourceId")
            appendLine("Comparison source fallback: ${comparisonFallbackReason ?: "none"}")
            comparisonRenderer?.let {
                appendLine("Comparison renderer: ${it.identity} generation=${it.generation} status=${it.status}")
            } ?: appendLine("Comparison renderer: none")
            appendLine("Comparison mode: $comparisonMode")
            appendLine("Comparison transparency: ${comparisonTransparencyPercent?.let { "$it%" } ?: "n/a"}")
            appendLine("Comparison applied opacity: ${appliedComparisonOpacity ?: "n/a"}")
            appendLine("Camera authority: $cameraAuthority")
            appendLine("Touch owner: $touchOwner")
            appendLine("Overlay owner: $overlayOwner")
            appendLine("Location owner: $locationOwner")
            appendLine("Location follow owner: $locationFollowOwner")
            appendLine("Follow: $followMode")
            appendLine("Orientation: $orientation")
            appendLine(
                "Panel: mode=$panelMode tool=$panelTool content=$panelContent " +
                    "launcherExpanded=$toolLauncherExpanded",
            )
        }

    fun toTransitionLine(): String =
        "event=map_state_changed base=$effectiveBaseSourceId comparison=$effectiveComparisonSourceId " +
            "camera=$cameraAuthority touch=$touchOwner overlays=$overlayOwner location=$locationOwner " +
            "location_follow=$locationFollowOwner " +
            "follow=$followMode orientation=$orientation panel=$panelMode/$panelTool"
}

/** Emits only when map ownership or selection state changes, and seeds every explicit capture session. */
internal object PhoneMapStateDiagnostics {
    const val TAG = "PhoneMapState"

    private var latest: PhoneMapDiagnosticState? = null
    private var lastCaptureSessionId = 0L
    private var latestOfflineMapSelections =
        emptyMap<PhoneOfflineMapRendererRole, PhoneMapOfflineRevisionSelection>()

    @Synchronized
    fun record(
        state: PhoneMapDiagnosticState,
        captureSessionId: Long,
        captureActive: Boolean,
        timestampMs: Long = System.currentTimeMillis(),
        offlineMapSelections: Map<PhoneOfflineMapRendererRole, PhoneMapOfflineRevisionSelection> = emptyMap(),
    ) {
        val changed = latest != state
        val transitionLine = state.toTransitionLine()
        val transitionChanged = latest?.toTransitionLine() != transitionLine
        val revisionEvents = phoneMapOfflineRevisionChangeLines(latestOfflineMapSelections, offlineMapSelections)
        val captureStarted = captureActive && captureSessionId > 0L && captureSessionId != lastCaptureSessionId
        val activeCaptureSession = captureActive && captureSessionId > 0L
        val shouldWriteCaptureState = changed || captureStarted || revisionEvents.isNotEmpty()
        latest = state
        latestOfflineMapSelections = offlineMapSelections
        if (activeCaptureSession && shouldWriteCaptureState) {
            PhoneDebugCapture.updateSection(
                MAP_STATE_SECTION_KEY,
                state.toReportSection(timestampMs),
                expectedSessionId = captureSessionId,
            )
            if (transitionChanged) PhoneDebugCapture.log(TAG, transitionLine, expectedSessionId = captureSessionId)
            revisionEvents.forEach { line -> PhoneDebugCapture.log(TAG, line, expectedSessionId = captureSessionId) }
        }
        if (activeCaptureSession) lastCaptureSessionId = captureSessionId
    }

    @Synchronized
    internal fun clear() {
        latest = null
        lastCaptureSessionId = 0L
        latestOfflineMapSelections = emptyMap()
    }
}

internal const val MAP_STATE_SECTION_KEY = "map_state"
