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
    LOCATION_QUALITY("GPS quality"),
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
    QUALITY_SUSPECT,
    QUALITY_REJECTED,
}

internal enum class LiveTrackingFixSource(
    val label: String,
) {
    CACHED_STARTUP("cached_startup"),
    CALLBACK("callback"),
    EXPLICIT_FRESH("explicit_fresh"),
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
    val gpsAccuracyMeters: Float? = null,
    val fixAgeMillis: Long? = null,
    val distanceFromPreviousMeters: Double? = null,
    val impliedSpeedMetersPerSecond: Double? = null,
    val locationQualityResult: String? = null,
    val locationQualityReason: String? = null,
    val gsmSignalPercent: Int? = null,
    val isCatchUp: Boolean = false,
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
    private val fieldTestLock = Any()
    private var fieldTestSession: MutableFieldTestSession? = null

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
        synchronized(fieldTestLock) { fieldTestSession = null }
    }

    fun beginLiveTrackingSession(
        trackingUrl: String,
        updateIntervalSeconds: Int,
        cellularMonitorAvailable: Boolean,
    ) {
        if (!PhoneDebugCapture.isActive()) return
        val captureSessionId = PhoneDebugCapture.state.value.sessionId
        synchronized(fieldTestLock) {
            if (fieldTestSession?.captureSessionId == captureSessionId) return
            fieldTestSession =
                MutableFieldTestSession(
                    captureSessionId = captureSessionId,
                    endpoint = trackingUrl.toFieldTestEndpointName(),
                    updateIntervalSeconds = updateIntervalSeconds,
                    cellularMonitorAvailable = cellularMonitorAvailable,
                )
        }
    }

    fun recordLiveTrackingStartup(
        cachedLocationExists: Boolean,
        cachedLocationAgeMillis: Long?,
        cachedLocationAccepted: Boolean?,
    ) {
        if (!PhoneDebugCapture.isActive()) return
        val line =
            synchronized(fieldTestLock) {
                val session = fieldTestSession ?: return@synchronized null
                if (session.startupSummaryLogged) return@synchronized null
                session.startupSummaryLogged = true
                buildString {
                    append("session_start")
                    append(" endpoint=").append(session.endpoint)
                    append(" intervalSec=").append(session.updateIntervalSeconds)
                    append(" gsmMonitor=").append(session.cellularMonitorAvailable)
                    append(" cached=").append(cachedLocationExists)
                    append(" cacheAgeMs=").append(cachedLocationAgeMillis ?: "na")
                    append(" cacheFreshness=")
                        .append(cachedLocationAccepted?.let { if (it) "accepted" else "rejected" } ?: "na")
                }
            }
        line?.let { PhoneDebugCapture.log(LIVE_TRACKING_CAPTURE_TAG, it) }
    }

    @Suppress("LongParameterList")
    fun recordLiveTrackingFix(
        source: LiveTrackingFixSource,
        decision: LiveTrackingLocationQualityDecision,
        androidSpeedMetersPerSecond: Float?,
        isMockLocation: Boolean?,
        gsmSignalPercent: Int,
        queueSize: Int?,
    ) {
        if (!PhoneDebugCapture.isActive()) return
        val captureSessionId = PhoneDebugCapture.state.value.sessionId
        synchronized(fieldTestLock) {
            val session =
                fieldTestSession
                    ?.takeIf { it.captureSessionId == captureSessionId }
                    ?: MutableFieldTestSession(captureSessionId = captureSessionId)
                        .also { fieldTestSession = it }
            val sequence = ++session.nextFixSequence
            session.recordFix(decision, gsmSignalPercent, queueSize)
            val line =
                buildString {
                    append("fix=").append(sequence)
                    append(" source=").append(source.label)
                    append(" ageMs=").append(decision.fixAgeMillis ?: "na")
                    append(" accM=")
                        .append(decision.accuracyMeters?.let(::formatDiagnosticDecimal) ?: "na")
                    append(" androidSpeedReported=").append(androidSpeedMetersPerSecond != null)
                    append(" androidSpeedMps=")
                        .append(androidSpeedMetersPerSecond?.let(::formatDiagnosticDecimal) ?: "na")
                    append(" deltaMs=").append(decision.timeDeltaFromPreviousAcceptedFixMillis ?: "na")
                    append(" distanceM=")
                        .append(decision.distanceFromPreviousMeters?.let(::formatDiagnosticDecimal) ?: "na")
                    append(" impliedSpeedMps=")
                        .append(decision.impliedSpeedMetersPerSecond?.let(::formatDiagnosticDecimal) ?: "na")
                    append(" decision=").append(decision.result)
                    append(" reason=").append(decision.reason)
                    append(" confirmation=").append(decision.suspectResolution.name.lowercase(Locale.US))
                    append(" suspectWaiting=")
                        .append(decision.suspectResolution == LiveTrackingSuspectResolution.WAITING)
                    append(" gsm=").append(gsmSignalPercent)
                    append(" queue=").append(queueSize ?: "na")
                    append(" mock=").append(isMockLocation ?: "na")
                }
            PhoneDebugCapture.log(LIVE_TRACKING_CAPTURE_TAG, line)
        }
    }

    @Suppress("LongParameterList")
    fun recordLiveTrackingTransmission(
        isCatchUp: Boolean,
        fixTimestampEpochMillis: Long,
        fixAgeMillis: Long?,
        gsmSignalPercent: Int,
        queueSizeBefore: Int?,
        queueSizeAfter: Int?,
        outcome: String,
    ) {
        if (!PhoneDebugCapture.isActive()) return
        val captureSessionId = PhoneDebugCapture.state.value.sessionId
        val line =
            synchronized(fieldTestLock) {
                val session =
                    fieldTestSession
                        ?.takeIf { it.captureSessionId == captureSessionId }
                        ?: MutableFieldTestSession(captureSessionId = captureSessionId)
                            .also { fieldTestSession = it }
                session.observeQueue(queueSizeBefore)
                session.observeQueue(queueSizeAfter)
                buildString {
                    append("tx mode=").append(if (isCatchUp) "catch_up" else "realtime")
                    append(" fixTsMs=").append(fixTimestampEpochMillis)
                    append(" ageMs=").append(fixAgeMillis ?: "na")
                    append(" gsm_signal=").append(gsmSignalPercent)
                    append(" queueBefore=").append(queueSizeBefore ?: "na")
                    append(" queueAfter=").append(queueSizeAfter ?: "na")
                    append(" outcome=").append(outcome)
                }
            }
        PhoneDebugCapture.log(LIVE_TRACKING_CAPTURE_TAG, line)
    }

    fun recordLiveTrackingPositionQueued(queueSize: Int) {
        if (!PhoneDebugCapture.isActive()) return
        synchronized(fieldTestLock) {
            fieldTestSession?.let {
                it.positionsQueued += 1
                it.observeQueue(queueSize)
            }
        }
    }

    fun recordLiveTrackingCatchUpReplay(queueSizeAfter: Int) {
        if (!PhoneDebugCapture.isActive()) return
        synchronized(fieldTestLock) {
            fieldTestSession?.let {
                it.catchUpReplayed += 1
                it.observeQueue(queueSizeAfter)
            }
        }
    }

    fun finishLiveTrackingSession() {
        val line =
            synchronized(fieldTestLock) {
                val session = fieldTestSession ?: return@synchronized null
                fieldTestSession = null
                if (!PhoneDebugCapture.isActive()) return@synchronized null
                session.toSummaryLine()
            }
        line?.let { PhoneDebugCapture.log(LIVE_TRACKING_CAPTURE_TAG, it) }
    }

    fun recordLocationQuality(
        decision: LiveTrackingLocationQualityDecision,
        gsmSignalPercent: Int,
    ) {
        if (decision.result == LiveTrackingLocationQualityResult.ACCEPT || PhoneDebugCapture.isActive()) return
        record(
            request =
                LiveTrackingDiagnosticRequest(
                    operation = LiveTrackingDiagnosticOperation.LOCATION_QUALITY,
                    gpsAccuracyMeters = decision.accuracyMeters,
                    fixAgeMillis = decision.fixAgeMillis,
                    distanceFromPreviousMeters = decision.distanceFromPreviousMeters,
                    impliedSpeedMetersPerSecond = decision.impliedSpeedMetersPerSecond,
                    locationQualityResult = decision.result.name,
                    locationQualityReason = decision.reason,
                    gsmSignalPercent = gsmSignalPercent,
                ),
            result =
                when (decision.result) {
                    LiveTrackingLocationQualityResult.ACCEPT -> LiveTrackingDiagnosticResult.SUCCESS
                    LiveTrackingLocationQualityResult.SUSPECT -> LiveTrackingDiagnosticResult.QUALITY_SUSPECT
                    LiveTrackingLocationQualityResult.REJECT -> LiveTrackingDiagnosticResult.QUALITY_REJECTED
                },
            durationMs = 0,
        )
    }

    private class MutableFieldTestSession(
        val captureSessionId: Long,
        val endpoint: String = "unknown",
        val updateIntervalSeconds: Int = 0,
        val cellularMonitorAvailable: Boolean = false,
    ) {
        var startupSummaryLogged = false
        var nextFixSequence = 0L
        var fixesReceived = 0
        var fixesAccepted = 0
        var fixesSuspect = 0
        var fixesRejected = 0
        var suspectFixesConfirmed = 0
        var staleStartupRejected = 0
        var positionsQueued = 0
        var catchUpReplayed = 0
        var maxQueueDepth = 0
        var gsmUnknown = 0
        private val gsmBuckets =
            linkedMapOf(
                "unknown" to 0,
                "none" to 0,
                "low" to 0,
                "medium" to 0,
                "good" to 0,
                "high" to 0,
            )

        fun recordFix(
            decision: LiveTrackingLocationQualityDecision,
            gsmSignalPercent: Int,
            queueSize: Int?,
        ) {
            fixesReceived += 1
            when (decision.result) {
                LiveTrackingLocationQualityResult.ACCEPT -> fixesAccepted += 1
                LiveTrackingLocationQualityResult.SUSPECT -> fixesSuspect += 1
                LiveTrackingLocationQualityResult.REJECT -> fixesRejected += 1
            }
            if (decision.suspectResolution == LiveTrackingSuspectResolution.CONFIRMED) {
                suspectFixesConfirmed += 1
            }
            if (decision.reason in
                setOf(
                    "stale_startup_cache",
                    "stale_startup_callback",
                    "unknown_startup_cache_age",
                )
            ) {
                staleStartupRejected += 1
            }
            if (gsmSignalPercent < 0) gsmUnknown += 1
            gsmBuckets[gsmSignalPercent.toFieldTestGsmBucket()] =
                gsmBuckets.getValue(gsmSignalPercent.toFieldTestGsmBucket()) + 1
            observeQueue(queueSize)
        }

        fun observeQueue(queueSize: Int?) {
            if (queueSize != null) maxQueueDepth = maxOf(maxQueueDepth, queueSize)
        }

        fun toSummaryLine(): String =
            buildString {
                append("session_end")
                append(" fixesReceived=").append(fixesReceived)
                append(" fixesAccepted=").append(fixesAccepted)
                append(" fixesSuspect=").append(fixesSuspect)
                append(" fixesRejected=").append(fixesRejected)
                append(" suspectConfirmed=").append(suspectFixesConfirmed)
                append(" staleStartupRejected=").append(staleStartupRejected)
                append(" positionsQueued=").append(positionsQueued)
                append(" catchUpReplayed=").append(catchUpReplayed)
                append(" maxQueueDepth=").append(maxQueueDepth)
                append(" gsmUnknown=").append(gsmUnknown)
                append(" gsmBuckets=")
                append(gsmBuckets.entries.joinToString("|") { (bucket, count) -> "$bucket:$count" })
            }
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
        request.gpsAccuracyMeters?.let { add("acc ${formatDiagnosticDecimal(it)}m") }
        request.fixAgeMillis?.let { add("age ${it}ms") }
        request.distanceFromPreviousMeters?.let { add("from previous ${formatDiagnosticDecimal(it)}m") }
        request.impliedSpeedMetersPerSecond?.let { add("implied ${formatDiagnosticDecimal(it)}m/s") }
        request.locationQualityResult?.let { result ->
            add("quality ${result.lowercase()}${request.locationQualityReason?.let { ":$it" }.orEmpty()}")
        }
        request.gsmSignalPercent?.let { add(if (it < 0) "gsm unknown" else "gsm $it%") }
        if (request.operation == LiveTrackingDiagnosticOperation.LOCATION_UPDATE) {
            add(if (request.isCatchUp) "catch-up" else "real-time")
        }
        if (flags.isNotEmpty()) add(flags.joinToString(","))
    }.joinToString(" · ")
}

private fun formatDiagnosticDecimal(value: Number): String = String.format(Locale.US, "%.1f", value.toDouble())

private fun String.toFieldTestEndpointName(): String =
    when {
        trim() == ArkluzTrackingEndpoint.DEVELOPMENT.url -> "development"
        else -> "production"
    }

private fun Int.toFieldTestGsmBucket(): String =
    when {
        this < 0 -> "unknown"
        this == 0 -> "none"
        this <= 25 -> "low"
        this <= 50 -> "medium"
        this <= 75 -> "good"
        else -> "high"
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
        LiveTrackingDiagnosticResult.QUALITY_SUSPECT -> "Suspect GPS fix"
        LiveTrackingDiagnosticResult.QUALITY_REJECTED -> "Rejected GPS fix"
    }

private const val MAX_VISIBLE_EVENTS = 30
private const val LIVE_TRACKING_CAPTURE_TAG = "LiveTracking"
