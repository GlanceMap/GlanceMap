package com.glancemap.glancemapwearos.presentation.features.gpx

import org.junit.Assert.assertEquals
import org.junit.Test

class GpxHelpInlineTextTest {
    @Test
    fun iconsCanBeReorderedAndRepeatedWithoutChangingSentenceSpacing() {
        val text =
            gpxHelpAnnotatedText(
                "Use __delete__, then __edit__ or __delete__.",
                mapOf("edit" to "[edit]", "delete" to "[delete]"),
            )
        assertEquals("Use [delete], then [edit] or [delete].", text.text)
    }
}
