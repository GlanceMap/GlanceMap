package com.glancemap.glancemapwearos.data.repository.maps.theme

import android.content.Context
import android.content.SharedPreferences
import com.glancemap.glancemapwearos.core.maps.theme.BundledRenderThemeAssetLocator
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeListItem
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds.DEFAULT_STYLE_ID
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds.HILL_SHADING
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds.NIGHT_MODE
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds.RELIEF_OVERLAY
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ThemeRepositoryImpl(
    private val context: Context,
) : ThemeRepository {
    companion object {
        const val THEME_ID_MAPSFORGE = MapsforgeThemeCatalog.MAPSFORGE_THEME_ID
        const val THEME_ID_ELEVATE = MapsforgeThemeCatalog.ELEVATE_THEME_ID
        const val THEME_ID_ELEVATE_WINTER = MapsforgeThemeCatalog.ELEVATE_WINTER_THEME_ID
        const val THEME_ID_ELEVATE_WINTER_WHITE = MapsforgeThemeCatalog.ELEVATE_WINTER_WHITE_THEME_ID
        const val THEME_ID_OPENHIKING = MapsforgeThemeCatalog.OPENHIKING_THEME_ID
        const val THEME_ID_FRENCH_KISS = MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID
        const val THEME_ID_TIRAMISU = MapsforgeThemeCatalog.TIRAMISU_THEME_ID
        const val THEME_ID_HIKE_RIDE_SIGHT = MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID
        const val THEME_ID_VOLUNTARY = MapsforgeThemeCatalog.VOLUNTARY_THEME_ID
        const val THEME_ID_OS_MAP = MapsforgeThemeCatalog.OS_MAP_THEME_ID
        const val THEME_ID_OS_MAP_DAY = MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID
        const val THEME_ID_OS_MAP_NIGHT = MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID
        private const val WINTER_STYLE_ID = "elv-winter"
        private const val WINTER_WHITE_STYLE_ID = "__WINTER_WHITE__"
        private const val OS_MAP_DAY_STYLE_PREFIX = "__OS_MAP_DAY__:"
        private const val OS_MAP_NIGHT_STYLE_PREFIX = "__OS_MAP_NIGHT__:"
        private const val VOLUNTARY_DEFAULT_STYLE_ID = "vol-hiking"
        private const val TAG = "ThemeRepository"
        private const val KEY_PREFIX_SELECTED_STYLE = "selected_style_"
        private const val KEY_PREFIX_ENABLED_OVERLAYS = "enabled_overlays_for_theme_"
        private const val KEY_LEGACY_SELECTED_STYLE = "selected_style"
        private const val KEY_PREFIX_LEGACY_ENABLED_OVERLAYS = "enabled_overlays_for_style_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    private val keySelectedThemeId = "selected_theme_id"
    private val keyHillShadingEnabled = "global_hill_shading_enabled"
    private val keyReliefOverlayEnabled = "global_relief_overlay_enabled"
    private val keyNightModeEnabled = "global_night_mode_enabled"
    private val keyLegacySlopeOverlayEnabled = "global_slope_overlay_enabled"
    private val winterThemeIds =
        setOf(THEME_ID_ELEVATE_WINTER, THEME_ID_ELEVATE_WINTER_WHITE)
    private val osMapInternalThemeIds =
        setOf(THEME_ID_OS_MAP_DAY, THEME_ID_OS_MAP_NIGHT)

    private val parsedByTheme: Map<String, ParsedStyleMenu> by lazy {
        buildMap {
            put(THEME_ID_ELEVATE, parseThemeStyleMenuFromXml(context, THEME_ID_ELEVATE, TAG))
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_ELEVATE_WINTER)) {
                put(
                    THEME_ID_ELEVATE_WINTER,
                    parseThemeStyleMenuFromXml(context, THEME_ID_ELEVATE_WINTER, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_ELEVATE_WINTER_WHITE)) {
                put(
                    THEME_ID_ELEVATE_WINTER_WHITE,
                    parseThemeStyleMenuFromXml(context, THEME_ID_ELEVATE_WINTER_WHITE, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OPENHIKING)) {
                put(
                    THEME_ID_OPENHIKING,
                    parseThemeStyleMenuFromXml(context, THEME_ID_OPENHIKING, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_FRENCH_KISS)) {
                put(
                    THEME_ID_FRENCH_KISS,
                    parseThemeStyleMenuFromXml(context, THEME_ID_FRENCH_KISS, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_TIRAMISU)) {
                put(
                    THEME_ID_TIRAMISU,
                    parseThemeStyleMenuFromXml(context, THEME_ID_TIRAMISU, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_HIKE_RIDE_SIGHT)) {
                put(
                    THEME_ID_HIKE_RIDE_SIGHT,
                    parseThemeStyleMenuFromXml(context, THEME_ID_HIKE_RIDE_SIGHT, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_VOLUNTARY)) {
                put(
                    THEME_ID_VOLUNTARY,
                    parseThemeStyleMenuFromXml(context, THEME_ID_VOLUNTARY, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OS_MAP_DAY)) {
                put(
                    THEME_ID_OS_MAP_DAY,
                    parseThemeStyleMenuFromXml(context, THEME_ID_OS_MAP_DAY, TAG),
                )
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OS_MAP_NIGHT)) {
                put(
                    THEME_ID_OS_MAP_NIGHT,
                    parseThemeStyleMenuFromXml(context, THEME_ID_OS_MAP_NIGHT, TAG),
                )
            }
        }
    }

    private val nativeHillShadingSupportByBundledTheme: Map<String, Boolean> by lazy {
        buildMap {
            bundledThemeIds().forEach { themeId ->
                if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, themeId)) {
                    put(themeId, bundledThemeSupportsNativeHillShading(context, themeId, TAG))
                }
            }
        }
    }

    override fun getThemeItems(): Flow<List<ThemeListItem>> =
        callbackFlow {
            fun emitCurrent() {
                trySend(getCurrentThemeState()).isSuccess
            }

            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    emitCurrent()
                }

            prefs.registerOnSharedPreferenceChangeListener(listener)
            emitCurrent()

            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    override fun getThemeSelection(): Flow<ThemeSelection> =
        callbackFlow {
            fun emitCurrent() {
                trySend(getCurrentSelection()).isSuccess
            }

            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    emitCurrent()
                }

            prefs.registerOnSharedPreferenceChangeListener(listener)
            emitCurrent()

            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    private fun selectedStyleKeyForTheme(themeId: String): String = "$KEY_PREFIX_SELECTED_STYLE$themeId"

    private fun bundledThemeIds(): List<String> =
        listOf(
            THEME_ID_ELEVATE,
            THEME_ID_ELEVATE_WINTER,
            THEME_ID_ELEVATE_WINTER_WHITE,
            THEME_ID_OPENHIKING,
            THEME_ID_FRENCH_KISS,
            THEME_ID_TIRAMISU,
            THEME_ID_HIKE_RIDE_SIGHT,
            THEME_ID_VOLUNTARY,
            THEME_ID_OS_MAP_DAY,
            THEME_ID_OS_MAP_NIGHT,
        )

    private fun themeSupportsNativeHillShading(themeId: String?): Boolean {
        val normalizedThemeId = themeId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return when {
            MapsforgeThemeCatalog.isMapsforgeFamilyTheme(normalizedThemeId) -> true
            MapsforgeThemeCatalog.isMapsforgeStyleId(normalizedThemeId) -> true
            MapsforgeThemeCatalog.isBundledAssetTheme(normalizedThemeId) -> {
                nativeHillShadingSupportByBundledTheme[normalizedThemeId] == true
            }
            else -> false
        }
    }

    private fun isWinterFamilyTheme(themeId: String): Boolean = themeId in winterThemeIds

    private fun isOsMapInternalTheme(themeId: String): Boolean = themeId in osMapInternalThemeIds

    private fun osMapDefaultInternalThemeId(): String =
        when {
            BundledRenderThemeAssetLocator.isThemeAvailable(
                context.assets,
                THEME_ID_OS_MAP_DAY,
            ) -> THEME_ID_OS_MAP_DAY
            BundledRenderThemeAssetLocator.isThemeAvailable(
                context.assets,
                THEME_ID_OS_MAP_NIGHT,
            ) -> THEME_ID_OS_MAP_NIGHT
            else -> THEME_ID_ELEVATE
        }

    private fun osMapRenderingStyleId(
        themeId: String,
        parsed: ParsedStyleMenu,
    ): String {
        val storedStyleId =
            prefs
                .getString(selectedStyleKeyForTheme(themeId), null)
                ?.trim()
                .orEmpty()
        return if (storedStyleId.isNotEmpty() && isStyleAllowedForTheme(themeId, storedStyleId, parsed)) {
            storedStyleId
        } else {
            resolveDefaultStyleId(themeId, parsed) ?: DEFAULT_STYLE_ID
        }
    }

    private data class OsMapStyleSelection(
        val themeId: String,
        val realStyleId: String,
    )

    private fun osMapSyntheticStyleId(
        themeId: String,
        realStyleId: String,
    ): String {
        val prefix = if (themeId == THEME_ID_OS_MAP_NIGHT) OS_MAP_NIGHT_STYLE_PREFIX else OS_MAP_DAY_STYLE_PREFIX
        return "$prefix$realStyleId"
    }

    private fun parseOsMapSyntheticStyleId(styleId: String): OsMapStyleSelection? =
        when {
            styleId.startsWith(OS_MAP_DAY_STYLE_PREFIX) ->
                OsMapStyleSelection(
                    themeId = THEME_ID_OS_MAP_DAY,
                    realStyleId = styleId.removePrefix(OS_MAP_DAY_STYLE_PREFIX),
                )
            styleId.startsWith(OS_MAP_NIGHT_STYLE_PREFIX) ->
                OsMapStyleSelection(
                    themeId = THEME_ID_OS_MAP_NIGHT,
                    realStyleId = styleId.removePrefix(OS_MAP_NIGHT_STYLE_PREFIX),
                )
            else -> null
        }?.takeIf { it.realStyleId.isNotBlank() }

    private fun enabledOverlaysKeyFor(
        themeId: String,
        styleId: String,
    ): String = "${KEY_PREFIX_ENABLED_OVERLAYS}${themeId}_style_$styleId"

    private fun legacyEnabledOverlaysKeyFor(styleId: String): String = "$KEY_PREFIX_LEGACY_ENABLED_OVERLAYS$styleId"

    private fun parsedForTheme(themeId: String): ParsedStyleMenu {
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(themeId)) {
            return ParsedStyleMenu(
                defaultStyleId = null,
                styles = emptyList(),
                overlayDefinitions = emptyMap(),
            )
        }
        return parsedByTheme[themeId] ?: parsedByTheme[THEME_ID_ELEVATE] ?: ParsedStyleMenu(
            defaultStyleId = null,
            styles = emptyList(),
            overlayDefinitions = emptyMap(),
        )
    }

    private fun selectedThemeIdForUi(themeId: String): String =
        when {
            themeId == THEME_ID_ELEVATE_WINTER_WHITE -> THEME_ID_ELEVATE_WINTER
            isOsMapInternalTheme(themeId) -> THEME_ID_OS_MAP
            else -> themeId
        }

    private fun mapsforgeStyleIdOrDefault(): String {
        val storedStyleId =
            prefs
                .getString(selectedStyleKeyForTheme(THEME_ID_MAPSFORGE), null)
                ?.trim()
                .orEmpty()
        return MapsforgeThemeCatalog.optionById(storedStyleId)?.id
            ?: MapsforgeThemeCatalog.defaultOption().id
    }

    private fun migrateLegacyMapsforgeSelection(styleId: String): String {
        val resolvedStyleId =
            MapsforgeThemeCatalog.optionById(styleId)?.id
                ?: MapsforgeThemeCatalog.defaultOption().id
        prefs.edit().apply {
            putString(keySelectedThemeId, THEME_ID_MAPSFORGE)
            putString(selectedStyleKeyForTheme(THEME_ID_MAPSFORGE), resolvedStyleId)
            apply()
        }
        return THEME_ID_MAPSFORGE
    }

    private fun normalizeIncomingStyleIdForTheme(
        themeId: String,
        styleId: String,
    ): String =
        when {
            isWinterFamilyTheme(themeId) && styleId == WINTER_WHITE_STYLE_ID -> WINTER_STYLE_ID
            isOsMapInternalTheme(themeId) -> parseOsMapSyntheticStyleId(styleId)?.realStyleId ?: styleId
            else -> styleId
        }

    override suspend fun setTheme(themeId: String) {
        val validThemeId =
            when {
                themeId == THEME_ID_OS_MAP -> osMapDefaultInternalThemeId()
                MapsforgeThemeCatalog.isBundledAssetTheme(themeId) &&
                    BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, themeId) -> themeId
                MapsforgeThemeCatalog.isMapsforgeFamilyTheme(themeId) -> themeId
                else -> THEME_ID_ELEVATE
            }
        val normalizedThemeId =
            if (validThemeId == THEME_ID_ELEVATE_WINTER_WHITE) {
                THEME_ID_ELEVATE_WINTER
            } else {
                validThemeId
            }
        prefs.edit().apply {
            putString(keySelectedThemeId, normalizedThemeId)
            if (isWinterFamilyTheme(normalizedThemeId)) {
                putString(selectedStyleKeyForTheme(normalizedThemeId), WINTER_STYLE_ID)
            }
            if (isOsMapInternalTheme(normalizedThemeId)) {
                putString(selectedStyleKeyForTheme(normalizedThemeId), DEFAULT_STYLE_ID)
            }
            if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(normalizedThemeId)) {
                putString(selectedStyleKeyForTheme(normalizedThemeId), mapsforgeStyleIdOrDefault())
            }
            apply()
        }
    }

    override suspend fun setMapStyle(styleId: String) {
        val selectedThemeId = selectedThemeIdOrDefault()
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(selectedThemeId)) {
            setMapsforgeStyle(styleId)
            return
        }
        if (!MapsforgeThemeCatalog.isBundledAssetTheme(selectedThemeId)) return

        if (isOsMapInternalTheme(selectedThemeId)) {
            setOsMapStyle(selectedThemeId, styleId)
            return
        }

        if (isWinterFamilyTheme(selectedThemeId)) {
            setWinterStyle(styleId)
            return
        }

        setStandardBundledStyle(selectedThemeId, styleId)
    }

    private fun setMapsforgeStyle(styleId: String) {
        val resolvedStyleId =
            MapsforgeThemeCatalog.optionById(styleId)?.id
                ?: MapsforgeThemeCatalog.defaultOption().id
        prefs.edit().apply {
            putString(keySelectedThemeId, THEME_ID_MAPSFORGE)
            putString(selectedStyleKeyForTheme(THEME_ID_MAPSFORGE), resolvedStyleId)
            apply()
        }
    }

    private fun setOsMapStyle(
        selectedThemeId: String,
        styleId: String,
    ) {
        val requested = parseOsMapSyntheticStyleId(styleId)
        val targetThemeId = requested?.themeId ?: selectedThemeId
        val availableTargetThemeId =
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, targetThemeId)) {
                targetThemeId
            } else {
                osMapDefaultInternalThemeId()
            }
        val targetParsed = parsedForTheme(availableTargetThemeId)
        val requestedStyleId =
            requested?.realStyleId ?: osMapRenderingStyleId(availableTargetThemeId, targetParsed)
        val resolvedStyleId =
            if (isStyleAllowedForTheme(availableTargetThemeId, requestedStyleId, targetParsed)) {
                requestedStyleId
            } else {
                resolveDefaultStyleId(availableTargetThemeId, targetParsed) ?: DEFAULT_STYLE_ID
            }
        prefs.edit().apply {
            putString(keySelectedThemeId, availableTargetThemeId)
            putString(selectedStyleKeyForTheme(availableTargetThemeId), resolvedStyleId)
            apply()
        }
    }

    private fun setWinterStyle(styleId: String) {
        val targetThemeId =
            when {
                styleId == WINTER_WHITE_STYLE_ID &&
                    BundledRenderThemeAssetLocator.isThemeAvailable(
                        context.assets,
                        THEME_ID_ELEVATE_WINTER_WHITE,
                    ) -> THEME_ID_ELEVATE_WINTER_WHITE
                else -> THEME_ID_ELEVATE_WINTER
            }
        val targetParsed = parsedForTheme(targetThemeId)
        val resolvedStyleId =
            when {
                isStyleAllowedForTheme(targetThemeId, WINTER_STYLE_ID, targetParsed) -> WINTER_STYLE_ID
                else -> resolveDefaultStyleId(targetThemeId, targetParsed) ?: DEFAULT_STYLE_ID
            }
        prefs.edit().apply {
            putString(keySelectedThemeId, targetThemeId)
            putString(selectedStyleKeyForTheme(targetThemeId), resolvedStyleId)
            apply()
        }
    }

    private fun setStandardBundledStyle(
        selectedThemeId: String,
        styleId: String,
    ) {
        val parsed = parsedForTheme(selectedThemeId)
        val normalizedStyleId = normalizeIncomingStyleIdForTheme(selectedThemeId, styleId)
        val effectiveStyleId =
            when {
                isWinterFamilyTheme(selectedThemeId) &&
                    normalizedStyleId == DEFAULT_STYLE_ID &&
                    isStyleAllowedForTheme(selectedThemeId, WINTER_STYLE_ID, parsed) -> WINTER_STYLE_ID
                normalizedStyleId == DEFAULT_STYLE_ID -> DEFAULT_STYLE_ID
                isStyleAllowedForTheme(selectedThemeId, normalizedStyleId, parsed) -> normalizedStyleId
                else -> DEFAULT_STYLE_ID
            }
        prefs
            .edit()
            .putString(selectedStyleKeyForTheme(selectedThemeId), effectiveStyleId)
            .apply()
    }

    override suspend fun toggleOverlay(
        styleId: String,
        overlayId: String,
    ) {
        val selectedThemeId = selectedThemeIdOrDefault()
        if (!MapsforgeThemeCatalog.isBundledAssetTheme(selectedThemeId)) return
        val parsed = parsedForTheme(selectedThemeId)
        val requestedStyleId = normalizeIncomingStyleIdForTheme(selectedThemeId, styleId)

        val effectiveStyleId = resolveEffectiveStyleId(requestedStyleId, selectedThemeId, parsed) ?: return
        val style = parsed.stylesById[effectiveStyleId] ?: return
        if (overlayId !in style.overlayLayerIds) return

        val key = enabledOverlaysKeyFor(selectedThemeId, style.id)
        val current =
            if (requestedStyleId == DEFAULT_STYLE_ID) {
                // "Default" must remain pristine even if explicit default style has custom overlays.
                getDefaultEnabledOverlays(style, parsed).toMutableSet()
            } else {
                readEnabledOverlaysForStyle(selectedThemeId, style, parsed).toMutableSet()
            }

        if (current.contains(overlayId)) current.remove(overlayId) else current.add(overlayId)

        prefs.edit().apply {
            // If user tweaks overlays while "Default" is selected, promote to explicit style.
            if (requestedStyleId == DEFAULT_STYLE_ID) {
                putString(selectedStyleKeyForTheme(selectedThemeId), style.id)
            }
            putStringSet(key, current)
            apply()
        }
    }

    override suspend fun setOverlaysForStyle(
        styleId: String,
        enabledOverlayLayerIds: Set<String>,
    ) {
        val selectedThemeId = selectedThemeIdOrDefault()
        if (!MapsforgeThemeCatalog.isBundledAssetTheme(selectedThemeId)) return
        val parsed = parsedForTheme(selectedThemeId)
        val requestedStyleId = normalizeIncomingStyleIdForTheme(selectedThemeId, styleId)

        val effectiveStyleId = resolveEffectiveStyleId(requestedStyleId, selectedThemeId, parsed) ?: return
        val style = parsed.stylesById[effectiveStyleId] ?: return

        val allowed = style.overlayLayerIds.toSet()
        val sanitized =
            enabledOverlayLayerIds
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in allowed }
                .toSet()

        val key = enabledOverlaysKeyFor(selectedThemeId, style.id)
        val defaults = getDefaultEnabledOverlays(style, parsed)

        prefs.edit().apply {
            if (requestedStyleId == DEFAULT_STYLE_ID) {
                if (sanitized == defaults) {
                    putString(selectedStyleKeyForTheme(selectedThemeId), DEFAULT_STYLE_ID)
                    remove(key)
                } else {
                    putString(selectedStyleKeyForTheme(selectedThemeId), style.id)
                    putStringSet(key, sanitized)
                }
            } else {
                putStringSet(key, sanitized)
            }
            apply()
        }
    }

    override suspend fun setHillShadingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(keyHillShadingEnabled, enabled).apply()
    }

    override suspend fun setReliefOverlayEnabled(enabled: Boolean) {
        prefs
            .edit()
            .putBoolean(keyReliefOverlayEnabled, enabled)
            .remove(keyLegacySlopeOverlayEnabled)
            .apply()
    }

    override suspend fun setNightModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(keyNightModeEnabled, enabled).apply()
    }

    override suspend fun resetToDefaults() {
        val editor =
            prefs
                .edit()
                .remove(keySelectedThemeId)
                .remove(KEY_LEGACY_SELECTED_STYLE)
                .remove(keyHillShadingEnabled)
                .remove(keyReliefOverlayEnabled)
                .remove(keyNightModeEnabled)
                .remove(keyLegacySlopeOverlayEnabled)
        parsedByTheme.forEach { (themeId, parsed) ->
            editor.remove(selectedStyleKeyForTheme(themeId))
            parsed.styles.forEach { style ->
                editor.remove(enabledOverlaysKeyFor(themeId, style.id))
                editor.remove(legacyEnabledOverlaysKeyFor(style.id))
            }
        }
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_PREFIX_SELECTED_STYLE) || it.startsWith(KEY_PREFIX_ENABLED_OVERLAYS) }
            .forEach { editor.remove(it) }
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_PREFIX_LEGACY_ENABLED_OVERLAYS) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun getDefaultEnabledOverlays(
        style: StyleDefinition,
        parsed: ParsedStyleMenu,
    ): Set<String> =
        style.overlayLayerIds
            .asSequence()
            .filter { layerId -> parsed.overlayDefinitions[layerId]?.enabledByDefault == true }
            .toSet()

    private fun readEnabledOverlaysForStyle(
        themeId: String,
        style: StyleDefinition,
        parsed: ParsedStyleMenu,
    ): Set<String> {
        val defaultEnabled = getDefaultEnabledOverlays(style, parsed)
        val allowed = style.overlayLayerIds.toSet()
        val key = enabledOverlaysKeyFor(themeId, style.id)
        val storedRaw =
            prefs.getStringSet(key, null)
                ?: if (themeId == THEME_ID_ELEVATE) prefs.getStringSet(legacyEnabledOverlaysKeyFor(style.id), null) else null
        val stored =
            storedRaw
                ?.asSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: return defaultEnabled

        val valid = stored.filterTo(mutableSetOf()) { it in allowed }
        if (valid.isEmpty() && stored.isNotEmpty()) {
            // Legacy prefs may contain old group ids. Fall back to style defaults.
            return defaultEnabled
        }
        return valid
    }

    private fun selectedThemeIdOrDefault(): String {
        val selected = prefs.getString(keySelectedThemeId, null)?.trim().orEmpty()
        if (selected == MapsforgeThemeCatalog.LEGACY_HILLSHADING_THEME_ID) {
            // Legacy choice removed from UI: keep user in Mapsforge family on the default style.
            return migrateLegacyMapsforgeSelection(MapsforgeThemeCatalog.defaultOption().id)
        }
        if (MapsforgeThemeCatalog.isMapsforgeStyleId(selected)) {
            return migrateLegacyMapsforgeSelection(selected)
        }
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(selected)) return THEME_ID_MAPSFORGE
        if (MapsforgeThemeCatalog.isBundledAssetTheme(selected) &&
            BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, selected)
        ) {
            return selected
        }
        if (selected == THEME_ID_OS_MAP) {
            return osMapDefaultInternalThemeId()
        }
        if (selected == THEME_ID_ELEVATE_WINTER_WHITE &&
            BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_ELEVATE_WINTER)
        ) {
            return THEME_ID_ELEVATE_WINTER
        }
        return THEME_ID_ELEVATE
    }

    private fun selectedStyleIdOrDefault(
        themeId: String,
        parsed: ParsedStyleMenu,
    ): String {
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(themeId)) {
            return mapsforgeStyleIdOrDefault()
        }
        val styleKey = selectedStyleKeyForTheme(themeId)
        val storedSelectedStyle = prefs.getString(styleKey, null)
        val legacySelectedStyle =
            if (themeId == THEME_ID_ELEVATE) {
                prefs.getString(KEY_LEGACY_SELECTED_STYLE, DEFAULT_STYLE_ID)
            } else {
                DEFAULT_STYLE_ID
            }
        val selectedStyleId = (storedSelectedStyle ?: legacySelectedStyle ?: DEFAULT_STYLE_ID).trim()
        if (isWinterFamilyTheme(themeId)) {
            if (isStyleAllowedForTheme(themeId, selectedStyleId, parsed)) return selectedStyleId
            return if (isStyleAllowedForTheme(themeId, WINTER_STYLE_ID, parsed)) WINTER_STYLE_ID else DEFAULT_STYLE_ID
        }
        if (isOsMapInternalTheme(themeId)) {
            return osMapSyntheticStyleId(
                themeId = themeId,
                realStyleId = osMapRenderingStyleId(themeId, parsed),
            )
        }
        if (selectedStyleId == DEFAULT_STYLE_ID) return DEFAULT_STYLE_ID
        return if (isStyleAllowedForTheme(themeId, selectedStyleId, parsed)) selectedStyleId else DEFAULT_STYLE_ID
    }

    private fun hillShadingEnabledOrDefault(): Boolean = prefs.getBoolean(keyHillShadingEnabled, false)

    private fun nightModeEnabledOrDefault(): Boolean = prefs.getBoolean(keyNightModeEnabled, false)

    private fun reliefOverlayEnabledOrDefault(): Boolean =
        when {
            prefs.contains(keyReliefOverlayEnabled) -> prefs.getBoolean(keyReliefOverlayEnabled, false)
            prefs.contains(keyLegacySlopeOverlayEnabled) -> prefs.getBoolean(keyLegacySlopeOverlayEnabled, false)
            else -> false
        }

    private fun resolveDefaultStyleId(
        themeId: String,
        parsed: ParsedStyleMenu,
    ): String? {
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(themeId)) {
            return MapsforgeThemeCatalog.defaultOption().id
        }
        if (isWinterFamilyTheme(themeId) && parsed.stylesById.containsKey(WINTER_STYLE_ID)) {
            return WINTER_STYLE_ID
        }
        if (themeId == THEME_ID_VOLUNTARY && parsed.stylesById.containsKey(VOLUNTARY_DEFAULT_STYLE_ID)) {
            return VOLUNTARY_DEFAULT_STYLE_ID
        }
        val fromMenu = parsed.defaultStyleId
        if (fromMenu != null && isStyleAllowedForTheme(themeId, fromMenu, parsed)) return fromMenu
        return allowedStylesForTheme(themeId, parsed).firstOrNull()?.id
    }

    private fun resolveEffectiveStyleId(
        selectedStyleId: String,
        themeId: String,
        parsed: ParsedStyleMenu,
    ): String? =
        if (selectedStyleId == DEFAULT_STYLE_ID) {
            resolveDefaultStyleId(themeId, parsed)
        } else {
            if (isStyleAllowedForTheme(themeId, selectedStyleId, parsed)) {
                selectedStyleId
            } else {
                resolveDefaultStyleId(themeId, parsed)
            }
        }

    private fun getCurrentSelection(): ThemeSelection {
        val selectedThemeId = selectedThemeIdOrDefault()
        val nightModeEnabled = nightModeEnabledOrDefault()
        val hillShadingPreferenceEnabled = hillShadingEnabledOrDefault()
        val renderingThemeId = if (nightModeEnabled) THEME_ID_MAPSFORGE else selectedThemeId
        val hillShadingEnabled =
            hillShadingPreferenceEnabled &&
                themeSupportsNativeHillShading(renderingThemeId)
        val reliefOverlayEnabled = reliefOverlayEnabledOrDefault()
        if (nightModeEnabled) {
            val darkOption =
                MapsforgeThemeCatalog.optionById(MapsforgeThemeCatalog.DARK_MAPSFORGE_STYLE_ID)
                    ?: MapsforgeThemeCatalog.defaultOption()
            return ThemeSelection(
                themeId = THEME_ID_MAPSFORGE,
                mapsforgeThemeName = darkOption.themeName,
                styleId = darkOption.id,
                enabledOverlayLayerIds = emptyList(),
                hillShadingEnabled = hillShadingEnabled,
                reliefOverlayEnabled = reliefOverlayEnabled,
                nightModeEnabled = true,
            )
        }
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(selectedThemeId)) {
            val selectedStyleId = mapsforgeStyleIdOrDefault()
            val mapsforgeOption =
                MapsforgeThemeCatalog.optionById(selectedStyleId)
                    ?: MapsforgeThemeCatalog.defaultOption()
            return ThemeSelection(
                themeId = selectedThemeId,
                mapsforgeThemeName = mapsforgeOption.themeName,
                styleId = selectedStyleId,
                enabledOverlayLayerIds = emptyList(),
                hillShadingEnabled = hillShadingEnabled,
                reliefOverlayEnabled = reliefOverlayEnabled,
                nightModeEnabled = nightModeEnabled,
            )
        }

        val parsed = parsedForTheme(selectedThemeId)
        val selectedStyleId =
            if (isWinterFamilyTheme(selectedThemeId)) {
                WINTER_STYLE_ID
            } else {
                selectedStyleIdOrDefault(selectedThemeId, parsed)
            }
        val renderingStyleId =
            if (isOsMapInternalTheme(selectedThemeId)) {
                osMapRenderingStyleId(selectedThemeId, parsed)
            } else {
                selectedStyleId
            }
        val effectiveStyleId =
            resolveEffectiveStyleId(renderingStyleId, selectedThemeId, parsed) ?: return ThemeSelection(
                themeId = selectedThemeId,
                mapsforgeThemeName = null,
                styleId = DEFAULT_STYLE_ID,
                enabledOverlayLayerIds = emptyList(),
                hillShadingEnabled = hillShadingEnabled,
                reliefOverlayEnabled = reliefOverlayEnabled,
                nightModeEnabled = nightModeEnabled,
            )
        val selectedStyle =
            parsed.stylesById[effectiveStyleId] ?: return ThemeSelection(
                themeId = selectedThemeId,
                mapsforgeThemeName = null,
                styleId = DEFAULT_STYLE_ID,
                enabledOverlayLayerIds = emptyList(),
                hillShadingEnabled = hillShadingEnabled,
                reliefOverlayEnabled = reliefOverlayEnabled,
                nightModeEnabled = nightModeEnabled,
            )
        val enabledLayerIds =
            if (renderingStyleId == DEFAULT_STYLE_ID) {
                getDefaultEnabledOverlays(selectedStyle, parsed).toList().sorted()
            } else {
                readEnabledOverlaysForStyle(selectedThemeId, selectedStyle, parsed).toList().sorted()
            }

        return ThemeSelection(
            themeId = selectedThemeId,
            mapsforgeThemeName = null,
            styleId = renderingStyleId,
            enabledOverlayLayerIds = enabledLayerIds,
            hillShadingEnabled = hillShadingEnabled,
            reliefOverlayEnabled = reliefOverlayEnabled,
            nightModeEnabled = nightModeEnabled,
        )
    }

    private fun getCurrentThemeState(): List<ThemeListItem> {
        val selectedThemeId = selectedThemeIdOrDefault()
        val selectedThemeIdUi = selectedThemeIdForUi(selectedThemeId)
        val parsed = parsedForTheme(selectedThemeId)
        val selectedStyleId = selectedStyleIdOrDefault(selectedThemeId, parsed)
        val hillShadingEnabled = hillShadingEnabledOrDefault()
        val hillShadingSupported = themeSupportsNativeHillShading(selectedThemeId)
        val reliefOverlayEnabled = reliefOverlayEnabledOrDefault()
        val nightModeEnabled = nightModeEnabledOrDefault()
        val items = mutableListOf<ThemeListItem>()

        items += ThemeListItem.Header("Theme")
        items +=
            ThemeListItem.GlobalToggle(
                id = NIGHT_MODE,
                name = "Night mode",
                enabled = nightModeEnabled,
            )
        items +=
            ThemeListItem.ThemeOption(
                id = THEME_ID_ELEVATE,
                name = "Elevate",
                selected = selectedThemeIdUi == THEME_ID_ELEVATE,
            )
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_ELEVATE_WINTER) ||
            BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_ELEVATE_WINTER_WHITE)
        ) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_ELEVATE_WINTER,
                    name = "Elevate Winter",
                    selected = selectedThemeIdUi == THEME_ID_ELEVATE_WINTER,
                )
        }
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_HIKE_RIDE_SIGHT)) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_HIKE_RIDE_SIGHT,
                    name = "Hike, Ride & Sight",
                    selected = selectedThemeIdUi == THEME_ID_HIKE_RIDE_SIGHT,
                )
        }
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_VOLUNTARY)) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_VOLUNTARY,
                    name = "Voluntary",
                    selected = selectedThemeIdUi == THEME_ID_VOLUNTARY,
                )
        }
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OS_MAP_DAY) ||
            BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OS_MAP_NIGHT)
        ) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_OS_MAP,
                    name = "OS Map",
                    selected = selectedThemeIdUi == THEME_ID_OS_MAP,
                )
        }
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OPENHIKING)) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_OPENHIKING,
                    name = "OpenHiking",
                    selected = selectedThemeIdUi == THEME_ID_OPENHIKING,
                )
        }
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_FRENCH_KISS)) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_FRENCH_KISS,
                    name = "French Kiss",
                    selected = selectedThemeIdUi == THEME_ID_FRENCH_KISS,
                )
        }
        if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_TIRAMISU)) {
            items +=
                ThemeListItem.ThemeOption(
                    id = THEME_ID_TIRAMISU,
                    name = "Tiramisu",
                    selected = selectedThemeIdUi == THEME_ID_TIRAMISU,
                )
        }
        items +=
            ThemeListItem.ThemeOption(
                id = THEME_ID_MAPSFORGE,
                name = "Mapsforge",
                selected = selectedThemeIdUi == THEME_ID_MAPSFORGE,
            )
        items +=
            ThemeListItem.GlobalToggle(
                id = HILL_SHADING,
                name = "Hill Shading",
                enabled = hillShadingEnabled,
                supported = hillShadingSupported,
            )
        items +=
            ThemeListItem.GlobalToggle(
                id = RELIEF_OVERLAY,
                name = "Slope Overlay",
                enabled = reliefOverlayEnabled,
            )

        if (!themeSupportsStyleSelection(selectedThemeId, parsed)) {
            return items
        }

        items += ThemeListItem.Header("Theme Style")
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(selectedThemeId)) {
            items +=
                MapsforgeThemeCatalog.options.map { option ->
                    ThemeListItem.Style(
                        id = option.id,
                        name = option.label,
                        selected = option.id == selectedStyleId,
                    )
                }
        } else if (isWinterFamilyTheme(selectedThemeId)) {
            items +=
                ThemeListItem.Style(
                    id = WINTER_STYLE_ID,
                    name = "Default",
                    selected = selectedThemeId != THEME_ID_ELEVATE_WINTER_WHITE,
                )
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_ELEVATE_WINTER_WHITE)) {
                items +=
                    ThemeListItem.Style(
                        id = WINTER_WHITE_STYLE_ID,
                        name = "White",
                        selected = selectedThemeId == THEME_ID_ELEVATE_WINTER_WHITE,
                    )
            }
        } else if (isOsMapInternalTheme(selectedThemeId)) {
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OS_MAP_DAY)) {
                items +=
                    allowedStylesForTheme(THEME_ID_OS_MAP_DAY, parsedForTheme(THEME_ID_OS_MAP_DAY)).map { style ->
                        ThemeListItem.Style(
                            id = osMapSyntheticStyleId(THEME_ID_OS_MAP_DAY, style.id),
                            name = "Day - ${style.name}",
                            selected =
                                selectedThemeId == THEME_ID_OS_MAP_DAY &&
                                    selectedStyleId == osMapSyntheticStyleId(THEME_ID_OS_MAP_DAY, style.id),
                        )
                    }
            }
            if (BundledRenderThemeAssetLocator.isThemeAvailable(context.assets, THEME_ID_OS_MAP_NIGHT)) {
                items +=
                    allowedStylesForTheme(THEME_ID_OS_MAP_NIGHT, parsedForTheme(THEME_ID_OS_MAP_NIGHT)).map { style ->
                        ThemeListItem.Style(
                            id = osMapSyntheticStyleId(THEME_ID_OS_MAP_NIGHT, style.id),
                            name = "Night - ${style.name}",
                            selected =
                                selectedThemeId == THEME_ID_OS_MAP_NIGHT &&
                                    selectedStyleId == osMapSyntheticStyleId(THEME_ID_OS_MAP_NIGHT, style.id),
                        )
                    }
            }
        } else {
            val allowedStyles = allowedStylesForTheme(selectedThemeId, parsed)
            val defaultStyleName =
                resolveDefaultStyleId(selectedThemeId, parsed)
                    ?.let { parsed.stylesById[it]?.name }
                    ?: "Elevate"
            val defaultStyleLabel =
                when (selectedThemeId) {
                    THEME_ID_ELEVATE_WINTER -> "Default (Winter)"
                    THEME_ID_ELEVATE_WINTER_WHITE -> "Default (White ski)"
                    else -> "Default ($defaultStyleName)"
                }

            items +=
                ThemeListItem.Style(
                    id = DEFAULT_STYLE_ID,
                    name = defaultStyleLabel,
                    selected = selectedStyleId == DEFAULT_STYLE_ID,
                )
            items +=
                allowedStyles.map { style ->
                    ThemeListItem.Style(
                        id = style.id,
                        name = style.name,
                        selected = style.id == selectedStyleId,
                    )
                }
        }

        if (!MapsforgeThemeCatalog.isBundledAssetTheme(selectedThemeId)) {
            return items
        }

        val selectedStyleIdForOverlay =
            when {
                isWinterFamilyTheme(selectedThemeId) -> WINTER_STYLE_ID
                isOsMapInternalTheme(selectedThemeId) -> osMapRenderingStyleId(selectedThemeId, parsed)
                else -> selectedStyleId
            }
        val effectiveStyleId = resolveEffectiveStyleId(selectedStyleIdForOverlay, selectedThemeId, parsed)
        val selectedStyle = effectiveStyleId?.let { parsed.stylesById[it] }
        if (selectedStyle != null && selectedStyle.overlayLayerIds.isNotEmpty()) {
            items += ThemeListItem.Header("Enabled Overlays")
            val enabledLayerIds =
                if (selectedStyleIdForOverlay == DEFAULT_STYLE_ID) {
                    getDefaultEnabledOverlays(selectedStyle, parsed)
                } else {
                    readEnabledOverlaysForStyle(selectedThemeId, selectedStyle, parsed)
                }
            val overlays =
                selectedStyle.overlayLayerIds.map { layerId ->
                    ThemeListItem.Overlay(
                        layerId = layerId,
                        name = parsed.overlayDefinitions[layerId]?.name ?: layerId,
                        enabled = layerId in enabledLayerIds,
                        defaultEnabled = parsed.overlayDefinitions[layerId]?.enabledByDefault == true,
                    )
                }
            items += overlays
        }

        return items
    }

    private fun allowedStylesForTheme(
        themeId: String,
        parsed: ParsedStyleMenu,
    ): List<StyleDefinition> {
        if (!isWinterFamilyTheme(themeId)) return parsed.styles
        val winterStyle = parsed.stylesById[WINTER_STYLE_ID] ?: return parsed.styles
        return listOf(winterStyle)
    }

    private fun themeSupportsStyleSelection(
        themeId: String,
        parsed: ParsedStyleMenu,
    ): Boolean =
        when {
            MapsforgeThemeCatalog.isMapsforgeFamilyTheme(themeId) -> MapsforgeThemeCatalog.options.isNotEmpty()
            isWinterFamilyTheme(themeId) -> {
                allowedStylesForTheme(themeId, parsed).isNotEmpty()
            }
            else -> parsed.styles.isNotEmpty()
        }

    private fun isStyleAllowedForTheme(
        themeId: String,
        styleId: String,
        parsed: ParsedStyleMenu,
    ): Boolean {
        if (MapsforgeThemeCatalog.isMapsforgeFamilyTheme(themeId)) {
            return MapsforgeThemeCatalog.optionById(styleId) != null
        }
        if (!parsed.stylesById.containsKey(styleId)) return false
        return allowedStylesForTheme(themeId, parsed).any { it.id == styleId }
    }
}
