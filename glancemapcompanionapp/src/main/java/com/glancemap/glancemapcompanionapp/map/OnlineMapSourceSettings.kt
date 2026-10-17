package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun mapToolsOnlineMapSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_maps_settings_online_summary))
        // Use the full configured catalog so hidden providers can always be enabled again.
        PhoneMapRendererCatalog.availableOnlineSources().forEach { source ->
            val checked = source !in settings.hiddenOnlineSources
            ListItem(
                headlineContent = { Text(PhoneMapRendererCatalog.onlineSourceLabel(source)) },
                trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = { enabled ->
                            onSettingsChanged(
                                settings.copy(
                                    hiddenOnlineSources =
                                        if (enabled) {
                                            settings.hiddenOnlineSources - source
                                        } else {
                                            settings.hiddenOnlineSources + source
                                        },
                                ),
                            )
                        },
                    ),
            )
        }
    }
}
