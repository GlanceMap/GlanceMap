package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.library.PhoneLibraryEntryOrganization
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryFolder
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryItem
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGpxCustomizationTest {
    private val general = PhoneMapGpxSettings(trackColorArgb = 1, trackWidth = 5f, trackOpacityPercent = 70)
    private val organization =
        PhoneLibraryOrganization(
            folders = listOf(PhoneLibraryFolder("parent", "Parent"), PhoneLibraryFolder("child", "Child", "parent")),
            entries = mapOf("gpx/track" to PhoneLibraryEntryOrganization("child")),
        )

    @Test
    fun eachPropertyUsesFileThenClosestFolderThenParentThenGeneral() {
        val styles =
            PhoneGpxCustomizationState(
                folderStyles =
                    mapOf(
                        "parent" to
                            PhoneGpxAppearanceOverride(
                                trackColorArgb = 2,
                                trackWidth = 7f,
                                colorMode = PhoneMapGpxColorMode.ELEVATION,
                            ),
                        "child" to PhoneGpxAppearanceOverride(trackWidth = 10f, directionArrowsEnabled = true),
                    ),
                fileStyles = mapOf("gpx/track" to PhoneGpxAppearanceOverride(trackColorArgb = 3)),
            )
        val resolved = styles.settingsForFile("gpx/track", general, organization)
        assertEquals(3, resolved.trackColorArgb)
        assertEquals(10f, resolved.trackWidth)
        assertEquals(PhoneMapGpxColorMode.ELEVATION, resolved.colorMode)
        assertEquals(70, resolved.trackOpacityPercent)
        assertTrue(resolved.directionArrowsEnabled)
        assertEquals(general.flatSpeedMetersPerSecond, resolved.flatSpeedMetersPerSecond)
    }

    @Test
    fun anExplicitFalseAndAValueEqualToTheOldDefaultRemainOverrides() {
        val explicit = PhoneGpxAppearanceOverride(trackWidth = 5f, directionArrowsEnabled = false)
        val styles = PhoneGpxCustomizationState(fileStyles = mapOf("gpx/track" to explicit))
        val changed = general.copy(trackWidth = 12f, directionArrowsEnabled = true)
        val resolved = styles.settingsForFile("gpx/track", changed, organization)
        assertEquals(5f, resolved.trackWidth)
        assertFalse(resolved.directionArrowsEnabled)
    }

    @Test
    fun editingWidthDoesNotFreezeInheritedColorOrOpacity() {
        val custom = PhoneGpxAppearanceOverride().changedFrom(general, general.copy(trackWidth = 11f))
        val resolved = custom.applyTo(general.copy(trackColorArgb = 9, trackOpacityPercent = 40))
        assertEquals(11f, resolved.trackWidth)
        assertEquals(9, resolved.trackColorArgb)
        assertEquals(40, resolved.trackOpacityPercent)
        assertNull(custom.trackColorArgb)
        assertNull(custom.trackOpacityPercent)
    }

    @Test
    fun resettingOnePropertyRestoresItsParentAndKeepsOtherOverrides() {
        val custom = PhoneGpxAppearanceOverride(trackWidth = 11f, trackColorArgb = 8).copy(trackWidth = null)
        val resolved = custom.applyTo(general)
        assertEquals(5f, resolved.trackWidth)
        assertEquals(8, resolved.trackColorArgb)
    }

    @Test
    fun applyingOnlyInheritedGeneralSettingsPreservesFolderAndFileOverrides() {
        val styles =
            PhoneGpxCustomizationState(
                folderStyles = mapOf("parent" to PhoneGpxAppearanceOverride(trackWidth = 8f)),
                fileStyles = mapOf("gpx/track" to PhoneGpxAppearanceOverride(trackOpacityPercent = 30)),
            )
        val newGeneral = general.copy(trackWidth = 12f, trackColorArgb = 9, trackOpacityPercent = 90)
        val resolved = styles.settingsForFile("gpx/track", newGeneral, organization)
        assertEquals(8f, resolved.trackWidth)
        assertEquals(30, resolved.trackOpacityPercent)
        assertEquals(9, resolved.trackColorArgb)
        assertEquals(newGeneral, styles.settingsForFile("gpx/new", newGeneral, organization))
    }

    @Test
    fun applyingToAllReplacesOnlyChangedPropertiesAtEveryLevel() {
        val styles =
            PhoneGpxCustomizationState(
                folderStyles =
                    mapOf(
                        "parent" to PhoneGpxAppearanceOverride(trackWidth = 8f, trackColorArgb = 6),
                        "child" to PhoneGpxAppearanceOverride(trackWidth = 9f),
                    ),
                fileStyles =
                    mapOf(
                        "gpx/track" to PhoneGpxAppearanceOverride(trackWidth = 10f, trackOpacityPercent = 30),
                    ),
            )
        val newGeneral = general.copy(trackWidth = 12f)
        val updated = styles.applyGeneralToAll(general, newGeneral)
        val resolved = updated.settingsForFile("gpx/track", newGeneral, organization)
        assertEquals(12f, resolved.trackWidth)
        assertEquals(6, resolved.trackColorArgb)
        assertEquals(30, resolved.trackOpacityPercent)
        assertTrue(updated.folderStyles.values.all { it.trackWidth == null })
        assertNull(updated.fileStyles.getValue("gpx/track").trackWidth)
    }

    @Test
    fun movingAFileChangesInheritedValuesButKeepsItsOwnAppearance() {
        val styles =
            PhoneGpxCustomizationState(
                folderStyles =
                    mapOf(
                        "parent" to PhoneGpxAppearanceOverride(trackWidth = 7f),
                        "child" to PhoneGpxAppearanceOverride(trackWidth = 10f),
                    ),
                fileStyles = mapOf("gpx/track" to PhoneGpxAppearanceOverride(trackColorArgb = 3)),
            )
        val item = PhoneLibraryItem.FolderGpx(trackItem("track"))
        val moved = organization.move(item, "parent")
        assertEquals(10f, styles.settingsForFile(item.key, general, organization).trackWidth)
        val resolved = styles.settingsForFile(item.key, general, moved)
        assertEquals(7f, resolved.trackWidth)
        assertEquals(3, resolved.trackColorArgb)
    }

    @Test
    fun transferredGpxUsesItsStableExportOrganizationKeyAfterImport() {
        val key = "export/fixture"
        val item = trackItem("imported-id").copy(libraryKey = key)
        val folders = organization.copy(entries = mapOf(key to PhoneLibraryEntryOrganization("child")))
        val styles =
            PhoneGpxCustomizationState(
                folderStyles = mapOf("child" to PhoneGpxAppearanceOverride(trackWidth = 9f)),
            )
        assertEquals(
            9f,
            listOf(item)
                .enabledOverlays(true, customization = styles, generalSettings = general, organization = folders)
                .single()
                .settings
                ?.trackWidth,
        )
    }

    @Test
    fun persistedOverridesKeepEnumsExplicitFalseAndUnsetFields() {
        val styles =
            PhoneGpxCustomizationState(
                folderStyles =
                    mapOf(
                        "child" to
                            PhoneGpxAppearanceOverride(
                                colorMode = PhoneMapGpxColorMode.ELEVATION,
                                directionArrowsEnabled = false,
                            ),
                    ),
                fileStyles = mapOf("gpx/track" to PhoneGpxAppearanceOverride(trackWidth = 11f)),
            )
        val gson = Gson()
        assertEquals(styles, gson.fromJson(gson.toJson(styles), PhoneGpxCustomizationState::class.java))
        assertEquals(PhoneGpxCustomizationState(), gson.fromJson("{}", PhoneGpxCustomizationState::class.java))
    }

    @Test
    fun mixedOverlaysKeepSeparateStylesAndStyleChangesInvalidateGeometryEqualOverlays() {
        val first = trackItem("track")
        val second = trackItem("other")
        val styles =
            PhoneGpxCustomizationState(
                fileStyles =
                    mapOf("gpx/track" to PhoneGpxAppearanceOverride(trackWidth = 12f, trackOpacityPercent = 20)),
            )
        val overlays = listOf(first, second).enabledOverlays(true, customization = styles, generalSettings = general)
        assertEquals(12f, overlays.first().settings?.trackWidth)
        assertEquals(20, overlays.first().settings?.trackOpacityPercent)
        assertNull(overlays.last().settings)
        val recolored = overlays.first().copy(settings = overlays.first().settings?.copy(trackColorArgb = 8))
        assertNotEquals(overlays.first(), recolored)
        assertSame(overlays.first().segments, recolored.segments)
    }

    @Test
    fun incompleteOrCyclicFolderMetadataCannotLoopForever() {
        val cyclic =
            organization.copy(
                folders =
                    listOf(
                        PhoneLibraryFolder("parent", "Parent", "child"),
                        PhoneLibraryFolder("child", "Child", "parent"),
                    ),
            )
        assertEquals(general, PhoneGpxCustomizationState().settingsForFile("gpx/track", general, cyclic))
        val missing = organization.copy(entries = mapOf("gpx/track" to PhoneLibraryEntryOrganization("gone")))
        assertEquals(general, PhoneGpxCustomizationState().settingsForFile("gpx/track", general, missing))
    }

    @Test
    fun appearanceUpdatesReuseLoadedCoordinatesAndLeaveUnchangedOverlaysAlone() {
        val item = trackItem("track")
        val loaded = listOf(item).enabledOverlays(true)
        val styles =
            PhoneGpxCustomizationState(
                fileStyles = mapOf(item.libraryKey to PhoneGpxAppearanceOverride(trackWidth = 11f)),
            )
        val styled = loaded.withAppearance(listOf(item), styles, general, organization)
        assertSame(loaded.single().segments, styled.single().segments)
        val unchanged = styled.withAppearance(listOf(item), styles, general, organization)
        assertSame(styled.single(), unchanged.single())
    }

    @Test
    fun restoredStyleValuesUseTheExistingAppearanceLimits() {
        val restored = PhoneGpxAppearanceOverride(trackWidth = Float.NaN, trackOpacityPercent = 500).applyTo(general)
        assertEquals(general.trackWidth, restored.trackWidth)
        assertEquals(MAX_PHONE_GPX_TRACK_OPACITY_PERCENT, restored.trackOpacityPercent)
    }

    private fun trackItem(id: String) =
        PhoneMapGpxItem(
            id,
            id,
            PhoneMapGpxTrack(id, listOf(TrailPoint(GeoPoint(46.0, 7.0)), TrailPoint(GeoPoint(46.01, 7.01)))),
            enabled = true,
        )
}
