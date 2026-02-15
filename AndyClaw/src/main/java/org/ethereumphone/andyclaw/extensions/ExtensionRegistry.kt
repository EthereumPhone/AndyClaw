package org.ethereumphone.andyclaw.extensions

/**
 * Central registry of discovered extensions and their capabilities.
 *
 * Maintains a bidirectional index:
 * - Extension ID → [ExtensionDescriptor]
 * - Function name → Extension ID
 *
 * All public methods are `@Synchronized` for thread-safe access from
 * discovery (background) and execution (coroutine) contexts.
 */
class ExtensionRegistry {

    /** Registered extensions keyed by their unique ID. */
    private val extensions = mutableMapOf<String, ExtensionDescriptor>()

    /** Reverse index: function name → extension ID that provides it. */
    private val functionIndex = mutableMapOf<String, String>()

    // ── Registration ─────────────────────────────────────────────────

    /**
     * Register an extension and index its functions.
     *
     * If an extension with the same ID already exists, it is replaced and
     * its old function index entries are cleaned up.
     */
    @Synchronized
    fun register(descriptor: ExtensionDescriptor) {
        // Clean up stale function entries from a previous version
        extensions[descriptor.id]?.functions?.forEach { fn ->
            functionIndex.remove(fn.name)
        }

        extensions[descriptor.id] = descriptor

        for (fn in descriptor.functions) {
            functionIndex[fn.name] = descriptor.id
        }
    }

    /**
     * Unregister an extension and remove its function index entries.
     */
    @Synchronized
    fun unregister(extensionId: String) {
        extensions.remove(extensionId)?.functions?.forEach { fn ->
            functionIndex.remove(fn.name)
        }
    }

    // ── Lookups ──────────────────────────────────────────────────────

    /** Get an extension by its unique ID. */
    @Synchronized
    fun getExtension(extensionId: String): ExtensionDescriptor? =
        extensions[extensionId]

    /** Find which extension provides a given function name. */
    @Synchronized
    fun findExtensionForFunction(functionName: String): ExtensionDescriptor? {
        val extensionId = functionIndex[functionName] ?: return null
        return extensions[extensionId]
    }

    /** Get a snapshot of all registered extensions. */
    @Synchronized
    fun getAll(): List<ExtensionDescriptor> =
        extensions.values.toList()

    /** Get all (extension, function) pairs across all registered extensions. */
    @Synchronized
    fun getAllFunctions(): List<Pair<ExtensionDescriptor, ExtensionFunction>> =
        extensions.values.flatMap { ext ->
            ext.functions.map { fn -> ext to fn }
        }

    /** Get functions exposed by a specific extension. */
    @Synchronized
    fun getFunctions(extensionId: String): List<ExtensionFunction> =
        extensions[extensionId]?.functions ?: emptyList()

    /** Check whether any extension exposes a function with this name. */
    @Synchronized
    fun hasFunction(functionName: String): Boolean =
        functionName in functionIndex

    // ── Housekeeping ─────────────────────────────────────────────────

    /** Number of registered extensions. */
    @Synchronized
    fun size(): Int = extensions.size

    /** Remove all registered extensions. */
    @Synchronized
    fun clear() {
        extensions.clear()
        functionIndex.clear()
    }
}
