package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.util.concurrent.atomic.AtomicLong

internal enum class PhoneMapLibreRendererRole {
    BASE,
    COMPARISON,
}

internal enum class PhoneMapLibreErrorCategory(
    val diagnosticValue: String,
) {
    MAP_LOAD("map_load"),
    RENDER("render"),
}

internal data class PhoneMapLibreRenderError(
    val providerId: String,
    val source: PhoneOnlineMapSource,
    val role: PhoneMapLibreRendererRole,
    val generation: Long,
    val category: PhoneMapLibreErrorCategory,
)

internal data class PhoneMapLibreActiveSource(
    val source: PhoneOnlineMapSource?,
    val providerId: String?,
)

internal data class PhoneMapLibreRenderErrorState(
    val baseError: PhoneMapLibreRenderError? = null,
    val comparisonError: PhoneMapLibreRenderError? = null,
) {
    fun recordFailureIfCurrent(
        error: PhoneMapLibreRenderError,
        rendererIsCurrent: Boolean,
        activeSource: PhoneMapLibreActiveSource,
    ): PhoneMapLibreRenderErrorState? {
        val existing = if (error.role == PhoneMapLibreRendererRole.BASE) baseError else comparisonError
        val hasCurrentLoadFailure =
            existing?.category == PhoneMapLibreErrorCategory.MAP_LOAD &&
                existing.generation == error.generation &&
                existing.matches(activeSource)
        val hidesLoadFailure = hasCurrentLoadFailure && error.category == PhoneMapLibreErrorCategory.RENDER
        if (
            !rendererIsCurrent ||
            !error.matches(activeSource) ||
            hidesLoadFailure
        ) {
            return null
        }
        return when (error.role) {
            PhoneMapLibreRendererRole.BASE -> copy(baseError = error)
            PhoneMapLibreRendererRole.COMPARISON -> copy(comparisonError = error)
        }.takeUnless { next -> next == this }
    }

    fun clearAfterRecoveryIfCurrent(
        source: PhoneMapLibreRenderErrorSource,
        role: PhoneMapLibreRendererRole,
        rendererIsCurrent: Boolean,
        activeSource: PhoneMapLibreActiveSource,
        recoveryCategory: PhoneMapLibreErrorCategory = PhoneMapLibreErrorCategory.MAP_LOAD,
    ): PhoneMapLibreRenderErrorState? {
        val error =
            when (role) {
                PhoneMapLibreRendererRole.BASE -> baseError
                PhoneMapLibreRendererRole.COMPARISON -> comparisonError
            }
        val sourceIsCurrent = source.source == activeSource.source && source.providerId == activeSource.providerId
        val matchesRecovery =
            error != null &&
                (recoveryCategory == PhoneMapLibreErrorCategory.MAP_LOAD || error.category == recoveryCategory)
        if (!rendererIsCurrent || !sourceIsCurrent || !matchesRecovery) return null
        return clear(role)
    }

    fun clearObsoleteSources(
        baseSource: PhoneMapLibreActiveSource,
        comparisonSource: PhoneMapLibreActiveSource,
    ): PhoneMapLibreRenderErrorState =
        copy(
            baseError = baseError?.takeIf { error -> error.matches(baseSource) },
            comparisonError = comparisonError?.takeIf { error -> error.matches(comparisonSource) },
        )

    fun errorsForSources(
        baseSource: PhoneMapLibreActiveSource,
        comparisonSource: PhoneMapLibreActiveSource,
    ): List<PhoneMapLibreRenderError> =
        listOfNotNull(
            baseError?.takeIf { error -> error.matches(baseSource) },
            comparisonError?.takeIf { error -> error.matches(comparisonSource) },
        )

    fun clear(role: PhoneMapLibreRendererRole): PhoneMapLibreRenderErrorState =
        when (role) {
            PhoneMapLibreRendererRole.BASE -> copy(baseError = null)
            PhoneMapLibreRendererRole.COMPARISON -> copy(comparisonError = null)
        }

    private fun PhoneMapLibreRenderError.matches(
        activeSource: PhoneMapLibreActiveSource,
    ) = source == activeSource.source && providerId == activeSource.providerId
}

internal data class PhoneMapLibreRenderErrorSource(
    val source: PhoneOnlineMapSource,
    val providerId: String,
)

