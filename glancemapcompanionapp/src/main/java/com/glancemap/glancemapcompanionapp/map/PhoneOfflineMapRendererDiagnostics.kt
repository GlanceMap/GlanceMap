package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal enum class PhoneOfflineMapRendererRole { BASE, COMPARISON }

internal enum class PhoneOfflineMapRendererStatus { ACTIVE, DISPOSED }

internal data class PhoneOfflineMapRendererIdentity(
    val role: PhoneOfflineMapRendererRole,
    val rendererId: Long,
    val generation: Long,
    val offlineMapId: String? = null,
)

internal data class PhoneOfflineMapRendererRef(
    val identity: PhoneOfflineMapRendererIdentity,
    val status: PhoneOfflineMapRendererStatus,
)

internal data class PhoneOfflineMapRendererSnapshot(
    val identity: PhoneOfflineMapRendererIdentity,
    val status: PhoneOfflineMapRendererStatus,
    val updatedAtMs: Long,
    val attempt: PhoneOfflineMapRendererAttempt? = null,
    val runtime: PhoneOfflineMapRuntimeDiagnostics? = null,
) {
    fun toReportSection(): String =
        buildString {
            appendLine("${identity.role.name.lowercase()} offline renderer")
            appendLine("Renderer id: ${identity.rendererId}")
            appendLine("Generation: ${identity.generation}")
            appendLine("Offline map id: ${identity.offlineMapId ?: "none"}")
            appendLine("Status: $status")
            appendLine("Updated at (epoch ms): $updatedAtMs")
            attempt?.let { append(it.toReportDetails()) } ?: appendLine("Attempt: none")
            runtime?.let {
                appendLine()
                append(it.toReportSection())
            }
        }
}

internal fun PhoneOfflineMap.opaqueDiagnosticId(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(rendererIdentity.toByteArray(Charsets.UTF_8))
    return "offline-${digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }}"
}

private fun offlineRendererReportSection(snapshots: Collection<PhoneOfflineMapRendererSnapshot>): String? =
    snapshots
        .sortedBy { it.identity.role.ordinal }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "\n\n", prefix = "Offline map renderer snapshots\n\n") { it.toReportSection() }

private fun publishOfflineRendererSnapshots(
    snapshots: Map<PhoneOfflineMapRendererRole, PhoneOfflineMapRendererSnapshot>,
    rendererRefs: MutableStateFlow<Map<PhoneOfflineMapRendererRole, PhoneOfflineMapRendererRef>>,
) {
    rendererRefs.value =
        snapshots.mapValues { (_, snapshot) ->
            PhoneOfflineMapRendererRef(snapshot.identity, snapshot.status)
        }
    offlineRendererReportSection(snapshots.values)
        ?.let { report -> PhoneDebugCapture.updateSection("offline_map_renderer", report) }
}

private fun PhoneOfflineMapRendererSnapshot.matches(identity: PhoneOfflineMapRendererIdentity): Boolean = this.identity == identity

private fun PhoneOfflineMapRendererSnapshot.matches(attempt: PhoneOfflineMapRendererAttempt): Boolean =
    identity.role == attempt.role &&
        identity.rendererId == attempt.rendererId &&
        identity.generation == attempt.generation &&
        identity.offlineMapId == attempt.offlineMapId &&
        status == PhoneOfflineMapRendererStatus.ACTIVE

private fun PhoneOfflineMapRendererSnapshot.matches(runtime: PhoneOfflineMapRuntimeDiagnostics): Boolean =
    identity.role == runtime.role &&
        identity.rendererId == runtime.rendererId &&
        identity.generation == runtime.generation &&
        identity.offlineMapId == runtime.offlineMapId &&
        status == PhoneOfflineMapRendererStatus.ACTIVE

internal enum class PhoneOfflineMapRendererStage {
    MAP_SELECTED,
    VIEW_CREATE,
    GRAPHICS_FACTORY,
    MAPFILE_OPEN,
    MAPVIEW_CREATE,
    TILE_CACHE_CREATE,
    TILE_LAYER_CREATE,
    THEME_CREATE,
    THEME_APPLY,
    LAYER_ATTACH,
    OVERLAYS_ATTACH,
    VIEW_ATTACH,
    FIRST_CAMERA,
    READY,
}

internal enum class PhoneOfflineMapRendererOutcome {
    READY,
    FAILED,
}

