package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

private const val PHONE_OFFLINE_MIGRATION_TEMPORARY_OVERHEAD_BYTES = 1L shl 20

internal enum class PhoneOfflineStorageMigrationPhase {
    IDLE,
    COPYING,
    VERIFYING,
    SWITCHING,
    CLEANUP,
    COMPLETE,
    FAILED,
}

internal fun phoneOfflineStorageMigrationPercent(
    copiedFiles: Int,
    totalFiles: Int,
): Int? =
    totalFiles.takeIf { it > 0 }?.let {
        (copiedFiles.toLong().coerceAtLeast(0L) * 100L / it.toLong())
            .toInt()
            .coerceIn(0, 100)
    }

internal data class PhoneOfflineStorageMigrationState(
    val phase: PhoneOfflineStorageMigrationPhase = PhoneOfflineStorageMigrationPhase.IDLE,
    val source: PhoneOfflineStorageLocation? = null,
    val target: PhoneOfflineStorageLocation? = null,
    val copiedFiles: Int = 0,
    val totalFiles: Int = 0,
    val reusedFiles: Int = 0,
    val replacedFiles: Int = 0,
    val requiredSpaceBytes: Long = 0L,
    val availableSpaceBytes: Long = 0L,
    val message: String? = null,
) {
    val percent: Int?
        get() = phoneOfflineStorageMigrationPercent(copiedFiles, totalFiles)
}

internal data class PhoneOfflineStorageMigrationProgress(
    val phase: PhoneOfflineStorageMigrationPhase,
    val source: PhoneOfflineStorageLocation,
    val target: PhoneOfflineStorageLocation,
    val copiedFiles: Int,
    val totalFiles: Int,
    val requiredSpaceBytes: Long,
    val availableSpaceBytes: Long,
)

internal data class PhoneOfflineStorageMigrationFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

private typealias FileEntry = PhoneOfflineStorageMigrationFileEntry

internal data class PhoneOfflineStorageMigrationSpaceRequirement(
    val totalBytes: Long,
    val stagedBytes: Long,
    val remainingBytes: Long,
    val temporaryOverheadBytes: Long,
) {
    val requiredSpaceBytes: Long
        get() = remainingBytes + temporaryOverheadBytes
}

internal fun phoneOfflineStorageMigrationSpaceRequirement(
    expectedFiles: List<PhoneOfflineStorageMigrationFileEntry>,
    stagedFiles: List<PhoneOfflineStorageMigrationFileEntry>,
    temporaryOverheadBytes: Long = PHONE_OFFLINE_MIGRATION_TEMPORARY_OVERHEAD_BYTES,
): PhoneOfflineStorageMigrationSpaceRequirement {
    val stagedByPath = stagedFiles.associateBy(PhoneOfflineStorageMigrationFileEntry::relativePath)
    val matchingExpected = expectedFiles.filter { expected -> stagedByPath[expected.relativePath] == expected }
    val stagedBytes = matchingExpected.sumOf(PhoneOfflineStorageMigrationFileEntry::sizeBytes)
    val remainingBytes =
        expectedFiles
            .filterNot { expected -> stagedByPath[expected.relativePath] == expected }
            .sumOf(PhoneOfflineStorageMigrationFileEntry::sizeBytes)
    return PhoneOfflineStorageMigrationSpaceRequirement(
        totalBytes = expectedFiles.sumOf(PhoneOfflineStorageMigrationFileEntry::sizeBytes),
        stagedBytes = stagedBytes,
        remainingBytes = remainingBytes,
        temporaryOverheadBytes = temporaryOverheadBytes.coerceAtLeast(0L).coerceAtMost(remainingBytes),
    )
}

internal enum class PhoneOfflineStorageMigrationError {
    EXTERNAL_UNAVAILABLE,
    MIGRATION_ALREADY_PENDING,
    INSUFFICIENT_SPACE,
    TARGET_NOT_EMPTY,
    COPY_FAILED,
    VERIFY_FAILED,
    SWITCH_FAILED,
    CLEANUP_FAILED,
}

internal sealed interface PhoneOfflineStorageMigrationResult {
    data class Success(
        val source: PhoneOfflineStorageLocation,
        val target: PhoneOfflineStorageLocation,
        val movedFiles: Int,
        val reusedFiles: Int = 0,
        val copiedFiles: Int = movedFiles,
        val replacedFiles: Int = 0,
    ) : PhoneOfflineStorageMigrationResult

    data class Failure(
        val error: PhoneOfflineStorageMigrationError,
        val message: String,
    ) : PhoneOfflineStorageMigrationResult
}

internal data class PhoneOfflineStorageMigrationJournal(
    val source: PhoneOfflineStorageLocation,
    val target: PhoneOfflineStorageLocation,
    val sourceRootPath: String,
    val targetRootPath: String,
    val stagingRootPath: String,
    val backupRootPath: String,
    val phase: PhoneOfflineStorageMigrationPhase,
    val manifestPath: String? = null,
)

/**
 * Moves the shared file tree only after a verified copy is ready.
 * The complete staging tree is intentional: the inactive target can be a different Android
 * storage volume, so root-level adoption would remove the rollback copy before activation was
 * verified. Valid staged entries are reused on every retry to avoid restarting interrupted work.
 * Test-only roots and journal make the transactional boundary independently testable.
 */
