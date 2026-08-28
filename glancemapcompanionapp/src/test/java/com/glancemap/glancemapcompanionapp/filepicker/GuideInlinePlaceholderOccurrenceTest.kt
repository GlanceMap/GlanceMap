package com.glancemap.glancemapcompanionapp.filepicker

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideInlinePlaceholderOccurrenceTest {
    @Test
    fun `orders inline icons in source placeholder order`() {
        val placeholders = listOf("__first_icon__", "__second_icon__")

        assertEquals(
            listOf(
                GuideInlinePlaceholderOccurrence(inlineIconIndex = 0, startIndex = 6),
                GuideInlinePlaceholderOccurrence(inlineIconIndex = 1, startIndex = 26),
            ),
            guideInlinePlaceholderOccurrences(
                text = "Start __first_icon__ then __second_icon__.",
                placeholders = placeholders,
            ),
        )
    }

    @Test
    fun `orders inline icons by reordered translated placeholders`() {
        val placeholders = listOf("__first_icon__", "__second_icon__")

        assertEquals(
            listOf(
                GuideInlinePlaceholderOccurrence(inlineIconIndex = 1, startIndex = 6),
                GuideInlinePlaceholderOccurrence(inlineIconIndex = 0, startIndex = 27),
            ),
            guideInlinePlaceholderOccurrences(
                text = "Start __second_icon__ then __first_icon__.",
                placeholders = placeholders,
            ),
        )
    }
}
