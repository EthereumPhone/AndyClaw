package org.ethereumphone.andyclaw.extensions

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Contract that JAR/DEX plugin extensions must implement.
 *
 * ## Lifecycle
 *
 * 1. The host instantiates the plugin via its **no-argument constructor**.
 * 2. [initialize] is called once with a [JarPluginContext].
 * 3. [functions] is read to discover the capabilities the plugin exposes.
 * 4. [execute] is called (possibly concurrently) for each agent invocation.
 * 5. [destroy] is called when the extension is unloaded. Release resources here.
 *
 * ## Packaging
 *
 * For JAR files, declare the implementing class in `META-INF/MANIFEST.MF`:
 * ```
 * Extension-Class: com.example.MyPlugin
 * Extension-Id:    com.example.myplugin
 * Extension-Name:  My Plugin
 * Extension-Version: 1
 * ```
 *
 * For standalone DEX files, the filename (without extension) is used as the
 * fully-qualified class name by convention (e.g. `com.example.MyPlugin.dex`).
 */
interface JarPlugin {

    /** Unique identifier. Convention: reverse-domain (e.g. "com.example.myplugin"). */
    val id: String

    /** Human-readable name. */
    val name: String

    /** Functions this plugin exposes to agents. */
    val functions: List<ExtensionFunction>

    /**
     * One-time initialization. Called on a background thread.
     *
     * @param context Sandboxed context providing a working directory and logging.
     */
    fun initialize(context: JarPluginContext)

    /**
     * Execute a named function with the given parameters.
     *
     * Implementations **must** be safe to call from any coroutine dispatcher.
     * Long-running work should be cooperative with cancellation.
     *
     * @param function One of the names declared in [functions].
     * @param params   JSON parameters matching the function's `inputSchema`.
     * @return The invocation result.
     */
    suspend fun execute(function: String, params: JsonObject): ExtensionResult

    /**
     * Teardown hook. Called when the plugin is being unloaded.
     * After this returns, no further calls will be made on this instance.
     */
    fun destroy()
}

/**
 * Sandboxed context provided to JAR plugins by the host.
 *
 * Plugins should only use the facilities exposed here rather than reaching
 * into the Android application context directly.
 *
 * @property workDir       Private directory the plugin can use for scratch files.
 * @property config        Read-only key/value configuration supplied by the host or user.
 * @property logger        Callback for structured logging (level: VERBOSE/DEBUG/INFO/WARN/ERROR).
 */
class JarPluginContext(
    val workDir: File,
    val config: Map<String, String> = emptyMap(),
    val logger: (level: String, message: String) -> Unit = { _, _ -> },
)
