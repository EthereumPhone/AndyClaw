package org.ethereumphone.andyclaw.extensions.discovery

import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionType
import java.io.File
import java.util.jar.JarFile

/**
 * Discovers JAR/DEX plugin extensions from designated directories.
 *
 * ## JAR manifest convention
 *
 * Extension JARs must include a `META-INF/MANIFEST.MF` with at least:
 *
 * ```
 * Extension-Class:   com.example.MyPlugin
 * Extension-Id:      com.example.myplugin
 * Extension-Name:    My Plugin          (optional, defaults to id)
 * Extension-Version: 1                  (optional, defaults to 1)
 * ```
 *
 * `Extension-Class` must be the fully-qualified name of a class implementing
 * [org.ethereumphone.andyclaw.extensions.JarPlugin].
 *
 * ## DEX file convention
 *
 * Standalone `.dex` files use a filename convention: the filename (without
 * extension) is treated as the fully-qualified plugin class name.
 * Example: `com.example.MyPlugin.dex`.
 *
 * ## Note on functions
 *
 * Descriptors returned by the scanner have an **empty** function list.
 * Functions are populated when the [JarExtensionLoader] actually loads and
 * initializes the plugin.
 */
class JarExtensionScanner {

    companion object {
        const val ATTR_EXTENSION_CLASS = "Extension-Class"
        const val ATTR_EXTENSION_ID = "Extension-Id"
        const val ATTR_EXTENSION_NAME = "Extension-Name"
        const val ATTR_EXTENSION_VERSION = "Extension-Version"

        /** File extensions we recognise as loadable plugins. */
        private val SUPPORTED_EXTENSIONS = setOf("jar", "dex")
    }

    /**
     * Scan the given directories for JAR/DEX extension files.
     *
     * @param extensionDirs Directories to scan (non-recursive).
     * @return Descriptors for every valid extension file found.
     */
    fun scan(extensionDirs: List<File>): List<ExtensionDescriptor> {
        val results = mutableListOf<ExtensionDescriptor>()

        for (dir in extensionDirs) {
            if (!dir.isDirectory) continue

            val files = dir.listFiles { f ->
                f.isFile && f.extension in SUPPORTED_EXTENSIONS
            } ?: continue

            for (file in files) {
                try {
                    val descriptor = inspectFile(file) ?: continue
                    results.add(descriptor)
                } catch (_: Exception) {
                    // Skip malformed files
                }
            }
        }

        return results
    }

    // ── Inspection ───────────────────────────────────────────────────

    private fun inspectFile(file: File): ExtensionDescriptor? {
        return when (file.extension) {
            "jar" -> inspectJar(file)
            "dex" -> inspectDex(file)
            else -> null
        }
    }

    /**
     * Parse the JAR manifest for extension metadata.
     */
    private fun inspectJar(file: File): ExtensionDescriptor? {
        return try {
            JarFile(file).use { jar ->
                val manifest = jar.manifest ?: return null
                val attrs = manifest.mainAttributes

                // Extension-Class is mandatory
                attrs.getValue(ATTR_EXTENSION_CLASS) ?: return null

                val id = attrs.getValue(ATTR_EXTENSION_ID)
                    ?: "jar:${file.nameWithoutExtension}"
                val name = attrs.getValue(ATTR_EXTENSION_NAME) ?: id
                val version = attrs.getValue(ATTR_EXTENSION_VERSION)?.toIntOrNull() ?: 1

                ExtensionDescriptor(
                    id = id,
                    name = name,
                    type = ExtensionType.JAR,
                    version = version,
                    jarPath = file.absolutePath,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * DEX files use filename-as-class convention.
     */
    private fun inspectDex(file: File): ExtensionDescriptor {
        val className = file.nameWithoutExtension
        return ExtensionDescriptor(
            id = "jar:$className",
            name = className.substringAfterLast('.'),
            type = ExtensionType.JAR,
            jarPath = file.absolutePath,
        )
    }
}