internal fun phoneMapLibreRendererCallbackIsCurrent(
    callbackGeneration: Long,
    currentGeneration: Long,
    callbackRenderer: Any,
    currentRenderer: Any?,
): Boolean =
    callbackGeneration == currentGeneration &&
        callbackRenderer === currentRenderer

internal data class PhoneMapLibreRendererIdentity(
    val role: PhoneMapLibreRendererRole,
    val rendererId: Long,
    val generation: Long,
    val source: PhoneOnlineMapSource,
    val providerId: String,
)

internal enum class PhoneMapLibreRendererStatus { ACTIVE, DISPOSED }

internal enum class PhoneMapLibreResourceFailureKind { TILE, GLYPHS, SPRITE }

internal enum class PhoneMapLibreLifecycleKind(
    val value: String,
) {
    RENDERER_CREATED("renderer_created"),
    STYLE_LOAD_STARTED("style_load_started"),
    STYLE_LOAD_COMPLETED("style_load_completed"),
    RENDER_FRAME_FINISHED("render_frame_finished"),
    FULL_RENDER_FRAME_FINISHED("full_render_frame_finished"),
    FAILURE("failure"),
    RECOVERY("recovery"),
    DISPOSED("renderer_disposed"),
    RESOURCE_FAILURE_SUMMARY("resource_failure_summary"),
}

internal data class PhoneMapLibreResourceFailureCounts(
    val tile: Int = 0,
    val glyphs: Int = 0,
    val sprite: Int = 0,
)

internal data class PhoneMapLibreLifecycleEvent(
    val kind: PhoneMapLibreLifecycleKind,
    val identity: PhoneMapLibreRendererIdentity,
    val category: PhoneMapLibreErrorCategory? = null,
    val fullyRendered: Boolean? = null,
    val resourceFailures: PhoneMapLibreResourceFailureCounts? = null,
) {
    fun toCaptureLine(): String =
        buildString {
            append("event=${kind.value} role=${identity.role.name.lowercase()} ")
            append("provider=${identity.providerId} renderer=${identity.rendererId} ")
            append("generation=${identity.generation}")
            category?.let { append(" category=${it.diagnosticValue}") }
            fullyRendered?.let { append(" fullyRendered=$it") }
            resourceFailures?.let {
                append(" tileErrors=${it.tile} glyphErrors=${it.glyphs} spriteErrors=${it.sprite}")
            }
        }
}

internal data class PhoneMapLibreRendererHealthSnapshot(
    val identity: PhoneMapLibreRendererIdentity,
    val status: PhoneMapLibreRendererStatus,
    val createdAtElapsedMs: Long,
    val updatedAtElapsedMs: Long,
    val styleLoadStartedAtElapsedMs: Long? = null,
    val styleLoadCompletedAtElapsedMs: Long? = null,
    val firstRenderFrameFinishedAtElapsedMs: Long? = null,
    val firstFullRenderFrameFinishedAtElapsedMs: Long? = null,
    val lastFailureCategory: PhoneMapLibreErrorCategory? = null,
    val lastFailureAtElapsedMs: Long? = null,
    val recoveredAtElapsedMs: Long? = null,
    val resourceFailures: PhoneMapLibreResourceFailureCounts = PhoneMapLibreResourceFailureCounts(),
) {
    fun toReportSection(): String =
        buildString {
            appendLine("${identity.role.name.lowercase()} renderer")
            appendLine("Provider: ${identity.providerId}")
            appendLine("Renderer id: ${identity.rendererId}")
            appendLine("Generation: ${identity.generation}")
            appendLine("Status: $status")
            appendLine("Created at (elapsed ms): $createdAtElapsedMs")
            appendLine("Updated at (elapsed ms): $updatedAtElapsedMs")
            appendLine(
                "Style load started at (elapsed ms): " +
                    (styleLoadStartedAtElapsedMs ?: "not started"),
            )
            appendLine(
                "Style load completed at (elapsed ms): " +
                    (styleLoadCompletedAtElapsedMs ?: "not loaded"),
            )
            appendLine(
                "First finished render frame (elapsed ms): " +
                    (firstRenderFrameFinishedAtElapsedMs ?: "not observed"),
            )
            appendLine(
                "First fully rendered frame (elapsed ms): " +
                    (firstFullRenderFrameFinishedAtElapsedMs ?: "not observed"),
            )
            appendLine("Last failure: ${lastFailureCategory?.diagnosticValue ?: "none"}")
            appendLine("Last failure at (elapsed ms): ${lastFailureAtElapsedMs ?: "none"}")
            appendLine(
                "Recovery render frame observed at (elapsed ms): " +
                    (recoveredAtElapsedMs ?: "not recovered"),
            )
            appendLine(
                "Aggregate SDK resource errors: tiles=${resourceFailures.tile} " +
                    "glyphs=${resourceFailures.glyphs} sprites=${resourceFailures.sprite}",
            )
            append("HTTP status: unavailable from the pinned MapLibre listener callbacks")
        }
}

