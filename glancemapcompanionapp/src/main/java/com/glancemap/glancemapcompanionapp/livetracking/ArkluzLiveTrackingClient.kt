@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import com.glancemap.glancemapcompanionapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class LiveTrackingSettings(
    val trackingUrl: String = ArkluzTrackingEndpoint.defaultUrl,
    val updateIntervalSeconds: Int = 60,
    val group: String,
    val participantPassword: String,
    val followerPassword: String,
    val userName: String,
    val notificationEmails: String,
    val alertEmails: String,
    val stuckAlarmMinutes: String,
    val comments: String,
    val gpxUri: Uri?,
    val gpxName: String,
)

internal data class ArkluzLocationUpdate(
    val trackingUrl: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float,
    val epochMilliseconds: Long,
    val batteryPercent: Int,
    val gsmSignalPercent: Int,
    val group: String,
    val participantPassword: String,
    val userName: String,
    val notificationEmails: String,
    val alertEmails: String,
    val stuckAlarmMinutes: String,
    val start: Boolean,
    val stop: Boolean,
    val pause: Boolean = false,
    val resume: Boolean = false,
    val dateId: String? = null,
    val locationDiagnostics: LiveTrackingLocationDiagnostics? = null,
    val isCatchUp: Boolean = false,
) {
    fun asStoredGpsPoint(): ArkluzLocationUpdate =
        copy(
            start = false,
            stop = false,
            pause = false,
            resume = false,
            dateId = null,
            isCatchUp = false,
        )

    fun asCatchUpPoint(): ArkluzLocationUpdate =
        asStoredGpsPoint().copy(
            gsmSignalPercent = 0,
            isCatchUp = true,
        )
}

internal data class ArkluzRecordedGpxDownload(
    val cacheFile: File,
    val suggestedFileName: String,
) {
    fun delete() {
        cacheFile.delete()
    }
}

internal enum class ArkluzSmsSupport {
    SUPPORTED,
    UNSUPPORTED,
}

enum class ArkluzTrackingEndpoint(
    val label: String,
    val url: String,
) {
    DEVELOPMENT("Development", "https://arkluz.com/dev/trk"),
    PRODUCTION("Production", "https://arkluz.com/trk"),
    ;

    companion object {
        val defaultUrl: String = BuildConfig.ARKLUZ_TRACKING_URL.ifBlank { PRODUCTION.url }

        fun fromPersistedName(name: String?) = entries.firstOrNull { it.name == name } ?: PRODUCTION

        internal fun resolveUrl(
            activeSessionUrl: String?,
            selectedEndpoint: ArkluzTrackingEndpoint,
        ): String = activeSessionUrl?.trim()?.takeIf(String::isNotBlank) ?: selectedEndpoint.url
    }
}

