@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
)

package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R

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
    resources: Resources,
) {
    item {
        DownloadChip(
            label = resources.getString(R.string.common_done),
            secondaryLabel =
                if (areaSearchQueryNormalized.isBlank()) {
                    selectedAreaLabel
                } else {
                    resources.getQuantityString(
                        R.plurals.download_result_s,
                        visiblePickerAreas.size,
                        visiblePickerAreas.size,
                    )
                },
            icon = Icons.Filled.Check,
            onClick = onDone,
        )
    }

    item {
        DownloadChip(
            label = resources.getString(R.string.download_current_location),
            secondaryLabel =
                when {
                    isFindingCurrentLocation -> resources.getString(R.string.download_finding_matching_bundles)
                    locationSuggestionMessage != null -> locationSuggestionMessage
                    suggestedAreas.isEmpty() -> resources.getString(R.string.download_find_nearby_bundles)
                    else -> resources.getString(R.string.download_refresh_nearby_bundles)
                },
            icon = Icons.Filled.MyLocation,
            onClick = onUseCurrentLocation,
        )
    }

    if (suggestedAreas.isNotEmpty()) {
        item {
            Text(
                text = resources.getString(R.string.download_suggested_bundles),
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
                            selected -> resources.getString(R.string.common_selected)
                            index == 0 -> resources.getString(R.string.download_best_match)
                            else -> resources.getString(R.string.download_also_covers_location)
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
                    resources.getString(R.string.download_clear_selected_areas)
                } else {
                    resources.getString(R.string.download_no_areas_selected)
                },
            secondaryLabel =
                if (selectedAreaIds.isNotEmpty()) {
                    resources.getQuantityString(
                        R.plurals.download_selected_count,
                        selectedAreaIds.size,
                        selectedAreaIds.size,
                    )
                } else {
                    resources.getString(R.string.download_pick_one_or_more_areas)
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
                    resources.getString(R.string.download_search_area)
                } else {
                    resources.getString(R.string.download_search, areaSearchQueryNormalized)
                },
            secondaryLabel =
                if (areaSearchQueryNormalized.isBlank()) {
                    resources.getString(R.string.download_type_to_filter)
                } else {
                    resources.getString(R.string.download_tap_to_edit)
                },
            icon = Icons.Filled.Search,
            onClick = onOpenSearch,
        )
    }

    when {
        areaSearchQueryNormalized.isNotBlank() -> {
            item {
                DownloadChip(
                    label = resources.getString(R.string.download_clear_search),
                    secondaryLabel =
                        resources.getQuantityString(
                            R.plurals.download_area_s,
                            visiblePickerAreas.size,
                            visiblePickerAreas.size,
                        ),
                    icon = Icons.Filled.Close,
                    onClick = onClearSearch,
                )
            }
        }
        selectedAreaFolder == null -> {
            val (countryFolders, regionFolders) =
                areaFolders.partition { (folder, _) -> folder in AREA_PICKER_COUNTRY_FOLDERS }
            downloadAreaFolderGroup(
                label = resources.getString(R.string.download_countries),
                folders = countryFolders.sortedBy { it.first },
                selectedAreaIds = selectedAreaIds,
                onSelectedAreaFolderChange = onSelectedAreaFolderChange,
                resources = resources,
            )
            downloadAreaFolderGroup(
                label = resources.getString(R.string.download_regions),
                folders = regionFolders.sortedBy { it.first },
                selectedAreaIds = selectedAreaIds,
                onSelectedAreaFolderChange = onSelectedAreaFolderChange,
                resources = resources,
            )
        }
        else -> {
            item {
                DownloadChip(
                    label = resources.getString(R.string.download_all_regions),
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
            resources = resources,
        )
    }
}

private fun ScalingLazyListScope.downloadAreaFolderGroup(
    label: String,
    folders: List<Pair<String, List<OamDownloadArea>>>,
    selectedAreaIds: Set<String>,
    onSelectedAreaFolderChange: (String?) -> Unit,
    resources: Resources,
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
                    if (selectedCount >
                        0
                    ) {
                        resources.getString(
                            R.string.download_area_selection_summary,
                            resources.getQuantityString(
                                R.plurals.download_areas,
                                folderAreas.size,
                                folderAreas.size,
                            ),
                            resources.getQuantityString(
                                R.plurals.download_selected_count,
                                selectedCount,
                                selectedCount,
                            ),
                        )
                    } else {
                        resources.getQuantityString(R.plurals.download_areas, folderAreas.size, folderAreas.size)
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
    resources: Resources,
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
                secondaryLabel = area.areaSizeLabel(selection, resources = resources),
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
        text = stringResource(R.string.download_no_area_found),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