internal data class PhoneMapLibreLifecycleUpdate(
    val events: List<PhoneMapLibreLifecycleEvent>,
    val publishSummary: Boolean,
)

/** Pure state tracker; callbacks for a replaced renderer cannot mutate the current role snapshot. */
internal class PhoneMapLibreRendererHealthTracker(
    private val resourceSummaryIntervalMs: Long = 5_000L,
) {
    private val currentByRole = mutableMapOf<PhoneMapLibreRendererRole, PhoneMapLibreRendererHealthSnapshot>()
    private val knownRenderers = linkedSetOf<PhoneMapLibreRendererIdentity>()
    private val lastResourceSummaryAtMs = mutableMapOf<PhoneMapLibreRendererIdentity, Long>()

    @Synchronized
    fun rendererCreated(
        identity: PhoneMapLibreRendererIdentity,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        if (currentByRole[identity.role]?.identity == identity) return null
        currentByRole[identity.role] =
            PhoneMapLibreRendererHealthSnapshot(
                identity = identity,
                status = PhoneMapLibreRendererStatus.ACTIVE,
                createdAtElapsedMs = nowElapsedMs,
                updatedAtElapsedMs = nowElapsedMs,
            )
        knownRenderers += identity
        while (knownRenderers.size > MAX_TRACKED_RENDERERS) knownRenderers.remove(knownRenderers.first())
        return PhoneMapLibreLifecycleUpdate(
            events = listOf(PhoneMapLibreLifecycleEvent(PhoneMapLibreLifecycleKind.RENDERER_CREATED, identity)),
            publishSummary = true,
        )
    }

    @Synchronized
    fun styleLoadStarted(
        identity: PhoneMapLibreRendererIdentity,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        val current =
            activeSnapshot(identity)?.takeIf { snapshot ->
                snapshot.styleLoadStartedAtElapsedMs == null || snapshot.styleLoadCompletedAtElapsedMs != null
            } ?: return null
        currentByRole[identity.role] =
            current.copy(
                updatedAtElapsedMs = nowElapsedMs,
                styleLoadStartedAtElapsedMs = nowElapsedMs,
                styleLoadCompletedAtElapsedMs = null,
            )
        return update(identity, PhoneMapLibreLifecycleKind.STYLE_LOAD_STARTED)
    }

    @Synchronized
    fun styleLoadCompleted(
        identity: PhoneMapLibreRendererIdentity,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        val current =
            activeSnapshot(identity)?.takeIf { snapshot ->
                snapshot.styleLoadStartedAtElapsedMs != null && snapshot.styleLoadCompletedAtElapsedMs == null
            } ?: return null
        currentByRole[identity.role] =
            current.copy(updatedAtElapsedMs = nowElapsedMs, styleLoadCompletedAtElapsedMs = nowElapsedMs)
        return update(identity, PhoneMapLibreLifecycleKind.STYLE_LOAD_COMPLETED)
    }

    @Synchronized
    fun renderFrameFinished(
        identity: PhoneMapLibreRendererIdentity,
        fullyRendered: Boolean,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        val current = activeSnapshot(identity) ?: return null
        val firstFrame = current.firstRenderFrameFinishedAtElapsedMs == null
        val firstFullFrame = fullyRendered && current.firstFullRenderFrameFinishedAtElapsedMs == null
        val recovered = current.lastFailureCategory != null && current.recoveredAtElapsedMs == null
        return if (!firstFrame && !firstFullFrame && !recovered) {
            null
        } else {
            currentByRole[identity.role] =
                current.copy(
                    updatedAtElapsedMs = nowElapsedMs,
                    firstRenderFrameFinishedAtElapsedMs = current.firstRenderFrameFinishedAtElapsedMs ?: nowElapsedMs,
                    firstFullRenderFrameFinishedAtElapsedMs =
                        current.firstFullRenderFrameFinishedAtElapsedMs ?: nowElapsedMs.takeIf { fullyRendered },
                    recoveredAtElapsedMs = nowElapsedMs.takeIf { recovered } ?: current.recoveredAtElapsedMs,
                )
            val events =
                buildList {
                    if (firstFrame) {
                        add(
                            PhoneMapLibreLifecycleEvent(
                                PhoneMapLibreLifecycleKind.RENDER_FRAME_FINISHED,
                                identity,
                                fullyRendered = fullyRendered,
                            ),
                        )
                    }
                    if (firstFullFrame) {
                        add(PhoneMapLibreLifecycleEvent(PhoneMapLibreLifecycleKind.FULL_RENDER_FRAME_FINISHED, identity))
                    }
                    if (recovered) {
                        add(
                            PhoneMapLibreLifecycleEvent(
                                PhoneMapLibreLifecycleKind.RECOVERY,
                                identity,
                                category = current.lastFailureCategory,
                                fullyRendered = fullyRendered,
                            ),
                        )
                    }
                }
            PhoneMapLibreLifecycleUpdate(events, publishSummary = true)
        }
    }

    @Synchronized
    fun failure(
        identity: PhoneMapLibreRendererIdentity,
        category: PhoneMapLibreErrorCategory,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        val current =
            activeSnapshot(identity)?.takeIf { snapshot ->
                snapshot.lastFailureCategory != category || snapshot.recoveredAtElapsedMs != null
            } ?: return null
        currentByRole[identity.role] =
            current.copy(
                updatedAtElapsedMs = nowElapsedMs,
                lastFailureCategory = category,
                lastFailureAtElapsedMs = nowElapsedMs,
                recoveredAtElapsedMs = null,
            )
        return update(identity, PhoneMapLibreLifecycleKind.FAILURE, category)
    }

    @Synchronized
    fun resourceFailure(
        identity: PhoneMapLibreRendererIdentity,
        kind: PhoneMapLibreResourceFailureKind,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        val current = activeSnapshot(identity) ?: return null
        val failures =
            when (kind) {
                PhoneMapLibreResourceFailureKind.TILE ->
                    current.resourceFailures.copy(tile = current.resourceFailures.tile + 1)
                PhoneMapLibreResourceFailureKind.GLYPHS ->
                    current.resourceFailures.copy(glyphs = current.resourceFailures.glyphs + 1)
                PhoneMapLibreResourceFailureKind.SPRITE ->
                    current.resourceFailures.copy(sprite = current.resourceFailures.sprite + 1)
            }
        val next = current.copy(updatedAtElapsedMs = nowElapsedMs, resourceFailures = failures)
        currentByRole[identity.role] = next
        val lastSummary = lastResourceSummaryAtMs[identity]
        val due = lastSummary == null || nowElapsedMs - lastSummary >= resourceSummaryIntervalMs.coerceAtLeast(1L)
        return if (due) {
            lastResourceSummaryAtMs[identity] = nowElapsedMs
            PhoneMapLibreLifecycleUpdate(
                events =
                    listOf(
                        PhoneMapLibreLifecycleEvent(
                            kind = PhoneMapLibreLifecycleKind.RESOURCE_FAILURE_SUMMARY,
                            identity = identity,
                            resourceFailures = failures,
                        ),
                    ),
                publishSummary = true,
            )
        } else {
            PhoneMapLibreLifecycleUpdate(events = emptyList(), publishSummary = false)
        }
    }

    @Synchronized
    fun rendererDisposed(
        identity: PhoneMapLibreRendererIdentity,
        nowElapsedMs: Long,
    ): PhoneMapLibreLifecycleUpdate? {
        if (identity !in knownRenderers) return null
        val current = currentByRole[identity.role]
        val isCurrent = current?.identity == identity && current.status == PhoneMapLibreRendererStatus.ACTIVE
        if (isCurrent) {
            currentByRole[identity.role] =
                current.copy(
                    status = PhoneMapLibreRendererStatus.DISPOSED,
                    updatedAtElapsedMs = nowElapsedMs,
                )
        }
        knownRenderers.remove(identity)
        return PhoneMapLibreLifecycleUpdate(
            events = listOf(PhoneMapLibreLifecycleEvent(PhoneMapLibreLifecycleKind.DISPOSED, identity)),
            publishSummary = isCurrent,
        )
    }

    @get:Synchronized
    val snapshots: List<PhoneMapLibreRendererHealthSnapshot>
        get() = currentByRole.values.sortedBy { snapshot -> snapshot.identity.role.ordinal }

    private fun activeSnapshot(identity: PhoneMapLibreRendererIdentity): PhoneMapLibreRendererHealthSnapshot? =
        currentByRole[identity.role]?.takeIf { snapshot ->
            snapshot.identity == identity && snapshot.status == PhoneMapLibreRendererStatus.ACTIVE
        }

    private fun update(
        identity: PhoneMapLibreRendererIdentity,
        kind: PhoneMapLibreLifecycleKind,
        category: PhoneMapLibreErrorCategory? = null,
    ) = PhoneMapLibreLifecycleUpdate(
        events = listOf(PhoneMapLibreLifecycleEvent(kind, identity, category)),
        publishSummary = true,
    )

    private companion object {
        const val MAX_TRACKED_RENDERERS = 24
    }
}

