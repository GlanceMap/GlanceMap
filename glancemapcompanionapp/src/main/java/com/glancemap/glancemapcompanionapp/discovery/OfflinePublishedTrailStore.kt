package com.glancemap.glancemapcompanionapp.discovery

import android.util.AtomicFile
import com.glancemap.trailcore.geo.GeoPoint
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

/** Catalog information only; all route geometry stays in the existing Route Library GPX. */
internal data class OfflinePublishedTrail(
    val trail: PublishedTrail,
    val libraryRouteId: String?,
    val downloadedAtMillis: Long,
)

internal class OfflinePublishedTrailStore(
    directory: File,
) {
    private val file = AtomicFile(File(directory, "route-discovery/offline.json"))

    fun load(): List<OfflinePublishedTrail> = synchronized(lock) { readLocked() }

    fun save(record: OfflinePublishedTrail): List<OfflinePublishedTrail> =
        synchronized(lock) {
            val previous = readLocked()
            val existing = previous.firstOrNull { it.trail.id == record.trail.id }
            val merged =
                record.copy(
                    trail =
                        record.trail.copy(
                            geometry = emptyList(),
                            geometryComplete = false,
                            providerDistanceMeters = record.trail.distanceMeters,
                            sources = (record.trail.sources + existing?.trail?.sources.orEmpty()).distinct(),
                        ),
                    libraryRouteId = record.libraryRouteId ?: existing?.libraryRouteId,
                )
            val next = listOf(merged) + previous.filterNot { it.trail.id == record.trail.id }
            require(next.size <= 1000) { "Offline route information storage is full" }
            val root =
                JsonObject().apply {
                    add("records", JsonArray().apply { next.forEach { add(offlineTrailJson(it)) } })
                }
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= OFFLINE_INFO_LIMIT) { "Offline route information storage is full" }
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: IOException) {
                file.failWrite(output)
                throw failure
            }
            next
        }

    private fun readLocked(): List<OfflinePublishedTrail> {
        if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return emptyList()
        val bytes =
            file.openRead().use {
                require(file.baseFile.length() <= OFFLINE_INFO_LIMIT)
                it.readBytes()
            }
        require(bytes.size <= OFFLINE_INFO_LIMIT)
        val records = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("records")
        require(records.size() <= 1000)
        return records.map { parseOfflineTrail(it.asJsonObject) }.distinctBy { it.trail.id }
    }

    private companion object {
        val lock = Any()
        const val OFFLINE_INFO_LIMIT = 4 * 1024 * 1024
    }
}

internal fun offlineTrailJson(record: OfflinePublishedTrail): JsonObject =
    JsonObject().apply {
        val trail = record.trail
        addProperty("id", trail.id)
        addProperty("title", trail.title)
        addProperty("description", trail.description)
        addProperty("distanceMeters", trail.distanceMeters)
        addProperty("difficulty", trail.difficulty)
        addProperty("activity", trail.activity)
        addProperty("libraryRouteId", record.libraryRouteId)
        addProperty("downloadedAtMillis", record.downloadedAtMillis)
        trail.startLocation?.let { point ->
            add(
                "startLocation",
                JsonObject().apply {
                    addProperty("latitude", point.latitude)
                    addProperty("longitude", point.longitude)
                },
            )
        }
        add(
            "sources",
            JsonArray().apply {
                trail.sources.forEach { source ->
                    add(
                        JsonObject().apply {
                            addProperty("provider", source.provider.name)
                            addProperty("providerTrailId", source.providerTrailId)
                            addProperty("sourceUrl", source.sourceUrl)
                            addProperty("attribution", source.attribution)
                            addProperty("license", source.license)
                            addProperty("publisherName", source.publisherName)
                            addProperty("updatedAt", source.updatedAt)
                        },
                    )
                }
            },
        )
    }

internal fun parseOfflineTrail(json: JsonObject): OfflinePublishedTrail {
    val id = json.trailText("id").also { require(it.isNotBlank()) }
    val title = json.trailText("title").also { require(it.isNotBlank()) }
    val sources =
        json.getAsJsonArray("sources").also { require(it.size() in 1..128) }.map { value ->
            val source = value.asJsonObject
            TrailSource(
                provider = TrailProvider.valueOf(source.trailText("provider")),
                providerTrailId = source.trailText("providerTrailId"),
                sourceUrl = source.trailText("sourceUrl").also { require(it.startsWith("https://")) },
                attribution = source.trailText("attribution"),
                license = source.trailText("license"),
                publisherName = source.trailText("publisherName").ifBlank { null },
                updatedAt = source.trailText("updatedAt").ifBlank { null },
            )
        }
    val location =
        json.get("startLocation")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            GeoPoint(it.get("latitude").asDouble, it.get("longitude").asDouble)
        }
    return OfflinePublishedTrail(
        trail =
            PublishedTrail(
                id = id,
                title = title,
                sources = sources,
                description = json.trailText("description"),
                startLocation = location,
                providerDistanceMeters = json.trailNumber("distanceMeters"),
                difficulty = json.trailText("difficulty").ifBlank { null },
                activity = json.trailText("activity").ifBlank { "Hiking" },
            ),
        libraryRouteId = json.trailText("libraryRouteId").ifBlank { null },
        downloadedAtMillis = json.get("downloadedAtMillis").asLong.also { require(it >= 0) },
    )
}
