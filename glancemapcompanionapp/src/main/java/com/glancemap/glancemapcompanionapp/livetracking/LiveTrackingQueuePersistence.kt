package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** Shared codec preserves legacy queues and malformed records during acknowledgements. */
internal class LiveTrackingQueuePersistence(
    private val name: String,
    private val read: () -> String,
    private val write: (String) -> Boolean,
) {
    fun load(): List<ArkluzLocationUpdate> =
        readArray().mapNotNull(::decode).let { updates ->
            if (name == "position_queue") updates.sortedBy { it.epochMilliseconds } else updates
        }

    fun enqueue(update: ArkluzLocationUpdate): Int {
        val records = readArray()
        val remaining = JsonArray()
        records.forEachIndexed { index, record ->
            val stored = decode(record)
            val replace =
                stored != null &&
                    matches(stored, update) &&
                    (name != "control_queue" || index == records.size() - 1)
            if (!replace) remaining.add(record)
        }
        remaining.add(update.toStoredJson())
        save(remaining, "stored", update)
        return remaining.size()
    }

    fun acknowledge(update: ArkluzLocationUpdate): Int {
        val records = readArray()
        val remaining = JsonArray()
        records.forEach { record ->
            val stored = decode(record)
            if (stored == null || !matches(stored, update)) remaining.add(record)
        }
        if (records.size() != remaining.size()) save(remaining, "acknowledged", update)
        return remaining.size()
    }

    fun removeFirst() {
        val records = readArray()
        val firstValid = records.indexOfFirst { decode(it) != null }
        if (firstValid >= 0) {
            records.remove(firstValid)
            save(records, "control_acknowledged")
        }
    }

    fun clear() = save(JsonArray(), "cleared")

    private fun readArray(): JsonArray {
        val parsed = runCatching { JsonParser.parseString(read()).asJsonArray }
        if (parsed.isFailure) {
            recordLiveTrackingDelivery(LiveTrackingDeliveryDiagnostic("${name}_malformed_queue"))
            throw LiveTrackingQueueException("Stored tracking data is unreadable; it has been preserved")
        }
        val records = parsed.getOrThrow()
        val malformed = records.count { decode(it) == null }
        if (malformed > 0) {
            recordLiveTrackingDelivery(
                LiveTrackingDeliveryDiagnostic("${name}_malformed_entries_retained", count = malformed),
            )
            if (name == "control_queue") {
                throw LiveTrackingQueueException("Pending controls are unreadable; their order has been preserved")
            }
        }
        return records
    }

    private fun save(
        records: JsonArray,
        event: String,
        update: ArkluzLocationUpdate? = null,
    ) {
        val committed = runCatching { write(records.toString()) }.getOrDefault(false)
        recordLiveTrackingDelivery(
            LiveTrackingDeliveryDiagnostic(
                event = "${name}_${if (committed) event else "write_failed"}",
                pointId = update?.pointId,
                fixTimestampEpochMillis = update?.epochMilliseconds,
                count = records.size(),
            ),
        )
        if (!committed) throw LiveTrackingQueueException("Unable to save pending tracking data")
        if (name == "position_queue" && records.size() > QUEUE_WARNING_SIZE) {
            // Unacknowledged points must not be evicted merely because an outage is long.
            recordLiveTrackingDelivery(LiveTrackingDeliveryDiagnostic("position_queue_large", count = records.size()))
        }
    }

    private fun matches(
        stored: ArkluzLocationUpdate,
        update: ArkluzLocationUpdate,
    ): Boolean =
        stored.isSameQueuedPoint(update) &&
            (
                name != "control_queue" ||
                    (
                        stored.start == update.start &&
                            stored.pause == update.pause &&
                            stored.resume == update.resume &&
                            stored.stop == update.stop
                    )
            )

    private fun decode(record: JsonElement): ArkluzLocationUpdate? =
        runCatching {
            record.asJsonObject.toStoredLocationUpdate()
        }.getOrNull()

    companion object {
        private const val QUEUE_WARNING_SIZE = 500

        fun fromPreferences(
            context: Context,
            prefsName: String,
            name: String,
        ): LiveTrackingQueuePersistence {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            return LiveTrackingQueuePersistence(
                name,
                read = { prefs.getString("queue", "[]").orEmpty() },
                write = { prefs.edit().putString("queue", it).commit() },
            )
        }
    }
}

internal class LiveTrackingQueueException(
    message: String,
) : IOException(message)

private fun ArkluzLocationUpdate.isSameQueuedPoint(other: ArkluzLocationUpdate): Boolean {
    val sameId = pointId != null && pointId == other.pointId
    return sameId || isSameGpsPoint(other)
}

internal fun ArkluzLocationUpdate.toStoredJson(): JsonObject =
    JsonObject().apply {
        addProperty("trackingUrl", trackingUrl)
        addProperty("latitude", latitude)
        addProperty("longitude", longitude)
        addProperty("altitudeMeters", altitudeMeters)
        addProperty("speedMetersPerSecond", speedMetersPerSecond)
        addProperty("accuracyMeters", accuracyMeters)
        addProperty("epochMilliseconds", epochMilliseconds)
        addProperty("batteryPercent", batteryPercent)
        addProperty("gsmSignalPercent", gsmSignalPercent)
        addProperty("group", group)
        addProperty("participantPassword", participantPassword)
        addProperty("userName", userName)
        addProperty("notificationEmails", notificationEmails)
        addProperty("alertEmails", alertEmails)
        addProperty("stuckAlarmMinutes", stuckAlarmMinutes)
        addProperty("start", start)
        addProperty("stop", stop)
        addProperty("pause", pause)
        addProperty("resume", resume)
        addProperty("dateId", dateId)
        addProperty("pointId", pointId)
    }

internal fun JsonObject.toStoredLocationUpdate(): ArkluzLocationUpdate =
    ArkluzLocationUpdate(
        trackingUrl = get("trackingUrl").asString,
        latitude = get("latitude").asDouble,
        longitude = get("longitude").asDouble,
        altitudeMeters = optional("altitudeMeters")?.asDouble,
        speedMetersPerSecond = optional("speedMetersPerSecond")?.asFloat,
        accuracyMeters = get("accuracyMeters").asFloat,
        epochMilliseconds = optional("epochMilliseconds")?.asLong ?: (get("epochSeconds").asLong * 1_000L),
        batteryPercent = get("batteryPercent").asInt,
        gsmSignalPercent = get("gsmSignalPercent").asInt,
        group = get("group").asString,
        participantPassword = get("participantPassword").asString,
        userName = get("userName").asString,
        notificationEmails = optional("notificationEmails")?.asString.orEmpty(),
        alertEmails = optional("alertEmails")?.asString.orEmpty(),
        stuckAlarmMinutes = optional("stuckAlarmMinutes")?.asString.orEmpty(),
        start = optional("start")?.asBoolean ?: false,
        stop = optional("stop")?.asBoolean ?: false,
        pause = optional("pause")?.asBoolean ?: false,
        resume = optional("resume")?.asBoolean ?: false,
        dateId = optional("dateId")?.asString?.takeIf(String::isNotBlank),
        pointId = optional("pointId")?.asString?.takeIf(String::isNotBlank),
    ).also { update ->
        require(update.latitude.isFinite() && update.latitude in -90.0..90.0)
        require(update.longitude.isFinite() && update.longitude in -180.0..180.0)
        require(update.epochMilliseconds > 0L && update.accuracyMeters.isFinite())
    }

private fun JsonObject.optional(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }
