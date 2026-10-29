@file:Suppress("LongParameterList")

package com.glancemap.glancemapwearos.core.maps

import android.content.Context
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import java.io.File
import java.util.Locale

enum class DemSource(
    val id: String,
    @StringRes val displayNameRes: Int,
    @StringRes val shortLabelRes: Int,
    @StringRes val detailLabelRes: Int,
    val rootDirName: String,
    val baseUrl: String,
    val remoteExtension: String,
) {
    MAPSFORGE_DEM3(
        id = "mapsforge_dem3",
        displayNameRes = R.string.dem_standard_terrain,
        shortLabelRes = R.string.dem_standard,
        detailLabelRes = R.string.dem_smaller_files_good_for_most_maps,
        rootDirName = "dem3",
        baseUrl = "https://download.mapsforge.org/maps/dem/dem3",
        remoteExtension = ".hgt.zip",
    ),
    MAPZEN_SKADI_1S(
        id = "mapzen_skadi_1s",
        displayNameRes = R.string.dem_detailed_terrain,
        shortLabelRes = R.string.dem_detailed,
        detailLabelRes = R.string.dem_sharper_hills_much_larger_files,
        rootDirName = "dem1",
        baseUrl = "https://s3.amazonaws.com/elevation-tiles-prod/skadi",
        remoteExtension = ".hgt.gz",
    ),
    ;

    val telemetryLabel: String
        get() = if (this == MAPSFORGE_DEM3) "Standard" else "Detailed"

    fun rootDir(context: Context): File =
        context.getExternalFilesDir(rootDirName)
            ?: File(context.getDir("maps", Context.MODE_PRIVATE), rootDirName)

    fun folderForTile(tileId: String): String = tileId.uppercase(Locale.ROOT).substring(0, 3)

    fun remoteFileName(tileId: String): String = tileId.uppercase(Locale.ROOT) + remoteExtension

    fun localFileName(tileId: String): String = remoteFileName(tileId)

    /**
     * Read the selected terrain first, then any other downloaded terrain as a runtime fallback.
     *
     * The selected source remains the user's preference. A fallback only prevents DEM-backed
     * features from being unavailable while another complete terrain dataset is already present.
     */
    fun readFallbackOrder(): List<DemSource> = listOf(this) + entries.filterNot { it == this }

    fun remoteUrl(tileId: String): String {
        val safeTileId = tileId.uppercase(Locale.ROOT)
        return "${baseUrl.trimEnd('/')}/${folderForTile(safeTileId)}/${remoteFileName(safeTileId)}"
    }

    companion object {
        val DEFAULT = MAPSFORGE_DEM3
        val LOAD_PRIORITY = listOf(MAPZEN_SKADI_1S, MAPSFORGE_DEM3)

        fun fromId(id: String?): DemSource = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
