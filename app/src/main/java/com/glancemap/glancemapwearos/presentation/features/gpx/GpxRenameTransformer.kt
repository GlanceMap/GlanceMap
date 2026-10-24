package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolArgumentException
import java.util.ArrayDeque

/**
 * Changes only direct metadata and track names. The source GPX is otherwise copied byte-for-byte
 * so recording metadata and point extensions are retained when a user renames an activity.
 */
internal fun renameGpxXmlTitle(
    sourceXml: String,
    newTitle: String,
): ByteArray {
    val output = StringBuilder(sourceXml.length + newTitle.length)
    val openElements = ArrayDeque<String>()
    var cursor = 0

    while (cursor < sourceXml.length) {
        val markup = nextXmlMarkup(sourceXml, cursor)
        if (markup == null) {
            output.append(sourceXml, cursor, sourceXml.length)
            cursor = sourceXml.length
        } else {
            output.append(sourceXml, cursor, markup.endExclusive)
            val replacementEnd =
                markup.directTitleClosingTagStart(
                    sourceXml = sourceXml,
                    openElements = openElements,
                )
            if (replacementEnd != null) {
                output.append(newTitle.escapeXmlText())
                cursor = replacementEnd
            } else {
                cursor = markup.endExclusive
            }
        }
    }

    return output.toString().toByteArray(Charsets.UTF_8)
}

private fun findMatchingClosingTag(
    sourceXml: String,
    startIndex: Int,
    tagName: String,
): Int? {
    var cursor = startIndex
    var matchingTagDepth = 1
    var closingTagStart: Int? = null

    while (cursor < sourceXml.length && closingTagStart == null) {
        val markup = nextXmlMarkup(sourceXml, cursor)
        if (markup == null) {
            cursor = sourceXml.length
        } else {
            val matchingTag =
                markup.rawTag?.takeIf { rawTag ->
                    rawTag.localXmlTagName() == tagName && !rawTag.trimEnd().endsWith('/')
                }
            matchingTagDepth =
                when {
                    matchingTag == null -> matchingTagDepth
                    matchingTag.trimStart().startsWith('/') -> matchingTagDepth - 1
                    else -> matchingTagDepth + 1
                }
            if (matchingTagDepth == 0) closingTagStart = markup.start
            cursor = markup.endExclusive
        }
    }
    return closingTagStart
}

private data class XmlMarkup(
    val start: Int,
    val endExclusive: Int,
    val rawTag: String?,
)

private fun nextXmlMarkup(
    sourceXml: String,
    startIndex: Int,
): XmlMarkup? {
    val tagStart = sourceXml.indexOf('<', startIndex)
    return if (tagStart < 0) {
        null
    } else {
        val specialMarkupEnd = specialMarkupEnd(sourceXml, tagStart)
        if (specialMarkupEnd != null) {
            XmlMarkup(tagStart, specialMarkupEnd, rawTag = null)
        } else {
            val tagEnd = findTagEnd(sourceXml, tagStart)
            require(tagEnd != null) {
                throw RouteToolArgumentException(
                    R.string.gpx_the_gpx_contains_an_incomplete_xml_tag,
                    "The GPX contains an incomplete XML tag.",
                )
            }
            XmlMarkup(
                start = tagStart,
                endExclusive = tagEnd + 1,
                rawTag = sourceXml.substring(tagStart + 1, tagEnd),
            )
        }
    }
}

@Suppress("ReturnCount")
private fun XmlMarkup.directTitleClosingTagStart(
    sourceXml: String,
    openElements: ArrayDeque<String>,
): Int? {
    val tag = rawTag ?: return null
    if (tag.trimStart().startsWith('/')) {
        if (openElements.isNotEmpty()) openElements.removeLast()
        return null
    }

    val tagName = tag.localXmlTagName() ?: return null
    val isDirectTitleTag = tagName == "name" && openElements.lastOrNull() in TITLE_PARENT_TAGS
    if (!tag.trimEnd().endsWith('/')) openElements.addLast(tagName)
    if (!isDirectTitleTag) return null

    return requireNotNull(findMatchingClosingTag(sourceXml, endExclusive, tagName)) {
        throw RouteToolArgumentException(
            R.string.gpx_the_gpx_contains_an_incomplete_name_tag,
            "The GPX contains an incomplete name tag.",
        )
    }
}

private fun specialMarkupEnd(
    sourceXml: String,
    tagStart: Int,
): Int? =
    when {
        sourceXml.startsWith("<!--", tagStart) ->
            sourceXml.indexOf("-->", tagStart + 4).takeIf { it >= 0 }?.plus(3)
        sourceXml.startsWith("<![CDATA[", tagStart) ->
            sourceXml.indexOf("]]>", tagStart + 9).takeIf { it >= 0 }?.plus(3)
        sourceXml.startsWith("<?", tagStart) ->
            sourceXml.indexOf("?>", tagStart + 2).takeIf { it >= 0 }?.plus(2)
        else -> null
    }

private fun findTagEnd(
    sourceXml: String,
    tagStart: Int,
): Int? {
    var quote: Char? = null
    for (index in tagStart + 1 until sourceXml.length) {
        when (val character = sourceXml[index]) {
            '\'', '"' -> {
                quote = if (quote == character) null else quote ?: character
            }
            '>' -> if (quote == null) return index
        }
    }
    return null
}

private fun String.localXmlTagName(): String? {
    val normalized = trimStart().removePrefix("/").trimStart()
    val rawName = normalized.takeWhile { !it.isWhitespace() && it != '/' }
    return rawName.substringAfterLast(':').takeIf { it.isNotEmpty() }
}

private fun String.escapeXmlText(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private val TITLE_PARENT_TAGS = setOf("metadata", "trk")
