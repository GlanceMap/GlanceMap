@file:Suppress("TooManyFunctions") // Bundle persistence and refresh metadata live with the bundle model.

package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.trailcore.oam.OamDownloadArea
import com.glancemap.trailcore.oam.OamDownloadCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

internal enum class PhoneOfflineDemSource(
    val id: String,
    val label: String,
    val shortLabel: String,
    val detailLabel: String,
    private val baseUrl: String,
    private val remoteExtension: String,
) {
    STANDARD(
        id = "mapsforge_dem3",
        label = "Standard terrain",
        shortLabel = "Standard",
        detailLabel = "Smaller files, good for most maps",
        baseUrl = "https://download.mapsforge.org/maps/dem/dem3",
        remoteExtension = ".hgt.zip",
    ),
    DETAILED(
        id = "mapzen_skadi_1s",
        label = "Detailed terrain",
        shortLabel = "Detailed",
        detailLabel = "Sharper hills, much larger files",
        baseUrl = "https://s3.amazonaws.com/elevation-tiles-prod/skadi",
        remoteExtension = ".hgt.gz",
    ),
    ;

    fun remoteFileName(tileId: String): String = tileId.uppercase(Locale.ROOT) + remoteExtension

    fun remoteUrl(tileId: String): String {
        val safeTileId = tileId.uppercase(Locale.ROOT)
        return "$baseUrl/${safeTileId.substring(0, 3)}/${remoteFileName(safeTileId)}"
    }

    companion object {
        val DEFAULT = STANDARD

        fun fromId(id: String?): PhoneOfflineDemSource = entries.firstOrNull { source -> source.id == id } ?: DEFAULT
    }
}

/** The phone bundle combines an OAM map/POI database with optional routing and elevation data. */
internal data class PhoneOfflineBundleSelection(
    val area: OamDownloadArea,
    val includeMap: Boolean = true,
    val includePoi: Boolean = true,
    val includeRouting: Boolean = true,
    val includeDem: Boolean = true,
    val demSource: PhoneOfflineDemSource = PhoneOfflineDemSource.DEFAULT,
    val includeRefugesInfo: Boolean = false,
) {
    val canDownload: Boolean
        get() = includeMap || includePoi || includeRouting || includeDem || includeRefugesInfo

    fun label(): String =
        listOfNotNull(
            "Map".takeIf { includeMap },
            "POI".takeIf { includePoi },
            "Routing".takeIf { includeRouting },
            "${demSource.shortLabel} elevation".takeIf { includeDem },
            "Refuges.info".takeIf { includeRefugesInfo },
        ).joinToString(" + ").ifBlank { "Nothing selected" }
}

