package com.glancemap.glancemapwearos.data.repository.maps.theme

import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import com.glancemap.shared.transfer.MapsforgeThemePackage
import com.glancemap.shared.transfer.mapsforgeSvgStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class ThemePackageFailureReason {
    INVALID_PACKAGE,
    MISSING_RESOURCES,
    NO_XML,
    INSTALL_FAILED,
    REMOVE_FAILED,
    DAMAGED,
    TOO_LARGE,
    UNKNOWN,
}

class ThemePackageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    val reason: ThemePackageFailureReason =
        when (message) {
            "Not a valid Mapsforge theme package" -> ThemePackageFailureReason.INVALID_PACKAGE
            "Theme resources are missing" -> ThemePackageFailureReason.MISSING_RESOURCES
            "No render theme XML found" -> ThemePackageFailureReason.NO_XML
            "Theme could not be installed" -> ThemePackageFailureReason.INSTALL_FAILED
            "Theme files could not be removed" -> ThemePackageFailureReason.REMOVE_FAILED
            "Theme package is damaged" -> ThemePackageFailureReason.DAMAGED
            "Theme package is too large" -> ThemePackageFailureReason.TOO_LARGE
            else -> ThemePackageFailureReason.UNKNOWN
        }
}

// Installation and catalog helpers share the package bounds and atomic publication state.

