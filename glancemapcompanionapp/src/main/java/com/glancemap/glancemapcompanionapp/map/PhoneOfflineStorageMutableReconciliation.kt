@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.glancemapcompanionapp.routes.MissionPlanDay
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets

internal data class PhoneOfflineStorageSemanticFile(
    val relativePath: String,
    val source: PhoneOfflineStorageFile? = null,
    val bytes: ByteArray? = null,
    val decision: PhoneOfflineStorageReconciliationDecision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
) {
    init {
        require(source != null || bytes != null)
    }
}

internal data class PhoneOfflineStorageMutableReconciliation(
    val files: List<PhoneOfflineStorageSemanticFile> = emptyList(),
    val sourceRouteCount: Int = 0,
    val targetRouteCount: Int = 0,
    val mergedRouteCount: Int = 0,
    val sourceWinsCount: Int = 0,
    val targetOnlyRouteCount: Int = 0,
    val orphanedRoutesDropped: Int = 0,
    val selectedRouteId: String? = null,
    val missionSourcePresent: Boolean = false,
    val missionTargetPresent: Boolean = false,
    val missionIdentical: Boolean = false,
    val missionConflictPreserved: Boolean = false,
    val missionActiveSourceChosen: Boolean = false,
)

/**
 * Reconciles the two mutable user-data stores without applying immutable asset conflict rules.
 * The returned files are either copied from one side or generated as small index/recovery files.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun reconcilePhoneOfflineMutableData(
    sourceFiles: List<PhoneOfflineStorageFile>,
    targetFiles: List<PhoneOfflineStorageFile>,
    sourceLocation: PhoneOfflineStorageLocation,
    targetLocation: PhoneOfflineStorageLocation,
    migrationId: String,
): PhoneOfflineStorageMutableReconciliation {
    val gson = Gson()
    val recovery = RecoveryFiles(sourceFiles, targetFiles, sourceLocation, targetLocation, migrationId)
    val routeResult = reconcileRouteLibrary(sourceFiles, targetFiles, gson, recovery)
    val missionResult = reconcileMissionPlan(sourceFiles, targetFiles, recovery)
    return PhoneOfflineStorageMutableReconciliation(
        files = routeResult.files + missionResult.files + recovery.files,
        sourceRouteCount = routeResult.sourceRouteCount,
        targetRouteCount = routeResult.targetRouteCount,
        mergedRouteCount = routeResult.mergedRouteCount,
        sourceWinsCount = routeResult.sourceWinsCount,
        targetOnlyRouteCount = routeResult.targetOnlyRouteCount,
        orphanedRoutesDropped = routeResult.orphanedRoutesDropped,
        selectedRouteId = routeResult.selectedRouteId,
        missionSourcePresent = missionResult.sourcePresent,
        missionTargetPresent = missionResult.targetPresent,
        missionIdentical = missionResult.identical,
        missionConflictPreserved = missionResult.conflictPreserved,
        missionActiveSourceChosen = missionResult.activeSourceChosen,
    )
}

private data class RouteLibraryStorageIndex(
    val routes: List<RouteLibraryRoute> = emptyList(),
    val selectedRouteId: String? = null,
)

private data class MissionPlanStorageIndex(
    val days: List<MissionPlanDay> = emptyList(),
    val selectedDayId: String? = null,
)

private data class ParsedRouteLibraryIndex(
    val record: PhoneOfflineStorageFile?,
    val index: RouteLibraryStorageIndex?,
    val valid: Boolean,
) {
    val present: Boolean
        get() = record != null
}

private data class ParsedMissionPlanIndex(
    val record: PhoneOfflineStorageFile?,
    val index: MissionPlanStorageIndex?,
    val valid: Boolean,
) {
    val present: Boolean
        get() = record != null

    val hasPlan: Boolean
        get() = valid && index?.days?.isNotEmpty() == true
}

private data class RouteCandidate(
    val route: RouteLibraryRoute,
    val backingFile: PhoneOfflineStorageFile,
)

private data class RouteLibraryReconciliation(
    val files: List<PhoneOfflineStorageSemanticFile>,
    val sourceRouteCount: Int,
    val targetRouteCount: Int,
    val mergedRouteCount: Int,
    val sourceWinsCount: Int,
    val targetOnlyRouteCount: Int,
    val orphanedRoutesDropped: Int,
    val selectedRouteId: String?,
)

private data class MissionPlanReconciliation(
    val files: List<PhoneOfflineStorageSemanticFile>,
    val sourcePresent: Boolean,
    val targetPresent: Boolean,
    val identical: Boolean,
    val conflictPreserved: Boolean,
    val activeSourceChosen: Boolean,
)

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun reconcileRouteLibrary(
    sourceFiles: List<PhoneOfflineStorageFile>,
    targetFiles: List<PhoneOfflineStorageFile>,
    gson: Gson,
    recovery: RecoveryFiles,
): RouteLibraryReconciliation {
    val sourceIndex = parseRouteLibraryIndex(sourceFiles.routeIndexFile(), gson)
    val targetIndex = parseRouteLibraryIndex(targetFiles.routeIndexFile(), gson)
    if (!sourceIndex.present && !targetIndex.present) {
        return reconcileLegacyRouteFiles(sourceFiles, targetFiles, recovery).let { files ->
            RouteLibraryReconciliation(
                files = files,
                sourceRouteCount = 0,
                targetRouteCount = 0,
                mergedRouteCount = 0,
                sourceWinsCount = 0,
                targetOnlyRouteCount = 0,
                orphanedRoutesDropped = 0,
                selectedRouteId = null,
            )
        }
    }

    val sourceCandidates = validRouteCandidates(sourceIndex, sourceFiles)
    val targetCandidates = validRouteCandidates(targetIndex, targetFiles)
    val sourceById = sourceCandidates.associateBy { candidate -> candidate.route.id }
    val targetById = targetCandidates.associateBy { candidate -> candidate.route.id }
    val routeIds = (sourceById.keys + targetById.keys).toList()
    val usedStoredFileNames = mutableSetOf<String>()
    val selectedBackingFiles = mutableSetOf<String>()
    val mergedRoutes = mutableListOf<RouteLibraryRoute>()
    val files = mutableListOf<PhoneOfflineStorageSemanticFile>()
    var sourceWinsCount = 0
    var targetOnlyCount = 0

    routeIds.forEach { routeId ->
        val source = sourceById[routeId]
        val target = targetById[routeId]
        val chosen = source ?: target ?: return@forEach
        if (source != null && target != null) sourceWinsCount++
        if (source == null) targetOnlyCount++
        val storedFileName = uniqueStoredFileName(chosen.route, routeId, usedStoredFileNames)
        usedStoredFileNames += storedFileName
        mergedRoutes += chosen.route.copy(storedFileName = storedFileName)
        selectedBackingFiles += storageFileKey(chosen.backingFile)
        files +=
            PhoneOfflineStorageSemanticFile(
                relativePath = "route-library/$storedFileName",
                source = chosen.backingFile,
                decision =
                    if (source != null) {
                        PhoneOfflineStorageReconciliationDecision.COPY_SOURCE
                    } else {
                        PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_ONLY
                    },
            )
    }

    val selectedRouteId =
        sourceIndex.index
            ?.selectedRouteId
            ?.takeIf { id -> mergedRoutes.any { route -> route.id == id } }
            ?: targetIndex.index
                ?.selectedRouteId
                ?.takeIf { id -> mergedRoutes.any { route -> route.id == id } }
    val routeIndexBytes =
        gson
            .toJson(RouteLibraryStorageIndex(mergedRoutes, selectedRouteId))
            .toByteArray(StandardCharsets.UTF_8)
    files +=
        PhoneOfflineStorageSemanticFile(
            relativePath = "route-library/routes.json",
            bytes = routeIndexBytes,
            decision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
        )

    val indexedRouteCount =
        (if (sourceIndex.valid) sourceIndex.index?.routes?.size ?: 0 else 0) +
            (if (targetIndex.valid) targetIndex.index?.routes?.size ?: 0 else 0)
    val orphanedRoutesDropped = indexedRouteCount - mergedRoutes.size
    if (sourceIndex.record != null && !sourceIndex.valid) {
        recovery.add(sourceIndex.record, "ROUTE_LIBRARY", "source")
    }
    if (targetIndex.record != null && !targetIndex.valid) {
        recovery.add(targetIndex.record, "ROUTE_LIBRARY", "target")
    }
    val routeFiles = (sourceFiles.routeLibraryFiles() + targetFiles.routeLibraryFiles()).distinctBy(::storageFileKey)
    routeFiles.forEach { file ->
        if (storageFileKey(file) !in selectedBackingFiles) {
            val side =
                if (sourceFiles.any { candidate -> storageFileKey(candidate) == storageFileKey(file) }) {
                    "source"
                } else {
                    "target"
                }
            recovery.add(file, "ROUTE_LIBRARY", side)
        }
    }
    val malformedIndexFallback =
        when {
            !sourceIndex.valid && !targetIndex.present -> sourceIndex.record
            !targetIndex.valid && !sourceIndex.present -> targetIndex.record
            !sourceIndex.valid && !targetIndex.valid -> targetIndex.record ?: sourceIndex.record
            else -> null
        }
    if (malformedIndexFallback != null) {
        val fallback = malformedIndexFallback
        files.removeAll { file -> file.relativePath == "route-library/routes.json" }
        files +=
            PhoneOfflineStorageSemanticFile(
                relativePath = "route-library/routes.json",
                source = fallback,
                decision =
                    if (fallback == targetIndex.record) {
                        PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_INVALID
                    } else {
                        PhoneOfflineStorageReconciliationDecision.COPY_SOURCE
                    },
            )
    }
    return RouteLibraryReconciliation(
        files = files,
        sourceRouteCount = sourceCandidates.size,
        targetRouteCount = targetCandidates.size,
        mergedRouteCount = mergedRoutes.size,
        sourceWinsCount = sourceWinsCount,
        targetOnlyRouteCount = targetOnlyCount,
        orphanedRoutesDropped = orphanedRoutesDropped.coerceAtLeast(0),
        selectedRouteId = selectedRouteId,
    )
}

private fun reconcileLegacyRouteFiles(
    sourceFiles: List<PhoneOfflineStorageFile>,
    targetFiles: List<PhoneOfflineStorageFile>,
    recovery: RecoveryFiles,
): List<PhoneOfflineStorageSemanticFile> {
    val sourceByPath = sourceFiles.routeLibraryFiles().associateBy(PhoneOfflineStorageFile::relativePath)
    val targetByPath = targetFiles.routeLibraryFiles().associateBy(PhoneOfflineStorageFile::relativePath)
    return (sourceByPath.keys + targetByPath.keys).sorted().mapNotNull { path ->
        val result =
            PhoneOfflineStorageReconciler().reconcile(
                source = sourceByPath[path],
                target = targetByPath[path],
            )
        if (result.preserveSourceConflict) {
            recovery.add(sourceByPath[path], "ROUTE_LIBRARY", "source")
        }
        PhoneOfflineStorageSemanticFile(
            relativePath = path,
            source = result.selected,
            decision = result.decision,
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun reconcileMissionPlan(
    sourceFiles: List<PhoneOfflineStorageFile>,
    targetFiles: List<PhoneOfflineStorageFile>,
    recovery: RecoveryFiles,
): MissionPlanReconciliation {
    val source = parseMissionPlanIndex(sourceFiles.missionPlanFile())
    val target = parseMissionPlanIndex(targetFiles.missionPlanFile())
    val identical = source.valid && target.valid && source.index == target.index
    val files = mutableListOf<PhoneOfflineStorageSemanticFile>()
    val activeSourceChosen: Boolean
    val activeFile: PhoneOfflineStorageFile?
    when {
        source.hasPlan -> {
            activeSourceChosen = true
            activeFile = source.record
            if (!target.hasPlan || !identical) {
                if (target.record != null && target.hasPlan) recovery.add(target.record, "MISSION_PLAN", "target")
                if (target.record != null && !target.valid) recovery.add(target.record, "MISSION_PLAN", "target")
                if (target.record == null || !target.hasPlan) recovery.add(source.record, "MISSION_PLAN", "source")
            }
        }
        !source.valid && !target.present -> {
            activeSourceChosen = true
            activeFile = source.record
        }
        source.valid -> {
            activeSourceChosen = false
            activeFile = null
            if (target.hasPlan) recovery.add(target.record, "MISSION_PLAN", "target")
            if (target.record != null && !target.valid) recovery.add(target.record, "MISSION_PLAN", "target")
        }
        target.hasPlan -> {
            activeSourceChosen = false
            activeFile = target.record
            recovery.add(source.record, "MISSION_PLAN", "source")
        }
        target.valid -> {
            activeSourceChosen = false
            activeFile = null
            recovery.add(source.record, "MISSION_PLAN", "source")
        }
        target.record != null -> {
            activeSourceChosen = false
            activeFile = target.record
            recovery.add(source.record, "MISSION_PLAN", "source")
        }
        source.record != null -> {
            activeSourceChosen = true
            activeFile = source.record
        }
        else -> {
            activeSourceChosen = false
            activeFile = null
        }
    }
    if (activeFile != null) {
        files +=
            PhoneOfflineStorageSemanticFile(
                relativePath = "mission-plan/mission-plan.json",
                source = activeFile,
                decision =
                    if (activeSourceChosen) {
                        PhoneOfflineStorageReconciliationDecision.COPY_SOURCE
                    } else {
                        PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_VALID
                    },
            )
    }
    if (source.record != null && !source.valid && activeFile != source.record) {
        recovery.add(source.record, "MISSION_PLAN", "source")
    }
    if (target.record != null && !target.valid && activeFile != target.record) {
        recovery.add(target.record, "MISSION_PLAN", "target")
    }
    return MissionPlanReconciliation(
        files = files,
        sourcePresent = source.present,
        targetPresent = target.present,
        identical = identical,
        conflictPreserved = recovery.addedMissionPlanConflict,
        activeSourceChosen = activeSourceChosen,
    )
}

private class RecoveryFiles(
    private val sourceFiles: List<PhoneOfflineStorageFile>,
    private val targetFiles: List<PhoneOfflineStorageFile>,
    private val sourceLocation: PhoneOfflineStorageLocation,
    private val targetLocation: PhoneOfflineStorageLocation,
    private val migrationId: String,
) {
    val files = mutableListOf<PhoneOfflineStorageSemanticFile>()
    var addedMissionPlanConflict: Boolean = false
        private set
    private val knownRecoveryKeys =
        (sourceFiles + targetFiles)
            .filter { file -> file.relativePath.startsWith("$PHONE_OFFLINE_MIGRATION_CONFLICT_DIRECTORY_NAME/") }
            .mapTo(mutableSetOf()) { file -> file.relativePath to file.sha256 }
    private val addedKeys = mutableSetOf<String>()

    @Suppress("ReturnCount")
    fun add(
        file: PhoneOfflineStorageFile?,
        dataType: String,
        side: String,
    ) {
        if (file == null) return
        val recoveryKey = "$dataType:${file.sha256}"
        if (!addedKeys.add(recoveryKey) && dataType == "MISSION_PLAN") return
        if (knownRecoveryKeys.any { (_, hash) -> hash == file.sha256 }) return
        val safeId =
            migrationId
                .filter { character -> character.isLetterOrDigit() || character == '-' }
                .ifBlank { "migration" }
        val relativeOriginal = file.relativePath.replace('\\', '/')
        val originalLocation = if (side == "source") sourceLocation.name else targetLocation.name
        val directory =
            "$PHONE_OFFLINE_MIGRATION_CONFLICT_DIRECTORY_NAME/" +
                "${dataType.lowercase().replace('_', '-')}/" +
                "$safeId-$side-${file.sha256.take(16)}"
        val payloadPath = "$directory/${File(relativeOriginal).name}"
        val metadataPath = "$directory/zz-metadata.properties"
        files +=
            PhoneOfflineStorageSemanticFile(
                relativePath = payloadPath,
                source = file,
                decision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
            )
        if (dataType == "MISSION_PLAN") {
            files +=
                PhoneOfflineStorageSemanticFile(
                    relativePath = metadataPath,
                    bytes =
                        (
                            "dataType=$dataType\n" +
                                "originalLocation=$originalLocation\n" +
                                "migrationId=$safeId\n" +
                                "originalPath=$relativeOriginal\n" +
                                "sha256=${file.sha256}\n"
                        ).toByteArray(StandardCharsets.UTF_8),
                    decision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
                )
        }
        if (dataType == "MISSION_PLAN") addedMissionPlanConflict = true
    }
}

private fun parseRouteLibraryIndex(
    record: PhoneOfflineStorageFile?,
    gson: Gson,
): ParsedRouteLibraryIndex =
    if (record == null) {
        ParsedRouteLibraryIndex(record = null, index = null, valid = true)
    } else {
        runCatching {
            val index =
                record.file.reader().use { reader ->
                    gson.fromJson(reader, RouteLibraryStorageIndex::class.java)
                }
            requireNotNull(index)
            require(index.routes.all { route -> route.id.isNotBlank() && route.storedFileName.isSafeFileName() })
            ParsedRouteLibraryIndex(record, index, true)
        }.getOrDefault(ParsedRouteLibraryIndex(record, null, false))
    }

private fun parseMissionPlanIndex(record: PhoneOfflineStorageFile?): ParsedMissionPlanIndex =
    if (record == null) {
        ParsedMissionPlanIndex(record = null, index = null, valid = true)
    } else {
        runCatching {
            val index =
                record.file.reader().use { reader ->
                    Gson().fromJson(reader, MissionPlanStorageIndex::class.java)
                }
            requireNotNull(index)
            require(index.days.all { day -> day.id.isNotBlank() && day.routeId.isNotBlank() })
            ParsedMissionPlanIndex(record, index, true)
        }.getOrDefault(ParsedMissionPlanIndex(record, null, false))
    }

private fun validRouteCandidates(
    parsedIndex: ParsedRouteLibraryIndex,
    files: List<PhoneOfflineStorageFile>,
): List<RouteCandidate> {
    if (!parsedIndex.valid) return emptyList()
    val byPath = files.routeLibraryFiles().associateBy(PhoneOfflineStorageFile::relativePath)
    return parsedIndex.index
        ?.routes
        .orEmpty()
        .mapNotNull { route ->
            val storedFileName = route.storedFileName.takeIf(String::isSafeFileName) ?: return@mapNotNull null
            val backingFile = byPath["route-library/$storedFileName"] ?: return@mapNotNull null
            if (!isUsableGpx(backingFile.file)) return@mapNotNull null
            RouteCandidate(route, backingFile)
        }.distinctBy { candidate -> candidate.route.id }
}

private fun isUsableGpx(file: File): Boolean =
    file.isFile &&
        runCatching { file.inputStream().use(CompanionGpxRouteParser::parse) }
            .isSuccess

private fun uniqueStoredFileName(
    route: RouteLibraryRoute,
    routeId: String,
    usedNames: Set<String>,
): String {
    if (route.storedFileName !in usedNames) return route.storedFileName
    val extension =
        route.storedFileName
            .substringAfterLast('.', "gpx")
            .let { ".$it" }
    val safeId = routeId.replace(UNSAFE_FILE_NAME_REGEX, "_")
    return "$safeId$extension"
}

private fun String.isSafeFileName(): Boolean =
    isNotBlank() &&
        endsWith(".gpx", ignoreCase = true) &&
        File(this).name == this &&
        !contains('/') &&
        !contains('\\')

private fun storageFileKey(file: PhoneOfflineStorageFile): String = "${file.relativePath}:${file.sha256}"

internal fun isPhoneOfflineStorageMutableDataPath(relativePath: String): Boolean =
    when {
        relativePath.startsWith("route-library/") -> true
        relativePath.startsWith("mission-plan/") -> true
        else -> false
    }

private fun isRouteIndexFile(file: PhoneOfflineStorageFile): Boolean = file.relativePath == "route-library/routes.json"

private fun List<PhoneOfflineStorageFile>.routeIndexFile(): PhoneOfflineStorageFile? = firstOrNull(::isRouteIndexFile)

private fun isMissionFile(file: PhoneOfflineStorageFile): Boolean = file.relativePath == "mission-plan/mission-plan.json"

private fun List<PhoneOfflineStorageFile>.missionPlanFile(): PhoneOfflineStorageFile? = firstOrNull(::isMissionFile)

private fun List<PhoneOfflineStorageFile>.routeLibraryFiles(): List<PhoneOfflineStorageFile> =
    filter { file ->
        file.relativePath.startsWith("route-library/") && file.relativePath != "route-library/routes.json"
    }

private val UNSAFE_FILE_NAME_REGEX = "[^A-Za-z0-9._-]".toRegex()