internal fun List<PhoneMapLibreRendererHealthSnapshot>.toReportSectionOrNull(): String? =
    takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "\n\n", prefix = "MapLibre renderer health snapshots\n\n") { snapshot ->
            snapshot.toReportSection()
        }

/** Bridges sparse native lifecycle signals into the user's explicit, local diagnostics capture. */
internal object PhoneMapLibreRenderDiagnostics {
    const val TAG = "PhoneMapLibreRenderer"
    const val SECTION_KEY = "maplibre_renderer_health"

    private val rendererIds = AtomicLong()
    private val tracker = PhoneMapLibreRendererHealthTracker()

    fun rendererCreated(
        role: PhoneMapLibreRendererRole,
        generation: Long,
        source: PhoneOnlineMapSource,
        providerId: String,
    ): PhoneMapLibreRendererIdentity {
        val identity =
            PhoneMapLibreRendererIdentity(
                role,
                rendererIds.incrementAndGet(),
                generation,
                source,
                providerId,
            )
        publish(tracker.rendererCreated(identity, SystemClock.elapsedRealtime()))
        return identity
    }

    fun styleLoadStarted(identity: PhoneMapLibreRendererIdentity) {
        publish(tracker.styleLoadStarted(identity, SystemClock.elapsedRealtime()))
    }