/** Only the manifest publishes a revision. Old renderer resources survive until the next process start. */
@Suppress("TooManyFunctions")
class ExternalThemeStore(
    private val root: File,
    private val validateTheme: (RenderThemeSource.External) -> Unit = {},
    private val diagnostic: (String, String) -> Unit = { _, _ -> },
) {
    private val mutableRevision = MutableStateFlow(0L)
    val changes = mutableRevision.asStateFlow()
    val transferDirectory: File get() = File(root, ".transfers")

    init {
        // This singleton is created before any external theme is rendered in this process.
        File(root, ".staging").deleteRecursively()
        transferDirectory.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { it.delete() }
        packageDirectories().forEach { directory ->
            val revision = readManifest(directory)?.getProperty("revision")
            File(directory, "versions").listFiles()?.filter { it.name != revision }?.forEach { it.deleteRecursively() }
        }
    }

    @Synchronized
    fun themes(): List<RenderThemeSource.External> =
        packageDirectories()
            .flatMap { directory ->
                val manifest = readManifest(directory) ?: return@flatMap emptyList()
                val revision = manifest.getProperty("revision") ?: return@flatMap emptyList()
                if (!HASH.matches(revision)) return@flatMap emptyList()
                val resourceRoot = File(directory, "versions/$revision")
                val count =
                    manifest.getProperty("count")?.toIntOrNull()?.takeIf { it in 1..MapsforgeThemePackage.MAX_ENTRIES }
                        ?: return@flatMap emptyList()
                (0 until count).mapNotNull { index ->
                    val path = manifest.getProperty("path.$index") ?: return@mapNotNull null
                    if (!MapsforgeThemePackage.validEntryPath(path)) return@mapNotNull null
                    val xml = File(resourceRoot, path)
                    if (!xml.isFile || !inside(resourceRoot, xml)) return@mapNotNull null
                    theme(directory.name, revision, resourceRoot, path, manifest.getProperty("title.$index"))
                }
            }.sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.id }))

    fun finishTransfer(
        transferName: String,
        checkCancelled: () -> Unit = {},
    ) {
        if (!MapsforgeThemePackage.isTransferName(transferName)) return
        require(File(transferName).name == transferName && !transferName.contains('\\'))
        diagnostic("transfer_completed", packageId(transferName))
        val archive = File(transferDirectory, transferName)
        try {
            install(archive, transferName, checkCancelled)
        } finally {
            archive.delete()
            File(transferDirectory, ".$transferName.part").delete()
        }
    }

    // Archive and renderer failures need safe messages; cancellation must keep its identity.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    @Synchronized
    fun install(
        archive: File,
        transferName: String,
        checkCancelled: () -> Unit = {},
    ): List<RenderThemeSource.External> {
        val packageId = packageId(transferName)
        diagnostic("import_requested", packageId)
        if (archive.length() > MapsforgeThemePackage.MAX_ARCHIVE_BYTES) tooLarge()
        val staging = File(root, ".staging/${UUID.randomUUID()}")
        try {
            check(staging.mkdirs())
            extract(archive, staging, checkCancelled)
            diagnostic("archive_validated", packageId)
            val revision = archiveRevision(archive, checkCancelled)
            val discovered = discover(packageId, revision, staging)
            if (discovered.size > MapsforgeThemePackage.MAX_RENDER_THEMES) tooLarge()
            if (discovered.isEmpty()) throw ThemePackageException("No render theme XML found")
            discovered.forEach { candidate ->
                checkCancelled()
                validateResources(candidate)
                validateTheme(candidate)
                diagnostic("theme_discovered", candidate.id)
            }
            checkCancelled()
            publishRevision(packageId, revision, staging, discovered)
            mutableRevision.value += 1L
            return themes().filter { it.packageId == packageId }
        } catch (error: ThemePackageException) {
            throw error
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ThemePackageException("Theme could not be installed", error)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun archiveRevision(
        archive: File,
        checkCancelled: () -> Unit,
    ): String =
        archive.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }

    private fun publishRevision(
        packageId: String,
        revision: String,
        staging: File,
        discovered: List<RenderThemeSource.External>,
    ) {
        val directory = File(root, packageId)
        val versions = File(directory, "versions")
        check(versions.isDirectory || versions.mkdirs())
        val destination = File(versions, revision)
        if (!destination.exists()) {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        val manifest =
            Properties().apply {
                setProperty("revision", revision)
                setProperty("count", discovered.size.toString())
                discovered.forEachIndexed { index, item ->
                    setProperty("path.$index", item.xmlPath)
                    setProperty("title.$index", item.displayName)
                }
            }
        val installed = File(directory, MANIFEST)
        val replacing = installed.exists()
        val pending = File(directory, ".$MANIFEST.tmp")
        FileOutputStream(pending).use { output ->
            manifest.store(output, null)
            output.fd.sync()
        }
        Files.move(
            pending.toPath(),
            installed.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        diagnostic(if (replacing) "theme_replaced" else "theme_installed", packageId)
    }

    @Synchronized
    fun deletePackage(packageId: String) {
        require(HASH.matches(packageId))
        val directory = File(root, packageId)
        Files.deleteIfExists(File(directory, MANIFEST).toPath())
        mutableRevision.value += 1L
        diagnostic("theme_deleted", packageId)
        if (!directory.deleteRecursively()) throw ThemePackageException("Theme files could not be removed")
    }

    private fun extract(
        archive: File,
        staging: File,
        checkCancelled: () -> Unit,
    ) {
        try {
            ZipFile(archive).use { zip ->
                validateArchiveBounds(zip)
                var total = 0L
                val names = hashSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    val target = entryTarget(staging, entry.name, names)
                    total +=
                        extractEntry(
                            zip,
                            entry,
                            target,
                            MapsforgeThemePackage.MAX_EXPANDED_BYTES - total,
                            checkCancelled,
                        )
                }
            }
        } catch (error: java.util.zip.ZipException) {
            throw ThemePackageException("Theme package is damaged", error)
        }
    }

    private fun validateArchiveBounds(zip: ZipFile) {
        if (zip.size() > MapsforgeThemePackage.MAX_ENTRIES) tooLarge()
        var total = 0L
        zip.entries().asSequence().forEach { entry ->
            if (entry.size < 0L) damaged()
            if (entry.size > MapsforgeThemePackage.MAX_ENTRY_BYTES) tooLarge()
            total += entry.size
            if (total > MapsforgeThemePackage.MAX_EXPANDED_BYTES) tooLarge()
        }
    }

    private fun entryTarget(
        staging: File,
        path: String,
        names: MutableSet<String>,
    ): File {
        if (!MapsforgeThemePackage.validEntryPath(path) || !names.add(path.removeSuffix("/"))) invalidPackage()
        val target = File(staging, path).canonicalFile
        if (!inside(staging, target)) invalidPackage()
        return target
    }

    private fun extractEntry(
        zip: ZipFile,
        entry: ZipEntry,
        target: File,
        remaining: Long,
        checkCancelled: () -> Unit,
    ): Long {
        if (entry.isDirectory) {
            if (entry.size > 0L || (!target.isDirectory && !target.mkdirs())) damaged()
            return 0L
        }
        if (target.exists() || target.parentFile?.let { it.isDirectory || it.mkdirs() } != true) damaged()
        return zip.getInputStream(entry).use { input ->
            FileOutputStream(target).use { output ->
                val size = copyVerifiedEntry(input, output, entry, remaining, checkCancelled)
                output.fd.sync()
                size
            }
        }
    }

    private fun copyVerifiedEntry(
        input: InputStream,
        output: OutputStream,
        entry: ZipEntry,
        remaining: Long,
        checkCancelled: () -> Unit,
    ): Long {
        var size = 0L
        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            if (size > MapsforgeThemePackage.MAX_ENTRY_BYTES || size > remaining) tooLarge()
            output.write(buffer, 0, read)
            crc.update(buffer, 0, read)
        }
        if (size != entry.size || crc.value != entry.crc) damaged()
        return size
    }

    private fun discover(
        packageId: String,
        revision: String,
        staging: File,
    ): List<RenderThemeSource.External> =
        staging
            .walkTopDown()
            .onEnter { it.name != "__MACOSX" }
            .filter {
                it.isFile &&
                    it.extension.equals("xml", true) &&
                    !it.name.startsWith("._")
            }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }
            .mapNotNull { xml ->
                val info = readXmlInfo(xml) ?: return@mapNotNull null
                theme(packageId, revision, staging, xml.relativeTo(staging).invariantSeparatorsPath, info.title)
            }.toList()

    // SAX implementations report malformed XML with different checked/runtime exceptions.
    @Suppress("TooGenericExceptionCaught")
    private fun readXmlInfo(file: File): MapsforgeThemePackage.XmlInfo? {
        if (file.length() > MapsforgeThemePackage.MAX_XML_BYTES) tooLarge()
        return try {
            if (file.extension.equals("svg", ignoreCase = true)) {
                val input = file.inputStream().use { mapsforgeSvgStream(it) }
                input.use { MapsforgeThemePackage.inspectXml(it) }
            } else {
                file.inputStream().use { MapsforgeThemePackage.inspectXml(it) }
            }
        } catch (error: Exception) {
            throw ThemePackageException("Theme package is damaged", error)
        }
    }

    private fun validateResources(theme: RenderThemeSource.External) {
        val info = theme.xmlFile.inputStream().use { MapsforgeThemePackage.inspectXml(it) } ?: damaged()
        info.resourceSources.forEach { source ->
            val resource =
                try {
                    File(theme.xmlFile.parentFile, MapsforgeThemePackage.resourceFileName(source)).canonicalFile
                } catch (error: IllegalStateException) {
                    throw ThemePackageException("Not a valid Mapsforge theme package", error)
                }
            if (!inside(theme.resourceRoot, resource)) invalidPackage()
            if (!resource.isFile) throw ThemePackageException("Theme resources are missing")
            // Check the same declaration-free SVG stream that the native decoder receives.
            if (resource.extension.equals("svg", ignoreCase = true)) readXmlInfo(resource)
        }
    }

    private fun theme(
        packageId: String,
        revision: String,
        resourceRoot: File,
        path: String,
        title: String?,
    ) = RenderThemeSource.External(
        id = "external:$packageId:${hash(path).take(16)}",
        packageId = packageId,
        displayName =
            title?.take(100) ?: File(path)
                .nameWithoutExtension
                .replace('_', ' ')
                .take(100),
        xmlPath = path,
        resourceRoot = resourceRoot,
        revision = revision,
    )

    private fun packageDirectories(): List<File> =
        root
            .listFiles()
            ?.filter {
                it.isDirectory && HASH.matches(it.name)
            }.orEmpty()

    private fun readManifest(directory: File): Properties? =
        runCatching {
            File(directory, MANIFEST).inputStream().use { input ->
                Properties().apply { load(input) }
            }
        }.getOrNull()

    companion object {
        private const val MANIFEST = "installed.properties"
        private val HASH = Regex("[0-9a-f]{64}")

        fun packageId(transferName: String): String = hash(transferName.lowercase(Locale.ROOT))

        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(value.toByteArray(Charsets.UTF_8)).toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun inside(
            root: File,
            file: File,
        ): Boolean =
            file.canonicalPath.startsWith(
                root.canonicalPath + File.separator,
            )

        private fun tooLarge(): Nothing = throw ThemePackageException("Theme package is too large")

        private fun invalidPackage(): Nothing = throw ThemePackageException("Not a valid Mapsforge theme package")

        private fun damaged(): Nothing = throw ThemePackageException("Theme package is damaged")
    }
}
