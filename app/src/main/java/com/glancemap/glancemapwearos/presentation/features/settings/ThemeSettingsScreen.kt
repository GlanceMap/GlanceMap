@file:Suppress("CyclomaticComplexMethod", "FunctionName", "FunctionNaming", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeListItem
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.theme.ThemeViewModel
import java.util.Locale

internal object ThemeOverlayGrouping {
    fun groupIdForOverlay(layerId: String): String {
        val id = layerId.lowercase(Locale.ROOT)
        return when {
            id.contains("skipiste") ||
                id.contains("ski") ||
                id.contains("skitour") ||
                id.contains("skiloipe") ||
                id.contains("schneeschuh") ||
                id.contains("rodeln") ||
                id.contains("hundeschlitten") ||
                id.contains("eislaufen") ||
                id.contains("schneepark") ||
                id.contains("winter_") -> "winter"

            id.contains("routes") ||
                id.contains("hiking") ||
                id.contains("cycling") ||
                id.contains("mtb") ||
                id.contains("guidepost") ||
                id.contains("placenames") ||
                id.contains("roadnames") ||
                id.contains("roadnumbers") ||
                id.contains("elevations") ||
                id.contains("topography") ||
                id.contains("tracks") ||
                id.contains("waymarks") ||
                id.contains("pt_network") ||
                id.contains("reference") ||
                id.contains("symbol") ||
                id.contains("namen") ||
                id.contains("tms_hknw") ||
                id.contains("tms_cynw") ||
                id.contains("tms_mtbnw") ||
                id.contains("tms_paths") ||
                id.contains("tms_tracks") -> "routes"

            id.contains("amenities") ||
                id.contains("accommodation") ||
                id.contains("restaurants") ||
                id.contains("shops") ||
                id.contains("publictrans") ||
                id.contains("camping") ||
                id.contains("hotel") ||
                id.contains("hut") ||
                id.contains("health") ||
                id.contains("education") ||
                id.contains("eating") ||
                id.contains("fun") ||
                id.contains("urban_equipment") ||
                id.contains("nature_equipment") ||
                id.contains("infrastructure") ||
                id.contains("tourism") ||
                id.contains("sports") ||
                id.contains("emergency") ||
                id.contains("pubtrans") ||
                id.contains("car") ||
                id.contains("barriers") ||
                id.contains("buildings") ||
                id.contains("shop") ||
                id.contains("tms_huts") ||
                id.contains("tms_acco") ||
                id.contains("tms_picnic") ||
                id.contains("tms_tourism") ||
                id.contains("tms_trans-priv") ||
                id.contains("tms_trans-pub") ||
                id.contains("tms_culture") ||
                id.contains("tms_freetime") ||
                id.contains("tms_food") ||
                id.contains("tms_shops") ||
                id.contains("tms_religion") ||
                id.contains("tms_emergency") -> "poi"

            else -> "map"
        }
    }
}

private data class OverlayGroupUi(
    val id: String,
    val title: String,
    val overlays: List<ThemeListItem.Overlay>,
)

private const val OS_MAP_DAY_STYLE_PREFIX = "__OS_MAP_DAY__:"
private const val OS_MAP_NIGHT_STYLE_PREFIX = "__OS_MAP_NIGHT__:"

private data class OsMapStyleSelection(
    val modeLabel: String,
    val prefix: String,
    val realStyleId: String,
)

private data class OsMapStyleUi(
    val selectedModeLabel: String,
    val selectedModeValue: String,
    val modeOptions: List<Pair<String, String>>,
    val selectedVariantLabel: String,
    val selectedVariantValue: String,
    val variantOptions: List<Pair<String, String>>,
)

@Composable
fun ThemeSettingsScreen(
    themeViewModel: ThemeViewModel,
    mapViewModel: MapViewModel,
    @Suppress("UNUSED_PARAMETER")
    onOpenMaps: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val themeItems by themeViewModel.themeItems.collectAsState()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val selectedStyle =
        themeItems
            .filterIsInstance<ThemeListItem.Style>()
            .firstOrNull { it.selected }
    val selectedStyleId = selectedStyle?.id

    val selectedTheme =
        themeItems
            .filterIsInstance<ThemeListItem.ThemeOption>()
            .firstOrNull { it.selected }
    val selectedThemeId = selectedTheme?.id

    val themeOptions = themeItems.filterIsInstance<ThemeListItem.ThemeOption>()
    val styleOptions = themeItems.filterIsInstance<ThemeListItem.Style>()
    val overlayOptions = themeItems.filterIsInstance<ThemeListItem.Overlay>()
    val nightModeToggle =
        themeItems
            .filterIsInstance<ThemeListItem.GlobalToggle>()
            .firstOrNull { it.id == ThemeUiIds.NIGHT_MODE }
    val overlayGroups = remember(overlayOptions) { buildOverlayGroups(overlayOptions) }
    val themePickerOptions =
        remember(themeOptions) {
            themeOptions.map { option -> option.id to option.name.ifBlank { option.id } }
        }
    val stylePickerOptions =
        remember(styleOptions) {
            styleOptions.map { option -> option.id to option.name.ifBlank { option.id } }
        }
    val osMapStyleUi =
        remember(selectedThemeId, selectedStyleId, styleOptions) {
            buildOsMapStyleUi(
                selectedThemeId = selectedThemeId,
                selectedStyleId = selectedStyleId,
                styles = styleOptions,
            )
        }

    val bundledThemeSelected = MapsforgeThemeCatalog.isBundledAssetTheme(selectedThemeId)
    val styleSelectionAvailable = hasMeaningfulStyleSelection(styleOptions)
    val canToggleOverlays = bundledThemeSelected && selectedStyleId != null
    val selectedThemeValue = selectedTheme?.id ?: themeOptions.firstOrNull()?.id
    val selectedStyleValue = selectedStyleId ?: styleOptions.firstOrNull()?.id

    DisposableEffect(mapViewModel) {
        mapViewModel.setThemeRenderingDeferred(true)
        onDispose {
            mapViewModel.setThemeRenderingDeferred(false)
        }
    }

    LaunchedEffect(overlayGroups.map { it.id }) {
        val validKeys = overlayGroups.map { it.id }.toSet()
        expandedGroups.keys
            .toList()
            .filter { it !in validKeys }
            .forEach { expandedGroups.remove(it) }
        validKeys.forEach { key ->
            if (expandedGroups[key] == null) expandedGroups[key] = false
        }
    }

    val overlayRows =
        remember(overlayGroups, expandedGroups.toMap()) {
            buildOverlayRows(overlayGroups, expandedGroups)
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }

        item {
            Button(onClick = { themeViewModel.resetToDefaults() }) {
                Text(
                    text = "Reset defaults",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        item {
            SettingsToggleChip(
                checked = nightModeToggle?.enabled == true,
                onCheckedChanged = { enabled ->
                    themeViewModel.setGlobalToggle(ThemeUiIds.NIGHT_MODE, enabled)
                },
                label = "Night mode",
                secondaryLabel = if (nightModeToggle?.enabled == true) "On" else "Off",
            )
        }

        if (nightModeToggle?.enabled == true) {
            item {
                Text(
                    text =
                        "Night mode uses Mapsforge Dark. Your normal theme and overlay choices " +
                            "are saved and return when it is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        } else if (themeOptions.isNotEmpty()) {
            item {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            item {
                selectedThemeValue?.let {
                    SettingsOptionPickerRow(
                        label = "Map theme",
                        dialogTitle = "Theme",
                        selectedValue = it,
                        options = themePickerOptions,
                        secondaryLabel = selectedTheme?.name?.ifBlank { selectedTheme.id } ?: "-",
                        onSelect = themeViewModel::setTheme,
                    )
                }
            }
        }

        if (nightModeToggle?.enabled != true && styleSelectionAvailable) {
            if (osMapStyleUi != null) {
                item {
                    SettingsOptionPickerRow(
                        label = "Mode",
                        dialogTitle = "OS Map mode",
                        selectedValue = osMapStyleUi.selectedModeValue,
                        options = osMapStyleUi.modeOptions,
                        secondaryLabel = osMapStyleUi.selectedModeLabel,
                        onSelect = themeViewModel::setMapStyle,
                    )
                }
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)),
                    )
                }
                item {
                    SettingsOptionPickerRow(
                        label = "Map style",
                        dialogTitle = "OS Map style",
                        selectedValue = osMapStyleUi.selectedVariantValue,
                        options = osMapStyleUi.variantOptions,
                        secondaryLabel = osMapStyleUi.selectedVariantLabel,
                        onSelect = themeViewModel::setMapStyle,
                    )
                }
            } else {
                item {
                    selectedStyleValue?.let {
                        SettingsOptionPickerRow(
                            label = "Style",
                            dialogTitle = "Theme style",
                            selectedValue = it,
                            options = stylePickerOptions,
                            secondaryLabel = selectedStyle?.name?.ifBlank { selectedStyle.id } ?: "-",
                            onSelect = themeViewModel::setMapStyle,
                        )
                    }
                }
            }
        }

        if (nightModeToggle?.enabled != true && themeOptions.isNotEmpty()) {
            item {
                Text(
                    text = "Themes can misread terrain or paths. Verify with local conditions and other sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (nightModeToggle?.enabled != true && bundledThemeSelected && overlayOptions.isNotEmpty()) {
            item {
                Text(
                    text = "Overlays (tap group to open)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            items(overlayRows) { row ->
                when (row) {
                    is OverlayRow.Group -> {
                        val enabledCount = row.group.overlays.count { it.enabled }
                        val totalCount = row.group.overlays.size
                        SettingsPickerChip(
                            label = if (row.expanded) "▾ ${row.group.title}" else "▸ ${row.group.title}",
                            secondaryLabel = "$enabledCount/$totalCount",
                            iconImageVector = null,
                            onClick = {
                                expandedGroups[row.group.id] = !row.expanded
                            },
                        )
                    }

                    is OverlayRow.Item -> {
                        SettingsToggleChip(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canToggleOverlays,
                            checked = row.overlay.enabled,
                            onCheckedChanged = { _ ->
                                if (canToggleOverlays) {
                                    themeViewModel.toggleOverlay(row.overlay.layerId)
                                }
                            },
                            label = row.overlay.name.ifBlank { row.overlay.layerId },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface OverlayRow {
    data class Group(
        val group: OverlayGroupUi,
        val expanded: Boolean,
    ) : OverlayRow

    data class Item(
        val groupId: String,
        val overlay: ThemeListItem.Overlay,
    ) : OverlayRow
}

private fun buildOverlayRows(
    groups: List<OverlayGroupUi>,
    expandedState: Map<String, Boolean>,
): List<OverlayRow> {
    val rows = mutableListOf<OverlayRow>()
    groups.forEach { group ->
        val expanded = expandedState[group.id] == true
        rows += OverlayRow.Group(group = group, expanded = expanded)
        if (expanded) {
            group.overlays.forEach { overlay ->
                rows += OverlayRow.Item(groupId = group.id, overlay = overlay)
            }
        }
    }
    return rows
}

private fun buildOverlayGroups(overlays: List<ThemeListItem.Overlay>): List<OverlayGroupUi> {
    val buckets =
        linkedMapOf(
            "winter" to mutableListOf<ThemeListItem.Overlay>(),
            "routes" to mutableListOf<ThemeListItem.Overlay>(),
            "poi" to mutableListOf<ThemeListItem.Overlay>(),
            "map" to mutableListOf<ThemeListItem.Overlay>(),
        )

    overlays.forEach { overlay ->
        buckets[ThemeOverlayGrouping.groupIdForOverlay(overlay.layerId)]?.add(overlay)
    }

    val titles =
        mapOf(
            "winter" to "Winter activities",
            "routes" to "Routes & labels",
            "poi" to "POI & services",
            "map" to "Map details",
        )

    return buckets
        .mapNotNull { (id, list) ->
            if (list.isEmpty()) return@mapNotNull null
            OverlayGroupUi(
                id = id,
                title = titles[id] ?: id,
                overlays = list.sortedBy { it.name.lowercase(Locale.ROOT) },
            )
        }
}

private fun hasMeaningfulStyleSelection(styles: List<ThemeListItem.Style>): Boolean {
    val nonDefaultStyleCount =
        styles.count {
            it.id != ThemeUiIds.DEFAULT_STYLE_ID
        }
    return nonDefaultStyleCount > 1
}

private fun buildOsMapStyleUi(
    selectedThemeId: String?,
    selectedStyleId: String?,
    styles: List<ThemeListItem.Style>,
): OsMapStyleUi? {
    val options =
        if (selectedThemeId == MapsforgeThemeCatalog.OS_MAP_THEME_ID) {
            styles
                .mapNotNull { style ->
                    parseOsMapStyleSelection(style.id)?.let { selection ->
                        val label =
                            style.name
                                .removePrefix("${selection.modeLabel} - ")
                                .ifBlank { selection.realStyleId }
                        selection to label
                    }
                }
        } else {
            emptyList()
        }

    return if (options.isEmpty()) {
        null
    } else {
        val selected =
            selectedStyleId
                ?.let(::parseOsMapStyleSelection)
                ?: options.first().first
        val selectedVariantLabel =
            options
                .firstOrNull { (selection, _) -> selection.realStyleId == selected.realStyleId }
                ?.second
                ?: selected.realStyleId

        val hasDay = options.any { (selection, _) -> selection.prefix == OS_MAP_DAY_STYLE_PREFIX }
        val hasNight = options.any { (selection, _) -> selection.prefix == OS_MAP_NIGHT_STYLE_PREFIX }
        val modeOptions =
            buildList {
                if (hasDay) add("${OS_MAP_DAY_STYLE_PREFIX}${selected.realStyleId}" to "Day")
                if (hasNight) add("${OS_MAP_NIGHT_STYLE_PREFIX}${selected.realStyleId}" to "Night")
            }

        val selectedModeValue = "${selected.prefix}${selected.realStyleId}"
        val variantOptions =
            options
                .asSequence()
                .filter { (selection, _) -> selection.prefix == selected.prefix }
                .distinctBy { (selection, _) -> selection.realStyleId }
                .map { (selection, label) -> "${selected.prefix}${selection.realStyleId}" to label }
                .toList()

        OsMapStyleUi(
            selectedModeLabel = selected.modeLabel,
            selectedModeValue = selectedModeValue,
            modeOptions = modeOptions,
            selectedVariantLabel = selectedVariantLabel,
            selectedVariantValue = selectedModeValue,
            variantOptions = variantOptions,
        )
    }
}

private fun parseOsMapStyleSelection(styleId: String): OsMapStyleSelection? =
    when {
        styleId.startsWith(OS_MAP_DAY_STYLE_PREFIX) ->
            OsMapStyleSelection(
                modeLabel = "Day",
                prefix = OS_MAP_DAY_STYLE_PREFIX,
                realStyleId = styleId.removePrefix(OS_MAP_DAY_STYLE_PREFIX),
            )
        styleId.startsWith(OS_MAP_NIGHT_STYLE_PREFIX) ->
            OsMapStyleSelection(
                modeLabel = "Night",
                prefix = OS_MAP_NIGHT_STYLE_PREFIX,
                realStyleId = styleId.removePrefix(OS_MAP_NIGHT_STYLE_PREFIX),
            )
        else -> null
    }?.takeIf { it.realStyleId.isNotBlank() }