    fun styleLoadCompleted(identity: PhoneMapLibreRendererIdentity) {
        publish(tracker.styleLoadCompleted(identity, SystemClock.elapsedRealtime()))
    }

    fun renderFrameFinished(
        identity: PhoneMapLibreRendererIdentity,
        fullyRendered: Boolean,
    ) {
        publish(tracker.renderFrameFinished(identity, fullyRendered, SystemClock.elapsedRealtime()))
    }

    fun failure(
        identity: PhoneMapLibreRendererIdentity,
        category: PhoneMapLibreErrorCategory,
    ) {
        publish(tracker.failure(identity, category, SystemClock.elapsedRealtime()))
    }

    fun resourceFailure(
        identity: PhoneMapLibreRendererIdentity,
        kind: PhoneMapLibreResourceFailureKind,
    ) {
        publish(tracker.resourceFailure(identity, kind, SystemClock.elapsedRealtime()))
    }

    fun rendererDisposed(identity: PhoneMapLibreRendererIdentity) {
        publish(tracker.rendererDisposed(identity, SystemClock.elapsedRealtime()))
    }

    fun publishCaptureSnapshot() {
        tracker.snapshots
            .toReportSectionOrNull()
            ?.let { report -> PhoneDebugCapture.updateSection(SECTION_KEY, report) }
    }

    private fun publish(update: PhoneMapLibreLifecycleUpdate?) {
        update ?: return
        update.events.forEach { event -> PhoneDebugCapture.log(TAG, event.toCaptureLine()) }
        if (update.publishSummary) publishCaptureSnapshot()
    }
}