@Suppress(
    "LargeClass",
    "LongParameterList",
    "TooManyFunctions",
) // The migration class owns one transactional journal boundary.
internal class PhoneOfflineStorageMigration(
    private val storage: PhoneOfflineStorage?,
    private val fixedSourceRoot: File?,
    private val fixedTargetRoot: File?,
    private val fixedJournalFile: File?,
    private val fixedSourceLocation: PhoneOfflineStorageLocation?,
    private val fixedTargetLocation: PhoneOfflineStorageLocation?,
    private val reconciler: PhoneOfflineStorageReconciler,
    private val availableSpace: (File) -> Long = { directory -> directory.usableSpace },
) {
    private val mutex = Mutex()

    constructor(context: Context) :
        this(
            storage = PhoneOfflineStorage(context.applicationContext),
            fixedSourceRoot = null,
            fixedTargetRoot = null,
            fixedJournalFile = null,
            fixedSourceLocation = null,
            fixedTargetLocation = null,
            reconciler = PhoneOfflineStorageReconciler(),
        )

    internal constructor(
        sourceRoot: File,
        targetRoot: File,
        journalFile: File,
        reconciler: PhoneOfflineStorageReconciler = PhoneOfflineStorageReconciler(),
        availableSpace: (File) -> Long = { directory -> directory.usableSpace },
    ) :
        this(
            storage = null,
            fixedSourceRoot = sourceRoot,
            fixedTargetRoot = targetRoot,
            fixedJournalFile = journalFile,
            fixedSourceLocation = PhoneOfflineStorageLocation.INTERNAL,
            fixedTargetLocation = PhoneOfflineStorageLocation.EXTERNAL,
            reconciler = reconciler,
            availableSpace = availableSpace,
        )

    fun pending(): PhoneOfflineStorageMigrationJournal? = readJournal(journalFile())

    suspend fun move(
        target: PhoneOfflineStorageLocation,
        onProgress: suspend (PhoneOfflineStorageMigrationProgress) -> Unit = {},
    ): PhoneOfflineStorageMigrationResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                moveLocked(target, onProgress)
            }
        }

    @Suppress(
        "ComplexCondition",
        "CyclomaticComplexMethod",
        "LongMethod",
        "NestedBlockDepth",
        "ReturnCount",
        "TooGenericExceptionCaught",
    ) // The copy/verify/switch journal is one auditable transaction boundary.
    private suspend fun moveLocked(
        target: PhoneOfflineStorageLocation,
        onProgress: suspend (PhoneOfflineStorageMigrationProgress) -> Unit,
    ): PhoneOfflineStorageMigrationResult {
        val targetRoot =
            (fixedTargetRoot ?: storage?.canonicalRoot(target))
                ?: return PhoneOfflineStorageMigrationResult.Failure(
                    PhoneOfflineStorageMigrationError.EXTERNAL_UNAVAILABLE,
                    "The selected storage is unavailable.",
                )
        val pending = readJournal(journalFile())
        if (pending != null && pending.target != target) {
            return PhoneOfflineStorageMigrationResult.Failure(
                PhoneOfflineStorageMigrationError.MIGRATION_ALREADY_PENDING,
                "Finish the pending move to ${pending.target.label} first.",
            )
        }
        val sourceLocation = pending?.source ?: fixedSourceLocation ?: checkNotNull(storage).location()
        val sourceRoot = pending?.let(::sourceRoot) ?: fixedSourceRoot ?: checkNotNull(storage).activeRoot()
        if (sourceRoot.absoluteFile == targetRoot.absoluteFile && pending == null) {
            storage?.setLocation(target)
            return PhoneOfflineStorageMigrationResult.Success(sourceLocation, target, 0)
        }

        var journal =
            pending ?: PhoneOfflineStorageMigrationJournal(
                source = sourceLocation,
                target = target,
                sourceRootPath = sourceRoot.absolutePath,
                targetRootPath = targetRoot.absolutePath,
                stagingRootPath = File(targetRoot.parentFile, ".GlanceMap-migration-${UUID.randomUUID()}").absolutePath,
                backupRootPath = File(targetRoot.parentFile, ".GlanceMap-backup-${UUID.randomUUID()}").absolutePath,
                phase = PhoneOfflineStorageMigrationPhase.COPYING,
            )
        val stagingRoot = File(journal.stagingRootPath)
        val finalTargetRoot = File(journal.targetRootPath)
        if (journal.phase == PhoneOfflineStorageMigrationPhase.SWITCHING ||
            journal.phase == PhoneOfflineStorageMigrationPhase.CLEANUP
        ) {
            return try {
                recoverInterruptedSwitch(journal, onProgress)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                failure(
                    journal,
                    PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                    error.message ?: "The interrupted storage switch could not be recovered.",
                )
            }
        }
        val hashCache = mutableMapOf<String, String>()
        val validityCache = mutableMapOf<String, Boolean>()
        val sourceFiles = managedFiles(sourceRoot, hashCache)
        val targetFiles = allFiles(finalTargetRoot, hashCache)
        val reconciliation =
            reconcileFiles(
                sourceFiles = sourceFiles.filterNot { file -> isPhoneOfflineStorageMutableDataPath(file.relativePath) },
                targetFiles = targetFiles.filterNot { file -> isPhoneOfflineStorageMutableDataPath(file.relativePath) },
                targetRoot = finalTargetRoot,
                reconciler =
                    PhoneOfflineStorageReconciler { kind, file ->
                        validityCache.getOrPut(file.absolutePath) {
                            reconciler.isValid(kind, file)
                        }
                    },
            )
        val mutableReconciliation =
            reconcilePhoneOfflineMutableData(
                sourceFiles = sourceFiles,
                targetFiles =
                    targetFiles.map { entry ->
                        PhoneOfflineStorageFile(
                            relativePath = entry.relativePath,
                            file = File(finalTargetRoot, entry.relativePath),
                            sizeBytes = entry.sizeBytes,
                            sha256 = entry.sha256,
                        )
                    },
                sourceLocation = journal.source,
                targetLocation = journal.target,
                migrationId = File(journal.stagingRootPath).name.removePrefix(".GlanceMap-migration-"),
            )
        PhoneDownloadDiagnostics.log(
            "StorageMigration",
            "RouteLibrary sourceRouteCount=${mutableReconciliation.sourceRouteCount} " +
                "targetRouteCount=${mutableReconciliation.targetRouteCount} " +
                "mergedRouteCount=${mutableReconciliation.mergedRouteCount} " +
                "sourceWins=${mutableReconciliation.sourceWinsCount} " +
                "targetOnly=${mutableReconciliation.targetOnlyRouteCount} " +
                "orphanedDropped=${mutableReconciliation.orphanedRoutesDropped} " +
                "selected=${mutableReconciliation.selectedRouteId != null}",
        )
        PhoneDownloadDiagnostics.log(
            "StorageMigration",
            "MissionPlan sourcePresent=${mutableReconciliation.missionSourcePresent} " +
                "targetPresent=${mutableReconciliation.missionTargetPresent} " +
                "identical=${mutableReconciliation.missionIdentical} " +
                "conflictPreserved=${mutableReconciliation.missionConflictPreserved} " +
                "activeSourceChosen=${mutableReconciliation.missionActiveSourceChosen}",
        )
        val reconciledFiles =
            (
                reconciliation.files +
                    mutableReconciliation.files.map { semanticFile ->
                        ReconciledFile(
                            entry =
                                FileEntry(
                                    relativePath = semanticFile.relativePath,
                                    sizeBytes =
                                        semanticFile.bytes?.size?.toLong()
                                            ?: checkNotNull(semanticFile.source).sizeBytes,
                                    sha256 =
                                        semanticFile.bytes?.let(::sha256)
                                            ?: checkNotNull(semanticFile.source).sha256,
                                ),
                            source = semanticFile.source,
                            bytes = semanticFile.bytes,
                            decision = semanticFile.decision,
                        )
                    }
            ).sortedBy { file -> file.entry.relativePath }
        val expectedFiles = reconciledFiles.map(ReconciledFile::entry)
        val targetParent = finalTargetRoot.parentFile
        if (targetParent == null || (!targetParent.exists() && !targetParent.mkdirs())) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                "The selected storage directory cannot be created.",
            )
        }
        if (!pruneStaging(File(journal.stagingRootPath), expectedFiles)) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.COPY_FAILED,
                "Obsolete migration staging files could not be removed.",
            )
        }
        val stagedFiles = expectedFiles.filter { entry -> matches(File(stagingRoot, entry.relativePath), entry) }
        val spaceRequirement =
            phoneOfflineStorageMigrationSpaceRequirement(
                expectedFiles = expectedFiles,
                stagedFiles = stagedFiles,
            )
        val availableBytes = availableSpace(targetParent)
        PhoneDownloadDiagnostics.log(
            "StorageMigration",
            "phase=${journal.phase.name.lowercase()} staged=${spaceRequirement.stagedBytes} " +
                "remaining=${spaceRequirement.remainingBytes} required=${spaceRequirement.requiredSpaceBytes} " +
                "available=$availableBytes",
        )
        if (availableBytes < spaceRequirement.requiredSpaceBytes &&
            journal.phase == PhoneOfflineStorageMigrationPhase.COPYING
        ) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.INSUFFICIENT_SPACE,
                "Not enough free space: ${spaceRequirement.requiredSpaceBytes} bytes required " +
                    "for the remaining files, $availableBytes available.",
            )
        }

        return try {
            if (journal.phase == PhoneOfflineStorageMigrationPhase.VERIFYING &&
                !matchesRoot(stagingRoot, expectedFiles)
            ) {
                // A failed verification keeps its semantic phase in the journal, but a retry
                // must recopy entries which did not pass the manifest check.
                journal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.COPYING)
                writeJournal(journalFile(), journal)
                if (availableBytes < spaceRequirement.requiredSpaceBytes) {
                    return failure(
                        journal,
                        PhoneOfflineStorageMigrationError.INSUFFICIENT_SPACE,
                        "Not enough free space: ${spaceRequirement.requiredSpaceBytes} bytes required " +
                            "for the remaining files, $availableBytes available.",
                    )
                }
            }
            if (journal.phase == PhoneOfflineStorageMigrationPhase.COPYING) {
                writeJournal(journalFile(), journal)
                if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
                    return failure(
                        journal,
                        PhoneOfflineStorageMigrationError.COPY_FAILED,
                        "The migration staging directory cannot be created.",
                    )
                }
                onProgress(
                    PhoneOfflineStorageMigrationProgress(
                        phase = PhoneOfflineStorageMigrationPhase.COPYING,
                        source = journal.source,
                        target = journal.target,
                        copiedFiles = stagedFiles.size,
                        totalFiles = expectedFiles.size,
                        requiredSpaceBytes = spaceRequirement.requiredSpaceBytes,
                        availableSpaceBytes = availableBytes,
                    ),
                )
                var copiedFiles = stagedFiles.size
                reconciledFiles.forEach { reconciledFile ->
                    currentCoroutineContext().ensureActive()
                    val entry = reconciledFile.entry
                    val stagingFile = File(stagingRoot, entry.relativePath)
                    val wasAlreadyStaged = matches(stagingFile, entry)
                    if (!wasAlreadyStaged) {
                        if (reconciledFile.bytes != null) {
                            writeBytes(reconciledFile.bytes, stagingFile)
                        } else {
                            copyFile(checkNotNull(reconciledFile.source).file, stagingFile)
                        }
                    }
                    if (!matches(stagingFile, entry)) {
                        return failure(
                            journal,
                            PhoneOfflineStorageMigrationError.COPY_FAILED,
                            "The copied file could not be verified: ${entry.relativePath}",
                        )
                    }
                    if (!wasAlreadyStaged) copiedFiles += 1
                    val currentStagedFiles =
                        expectedFiles.filter { expected ->
                            matches(File(stagingRoot, expected.relativePath), expected)
                        }
                    val currentSpaceRequirement =
                        phoneOfflineStorageMigrationSpaceRequirement(
                            expectedFiles = expectedFiles,
                            stagedFiles = currentStagedFiles,
                        )
                    onProgress(
                        PhoneOfflineStorageMigrationProgress(
                            phase = PhoneOfflineStorageMigrationPhase.COPYING,
                            source = journal.source,
                            target = journal.target,
                            copiedFiles = copiedFiles,
                            totalFiles = expectedFiles.size,
                            requiredSpaceBytes = currentSpaceRequirement.requiredSpaceBytes,
                            availableSpaceBytes = availableSpace(targetParent),
                        ),
                    )
                }
                journal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.VERIFYING)
                writeJournal(journalFile(), journal)
            }

            val verified = allFiles(stagingRoot)
            if (verified != expectedFiles) {
                return failure(
                    journal,
                    PhoneOfflineStorageMigrationError.VERIFY_FAILED,
                    "The staged GlanceMap data did not pass integrity verification.",
                )
            }
            onProgress(
                PhoneOfflineStorageMigrationProgress(
                    phase = PhoneOfflineStorageMigrationPhase.VERIFYING,
                    source = journal.source,
                    target = journal.target,
                    copiedFiles = verified.size,
                    totalFiles = expectedFiles.size,
                    requiredSpaceBytes = 0L,
                    availableSpaceBytes = availableSpace(targetParent),
                ),
            )

            if (journal.manifestPath == null) {
                journal =
                    journal.copy(
                        manifestPath =
                            File(
                                targetParent,
                                ".GlanceMap-migration-${UUID.randomUUID()}.manifest",
                            ).absolutePath,
                    )
            }
            writeManifest(File(checkNotNull(journal.manifestPath)), expectedFiles)
            val switchingJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.SWITCHING)
            writeJournal(journalFile(), switchingJournal)
            journal = switchingJournal
            promoteVerifiedStaging(
                journal = switchingJournal,
                expectedFiles = expectedFiles,
                onProgress = onProgress,
                reusedFiles =
                    reconciledFiles.count { file ->
                        file.decision in
                            setOf(
                                PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_ONLY,
                                PhoneOfflineStorageReconciliationDecision.REUSE_TARGET_IDENTICAL,
                                PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_VALID,
                                PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_INVALID,
                                PhoneOfflineStorageReconciliationDecision.KEEP_LARGER_ROUTING_PARTIAL,
                                PhoneOfflineStorageReconciliationDecision.PRESERVE_TARGET_CONFLICT,
                            )
                    },
                copiedFiles =
                    reconciledFiles.count { file ->
                        file.decision in
                            setOf(
                                PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
                                PhoneOfflineStorageReconciliationDecision.REPLACE_INVALID_TARGET,
                            )
                    },
                replacedFiles =
                    reconciledFiles.count { file ->
                        file.decision == PhoneOfflineStorageReconciliationDecision.REPLACE_INVALID_TARGET
                    },
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            failure(
                journal = journal,
                error = PhoneOfflineStorageMigrationError.COPY_FAILED,
                message = error.message ?: "The GlanceMap data move failed.",
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Recovery enumerates each crash-visible root combination.
    private suspend fun recoverInterruptedSwitch(
        journal: PhoneOfflineStorageMigrationJournal,
        onProgress: suspend (PhoneOfflineStorageMigrationProgress) -> Unit,
    ): PhoneOfflineStorageMigrationResult {
        val finalTargetRoot = File(journal.targetRootPath)
        val stagingRoot = File(journal.stagingRootPath)
        val backupRoot = File(journal.backupRootPath)
        val expectedFiles = journal.manifestPath?.let { readManifest(File(it)) }
        PhoneDownloadDiagnostics.log(
            "StorageMigration",
            "recover phase=${journal.phase.name.lowercase()} target=${finalTargetRoot.isDirectory} " +
                "staging=${stagingRoot.exists()} backup=${backupRoot.exists()} " +
                "manifest=${expectedFiles != null}",
        )
        if (expectedFiles == null) {
            return recoverLegacySwitch(journal)
        }

        val finalValid = matchesRoot(finalTargetRoot, expectedFiles)
        val stagingValid = matchesRoot(stagingRoot, expectedFiles)
        return when {
            finalValid && !stagingRoot.exists() -> {
                PhoneDownloadDiagnostics.log("StorageMigration", "recovery action=finish_cleanup")
                val cleanupJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
                writeJournal(journalFile(), cleanupJournal)
                finishCleanup(cleanupJournal, expectedFiles.size, expectedFiles = expectedFiles)
            }
            finalValid && stagingValid -> {
                PhoneDownloadDiagnostics.log("StorageMigration", "recovery action=discard_verified_duplicate")
                if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                        "The duplicate migration staging directory could not be removed.",
                    )
                } else {
                    val cleanupJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
                    writeJournal(journalFile(), cleanupJournal)
                    finishCleanup(cleanupJournal, expectedFiles.size, expectedFiles = expectedFiles)
                }
            }
            stagingValid -> {
                PhoneDownloadDiagnostics.log("StorageMigration", "recovery action=promote_verified_staging")
                promoteVerifiedStaging(
                    journal = journal,
                    expectedFiles = expectedFiles,
                    onProgress = onProgress,
                )
            }
            !finalTargetRoot.exists() && backupRoot.exists() -> {
                PhoneDownloadDiagnostics.warn("StorageMigration", "recovery action=restore_backup")
                restoreBackupForRetry(journal, finalTargetRoot, stagingRoot, backupRoot)
            }
            finalValid -> {
                PhoneDownloadDiagnostics.log("StorageMigration", "recovery action=discard_invalid_staging")
                if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                        "The invalid migration staging directory could not be removed.",
                    )
                } else {
                    val cleanupJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
                    writeJournal(journalFile(), cleanupJournal)
                    finishCleanup(cleanupJournal, expectedFiles.size, expectedFiles = expectedFiles)
                }
            }
            else ->
                failure(
                    journal,
                    PhoneOfflineStorageMigrationError.VERIFY_FAILED,
                    "The migration staging data is incomplete and the target is not verified.",
                    recoveryPhase = PhoneOfflineStorageMigrationPhase.COPYING,
                )
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Legacy journals need an explicit crash-state decision table.
    private fun recoverLegacySwitch(
        journal: PhoneOfflineStorageMigrationJournal,
    ): PhoneOfflineStorageMigrationResult {
        val finalTargetRoot = File(journal.targetRootPath)
        val stagingRoot = File(journal.stagingRootPath)
        val backupRoot = File(journal.backupRootPath)
        return when {
            finalTargetRoot.isDirectory && !stagingRoot.exists() && backupRoot.isDirectory -> {
                PhoneDownloadDiagnostics.log("StorageMigration", "legacy recovery action=finish_cleanup")
                val cleanupJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
                writeJournal(journalFile(), cleanupJournal)
                finishCleanup(cleanupJournal, allFiles(finalTargetRoot).size)
            }
            journal.phase == PhoneOfflineStorageMigrationPhase.CLEANUP &&
                finalTargetRoot.isDirectory &&
                !stagingRoot.exists() &&
                !backupRoot.exists() -> {
                PhoneDownloadDiagnostics.warn(
                    "StorageMigration",
                    "legacy recovery action=finish_cleanup_without_manifest",
                )
                finishCleanup(journal, allFiles(finalTargetRoot).size)
            }
            finalTargetRoot.isDirectory && stagingRoot.exists() && !backupRoot.exists() -> {
                PhoneDownloadDiagnostics.warn("StorageMigration", "legacy recovery action=discard_unverified_staging")
                if (!stagingRoot.deleteRecursively()) {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                        "The unverified migration staging directory could not be removed.",
                    )
                } else {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                        "The unverified migration was kept inactive and can be retried.",
                        recoveryPhase = PhoneOfflineStorageMigrationPhase.COPYING,
                    )
                }
            }
            !finalTargetRoot.exists() && backupRoot.exists() -> {
                if (!backupRoot.renameTo(finalTargetRoot)) {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                        "The previous target directory could not be restored.",
                    )
                } else {
                    stagingRoot.deleteRecursively()
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                        "The migration was restored safely and can be retried.",
                        recoveryPhase = PhoneOfflineStorageMigrationPhase.COPYING,
                    )
                }
            }
            !finalTargetRoot.exists() && !backupRoot.exists() -> {
                PhoneDownloadDiagnostics.warn("StorageMigration", "legacy recovery action=restart_copy")
                if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                        "The unverified migration staging directory could not be removed.",
                    )
                } else {
                    failure(
                        journal,
                        PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                        "The incomplete migration was kept inactive and can be retried.",
                        recoveryPhase = PhoneOfflineStorageMigrationPhase.COPYING,
                    )
                }
            }
            else ->
                failure(
                    journal,
                    PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                    "The interrupted storage switch cannot be verified yet.",
                )
        }
    }

    @Suppress("ReturnCount") // Each failure preserves a distinct on-disk recovery phase.
    private fun restoreBackupForRetry(
        journal: PhoneOfflineStorageMigrationJournal,
        finalTargetRoot: File,
        stagingRoot: File,
        backupRoot: File,
    ): PhoneOfflineStorageMigrationResult.Failure {
        if (!backupRoot.renameTo(finalTargetRoot)) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                "The previous target directory could not be restored.",
            )
        }
        if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                "The invalid migration staging directory could not be removed.",
            )
        }
        val retryJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.COPYING)
        writeJournal(journalFile(), retryJournal)
        return PhoneOfflineStorageMigrationResult.Failure(
            PhoneOfflineStorageMigrationError.SWITCH_FAILED,
            "The incomplete migration was rolled back safely and can be retried.",
        )
    }

    @Suppress("ReturnCount") // Each switch failure leaves the rollback journal actionable.
    private suspend fun promoteVerifiedStaging(
        journal: PhoneOfflineStorageMigrationJournal,
        expectedFiles: List<FileEntry>,
        onProgress: suspend (PhoneOfflineStorageMigrationProgress) -> Unit,
        reusedFiles: Int = 0,
        copiedFiles: Int = expectedFiles.size,
        replacedFiles: Int = 0,
    ): PhoneOfflineStorageMigrationResult {
        val stagingRoot = File(journal.stagingRootPath)
        val finalTargetRoot = File(journal.targetRootPath)
        val backupRoot = File(journal.backupRootPath)
        if (finalTargetRoot.exists() && !backupRoot.exists() && !finalTargetRoot.renameTo(backupRoot)) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                "The existing target directory could not be preserved.",
            )
        }
        if (finalTargetRoot.exists() && backupRoot.exists()) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                "The existing target directory could not be disambiguated safely.",
            )
        }
        if (!stagingRoot.renameTo(finalTargetRoot)) {
            if (backupRoot.exists() && !finalTargetRoot.exists()) backupRoot.renameTo(finalTargetRoot)
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                "The verified GlanceMap directory could not be activated.",
            )
        }
        val cleanupJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
        writeJournal(journalFile(), cleanupJournal)
        onProgress(
            PhoneOfflineStorageMigrationProgress(
                phase = PhoneOfflineStorageMigrationPhase.CLEANUP,
                source = journal.source,
                target = journal.target,
                copiedFiles = expectedFiles.size,
                totalFiles = expectedFiles.size,
                requiredSpaceBytes = 0L,
                availableSpaceBytes = finalTargetRoot.parentFile?.let(availableSpace) ?: 0L,
            ),
        )
        return finishCleanup(
            journal = cleanupJournal,
            movedFiles = expectedFiles.size,
            reusedFiles = reusedFiles,
            copiedFiles = copiedFiles,
            replacedFiles = replacedFiles,
            expectedFiles = expectedFiles,
        )
    }

    private fun sourceRoot(journal: PhoneOfflineStorageMigrationJournal): File = File(journal.sourceRootPath)

    private fun journalFile(): File = fixedJournalFile ?: checkNotNull(storage).migrationJournalFile()

    private fun setActiveLocation(target: PhoneOfflineStorageLocation) {
        storage?.setLocation(fixedTargetLocation ?: target)
    }

    @Suppress("ReturnCount") // Cleanup failures must preserve the exact retry state.
    private fun finishCleanup(
        journal: PhoneOfflineStorageMigrationJournal,
        movedFiles: Int,
        reusedFiles: Int = 0,
        copiedFiles: Int = movedFiles,
        replacedFiles: Int = 0,
        expectedFiles: List<FileEntry>? = null,
    ): PhoneOfflineStorageMigrationResult {
        val finalTargetRoot = File(journal.targetRootPath)
        if (expectedFiles != null && !matchesRoot(finalTargetRoot, expectedFiles)) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.VERIFY_FAILED,
                "The active GlanceMap directory did not pass integrity verification.",
                recoveryPhase = PhoneOfflineStorageMigrationPhase.CLEANUP,
            )
        }
        setActiveLocation(journal.target)
        if (!deleteManagedData(sourceRoot(journal)) || !deleteMigrationBackup(File(journal.backupRootPath))) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                "The new storage is active, but the old data could not be deleted yet.",
            )
        }
        if (journal.manifestPath != null && !deleteMigrationManifest(File(journal.manifestPath))) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                "The migration manifest could not be removed yet.",
            )
        }
        journalFile().delete()
        return PhoneOfflineStorageMigrationResult.Success(
            source = journal.source,
            target = journal.target,
            movedFiles = movedFiles,
            reusedFiles = reusedFiles,
            copiedFiles = copiedFiles,
            replacedFiles = replacedFiles,
        )
    }

    private fun failure(
        journal: PhoneOfflineStorageMigrationJournal,
        error: PhoneOfflineStorageMigrationError,
        message: String,
        recoveryPhase: PhoneOfflineStorageMigrationPhase? = null,
    ): PhoneOfflineStorageMigrationResult.Failure {
        val phase =
            recoveryPhase
                ?: when (error) {
                    PhoneOfflineStorageMigrationError.CLEANUP_FAILED -> PhoneOfflineStorageMigrationPhase.CLEANUP
                    PhoneOfflineStorageMigrationError.VERIFY_FAILED ->
                        if (journal.phase == PhoneOfflineStorageMigrationPhase.COPYING) {
                            PhoneOfflineStorageMigrationPhase.COPYING
                        } else {
                            PhoneOfflineStorageMigrationPhase.VERIFYING
                        }
                    PhoneOfflineStorageMigrationError.SWITCH_FAILED -> PhoneOfflineStorageMigrationPhase.SWITCHING
                    else ->
                        when (journal.phase) {
                            PhoneOfflineStorageMigrationPhase.SWITCHING -> PhoneOfflineStorageMigrationPhase.SWITCHING
                            PhoneOfflineStorageMigrationPhase.CLEANUP -> PhoneOfflineStorageMigrationPhase.CLEANUP
                            else -> PhoneOfflineStorageMigrationPhase.COPYING
                        }
                }
        writeJournal(
            journalFile(),
            journal.copy(phase = phase),
        )
        return PhoneOfflineStorageMigrationResult.Failure(error, message)
    }

    @Suppress("TooManyFunctions") // Journal, hash, copy, and cleanup helpers are one transaction boundary.
    private companion object {
        fun managedFiles(
            root: File,
            hashCache: MutableMap<String, String> = mutableMapOf(),
        ): List<PhoneOfflineStorageFile> =
            migrationDirectoryNames
                .asSequence()
                .map { name -> File(root, name) }
                .flatMap { directory -> directory.walkTopDown().filter(File::isFile) }
                .map { file ->
                    PhoneOfflineStorageFile(
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        file = file,
                        sizeBytes = file.length(),
                        sha256 = hashCache.getOrPut(file.absolutePath) { sha256(file) },
                    )
                }.sortedBy(PhoneOfflineStorageFile::relativePath)
                .toList()

        fun allFiles(
            root: File,
            hashCache: MutableMap<String, String> = mutableMapOf(),
        ): List<FileEntry> =
            if (!root.exists()) {
                emptyList()
            } else {
                root
                    .walkTopDown()
                    .filter(File::isFile)
                    .map { file ->
                        FileEntry(
                            relativePath = file.relativeTo(root).invariantSeparatorsPath,
                            sizeBytes = file.length(),
                            sha256 = hashCache.getOrPut(file.absolutePath) { sha256(file) },
                        )
                    }.sortedBy(FileEntry::relativePath)
                    .toList()
            }

        fun matches(
            file: File,
            expected: FileEntry,
        ): Boolean =
            file.isFile &&
                file.length() == expected.sizeBytes &&
                runCatching { sha256(file) == expected.sha256 }.getOrDefault(false)

        fun matchesRoot(
            root: File,
            expected: List<FileEntry>,
        ): Boolean = root.isDirectory && allFiles(root) == expected

        fun pruneStaging(
            root: File,
            expected: List<FileEntry>,
        ): Boolean {
            if (!root.exists()) return true
            val expectedPaths = expected.mapTo(hashSetOf(), FileEntry::relativePath)
            return root
                .walkTopDown()
                .filter(File::isFile)
                .toList()
                .filterNot { file -> file.relativeTo(root).invariantSeparatorsPath in expectedPaths }
                .all(File::delete)
        }

        fun copyFile(
            source: File,
            target: File,
        ) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.part")
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not promote ${source.name}." }
        }

        fun writeBytes(
            bytes: ByteArray,
            target: File,
        ) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.part")
            FileOutputStream(temporary).use { output -> output.write(bytes) }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not promote ${target.name}." }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }

        fun deleteManagedData(root: File): Boolean =
            migrationDirectoryNames.all { name ->
                val directory = File(root, name)
                !directory.exists() || directory.deleteRecursively()
            }

        fun deleteMigrationBackup(root: File): Boolean = !root.exists() || root.deleteRecursively()

        fun deleteMigrationManifest(file: File): Boolean = !file.exists() || file.delete()

        fun writeManifest(
            file: File,
            entries: List<FileEntry>,
        ) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val properties =
                Properties().apply {
                    setProperty("count", entries.size.toString())
                    entries.forEachIndexed { index, entry ->
                        setProperty("entry.$index.path", entry.relativePath)
                        setProperty("entry.$index.size", entry.sizeBytes.toString())
                        setProperty("entry.$index.sha256", entry.sha256)
                    }
                }
            FileOutputStream(temporary).use { output ->
                properties.store(output, "GlanceMap storage migration manifest")
            }
            if (file.exists()) file.delete()
            check(temporary.renameTo(file)) { "Could not persist the storage migration manifest." }
        }

        fun readManifest(file: File): List<FileEntry>? =
            runCatching {
                if (!file.isFile) return@runCatching null
                val properties = Properties()
                file.inputStream().use(properties::load)
                val count = properties.getProperty("count")?.toIntOrNull() ?: return@runCatching null
                if (count < 0) return@runCatching null
                (0 until count)
                    .map { index ->
                        FileEntry(
                            relativePath = requireNotNull(properties.getProperty("entry.$index.path")),
                            sizeBytes = requireNotNull(properties.getProperty("entry.$index.size")).toLong(),
                            sha256 = requireNotNull(properties.getProperty("entry.$index.sha256")),
                        )
                    }.sortedBy(FileEntry::relativePath)
            }.getOrNull()

        fun writeJournal(
            file: File,
            journal: PhoneOfflineStorageMigrationJournal,
        ) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val properties =
                Properties().apply {
                    setProperty("source", journal.source.name)
                    setProperty("target", journal.target.name)
                    setProperty("sourceRoot", journal.sourceRootPath)
                    setProperty("targetRoot", journal.targetRootPath)
                    setProperty("stagingRoot", journal.stagingRootPath)
                    setProperty("backupRoot", journal.backupRootPath)
                    setProperty("phase", journal.phase.name)
                    journal.manifestPath?.let { path -> setProperty("manifestPath", path) }
                }
            FileOutputStream(temporary).use { output -> properties.store(output, "GlanceMap storage migration") }
            if (file.exists()) file.delete()
            check(temporary.renameTo(file)) { "Could not persist the storage migration journal." }
        }

        fun readJournal(file: File): PhoneOfflineStorageMigrationJournal? =
            runCatching {
                if (!file.isFile) return@runCatching null
                val properties = Properties()
                file.inputStream().use(properties::load)
                PhoneOfflineStorageMigrationJournal(
                    source = PhoneOfflineStorageLocation.valueOf(properties.getProperty("source")),
                    target = PhoneOfflineStorageLocation.valueOf(properties.getProperty("target")),
                    sourceRootPath = properties.getProperty("sourceRoot"),
                    targetRootPath = properties.getProperty("targetRoot"),
                    stagingRootPath = properties.getProperty("stagingRoot"),
                    backupRootPath = properties.getProperty("backupRoot"),
                    phase = PhoneOfflineStorageMigrationPhase.valueOf(properties.getProperty("phase")),
                    manifestPath = properties.getProperty("manifestPath"),
                )
            }.getOrNull()

        @Suppress("LongMethod") // Reconciliation must be assembled in one deterministic relative-path pass.
        private fun reconcileFiles(
            sourceFiles: List<PhoneOfflineStorageFile>,
            targetFiles: List<FileEntry>,
            targetRoot: File,
            reconciler: PhoneOfflineStorageReconciler,
        ): ReconciliationSummary {
            val sourceByPath = sourceFiles.associateBy(PhoneOfflineStorageFile::relativePath)
            val targetByPath =
                targetFiles.associate { entry ->
                    entry.relativePath to
                        PhoneOfflineStorageFile(
                            relativePath = entry.relativePath,
                            file = File(targetRoot, entry.relativePath),
                            sizeBytes = entry.sizeBytes,
                            sha256 = entry.sha256,
                        )
                }
            val routingFiles =
                (sourceFiles + targetByPath.values).filter { file ->
                    phoneOfflineStorageAssetKind(file.relativePath) == PhoneOfflineStorageAssetKind.ROUTING
                }
            val validRoutingFinalPaths =
                routingFiles
                    .filter { file ->
                        reconciler.isValid(PhoneOfflineStorageAssetKind.ROUTING, file.file)
                    }.mapTo(mutableSetOf(), PhoneOfflineStorageFile::relativePath)
            val reconciled = mutableListOf<ReconciledFile>()
            (sourceByPath.keys + targetByPath.keys)
                .sorted()
                .filterNot { path ->
                    phoneOfflineStorageAssetKind(path) == PhoneOfflineStorageAssetKind.ROUTING_PARTIAL &&
                        routingFinalPathForPartial(path) in validRoutingFinalPaths
                }.forEach { path ->
                    val source = sourceByPath[path]
                    val target = targetByPath[path]
                    val result = reconciler.reconcile(source, target)
                    PhoneDownloadDiagnostics.log(
                        "StorageMigration",
                        "decision=${result.decision.name.lowercase()} " +
                            "preserveSourceConflict=${result.preserveSourceConflict} file=$path",
                    )
                    reconciled +=
                        ReconciledFile(
                            entry =
                                FileEntry(
                                    result.selected.relativePath,
                                    result.selected.sizeBytes,
                                    result.selected.sha256,
                                ),
                            source = result.selected,
                            decision = result.decision,
                        )
                    if (result.preserveSourceConflict && source != null) {
                        reconciled +=
                            ReconciledFile(
                                entry =
                                    FileEntry(
                                        relativePath =
                                            "$PHONE_OFFLINE_MIGRATION_CONFLICT_DIRECTORY_NAME/" +
                                                "${source.relativePath}.source-${source.sha256.take(12)}",
                                        sizeBytes = source.sizeBytes,
                                        sha256 = source.sha256,
                                    ),
                                source = source,
                                decision = PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
                            )
                    }
                }
            return ReconciliationSummary(
                files = reconciled.sortedBy { file -> file.entry.relativePath },
                reusedFiles =
                    reconciled.count { file ->
                        file.decision in
                            setOf(
                                PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_ONLY,
                                PhoneOfflineStorageReconciliationDecision.REUSE_TARGET_IDENTICAL,
                                PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_VALID,
                                PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_INVALID,
                                PhoneOfflineStorageReconciliationDecision.KEEP_LARGER_ROUTING_PARTIAL,
                                PhoneOfflineStorageReconciliationDecision.PRESERVE_TARGET_CONFLICT,
                            )
                    },
                copiedFiles =
                    reconciled.count { file ->
                        file.decision in
                            setOf(
                                PhoneOfflineStorageReconciliationDecision.COPY_SOURCE,
                                PhoneOfflineStorageReconciliationDecision.REPLACE_INVALID_TARGET,
                            )
                    },
                replacedFiles =
                    reconciled.count { file ->
                        file.decision == PhoneOfflineStorageReconciliationDecision.REPLACE_INVALID_TARGET
                    },
            )
        }

        private fun routingFinalPathForPartial(path: String): String? =
            when {
                path.endsWith(".rd5.tmp", ignoreCase = true) -> path.removeSuffix(".tmp")
                path.endsWith(".rd5.import.part", ignoreCase = true) -> path.removeSuffix(".import.part")
                else -> null
            }

        private val migrationDirectoryNames =
            PHONE_OFFLINE_MANAGED_DIRECTORY_NAMES + PHONE_OFFLINE_MIGRATION_CONFLICT_DIRECTORY_NAME

        private data class ReconciledFile(
            val entry: FileEntry,
            val source: PhoneOfflineStorageFile?,
            val bytes: ByteArray? = null,
            val decision: PhoneOfflineStorageReconciliationDecision,
        ) {
            init {
                require(source != null || bytes != null)
            }
        }

        private data class ReconciliationSummary(
            val files: List<ReconciledFile>,
            val reusedFiles: Int,
            val copiedFiles: Int,
            val replacedFiles: Int,
        )
    }
}
