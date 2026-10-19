package com.glancemap.glancemapwearos.domain.model.maps.theme

sealed class ThemeListItem {
    data class Header(
        val name: String,
    ) : ThemeListItem()

    data class ThemeOption(
        val id: String,
        val name: String,
        val selected: Boolean,
        val packageId: String? = null,
    ) : ThemeListItem()

    data class GlobalToggle(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val supported: Boolean = true,
    ) : ThemeListItem()

    data class Style(
        val id: String,
        val name: String,
        val selected: Boolean,
    ) : ThemeListItem()

    data class Overlay(
        val layerId: String,
        val name: String,
        val enabled: Boolean,
        val defaultEnabled: Boolean,
    ) : ThemeListItem()
}
