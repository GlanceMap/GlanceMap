package com.glancemap.glancemapcompanionapp.map

import java.io.File

/** Content classes which have a stronger validation contract than a non-empty managed file. */
internal enum class PhoneOfflineStorageAssetKind {
    MAP,
    POI,
    ROUTING,
    ROUTING_PARTIAL,
    DEM,
    OTHER,
}

/** Explicitly records why migration selected one side of a managed relative path. */
internal enum class PhoneOfflineStorageReconciliationDecision {
    COPY_SOURCE,
    KEEP_TARGET_ONLY,
    REUSE_TARGET_IDENTICAL,
    KEEP_TARGET_VALID,
    REPLACE_INVALID_TARGET,
    KEEP_TARGET_INVALID,
    KEEP_LARGER_ROUTING_PARTIAL,
    PRESERVE_TARGET_CONFLICT,
}

internal data class PhoneOfflineStorageFile(
    val relativePath: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class PhoneOfflineStorageReconciliation(
    val decision: PhoneOfflineStorageReconciliationDecision,
    val selected: PhoneOfflineStorageFile,
    val preserveSourceConflict: Boolean = false,
)

/**
 * Keeps file-type validation and source/target policy outside the transactional migration loop.
 * It deliberately makes no Android or storage-location assumptions, so unit tests can exercise
 * the decisions with temporary files and a small validator substitute.
 */
internal class PhoneOfflineStorageReconciler(
    private val assetValidator: (PhoneOfflineStorageAssetKind, File) -> Boolean =
        ::isUsablePhoneOfflineStorageAsset,
) {
    fun isValid(
        kind: PhoneOfflineStorageAssetKind,
        file: File,
    ): Boolean = assetValidator(kind, file)

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "ReturnCount",
    ) // The explicit source/target decision table is safer to audit without splitting its cases.
    fun reconcile(
        source: PhoneOfflineStorageFile?,
        target: PhoneOfflineStorageFile?,
    ): PhoneOfflineStorageReconciliation {
        require(source != null || target != null)
        if (source == null) {
            return PhoneOfflineStorageReconciliation(
                decision = PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_ONLY,
                selected = checkNotNull(target),
            )
        }
        if (target == null) {
            return PhoneOfflineStorageReconciliation(
                decision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
                selected = source,
            )
        }
        if (source.sizeBytes == target.sizeBytes && source.sha256 == target.sha256) {
            return PhoneOfflineStorageReconciliation(
                decision = PhoneOfflineStorageReconciliationDecision.REUSE_TARGET_IDENTICAL,
                selected = target,
            )
        }

        val kind = phoneOfflineStorageAssetKind(source.relativePath)
        if (kind == PhoneOfflineStorageAssetKind.ROUTING_PARTIAL) {
            return if (source.sizeBytes > target.sizeBytes) {
                PhoneOfflineStorageReconciliation(
                    decision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
                    selected = source,
                )
            } else {
                PhoneOfflineStorageReconciliation(
                    decision = PhoneOfflineStorageReconciliationDecision.KEEP_LARGER_ROUTING_PARTIAL,
                    selected = target,
                )
            }
        }

        val sourceValid = isValid(kind, source.file)
        val targetValid = isValid(kind, target.file)
        return when {
            targetValid && !sourceValid ->
                PhoneOfflineStorageReconciliation(
                    decision = PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_VALID,
                    selected = target,
                )
            sourceValid && !targetValid ->
                PhoneOfflineStorageReconciliation(
                    decision = PhoneOfflineStorageReconciliationDecision.REPLACE_INVALID_TARGET,
                    selected = source,
                )
            sourceValid && targetValid ->
                PhoneOfflineStorageReconciliation(
                    decision = PhoneOfflineStorageReconciliationDecision.PRESERVE_TARGET_CONFLICT,
                    selected = target,
                    preserveSourceConflict = true,
                )
            else ->
                PhoneOfflineStorageReconciliation(
                    decision = PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_INVALID,
                    selected = target,
                    preserveSourceConflict = true,
                )
        }
    }
}

internal fun phoneOfflineStorageAssetKind(relativePath: String): PhoneOfflineStorageAssetKind {
    val normalized = relativePath.replace('\\', '/')
    val fileName = File(normalized).name
    return when {
        normalized.startsWith("maps/") && fileName.endsWith(".map", ignoreCase = true) ->
            PhoneOfflineStorageAssetKind.MAP
        normalized.startsWith("refuges-poi/") && fileName.endsWith(".poi", ignoreCase = true) ->
            PhoneOfflineStorageAssetKind.POI
        normalized.startsWith("routing-segments/") && fileName.endsWith(".rd5", ignoreCase = true) ->
            PhoneOfflineStorageAssetKind.ROUTING
        normalized.startsWith("routing-segments/") &&
            (
                fileName.endsWith(".rd5.tmp", ignoreCase = true) ||
                    fileName.endsWith(".rd5.import.part", ignoreCase = true)
            ) ->
            PhoneOfflineStorageAssetKind.ROUTING_PARTIAL
        normalized.startsWith("elevation/") -> PhoneOfflineStorageAssetKind.DEM
        else -> PhoneOfflineStorageAssetKind.OTHER
    }
}

internal fun isUsablePhoneOfflineStorageAsset(
    kind: PhoneOfflineStorageAssetKind,
    file: File,
): Boolean =
    when (kind) {
        PhoneOfflineStorageAssetKind.MAP -> isUsablePhoneOfflineMapFile(file)
        PhoneOfflineStorageAssetKind.POI -> isPhoneMapPoiFileValid(file)
        PhoneOfflineStorageAssetKind.ROUTING -> isUsablePhoneRoutingFile(file)
        PhoneOfflineStorageAssetKind.DEM -> isUsablePhoneDemFile(file)
        PhoneOfflineStorageAssetKind.ROUTING_PARTIAL,
        PhoneOfflineStorageAssetKind.OTHER,
        -> file.isFile && file.canRead() && file.length() > 0L
    }
