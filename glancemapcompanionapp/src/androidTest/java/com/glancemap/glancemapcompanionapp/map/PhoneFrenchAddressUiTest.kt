package com.glancemap.glancemapcompanionapp.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneFrenchAddressUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun banAddressIsSelectableAndShowsItsOwnAttribution() {
        val result =
            PhoneMapSearchResult(
                "ban:fixture",
                "12 Rue des Tests",
                "75001 Paris, France · IGN / Base Adresse Nationale",
                PhoneMapCoordinate(48.86, 2.34),
            )
        var selected: PhoneMapSearchResult? = null
        compose.setContent {
            MaterialTheme {
                PhoneMapSearchPanel(
                    PhoneMapSearchUiState(searched = true, reply = PhoneMapSearchReply(listOf(result))),
                    PhoneMapSearchActions({}, {}, {}, {}, { selected = it }, {}),
                )
            }
        }
        compose.onNodeWithText("Addresses: IGN · Base Adresse Nationale · Licence Ouverte 2.0").assertIsDisplayed()
        compose.onNodeWithText("Search: Photon · © OpenStreetMap contributors").assertDoesNotExist()
        compose.onNodeWithText("12 Rue des Tests").performClick()
        compose.runOnIdle { assertEquals(result, selected) }
    }
}