internal class ArkluzLiveTrackingClient(
    private val context: Context,
    private val gsmSignalPercentProvider: () -> Int = { UNKNOWN_GSM_SIGNAL_PERCENT },
) {
    private val appContext = context.applicationContext
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()

    suspend fun uploadPlannedRoute(settings: LiveTrackingSettings): ArkluzServerResult {
        if (settings.gpxUri == null && settings.comments.isBlank()) {
            return ArkluzServerResult("Nothing to send")
        }
        return withContext(Dispatchers.IO) {
            val tempFile =
                settings.gpxUri?.let { gpxUri ->
                    copyToTempFile(gpxUri, settings.gpxName.ifBlank { "planned-route.gpx" })
                }
            try {
                val builder =
                    MultipartBody
                        .Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("q", "upload")
                        .addFormDataPart("api", "1")
                        .addFormDataPart("group", settings.group.trim())
                        .addFormDataPart("pass", settings.participantPassword.trim())
                if (tempFile != null) {
                    builder.addFormDataPart(
                        "upload",
                        tempFile.name,
                        tempFile.asRequestBody("application/gpx+xml".toMediaType()),
                    )
                }
                settings.comments.trim().takeIf { it.isNotBlank() }?.let { comments ->
                    builder.addFormDataPart("comments", comments)
                }
                val body = builder.build()

                execute(
                    Request
                        .Builder()
                        .url(settings.trackingUrl.trim().ifBlank { ArkluzTrackingEndpoint.defaultUrl })
                        .post(body)
                        .build(),
                    diagnosticRequest =
                        LiveTrackingDiagnosticRequest(
                            operation = LiveTrackingDiagnosticOperation.UPLOAD,
                        ),
                    responseParser = String::toArkluzUploadResult,
                )
            } finally {
                tempFile?.delete()
            }
        }
    }

    suspend fun registerGroup(settings: LiveTrackingSettings): ArkluzServerResult =
        withContext(Dispatchers.IO) {
            val url =
                settings.trackingUrl
                    .trim()
                    .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", "register")
                    .addQueryParameter("group", settings.group.trim())
                    .addQueryParameter("pass", settings.participantPassword.trim())
                    .addEncodedQueryParameter("api", null)
                    .build()

            execute(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
                diagnosticRequest =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.REGISTER,
                    ),
            )
        }

    suspend fun checkGroup(settings: LiveTrackingSettings): ArkluzServerResult =
        withContext(Dispatchers.IO) {
            val url =
                settings.trackingUrl
                    .trim()
                    .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", "check")
                    .addQueryParameter("group", settings.group.trim())
                    .addQueryParameter("pass", settings.participantPassword.trim())
                    .build()

            execute(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
                diagnosticRequest =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.CHECK_GROUP,
                    ),
            )
        }

    suspend fun checkSmsSupport(
        trackingUrl: String,
        phoneNumber: String,
    ): ArkluzSmsSupport =
        withContext(Dispatchers.IO) {
            val url =
                buildArkluzSmsSupportUrl(
                    trackingUrl = trackingUrl,
                    phoneNumber = phoneNumber,
                    apiKey = BuildConfig.ARKLUZ_SMS_API_KEY,
                )
            executeSmsSupport(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
                diagnosticRequest =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.SMS_SUPPORT,
                    ),
            )
        }

    suspend fun deleteRecordedTracks(settings: LiveTrackingSettings): ArkluzServerResult =
        withContext(Dispatchers.IO) {
            val url =
                settings.trackingUrl
                    .trim()
                    .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", "cleanup")
                    .addQueryParameter("group", settings.group.trim())
                    .addQueryParameter("pass", settings.participantPassword.trim())
                    .build()

            execute(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
                diagnosticRequest =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.CLEANUP,
                    ),
            )
        }

    suspend fun downloadRecordedGpx(
        settings: LiveTrackingSettings,
        userOnly: Boolean,
        fallbackFileName: String,
    ): ArkluzRecordedGpxDownload =
        withContext(Dispatchers.IO) {
            val urlBuilder =
                settings.trackingUrl
                    .trim()
                    .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", "gpx")
                    .addQueryParameter("day", todayForArkluz())
                    .addQueryParameter("group", settings.group.trim())
                    .addQueryParameter("p", settings.followerPassword.trim())

            if (userOnly) {
                urlBuilder.addQueryParameter("user", settings.userName.trim())
            }

            executeGpxDownload(
                request =
                    Request
                        .Builder()
                        .url(urlBuilder.build())
                        .get()
                        .build(),
                fallbackFileName = fallbackFileName,
                diagnosticRequest =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.GPX_DOWNLOAD,
                    ),
            )
        }

    suspend fun saveRecordedGpx(
        download: ArkluzRecordedGpxDownload,
        outputUri: Uri,
    ): ArkluzServerResult =
        withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.openOutputStream(outputUri, "wt").use { output ->
                    requireNotNull(output) { "Unable to open destination file" }
                    download.cacheFile.inputStream().use { input -> input.copyTo(output) }
                }
                ArkluzServerResult("Recorded GPX downloaded")
            } finally {
                download.delete()
            }
        }

    suspend fun saveSettings(settings: LiveTrackingSettings): ArkluzServerResult =
        withContext(Dispatchers.IO) {
            val urlBuilder =
                settings.trackingUrl
                    .trim()
                    .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", "check")
                    .addQueryParameter("group", settings.group.trim())
                    .addQueryParameter("pass", settings.participantPassword.trim())

            settings.userName.trim().takeIf { it.isNotBlank() }?.let { user ->
                urlBuilder.addQueryParameter("user", user)
            }
            settings.notificationEmails.trim().takeIf { it.isNotBlank() }?.let { emails ->
                urlBuilder.addQueryParameter("email", emails)
            }
            settings.alertEmails.trim().takeIf { it.isNotBlank() }?.let { emails ->
                urlBuilder.addQueryParameter("alert", emails)
            }
            settings.stuckAlarmMinutes.trim().takeIf { it.isNotBlank() }?.let { alarm ->
                urlBuilder.addQueryParameter("alarm", alarm)
            }

            execute(
                Request
                    .Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build(),
                diagnosticRequest = settings.toDiagnosticRequest(LiveTrackingDiagnosticOperation.SAVE_SETTINGS),
            )
        }

    suspend fun sendLocation(
        settings: LiveTrackingSettings,
        location: Location,
        start: Boolean,
        stop: Boolean,
    ): ArkluzServerResult = sendLocationUpdate(buildLocationUpdate(settings, location, start, stop))

    @Suppress("LongParameterList")
    fun buildLocationUpdate(
        settings: LiveTrackingSettings,
        location: Location,
        start: Boolean,
        stop: Boolean,
        pause: Boolean = false,
        resume: Boolean = false,
        dateId: String? = null,
        locationDiagnostics: LiveTrackingLocationDiagnostics? = null,
    ): ArkluzLocationUpdate =
        ArkluzLocationUpdate(
            trackingUrl = settings.trackingUrl.trim().ifBlank { ArkluzTrackingEndpoint.defaultUrl },
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            accuracyMeters = location.accuracy,
            epochMilliseconds = location.time,
            batteryPercent = batteryPercent(),
            gsmSignalPercent = gsmSignalPercent(),
            group = settings.group.trim(),
            participantPassword = settings.participantPassword.trim(),
            userName = settings.userName.trim(),
            notificationEmails = settings.notificationEmails.trim(),
            alertEmails = settings.alertEmails.trim(),
            stuckAlarmMinutes = settings.stuckAlarmMinutes.trim(),
            start = start,
            stop = stop,
            pause = pause,
            resume = resume,
            dateId = dateId,
            locationDiagnostics = locationDiagnostics,
        )

    suspend fun sendLocationUpdate(update: ArkluzLocationUpdate): ArkluzServerResult =
        withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url(buildArkluzLocationUrl(update))
                    .get()
                    .build(),
                diagnosticRequest = update.toDiagnosticRequest(),
            )
        }

    private fun execute(
        request: Request,
        diagnosticRequest: LiveTrackingDiagnosticRequest,
        responseParser: (String) -> ArkluzServerResult = String::toArkluzServerResult,
    ): ArkluzServerResult =
        withDiagnostics(diagnosticRequest) { recordOutcome ->
            httpClient.newCall(request).execute().use { response ->
                val serverMessage =
                    response.body
                        .string()
                        .toReadableServerMessage()
                if (!response.isSuccessful) {
                    recordOutcome(LiveTrackingDiagnosticResult.HTTP_ERROR, response.code)
                    throw ArkluzHttpException(response.code, response.toShortHttpErrorMessage())
                }
                if (serverMessage.isArkluzError()) {
                    recordOutcome(LiveTrackingDiagnosticResult.SERVER_REJECTED, response.code)
                    error(serverMessage)
                }
                val serverResult =
                    runCatching { responseParser(serverMessage) }
                        .getOrElse { error ->
                            recordOutcome(LiveTrackingDiagnosticResult.SERVER_REJECTED, response.code)
                            throw error
                        }
                recordOutcome(LiveTrackingDiagnosticResult.SUCCESS, response.code)
                serverResult
            }
        }

    private fun executeSmsSupport(
        request: Request,
        diagnosticRequest: LiveTrackingDiagnosticRequest,
    ): ArkluzSmsSupport =
        withDiagnostics(diagnosticRequest) { recordOutcome ->
            httpClient.newCall(request).execute().use { response ->
                val serverMessage = response.body.string().trim()
                if (!response.isSuccessful) {
                    recordOutcome(LiveTrackingDiagnosticResult.HTTP_ERROR, response.code)
                    throw ArkluzHttpException(response.code, response.toShortHttpErrorMessage())
                }
                val support =
                    runCatching { serverMessage.toArkluzSmsSupport() }
                        .getOrElse { error ->
                            recordOutcome(LiveTrackingDiagnosticResult.UNEXPECTED_RESPONSE, response.code)
                            throw error
                        }
                recordOutcome(
                    when (support) {
                        ArkluzSmsSupport.SUPPORTED -> LiveTrackingDiagnosticResult.SMS_SUPPORTED
                        ArkluzSmsSupport.UNSUPPORTED -> LiveTrackingDiagnosticResult.SMS_UNSUPPORTED
                    },
                    response.code,
                )
                support
            }
        }

    @Suppress("NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")
    private fun executeGpxDownload(
        request: Request,
        fallbackFileName: String,
        diagnosticRequest: LiveTrackingDiagnosticRequest,
    ): ArkluzRecordedGpxDownload =
        withDiagnostics(diagnosticRequest) { recordOutcome ->
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                val contentType = body.contentType()?.toString().orEmpty()
                if (!response.isSuccessful) {
                    recordOutcome(LiveTrackingDiagnosticResult.HTTP_ERROR, response.code)
                    throw ArkluzHttpException(response.code, response.toShortHttpErrorMessage())
                }
                if (!contentType.contains("gpx", ignoreCase = true)) {
                    val serverMessage = body.string().toReadableServerMessage()
                    recordOutcome(
                        if (serverMessage.isArkluzError()) {
                            LiveTrackingDiagnosticResult.SERVER_REJECTED
                        } else {
                            LiveTrackingDiagnosticResult.UNEXPECTED_RESPONSE
                        },
                        response.code,
                    )
                    if (serverMessage.isArkluzError()) {
                        throw IllegalStateException(serverMessage)
                    }
                    throw IllegalStateException(serverMessage.ifBlank { "Server did not return a GPX file" })
                }

                val suggestedFileName =
                    contentDispositionFileName(response.header("Content-Disposition"))
                        ?: fallbackFileName
                val cacheFile = File.createTempFile("arkluz-recorded-", ".gpx", appContext.cacheDir)
                try {
                    cacheFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                } catch (error: Throwable) {
                    cacheFile.delete()
                    throw error
                }
                recordOutcome(LiveTrackingDiagnosticResult.SUCCESS, response.code)
                ArkluzRecordedGpxDownload(
                    cacheFile = cacheFile,
                    suggestedFileName = suggestedFileName,
                )
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> withDiagnostics(
        diagnosticRequest: LiveTrackingDiagnosticRequest,
        block: (((LiveTrackingDiagnosticResult, Int?) -> Unit) -> T),
    ): T {
        val startedAtEpochMs = System.currentTimeMillis()
        val startedAtNanos = System.nanoTime()
        var outcomeRecorded = false
        val recordOutcome = { result: LiveTrackingDiagnosticResult, httpCode: Int? ->
            if (!outcomeRecorded) {
                outcomeRecorded = true
                LiveTrackingDiagnostics.record(
                    request = diagnosticRequest,
                    result = result,
                    httpCode = httpCode,
                    timestampEpochMs = startedAtEpochMs,
                    durationMs = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND,
                )
            }
        }
        return try {
            block(recordOutcome)
        } catch (error: Throwable) {
            if (!outcomeRecorded) {
                val (result, httpCode) = error.toDiagnosticResult()
                recordOutcome(result, httpCode)
            }
            throw error
        }
    }

    private fun copyToTempFile(
        uri: Uri,
        displayName: String,
    ): File {
        val safeName =
            displayName
                .replace("\\", "_")
                .replace("/", "_")
                .ifBlank { "planned-route.gpx" }
        val tempFile = File.createTempFile("arkluz-", "-$safeName", appContext.cacheDir)
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open GPX file" }
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    private fun batteryPercent(): Int {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun gsmSignalPercent(): Int =
        runCatching { gsmSignalPercentProvider() }
            .getOrDefault(UNKNOWN_GSM_SIGNAL_PERCENT)
            .let { value ->
                if (value < 0) UNKNOWN_GSM_SIGNAL_PERCENT else value.coerceIn(0, 100)
            }

    private companion object {
        const val UNKNOWN_GSM_SIGNAL_PERCENT = -1
    }
}

private fun LiveTrackingSettings.toDiagnosticRequest(
    operation: LiveTrackingDiagnosticOperation,
): LiveTrackingDiagnosticRequest =
    diagnosticRequestWithRecipients(
        operation = operation,
        notificationEmails = notificationEmails,
        alertRecipients = alertEmails,
        alarmMinutes = stuckAlarmMinutes,
    )

private fun ArkluzLocationUpdate.toDiagnosticRequest(): LiveTrackingDiagnosticRequest =
    diagnosticRequestWithRecipients(
        operation = LiveTrackingDiagnosticOperation.LOCATION_UPDATE,
        notificationEmails = notificationEmails,
        alertRecipients = alertEmails,
        alarmMinutes = stuckAlarmMinutes,
        start = start,
        stop = stop,
        pause = pause,
        resume = resume,
        gpsAccuracyMeters = accuracyMeters.takeIf { it.isFinite() && it >= 0f },
        fixAgeMillis = locationDiagnostics?.fixAgeMillis,
        distanceFromPreviousMeters = locationDiagnostics?.distanceFromPreviousMeters,
        impliedSpeedMetersPerSecond = locationDiagnostics?.impliedSpeedMetersPerSecond,
        speedAccuracyMetersPerSecond = locationDiagnostics?.speedAccuracyMetersPerSecond,
        effectiveJumpThresholdMeters = locationDiagnostics?.effectiveJumpThresholdMeters,
        poorAccuracy = locationDiagnostics?.poorAccuracy,
        speedEvidence = locationDiagnostics?.speedEvidence,
        locationQualityResult = locationDiagnostics?.qualityResult?.name,
        locationQualityReason = locationDiagnostics?.qualityReason,
        gsmSignalPercent = gsmSignalPercent,
        isCatchUp = isCatchUp,
    )

@Suppress("LongParameterList")
private fun diagnosticRequestWithRecipients(
    operation: LiveTrackingDiagnosticOperation,
    notificationEmails: String,
    alertRecipients: String,
    alarmMinutes: String,
    start: Boolean = false,
    stop: Boolean = false,
    pause: Boolean = false,
    resume: Boolean = false,
    gpsAccuracyMeters: Float? = null,
    fixAgeMillis: Long? = null,
    distanceFromPreviousMeters: Double? = null,
    impliedSpeedMetersPerSecond: Double? = null,
    speedAccuracyMetersPerSecond: Float? = null,
    effectiveJumpThresholdMeters: Double? = null,
    poorAccuracy: Boolean? = null,
    speedEvidence: LiveTrackingSpeedEvidence? = null,
    locationQualityResult: String? = null,
    locationQualityReason: String? = null,
    gsmSignalPercent: Int? = null,
    isCatchUp: Boolean = false,
): LiveTrackingDiagnosticRequest {
    val alertRecipientValues = recipientValues(alertRecipients)
    return LiveTrackingDiagnosticRequest(
        operation = operation,
        alarmMinutes = alarmMinutes.trim().toIntOrNull(),
        notificationEmailCount = recipientValues(notificationEmails).size,
        alertEmailCount = alertRecipientValues.count { !it.startsWith("+") },
        alertSmsCount = alertRecipientValues.count { it.startsWith("+") },
        includesRecipientSummary = true,
        start = start,
        stop = stop,
        pause = pause,
        resume = resume,
        gpsAccuracyMeters = gpsAccuracyMeters,
        fixAgeMillis = fixAgeMillis,
        distanceFromPreviousMeters = distanceFromPreviousMeters,
        impliedSpeedMetersPerSecond = impliedSpeedMetersPerSecond,
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
        effectiveJumpThresholdMeters = effectiveJumpThresholdMeters,
        poorAccuracy = poorAccuracy,
        speedEvidence = speedEvidence,
        locationQualityResult = locationQualityResult,
        locationQualityReason = locationQualityReason,
        gsmSignalPercent = gsmSignalPercent,
        isCatchUp = isCatchUp,
    )
}

private fun recipientValues(raw: String): List<String> =
    raw
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)

private fun Throwable.toDiagnosticResult(): Pair<LiveTrackingDiagnosticResult, Int?> =
    when (this) {
        is SocketTimeoutException -> LiveTrackingDiagnosticResult.TIMEOUT to null
        is UnknownHostException -> LiveTrackingDiagnosticResult.OFFLINE to null
        is ArkluzHttpException -> LiveTrackingDiagnosticResult.HTTP_ERROR to code
        is IOException -> LiveTrackingDiagnosticResult.NETWORK_ERROR to null
        else -> LiveTrackingDiagnosticResult.FAILED to null
    }

private fun todayForArkluz(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

private const val NANOS_PER_MILLISECOND = 1_000_000L

internal fun buildArkluzLocationUrl(update: ArkluzLocationUpdate): HttpUrl {
    val urlBuilder =
        update.trackingUrl
            .trim()
            .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lat", update.latitude.toString())
            .addQueryParameter("lon", update.longitude.toString())
            .addQueryParameter("acc", update.accuracyMeters.toString())
            .addQueryParameter("time", update.epochMilliseconds.toString())
            .addQueryParameter("battery", update.batteryPercent.toString())
            .addQueryParameter("gsm_signal", update.gsmSignalPercent.toString())
            .addQueryParameter("group", update.group)
            .addQueryParameter("pass", update.participantPassword)
            .addQueryParameter("user", update.userName)

    update.altitudeMeters?.let { urlBuilder.addQueryParameter("alt", formatArkluzAltitudeMeters(it)) }
    update.speedMetersPerSecond?.let { urlBuilder.addQueryParameter("speed", it.toString()) }
    update.notificationEmails.takeIf(String::isNotBlank)?.let { urlBuilder.addQueryParameter("email", it) }
    update.alertEmails.takeIf(String::isNotBlank)?.let { urlBuilder.addQueryParameter("alert", it) }
    update.stuckAlarmMinutes.takeIf(String::isNotBlank)?.let { urlBuilder.addQueryParameter("alarm", it) }
    if (update.start) urlBuilder.addEncodedQueryParameter("start", null)
    if (update.stop) urlBuilder.addEncodedQueryParameter("stop", null)
    if (update.pause) urlBuilder.addEncodedQueryParameter("pause", null)
    if (update.resume) urlBuilder.addEncodedQueryParameter("resume", null)
    update.dateId?.takeIf(String::isNotBlank)?.let { urlBuilder.addQueryParameter("date_id", it) }
    return urlBuilder.build()
}

internal fun formatArkluzAltitudeMeters(
    altitudeMeters: Double,
): String = String.format(Locale.US, "%.1f", altitudeMeters)

internal fun buildArkluzSmsSupportUrl(
    trackingUrl: String,
    phoneNumber: String,
    apiKey: String,
): HttpUrl {
    val baseUrl =
        trackingUrl
            .trim()
            .ifBlank { ArkluzTrackingEndpoint.defaultUrl }
            .toHttpUrl()
    val urlBuilder =
        baseUrl
            .newBuilder()
            .addQueryParameter("q", "sms")
            .addQueryParameter("sms", phoneNumber)
    val isTrustedArkluzUrl =
        baseUrl.isHttps && baseUrl.host.equals(ARKLUZ_API_HOST, ignoreCase = true)

    if (apiKey.isNotBlank() && isTrustedArkluzUrl) {
        urlBuilder.addQueryParameter("key", apiKey)
    }
    return urlBuilder.build()
}

private const val ARKLUZ_API_HOST = "arkluz.com"

internal class ArkluzHttpException(
    val code: Int,
    message: String,
) : IOException(message)

internal fun Throwable.isRetryableArkluzFailure(): Boolean =
    this is IOException &&
        (this !is ArkluzHttpException || code >= 500)

internal fun Throwable.toArkluzFailureDetail(): String =
    when (this) {
        is UnknownHostException -> "Arkluz is temporarily unreachable. Check your internet connection and try again."
        is SocketTimeoutException -> "Arkluz did not respond in time. Try again."
        is IOException ->
            if (this is ArkluzHttpException) {
                if (code == 401) {
                    "GlanceMap could not authenticate with Arkluz. " +
                        "Update the app, or contact support if it is already up to date."
                } else {
                    message?.takeIf { it.isNotBlank() } ?: "Server error"
                }
            } else {
                "Network connection to Arkluz was interrupted. Try again."
            }
        else -> message?.takeIf { it.isNotBlank() } ?: "unknown error"
    }

private fun okhttp3.Response.toShortHttpErrorMessage(): String = "HTTP $code at ${arkluzUtcTimestamp()}"

private fun arkluzUtcTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

data class ArkluzServerResult(
    val message: String,
    val responseValue: String? = null,
    val groupAvailable: Boolean = false,
) {
    val viewerPassword: String?
        get() = responseValue

    val dateId: String?
        get() = responseValue
}

private fun String.toReadableServerMessage(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>|</div>|</li>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n")
        .replace(Regex("\\s*Go back\\.?\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()

private fun String.isArkluzError(): Boolean {
    val normalized = lowercase()
    val singleLine = normalized.replace(Regex("\\s+"), " ")
    return normalized.startsWith("error:") ||
        "incorrect password" in normalized ||
        "please specify a group and a password" in singleLine
}

private fun String.toUserFacingServerMessage(): String =
    if (isBlank() || startsWith("OK", ignoreCase = true)) {
        "Server accepted request"
    } else {
        this
    }

private fun String.toArkluzServerResult(): ArkluzServerResult {
    val lines = lines().map { it.trim() }.filter { it.isNotBlank() }
    val responseValue =
        lines
            .drop(1)
            .firstOrNull()
            ?.takeIf { lines.firstOrNull()?.equals("OK", ignoreCase = true) == true }
    return ArkluzServerResult(
        message = toUserFacingServerMessage(),
        responseValue = responseValue,
        groupAvailable = equals("group available", ignoreCase = true),
    )
}

internal fun String.toArkluzUploadResult(): ArkluzServerResult {
    val response = trim()
    check(response.equals("OK", ignoreCase = true)) {
        response.ifBlank { "Arkluz did not confirm the upload." }
    }
    return ArkluzServerResult(message = "Comment sent")
}

internal fun String.toArkluzSmsSupport(): ArkluzSmsSupport =
    when {
        trim().equals("OK", ignoreCase = true) -> ArkluzSmsSupport.SUPPORTED
        trim().equals("forbidden", ignoreCase = true) -> ArkluzSmsSupport.UNSUPPORTED
        else -> throw IOException("Unexpected SMS support response from Arkluz")
    }
