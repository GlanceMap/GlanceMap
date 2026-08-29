package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.util.concurrent.atomic.AtomicReference

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
    val displayName: String?,
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
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
) {
    @Suppress("CyclomaticComplexMethod") // Fixed fields make exported renderer state easy to inspect.
    fun toReportSection(): String =
        buildString {
            appendLine("Latest offline map renderer")
            appendLine("Result: $outcome")
            appendLine("File: ${displayName ?: "unknown"}")
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
            exceptionClass?.let { appendLine("Exception: $it") }
            exceptionMessage?.let { appendLine("Exception message: $it") }
            append("Map storage: companion private maps")
        }

    fun toCaptureLine(): String =
        "event=offline_map_renderer result=$outcome file=${displayName ?: "unknown"} " +
            "lastStage=${lastSuccessfulStage ?: "none"} failureStage=${failureStage ?: "none"} " +
            "theme=${themeId ?: "unknown"}/${styleId ?: "unknown"} fallback=$fallbackThemeUsed " +
            "tileLayer=$tileLayerAttached view=$viewAttached camera=$firstCameraPublished " +
            "exception=${exceptionClass ?: "none"}"
}

/** Retains one safe renderer summary beside the existing import summary in explicit debug capture. */
internal object PhoneOfflineMapRendererDiagnostics {
    const val TAG = "PhoneOfflineMapRenderer"

    private val latestAttempt = AtomicReference<PhoneOfflineMapRendererAttempt?>(null)

    fun record(attempt: PhoneOfflineMapRendererAttempt) {
        latestAttempt.set(attempt)
        PhoneDebugCapture.log(TAG, attempt.toCaptureLine())
    }

    fun update(attempt: PhoneOfflineMapRendererAttempt) {
        latestAttempt.set(attempt)
    }

    fun latestReportSection(): String? = latestAttempt.get()?.toReportSection()

    internal fun latestAttempt(): PhoneOfflineMapRendererAttempt? = latestAttempt.get()

    internal fun clear() {
        latestAttempt.set(null)
    }
}

/** Collects only initialization facts; it never stores a URI, full path, or map contents. */
@Suppress("TooManyFunctions") // Each short method maps directly to one renderer initialization fact.
internal class PhoneOfflineMapRendererTrace(
    displayName: String,
    config: PhoneOfflineThemeConfig,
) {
    private val fileName = displayName
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
    ) {
        mapFileOpened = true
        boundingBoxAvailable = boundsAvailable
        initialCameraInsideBounds = cameraInsideBounds
        initialCameraFallbackUsed = !cameraInsideBounds
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
        readyAttempt?.let { PhoneOfflineMapRendererDiagnostics.update(snapshot(it.outcome, it.failureStage, null)) }
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
            displayName = fileName,
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
            exceptionClass = error?.className,
            exceptionMessage = error?.message,
        )
}

internal data class PhoneOfflineMapRendererException(
    val className: String,
    val message: String?,
)

internal fun Throwable.toPhoneOfflineMapRendererException(): PhoneOfflineMapRendererException =
    PhoneOfflineMapRendererException(
        className = javaClass.simpleName.ifBlank { javaClass.name },
        message = message?.redactPhoneOfflineMapDiagnosticMessage(),
    )
