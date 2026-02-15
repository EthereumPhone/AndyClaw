package org.ethereumphone.andyclaw.extensions.execution

import dalvik.system.DexClassLoader
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionResult
import org.ethereumphone.andyclaw.extensions.JarPlugin
import org.ethereumphone.andyclaw.extensions.JarPluginContext
import org.ethereumphone.andyclaw.extensions.discovery.JarExtensionScanner
import java.io.File
import java.util.jar.JarFile

/**
 * Loads and manages JAR/DEX plugin extensions at runtime.
 *
 * Uses Android's [DexClassLoader] to load DEX bytecode from extension files.
 * Each loaded plugin goes through a full lifecycle:
 *
 * 1. **Load** — The DEX is loaded and the plugin class instantiated.
 * 2. **Initialize** — [JarPlugin.initialize] is called with a sandboxed [JarPluginContext].
 * 3. **Execute** — [JarPlugin.execute] is called for each invocation.
 * 4. **Unload** — [JarPlugin.destroy] is called and references are released.
 *
 * ## Thread safety
 *
 * Loading and unloading are **not** thread-safe — callers (typically [ExtensionEngine])
 * should serialize discovery/load operations. [execute] is safe for concurrent use
 * from multiple coroutines.
 *
 * @param cacheDir Directory for optimized DEX output (e.g. `context.cacheDir/extension_dex`).
 * @param workDir  Parent directory for per-plugin working directories.
 */
class JarExtensionLoader(
    private val cacheDir: File,
    private val workDir: File,
) {

    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

    private data class LoadedPlugin(
        val descriptor: ExtensionDescriptor,
        val plugin: JarPlugin,
        val classLoader: ClassLoader,
    )

    // ── Loading ──────────────────────────────────────────────────────

    /**
     * Load and initialize a JAR/DEX extension.
     *
     * @param descriptor Scanner-produced descriptor (must have [ExtensionDescriptor.jarPath]).
     * @return An enriched descriptor with [JarPlugin.functions] populated, or `null` on failure.
     */
    fun load(descriptor: ExtensionDescriptor): ExtensionDescriptor? {
        val jarPath = descriptor.jarPath ?: return null
        val file = File(jarPath)
        if (!file.exists()) return null

        return try {
            val className = resolvePluginClass(file) ?: return null

            // Each extension gets its own optimized DEX directory
            val dexOutputDir = File(cacheDir, "ext_${descriptor.id.hashCode()}")
            dexOutputDir.mkdirs()

            val classLoader = DexClassLoader(
                file.absolutePath,
                dexOutputDir.absolutePath,
                null, // no native library path
                javaClass.classLoader,
            )

            val pluginClass = classLoader.loadClass(className)
            val plugin = pluginClass.getDeclaredConstructor().newInstance() as? JarPlugin
                ?: return null

            // Create a sandboxed work directory for this plugin
            val pluginWorkDir = File(
                workDir,
                descriptor.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            )
            pluginWorkDir.mkdirs()

            val pluginContext = JarPluginContext(
                workDir = pluginWorkDir,
                logger = { level, msg ->
                    android.util.Log.println(
                        when (level.uppercase()) {
                            "VERBOSE" -> android.util.Log.VERBOSE
                            "DEBUG" -> android.util.Log.DEBUG
                            "WARN" -> android.util.Log.WARN
                            "ERROR" -> android.util.Log.ERROR
                            else -> android.util.Log.INFO
                        },
                        "ExtPlugin:${descriptor.id}",
                        msg,
                    )
                },
            )

            plugin.initialize(pluginContext)

            // Build enriched descriptor with discovered functions
            val enriched = descriptor.copy(
                name = plugin.name,
                functions = plugin.functions,
            )

            loadedPlugins[descriptor.id] = LoadedPlugin(
                descriptor = enriched,
                plugin = plugin,
                classLoader = classLoader,
            )

            enriched
        } catch (e: Exception) {
            android.util.Log.e(
                "JarExtensionLoader",
                "Failed to load plugin ${descriptor.id}: ${e.message}",
                e,
            )
            null
        }
    }

    // ── Execution ────────────────────────────────────────────────────

    /**
     * Execute a function on a loaded JAR plugin.
     *
     * @param extensionId Must match a previously loaded extension.
     * @param function    Function name to invoke.
     * @param params      JSON parameters.
     * @param timeoutMs   Maximum execution time.
     * @return The plugin's result, or an error/timeout.
     */
    suspend fun execute(
        extensionId: String,
        function: String,
        params: JsonObject,
        timeoutMs: Long,
    ): ExtensionResult {
        val loaded = loadedPlugins[extensionId]
            ?: return ExtensionResult.Error("JAR extension not loaded: $extensionId")

        return try {
            withTimeout(timeoutMs) {
                loaded.plugin.execute(function, params)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ExtensionResult.Timeout(timeoutMs)
        } catch (e: Exception) {
            ExtensionResult.Error("Plugin execution failed: ${e.message}", e)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Unload a specific plugin, calling its [JarPlugin.destroy] hook.
     */
    fun unload(extensionId: String) {
        loadedPlugins.remove(extensionId)?.let { loaded ->
            try {
                loaded.plugin.destroy()
            } catch (e: Exception) {
                android.util.Log.w(
                    "JarExtensionLoader",
                    "Plugin ${extensionId} threw during destroy: ${e.message}",
                )
            }
        }
    }

    /** Unload all plugins. */
    fun unloadAll() {
        loadedPlugins.keys.toList().forEach { unload(it) }
    }

    /** Check whether a plugin is currently loaded. */
    fun isLoaded(extensionId: String): Boolean = extensionId in loadedPlugins

    /** Get the live plugin instance (for advanced introspection). */
    fun getPlugin(extensionId: String): JarPlugin? = loadedPlugins[extensionId]?.plugin

    // ── Class resolution ─────────────────────────────────────────────

    /**
     * Resolve the plugin entry class from the JAR manifest or DEX filename.
     */
    private fun resolvePluginClass(file: File): String? {
        if (file.extension == "dex") {
            // Convention: filename (without .dex) = fully-qualified class name
            return file.nameWithoutExtension
        }

        return try {
            JarFile(file).use { jar ->
                jar.manifest?.mainAttributes?.getValue(
                    JarExtensionScanner.ATTR_EXTENSION_CLASS
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
