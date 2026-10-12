package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneMapOfflineStartupSelectionTest {
    private val oldMap = PhoneOfflineMap(File("fixture.map"), contentRevision = "old")
    private val preference =
        PhoneMapSourcePreference(
            PhoneMapSourcePreferenceMode.OFFLINE,
            oldMap.displayName,
            PhoneOnlineMapSource.CYCLOSM,
        )

    @Test
    fun missingOfflinePreferenceIsReportedOnAFreshOnlineInitialState() {
        val missing = resolvePhoneMapOfflineStartupSelection(preference, PhoneMapSource.Online, emptyList())
        assertTrue(missing.missingMap)
        assertNull(missing.map)
    }

    @Test
    fun discoveryUsesTheCurrentRevisionAndRecoveryFindsThePreferredMapAgain() {
        val replacement = oldMap.copy(contentRevision = "new")
        val recovered = resolvePhoneMapOfflineStartupSelection(preference, PhoneMapSource.Online, listOf(replacement))
        assertFalse(recovered.missingMap)
        assertSame(replacement, recovered.map)
    }

    @Test
    fun anOnlinePreferenceDoesNotReportItsRememberedOfflineFilenameAsMissing() {
        val online = preference.restoredOnline()
        val selection = resolvePhoneMapOfflineStartupSelection(online, PhoneMapSource.Online, emptyList())
        assertFalse(selection.missingMap)
        assertNull(selection.map)
    }

    @Test
    fun aMissingOfflineSourceRestoredFromSavedStateIsAlsoReported() {
        val selection =
            resolvePhoneMapOfflineStartupSelection(
                preference.restoredOnline(),
                PhoneMapSource.Offline(oldMap),
                emptyList(),
            )
        assertTrue(selection.missingMap)
    }
}
