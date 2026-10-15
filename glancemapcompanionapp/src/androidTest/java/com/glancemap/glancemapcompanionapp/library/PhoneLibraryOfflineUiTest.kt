package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhoneLibraryOfflineUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun sectionsShowStandaloneFilesAndFolderActionsUseTheirOwnSources() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        var elevationFolders = 0
        var routingFolders = 0
        var elevationRescans = 0
        val mapActions = libraryTestMapActions(state)
        val file =
            PhoneLibraryItem.OfflineData(
                File("N45E010.hgt"),
                "N45E010.hgt",
                PhoneLibraryOfflineSection.ELEVATION,
                100L,
                1L,
                true,
                phoneLibraryOfflineCoverage("N45E010.hgt", PhoneLibraryOfflineSection.ELEVATION),
                emptyList(),
                PhoneOfflineStorageLocation.INTERNAL,
            )
        composeRule.setContent {
            MaterialTheme {
                libraryPanelContent(
                    PhoneLibraryListState(listOf(file), PhoneLibraryFilter.BUNDLES),
                    state.value,
                    mapActions.copy(
                        maps =
                            mapActions.maps.copy(
                                onSelectElevationFolder = { elevationFolders++ },
                                onSelectRoutingFolder = { routingFolders++ },
                                onRescanElevationFolder = { elevationRescans++ },
                            ),
                    ),
                    libraryTestActions(),
                )
            }
        }
        selectSection(PhoneLibraryOfflineSection.ELEVATION)
        scrollToText(R.string.map_elevation_folder_select).performClick()
        assertEquals(1, elevationFolders)
        composeRule.runOnIdle { state.value = state.value.copy(maps = state.value.maps.copy(hasSelectedElevationFolder = true)) }
        scrollToText(R.string.map_elevation_folder_rescan).performClick()
        assertEquals(1, elevationRescans)
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("N45E010.hgt"))
        composeRule.onNodeWithText("N45E010.hgt").performClick()
        composeRule.onNodeWithText(PhoneOfflineStorageLocation.INTERNAL.label).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.common_action_close)).performClick()
        selectSection(PhoneLibraryOfflineSection.ROUTING)
        scrollToText(R.string.map_routing_folder_select).performClick()
        assertEquals(1, routingFolders)
        composeRule.onNodeWithText("N45E010.hgt").assertDoesNotExist()
    }

    @Test fun areaCardsShowMissingComponentsSeparatelyFromComponentsNotIncluded() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        val bundle = PhoneInstalledBundle("alps", "Alps", "Alps.map", "Alps.poi", installedAtMillis = 1L)
        val area =
            PhoneLibraryItem.Bundle(
                bundle,
                PhoneOfflineBundleHealth(PhoneOfflineBundleStatus.PARTIAL, availableFileNames = listOf("map")),
                null,
            )
        composeRule.setContent {
            MaterialTheme {
                libraryPanelContent(
                    PhoneLibraryListState(listOf(area), PhoneLibraryFilter.BUNDLES),
                    state.value,
                    libraryTestMapActions(state),
                    libraryTestActions(),
                )
            }
        }
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Alps"))
        composeRule
            .onNodeWithText("${label(R.string.library_filter_pois)}: ${label(R.string.library_offline_missing)}")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("${label(R.string.library_offline_routing)}: ${label(R.string.library_offline_not_included)}")
            .assertIsDisplayed()
    }

    @Test fun separateFileImportsValidateReuseAndBecomeAvailableWithoutAnArea() {
        val root = File(composeRule.activity.cacheDir, "offline-library-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(composeRule.activity) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): File = root

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ) = super.getSharedPreferences(root.name + name, mode)
            }
        try {
            val dem = File(root, "N45E010.hgt")
            RandomAccessFile(dem, "rw").use { it.setLength(1201L * 1201L * 2L) }
            val routing = File(root, "E10_N45.rd5")
            routing.writeBytes(
                ByteBuffer
                    .allocate(200)
                    .putShort(11.toShort())
                    .putLong(192, 200L)
                    .array(),
            )
            listOf(dem to PhoneLibraryOfflineSection.ELEVATION, routing to PhoneLibraryOfflineSection.ROUTING)
                .forEach { (file, section) ->
                    assertEquals(1, importPhoneLibraryOfflineFiles(context, listOf(Uri.fromFile(file)), section).added)
                    assertEquals(1, importPhoneLibraryOfflineFiles(context, listOf(Uri.fromFile(file)), section).reused)
                }
            val storage = PhoneOfflineStorage(context)
            val installed =
                discoverPhoneLibraryOfflineData(
                    storage.elevationDirectory(),
                    storage.routingDirectory(),
                    emptyList(),
                    PhoneOfflineStorageLocation.INTERNAL,
                )
            assertEquals(2, installed.size)
            assertTrue(installed.all { it.isUsable })
            assertTrue(PhoneElevationRepository(context, false).elevationAt(45.2, 10.2) != null)
            assertTrue(isUsablePhoneRoutingFile(File(storage.routingDirectory(), routing.name)))
        } finally {
            root.deleteRecursively()
            composeRule.activity.deleteSharedPreferences(root.name + "phone_offline_storage")
            composeRule.activity.deleteSharedPreferences(root.name + "phone_map_settings")
        }
    }

    private fun selectSection(section: PhoneLibraryOfflineSection) {
        val tag = "library-offline-${section.name}"
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun scrollToText(resource: Int): SemanticsNodeInteraction {
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText(label(resource)))
        return composeRule.onNodeWithText(label(resource))
    }

    private fun label(resource: Int) = composeRule.activity.getString(resource)
}