/** Lightweight file metadata persisted with a completed bundle for recovery diagnostics. */
internal data class PhoneOfflineFileIntegrity(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

internal data class PhoneOfflineRemoteFileMetadata(
    val url: String,
    val fileName: String,
    val entityTag: String?,
    val lastModifiedMillis: Long?,
    val contentLengthBytes: Long?,
)

/** Metadata for files installed by the phone bundle flow, including remote update state. */
internal data class PhoneInstalledBundle(
    val areaId: String,
    val areaLabel: String,
    val mapFileName: String,
    val poiFileName: String,
    val refugesInfoFileName: String? = null,
    val routingFileNames: List<String> = emptyList(),
    val downloadedRoutingFileNames: List<String> = emptyList(),
    val demSource: PhoneOfflineDemSource = PhoneOfflineDemSource.DEFAULT,
    val demTileIds: List<String> = emptyList(),
    val installedAtMillis: Long,
    val downloadedDemTileIds: List<String> = emptyList(),
    val integrity: List<PhoneOfflineFileIntegrity> = emptyList(),
    val remoteFiles: List<PhoneOfflineRemoteFileMetadata> = emptyList(),
)

internal enum class PhoneOfflineBundleUpdateStatus {
    REPAIR_NEEDED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    UNKNOWN,
}

internal data class PhoneOfflineBundleUpdateCheck(
    val bundle: PhoneInstalledBundle,
    val status: PhoneOfflineBundleUpdateStatus,
    val checkedFileCount: Int,
    val changedFileNames: List<String> = emptyList(),
    val repairFileNames: List<String> = emptyList(),
    val unknownFileNames: List<String> = emptyList(),
)

internal data class PhoneOfflineBundleRefreshForces(
    val forceMap: Boolean = false,
    val forcePoi: Boolean = false,
    val forceRefugesInfo: Boolean = false,
    val forceRouting: Boolean = false,
    val forceDemTileIds: Set<String> = emptySet(),
)

internal fun PhoneOfflineBundleUpdateCheck.refreshForces(
    area: OamDownloadArea,
): PhoneOfflineBundleRefreshForces {
    val changedNames = (changedFileNames + repairFileNames).map { File(it).name }.toSet()
    val forceUnknown = status == PhoneOfflineBundleUpdateStatus.UNKNOWN
    return PhoneOfflineBundleRefreshForces(
        forceMap = forceUnknown || phoneOfflineRemoteFileName(area.mapZipUrl) in changedNames,
        forcePoi = forceUnknown || phoneOfflineRemoteFileName(area.poiZipUrl) in changedNames,
        forceRefugesInfo =
            forceUnknown || bundle.refugesInfoFileName?.let { File(it).name in changedNames } == true,
        forceRouting = forceUnknown || bundle.routingFileNames.any { File(it).name in changedNames },
        forceDemTileIds =
            if (forceUnknown) {
                bundle.demTileIds.map { it.uppercase(Locale.ROOT) }.toSet()
            } else {
                bundle.demTileIds
                    .map { it.uppercase(Locale.ROOT) }
                    .filter { bundle.demSource.remoteFileName(it) in changedNames }
                    .toSet()
            },
    )
}

internal fun phoneOfflineRemoteFileName(url: String): String =
    runCatching { File(URI(url).path).name }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: url.substringAfterLast('/').ifBlank { "download" }

internal enum class PhoneOfflineRemoteMetadataComparison {
    SAME,
    CHANGED,
    UNKNOWN,
}

@Suppress("CyclomaticComplexMethod") // Compares the three optional HTTP validators in priority order.
internal fun PhoneOfflineRemoteFileMetadata.compareWith(
    other: PhoneOfflineRemoteFileMetadata,
): PhoneOfflineRemoteMetadataComparison =
    when {
        url != other.url -> PhoneOfflineRemoteMetadataComparison.CHANGED
        contentLengthBytes != null &&
            other.contentLengthBytes != null &&
            contentLengthBytes == other.contentLengthBytes -> PhoneOfflineRemoteMetadataComparison.SAME
        contentLengthBytes != null && other.contentLengthBytes != null ->
            PhoneOfflineRemoteMetadataComparison.CHANGED
        lastModifiedMillis != null && other.lastModifiedMillis != null ->
            if (lastModifiedMillis == other.lastModifiedMillis) {
                PhoneOfflineRemoteMetadataComparison.SAME
            } else {
                PhoneOfflineRemoteMetadataComparison.CHANGED
            }
        entityTag != null && other.entityTag != null && entityTag == other.entityTag ->
            PhoneOfflineRemoteMetadataComparison.SAME
        entityTag != null && other.entityTag != null -> PhoneOfflineRemoteMetadataComparison.CHANGED
        else -> PhoneOfflineRemoteMetadataComparison.UNKNOWN
    }

internal fun PhoneOfflineRemoteFileMetadata.isComparable(): Boolean =
    entityTag != null ||
        lastModifiedMillis != null ||
        contentLengthBytes != null

/** State kept while a bundle is incomplete so the next download can repair it. */
internal data class PhoneOfflineBundleRecovery(
    val areaId: String,
    val areaLabel: String,
    val includeMap: Boolean,
    val includePoi: Boolean,
    val includeRouting: Boolean,
    val includeDem: Boolean,
    val includeRefugesInfo: Boolean,
    val demSource: PhoneOfflineDemSource,
    val phase: PhoneOfflineBundlePhase,
    val mapFileName: String? = null,
    val poiFileName: String? = null,
    val refugesInfoFileName: String? = null,
    val routingFileNames: List<String> = emptyList(),
    val downloadedRoutingFileNames: List<String> = emptyList(),
    val demTileIds: List<String> = emptyList(),
    val downloadedDemTileIds: List<String> = emptyList(),
    val detail: String = "",
    val failure: PhoneOfflineBundleFailure? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

internal enum class PhoneOfflineBundlePhase {
    DOWNLOADING_MAP,
    INSTALLING_MAP,
    DOWNLOADING_POI,
    INSTALLING_POI,
    DOWNLOADING_ROUTING,
    DOWNLOADING_DEM,
    DOWNLOADING_REFUGES,
}

internal data class PhoneOfflineBundleProgress(
    val phase: PhoneOfflineBundlePhase,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val detail: String = "",
    val percent: Int? = null,
)

internal enum class PhoneOfflineBundleFailure {
    NETWORK,
    HTTP,
    STORAGE,
    ARCHIVE,
    INVALID_MAP,
    INVALID_POI,
    INVALID_REFUGES_INFO,
    CANCELLED,
}

internal enum class PhoneOfflineBundleOperationStatus {
    RUNNING,
    PAUSED,
}

/** Persisted operation plan used by the foreground bundle service. */
internal data class PhoneOfflineBundleOperation(
    val selections: List<PhoneOfflineBundleSelection>,
    val refreshForces: List<PhoneOfflineBundleRefreshForces> = emptyList(),
    val nextSelectionIndex: Int = 0,
    val status: PhoneOfflineBundleOperationStatus = PhoneOfflineBundleOperationStatus.RUNNING,
) {
    fun forcesFor(index: Int): PhoneOfflineBundleRefreshForces = refreshForces.getOrNull(index) ?: PhoneOfflineBundleRefreshForces()
}

internal class PhoneOfflineBundleOperationStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneOfflineBundleOperation? {
        val encoded = preferences.getString(KEY_OPERATION, null) ?: return null
        return runCatching {
            val root = JSONObject(encoded)
            val status =
                PhoneOfflineBundleOperationStatus.valueOf(
                    root.optString("status", PhoneOfflineBundleOperationStatus.RUNNING.name),
                )
            val selectionsJson = root.optJSONArray("selections") ?: return@runCatching null
            val entries =
                buildList {
                    for (index in 0 until selectionsJson.length()) {
                        val value = selectionsJson.optJSONObject(index)
                        selectionFromJson(value)?.let { selection ->
                            add(selection to refreshForcesFromJson(value))
                        }
                    }
                }
            if (entries.isEmpty()) {
                null
            } else {
                PhoneOfflineBundleOperation(
                    selections = entries.map { entry -> entry.first },
                    refreshForces = entries.map { entry -> entry.second },
                    nextSelectionIndex =
                        root.optInt("next_selection_index", 0).coerceIn(0, entries.size),
                    status = status,
                )
            }
        }.getOrNull()
    }

    fun save(operation: PhoneOfflineBundleOperation) {
        val root =
            JSONObject().apply {
                put("status", operation.status.name)
                put("next_selection_index", operation.nextSelectionIndex.coerceAtLeast(0))
                put(
                    "selections",
                    JSONArray().apply {
                        operation.selections.forEachIndexed { index, selection ->
                            put(selection.toJson(operation.forcesFor(index)))
                        }
                    },
                )
            }
        preferences.edit().putString(KEY_OPERATION, root.toString()).commit()
    }

    fun clear() {
        preferences.edit().remove(KEY_OPERATION).commit()
    }

    private fun selectionFromJson(value: JSONObject?): PhoneOfflineBundleSelection? {
        value ?: return null
        val areaId = value.optString("area_id").takeIf(String::isNotBlank) ?: return null
        val area = OamDownloadCatalog.areas.firstOrNull { it.id == areaId } ?: return null
        return PhoneOfflineBundleSelection(
            area = area,
            includeMap = value.optBoolean("include_map", true),
            includePoi = value.optBoolean("include_poi", true),
            includeRouting = value.optBoolean("include_routing", true),
            includeDem = value.optBoolean("include_dem", true),
            demSource = PhoneOfflineDemSource.fromId(value.optString("dem_source")),
            includeRefugesInfo = value.optBoolean("include_refuges_info", false),
        )
    }

    private fun refreshForcesFromJson(
        value: JSONObject?,
    ): PhoneOfflineBundleRefreshForces {
        value ?: return PhoneOfflineBundleRefreshForces()
        return PhoneOfflineBundleRefreshForces(
            forceMap = value.optBoolean("force_map", false),
            forcePoi = value.optBoolean("force_poi", false),
            forceRefugesInfo = value.optBoolean("force_refuges_info", false),
            forceRouting = value.optBoolean("force_routing", false),
            forceDemTileIds =
                value
                    .optJSONArray("force_dem_tiles")
                    ?.let { tiles ->
                        buildSet {
                            for (index in 0 until tiles.length()) {
                                tiles.optString(index).takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }.orEmpty(),
        )
    }

    private fun PhoneOfflineBundleSelection.toJson(
        forces: PhoneOfflineBundleRefreshForces,
    ): JSONObject =
        JSONObject().apply {
            put("area_id", area.id)
            put("include_map", includeMap)
            put("include_poi", includePoi)
            put("include_routing", includeRouting)
            put("include_dem", includeDem)
            put("dem_source", demSource.id)
            put("include_refuges_info", includeRefugesInfo)
            put("force_map", forces.forceMap)
            put("force_poi", forces.forcePoi)
            put("force_refuges_info", forces.forceRefugesInfo)
            put("force_routing", forces.forceRouting)
            put("force_dem_tiles", JSONArray().apply { forces.forceDemTileIds.forEach(::put) })
        }

    private companion object {
        const val PREFERENCES_NAME = "phone_oam_download_operation"
        const val KEY_OPERATION = "operation"
    }
}

internal enum class PhoneOfflineBundleStatus {
    COMPLETE,
    PARTIAL,
    RECOVERY_NEEDED,
    NOT_INSTALLED,
}

internal data class PhoneOfflineBundleHealth(
    val status: PhoneOfflineBundleStatus,
    val expectedFileNames: List<String> = emptyList(),
    val availableFileNames: List<String> = emptyList(),
    val missingFileNames: List<String> = emptyList(),
    val invalidFileNames: List<String> = emptyList(),
    val integrity: List<PhoneOfflineFileIntegrity> = emptyList(),
    val hasRecovery: Boolean = false,
) {
    val isComplete: Boolean
        get() = status == PhoneOfflineBundleStatus.COMPLETE
}

/** Pure completion calculation shared by the downloader, UI status, and unit tests. */
@Suppress("LongParameterList") // The pure helper keeps the completion inputs explicit and easy to test.
internal fun phoneOfflineBundleHealth(
    hasMap: Boolean,
    hasPoi: Boolean,
    expectsRefugesInfo: Boolean,
    hasRefugesInfo: Boolean,
    expectedRoutingFileNames: List<String>,
    downloadedRoutingFileNames: List<String>,
    expectedDemTileIds: List<String>,
    downloadedDemTileIds: List<String>,
    hasRecovery: Boolean = false,
): PhoneOfflineBundleHealth {
    val expected =
        buildList {
            add("map")
            add("poi")
            if (expectsRefugesInfo) add("refuges.info")
            addAll(expectedRoutingFileNames.map { java.io.File(it).name })
            addAll(expectedDemTileIds.map { it.uppercase(Locale.ROOT) })
        }.distinct()
    val available =
        buildList {
            if (hasMap) add("map")
            if (hasPoi) add("poi")
            if (expectsRefugesInfo && hasRefugesInfo) add("refuges.info")
            addAll(downloadedRoutingFileNames.map { java.io.File(it).name })
            addAll(downloadedDemTileIds.map { it.uppercase(Locale.ROOT) })
        }.distinct()
    val missing = expected.filterNot(available::contains)
    val status =
        when {
            hasRecovery -> PhoneOfflineBundleStatus.RECOVERY_NEEDED
            missing.isEmpty() -> PhoneOfflineBundleStatus.COMPLETE
            available.isEmpty() -> PhoneOfflineBundleStatus.NOT_INSTALLED
            else -> PhoneOfflineBundleStatus.PARTIAL
        }
    return PhoneOfflineBundleHealth(
        status = status,
        expectedFileNames = expected,
        availableFileNames = available,
        missingFileNames = missing,
        hasRecovery = hasRecovery,
    )
}

internal sealed interface PhoneOfflineBundleOutcome {
    data class Success(
        val bundle: PhoneInstalledBundle,
        val reusedMap: Boolean,
        val reusedPoi: Boolean,
    ) : PhoneOfflineBundleOutcome

    data class Failure(
        val reason: PhoneOfflineBundleFailure,
    ) : PhoneOfflineBundleOutcome
}

/** Companion-owned persistence for the phone's installed offline bundles. */
@Suppress("TooManyFunctions") // Installed and recovery metadata intentionally share one preference store.
internal class PhoneOfflineBundleStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun list(): List<PhoneInstalledBundle> =
        preferences
            .getStringSet(KEY_AREA_IDS, emptySet())
            .orEmpty()
            .mapNotNull(::read)
            .sortedBy { it.areaLabel.lowercase() }

    fun find(areaId: String): PhoneInstalledBundle? = read(areaId)

    fun upsert(bundle: PhoneInstalledBundle) {
        val areaIds = preferences.getStringSet(KEY_AREA_IDS, emptySet()).orEmpty() + bundle.areaId
        val editor = preferences.edit()
        editor.putStringSet(KEY_AREA_IDS, areaIds)
        editor.putString(key(bundle.areaId, "label"), bundle.areaLabel)
        editor.putString(key(bundle.areaId, "map"), bundle.mapFileName)
        editor.putString(key(bundle.areaId, "poi"), bundle.poiFileName)
        editor.putString(key(bundle.areaId, "refuges_info"), bundle.refugesInfoFileName)
        editor.putString(key(bundle.areaId, "routing"), bundle.routingFileNames.joinToString("\n"))
        editor.putString(
            key(bundle.areaId, "routing_downloaded"),
            bundle.downloadedRoutingFileNames.joinToString("\n"),
        )
        editor.putString(key(bundle.areaId, "dem_source"), bundle.demSource.id)
        editor.putString(key(bundle.areaId, "dem"), bundle.demTileIds.joinToString("\n"))
        editor.putString(
            key(bundle.areaId, "dem_downloaded"),
            bundle.downloadedDemTileIds.joinToString("\n"),
        )
        editor.putString(
            key(bundle.areaId, "integrity"),
            bundle.integrity.joinToString("\n", transform = ::encodeIntegrity),
        )
        editor.putLong(key(bundle.areaId, "installed_at"), bundle.installedAtMillis)
        editor.putString(key(bundle.areaId, "remote_files"), bundle.remoteFiles.toJson())
        editor.apply()
    }

    /** Keeps bundle health metadata in sync when the user renames a visible map or POI source. */
    fun replaceManagedFileName(
        oldFileName: String,
        newFileName: String,
        isMap: Boolean,
    ) {
        val oldSafeName = java.io.File(oldFileName).name
        val newSafeName = java.io.File(newFileName).name
        list().forEach { bundle ->
            val updated =
                if (isMap && bundle.mapFileName == oldSafeName) {
                    bundle.copy(mapFileName = newSafeName)
                } else if (!isMap && bundle.poiFileName == oldSafeName) {
                    bundle.copy(poiFileName = newSafeName)
                } else if (!isMap && bundle.refugesInfoFileName == oldSafeName) {
                    bundle.copy(refugesInfoFileName = newSafeName)
                } else {
                    null
                }
            updated?.let { changed ->
                upsert(changed.copy(integrity = phoneOfflineBundleIntegrity(appContext, changed)))
            }
        }
    }

    fun findRecovery(areaId: String): PhoneOfflineBundleRecovery? = readRecovery(areaId)

    fun recoveries(): List<PhoneOfflineBundleRecovery> =
        preferences
            .getStringSet(KEY_RECOVERY_AREA_IDS, emptySet())
            .orEmpty()
            .mapNotNull(::readRecovery)
            .sortedBy { it.areaLabel.lowercase(Locale.ROOT) }

    fun saveRecovery(recovery: PhoneOfflineBundleRecovery) {
        val areaIds = preferences.getStringSet(KEY_RECOVERY_AREA_IDS, emptySet()).orEmpty() + recovery.areaId
        val editor = preferences.edit()
        editor.putStringSet(KEY_RECOVERY_AREA_IDS, areaIds)
        editor.putString(recoveryKey(recovery.areaId, "label"), recovery.areaLabel)
        editor.putBoolean(recoveryKey(recovery.areaId, "include_map"), recovery.includeMap)
        editor.putBoolean(recoveryKey(recovery.areaId, "include_poi"), recovery.includePoi)
        editor.putBoolean(recoveryKey(recovery.areaId, "include_routing"), recovery.includeRouting)
        editor.putBoolean(recoveryKey(recovery.areaId, "include_dem"), recovery.includeDem)
        editor.putBoolean(recoveryKey(recovery.areaId, "include_refuges"), recovery.includeRefugesInfo)
        editor.putString(recoveryKey(recovery.areaId, "dem_source"), recovery.demSource.id)
        editor.putString(recoveryKey(recovery.areaId, "phase"), recovery.phase.name)
        editor.putString(recoveryKey(recovery.areaId, "map"), recovery.mapFileName)
        editor.putString(recoveryKey(recovery.areaId, "poi"), recovery.poiFileName)
        editor.putString(recoveryKey(recovery.areaId, "refuges_info"), recovery.refugesInfoFileName)
        editor.putString(recoveryKey(recovery.areaId, "routing"), recovery.routingFileNames.joinToString("\n"))
        editor.putString(
            recoveryKey(recovery.areaId, "routing_downloaded"),
            recovery.downloadedRoutingFileNames.joinToString("\n"),
        )
        editor.putString(recoveryKey(recovery.areaId, "dem"), recovery.demTileIds.joinToString("\n"))
        editor.putString(
            recoveryKey(recovery.areaId, "dem_downloaded"),
            recovery.downloadedDemTileIds.joinToString("\n"),
        )
        editor.putString(recoveryKey(recovery.areaId, "detail"), recovery.detail)
        editor.putString(recoveryKey(recovery.areaId, "failure"), recovery.failure?.name)
        editor.putLong(recoveryKey(recovery.areaId, "updated_at"), recovery.updatedAtMillis)
        editor.apply()
    }

    fun clearRecovery(areaId: String) {
        val areaIds = preferences.getStringSet(KEY_RECOVERY_AREA_IDS, emptySet()).orEmpty() - areaId
        preferences
            .edit()
            .putStringSet(KEY_RECOVERY_AREA_IDS, areaIds)
            .remove(recoveryKey(areaId, "label"))
            .remove(recoveryKey(areaId, "include_map"))
            .remove(recoveryKey(areaId, "include_poi"))
            .remove(recoveryKey(areaId, "include_routing"))
            .remove(recoveryKey(areaId, "include_dem"))
            .remove(recoveryKey(areaId, "include_refuges"))
            .remove(recoveryKey(areaId, "dem_source"))
            .remove(recoveryKey(areaId, "phase"))
            .remove(recoveryKey(areaId, "map"))
            .remove(recoveryKey(areaId, "poi"))
            .remove(recoveryKey(areaId, "refuges_info"))
            .remove(recoveryKey(areaId, "routing"))
            .remove(recoveryKey(areaId, "routing_downloaded"))
            .remove(recoveryKey(areaId, "dem"))
            .remove(recoveryKey(areaId, "dem_downloaded"))
            .remove(recoveryKey(areaId, "detail"))
            .remove(recoveryKey(areaId, "failure"))
            .remove(recoveryKey(areaId, "updated_at"))
            .apply()
    }

    private fun read(areaId: String): PhoneInstalledBundle? {
        val areaLabel = preferences.getString(key(areaId, "label"), null)
        val mapFileName = preferences.getString(key(areaId, "map"), null)
        val poiFileName = preferences.getString(key(areaId, "poi"), null)
        return if (areaLabel == null || mapFileName == null || poiFileName == null) {
            null
        } else {
            val routingFileNames = preferences.getString(key(areaId, "routing"), null).toFileNames(".rd5")
            val demTileIds = preferences.getString(key(areaId, "dem"), null).toTileIds()
            PhoneInstalledBundle(
                areaId = areaId,
                areaLabel = areaLabel,
                mapFileName = mapFileName,
                poiFileName = poiFileName,
                refugesInfoFileName = preferences.getString(key(areaId, "refuges_info"), null)?.safePoiFileName(),
                routingFileNames = routingFileNames,
                downloadedRoutingFileNames =
                    preferences
                        .getString(key(areaId, "routing_downloaded"), null)
                        .toFileNames(".rd5")
                        .ifEmpty { routingFileNames },
                demSource = PhoneOfflineDemSource.fromId(preferences.getString(key(areaId, "dem_source"), null)),
                demTileIds = demTileIds,
                downloadedDemTileIds =
                    preferences
                        .getString(key(areaId, "dem_downloaded"), null)
                        .toTileIds()
                        .ifEmpty { demTileIds },
                installedAtMillis = preferences.getLong(key(areaId, "installed_at"), 0L),
                integrity = preferences.getString(key(areaId, "integrity"), null).toIntegrity(),
                remoteFiles = preferences.getString(key(areaId, "remote_files"), null).toRemoteFileMetadata(),
            )
        }
    }

    @Suppress("ReturnCount") // Invalid persisted phase data is rejected at the persistence boundary.
    private fun readRecovery(areaId: String): PhoneOfflineBundleRecovery? {
        val label = preferences.getString(recoveryKey(areaId, "label"), null) ?: return null
        val phase =
            preferences
                .getString(recoveryKey(areaId, "phase"), null)
                ?.let { value -> runCatching { PhoneOfflineBundlePhase.valueOf(value) }.getOrNull() }
                ?: return null
        return PhoneOfflineBundleRecovery(
            areaId = areaId,
            areaLabel = label,
            includeMap = preferences.getBoolean(recoveryKey(areaId, "include_map"), true),
            includePoi = preferences.getBoolean(recoveryKey(areaId, "include_poi"), true),
            includeRouting = preferences.getBoolean(recoveryKey(areaId, "include_routing"), false),
            includeDem = preferences.getBoolean(recoveryKey(areaId, "include_dem"), false),
            includeRefugesInfo = preferences.getBoolean(recoveryKey(areaId, "include_refuges"), false),
            demSource = PhoneOfflineDemSource.fromId(preferences.getString(recoveryKey(areaId, "dem_source"), null)),
            phase = phase,
            mapFileName = preferences.getString(recoveryKey(areaId, "map"), null)?.safeMapFileName(),
            poiFileName = preferences.getString(recoveryKey(areaId, "poi"), null)?.safePoiFileName(),
            refugesInfoFileName = preferences.getString(recoveryKey(areaId, "refuges_info"), null)?.safePoiFileName(),
            routingFileNames = preferences.getString(recoveryKey(areaId, "routing"), null).toFileNames(".rd5"),
            downloadedRoutingFileNames =
                preferences
                    .getString(recoveryKey(areaId, "routing_downloaded"), null)
                    .toFileNames(".rd5"),
            demTileIds = preferences.getString(recoveryKey(areaId, "dem"), null).toTileIds(),
            downloadedDemTileIds = preferences.getString(recoveryKey(areaId, "dem_downloaded"), null).toTileIds(),
            detail = preferences.getString(recoveryKey(areaId, "detail"), "").orEmpty(),
            failure =
                preferences
                    .getString(recoveryKey(areaId, "failure"), null)
                    ?.let { value -> runCatching { PhoneOfflineBundleFailure.valueOf(value) }.getOrNull() },
            updatedAtMillis = preferences.getLong(recoveryKey(areaId, "updated_at"), 0L),
        )
    }

    private fun key(
        areaId: String,
        suffix: String,
    ): String = "$areaId.$suffix"

    private fun recoveryKey(
        areaId: String,
        suffix: String,
    ): String = "recovery.$areaId.$suffix"

    private companion object {
        const val PREFERENCES_NAME = "phone_oam_bundles"
        const val KEY_AREA_IDS = "area_ids"
        const val KEY_RECOVERY_AREA_IDS = "recovery_area_ids"
    }
}

private fun String?.toRemoteFileMetadata(): List<PhoneOfflineRemoteFileMetadata> =
    runCatching {
        if (isNullOrBlank()) return@runCatching emptyList()
        val array = org.json.JSONArray(this)
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { objectValue ->
                    val url = objectValue.optString("url").takeIf(String::isNotBlank) ?: return@let
                    val fileName = objectValue.optString("fileName").takeIf(String::isNotBlank) ?: return@let
                    add(
                        PhoneOfflineRemoteFileMetadata(
                            url = url,
                            fileName = fileName,
                            entityTag = objectValue.optString("entityTag").takeIf(String::isNotBlank),
                            lastModifiedMillis = objectValue.optLongOrNull("lastModifiedMillis"),
                            contentLengthBytes = objectValue.optLongOrNull("contentLengthBytes"),
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

private fun List<PhoneOfflineRemoteFileMetadata>.toJson(): String {
    val array = org.json.JSONArray()
    forEach { file ->
        array.put(
            org.json
                .JSONObject()
                .put("url", file.url)
                .put("fileName", file.fileName)
                .put("entityTag", file.entityTag)
                .put("lastModifiedMillis", file.lastModifiedMillis)
                .put("contentLengthBytes", file.contentLengthBytes),
        )
    }
    return array.toString()
}

private fun org.json.JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) {
        optLong(name).takeIf { it >= 0L }
    } else {
        null
    }

private fun encodeIntegrity(integrity: PhoneOfflineFileIntegrity): String {
    val fields = listOf(integrity.fileName, integrity.sizeBytes, integrity.lastModifiedMillis)
    return fields.joinToString("|")
}

private fun String?.toIntegrity(): List<PhoneOfflineFileIntegrity> =
    this
        ?.lineSequence()
        ?.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val size = parts[1].toLongOrNull() ?: return@mapNotNull null
            val modified = parts[2].toLongOrNull() ?: return@mapNotNull null
            PhoneOfflineFileIntegrity(parts[0].trim(), size, modified)
        }?.distinctBy { it.fileName }
        ?.toList()
        .orEmpty()

private fun String.safeMapFileName(): String? =
    java.io
        .File(this)
        .name
        .takeIf { it.endsWith(".map", ignoreCase = true) }

private fun String.safePoiFileName(): String? =
    java.io
        .File(this)
        .name
        .takeIf { it.endsWith(".poi", ignoreCase = true) }

private fun String?.toFileNames(extension: String): List<String> =
    this
        ?.lineSequence()
        ?.map { line -> line.trim() }
        ?.filter { line -> line.endsWith(extension, ignoreCase = true) }
        ?.map { line -> java.io.File(line).name }
        ?.distinct()
        ?.toList()
        .orEmpty()

private fun String?.toTileIds(): List<String> =
    this
        ?.lineSequence()
        ?.map { line -> line.trim().uppercase(Locale.ROOT) }
        ?.filter { line -> line.matches(Regex("[NS]\\d{2}[EW]\\d{3}")) }
        ?.distinct()
        ?.toList()
        .orEmpty()
