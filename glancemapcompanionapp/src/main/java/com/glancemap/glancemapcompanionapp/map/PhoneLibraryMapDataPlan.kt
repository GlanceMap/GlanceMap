package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadArea
import com.glancemap.trailcore.oam.OamDownloadCatalog
import java.io.File
import java.security.MessageDigest

internal const val PHONE_LOCAL_MAP_AREA_PREFIX = "local-map-"

internal fun phoneLocalMapArea(areaId: String, fileName: String): OamDownloadArea =
    OamDownloadArea(
        id = areaId,
        continent = "",
        region = File(fileName).nameWithoutExtension,
        mapSizeLabel = "",
        mapSizeBytes = 0,
        poiSizeLabel = "",
        poiSizeBytes = 0,
        notes = "",
        contourLabel = "",
        mapZipUrl = "",
        poiZipUrl = "",
    )

internal fun phoneMapDataSelection(
    map: PhoneOfflineMap,
    existingBundle: PhoneInstalledBundle?,
    includeElevation: Boolean,
    includeRouting: Boolean,
    source: PhoneOfflineDemSource,
): PhoneOfflineBundleSelection {
    val area = existingBundle?.takeIf { it.mapFileName == map.displayName }?.let { bundle ->
        OamDownloadCatalog.areas.firstOrNull { it.id == bundle.areaId }
    } ?: run {
        val digest = MessageDigest.getInstance("SHA-256").digest(map.displayName.toByteArray(Charsets.UTF_8))
        val id = PHONE_LOCAL_MAP_AREA_PREFIX + digest.joinToString("") { "%02x".format(it) }
        phoneLocalMapArea(id, map.displayName)
    }
    return PhoneOfflineBundleSelection(
        area = area,
        includeMap = false,
        includePoi = false,
        includeRouting = includeRouting,
        includeDem = includeElevation,
        demSource = source,
        includeRefugesInfo = false,
        localMapFileName = map.displayName,
    )
}
