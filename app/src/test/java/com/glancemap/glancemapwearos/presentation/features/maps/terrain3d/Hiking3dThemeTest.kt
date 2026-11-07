package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class Hiking3dThemeTest {
    @Test
    fun themeIncludesTrailsPeaksAndContours() {
        val theme = File("src/main/assets/theme/hiking-3d/hiking-3d.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(theme)
        val rules = document.getElementsByTagName("rule")
        val tags = (0 until rules.length).map { rules.item(it).attributes }

        fun hasRule(
            key: String,
            value: String,
        ) = tags.any { attributes ->
            val keys =
                attributes
                    .getNamedItem("k")
                    ?.nodeValue
                    .orEmpty()
                    .split('|')
            val values =
                attributes
                    .getNamedItem("v")
                    ?.nodeValue
                    .orEmpty()
                    .split('|')
            key in keys && value in values
        }

        assertTrue(hasRule("highway", "path"))
        assertTrue(hasRule("natural", "peak"))
        assertTrue(hasRule("contour_ext", "elevation_major"))
    }
}
