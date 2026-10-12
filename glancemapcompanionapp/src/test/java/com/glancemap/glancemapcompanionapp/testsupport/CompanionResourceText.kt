package com.glancemap.glancemapcompanionapp.testsupport

import com.glancemap.glancemapcompanionapp.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Resolves the shipped English fixtures without depending on mocked Android Resources. */
internal fun englishCompanionText(id: Int): String = requireNotNull(companionEnglishStrings[id])

private val companionEnglishStrings by lazy {
    val source =
        listOf(File("src/main/res/values/strings.xml"), File("glancemapcompanionapp/src/main/res/values/strings.xml"))
            .first(File::isFile)
    val elements =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(source)
            .getElementsByTagName("string")
    val ids = R.string::class.java.fields.associate { field -> field.name to field.getInt(null) }
    (0 until elements.length).associate { index ->
        val element = elements.item(index)
        val value =
            element.textContent
                .removeSurrounding("\"")
                .replace("\\n", "\n")
                .replace("\\'", "'")
                .replace("\\\"", "\"")
        requireNotNull(ids[element.attributes.getNamedItem("name").nodeValue]) to value
    }
}
