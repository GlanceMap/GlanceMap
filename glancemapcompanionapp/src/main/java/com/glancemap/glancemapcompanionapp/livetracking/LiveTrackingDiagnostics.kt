@file:Suppress("CyclomaticComplexMethod", "FunctionNaming")

package com.glancemap.glancemapcompanionapp.livetracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.BuildConfig
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class LiveTrackingDiagnosticOperation(
    val label: String,
) {
    UPLOAD("Upload"),
    REGISTER("Register"),
    CHECK_GROUP("Check group"),
    SMS_SUPPORT("SMS support"),
    CLEANUP("Delete tracks"),
    GPX_DOWNLOAD("GPX download"),
    SAVE_SETTINGS("Save settings"),
    LOCATION_UPDATE("GPS update"),
}

internal enum class LiveTrackingDiagnosticResult {
    SUCCESS,
    HTTP_ERROR,
    SERVER_REJECTED,
    SMS_SUPPORTED,
    SMS_UNSUPPORTED,
    UNEXPECTED_RESPONSE,
    TIMEOUT,
    OFFLINE,
    NETWORK_ERROR,
    FAILED,
}

internal data class LiveTrackingDiagnosticRequest(
    val operation: LiveTrackingDiagnosticOperation,
    val alarmMinutes: Int? = null,
    val notificationEmailCount: Int = 0,
    val alertEmailCount: Int = 0,
    val alertSmsCount: Int = 0,
    val includesRecipientSummary: Boolean = false,
    val start: Boolean = false,
    val stop: Boolean = false,
    val pause: Boolean = false,
    val resume: Boolean = false,
)

internal data class LiveTrackingDiagnosticEvent(
    val timestampEpochMs: Long,
    val request: LiveTrackingDiagnosticRequest,
    val result: LiveTrackingDiagnosticResult,
    val httpCode: Int?,
    val durationMs: Long,
)

internal object LiveTrackingDiagnostics {
    private const val MAX_EVENTS = 100
    private val mutableEvents = MutableStateFlow<List<LiveTrackingDiagnosticEvent>>(emptyList())

    val events = mutableEvents.asStateFlow()

    fun record(
        request: LiveTrackingDiagnosticRequest,
        result: LiveTrackingDiagnosticResult,
        httpCode: Int? = null,
        timestampEpochMs: Long = System.currentTimeMillis(),
        durationMs: Long,
    ) {
        val event =
            LiveTrackingDiagnosticEvent(
                timestampEpochMs = timestampEpochMs,
                request = request,
                result = result,
                httpCode = httpCode,
                durationMs = durationMs.coerceAtLeast(0),
            )
        if (BuildConfig.DEBUG) {
            mutableEvents.update { current -> (current + event).takeLast(MAX_EVENTS) }
        }
        if (PhoneDebugCapture.isActive()) {
            PhoneDebugCapture.log(LIVE_TRACKING_CAPTURE_TAG, event.toDisplayText())
        }
    }

    fun clear() {
        if (BuildConfig.DEBUG) mutableEvents.value = emptyList()
    }
}

@Composable
internal fun LiveTrackingDiagnosticsPanel() {
    if (!BuildConfig.DEBUG) return
    val events by LiveTrackingDiagnostics.events.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    TrackingPanel(title = stringResource(R.string.live_tracking_diagnostics_title)) {
        Text(
            text = stringResource(R.string.live_tracking_diagnostics_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (expanded) {
                            R.string.live_tracking_diagnostics_hide
                        } else {
                            R.string.live_tracking_diagnostics_show
                        },
                        events.size,
                    ),
                )
            }
            OutlinedButton(
                onClick = LiveTrackingDiagnostics::clear,
                enabled = events.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_action_clear))
            }
        }
        if (expanded) {
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.live_tracking_diagnostics_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                events.takeLast(MAX_VISIBLE_EVENTS).asReversed().forEach { event ->
                    Text(
                        text = event.toDisplayText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun LiveTrackingDiagnosticEvent.toDisplayText(): String {
    val request = request
    val flags =
        buildList {
            if (request.start) add("start")
            if (request.stop) add("stop")
            if (request.pause) add("pause")
            if (request.resume) add("resume")
        }
    return buildList {
        add(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampEpochMs)))
        add(request.operation.label)
        add(result.toDisplayText(httpCode))
        add("${durationMs}ms")
        request.alarmMinutes?.let { minutes ->
            add(if (minutes == -1) "alarm off" else "alarm ${minutes}m")
        }
        if (request.includesRecipientSummary) {
            add("notify ${request.notificationEmailCount}")
            add("alerts ${request.alertEmailCount} email/${request.alertSmsCount} SMS")
        }
        if (flags.isNotEmpty()) add(flags.joinToString(","))
    }.joinToString(" · ")
}

private fun LiveTrackingDiagnosticResult.toDisplayText(httpCode: Int?): String =
    when (this) {
        LiveTrackingDiagnosticResult.SUCCESS -> httpCode?.let { "HTTP $it" } ?: "Success"
        LiveTrackingDiagnosticResult.HTTP_ERROR -> httpCode?.let { "HTTP $it" } ?: "HTTP error"
        LiveTrackingDiagnosticResult.SERVER_REJECTED -> "Server rejected"
        LiveTrackingDiagnosticResult.SMS_SUPPORTED -> "Supported"
        LiveTrackingDiagnosticResult.SMS_UNSUPPORTED -> "Unsupported"
        LiveTrackingDiagnosticResult.UNEXPECTED_RESPONSE -> "Unexpected response"
        LiveTrackingDiagnosticResult.TIMEOUT -> "Timeout"
        LiveTrackingDiagnosticResult.OFFLINE -> "Offline"
        LiveTrackingDiagnosticResult.NETWORK_ERROR -> "Network error"
        LiveTrackingDiagnosticResult.FAILED -> "Failed"
    }

private const val MAX_VISIBLE_EVENTS = 30
private const val LIVE_TRACKING_CAPTURE_TAG = "LiveTracking"
