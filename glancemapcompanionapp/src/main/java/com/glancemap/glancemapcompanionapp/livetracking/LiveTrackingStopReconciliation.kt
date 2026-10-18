package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal interface LiveTrackingStopPendingStore {
    fun save(
        requestId: String,
        updates: List<ArkluzLocationUpdate>,
    ): Boolean

    fun load(requestId: String): List<ArkluzLocationUpdate>

    fun removeFirst(requestId: String): Boolean

    fun requestIds(): Set<String>
}

internal sealed interface LiveTrackingStopReconciliationResult {
    data object Complete : LiveTrackingStopReconciliationResult

    data class Failed(
        val error: Throwable,
    ) : LiveTrackingStopReconciliationResult
}

/** Sends persisted controls in order, deleting each only after Arkluz accepts it. */
internal class LiveTrackingStopReconciler(
    private val store: LiveTrackingStopPendingStore,
    private val send: suspend (ArkluzLocationUpdate) -> Unit,
) {
    fun persist(
        requestId: String,
        updates: List<ArkluzLocationUpdate>,
    ): Boolean = store.save(requestId, updates)

    suspend fun reconcile(requestId: String): LiveTrackingStopReconciliationResult =
        try {
            sendPendingUpdates(requestId)
            LiveTrackingStopReconciliationResult.Complete
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            LiveTrackingStopReconciliationResult.Failed(error)
        } catch (error: IllegalArgumentException) {
            LiveTrackingStopReconciliationResult.Failed(error)
        } catch (error: IllegalStateException) {
            LiveTrackingStopReconciliationResult.Failed(error)
        }

    private suspend fun sendPendingUpdates(requestId: String) {
        while (true) {
            val update = store.load(requestId).firstOrNull() ?: return
            send(update)
            if (!store.removeFirst(requestId)) {
                throw IOException("Unable to clear a reconciled live-tracking update.")
            }
        }
    }
}

/** SharedPreferences holds only outstanding requests; completed stops leave no periodic work behind. */
internal class SharedPreferencesLiveTrackingStopPendingStore(
    context: Context,
) : LiveTrackingStopPendingStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun save(
        requestId: String,
        updates: List<ArkluzLocationUpdate>,
    ): Boolean =
        synchronized(lock) {
            val json = JSONArray()
            updates.forEach { json.put(it.toJson()) }
            preferences.edit().putString(keyFor(requestId), json.toString()).commit()
        }

    override fun load(requestId: String): List<ArkluzLocationUpdate> =
        synchronized(lock) {
            val raw = preferences.getString(keyFor(requestId), null) ?: return@synchronized emptyList()
            val json = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptyList()
            buildList {
                for (index in 0 until json.length()) {
                    json.optJSONObject(index)?.toLocationUpdateOrNull()?.let(::add)
                }
            }
        }

    override fun removeFirst(requestId: String): Boolean =
        synchronized(lock) {
            val remaining = load(requestId).drop(1)
            if (remaining.isEmpty()) {
                preferences.edit().remove(keyFor(requestId)).commit()
            } else {
                val json = JSONArray()
                remaining.forEach { json.put(it.toJson()) }
                preferences.edit().putString(keyFor(requestId), json.toString()).commit()
            }
        }

    override fun requestIds(): Set<String> =
        synchronized(lock) {
            preferences.all.keys
                .filter { it.startsWith(REQUEST_PREFIX) }
                .mapTo(mutableSetOf()) { it.removePrefix(REQUEST_PREFIX) }
        }

    private fun keyFor(requestId: String) = REQUEST_PREFIX + requestId

    private fun ArkluzLocationUpdate.toJson(): JSONObject =
        JSONObject()
            .put("trackingUrl", trackingUrl)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("altitudeMeters", altitudeMeters ?: JSONObject.NULL)
            .put("speedMetersPerSecond", speedMetersPerSecond?.toDouble() ?: JSONObject.NULL)
            .put("accuracyMeters", accuracyMeters.toDouble())
            .put("epochMilliseconds", epochMilliseconds)
            .put("batteryPercent", batteryPercent)
            .put("gsmSignalPercent", gsmSignalPercent)
            .put("group", group)
            .put("participantPassword", participantPassword)
            .put("userName", userName)
            .put("notificationEmails", notificationEmails)
            .put("alertEmails", alertEmails)
            .put("stuckAlarmMinutes", stuckAlarmMinutes)
            .put("start", start)
            .put("stop", stop)
            .put("pause", pause)
            .put("resume", resume)
            .put("dateId", dateId ?: JSONObject.NULL)

    private fun JSONObject.toLocationUpdateOrNull(): ArkluzLocationUpdate? =
        runCatching {
            ArkluzLocationUpdate(
                trackingUrl = getString("trackingUrl"),
                latitude = getDouble("latitude"),
                longitude = getDouble("longitude"),
                altitudeMeters = nullableDouble("altitudeMeters"),
                speedMetersPerSecond = nullableDouble("speedMetersPerSecond")?.toFloat(),
                accuracyMeters = getDouble("accuracyMeters").toFloat(),
                epochMilliseconds = getLong("epochMilliseconds"),
                batteryPercent = getInt("batteryPercent"),
                gsmSignalPercent = getInt("gsmSignalPercent"),
                group = getString("group"),
                participantPassword = getString("participantPassword"),
                userName = getString("userName"),
                notificationEmails = optString("notificationEmails"),
                alertEmails = optString("alertEmails"),
                stuckAlarmMinutes = optString("stuckAlarmMinutes"),
                start = optBoolean("start"),
                stop = optBoolean("stop"),
                pause = optBoolean("pause"),
                resume = optBoolean("resume"),
                dateId = optString("dateId").takeIf(String::isNotBlank),
            )
        }.getOrNull()

    private fun JSONObject.nullableDouble(key: String): Double? = if (isNull(key)) null else optDouble(key)

    private companion object {
        const val PREFERENCES_NAME = "arkluz_live_tracking_stop_reconciliation"
        const val REQUEST_PREFIX = "request_"
        val lock = Any()
    }
}

