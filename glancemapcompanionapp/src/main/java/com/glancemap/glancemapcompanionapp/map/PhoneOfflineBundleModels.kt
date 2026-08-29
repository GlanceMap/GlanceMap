package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.trailcore.oam.OamDownloadArea

/** The first phone bundle is intentionally fixed to one OAM map and its matching POI database. */
internal data class PhoneOfflineBundleSelection(
    val area: OamDownloadArea,
)

/** Metadata for files installed by the phone bundle flow; it contains no remote update state. */
internal data class PhoneInstalledBundle(
    val areaId: String,
    val areaLabel: String,
    val mapFileName: String,
    val poiFileName: String,
    val installedAtMillis: Long,
)

internal enum class PhoneOfflineBundlePhase {
    DOWNLOADING_MAP,
    INSTALLING_MAP,
    DOWNLOADING_POI,
    INSTALLING_POI,
}

internal data class PhoneOfflineBundleProgress(
    val phase: PhoneOfflineBundlePhase,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
)

internal enum class PhoneOfflineBundleFailure {
    NETWORK,
    HTTP,
    STORAGE,
    ARCHIVE,
    INVALID_MAP,
    INVALID_POI,
    CANCELLED,
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

/** Companion-owned persistence for the small map+POI bundle subset only. */
internal class PhoneOfflineBundleStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun list(): List<PhoneInstalledBundle> =
        preferences
            .getStringSet(KEY_AREA_IDS, emptySet())
            .orEmpty()
            .mapNotNull(::read)
            .sortedBy { it.areaLabel.lowercase() }

    fun find(areaId: String): PhoneInstalledBundle? = read(areaId)

    fun upsert(bundle: PhoneInstalledBundle) {
        val areaIds = preferences.getStringSet(KEY_AREA_IDS, emptySet()).orEmpty() + bundle.areaId
        preferences
            .edit()
            .putStringSet(KEY_AREA_IDS, areaIds)
            .putString(key(bundle.areaId, "label"), bundle.areaLabel)
            .putString(key(bundle.areaId, "map"), bundle.mapFileName)
            .putString(key(bundle.areaId, "poi"), bundle.poiFileName)
            .putLong(key(bundle.areaId, "installed_at"), bundle.installedAtMillis)
            .apply()
    }

    private fun read(areaId: String): PhoneInstalledBundle? {
        val areaLabel = preferences.getString(key(areaId, "label"), null)
        val mapFileName = preferences.getString(key(areaId, "map"), null)
        val poiFileName = preferences.getString(key(areaId, "poi"), null)
        return if (areaLabel == null || mapFileName == null || poiFileName == null) {
            null
        } else {
            PhoneInstalledBundle(
                areaId = areaId,
                areaLabel = areaLabel,
                mapFileName = mapFileName,
                poiFileName = poiFileName,
                installedAtMillis = preferences.getLong(key(areaId, "installed_at"), 0L),
            )
        }
    }

    private fun key(
        areaId: String,
        suffix: String,
    ): String = "$areaId.$suffix"

    private companion object {
        const val PREFERENCES_NAME = "phone_oam_bundles"
        const val KEY_AREA_IDS = "area_ids"
    }
}
