@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
)

package com.glancemap.glancemapwearos.presentation.features.download

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

internal fun ScalingLazyListScope.downloadAreaPickerItems(
    areaSearchQueryNormalized: String,
    selectedAreaLabel: String,
    visiblePickerAreas: List<OamDownloadArea>,
    areaFolders: List<Pair<String, List<OamDownloadArea>>>,
    selectedAreaFolder: String?,
    selectedAreaIds: Set<String>,
    suggestedAreas: List<OamDownloadArea>,
    selection: OamDownloadSelection,
    isFindingCurrentLocation: Boolean,
    locationSuggestionMessage: String?,
    onDone: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onToggleSuggestedArea: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onClearAreaSelection: () -> Unit,
    onSelectedAreaFolderChange: (String?) -> Unit,
    onToggleArea: (String) -> Unit,
) {
    item {
        DownloadChip(
            label = "Done",
            secondaryLabel =
                if (areaSearchQueryNormalized.isBlank()) {
                    selectedAreaLabel
                } else {
                    "${visiblePickerAreas.size} result(s)"
                },
            icon = Icons.Filled.Check,
            onClick = onDone,
        )
    }

    item {
        DownloadChip(
            label = "Current location",
            secondaryLabel =
                when {
                    isFindingCurrentLocation -> "Finding matching bundles…"
                    locationSuggestionMessage != null -> locationSuggestionMessage
                    suggestedAreas.isEmpty() -> "Find nearby bundles"
                    else -> "Refresh nearby bundles"
                },
            icon = Icons.Filled.MyLocation,
            onClick = onUseCurrentLocation,
        )
    }

    if (suggestedAreas.isNotEmpty()) {
        item {
            Text(
                text = "Suggested bundles",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        suggestedAreas.forEachIndexed { index, area ->
            val selected = area.id in selectedAreaIds
            item {
                DownloadChip(
                    label = area.region,
                    secondaryLabel =
                        when {
                            selected -> "Selected"
                            index == 0 -> "Best match"
                            else -> "Also covers location"
                        },
                    icon = if (selected) Icons.Filled.Check else Icons.Filled.MyLocation,
                    selected = selected,
                    onClick = { onToggleSuggestedArea(area.id) },
                )
            }
        }
    }

    item {
        DownloadChip(
            label =
                if (selectedAreaIds.isNotEmpty()) {
                    "Clear selected areas"
                } else {
                    "No areas selected"
                },
            secondaryLabel =
                if (selectedAreaIds.isNotEmpty()) {
                    "${selectedAreaIds.size} selected"
                } else {
                    "Pick one or more areas"
                },
            icon = if (selectedAreaIds.isNotEmpty()) Icons.Filled.Close else Icons.Filled.Map,
            onClick = {
                if (selectedAreaIds.isNotEmpty()) {
                    onClearAreaSelection()
                }
            },
        )
    }

    item {
        DownloadChip(
            label =
                if (areaSearchQueryNormalized.isBlank()) {
                    "Search area"
                } else {
                    "Search: $areaSearchQueryNormalized"
                },
            secondaryLabel =
                if (areaSearchQueryNormalized.isBlank()) {
                    "Type to filter"
                } else {
                    "Tap to edit"
                },
            icon = Icons.Filled.Search,
            onClick = onOpenSearch,
        )
    }

    when {
        areaSearchQueryNormalized.isNotBlank() -> {
            item {
                DownloadChip(
                    label = "Clear search",
                    secondaryLabel = "${visiblePickerAreas.size} area(s)",
                    icon = Icons.Filled.Close,
                    onClick = onClearSearch,
                )
            }
        }
        selectedAreaFolder == null -> {
            val (countryFolders, regionFolders) =
                areaFolders.partition { (folder, _) -> folder in AREA_PICKER_COUNTRY_FOLDERS }
            downloadAreaFolderGroup(
                label = "Countries",
                folders = countryFolders.sortedBy { it.first },
                selectedAreaIds = selectedAreaIds,
                onSelectedAreaFolderChange = onSelectedAreaFolderChange,
            )
            downloadAreaFolderGroup(
                label = "Regions",
                folders = regionFolders.sortedBy { it.first },
                selectedAreaIds = selectedAreaIds,
                onSelectedAreaFolderChange = onSelectedAreaFolderChange,
            )
        }
        else -> {
            item {
                DownloadChip(
                    label = "All regions",
                    secondaryLabel = selectedAreaFolder.orEmpty(),
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { onSelectedAreaFolderChange(null) },
                )
            }
        }
    }

    if (areaSearchQueryNormalized.isNotBlank() || selectedAreaFolder != null) {
        downloadAreaResultItems(
            visiblePickerAreas = visiblePickerAreas,
            selectedAreaIds = selectedAreaIds,
            selection = selection,
            onToggleArea = onToggleArea,
        )
    }
}

private fun ScalingLazyListScope.downloadAreaFolderGroup(
    label: String,
    folders: List<Pair<String, List<OamDownloadArea>>>,
    selectedAreaIds: Set<String>,
    onSelectedAreaFolderChange: (String?) -> Unit,
) {
    if (folders.isEmpty()) return
    item {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    folders.forEach { (folder, folderAreas) ->
        val selectedCount = folderAreas.count { it.id in selectedAreaIds }
        item {
            DownloadChip(
                label = folder,
                secondaryLabel =
                    buildString {
                        append(folderAreas.size).append(" area(s)")
                        if (selectedCount > 0) {
                            append(" - ").append(selectedCount).append(" selected")
                        }
                    },
                icon = Icons.Filled.Folder,
                selected = selectedCount > 0,
                onClick = { onSelectedAreaFolderChange(folder) },
            )
        }
    }
}

private fun ScalingLazyListScope.downloadAreaResultItems(
    visiblePickerAreas: List<OamDownloadArea>,
    selectedAreaIds: Set<String>,
    selection: OamDownloadSelection,
    onToggleArea: (String) -> Unit,
) {
    if (visiblePickerAreas.isEmpty()) {
        item {
            NoAreaFoundText()
        }
    }
    visiblePickerAreas.forEach { area ->
        val selected = area.id in selectedAreaIds
        item {
            DownloadChip(
                label = area.region,
                secondaryLabel = area.areaSizeLabel(selection),
                icon = if (selected) Icons.Filled.Check else Icons.Filled.Map,
                selected = selected,
                onClick = {
                    onToggleArea(area.id)
                },
            )
        }
    }
}

private val AREA_PICKER_COUNTRY_FOLDERS = setOf("Canada", "Germany", "Russia", "USA")

@Composable
private fun NoAreaFoundText() {
    Text(
        text = "No area found",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
