package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryEntryOrganization
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryFolder
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganizationStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneGpxCustomizationPreferencesTest {
    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = super.getSharedPreferences("gpx_test_$name", mode)
        }

    @Before
    fun clearTestPreferences() {
        context
            .getSharedPreferences(PHONE_GPX_SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("phone_library_organization", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun settingsReloadKeepsOverridesAndApplyAllSavesDefaultsAndOverridesTogether() {
        val preferences = PhoneGpxCustomizationPreferences(context)
        val generalPreferences = PhoneMapGpxSettingsPreferences(context)
        val original = PhoneMapGpxSettings()
        val styles =
            PhoneGpxCustomizationState(
                fileStyles = mapOf("gpx/route" to PhoneGpxAppearanceOverride(trackWidth = 11f, trackColorArgb = 5)),
                folderStyles = mapOf("parent" to PhoneGpxAppearanceOverride(trackWidth = 9f)),
            )
        generalPreferences.save(original)
        preferences.save(styles)
        assertEquals(styles, PhoneGpxCustomizationPreferences(context).load())
        assertEquals(original, generalPreferences.load())
        assertEquals(styles, preferences.load())
        val changed = original.copy(trackWidth = 12f)
        generalPreferences.save(changed, styles.applyGeneralToAll(original, changed))
        assertEquals(changed, PhoneMapGpxSettingsPreferences(context).load())
        val restored = PhoneGpxCustomizationPreferences(context).load()
        assertNull(restored.fileStyles.getValue("gpx/route").trackWidth)
        assertNull(restored.folderStyles.getValue("parent").trackWidth)
        assertEquals(5, restored.fileStyles.getValue("gpx/route").trackColorArgb)
    }

    @Test
    fun anotherPreferenceInstancePublishesAppearanceChanges() =
        runBlocking {
            val observed = Channel<PhoneGpxCustomizationState>(Channel.UNLIMITED)
            val first = PhoneGpxCustomizationPreferences(context)
            val job = launch { first.changes.collect { observed.send(it) } }
            assertEquals(PhoneGpxCustomizationState(), withTimeout(5_000L) { observed.receive() })
            val changed = PhoneGpxCustomizationState(fileStyles = mapOf("gpx/route" to PhoneGpxAppearanceOverride(trackWidth = 11f)))
            PhoneGpxCustomizationPreferences(context).save(changed)
            assertEquals(changed, withTimeout(5_000L) { observed.receive() })
            job.cancelAndJoin()
        }

    @Test
    fun libraryMovesPublishTheNewHierarchyForMapRendering() =
        runBlocking {
            val original =
                PhoneLibraryOrganization(
                    folders = listOf(PhoneLibraryFolder("parent", "Parent"), PhoneLibraryFolder("child", "Child", "parent")),
                    entries = mapOf("gpx/route" to PhoneLibraryEntryOrganization("parent")),
                )
            val store = PhoneLibraryOrganizationStore(context)
            store.save(original)
            val observed = Channel<PhoneLibraryOrganization>(Channel.UNLIMITED)
            val job = launch { store.changes.collect { observed.send(it) } }
            assertEquals(original, withTimeout(5_000L) { observed.receive() })
            val moved = original.copy(entries = mapOf("gpx/route" to PhoneLibraryEntryOrganization("child")))
            PhoneLibraryOrganizationStore(context).save(moved)
            assertEquals(moved, withTimeout(5_000L) { observed.receive() })
            job.cancelAndJoin()
        }
}
