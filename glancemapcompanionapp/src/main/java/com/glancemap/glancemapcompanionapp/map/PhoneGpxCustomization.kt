package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization
import com.google.gson.annotations.SerializedName

internal data class PhoneGpxAppearanceOverride(
    @field:SerializedName("color") val trackColorArgb: Int? = null,
    @field:SerializedName("mode") val colorMode: PhoneMapGpxColorMode? = null,
    @field:SerializedName("width") val trackWidth: Float? = null,
    @field:SerializedName("opacity") val trackOpacityPercent: Int? = null,
    @field:SerializedName("arrows") val directionArrowsEnabled: Boolean? = null,
) {
    fun applyTo(parent: PhoneMapGpxSettings): PhoneMapGpxSettings =
        parent
            .copy(
                trackColorArgb = trackColorArgb ?: parent.trackColorArgb,
                colorMode = colorMode ?: parent.colorMode,
                trackWidth = trackWidth?.takeIf(Float::isFinite) ?: parent.trackWidth,
                trackOpacityPercent = trackOpacityPercent ?: parent.trackOpacityPercent,
                directionArrowsEnabled = directionArrowsEnabled ?: parent.directionArrowsEnabled,
            ).normalized()

    fun changedFrom(
        before: PhoneMapGpxSettings,
        after: PhoneMapGpxSettings,
    ): PhoneGpxAppearanceOverride =
        copy(
            trackColorArgb =
                if (before.trackColorArgb != after.trackColorArgb) after.trackColorArgb else trackColorArgb,
            colorMode = if (before.colorMode != after.colorMode) after.colorMode else colorMode,
            trackWidth = if (before.trackWidth != after.trackWidth) after.trackWidth else trackWidth,
            trackOpacityPercent =
                if (before.trackOpacityPercent != after.trackOpacityPercent) {
                    after.trackOpacityPercent
                } else {
                    trackOpacityPercent
                },
            directionArrowsEnabled =
                if (before.directionArrowsEnabled != after.directionArrowsEnabled) {
                    after.directionArrowsEnabled
                } else {
                    directionArrowsEnabled
                },
        )

    fun resetChangedBy(
        before: PhoneMapGpxSettings,
        after: PhoneMapGpxSettings,
    ): PhoneGpxAppearanceOverride =
        copy(
            trackColorArgb = trackColorArgb.takeIf { before.trackColorArgb == after.trackColorArgb },
            colorMode = colorMode.takeIf { before.colorMode == after.colorMode },
            trackWidth = trackWidth.takeIf { before.trackWidth == after.trackWidth },
            trackOpacityPercent =
                trackOpacityPercent.takeIf { before.trackOpacityPercent == after.trackOpacityPercent },
            directionArrowsEnabled =
                directionArrowsEnabled.takeIf { before.directionArrowsEnabled == after.directionArrowsEnabled },
        )
}

/** Appearance references the Library's stable item/folder keys without modifying GPX files. */
internal data class PhoneGpxCustomizationState(
    @field:SerializedName("files") val fileStyles: Map<String, PhoneGpxAppearanceOverride> = emptyMap(),
    @field:SerializedName("folderStyles") val folderStyles: Map<String, PhoneGpxAppearanceOverride> = emptyMap(),
) {
    fun settingsForFolder(
        folderId: String?,
        general: PhoneMapGpxSettings,
        organization: PhoneLibraryOrganization,
    ): PhoneMapGpxSettings {
        val seen = mutableSetOf<String>()
        val hierarchy = mutableListOf<String>()
        var current = organization.folder(folderId)
        while (current != null && seen.add(current.id)) {
            hierarchy += current.id
            current = organization.folder(current.parentId)
        }
        return hierarchy.asReversed().fold(general) { inherited, id ->
            folderStyles[id]?.applyTo(inherited) ?: inherited
        }
    }

    fun settingsForFile(
        fileKey: String,
        general: PhoneMapGpxSettings,
        organization: PhoneLibraryOrganization,
    ): PhoneMapGpxSettings {
        val inherited = settingsForFolder(organization.entries[fileKey]?.folderId, general, organization)
        return fileStyles[fileKey]?.applyTo(inherited) ?: inherited
    }

    fun applyGeneralToAll(
        before: PhoneMapGpxSettings,
        after: PhoneMapGpxSettings,
    ): PhoneGpxCustomizationState =
        copy(
            fileStyles = fileStyles.mapValues { (_, style) -> style.resetChangedBy(before, after) },
            folderStyles = folderStyles.mapValues { (_, style) -> style.resetChangedBy(before, after) },
        )
}