internal object LiveTrackingStopReconciliationWork {
    const val INPUT_REQUEST_ID = "live_tracking_stop_request_id"
    private const val UNIQUE_WORK_PREFIX = "live_tracking_stop_"

    fun enqueue(
        context: Context,
        requestId: String,
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(requestId),
            ExistingWorkPolicy.KEEP,
            request(requestId),
        )
    }

    fun enqueuePending(context: Context) {
        SharedPreferencesLiveTrackingStopPendingStore(context).requestIds().forEach { requestId ->
            enqueue(context, requestId)
        }
    }

    internal fun request(requestId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<LiveTrackingStopReconciliationWorker>()
            .setInputData(workDataOf(INPUT_REQUEST_ID to requestId))
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            ).build()

    private fun uniqueWorkName(requestId: String) = UNIQUE_WORK_PREFIX + requestId

    // Matches the old first retry delay; WorkManager expands later failures exponentially.
    private const val INITIAL_BACKOFF_MILLIS = 30_000L
}

class LiveTrackingStopReconciliationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val requestId =
            inputData.getString(LiveTrackingStopReconciliationWork.INPUT_REQUEST_ID) ?: return Result.failure()
        val arkluzClient = ArkluzLiveTrackingClient(applicationContext)
        val reconciler =
            LiveTrackingStopReconciler(
                store = SharedPreferencesLiveTrackingStopPendingStore(applicationContext),
                send = { update -> arkluzClient.sendLocationUpdate(update) },
            )
        return when (val result = reconciler.reconcile(requestId)) {
            LiveTrackingStopReconciliationResult.Complete -> {
                if (LiveTrackingActiveSessionStore.load(applicationContext) == null) {
                    LiveTrackingSessionStore.setStopped()
                }
                Result.success()
            }

            is LiveTrackingStopReconciliationResult.Failed ->
                if (result.error.isRetryableArkluzFailure()) {
                    Result.retry()
                } else {
                    if (LiveTrackingActiveSessionStore.load(applicationContext) == null) {
                        LiveTrackingSessionStore.setStoppedWithError(
                            status = "Stopped; server confirmation pending",
                            message = result.error.toArkluzFailureDetail(),
                        )
                    }
                    Result.failure()
                }
        }
    }
}

internal fun stopLiveTrackingLocalResources(
    stopLocationUpdates: () -> Unit,
    stopSensors: () -> Unit,
) {
    stopLocationUpdates()
    stopSensors()
}

internal fun newLiveTrackingStopRequestId(): String = UUID.randomUUID().toString()
