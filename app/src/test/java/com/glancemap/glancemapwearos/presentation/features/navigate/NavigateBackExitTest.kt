package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigateBackExitTest {
    @Test
    fun closeAppIsOffByDefaultAndBackStaysOnMap() {
        val actions = mutableListOf<String>()
        handleNavigateBackWithoutOverlay(
            backButtonExitsNavigation = false,
            onMenuClick = { actions += "menu" },
            onCloseApp = { actions += "close" },
        )
        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun defaultCloseAppSettingPreservesExistingBackToMenuPreference() {
        val actions = mutableListOf<String>()
        handleNavigateBackWithoutOverlay(
            backButtonExitsNavigation = true,
            onMenuClick = { actions += "menu" },
            onCloseApp = { actions += "close" },
        )
        assertEquals(listOf("menu"), actions)
    }

    @Test
    fun directCloseWorksWithEitherValueOfTheExistingMenuPreference() {
        listOf(false, true).forEach { exitToMenu ->
            assertEquals(listOf("close"), backActions(exitToMenu, closeApp = true))
        }
    }

    @Test
    fun turningDirectCloseOffRestoresExistingBehavior() {
        assertEquals(emptyList<String>(), backActions(exitToMenu = false, closeApp = false))
        assertEquals(listOf("menu"), backActions(exitToMenu = true, closeApp = false))
    }

    private fun backActions(
        exitToMenu: Boolean,
        closeApp: Boolean,
    ): List<String> {
        val actions = mutableListOf<String>()
        handleNavigateBackWithoutOverlay(
            backButtonExitsNavigation = exitToMenu,
            backButtonClosesApp = closeApp,
            onMenuClick = { actions += "menu" },
            onCloseApp = { actions += "close" },
        )
        return actions
    }
}
