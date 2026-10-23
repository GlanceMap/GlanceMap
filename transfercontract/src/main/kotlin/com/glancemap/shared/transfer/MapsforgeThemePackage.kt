package com.glancemap.shared.transfer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** The distinct suffix is type metadata in the existing filename-based transfer protocol. */
object MapsforgeThemePackage {
    const val TRANSFER_SUFFIX = ".mapsforge-theme.zip"
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    const val MAX_XML_BYTES = 8L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_XML_DEPTH = 64
    const val MAX_PATH_LENGTH = 512
    const val MAX_RENDER_THEMES = 64
    const val INSTALL_ACK_TIMEOUT_MS = 180_000L

    fun isTransferName(name: String): Boolean = name.endsWith(TRANSFER_SUFFIX, ignoreCase = true)

    fun transferName(name: String): String = if (isTransferName(name)) name else name.dropLast(4) + TRANSFER_SUFFIX

    fun isThemeZipCandidate(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) &&
            !name.endsWith(".hgt.zip", ignoreCase = true)

    fun validEntryPath(path: String): Boolean {
        val parts = path.removeSuffix("/").split('/')
        return path.isNotBlank() &&
            path.length <= MAX_PATH_LENGTH &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            !path.contains(':') &&
            path.none { it.code < 32 } &&
            parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Bounded phone preflight only. The watch revalidates and installs the complete package. */
    fun probe(input: InputStream): Boolean =
        runCatching {
            var total = 0L
            var count = 0
            var found = false
            ZipInputStream(ArchiveSizeLimitedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++count <= MAX_ENTRIES && validEntryPath(entry.name))
                    var bytes = 0L
                    val xml = entry.name.endsWith(".xml", true)
                    val output = if (xml) ByteArrayOutputStream() else null
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        bytes += read
                        total += read
                        check(bytes <= MAX_ENTRY_BYTES && total <= MAX_EXPANDED_BYTES)
                        check(!entry.isDirectory || bytes == 0L)
                        if (xml) {
                            check(bytes <= MAX_XML_BYTES)
                            output?.write(buffer, 0, read)
                        }
                    }
                    if (isRenderThemeXml(output)) found = true
                    zip.closeEntry()
                }
            }
            found
        }.getOrDefault(false)

    private fun isRenderThemeXml(output: ByteArrayOutputStream?): Boolean =
        output?.let {
            runCatching { inspectXml(ByteArrayInputStream(it.toByteArray())) }.getOrNull() != null
        } ?: false

    data class XmlInfo(
        val title: String?,
        val resourceSources: Set<String>,
    )

    /** Reject declarations before parsing, including internal entity expansion. */
    fun inspectXml(input: InputStream): XmlInfo? {
        val bytes = input.readBounded(MAX_XML_BYTES)
        val xml = bytes.toString(themeXmlCharset(bytes)).removePrefix("\uFEFF")
        checkXmlDeclarations(xml)
        var depth = 0
        var renderTheme = false
        var title: String? = null
        val sources = linkedSetOf<String>()
        val handler =
            object : DefaultHandler() {
                override fun resolveEntity(
                    publicId: String?,
                    systemId: String?,
                ): InputSource = throw SAXException("Entities are disabled")

                override fun startElement(
                    uri: String,
                    localName: String,
                    qName: String,
                    attributes: Attributes,
                ) {
                    check(++depth <= MAX_XML_DEPTH)
                    if (depth == 1) {
                        renderTheme =
                            localName == "rendertheme" &&
                            (uri.isEmpty() || uri == "http://mapsforge.org/renderTheme")
                        title = attributes.getValue("title")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    attributes.getValue("src")?.takeIf { it.isNotBlank() }?.let { sources += it }
                }

                override fun endElement(
                    uri: String,
                    localName: String,
                    qName: String,
                ) {
                    depth--
                }
            }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return if (renderTheme) XmlInfo(title, sources) else null
    }

    fun resourceFileName(source: String): String {
        val path = source.removePrefix("file:")
        check(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':'))
        check(path.none { it.code < 32 })
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        check(extension in setOf("svg", "png", "jpg", "jpeg", "webp"))
        return path
    }

    fun boundedArchiveStream(
        input: InputStream,
        offset: Long = 0L,
    ): InputStream = ArchiveSizeLimitedInputStream(input, offset)

    private class ArchiveSizeLimitedInputStream(
        input: InputStream,
        offset: Long = 0L,
    ) : FilterInputStream(input) {
        private var bytes = offset

        override fun read(): Int = super.read().also { if (it >= 0) record(1) }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = `in`.read(buffer, offset, length).also { if (it > 0) record(it) }

        private fun record(count: Int) {
            bytes += count
            check(bytes <= MAX_ARCHIVE_BYTES) { "Theme package is too large" }
        }
    }
}

/** Ignore the standard SVG 1.1 public declaration without ever loading its DTD or allowing entities. */
fun mapsforgeSvgStream(input: InputStream): InputStream {
    val bytes = input.readBounded(MapsforgeThemePackage.MAX_XML_BYTES)
    val charset = themeXmlCharset(bytes)
    val svg = bytes.toString(charset).replace(STANDARD_SVG_DOCTYPE, "")
    checkXmlDeclarations(svg)
    return svg.toByteArray(charset).inputStream()
}

private val STANDARD_SVG_DOCTYPE =
    Regex(
        """<!DOCTYPE\s+svg\s+PUBLIC\s+(["'])-//W3C//DTD SVG 1\.1//EN\1\s+""" +
            """(["'])https?://www\.w3\.org/Graphics/SVG/1\.1/DTD/svg11\.dtd\2\s*>""",
    )

private fun checkXmlDeclarations(xml: String) {
    check(!xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY") && !xml.contains('\u0000'))
}

private fun InputStream.readBounded(maximum: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) return output.toByteArray()
        check(output.size().toLong() + read <= maximum)
        output.write(buffer, 0, read)
    }
}

private fun themeXmlCharset(bytes: ByteArray): java.nio.charset.Charset =
    when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
        bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
        bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
