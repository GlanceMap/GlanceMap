package com.glancemap.glancemapcompanionapp.library

import androidx.compose.runtime.Composable
import com.glancemap.glancemapcompanionapp.map.MapToolsGpxState
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.PhoneGpxAppearanceEditState
import com.glancemap.glancemapcompanionapp.map.PhoneGpxAppearanceOverride
import com.glancemap.glancemapcompanionapp.map.phoneGpxAppearanceOverrideDialog

internal data class PhoneLibraryGpxAppearanceTarget(
    val key: String,
    val title: String,
    val isFolder: Boolean,
)

@Composable
internal fun libraryGpxAppearanceDialog(
    target: PhoneLibraryGpxAppearanceTarget,
    organization: PhoneLibraryOrganization,
    state: MapToolsGpxState,
    actions: MapToolsPanelActions,
    onDismiss: () -> Unit,
) {
    val customization = state.customization
    val parentId =
        if (target.isFolder) organization.folder(target.key)?.parentId else organization.entries[target.key]?.folderId
    val overrides = if (target.isFolder) customization.folderStyles else customization.fileStyles
    phoneGpxAppearanceOverrideDialog(
        title = target.title,
        state =
            PhoneGpxAppearanceEditState(
                customization.settingsForFolder(parentId, state.settings, organization),
                overrides[target.key] ?: PhoneGpxAppearanceOverride(),
            ),
        onSave = { style ->
            val next = overrides + (target.key to style)
            val updated =
                if (target.isFolder) customization.copy(folderStyles = next) else customization.copy(fileStyles = next)
            actions.onGpxCustomizationChanged(updated)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
