package com.glancemap.glancemapwearos.presentation.features.navigate

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry

internal class NavigationPopupTelemetry(
    private val popup: String,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val captureEnabled: () -> Boolean = DebugTelemetry::isFullDiagnosticsCaptureEnabled,
    private val log: (String) -> Unit = { DebugTelemetry.log("NavigationTelemetry", it) },
) {
    private var shownAtMs: Long? = null

    fun update(
        visible: Boolean,
        reason: String,
    ) {
        if (!captureEnabled()) {
            shownAtMs = null
            return
        }
        val startedAt = shownAtMs
        if (visible && startedAt == null) {
            shownAtMs = nowElapsedMs()
            log("event=navigation_popup popup=$popup action=show reason=$reason")
        } else if (!visible && startedAt != null) {
            shownAtMs = null
            val durationMs = (nowElapsedMs() - startedAt).coerceAtLeast(0L)
            log("event=navigation_popup popup=$popup action=hide reason=$reason durationMs=$durationMs")
        }
    }
}

@Composable
internal fun navigationPopupTelemetryEffect(
    popup: String,
    visible: Boolean,
    reason: String,
) {
    val telemetry = remember(popup) { NavigationPopupTelemetry(popup) }
    SideEffect { telemetry.update(visible, reason) }
    DisposableEffect(telemetry) {
        onDispose { telemetry.update(visible = false, reason = "overlay_disposed") }
    }
}