internal data class PhoneOfflineMapRendererAttempt(
    val outcome: PhoneOfflineMapRendererOutcome,
    val role: PhoneOfflineMapRendererRole,
    val rendererId: Long,
    val generation: Long,
    val offlineMapId: String,
    val lastSuccessfulStage: PhoneOfflineMapRendererStage?,
    val failureStage: PhoneOfflineMapRendererStage?,
    val themeId: String?,
    val styleId: String?,
    val mapFileOpened: Boolean = false,
    val tileCacheCreated: Boolean = false,
    val tileLayerAttached: Boolean = false,
    val themeObjectConstructed: Boolean = false,
    val themeApplied: Boolean = false,
    val fallbackThemeUsed: Boolean = false,
    val resourceProviderFailed: Boolean = false,
    val viewAttached: Boolean = false,
    val firstCameraPublished: Boolean = false,
    val boundingBoxAvailable: Boolean? = null,
    val initialCameraInsideBounds: Boolean? = null,
    val initialCameraFallbackUsed: Boolean? = null,
    val initialZoom: Int? = null,
    val initialZoomSource: PhoneOfflineInitialCameraReason? = null,
    val initialZoomClamped: Boolean? = null,
    val exceptionClass: String? = null,
) {
    @Suppress("CyclomaticComplexMethod") // Fixed fields make exported renderer state easy to inspect.
    internal fun toReportDetails(): String =
        buildString {
            appendLine("Result: $outcome")
            appendLine("Last stage: ${lastSuccessfulStage ?: "none"}")
            appendLine("Failure stage: ${failureStage ?: "none"}")
            appendLine("Theme: ${themeId ?: "unknown"} / ${styleId ?: "unknown"}")
            appendLine("MapFile opened: $mapFileOpened")
            appendLine("Tile cache created: $tileCacheCreated")
            appendLine("Tile layer attached: $tileLayerAttached")
            appendLine("Theme object constructed: $themeObjectConstructed")
            appendLine("Theme applied: $themeApplied")
            appendLine("Fallback theme: $fallbackThemeUsed")
            appendLine("Theme resource provider failure: $resourceProviderFailed")
            appendLine("View attached: $viewAttached")
            appendLine("First camera published: $firstCameraPublished")
            boundingBoxAvailable?.let { appendLine("Bounding box available: $it") }
            initialCameraInsideBounds?.let { appendLine("Initial camera inside bounds: $it") }
            initialCameraFallbackUsed?.let { appendLine("Initial camera fallback used: $it") }
            initialZoom?.let { appendLine("Initial zoom: $it") }
            initialZoomSource?.let { appendLine("Initial zoom source: $it") }
            initialZoomClamped?.let { appendLine("Zoom clamped: $it") }
            exceptionClass?.let { appendLine("Exception: $it") }
            append("Map storage: companion private maps")
        }

    fun toCaptureLine(): String =
        "event=offline_map_renderer result=$outcome role=${role.name.lowercase()} " +
            "renderer=$rendererId generation=$generation offlineMap=$offlineMapId " +
            "lastStage=${lastSuccessfulStage ?: "none"} failureStage=${failureStage ?: "none"} " +
            "theme=${themeId ?: "unknown"}/${styleId ?: "unknown"} fallback=$fallbackThemeUsed " +
            "tileLayer=$tileLayerAttached view=$viewAttached camera=$firstCameraPublished " +
            "exception=${exceptionClass ?: "none"}"
}

/** Retains the current base and comparison renderer snapshots in explicit debug capture. */
internal object PhoneOfflineMapRendererDiagnostics {
    const val TAG = "PhoneOfflineMapRenderer"

    private val rendererIds = AtomicLong()
    private val snapshots = mutableMapOf<PhoneOfflineMapRendererRole, PhoneOfflineMapRendererSnapshot>()
    private val rendererRefsFlow =
        MutableStateFlow<Map<PhoneOfflineMapRendererRole, PhoneOfflineMapRendererRef>>(emptyMap())

    val rendererRefs = rendererRefsFlow.asStateFlow()

    @Synchronized
    fun registerRenderer(
        role: PhoneOfflineMapRendererRole,
        nowMs: Long = System.currentTimeMillis(),
    ): PhoneOfflineMapRendererIdentity {
        val identity = PhoneOfflineMapRendererIdentity(role, rendererIds.incrementAndGet(), generation = 0L)
        snapshots[role] = PhoneOfflineMapRendererSnapshot(identity, PhoneOfflineMapRendererStatus.ACTIVE, nowMs)
        publishOfflineRendererSnapshots(snapshots, rendererRefsFlow)
        return identity
    }

