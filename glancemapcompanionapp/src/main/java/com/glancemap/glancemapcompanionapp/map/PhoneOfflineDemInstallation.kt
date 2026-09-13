package com.glancemap.glancemapcompanionapp.map

import java.io.File
import java.io.IOException

internal fun installPhoneDemFile(
    temporary: File,
    target: File,
) {
    val parent = target.parentFile ?: downloadFailure(PhoneOfflineBundleFailure.STORAGE)
    val backup = File(parent, ".${target.name}.previous")
    val backedUp = backupPhoneDemTarget(target, backup)
    var installed = false
    try {
        if (!temporary.renameTo(target)) downloadFailure(PhoneOfflineBundleFailure.STORAGE)
        installed = true
        if (backedUp) backup.delete()
    } catch (error: IOException) {
        restorePhoneDemInstallation(target, backup, backedUp, installed)
        throw error
    } catch (error: SecurityException) {
        restorePhoneDemInstallation(target, backup, backedUp, installed)
        throw error
    }
}

private fun backupPhoneDemTarget(
    target: File,
    backup: File,
): Boolean {
    if (!target.exists()) return false
    if (backup.exists() && !backup.delete()) downloadFailure(PhoneOfflineBundleFailure.STORAGE)
    if (!target.renameTo(backup)) downloadFailure(PhoneOfflineBundleFailure.STORAGE)
    return true
}

private fun restorePhoneDemInstallation(
    target: File,
    backup: File,
    backedUp: Boolean,
    installed: Boolean,
) {
    if (installed && target.exists()) target.delete()
    if (backedUp && backup.exists()) backup.renameTo(target)
}