    @Synchronized
    fun beginGeneration(
        previous: PhoneOfflineMapRendererIdentity,
        offlineMapId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PhoneOfflineMapRendererIdentity {
        val identity = previous.copy(generation = previous.generation + 1L, offlineMapId = offlineMapId)
        val current = snapshots[previous.role]
        if (current?.matches(previous) == true && current.status == PhoneOfflineMapRendererStatus.ACTIVE) {
            snapshots[previous.role] =
                PhoneOfflineMapRendererSnapshot(identity, PhoneOfflineMapRendererStatus.ACTIVE, nowMs)
            publishOfflineRendererSnapshots(snapshots, rendererRefsFlow)
        }
        return identity
    }

    @Synchronized
    fun markDisposed(
        identity: PhoneOfflineMapRendererIdentity,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = snapshots[identity.role]
        val canDispose =
            current != null &&
                current.matches(identity) &&
                current.status != PhoneOfflineMapRendererStatus.DISPOSED
        if (canDispose) {
            val active = checkNotNull(current)
            snapshots[identity.role] = active.copy(status = PhoneOfflineMapRendererStatus.DISPOSED, updatedAtMs = nowMs)
            publishOfflineRendererSnapshots(snapshots, rendererRefsFlow)
        }
        return canDispose
    }

    @Synchronized
    fun record(
        attempt: PhoneOfflineMapRendererAttempt,
        logEvent: Boolean = true,
    ) {
        val current = snapshots[attempt.role]
        if (current != null && current.matches(attempt)) {
            snapshots[attempt.role] =
                current.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    attempt = attempt,
                    runtime = null,
                )
            publishOfflineRendererSnapshots(snapshots, rendererRefsFlow)
            if (logEvent) PhoneDebugCapture.log(TAG, attempt.toCaptureLine())
        }
    }

    @Synchronized
    fun recordRuntime(runtime: PhoneOfflineMapRuntimeDiagnostics) {
        val current = snapshots[runtime.role]
        if (current != null && current.matches(runtime)) {
            val previousRuntime = current.runtime
            val unchangedExceptLocationAge =
                previousRuntime != null &&
                    previousRuntime.copy(locationAgeMillis = runtime.locationAgeMillis) == runtime
            if (!unchangedExceptLocationAge) {
                snapshots[runtime.role] = current.copy(updatedAtMs = System.currentTimeMillis(), runtime = runtime)
                publishOfflineRendererSnapshots(snapshots, rendererRefsFlow)
            }
        }
    }

    fun recordLifecycleEvent(
        event: String,
        detail: String,
    ) {
        PhoneDebugCapture.log(TAG, "event=$event $detail")
    }

    @Synchronized
    fun latestReportSection(): String? = offlineRendererReportSection(snapshots.values)

    @Synchronized
    internal fun snapshot(role: PhoneOfflineMapRendererRole): PhoneOfflineMapRendererSnapshot? = snapshots[role]

    @Synchronized
    internal fun latestAttempt(): PhoneOfflineMapRendererAttempt? =
        snapshots.values
            .mapNotNull { snapshot -> snapshot.attempt?.let { snapshot.updatedAtMs to it } }
            .maxByOrNull { (updatedAt, _) -> updatedAt }
            ?.second

    @Synchronized
    internal fun clear() {
        snapshots.clear()
        rendererRefsFlow.value = emptyMap()
    }
}

/** Collects only initialization facts; it never stores a URI, full path, or map contents. */
@Suppress("TooManyFunctions") // Each short method maps directly to one renderer initialization fact.
internal class PhoneOfflineMapRendererTrace(
    private val identity: PhoneOfflineMapRendererIdentity,
    config: PhoneOfflineThemeConfig,
) {
    internal val role: PhoneOfflineMapRendererRole = identity.role
    internal val rendererId: Long = identity.rendererId
    internal val generation: Long = identity.generation
    internal val offlineMapId: String = requireNotNull(identity.offlineMapId)
    private var themeId = config.themeId
    private var styleId = config.styleId
    private var currentStage = PhoneOfflineMapRendererStage.MAP_SELECTED
    private var lastSuccessfulStage: PhoneOfflineMapRendererStage? = null
    private var mapFileOpened = false
    private var tileCacheCreated = false
    private var tileLayerAttached = false
    private var themeObjectConstructed = false
    private var themeApplied = false
    private var fallbackThemeUsed = false
    private var resourceProviderFailed = false
    private var viewAttached = false
    private var firstCameraPublished = false
    private var boundingBoxAvailable: Boolean? = null
    private var initialCameraInsideBounds: Boolean? = null
    private var initialCameraFallbackUsed: Boolean? = null
    private var initialZoom: Int? = null
    private var initialZoomSource: PhoneOfflineInitialCameraReason? = null
    private var initialZoomClamped: Boolean? = null
    private var readyAttempt: PhoneOfflineMapRendererAttempt? = null

    @Synchronized
    fun begin(stage: PhoneOfflineMapRendererStage) {
        currentStage = stage
    }

    @Synchronized
    fun complete(stage: PhoneOfflineMapRendererStage) {
        currentStage = stage
        lastSuccessfulStage = stage
    }

    @Synchronized
    fun mapFileOpened(
        boundsAvailable: Boolean,
        cameraInsideBounds: Boolean,
        initialZoom: Int,
        initialZoomSource: PhoneOfflineInitialCameraReason,
        zoomClamped: Boolean,
    ) {
        mapFileOpened = true
        boundingBoxAvailable = boundsAvailable
        initialCameraInsideBounds = cameraInsideBounds
        initialCameraFallbackUsed = !cameraInsideBounds
        this.initialZoom = initialZoom
        this.initialZoomSource = initialZoomSource
        initialZoomClamped = zoomClamped
    }

    @Synchronized
    fun tileCacheCreated() {
        tileCacheCreated = true
    }

    @Synchronized
    fun themeCreated(
        resolved: PhoneOfflineThemeConfig,
        fallbackUsed: Boolean,
    ) {
        themeId = resolved.themeId
        styleId = resolved.styleId
        themeObjectConstructed = !fallbackUsed
        fallbackThemeUsed = fallbackThemeUsed || fallbackUsed
    }

    @Synchronized
    fun themeApplied(fallbackUsed: Boolean = false) {
        themeApplied = true
        fallbackThemeUsed = fallbackThemeUsed || fallbackUsed
    }

    @Synchronized
    fun tileLayerAttached() {
        tileLayerAttached = true
    }

    @Synchronized
    fun viewAttached() {
        viewAttached = true
    }

    @Synchronized
    fun firstCameraPublished(published: Boolean) {
        firstCameraPublished = firstCameraPublished || published
    }

    @Synchronized
    fun resourceProviderFailed() {
        resourceProviderFailed = true
        readyAttempt?.let { attempt ->
            PhoneOfflineMapRendererDiagnostics.record(
                snapshot(attempt.outcome, attempt.failureStage, null),
                logEvent = false,
            )
        }
    }

    @Synchronized
    fun ready(): PhoneOfflineMapRendererAttempt {
        complete(PhoneOfflineMapRendererStage.READY)
        return snapshot(PhoneOfflineMapRendererOutcome.READY, failureStage = null, error = null)
            .also { readyAttempt = it }
    }

    @Synchronized
    fun failed(error: Throwable): PhoneOfflineMapRendererAttempt =
        snapshot(
            outcome = PhoneOfflineMapRendererOutcome.FAILED,
            failureStage = currentStage,
            error = error.toPhoneOfflineMapRendererException(),
        )

    private fun snapshot(
        outcome: PhoneOfflineMapRendererOutcome,
        failureStage: PhoneOfflineMapRendererStage?,
        error: PhoneOfflineMapRendererException?,
    ): PhoneOfflineMapRendererAttempt =
        PhoneOfflineMapRendererAttempt(
            outcome = outcome,
            role = identity.role,
            rendererId = identity.rendererId,
            generation = identity.generation,
            offlineMapId = offlineMapId,
            lastSuccessfulStage = lastSuccessfulStage,
            failureStage = failureStage,
            themeId = themeId,
            styleId = styleId,
            mapFileOpened = mapFileOpened,
            tileCacheCreated = tileCacheCreated,
            tileLayerAttached = tileLayerAttached,
            themeObjectConstructed = themeObjectConstructed,
            themeApplied = themeApplied,
            fallbackThemeUsed = fallbackThemeUsed,
            resourceProviderFailed = resourceProviderFailed,
            viewAttached = viewAttached,
            firstCameraPublished = firstCameraPublished,
            boundingBoxAvailable = boundingBoxAvailable,
            initialCameraInsideBounds = initialCameraInsideBounds,
            initialCameraFallbackUsed = initialCameraFallbackUsed,
            initialZoom = initialZoom,
            initialZoomSource = initialZoomSource,
            initialZoomClamped = initialZoomClamped,
            exceptionClass = error?.className,
        )
}

internal data class PhoneOfflineMapRendererException(
    val className: String,
)

internal fun Throwable.toPhoneOfflineMapRendererException(): PhoneOfflineMapRendererException =
    PhoneOfflineMapRendererException(
        className = javaClass.simpleName.ifBlank { javaClass.name },
    )
